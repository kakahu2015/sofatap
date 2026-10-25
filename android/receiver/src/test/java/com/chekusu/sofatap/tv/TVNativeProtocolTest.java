package com.chekusu.sofatap.tv;

import org.junit.Test;
import org.json.JSONObject;
import static org.junit.Assert.*;

public class TVNativeProtocolTest {
    private static final String ID = "123e4567-e89b-12d3-a456-426614174000";
    private JSONObject base(String type) throws Exception { return new JSONObject().put("id", ID).put("type", type); }
    @Test public void trueRemoteKeysHaveAnExplicitNamespaceSeparateFromNodeFocus() throws Exception {
        for (String key : new String[]{"up", "down", "left", "right", "confirm", "back", "home"}) {
            TVProtocol.Action action = TVProtocol.parse(base("remote_key").put("key", key).toString());
            assertTrue(action.extended()); assertEquals(key, action.key());
        }
        assertFalse(TVProtocol.parse(base("key").put("key", "confirm").toString()).extended());
        assertThrows(Exception.class, () -> TVProtocol.parse(base("remote_key").put("key", "power").toString()));
    }
    @Test public void pairingOnlyAcceptsSixHexDigitsAndNoHostOrPort() throws Exception {
        assertTrue(TVProtocol.parse(base("remote_pair_start").toString()).extended());
        assertEquals("Ab12F0", TVProtocol.parse(base("remote_pair_code").put("code", "Ab12F0").toString()).code());
        for (String code : new String[]{"12345", "1234567", "12345G", " 12345", "１２３４５６"})
            assertThrows(Exception.class, () -> TVProtocol.parse(base("remote_pair_code").put("code", code).toString()));
        assertThrows(Exception.class, () -> TVProtocol.parse(base("remote_pair_start").put("host", "127.0.0.1").toString()));
    }
    @Test public void textRequiresCanonicalEpochAndValidBoundedUnicode() throws Exception {
        String text = "中文 日本語 😺\n";
        assertEquals(text, TVProtocol.parse(base("text_commit").put("editorEpoch", ID).put("text", text).toString()).text());
        assertEquals(4096, TVProtocol.parse(base("text_commit").put("editorEpoch", ID).put("text", "a".repeat(4096)).toString()).text().length());
        for (String invalid : new String[]{"a".repeat(4097), "😺".repeat(1025), "\uD800", "\uDC00", "a\0b"})
            assertThrows(Exception.class, () -> TVProtocol.parse(base("text_commit").put("editorEpoch", ID).put("text", invalid).toString()));
        assertThrows(Exception.class, () -> TVProtocol.parse(base("text_commit").put("editorEpoch", "1-1-1-1-1").put("text", "x").toString()));
    }
    @Test public void textDeletionAndActionCannotSelectOtherEditorsOrSupplyArbitraryActions() throws Exception {
        for (String type : new String[]{"text_backspace", "text_action"}) {
            assertEquals(ID, TVProtocol.parse(base(type).put("editorEpoch", ID).toString()).editorEpoch());
            assertThrows(Exception.class, () -> TVProtocol.parse(base(type).put("editorEpoch", ID).put("action", "shell").toString()));
            assertThrows(Exception.class, () -> TVProtocol.parse(base(type).toString()));
        }
    }
}
