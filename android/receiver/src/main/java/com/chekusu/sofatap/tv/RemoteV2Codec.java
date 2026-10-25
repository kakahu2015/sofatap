package com.chekusu.sofatap.tv;

import java.io.*;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.interfaces.RSAPublicKey;
import java.security.cert.X509Certificate;
import java.util.*;

/** Narrow, bounded protobuf subset for local Android TV Remote Service v2.
 * Wire reference: androidtvremote2 e664a8e1f14ee458b14c646bfb3ea46f10367051 (Apache-2.0).
 * No voice, app-launch, power or arbitrary key messages are implemented. */
final class RemoteV2Codec {
    static final int MAX_FRAME = 65_536;
    private RemoteV2Codec() { }
    static byte[] number(int field, long value) {
        ByteArrayOutputStream out = new ByteArrayOutputStream(); varint(out, (long)field << 3); varint(out, value); return out.toByteArray();
    }
    static byte[] bytes(int field, byte[] value) {
        ByteArrayOutputStream out = new ByteArrayOutputStream(); varint(out, ((long)field << 3) | 2); varint(out, value.length); out.write(value, 0, value.length); return out.toByteArray();
    }
    static byte[] text(int field, String value) { return bytes(field, value.getBytes(StandardCharsets.UTF_8)); }
    static byte[] join(byte[]... values) {
        ByteArrayOutputStream out = new ByteArrayOutputStream(); for (byte[] v : values) out.write(v, 0, v.length); return out.toByteArray();
    }
    private static void varint(ByteArrayOutputStream out, long n) {
        do { out.write((int)(n & 127) | ((n >>> 7) == 0 ? 0 : 128)); n >>>= 7; } while (n != 0);
    }
    static void write(OutputStream out, byte[] message) throws IOException {
        if (message.length == 0 || message.length > MAX_FRAME) throw new IOException("remote_frame_size");
        ByteArrayOutputStream prefix = new ByteArrayOutputStream(); varint(prefix, message.length);
        out.write(join(prefix.toByteArray(), message)); out.flush();
    }
    static byte[] read(InputStream in) throws IOException {
        int first = in.read(); if (first < 0) throw new EOFException();
        long length = first & 127; int c = first, shift = 7;
        while ((c & 128) != 0) {
            if (shift >= 28) throw new IOException("remote_frame_size");
            c = in.read(); if (c < 0) throw new EOFException(); length |= (long)(c & 127) << shift; shift += 7;
        }
        if (length < 1 || length > MAX_FRAME) throw new IOException("remote_frame_size");
        byte[] result = new byte[(int)length]; int offset = 0;
        while (offset < result.length) { int n = in.read(result, offset, result.length - offset); if (n < 0) throw new EOFException(); offset += n; }
        return result;
    }
    record Field(int number, long value, byte[] bytes) { }
    static List<Field> fields(byte[] message) throws IOException {
        if (message.length > MAX_FRAME) throw new IOException("remote_frame_size");
        ByteArrayInputStream in = new ByteArrayInputStream(message); List<Field> result = new ArrayList<>();
        while (in.available() > 0) {
            if (result.size() >= 256) throw new IOException("remote_field_limit");
            long tag = readVarint(in), fieldNumber = tag >>> 3;
            if (fieldNumber < 1 || fieldNumber > 536870911) throw new IOException("remote_invalid_tag");
            int number = (int)fieldNumber, wire = (int)(tag & 7);
            if (wire == 0) result.add(new Field(number, readVarint(in), null));
            else if (wire == 2) {
                long size = readVarint(in); if (size < 0 || size > in.available()) throw new IOException("remote_invalid_length");
                byte[] data = new byte[(int)size]; if (in.read(data) != data.length && data.length != 0) throw new EOFException(); result.add(new Field(number, 0, data));
            } else if (wire == 1 || wire == 5) {
                int n = wire == 1 ? 8 : 4; if (in.skip(n) != n) throw new EOFException();
            } else throw new IOException("remote_invalid_wire");
        }
        return result;
    }
    private static long readVarint(InputStream in) throws IOException {
        long result = 0;
        for (int i = 0; i < 10; i++) { int c = in.read(); if (c < 0) throw new EOFException(); if (i == 9 && (c & 254) != 0) throw new IOException("remote_invalid_varint"); result |= (long)(c & 127) << (7 * i); if ((c & 128) == 0) return result; }
        throw new IOException("remote_invalid_varint");
    }
    static Field field(byte[] message, int number) throws IOException {
        Field found = null; for (Field f : fields(message)) if (f.number == number) { if (found != null) throw new IOException("remote_duplicate_field"); found = f; } return found;
    }
    static long integer(byte[] message, int number, long fallback) throws IOException {
        Field f = field(message, number); if (f == null) return fallback; if (f.bytes != null) throw new IOException("remote_invalid_field"); return f.value;
    }
    static byte[] nested(byte[] message, int number) throws IOException {
        Field f = field(message, number); if (f == null || f.bytes == null) throw new IOException("remote_missing_field"); return f.bytes;
    }
    static byte[] pairing(int field, byte[] payload) { return join(number(1, 2), number(2, 200), bytes(field, payload)); }
    static void expectPairing(byte[] message, int field) throws IOException {
        if (integer(message, 1, -1) != 2 || integer(message, 2, -1) != 200) throw new IOException("remote_pair_rejected"); nested(message, field);
    }
    static byte[] secret(X509Certificate client, X509Certificate server, String code) throws Exception {
        if (code == null || !code.matches("[0-9a-fA-F]{6}")) throw new IOException("invalid_pair_code");
        if (!(client.getPublicKey() instanceof RSAPublicKey a) || !(server.getPublicKey() instanceof RSAPublicKey b)) throw new IOException("remote_rsa_required");
        MessageDigest hash = MessageDigest.getInstance("SHA-256");
        hash.update(unsigned(a.getModulus())); hash.update(unsigned(a.getPublicExponent()));
        hash.update(unsigned(b.getModulus())); hash.update(unsigned(b.getPublicExponent()));
        hash.update((byte)Integer.parseInt(code.substring(2, 4), 16)); hash.update((byte)Integer.parseInt(code.substring(4, 6), 16));
        byte[] secret = hash.digest(); if ((secret[0] & 255) != Integer.parseInt(code.substring(0, 2), 16)) throw new IOException("invalid_pair_code"); return secret;
    }
    private static byte[] unsigned(BigInteger n) { byte[] bytes = n.toByteArray(); return bytes.length > 1 && bytes[0] == 0 ? Arrays.copyOfRange(bytes, 1, bytes.length) : bytes; }
    static int keyCode(String key) throws IOException {
        return switch (key) { case "up" -> 19; case "down" -> 20; case "left" -> 21; case "right" -> 22; case "confirm" -> 23; case "back" -> 4; case "home" -> 3; default -> throw new IOException("unsupported_key"); };
    }
    static byte[] key(String key) throws IOException { return bytes(10, join(number(1, keyCode(key)), number(2, 3))); }
}
