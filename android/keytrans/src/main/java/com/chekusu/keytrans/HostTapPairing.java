package com.chekusu.keytrans;

import java.io.ByteArrayOutputStream;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.URI;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/** Strict parser for a desktop sofatap://pair link. The name is display-only. */
final class HostTapPairing {
    final String host;
    final int port;
    final String token;
    final String fingerprint;
    final String platform;
    final String name;
    final String link;

    private HostTapPairing(String host, int port, String token, String fingerprint,
                           String platform, String name, String link) {
        this.host = host;
        this.port = port;
        this.token = token;
        this.fingerprint = fingerprint;
        this.platform = platform;
        this.name = name;
        this.link = link;
    }

    static HostTapPairing parse(String raw) {
        if (raw == null || raw.trim().getBytes(StandardCharsets.UTF_8).length > 2048)
            throw new IllegalArgumentException("配对链接无效");
        String trimmed = raw.trim();
        try {
            URI uri = new URI(trimmed);
            if (!"sofatap".equalsIgnoreCase(uri.getScheme()) || !"pair".equals(uri.getRawAuthority()) ||
                uri.getRawUserInfo() != null || uri.getPort() != -1 || uri.getRawPath() == null ||
                !uri.getRawPath().isEmpty() || uri.getRawFragment() != null || uri.getRawQuery() == null) {
                throw new IllegalArgumentException("请粘贴电脑接收器的 sofatap://pair 配对链接");
            }
            String[] parts = uri.getRawQuery().split("&", -1);
            if (parts.length < 4 || parts.length > 6) throw new IllegalArgumentException("配对字段不完整");
            Map<String, String> fields = new HashMap<>();
            for (String part : parts) {
                int equals = part.indexOf('=');
                if (equals <= 0) throw new IllegalArgumentException("配对字段无效");
                String key = decode(part.substring(0, equals));
                String value = decode(part.substring(equals + 1));
                if (!new java.util.HashSet<>(java.util.Arrays.asList("host", "port", "token", "fingerprint", "platform", "name")).contains(key) ||
                    fields.put(key, value) != null) throw new IllegalArgumentException("配对字段重复或未知");
            }
            String host = fields.get("host");
            String portText = fields.get("port");
            String token = fields.get("token");
            String fingerprint = fields.get("fingerprint");
            if (!validHost(host) || portText == null || !portText.matches("[0-9]{1,5}"))
                throw new IllegalArgumentException("主机地址或端口无效");
            int port = Integer.parseInt(portText);
            if (port < 1 || port > 65535 || token == null || token.length() < 32 || token.length() > 256 ||
                !token.matches("[A-Za-z0-9_-]+") || fingerprint == null || !fingerprint.matches("(?i)[0-9a-f]{64}"))
                throw new IllegalArgumentException("配对凭据无效");
            String platform = fields.get("platform");
            if (platform != null && !java.util.Arrays.asList("windows", "macos", "linux").contains(platform))
                throw new IllegalArgumentException("电脑平台字段无效");
            String name = fields.get("name");
            if (name != null) {
                if (name.codePoints().anyMatch(point -> {
                        int type = Character.getType(point);
                        return type == Character.CONTROL || type == Character.FORMAT || type == Character.SURROGATE;
                    })) throw new IllegalArgumentException("电脑名称字段无效");
                name = trimWhitespace(name);
                if (name.isEmpty() || name.codePointCount(0, name.length()) > 80 ||
                    name.getBytes(StandardCharsets.UTF_8).length > 256) throw new IllegalArgumentException("电脑名称字段无效");
            }
            return new HostTapPairing(host.toLowerCase(Locale.ROOT), port, token,
                fingerprint.toLowerCase(Locale.ROOT), platform, name, trimmed);
        } catch (IllegalArgumentException error) {
            throw error;
        } catch (Exception error) {
            throw new IllegalArgumentException("请粘贴有效的 sofatap://pair 配对链接", error);
        }
    }

    String endpoint() {
        String endpointHost = host.indexOf(':') >= 0 ? "[" + host + "]" : host;
        return "wss://" + endpointHost + ":" + port + "/control";
    }

    private static boolean validHost(String host) {
        if (host == null || host.isEmpty() || host.length() > 253 || !host.chars().allMatch(value -> value < 128)) return false;
        if (host.indexOf(':') >= 0) {
            if (host.indexOf('%') >= 0) return false;
            try { return InetAddress.getByName(host) instanceof Inet6Address; }
            catch (Exception ignored) { return false; }
        }
        String[] labels = host.split("\\.", -1);
        for (String label : labels) {
            if (label.isEmpty() || label.length() > 63 || label.startsWith("-") || label.endsWith("-") ||
                !label.matches("[A-Za-z0-9-]+")) return false;
        }
        return true;
    }

    private static String trimWhitespace(String value) {
        int start = 0, end = value.length();
        while (start < end) {
            int point = value.codePointAt(start);
            if (!Character.isWhitespace(point) && !Character.isSpaceChar(point)) break;
            start += Character.charCount(point);
        }
        while (end > start) {
            int point = value.codePointBefore(end);
            if (!Character.isWhitespace(point) && !Character.isSpaceChar(point)) break;
            end -= Character.charCount(point);
        }
        return value.substring(start, end);
    }

    /** Query '+' is literal in this protocol; only percent escapes are decoded. */
    private static String decode(String raw) throws CharacterCodingException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream(raw.length());
        for (int i = 0; i < raw.length();) {
            char c = raw.charAt(i);
            if (c == '%') {
                if (i + 2 >= raw.length()) throw new IllegalArgumentException("转义字段无效");
                int hi = Character.digit(raw.charAt(i + 1), 16), lo = Character.digit(raw.charAt(i + 2), 16);
                if (hi < 0 || lo < 0) throw new IllegalArgumentException("转义字段无效");
                bytes.write((hi << 4) | lo);
                i += 3;
            } else {
                int next = raw.offsetByCodePoints(i, 1);
                byte[] encoded = raw.substring(i, next).getBytes(StandardCharsets.UTF_8);
                bytes.write(encoded, 0, encoded.length);
                i = next;
            }
        }
        return StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes.toByteArray())).toString();
    }
}
