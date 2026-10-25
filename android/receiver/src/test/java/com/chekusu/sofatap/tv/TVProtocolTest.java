package com.chekusu.sofatap.tv;

import org.junit.Test;
import java.util.Arrays;
import static org.junit.Assert.*;

public class TVProtocolTest {
    private static final String ID = "123e4567-e89b-12d3-a456-426614174000";
    private static String body(String fields) { return "{\"id\":\"" + ID + "\"," + fields + "}"; }

    @Test public void acceptsOnlyManualCapabilitiesWithFiniteBoundedDeltas() throws Exception {
        TVProtocol.Action move = TVProtocol.parse(body("\"type\":\"move\",\"dx\":-500,\"dy\":0.125"));
        assertEquals(ID, move.id()); assertEquals(-500, move.dx(), 0); assertEquals(.125, move.dy(), 0);
        TVProtocol.Action scroll = TVProtocol.parse(body("\"type\":\"scroll\",\"dx\":1200,\"dy\":-1200"));
        assertEquals(1200, scroll.dx(), 0);
        for (String type : Arrays.asList("click", "ping")) assertEquals(type, TVProtocol.parse(body("\"type\":\"" + type + "\"")).type());
        for (String key : Arrays.asList("back", "home", "up", "down", "left", "right", "confirm"))
            assertEquals(key, TVProtocol.parse(body("\"type\":\"key\",\"key\":\"" + key + "\"")).key());
    }

    @Test public void rejectsTypeConfusionPrivilegeExpansionAndUnexpectedFields() {
        for (String fields : Arrays.asList(
            "\"type\":\"launch\",\"key\":\"settings\"", "\"type\":\"shell\"", "\"type\":1",
            "\"type\":\"key\",\"key\":\"power\"", "\"type\":\"key\",\"key\":1",
            "\"type\":\"click\",\"dx\":0", "\"type\":\"click\",\"script\":\"anything\"",
            "\"type\":\"move\",\"dx\":1", "\"type\":\"move\",\"dx\":\"1\",\"dy\":0",
            "\"type\":\"move\",\"dx\":true,\"dy\":0", "\"type\":\"move\",\"dx\":null,\"dy\":0",
            "\"type\":\"move\",\"dx\":500.01,\"dy\":0", "\"type\":\"move\",\"dx\":0,\"dy\":-500.01",
            "\"type\":\"scroll\",\"dx\":1200.01,\"dy\":0", "\"type\":\"move\",\"dx\":1e999,\"dy\":0",
            "\"type\":\"move\",\"dx\":[],\"dy\":0", "\"type\":\"move\",\"dx\":{},\"dy\":0"))
            assertThrows("Accepted fields " + fields, Exception.class, () -> TVProtocol.parse(body(fields)));
    }

    @Test public void rejectsDuplicateIncludingEscapedFieldNamesAndTrailingEnvelopes() {
        for (String json : Arrays.asList(
            body("\"type\":\"click\",\"type\":\"ping\""),
            body("\"type\":\"click\",\"\\u0074ype\":\"ping\""),
            body("\"type\":\"click\",\"id\":\"" + ID + "\""),
            body("\"type\":\"click\"") + "{}", "[]", "null", "{}",
            body("\"type\":\"click\",") ))
            assertThrows(Exception.class, () -> TVProtocol.parse(json));
    }

    @Test public void requiresCanonicalUuidRatherThanJdkAbbreviatedUuid() {
        for (String id : Arrays.asList("1-1-1-1-1", "not-an-id", ID + " ", "", "123e4567-e89b-12d3-a456-42661417400"))
            assertThrows(Exception.class, () -> TVProtocol.parse(body("\"type\":\"click\"").replace(ID, id)));
    }

    @Test public void rejectsLenientJsonSyntaxAndEndSentinelSmuggling() {
        for (String json : Arrays.asList(
            body("\"type\":click"), body("\"type\":'click'"),
            body("\"type\":\"click\"") + "\u0000{}",
            body("\"type\":\"move\",\"dx\":01,\"dy\":0"),
            body("\"type\":\"move\",\"dx\":+1,\"dy\":0"),
            body("\"type\":\"move\",\"dx\":.1,\"dy\":0"),
            body("\"type\":\"move\",\"dx\":1.,\"dy\":0")))
            assertThrows("Accepted non-JSON syntax: " + json, Exception.class, () -> TVProtocol.parse(json));
    }

    @Test(timeout = 1000) public void rejectsDeepNestedValuesWithoutRecursionOrFatalError() {
        String nested = "[".repeat(12000) + "0" + "]".repeat(12000);
        assertThrows(Exception.class, () -> TVProtocol.parse(body("\"type\":\"move\",\"dx\":" + nested + ",\"dy\":0")));
    }

    @Test public void rejectsAndroidTokenizerCommentsAtEveryEnvelopeBoundary() {
        String valid = body("\"type\":\"click\"");
        for (String comment : Arrays.asList("/*comment*/", "//comment\n", "#comment\n")) {
            for (String json : Arrays.asList(comment + valid, valid + comment,
                valid.replace("{", "{" + comment), valid.replace(",\"type\"", "," + comment + "\"type\""),
                valid.replace(":\"click\"", ":" + comment + "\"click\"")))
                assertThrows("Comment accepted: " + json, Exception.class, () -> TVProtocol.parse(json));
        }
    }

    @Test public void rejectsNonstandardStringEscapesAndRawControls() {
        for (String type : Arrays.asList("cl\\ick", "cl\\x69ck", "cl\\vick", "cl\\'ick", "cl\\u069ck",
            "cl\\u００６９ck", "cl\tick", "cl\nick", "cl\rick", "cl\u0001ick")) {
            assertThrows(Exception.class, () -> TVProtocol.parse(body("\"type\":\"" + type + "\"")));
        }
        for (String prefix : Arrays.asList("\u0001", "\u000b", "\u000c", "\ufeff"))
            assertThrows(Exception.class, () -> TVProtocol.parse(prefix + body("\"type\":\"click\"")));
    }

    @Test public void acceptsCanonicalEscapesWhitespaceAndExponentNumbersAcrossTokenizers() throws Exception {
        String click = "\t\r\n" + body("\"t\\u0079pe\":\"cl\\u0069ck\"") + " \n";
        assertEquals("click", TVProtocol.parse(click).type());
        TVProtocol.Action move = TVProtocol.parse(body("\"type\":\"move\",\"dx\":-1.25e+2,\"dy\":2E-1"));
        assertEquals(-125, move.dx(), 0); assertEquals(.2, move.dy(), 0);
    }

    @Test public void rejectsAndroidTokenizerAlternateSeparatorsAndUnquotedKeys() {
        String valid = body("\"type\":\"click\"");
        for (String json : Arrays.asList(valid.replace(":\"click\"", "=\"click\""),
            valid.replace(":\"click\"", "=>\"click\""), valid.replace(",\"type\"", ";\"type\""),
            valid.replace("\"type\"", "type"), valid.replace("\"type\"", "'type'")))
            assertThrows(Exception.class, () -> TVProtocol.parse(json));
    }

    @Test public void acknowledgmentCorrelatesAndKeepsFailureExplicit() throws Exception {
        assertEquals(ID, TVProtocol.ack(ID, true, null).getString("id"));
        assertTrue(TVProtocol.ack(ID, true, null).getBoolean("ok"));
        assertFalse(TVProtocol.ack(ID, false, "unsupported").getBoolean("ok"));
        assertEquals("unsupported", TVProtocol.ack(ID, false, "unsupported").getString("error"));
        assertFalse(TVProtocol.ack(ID, true, null).has("error"));
    }
}
