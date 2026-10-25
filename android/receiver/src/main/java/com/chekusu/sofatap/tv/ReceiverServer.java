package com.chekusu.sofatap.tv;

import org.json.JSONObject;
import javax.net.ssl.*;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;

/** Small, bounded local TLS/WebSocket endpoint for authenticated manual input only. */
public final class ReceiverServer implements AutoCloseable {
    public interface Input {
        JSONObject snapshot() throws Exception;
        JSONObject apply(TVProtocol.Action action, BooleanSupplier stillCurrent) throws Exception;
        void disconnected();
    }
    private final SSLServerSocket listener;
    private final PairingAuthority authority;
    private final Input input;
    private final String name;
    private final boolean allowLoopbackPeers;
    private final ExecutorService workers = new ThreadPoolExecutor(0, 4, 30, TimeUnit.SECONDS,
        new SynchronousQueue<>());
    private final ScheduledExecutorService deadlines = Executors.newSingleThreadScheduledExecutor();
    // TLS close can write close_notify, even when aborting a socket. Android
    // lifecycle callbacks must never perform that network I/O on the main thread.
    private final ExecutorService cleanup = Executors.newSingleThreadExecutor(
        task -> new Thread(task, "Sofatap-TV-cleanup"));
    private final Set<Socket> sockets = ConcurrentHashMap.newKeySet();
    private final AtomicReference<Socket> controller = new AtomicReference<>();
    private final CountDownLatch listenerStopped = new CountDownLatch(1);
    private boolean started;
    private volatile boolean closed;

