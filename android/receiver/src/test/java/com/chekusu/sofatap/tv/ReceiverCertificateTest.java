package com.chekusu.sofatap.tv;

import okhttp3.tls.HeldCertificate;
import org.junit.Test;
import java.security.cert.X509Certificate;
import java.security.interfaces.ECPublicKey;
import java.util.Arrays;
import java.util.Collections;
import java.util.concurrent.TimeUnit;
import static org.junit.Assert.*;

public class ReceiverCertificateTest {
    @Test public void serverLeafHasAppleCompatibleUsageAndExactIpSans() throws Exception {
        HeldCertificate held = ReceiverCertificate.generate(Arrays.asList("192.168.1.2", "10.0.0.2"));
        X509Certificate certificate = held.certificate();
        certificate.verify(certificate.getPublicKey());
        assertEquals(Collections.singletonList("1.3.6.1.5.5.7.3.1"), certificate.getExtendedKeyUsage());
        assertEquals(-1, certificate.getBasicConstraints());
        assertTrue(certificate.getCriticalExtensionOIDs().contains("2.5.29.19"));
        assertTrue(certificate.getCriticalExtensionOIDs().contains("2.5.29.15"));
        assertTrue(certificate.getKeyUsage()[0]);
        assertFalse(certificate.getKeyUsage()[5]);
        assertEquals(256, ((ECPublicKey) certificate.getPublicKey()).getParams().getCurve().getField().getFieldSize());
        assertTrue(ReceiverCertificate.reusable(certificate, Arrays.asList("10.0.0.2", "192.168.1.2"), System.currentTimeMillis()));
        assertFalse(ReceiverCertificate.reusable(certificate, Collections.singletonList("192.168.1.3"), System.currentTimeMillis()));
        assertFalse(ReceiverCertificate.reusable(certificate, Collections.singletonList("192.168.1.2"), System.currentTimeMillis()));
    }

    @Test public void pemRoundTripKeepsSameCertificateAndPrivateKey() throws Exception {
        HeldCertificate original = ReceiverCertificate.generate(Collections.singletonList("127.0.0.1"));
        HeldCertificate restored = HeldCertificate.decode(original.certificatePem() + original.privateKeyPkcs8Pem());
        assertArrayEquals(original.certificate().getEncoded(), restored.certificate().getEncoded());
        assertArrayEquals(original.keyPair().getPrivate().getEncoded(), restored.keyPair().getPrivate().getEncoded());
        assertTrue(ReceiverCertificate.reusable(restored.certificate(), Collections.singletonList("127.0.0.1"), System.currentTimeMillis()));
    }

    @Test public void ekuLessPreviousIdentityRequiresReEnrollment() throws Exception {
        HeldCertificate old = new HeldCertificate.Builder().commonName("Sofatap TV")
                .duration(90, TimeUnit.DAYS).addSubjectAlternativeName("192.168.1.2").build();
        assertNull(old.certificate().getExtendedKeyUsage());
        assertFalse(ReceiverCertificate.reusable(old.certificate(), Collections.singletonList("192.168.1.2"), System.currentTimeMillis()));
    }

    @Test public void expiredOrFutureCertificateCannotBeReused() throws Exception {
        long now = System.currentTimeMillis();
        X509Certificate current = ReceiverCertificate.generate(Collections.singletonList("192.168.1.2"), now).certificate();
        assertTrue(ReceiverCertificate.reusable(current, Collections.singletonList("192.168.1.2"), now));
        assertFalse(ReceiverCertificate.reusable(current, Collections.singletonList("192.168.1.2"), now - TimeUnit.HOURS.toMillis(1)));
        assertFalse(ReceiverCertificate.reusable(current, Collections.singletonList("192.168.1.2"), now + TimeUnit.DAYS.toMillis(89)));
        assertFalse(ReceiverCertificate.reusable(current, Collections.singletonList("192.168.1.2"), now + TimeUnit.DAYS.toMillis(91)));
    }

    @Test public void addressInputsCannotBecomeDnsWildcardsOrMalformedSans() throws Exception {
        for (String invalid : Arrays.asList("*.example.com", "example.com", "192.168.001.2", "256.1.2.3", "192.168.1.2,10.0.0.2", "::1", "")) {
            try {
                ReceiverCertificate.generate(Collections.singletonList(invalid));
                fail("Accepted malformed address");
            } catch (IllegalArgumentException expected) { }
        }
    }

    @Test public void interfaceChurnAndOfflineRetainOnlyReachableCertifiedAddresses() throws Exception {
        long now = System.currentTimeMillis();
        java.util.List<String> certified = Arrays.asList("192.168.1.2", "10.0.0.2");
        X509Certificate certificate = ReceiverCertificate.generate(certified, now).certificate();
        assertEquals(Arrays.asList("10.0.0.2", "192.168.1.2"), ReceiverCertificate.reachableAddressesIfReusable(
                certificate, certified, Arrays.asList("10.0.0.2", "192.168.1.2"), now));
        assertEquals(Arrays.asList("10.0.0.2", "192.168.1.2"), ReceiverCertificate.reachableAddressesIfReusable(
                certificate, certified, Arrays.asList("192.168.1.2", "10.8.0.2", "10.0.0.2"), now));
        assertEquals(Collections.singletonList("192.168.1.2"), ReceiverCertificate.reachableAddressesIfReusable(
                certificate, certified, Collections.singletonList("192.168.1.2"), now));
        assertEquals(Collections.emptyList(), ReceiverCertificate.reachableAddressesIfReusable(
                certificate, certified, Collections.emptyList(), now));
        assertEquals(Arrays.asList("10.0.0.2", "192.168.1.2"), ReceiverCertificate.reachableAddressesIfReusable(
                certificate, certified, certified, now));
    }

    @Test public void actualAddressReplacementRequiresNewIdentityEvenWithStableOtherAdapter() throws Exception {
        long now = System.currentTimeMillis();
        java.util.List<String> certified = Arrays.asList("192.168.1.2", "10.0.0.2");
        X509Certificate certificate = ReceiverCertificate.generate(certified, now).certificate();
        assertNull(ReceiverCertificate.reachableAddressesIfReusable(certificate, certified,
                Arrays.asList("192.168.1.3", "10.0.0.2"), now));
        assertNull(ReceiverCertificate.reachableAddressesIfReusable(certificate, certified,
                Collections.singletonList("192.168.2.2"), now));
    }

    @Test public void expiryCannotBeHiddenByOfflineOrUnchangedNetwork() throws Exception {
        long now = System.currentTimeMillis();
        java.util.List<String> certified = Collections.singletonList("192.168.1.2");
        X509Certificate certificate = ReceiverCertificate.generate(certified, now).certificate();
        long expired = now + TimeUnit.DAYS.toMillis(91);
        assertNull(ReceiverCertificate.reachableAddressesIfReusable(certificate, certified, certified, expired));
        assertNull(ReceiverCertificate.reachableAddressesIfReusable(certificate, certified, Collections.emptyList(), expired));
    }
}
