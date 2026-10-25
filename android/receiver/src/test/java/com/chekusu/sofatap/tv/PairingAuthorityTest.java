package com.chekusu.sofatap.tv;

import org.junit.Test;
import java.io.IOException;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;
import static org.junit.Assert.*;

public class PairingAuthorityTest {
    private static final class Store implements PairingAuthority.Persistence {
        String digest; boolean fail; final List<String> writes = new ArrayList<>();
        public void save(String value) throws Exception {
            if (fail) throw new IOException("simulated storage failure");
            digest = value; writes.add(value);
        }
    }

    @Test public void bootstrapIsOneTimeAndDurableCredentialIsStoredOnlyAsDigest() throws Exception {
        Store store = new Store(); AtomicLong time = new AtomicLong(1000);
        PairingAuthority authority = new PairingAuthority(null, time::get, store);
        String bootstrap = authority.newPairing();
        assertTrue(bootstrap.matches("[A-Za-z0-9_-]{43}")); assertFalse(authority.paired());
        PairingAuthority.Grant grant = authority.authorize(bootstrap);
        assertTrue(grant.allowed()); assertNotNull(grant.replacementToken()); assertNotEquals(bootstrap, grant.replacementToken());
        assertNull(authority.bootstrap()); assertTrue(authority.paired());
        assertEquals(PairingAuthority.hash(grant.replacementToken()), store.digest);
        assertNotEquals(grant.replacementToken(), store.digest); assertFalse(store.writes.contains(bootstrap));
        assertFalse(authority.authorize(bootstrap).allowed());
        assertTrue(authority.authorize(grant.replacementToken()).allowed());
        assertNull(authority.authorize(grant.replacementToken()).replacementToken());
        PairingAuthority restarted = new PairingAuthority(store.digest, time::get, store);
        assertTrue(restarted.authorize(grant.replacementToken()).allowed());
        assertFalse(restarted.authorize(bootstrap).allowed());
    }

    @Test public void expiryBoundaryRejectsExpiredQrWithoutIssuingCredential() throws Exception {
        Store store = new Store(); AtomicLong time = new AtomicLong(1000);
        PairingAuthority authority = new PairingAuthority(null, time::get, store);
        String bootstrap = authority.newPairing(); time.set(300999); assertEquals(bootstrap, authority.bootstrap());
        time.set(301000); assertNull(authority.bootstrap()); assertFalse(authority.authorize(bootstrap).allowed());
        assertNull(store.digest); assertFalse(authority.paired());
    }

    @Test public void invalidTokensCannotConsumeLiveQrOrWriteStorage() throws Exception {
        Store store = new Store(); PairingAuthority authority = new PairingAuthority(null, () -> 1000, store);
        String bootstrap = authority.newPairing(); int writes = store.writes.size();
        for (String token : Arrays.asList(null, "", "short", "x".repeat(42), "x".repeat(44), "=".repeat(43), "a\n".repeat(21) + "a", "x".repeat(43))) {
            assertFalse(authority.authorize(token).allowed()); assertEquals(bootstrap, authority.bootstrap());
        }
        assertEquals(writes, store.writes.size());
    }

    @Test public void newQrAndRevokeInvalidateOldCredentialsAndAdvanceGeneration() throws Exception {
        Store store = new Store(); PairingAuthority authority = new PairingAuthority(null, () -> 1000, store);
        String firstQr = authority.newPairing(); long initialGeneration = authority.generation();
        String firstToken = authority.authorize(firstQr).replacementToken(); String secondQr = authority.newPairing();
        assertTrue(authority.generation() > initialGeneration); assertFalse(authority.authorize(firstToken).allowed());
        assertFalse(authority.authorize(firstQr).allowed()); assertNull(store.digest);
        String secondToken = authority.authorize(secondQr).replacementToken(); long beforeRevoke = authority.generation();
        authority.revoke(); assertTrue(authority.generation() > beforeRevoke); assertFalse(authority.paired());
        assertNull(authority.bootstrap()); assertNull(store.digest); assertFalse(authority.authorize(secondToken).allowed());
    }

    @Test public void bootstrapPersistenceFailureConsumesQrAndNeverAuthorizesInMemory() throws Exception {
        Store store = new Store(); PairingAuthority authority = new PairingAuthority(null, () -> 1000, store);
        String bootstrap = authority.newPairing(); store.fail = true;
        assertThrows(IOException.class, () -> authority.authorize(bootstrap));
        assertNull(authority.bootstrap()); assertFalse(authority.paired()); assertNull(store.digest);
        store.fail = false; assertFalse(authority.authorize(bootstrap).allowed());
    }

    @Test public void failedRevocationReportsFailureAndDeniesOldCredentialInThisProcess() throws Exception {
        Store store = new Store(); PairingAuthority authority = new PairingAuthority(null, () -> 1000, store);
        String token = authority.authorize(authority.newPairing()).replacementToken(); long generation = authority.generation();
        store.fail = true; assertThrows(IOException.class, authority::revoke);
        assertFalse(authority.authorize(token).allowed()); assertFalse(authority.paired());
        assertTrue(authority.generation() > generation);
        // The caller must surface this error: persistence did not revoke the disk credential.
        assertNotNull(store.digest);
    }

    @Test public void failedNewPairingNeverPublishesQrOrKeepsOldMemoryCredential() throws Exception {
        Store store = new Store(); PairingAuthority authority = new PairingAuthority(null, () -> 1000, store);
        String token = authority.authorize(authority.newPairing()).replacementToken(); store.fail = true;
        assertThrows(IOException.class, authority::newPairing);
        assertNull(authority.bootstrap()); assertFalse(authority.authorize(token).allowed());
    }

    @Test public void retiringListenerDeniesStaleCallbacksWithoutRevokingSavedPairing() throws Exception {
        Store store = new Store(); PairingAuthority authority = new PairingAuthority(null, () -> 1000, store);
        String token = authority.authorize(authority.newPairing()).replacementToken();
        String saved = store.digest; int writes = store.writes.size(); long generation = authority.generation();
        authority.retire();
        assertFalse(authority.authorize(token).allowed());
        assertTrue(authority.generation() > generation);
        assertEquals(saved, store.digest); assertEquals(writes, store.writes.size());
        PairingAuthority restarted = new PairingAuthority(store.digest, () -> 1000, store);
        assertTrue(restarted.authorize(token).allowed());
    }

    @Test(timeout = 5000) public void concurrentBootstrapRequestsProduceExactlyOneGrant() throws Exception {
        Store store = new Store(); PairingAuthority authority = new PairingAuthority(null, () -> 1000, store);
        String bootstrap = authority.newPairing(); ExecutorService executor = Executors.newFixedThreadPool(8);
        CountDownLatch ready = new CountDownLatch(8), start = new CountDownLatch(1);
        List<Future<PairingAuthority.Grant>> attempts = new ArrayList<>();
        try {
            for (int i = 0; i < 8; i++) attempts.add(executor.submit(() -> { ready.countDown(); start.await(); return authority.authorize(bootstrap); }));
            assertTrue(ready.await(2, TimeUnit.SECONDS)); start.countDown(); int allowed = 0;
            for (Future<PairingAuthority.Grant> attempt : attempts) if (attempt.get(2, TimeUnit.SECONDS).allowed()) allowed++;
            assertEquals(1, allowed); assertEquals(2, store.writes.size()); assertTrue(authority.paired());
        } finally { start.countDown(); executor.shutdownNow(); }
    }
}