    public ReceiverServer(SSLContext tls, InetAddress bind, int port, PairingAuthority authority,
                          Input input, String name) throws IOException {
        this.listener = (SSLServerSocket)tls.getServerSocketFactory().createServerSocket();
        // A replacement listener must be able to bind while retired connections
        // finish closing. Set reuse before bind so accepted sockets inherit it.
        try {
            listener.setReuseAddress(true);
            listener.bind(new InetSocketAddress(bind, port), 4);
        } catch (IOException failure) {
            try { listener.close(); } catch (IOException ignored) { }
            throw failure;
        }
        this.allowLoopbackPeers = bind != null && bind.isLoopbackAddress();
        List<String> protocols = new ArrayList<>();
        for (String p : listener.getSupportedProtocols()) if (p.equals("TLSv1.2") || p.equals("TLSv1.3")) protocols.add(p);
        listener.setEnabledProtocols(protocols.toArray(new String[0]));
        this.authority = authority; this.input = input; this.name = name;
    }
    public int port() { return listener.getLocalPort(); }
    public boolean connected() { return controller.get() != null; }
    public synchronized void start() {
        if (closed || started) return;
        started = true;
        new Thread(this::accept, "Sofatap-TV-listener").start();
    }
    /** Background lifecycle barrier; never wait from main or while holding a service lock. */
    boolean awaitListenerStopped(long timeout, TimeUnit unit) throws InterruptedException {
        return listenerStopped.await(timeout, unit);
    }
    private void accept() {
        try {
            while (!closed) {
                try {
                    SSLSocket socket = (SSLSocket)listener.accept();
                    if (!allowsPeer(socket.getInetAddress(), allowLoopbackPeers)) { close(socket); continue; }
                    boolean stopping;
                    synchronized (this) {
                        // close() may have run between accept() and registration.
                        stopping = closed;
                        if (!stopping) sockets.add(socket);
                    }
                    if (stopping) { close(socket); return; }
                    try { workers.execute(() -> handle(socket)); }
                    catch (RejectedExecutionException busy) { sockets.remove(socket); close(socket); }
                } catch (IOException error) { if (!closed) close(); }
            }
        } finally { listenerStopped.countDown(); }
    }
    public synchronized void disconnectController() {
        Socket socket = controller.getAndSet(null);
        if (socket == null) return;
        // Invalidate queued input now, without waiting for TLS cleanup. The old
        // worker's finally block must not disconnect a newly resumed controller.
        input.disconnected();
        cleanup.execute(() -> close(socket));
    }
    static boolean allowsPeer(InetAddress address, boolean allowLoopback) {
        // Production QR endpoints are RFC1918 IPv4. Wildcard binding must not also
        // expose an authenticated control service to public IPv6 or other interfaces.
        if (allowLoopback && address.isLoopbackAddress()) return true;
        return address instanceof Inet4Address && address.isSiteLocalAddress();
    }
    private void handle(SSLSocket socket) {
        ScheduledFuture<?> expiry = null;
        try {
            // A wall-clock deadline bounds slow TLS/header senders, including dribbled bytes.
            expiry = deadlines.schedule(() -> close(socket), 5, TimeUnit.SECONDS);
            socket.setSoTimeout(5000); socket.setTcpNoDelay(true); socket.startHandshake();
            InputStream in = socket.getInputStream(); OutputStream out = socket.getOutputStream();
            Map<String, String> h = WebSocketCodec.request(in);
            boolean acquired;
            synchronized (this) { acquired = !closed && controller.compareAndSet(null, socket); }
            if (!acquired) { reject(out, "409 Conflict"); return; }
            String auth = h.getOrDefault("authorization", "");
            long generation = authority.generation();
            PairingAuthority.Grant grant = authority.authorize(auth.startsWith("Bearer ") ? auth.substring(7) : null);
            if (!grant.allowed()) { reject(out, "401 Unauthorized"); return; }
            BooleanSupplier current = () -> !closed && !socket.isClosed() && controller.get() == socket && authority.generation() == generation;
            if (!current.getAsBoolean()) return;
            WebSocketCodec.upgrade(out, h.get("sec-websocket-key"));
            JSONObject hello = input.snapshot();
            hello.put("type", "hello").put("version", 1).put("platform", "androidtv").put("name", name);
            if (grant.replacementToken() != null) hello.put("pairingToken", grant.replacementToken());
            send(out, hello);
            expiry.cancel(false); expiry = null;
            socket.setSoTimeout(20_000);
            Map<String, Cached> seen = new HashMap<>();
            long rateStart = System.nanoTime(); int actions = 0;
            while (current.getAsBoolean()) {
                expiry = deadlines.schedule(() -> close(socket), 20, TimeUnit.SECONDS);
                String message = readMessage(in, out);
                if (message == null) return;
                expiry.cancel(false);
                // Also bound a stalled reader while writing acknowledgments.
                expiry = deadlines.schedule(() -> close(socket), 5, TimeUnit.SECONDS);
                if (System.nanoTime() - rateStart >= 1_000_000_000L) { rateStart = System.nanoTime(); actions = 0; }
                if (++actions > 240 || seen.size() >= 32_768) return;
                TVProtocol.Action action;
                try { action = TVProtocol.parse(message); }
                catch (Exception invalid) { send(out, TVProtocol.ack("", false, "invalid_action")); return; }
                String id = action.id().toLowerCase(Locale.ROOT);
                String digest = PairingAuthority.hash(message);
                Cached previous = seen.get(id);
                if (previous != null) {
                    if (!previous.digest.equals(digest)) { send(out, TVProtocol.ack(action.id(), false, "id_reused")); return; }
                    send(out, previous.ack); // Never repeat input on a duplicate.
                } else {
                    JSONObject ack;
                    if (action.type().equals("ping")) {
                        ack = input.snapshot().put("type", "ack").put("id", action.id()).put("ok", true);
                    } else {
                        if (!current.getAsBoolean()) return;
                        JSONObject result = input.apply(action, current);
                        ack = action.extended() ? input.snapshot().put("type", "ack").put("id", action.id()).put("ok", result.optBoolean("ok", false))
                            : TVProtocol.ack(action.id(), result.optBoolean("ok", false), result.optBoolean("ok", false) ? null : result.optString("code", "input_unavailable"));
                        if (action.extended() && !result.optBoolean("ok", false)) ack.put("error", result.optString("code", "input_unavailable"));
                    }
                    if (!current.getAsBoolean()) return;
                    seen.put(id, new Cached(digest, ack)); send(out, ack);
                }
                expiry.cancel(false); expiry = null;
            }
        } catch (Exception ignored) {
            // Neither credentials nor input payloads are logged, even on malformed traffic.
        } finally {
            if (expiry != null) expiry.cancel(false);
            synchronized (this) {
                // Natural EOF/timeout has the same atomic release-and-notify boundary
                // as explicit disconnect. A replacement cannot snapshot its new editor
                // epoch or start its remote connection until the old invalidation ran.
                if (controller.compareAndSet(socket, null)) input.disconnected();
            }
            close(socket); sockets.remove(socket);
        }
    }
    private record Cached(String digest, JSONObject ack) { }
    private static String readMessage(InputStream in, OutputStream out) throws IOException {
        ByteArrayOutputStream parts = new ByteArrayOutputStream(); boolean started = false; int frames = 0;
        while (++frames <= 256) {
            WebSocketCodec.Frame f = WebSocketCodec.read(in);
            if (f.opcode() == 8) { WebSocketCodec.write(out, 8, new byte[]{3, (byte)232}); return null; }
            if (f.opcode() == 9) { WebSocketCodec.write(out, 10, f.payload()); continue; }
            if (f.opcode() == 10) continue;
            if ((!started && f.opcode() != 1) || (started && f.opcode() != 0)) throw new IOException("invalid_fragment");
            started = true;
            if (parts.size() + f.payload().length > WebSocketCodec.MAX_MESSAGE) throw new IOException("message_too_large");
            parts.write(f.payload());
            if (f.fin()) return WebSocketCodec.text(parts.toByteArray());
        }
        throw new IOException("too_many_frames");
    }
    private static void send(OutputStream out, JSONObject message) throws IOException {
        WebSocketCodec.write(out, 1, message.toString().getBytes(StandardCharsets.UTF_8));
    }
    private static void reject(OutputStream out, String code) throws IOException {
        out.write(("HTTP/1.1 " + code + "\r\nContent-Length: 0\r\nConnection: close\r\n\r\n").getBytes(StandardCharsets.US_ASCII)); out.flush();
    }
    private static void close(Socket socket) {
        // A peer need not cooperate with TLS close_notify. Never spend the 20s
        // message timeout draining attacker data while holding the controller slot.
        try { socket.setSoTimeout(1); socket.setSoLinger(true, 0); } catch (IOException ignored) { }
        try { socket.shutdownInput(); } catch (IOException ignored) { }
        try { socket.close(); } catch (IOException ignored) { }
    }
    @Override public synchronized void close() {
        if (closed) return;
        closed = true;
        disconnectController();
        // Initiate listener shutdown without TLS I/O. Some runtimes defer the
        // native close until accept exits; setup waits for listenerStopped.
        try { listener.close(); } catch (IOException ignored) { }
        if (!started) listenerStopped.countDown();
        for (Socket socket : sockets) cleanup.execute(() -> close(socket));
        workers.shutdownNow(); deadlines.shutdownNow();
        // Drain every accepted cleanup, including disconnects queued above.
        cleanup.shutdown();
    }
}
