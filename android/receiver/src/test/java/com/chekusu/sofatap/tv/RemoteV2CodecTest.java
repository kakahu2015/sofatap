package com.chekusu.sofatap.tv;

import org.junit.Test;
import java.io.*;
import java.util.*;
import static org.junit.Assert.*;

public class RemoteV2CodecTest {
    @Test public void directionalKeysUseRealShortKeycodeInjection() throws Exception {
        int[] codes = {19,20,21,22,23,4,3}; String[] keys = {"up","down","left","right","confirm","back","home"};
        for (int n = 0; n < keys.length; n++) {
            byte[] inject = RemoteV2Codec.nested(RemoteV2Codec.key(keys[n]), 10);
            assertEquals(codes[n], RemoteV2Codec.integer(inject, 1, -1)); assertEquals(3, RemoteV2Codec.integer(inject, 2, -1));
        }
        assertThrows(IOException.class, () -> RemoteV2Codec.key("shell"));
    }
    @Test public void fragmentedReadsAndMultiByteLengthAreReassembled() throws Exception {
        byte[] payload = new byte[300]; Arrays.fill(payload, (byte)42); ByteArrayOutputStream framed = new ByteArrayOutputStream(); RemoteV2Codec.write(framed, payload);
        InputStream oneByte = new FilterInputStream(new ByteArrayInputStream(framed.toByteArray())) { @Override public int read(byte[] b,int o,int l) throws IOException { return super.read(b,o,Math.min(l,1)); } };
        assertArrayEquals(payload, RemoteV2Codec.read(oneByte));
    }
    @Test public void maliciousLengthsTruncationAndDuplicateSingletonsFailClosed() throws Exception {
        for (byte[] frame : new byte[][]{{0},{(byte)0xff,(byte)0xff,0x7f},{(byte)0x80,(byte)0x80,(byte)0x80,(byte)0x80,1},{2,1}})
            assertThrows(IOException.class, () -> RemoteV2Codec.read(new ByteArrayInputStream(frame)));
        assertThrows(IOException.class, () -> RemoteV2Codec.field(RemoteV2Codec.join(RemoteV2Codec.number(1,1),RemoteV2Codec.number(1,2)),1));
        assertThrows(IOException.class, () -> RemoteV2Codec.fields(new byte[]{10,12,1}));
        assertThrows(IOException.class, () -> RemoteV2Codec.fields(new byte[]{11}));
        // A 64-bit tag cannot wrap into a valid 32-bit singleton field number.
        assertThrows(IOException.class, () -> RemoteV2Codec.fields(new byte[]{(byte)0x88,(byte)0x80,(byte)0x80,(byte)0x80,(byte)0x80,1,0}));
    }
    @Test public void pairingRejectsWrongVersionStatusOrMissingExpectedStage() throws Exception {
        RemoteV2Codec.expectPairing(RemoteV2Codec.pairing(11,new byte[0]),11);
        assertThrows(IOException.class, () -> RemoteV2Codec.expectPairing(RemoteV2Codec.join(RemoteV2Codec.number(1,2),RemoteV2Codec.number(2,402),RemoteV2Codec.bytes(11,new byte[0])),11));
        assertThrows(IOException.class, () -> RemoteV2Codec.expectPairing(RemoteV2Codec.pairing(20,new byte[0]),11));
    }
}
