package com.chekusu.sofatap.tv;

import okhttp3.tls.HeldCertificate;
import org.junit.Test;
import javax.net.ssl.*;
import java.io.*;
import java.net.*;
import java.security.*;
import java.security.cert.X509Certificate;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.function.BooleanSupplier;
import static org.junit.Assert.*;

/** Real loopback TLS/protobuf fixture. No Android input or real TV is contacted. */
public class RemoteV2ClientTest {
    static final class Identity implements RemoteV2Client.Identity {
        final HeldCertificate cert = new HeldCertificate.Builder().rsa2048().commonName("fixture-client").build();
        volatile byte[] pin;
        public HeldCertificate client() { return cert; }
        public byte[] pin() { return pin == null ? null : pin.clone(); }
        public void savePin(byte[] value) { pin = value == null ? null : value.clone(); }
    }
    static final class Fixture implements AutoCloseable {
        final HeldCertificate cert = new HeldCertificate.Builder().rsa2048().commonName("fixture-tv").build();
        final SSLServerSocket pair, control;
        final ExecutorService workers = Executors.newCachedThreadPool();
        final List<Socket> peers = new CopyOnWriteArrayList<>();
        final BlockingQueue<Integer> keys = new LinkedBlockingQueue<>();
        final AtomicInteger controls = new AtomicInteger();
        final CountDownLatch challenge = new CountDownLatch(1);
        volatile Throwable failure;
        volatile boolean closed;
        Fixture() throws Exception {
            KeyStore ks = KeyStore.getInstance(KeyStore.getDefaultType()); ks.load(null); ks.setKeyEntry("tv", cert.keyPair().getPrivate(), new char[0], new java.security.cert.Certificate[]{cert.certificate()});
            KeyManagerFactory km = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm()); km.init(ks,new char[0]);
            SSLContext tls = SSLContext.getInstance("TLS");
            tls.init(km.getKeyManagers(),new TrustManager[]{new X509TrustManager() {
                public X509Certificate[] getAcceptedIssuers() { return new X509Certificate[0]; }
                public void checkClientTrusted(X509Certificate[] chain,String auth) { }
                public void checkServerTrusted(X509Certificate[] chain,String auth) { }
            }},new SecureRandom());
            pair=(SSLServerSocket)tls.getServerSocketFactory().createServerSocket(0,4,InetAddress.getLoopbackAddress()); pair.setNeedClientAuth(true);
            control=(SSLServerSocket)tls.getServerSocketFactory().createServerSocket(0,4,InetAddress.getLoopbackAddress()); control.setNeedClientAuth(true);
            workers.execute(this::pair); workers.execute(this::controls);
        }
        private void pair() {
            try (SSLSocket s=(SSLSocket)pair.accept()) {
                peers.add(s); s.setSoTimeout(4000); s.startHandshake(); InputStream in=s.getInputStream(); OutputStream out=s.getOutputStream();
                RemoteV2Codec.expectPairing(RemoteV2Codec.read(in),10); RemoteV2Codec.write(out,RemoteV2Codec.pairing(11,new byte[0]));
                RemoteV2Codec.expectPairing(RemoteV2Codec.read(in),20); RemoteV2Codec.write(out,RemoteV2Codec.pairing(20,new byte[0]));
                RemoteV2Codec.expectPairing(RemoteV2Codec.read(in),30); RemoteV2Codec.write(out,RemoteV2Codec.pairing(31,new byte[0])); challenge.countDown();
                byte[] secretMessage=RemoteV2Codec.read(in); RemoteV2Codec.expectPairing(secretMessage,40);
                X509Certificate client=(X509Certificate)s.getSession().getPeerCertificates()[0];
                byte[] expected=RemoteV2Codec.secret(client,cert.certificate(),code(client));
                assertArrayEquals(expected,RemoteV2Codec.nested(RemoteV2Codec.nested(secretMessage,40),1));
                RemoteV2Codec.write(out,RemoteV2Codec.pairing(41,RemoteV2Codec.bytes(1,expected)));
            } catch (EOFException | SocketException expectedClose) { }
            catch (Throwable e) { if(!closed)failure=e; }
        }
        private void controls() {
            while(!closed) try {
                SSLSocket s=(SSLSocket)control.accept(); peers.add(s); workers.execute(()->control(s));
            } catch(Exception e) { if(!closed)failure=e; return; }
        }
        private void control(SSLSocket s) {
            try(s) {
                s.setSoTimeout(4000); s.startHandshake(); controls.incrementAndGet(); InputStream in=s.getInputStream(); OutputStream out=s.getOutputStream();
                RemoteV2Codec.write(out,RemoteV2Codec.bytes(1,RemoteV2Codec.number(1,3)));
                assertEquals(3,RemoteV2Codec.integer(RemoteV2Codec.nested(RemoteV2Codec.read(in),1),1,-1));
                RemoteV2Codec.write(out,RemoteV2Codec.bytes(2,RemoteV2Codec.number(1,3))); RemoteV2Codec.nested(RemoteV2Codec.read(in),2);
                RemoteV2Codec.write(out,RemoteV2Codec.bytes(40,RemoteV2Codec.number(1,1)));
                while(!closed) {
                    byte[] message=RemoteV2Codec.read(in);
                    if(RemoteV2Codec.field(message,10)!=null) {
                        byte[] key=RemoteV2Codec.nested(message,10); assertEquals(3,RemoteV2Codec.integer(key,2,-1)); keys.add((int)RemoteV2Codec.integer(key,1,-1));
                    }
                }
            } catch(EOFException | SocketException | SSLException expectedClose) { }
            catch(Throwable e) { if(!closed)failure=e; }
        }
        String code(X509Certificate client) throws Exception {
            for(int prefix=0;prefix<256;prefix++) {
                String candidate=String.format(Locale.ROOT,"%02XBEEF",prefix);
                try { RemoteV2Codec.secret(client,cert.certificate(),candidate); return candidate; } catch(IOException wrong) { }
            } throw new AssertionError("code not found");
        }
        public void close() throws Exception { closed=true; pair.close(); control.close(); for(Socket s:peers)try{s.close();}catch(Exception ignored){} workers.shutdownNow(); assertTrue(workers.awaitTermination(5,TimeUnit.SECONDS)); }
    }
    private static void waitFor(BooleanSupplier condition) throws Exception {
        long until=System.nanoTime()+TimeUnit.SECONDS.toNanos(6);
        while(!condition.getAsBoolean() && System.nanoTime()<until) Thread.sleep(10);
        assertTrue("condition timed out",condition.getAsBoolean());
    }
    @Test public void explicitCodeProofPersistsExactPeerAndNativeKeysWorkAfterTlsReconnect() throws Exception {
        Identity identity=new Identity();
        try(Fixture f=new Fixture(); RemoteV2Client client=new RemoteV2Client(identity,f.pair.getLocalPort(),f.control.getLocalPort())) {
            assertEquals("pairing_required",client.status()); assertEquals("ok",client.startPairing(()->true));
            assertTrue(f.challenge.await(4,TimeUnit.SECONDS)); assertNull(identity.pin());
            assertEquals("ok",client.submitCode(f.code(identity.cert.certificate()),()->true)); waitFor(()->client.status().equals("ready"));
            assertArrayEquals(f.cert.certificate().getEncoded(),identity.pin());
            for(String key:new String[]{"up","down","left","right","confirm","back","home"}) {
                assertEquals("ok",client.key(key,()->true)); assertEquals(Integer.valueOf(RemoteV2Codec.keyCode(key)),f.keys.poll(2,TimeUnit.SECONDS));
            }
            client.disconnected(); client.ensureConnected(); waitFor(()->client.status().equals("ready"));
            assertEquals("ok",client.key("confirm",()->true)); assertEquals(Integer.valueOf(23),f.keys.poll(2,TimeUnit.SECONDS)); assertEquals(2,f.controls.get()); assertNull(f.failure);
        }
    }
    @Test public void incorrectCodeCannotPersistPeerOrOpenControl() throws Exception {
        Identity identity=new Identity();
        try(Fixture f=new Fixture(); RemoteV2Client client=new RemoteV2Client(identity,f.pair.getLocalPort(),f.control.getLocalPort())) {
            assertEquals("ok",client.startPairing(()->true)); assertTrue(f.challenge.await(4,TimeUnit.SECONDS));
            String correct=f.code(identity.cert.certificate()); String wrong=(correct.startsWith("00")?"01":"00")+correct.substring(2);
            assertEquals("ok",client.submitCode(wrong,()->true)); waitFor(()->client.status().equals("pairing_required"));
            assertNull(identity.pin()); assertEquals(0,f.controls.get()); assertTrue(f.keys.isEmpty());
        }
    }
    @Test public void changedPeerCertificateFailsBeforeControlHandshakeOrKey() throws Exception {
        Identity identity=new Identity(); identity.pin=new HeldCertificate.Builder().rsa2048().commonName("different-tv").build().certificate().getEncoded();
        try(Fixture f=new Fixture(); RemoteV2Client client=new RemoteV2Client(identity,f.pair.getLocalPort(),f.control.getLocalPort())) {
            client.ensureConnected(); waitFor(()->client.status().equals("pairing_required"));
            assertEquals("remote_unavailable",client.key("home",()->true)); assertEquals(0,f.controls.get()); assertTrue(f.keys.isEmpty());
        }
    }
    @Test public void controllerDisconnectInvalidatesADequeuedKeyBeforeItsWrite() throws Exception {
        Identity identity=new Identity();
        try(Fixture f=new Fixture()) {
            identity.pin=f.cert.certificate().getEncoded();
            try(RemoteV2Client client=new RemoteV2Client(identity,f.pair.getLocalPort(),f.control.getLocalPort())) {
                client.ensureConnected(); waitFor(()->client.status().equals("ready"));
                CountDownLatch checking=new CountDownLatch(1), release=new CountDownLatch(1); AtomicInteger checks=new AtomicInteger();
                ExecutorService caller=Executors.newSingleThreadExecutor();
                try {
                    Future<String> result=caller.submit(()->client.key("home",()->{
                        if(checks.incrementAndGet()==2) { checking.countDown(); try{release.await(2,TimeUnit.SECONDS);}catch(InterruptedException e){return false;} }
                        return true;
                    }));
                    assertTrue(checking.await(2,TimeUnit.SECONDS)); client.disconnected(); release.countDown();
                    assertEquals("session_ended",result.get(2,TimeUnit.SECONDS)); assertNull(f.keys.poll(200,TimeUnit.MILLISECONDS));
                } finally {release.countDown();caller.shutdownNow();}
            }
        }
    }
}
