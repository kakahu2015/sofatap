package com.chekusu.sofatap.tv;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.io.UnsupportedEncodingException;

/** Pure QR formatting; metadata is an untrusted label, never a TLS identity. */
final class PairingUri {
    private PairingUri() { }

    static String deviceName(String value) {
        if (value == null || value.codePoints().anyMatch(point -> {
            int type = Character.getType(point);
            return type == Character.CONTROL || type == Character.FORMAT || type == Character.SURROGATE;
        })) return "Android TV";
        int start = 0, end = value.length();
        while (start < end && whitespace(value.codePointAt(start))) start += Character.charCount(value.codePointAt(start));
        while (end > start && whitespace(value.codePointBefore(end))) end -= Character.charCount(value.codePointBefore(end));
        String trimmed = value.substring(start, end);
        return !trimmed.isEmpty() && trimmed.codePointCount(0, trimmed.length()) <= 80
            && trimmed.getBytes(StandardCharsets.UTF_8).length <= 256 ? trimmed : "Android TV";
    }

    static String create(String address, int port, String token, String fingerprint, String name) {
        return "sofatap://tv?host=" + encode(address) + "&port=" + port + "&token=" + token
            + "&fingerprint=" + fingerprint + "&platform=androidtv&name=" + encode(deviceName(name));
    }

    private static boolean whitespace(int point) {
        return Character.isWhitespace(point) || Character.isSpaceChar(point);
    }
    private static String encode(String value) {
        try { return URLEncoder.encode(value, "UTF-8").replace("+", "%20"); }
        catch (UnsupportedEncodingException impossible) { throw new AssertionError(impossible); }
    }
}
