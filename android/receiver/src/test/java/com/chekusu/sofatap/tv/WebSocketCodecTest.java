package com.chekusu.sofatap.tv;

import org.junit.Test;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Map;
import static org.junit.Assert.*;

/** Independent wire bytes, not a round trip through the production encoder. */
public class WebSocketCodecTest {
    private static final String KEY = "dGhlIHNhbXBsZSBub25jZQ==";
    private static final String REQUEST = "GET /control HTTP/1.1\r\nHost: 127.0.0.1:9443\r\n"
        + "Upgrade: websocket\r\nConnection: keep-alive, Upgrade\r\nSec-WebSocket-Key: " + KEY
        + "\r\nSec-WebSocket-Version: 13\r\nAuthorization: Bearer test-token\r\n\r\n";

    @Test public void parsesNativeUpgradeAndPreservesCredentialForSeparateAuthorization() throws Exception {
        Map<String, String> headers = WebSocketCodec.request(ascii(REQUEST));
        assertEquals("Bearer test-token", headers.get("authorization"));
        assertEquals(KEY, headers.get("sec-websocket-key"));
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        WebSocketCodec.upgrade(output, KEY);
        assertEquals("HTTP/1.1 101 Switching Protocols\r\nUpgrade: websocket\r\nConnection: Upgrade\r\n"
            + "Sec-WebSocket-Accept: s3pPLMBiTxaQ9kYGzzhZRbK+xOo=\r\n\r\n", output.toString("US-ASCII"));
    }

    @Test public void rejectsWrongEndpointsAndHttpSmugglingShapes() {
        for (String request : Arrays.asList(
            REQUEST.replace("GET /control", "POST /control"),
            REQUEST.replace("/control", "/control?token=secret"),
            REQUEST.replace("HTTP/1.1", "HTTP/1.0"),
            REQUEST.replace("Upgrade: websocket", "Upgrade: websocket\r\nuPgRaDe: websocket"),
            REQUEST.replace("Upgrade: websocket", "Upgrade: websocket\r\nOrigin: https://evil.invalid"),
            REQUEST.replace("Upgrade: websocket", "Upgrade: websocket\r\nContent-Length: 0"),
            REQUEST.replace("Upgrade: websocket", "Upgrade: websocket\r\nTransfer-Encoding: chunked"),
            REQUEST.replace("Upgrade: websocket", "Upgrade : websocket"),
            REQUEST.replace("Upgrade: websocket", " Upgrade: websocket"),
            REQUEST.replace("Version: 13", "Version: 12"),
            REQUEST.replace(KEY, "YQ=="),
            REQUEST.replace(KEY, "%%%"),
            REQUEST.replace("Connection: keep-alive, Upgrade", "Connection: keep-alive"),
            REQUEST.replace("test-token", "bad\u0000token"))) {
            assertThrows("Accepted malformed request: " + request, IOException.class,
                () -> WebSocketCodec.request(ascii(request)));
        }
    }

    @Test public void requiresHostAndRejectsHeaderControlCharacters() {
        for (String request : Arrays.asList(
            REQUEST.replace("Host: 127.0.0.1:9443\r\n", ""),
            REQUEST.replace("127.0.0.1:9443", ""),
            REQUEST.replace("test-token", "bad\ntoken"),
            REQUEST.replace("test-token", "bad\rtoken"),
            REQUEST.replace("test-token", "bad\ntwo\nlines"),
            REQUEST.replace("test-token", "bad\rthen\nmore"),
            REQUEST.replace("test-token", "bad\u0001token"),
            REQUEST.replace("test-token", "bad\u007ftoken"))) {
            assertThrows("Accepted malformed request: " + request, IOException.class, () -> WebSocketCodec.request(ascii(request)));
        }
    }

    @Test public void boundsHeadersWithoutConsumingUnboundedInput() {
        String oversized = REQUEST.replace("test-token", "x".repeat(9000));
        ByteArrayInputStream input = ascii(oversized);
        assertThrows(IOException.class, () -> WebSocketCodec.request(input));
        assertTrue("Parser exceeded its 8KiB envelope", input.available() >= oversized.length() - 8192);
        assertThrows(IOException.class, () -> WebSocketCodec.request(ascii(REQUEST.substring(0, REQUEST.length() - 2))));
    }

    @Test public void readsMaskedUtf8AndDoesNotConsumeFollowingFrame() throws Exception {
        byte[] message = "沙发瘫🙂".getBytes(StandardCharsets.UTF_8);
        ByteArrayOutputStream wire = new ByteArrayOutputStream();
        wire.write(masked(0x81, message)); wire.write(masked(0x89, new byte[]{7}));
        ByteArrayInputStream input = new ByteArrayInputStream(wire.toByteArray());
        WebSocketCodec.Frame first = WebSocketCodec.read(input);
        assertTrue(first.fin()); assertEquals(1, first.opcode());
        assertArrayEquals(message, first.payload()); assertEquals("沙发瘫🙂", WebSocketCodec.text(first.payload()));
        assertEquals(9, WebSocketCodec.read(input).opcode()); assertEquals(0, input.available());
    }

