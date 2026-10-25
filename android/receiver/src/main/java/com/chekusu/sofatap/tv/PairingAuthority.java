package com.chekusu.sofatap.tv;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.function.LongSupplier;

/** One-time QR enrollment; a separate bearer credential is returned over pinned TLS. */
public final class PairingAuthority {
    public interface Persistence { void save(String digest) throws Exception; }
    public record Grant(boolean allowed, String replacementToken) { }
    private final LongSupplier clock;
    private final Persistence persistence;
    private String pairedDigest;
    private String bootstrap;
    private long expiresAt;
    private long generation;

    public PairingAuthority(String pairedDigest, LongSupplier clock, Persistence persistence) {
        this.pairedDigest = pairedDigest;
        this.clock = clock;
        this.persistence = persistence;
    }
    public synchronized String newPairing() throws Exception {
        // Fail closed if revocation cannot be committed.
        pairedDigest = null; bootstrap = null; generation++;
        persistence.save(null);
        bootstrap = randomToken(); expiresAt = clock.getAsLong() + 300_000L;
        return bootstrap;
    }
    public synchronized void revoke() throws Exception {
        pairedDigest = null; bootstrap = null; generation++;
        persistence.save(null);
    }
    /** Retire an in-memory listener without revoking the saved controller. */
    synchronized void retire() {
        pairedDigest = null; bootstrap = null; generation++;
    }
    public synchronized long generation() { return generation; }
    public synchronized String bootstrap() {
        return bootstrap != null && clock.getAsLong() < expiresAt ? bootstrap : null;
    }
    public synchronized boolean paired() { return pairedDigest != null; }
    public synchronized Grant authorize(String token) throws Exception {
        if (token == null || !token.matches("[A-Za-z0-9_-]{43}")) return new Grant(false, null);
        if (pairedDigest != null && same(hash(token), pairedDigest)) return new Grant(true, null);
        if (bootstrap() == null || !same(token, bootstrap)) return new Grant(false, null);
        String replacement = randomToken();
        String digest = hash(replacement);
        // Consume before I/O: a failure must never create two live authorities.
        bootstrap = null;
        persistence.save(digest);
        pairedDigest = digest;
        return new Grant(true, replacement);
    }
    static String randomToken() {
        byte[] bytes = new byte[32]; new SecureRandom().nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }
    static String hash(String value) {
        try { return hex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8))); }
        catch (Exception impossible) { throw new IllegalStateException(impossible); }
    }
    static String hex(byte[] value) {
        StringBuilder b = new StringBuilder();
        for (byte item : value) b.append(String.format(java.util.Locale.ROOT, "%02x", item & 255));
        return b.toString();
    }
    private static boolean same(String a, String b) {
        return MessageDigest.isEqual(a.getBytes(StandardCharsets.US_ASCII), b.getBytes(StandardCharsets.US_ASCII));
    }
}
