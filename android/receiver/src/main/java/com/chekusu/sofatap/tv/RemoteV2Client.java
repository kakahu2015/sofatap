package com.chekusu.sofatap.tv;

import okhttp3.tls.HeldCertificate;
import javax.net.ssl.*;
import java.io.*;
import java.net.*;
import java.security.*;
import java.security.cert.*;
import java.security.interfaces.RSAPublicKey;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.function.BooleanSupplier;

/** Local-only TV remote adapter. All sockets, including TLS close_notify, stay off Android main.
 * Pairing temporarily validates an RSA peer and proves its key using the user-entered TV code.
 * Only the exact persisted peer certificate is accepted by the control channel. */
final class RemoteV2Client implements AutoCloseable {
    interface Identity { HeldCertificate client(); byte[] pin(); void savePin(byte[] pin) throws Exception; }
    private final Identity identity;
    private final int pairingPort, controlPort;
    private final ExecutorService worker = new ThreadPoolExecutor(1, 1, 0, TimeUnit.MILLISECONDS, new ArrayBlockingQueue<>(2), r -> new Thread(r, "Sofatap-TV-Remote"));
    private final ScheduledExecutorService cleanup = Executors.newSingleThreadScheduledExecutor(r -> new Thread(r, "Sofatap-TV-Remote-Cleanup"));
    private final ArrayBlockingQueue<KeyRequest> keys = new ArrayBlockingQueue<>(16);
    private final ArrayBlockingQueue<String> codes = new ArrayBlockingQueue<>(1);
    private final AtomicLong generation = new AtomicLong();
    private final AtomicBoolean running = new AtomicBoolean();
    private final AtomicReference<Socket> socket = new AtomicReference<>();
    private volatile boolean closed, requirePairing;
    private volatile String status;
    private volatile long nextConnect;
    private record KeyRequest(String key, BooleanSupplier current, long generation, long deadline, CompletableFuture<Boolean> done) { }
    RemoteV2Client(Identity identity) { this(identity, 6467, 6466); }
    // Package-private injectable ports exist only for loopback TLS fixtures. No network host is accepted.
    RemoteV2Client(Identity identity, int pairingPort, int controlPort) {
        this.identity = identity; this.pairingPort = pairingPort; this.controlPort = controlPort;
        requirePairing = identity.pin() == null; status = requirePairing ? "pairing_required" : "unavailable";
    }
    String status() { return status; }
    synchronized void ensureConnected() {
        if (closed || requirePairing || identity.pin() == null || System.nanoTime() < nextConnect || !running.compareAndSet(false, true)) return;
        long epoch = generation.get();
        try { worker.execute(() -> runControl(epoch, () -> true)); } catch (RejectedExecutionException full) { running.set(false); }
    }
    synchronized String startPairing(BooleanSupplier current) {
        if (closed || !current.getAsBoolean()) return "session_ended";
        if (status.equals("pairing")) return "pairing_in_progress";
        // A preceding cancelled worker can still be finishing its close; this bounded queue holds one replacement.
        invalidate(); long epoch = generation.get(); running.set(true); status = "pairing"; requirePairing = true;
        try { worker.execute(() -> pair(epoch, current)); return "ok"; } catch (RejectedExecutionException full) { status = "unavailable"; running.set(false); return "remote_busy"; }
    }
    synchronized String submitCode(String code, BooleanSupplier current) {
        if (closed || !current.getAsBoolean()) return "session_ended";
        if (!status.equals("pairing")) return "pairing_required";
        if (code == null || !code.matches("[0-9A-Fa-f]{6}")) return "invalid_pair_code";
        return codes.offer(code) ? "ok" : "pairing_code_pending";
    }
    String key(String key, BooleanSupplier current) {
        KeyRequest request;
        synchronized (this) {
            // Queue ownership is captured atomically with readiness. A key from
            // an old connection cannot enter the replacement pairing generation.
            if (!current.getAsBoolean() || closed) return "session_ended";
            if (!status.equals("ready")) return "remote_unavailable";
            long epoch = generation.get();
            request = new KeyRequest(key, current, epoch, System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(1400), new CompletableFuture<>());
            if (!keys.offer(request)) return "remote_busy";
        }
        try { return request.done.get(1600, TimeUnit.MILLISECONDS) ? "ok" : "session_ended"; }
        catch (Exception failure) { request.done.cancel(false); keys.remove(request); return "remote_action_uncertain"; }
    }
    /** Immediately invalidates queued work. Closing an SSL socket is delegated to the cleanup thread. */
    synchronized void disconnected() {
        invalidate(); status = requirePairing ? "pairing_required" : "unavailable";
    }
    private void invalidate() {
        generation.incrementAndGet(); running.set(false); codes.clear();
        KeyRequest pending; while ((pending = keys.poll()) != null) pending.done.complete(false);
        Socket old = socket.getAndSet(null); if (old != null) closeLater(old);
    }
    private boolean current(long epoch, BooleanSupplier current) { return !closed && generation.get() == epoch && current.getAsBoolean(); }
    private void pair(long epoch, BooleanSupplier current) {
        SSLSocket tls = null;
        try {
            if (!current(epoch, current)) return;
            tls = connect(pairingPort, null, epoch); tls.setSoTimeout(2500);
            OutputStream out = tls.getOutputStream(); InputStream in = tls.getInputStream();
            final Socket pairingSocket = tls;
            ScheduledFuture<?> pairingDeadline = cleanup.schedule(() -> closeNow(pairingSocket), 9, TimeUnit.SECONDS);
            try {
            write(tls, out, RemoteV2Codec.pairing(10, RemoteV2Codec.join(RemoteV2Codec.text(1, "atvremote"), RemoteV2Codec.text(2, "Sofatap"))));
            RemoteV2Codec.expectPairing(RemoteV2Codec.read(in), 11);
            byte[] encoding = RemoteV2Codec.join(RemoteV2Codec.number(1, 3), RemoteV2Codec.number(2, 6));
            write(tls, out, RemoteV2Codec.pairing(20, RemoteV2Codec.join(RemoteV2Codec.bytes(1, encoding), RemoteV2Codec.number(3, 1))));
            RemoteV2Codec.expectPairing(RemoteV2Codec.read(in), 20);
            write(tls, out, RemoteV2Codec.pairing(30, RemoteV2Codec.join(RemoteV2Codec.bytes(1, encoding), RemoteV2Codec.number(2, 1))));
            RemoteV2Codec.expectPairing(RemoteV2Codec.read(in), 31);
            } finally { pairingDeadline.cancel(false); }
            String code = null; long deadline = System.nanoTime() + TimeUnit.MINUTES.toNanos(2);
            while (current(epoch, current) && System.nanoTime() < deadline && code == null) code = codes.poll(100, TimeUnit.MILLISECONDS);
            if (code == null || !current(epoch, current)) return;
            X509Certificate peer = (X509Certificate)tls.getSession().getPeerCertificates()[0];
            byte[] secret = RemoteV2Codec.secret(identity.client().certificate(), peer, code);
            if (!current(epoch, current)) return;
            write(tls, out, RemoteV2Codec.pairing(40, RemoteV2Codec.bytes(1, secret)));
            ScheduledFuture<?> proofDeadline = cleanup.schedule(() -> closeNow(pairingSocket), 2500, TimeUnit.MILLISECONDS);
            try { RemoteV2Codec.expectPairing(RemoteV2Codec.read(in), 41); } finally { proofDeadline.cancel(false); }
            if (!current(epoch, current)) return;
            identity.savePin(peer.getEncoded());
            if (!current(epoch, current)) return;
            synchronized (this) { if (!current(epoch, current)) return; requirePairing = false; }
            socket.compareAndSet(tls, null); closeNow(tls); tls = null;
            runControl(epoch, current); return;
        } catch (Exception ignored) { /* Neither codes, peer details nor user text enter logs. */ }
        finally {
            if (tls != null) { socket.compareAndSet(tls, null); closeNow(tls); }
            synchronized (this) {
                if (generation.get() == epoch) { status = requirePairing ? "pairing_required" : "unavailable"; running.set(false); codes.clear(); }
            }
        }
    }
    private void runControl(long epoch, BooleanSupplier session) {
        SSLSocket tls = null;
        try {
            if (!current(epoch, session)) return;
            byte[] pin = identity.pin(); if (pin == null) return;
            tls = connect(controlPort, pin, epoch); tls.setSoTimeout(100);
            InputStream in = tls.getInputStream(); OutputStream out = tls.getOutputStream();
            int features = 3; boolean configured = false;
            long lastMessage = System.nanoTime(), handshakeDeadline = lastMessage + TimeUnit.SECONDS.toNanos(8);
            while (current(epoch, session)) {
                if (!status.equals("ready") && System.nanoTime() > handshakeDeadline) throw new IOException("remote_handshake_timeout");
                KeyRequest key;
                while ((key = keys.poll()) != null) {
                    if (!configured || !status.equals("ready") || key.done.isDone() || key.generation != epoch || System.nanoTime() > key.deadline || !current(epoch, key.current)) { key.done.complete(false); continue; }
                    final Socket active = tls;
                    ScheduledFuture<?> timeout = cleanup.schedule(() -> closeNow(active), 1400, TimeUnit.MILLISECONDS);
                    try {
                        // Last authorization check immediately precedes the only key write. No replay after write failure.
                        if (!current(epoch, key.current) || key.done.isDone()) { key.done.complete(false); continue; }
                        write(tls, out, RemoteV2Codec.key(key.key)); key.done.complete(true);
                    } finally { timeout.cancel(false); }
                }
                byte[] message;
                try {
                    int first = in.read(); if (first < 0) throw new EOFException();
                    tls.setSoTimeout(2000);
                    final Socket reading = tls;
                    ScheduledFuture<?> frameDeadline = cleanup.schedule(() -> closeNow(reading), 2000, TimeUnit.MILLISECONDS);
                    try { message = RemoteV2Codec.read(new SequenceInputStream(new ByteArrayInputStream(new byte[]{(byte)first}), in)); }
                    catch (SocketTimeoutException partialFrame) { throw new IOException("remote_partial_frame", partialFrame); }
                    finally { frameDeadline.cancel(false); tls.setSoTimeout(100); }
                } catch (SocketTimeoutException idle) {
                    if ((!status.equals("ready") && System.nanoTime() > handshakeDeadline) || System.nanoTime() - lastMessage > TimeUnit.SECONDS.toNanos(16)) throw idle;
                    continue;
                }
                lastMessage = System.nanoTime();
                if (RemoteV2Codec.field(message, 1) != null) {
                    features = (int)RemoteV2Codec.integer(RemoteV2Codec.nested(message, 1), 1, 0) & 3;
                    if ((features & 2) == 0) { synchronized (this) { if (generation.get() == epoch) status = "unsupported"; } throw new IOException("remote_keys_unsupported"); }
                    byte[] info = RemoteV2Codec.join(RemoteV2Codec.text(1, "Sofatap"), RemoteV2Codec.text(2, "CHEKUSU"), RemoteV2Codec.number(3, 1), RemoteV2Codec.text(4, "1"), RemoteV2Codec.text(5, "atvremote"), RemoteV2Codec.text(6, "1.0.0"));
                    write(tls, out, RemoteV2Codec.bytes(1, RemoteV2Codec.join(RemoteV2Codec.number(1, features), RemoteV2Codec.bytes(2, info)))); configured = true;
                } else if (RemoteV2Codec.field(message, 2) != null) {
                    if (!configured) throw new IOException("remote_handshake_order");
                    write(tls, out, RemoteV2Codec.bytes(2, RemoteV2Codec.number(1, features)));
                } else if (RemoteV2Codec.field(message, 40) != null) {
                    if (!configured) throw new IOException("remote_handshake_order");
                    long powerState = RemoteV2Codec.integer(RemoteV2Codec.nested(message, 40), 1, 0);
                    if (powerState != 0 && powerState != 1) throw new IOException("remote_invalid_state");
                    synchronized (this) { if (current(epoch, session)) status = "ready"; }
                } else if (RemoteV2Codec.field(message, 8) != null) {
                    long value = RemoteV2Codec.integer(RemoteV2Codec.nested(message, 8), 1, 0);
                    write(tls, out, RemoteV2Codec.bytes(9, RemoteV2Codec.number(1, value)));
                } else if (RemoteV2Codec.field(message, 3) != null) throw new IOException("remote_service_error");
            }
        } catch (SSLHandshakeException failure) { synchronized (this) { if (generation.get() == epoch) requirePairing = true; } }
        catch (Exception ignored) { }
        finally {
            if (tls != null) { socket.compareAndSet(tls, null); closeNow(tls); }
            synchronized (this) {
                if (generation.get() == epoch) {
                    if (!status.equals("unsupported")) status = requirePairing ? "pairing_required" : "unavailable";
                    nextConnect = System.nanoTime() + TimeUnit.SECONDS.toNanos(3); running.set(false);
                    KeyRequest key; while ((key = keys.poll()) != null) key.done.complete(false);
                }
            }
        }
    }
    private void write(SSLSocket active, OutputStream output, byte[] message) throws IOException {
        ScheduledFuture<?> timeout = cleanup.schedule(() -> closeNow(active), 1800, TimeUnit.MILLISECONDS);
        try { RemoteV2Codec.write(output, message); } finally { timeout.cancel(false); }
    }
    private SSLSocket connect(int port, byte[] pin, long epoch) throws Exception {
        KeyStore keys = KeyStore.getInstance(KeyStore.getDefaultType()); keys.load(null);
        char[] password = new char[0]; HeldCertificate cert = identity.client();
        keys.setKeyEntry("client", cert.keyPair().getPrivate(), password, new java.security.cert.Certificate[]{cert.certificate()});
        KeyManagerFactory manager = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm()); manager.init(keys, password);
        X509TrustManager trust = new X509TrustManager() {
            @Override public X509Certificate[] getAcceptedIssuers() { return new X509Certificate[0]; }
            @Override public void checkClientTrusted(X509Certificate[] chain, String auth) throws CertificateException { throw new CertificateException("remote_client_not_expected"); }
            @Override public void checkServerTrusted(X509Certificate[] chain, String auth) throws CertificateException {
                if (chain == null || chain.length < 1 || chain.length > 4 || !(chain[0].getPublicKey() instanceof RSAPublicKey rsa) || rsa.getModulus().bitLength() < 2048) throw new CertificateException("remote_invalid_identity");
                chain[0].checkValidity();
                if (pin != null) { try { if (!MessageDigest.isEqual(pin, chain[0].getEncoded())) throw new CertificateException("remote_peer_changed"); } catch (CertificateEncodingException invalid) { throw new CertificateException("remote_invalid_identity"); } }
                // pin==null is restricted to the explicit loopback pairing challenge. It cannot open control.
            }
        };
        if (port == controlPort && pin == null) throw new IOException("remote_pairing_required");
        SSLContext context = SSLContext.getInstance("TLS"); context.init(manager.getKeyManagers(), new TrustManager[]{trust}, new SecureRandom());
        SSLSocket tls = (SSLSocket)context.getSocketFactory().createSocket();
        if (generation.get() != epoch || closed || !socket.compareAndSet(null, tls)) { closeNow(tls); throw new IOException("session_ended"); }
        ScheduledFuture<?> connectDeadline = cleanup.schedule(() -> closeNow(tls), 4500, TimeUnit.MILLISECONDS);
        try {
            tls.connect(new InetSocketAddress(InetAddress.getByAddress(new byte[]{127, 0, 0, 1}), port), 2000); tls.setSoTimeout(2500); tls.setTcpNoDelay(true); tls.startHandshake();
            if (generation.get() != epoch || closed) throw new IOException("session_ended"); return tls;
        } catch (Exception failure) { socket.compareAndSet(tls, null); closeNow(tls); throw failure; }
        finally { connectDeadline.cancel(false); }
    }
    private void closeLater(Socket old) { try { cleanup.execute(() -> closeNow(old)); } catch (RejectedExecutionException ignored) { /* close() scheduled before executor shutdown */ } }
    private static void closeNow(Socket old) { try { old.close(); } catch (Exception ignored) { } }
    @Override public synchronized void close() { if (closed) return; closed = true; invalidate(); worker.shutdownNow(); cleanup.shutdown(); }
}