    @Test public void readsAllCanonicalLengthBoundaries() throws Exception {
        for (int size : new int[]{0, 1, 125, 126, 65535, 65536}) {
            byte[] message = new byte[size]; Arrays.fill(message, (byte)'x');
            assertArrayEquals("size " + size, message, WebSocketCodec.read(new ByteArrayInputStream(masked(0x81, message))).payload());
        }
    }

    @Test public void rejectsFrameFlagsUnsupportedOpcodesAndControlViolations() throws Exception {
        for (int first : new int[]{0xC1, 0xA1, 0x91, 0x82, 0x83, 0x8B, 0x09, 0x08}) {
            byte[] wire = masked(first, new byte[0]);
            assertThrows(IOException.class, () -> WebSocketCodec.read(new ByteArrayInputStream(wire)));
        }
        assertThrows(IOException.class, () -> WebSocketCodec.read(new ByteArrayInputStream(new byte[]{(byte)0x81, 0})));
        assertThrows(IOException.class, () -> WebSocketCodec.read(new ByteArrayInputStream(masked(0x89, new byte[126]))));
        assertThrows(IOException.class, () -> WebSocketCodec.read(new ByteArrayInputStream(masked(0x88, new byte[1]))));
    }

    @Test public void rejectsOversizeAndNoncanonicalLengthBeforeReadingPayload() {
        for (byte[] wire : new byte[][] {
            {(byte)0x81, (byte)0xfe, 0, 125},
            {(byte)0x81, (byte)0xff, 0, 0, 0, 0, 0, 0, (byte)0xff, (byte)0xff},
            {(byte)0x81, (byte)0xff, 0, 0, 0, 0, 0, 1, 0, 1},
            {(byte)0x81, (byte)0xff, (byte)0x80, 0, 0, 0, 0, 0, 0, 0}
        }) {
            assertThrows(IOException.class, () -> WebSocketCodec.read(new ByteArrayInputStream(wire)));
        }
    }

    @Test public void rejectsTruncatedFramesAndInvalidUtf8() throws Exception {
        byte[] frame = masked(0x81, new byte[]{1, 2, 3});
        for (int end = 0; end < frame.length; end++) {
            byte[] truncated = Arrays.copyOf(frame, end);
            assertThrows(IOException.class, () -> WebSocketCodec.read(new ByteArrayInputStream(truncated)));
        }
        for (byte[] invalid : new byte[][]{{(byte)0xc0, (byte)0xaf}, {(byte)0xed, (byte)0xa0, (byte)0x80}, {(byte)0xf0, (byte)0x9f}})
            assertThrows(IOException.class, () -> WebSocketCodec.text(invalid));
    }

    @Test public void writesServerFramesWithoutMaskOrLengthTruncation() throws Exception {
        for (int size : new int[]{0, 125, 126, 65535, 65536}) {
            byte[] payload = new byte[size]; Arrays.fill(payload, (byte)42);
            ByteArrayOutputStream output = new ByteArrayOutputStream(); WebSocketCodec.write(output, 1, payload);
            DataInputStream input = new DataInputStream(new ByteArrayInputStream(output.toByteArray()));
            assertEquals(0x81, input.readUnsignedByte());
            int length = input.readUnsignedByte(); assertEquals(0, length & 0x80);
            long decoded = length == 126 ? input.readUnsignedShort() : length == 127 ? input.readLong() : length;
            assertEquals(size, decoded); byte[] actual = new byte[size]; input.readFully(actual);
            assertArrayEquals(payload, actual); assertEquals(-1, input.read());
        }
        assertThrows(IOException.class, () -> WebSocketCodec.write(new ByteArrayOutputStream(), 1, new byte[65537]));
    }

    private static ByteArrayInputStream ascii(String text) { return new ByteArrayInputStream(text.getBytes(StandardCharsets.US_ASCII)); }
    private static byte[] masked(int first, byte[] payload) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream(); DataOutputStream data = new DataOutputStream(output);
        data.writeByte(first);
        if (payload.length < 126) data.writeByte(0x80 | payload.length);
        else if (payload.length <= 65535) { data.writeByte(0xfe); data.writeShort(payload.length); }
        else { data.writeByte(0xff); data.writeLong(payload.length); }
        byte[] mask = {0x37, (byte)0xfa, 0x21, 0x3d}; data.write(mask);
        for (int i = 0; i < payload.length; i++) data.writeByte(payload[i] ^ mask[i % mask.length]);
        return output.toByteArray();
    }
}
