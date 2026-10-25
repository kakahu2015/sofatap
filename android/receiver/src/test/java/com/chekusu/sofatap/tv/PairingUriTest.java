package com.chekusu.sofatap.tv;

import org.junit.Test;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.*;
import static org.junit.Assert.*;

public class PairingUriTest {
    @Test public void qrKeepsEndpointSecretsAndAddsEncodedMetadata() throws Exception {
        String token = "D".repeat(43), fingerprint = "a".repeat(64);
        String name = "客厅 + TV 🛋️";
        String value = PairingUri.create("192.168.1.2", 53319, token, fingerprint, "\u00a0 " + name + "\u3000");
        URI uri = new URI(value);
        assertEquals("sofatap", uri.getScheme()); assertEquals("tv", uri.getHost());
        assertFalse(value.contains(" ")); assertFalse(value.contains("+"));
        Map<String, String> fields = new HashMap<>();
        for (String item : uri.getRawQuery().split("&")) {
            String[] parts = item.split("=", 2);
            assertNull(fields.put(parts[0], URLDecoder.decode(parts[1], StandardCharsets.UTF_8)));
        }
        assertEquals(6, fields.size()); assertEquals("192.168.1.2", fields.get("host"));
        assertEquals("53319", fields.get("port")); assertEquals(token, fields.get("token"));
        assertEquals(fingerprint, fields.get("fingerprint")); assertEquals("androidtv", fields.get("platform"));
        assertEquals(name, fields.get("name"));
    }

    @Test public void namesUseUnicodeScalarAndUtf8Bounds() {
        for (String name : new String[] { "a".repeat(80), "客".repeat(80), "😀".repeat(64), "e\u0301".repeat(40) })
            assertEquals(name, PairingUri.deviceName(name));
        assertEquals("TV", PairingUri.deviceName("\u00a0 \u3000TV\u2007\u202f"));
    }

    @Test public void unsafeOrOversizedSystemNamesFallBackWithoutTruncation() {
        for (String name : Arrays.asList(null, "", " \u00a0", "a".repeat(81), "😀".repeat(65), "e\u0301".repeat(41),
            "TV\n", "\tTV", "TV\u0000", "TV\u0085", "TV\u200b", "TV\u202e", "bad\ud800"))
            assertEquals("Android TV", PairingUri.deviceName(name));
        assertTrue(PairingUri.create("192.168.1.2", 53319, "D".repeat(43), "a".repeat(64), "\n")
            .endsWith("&platform=androidtv&name=Android%20TV"));
    }
}
