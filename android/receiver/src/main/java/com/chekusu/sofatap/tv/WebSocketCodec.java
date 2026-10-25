package com.chekusu.sofatap.tv;

import java.io.*;
import java.nio.ByteBuffer;
import java.nio.charset.*;
import java.security.MessageDigest;
import java.util.*;

/** Bounded RFC 6455 subset: masked client frames, UTF-8 text, ping/pong and close. */
public final class WebSocketCodec {
    public static final int MAX_MESSAGE = 65_536;
    public record Frame(boolean fin, int opcode, byte[] payload) { }
    public static Map<String, String> request(InputStream in) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        int suffix = 0;
        while (bytes.size() < 8192) {
            int b = in.read(); if (b < 0) throw new EOFException();
            if (b > 127 || b == 0) throw new IOException("invalid_header");
            bytes.write(b);
            suffix = (suffix << 8) | b;
            if (suffix == 0x0d0a0d0a) break;
        }
        if (suffix != 0x0d0a0d0a) throw new IOException("header_too_large");
        String[] lines = bytes.toString(StandardCharsets.US_ASCII.name()).split("\r\n");
        if (lines.length < 2 || !lines[0].equals("GET /control HTTP/1.1")) throw new IOException("wrong_endpoint");
        Map<String, String> h = new HashMap<>();
        for (int i = 1; i < lines.length; i++) {
            int colon = lines[i].indexOf(':');
            if (colon < 1) throw new IOException("invalid_header");
            String name = lines[i].substring(0, colon);
            if (!name.matches("[A-Za-z0-9-]+")) throw new IOException("invalid_header");
            name = name.toLowerCase(Locale.ROOT);
            String value = lines[i].substring(colon + 1);
            for (int at = 0; at < value.length(); at++) {
                char ch = value.charAt(at);
                if ((ch < 32 && ch != 9) || ch == 127) throw new IOException("invalid_header");
            }
            if (h.put(name, value.trim()) != null) throw new IOException("duplicate_header");
        }
        if (h.getOrDefault("host", "").isEmpty()) throw new IOException("host_required");
        if (h.containsKey("origin") || h.containsKey("transfer-encoding") || h.containsKey("content-length"))
            throw new IOException("unsupported_request");
        if (!"websocket".equalsIgnoreCase(h.get("upgrade")) || !"13".equals(h.get("sec-websocket-version")) ||
            !Arrays.stream(h.getOrDefault("connection", "").split(",")).anyMatch(v -> v.trim().equalsIgnoreCase("upgrade")))
            throw new IOException("websocket_required");
        try {
            if (Base64.getDecoder().decode(h.getOrDefault("sec-websocket-key", "")).length != 16) throw new IOException("invalid_key");
        } catch (IllegalArgumentException error) { throw new IOException("invalid_key"); }
        return h;
    }
    public static void upgrade(OutputStream out, String key) throws Exception {
        String accept = Base64.getEncoder().encodeToString(MessageDigest.getInstance("SHA-1")
            .digest((key + "258EAFA5-E914-47DA-95CA-C5AB0DC85B11").getBytes(StandardCharsets.US_ASCII)));
        out.write(("HTTP/1.1 101 Switching Protocols\r\nUpgrade: websocket\r\nConnection: Upgrade\r\nSec-WebSocket-Accept: "
            + accept + "\r\n\r\n").getBytes(StandardCharsets.US_ASCII)); out.flush();
    }
    public static Frame read(InputStream in) throws IOException {
        int first = one(in), second = one(in), opcode = first & 15;
        if ((first & 0x70) != 0 || (second & 0x80) == 0 || !Set.of(0, 1, 8, 9, 10).contains(opcode))
            throw new IOException("unsupported_frame");
        long length = second & 127;
        if (length == 126) { length = (one(in) << 8) | one(in); if (length < 126) throw new IOException("invalid_length"); }
        else if (length == 127) {
            length = 0;
            for (int i = 0; i < 8; i++) { int b = one(in); if (i < 4 && b != 0) throw new IOException("frame_too_large"); length = (length << 8) | b; }
            if (length < 65536) throw new IOException("invalid_length");
        }
        boolean fin = (first & 128) != 0;
        if (length > MAX_MESSAGE || (opcode >= 8 && (!fin || length > 125))) throw new IOException("frame_too_large");
        byte[] mask = exact(in, 4), payload = exact(in, (int)length);
        for (int i = 0; i < payload.length; i++) payload[i] ^= mask[i % 4];
        if (opcode == 8 && payload.length == 1) throw new IOException("invalid_close");
        return new Frame(fin, opcode, payload);
    }
    public static void write(OutputStream out, int opcode, byte[] payload) throws IOException {
        if (payload.length > MAX_MESSAGE) throw new IOException("message_too_large");
        out.write(128 | opcode);
        if (payload.length < 126) out.write(payload.length);
        else if (payload.length <= 65535) { out.write(126); out.write(payload.length >>> 8); out.write(payload.length); }
        else { out.write(127); for (int i = 7; i >= 0; i--) out.write((int)(((long)payload.length >>> (8 * i)) & 255)); }
        out.write(payload); out.flush();
    }
    public static String text(byte[] bytes) throws IOException {
        try { return StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString(); }
        catch (CharacterCodingException error) { throw new IOException("invalid_utf8"); }
    }
    private static int one(InputStream in) throws IOException { int v = in.read(); if (v < 0) throw new EOFException(); return v; }
    private static byte[] exact(InputStream in, int count) throws IOException {
        byte[] b = new byte[count]; int offset = 0;
        while (offset < count) { int n = in.read(b, offset, count - offset); if (n < 0) throw new EOFException(); offset += n; }
        return b;
    }
}
