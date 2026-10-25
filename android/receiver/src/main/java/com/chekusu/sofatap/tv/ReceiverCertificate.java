package com.chekusu.sofatap.tv;

import okhttp3.tls.HeldCertificate;
import org.bouncycastle.asn1.x500.X500Name;
import org.bouncycastle.asn1.x509.BasicConstraints;
import org.bouncycastle.asn1.x509.ExtendedKeyUsage;
import org.bouncycastle.asn1.x509.Extension;
import org.bouncycastle.asn1.x509.GeneralName;
import org.bouncycastle.asn1.x509.GeneralNames;
import org.bouncycastle.asn1.x509.KeyPurposeId;
import org.bouncycastle.asn1.x509.KeyUsage;
import org.bouncycastle.cert.X509CertificateHolder;
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder;
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder;
import java.io.ByteArrayInputStream;
import java.math.BigInteger;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.SecureRandom;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.security.spec.ECGenParameterSpec;
import java.util.Collection;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;

/** Pure-JVM server leaf generation shared by the receiver and native TLS interoperability tests. */
public final class ReceiverCertificate {
    private static final String SERVER_AUTH = "1.3.6.1.5.5.7.3.1";
    private static final long LIFETIME = TimeUnit.DAYS.toMillis(90);
    private static final long CLOCK_SKEW = TimeUnit.MINUTES.toMillis(5);

    private ReceiverCertificate() {}

    public static HeldCertificate generate(List<String> addresses) throws Exception {
        return generate(addresses, System.currentTimeMillis());
    }

    static HeldCertificate generate(List<String> addresses, long now) throws Exception {
        Set<String> literals = validateAddresses(addresses);
        SecureRandom random = new SecureRandom();
        KeyPairGenerator generator = KeyPairGenerator.getInstance("EC");
        generator.initialize(new ECGenParameterSpec("secp256r1"), random);
        KeyPair pair = generator.generateKeyPair();
        X500Name name = new X500Name("CN=Sofatap TV");
        JcaX509v3CertificateBuilder builder = new JcaX509v3CertificateBuilder(name,
                new BigInteger(159, random).setBit(158), new Date(now - CLOCK_SKEW),
                new Date(now + LIFETIME), name, pair.getPublic());
        builder.addExtension(Extension.basicConstraints, true, new BasicConstraints(false));
        builder.addExtension(Extension.keyUsage, true, new KeyUsage(KeyUsage.digitalSignature));
        builder.addExtension(Extension.extendedKeyUsage, false, new ExtendedKeyUsage(KeyPurposeId.id_kp_serverAuth));
        GeneralName[] sans = new GeneralName[literals.size()];
        int index = 0;
        for (String address : literals) sans[index++] = new GeneralName(GeneralName.iPAddress, address);
        builder.addExtension(Extension.subjectAlternativeName, false, new GeneralNames(sans));
        // Use the platform JCA signer and X.509 parser; do not install a provider or alter trust.
        X509CertificateHolder holder = builder.build(
                new JcaContentSignerBuilder("SHA256withECDSA").build(pair.getPrivate()));
        X509Certificate certificate = (X509Certificate) CertificateFactory.getInstance("X.509")
                .generateCertificate(new ByteArrayInputStream(holder.getEncoded()));
        certificate.verify(pair.getPublic());
        return new HeldCertificate(pair, certificate);
    }

    /** Old EKU-less identities are re-enrolled with a new QR; the client never weakens TLS policy. */
    static boolean reusable(X509Certificate certificate, List<String> addresses, long now) {
        try {
            validateAddresses(addresses);
            certificate.checkValidity(new Date(now));
            if (certificate.getNotAfter().getTime() <= now + TimeUnit.DAYS.toMillis(1)) return false;
            if (certificate.getNotAfter().getTime() - certificate.getNotBefore().getTime() > LIFETIME + CLOCK_SKEW) return false;
            if (certificate.getBasicConstraints() != -1) return false;
            if (!"1.2.840.10045.4.3.2".equals(certificate.getSigAlgOID())) return false;
            List<String> usages = certificate.getExtendedKeyUsage();
            if (usages == null || usages.size() != 1 || !usages.contains(SERVER_AUTH)) return false;
            boolean[] keyUsage = certificate.getKeyUsage();
            if (keyUsage == null || keyUsage.length == 0 || !keyUsage[0]) return false;
            for (int i = 1; i < keyUsage.length; i++) if (keyUsage[i]) return false;
            Set<String> critical = certificate.getCriticalExtensionOIDs();
            if (critical == null || !critical.contains("2.5.29.19") || !critical.contains("2.5.29.15")) return false;
            if (!matchesAddresses(certificate, addresses)) return false;
            certificate.verify(certificate.getPublicKey());
            return true;
        } catch (Exception invalid) { return false; }
    }

    static boolean matchesAddresses(X509Certificate certificate, List<String> addresses) throws Exception {
        Set<String> expected = validateAddresses(addresses);
        Collection<List<?>> sans = certificate.getSubjectAlternativeNames();
        if (sans == null) return false;
        Set<String> actual = new HashSet<>();
        for (List<?> san : sans) {
            if (san.size() != 2 || !Integer.valueOf(7).equals(san.get(0)) || !(san.get(1) instanceof String)) return false;
            actual.add((String) san.get(1));
        }
        return actual.equals(expected);
    }

    /** Null means a new identity is needed; an empty list means safely offline. */
    static List<String> reachableAddressesIfReusable(X509Certificate certificate,
            List<String> certifiedAddresses, List<String> currentAddresses, long now) {
        Set<String> certified = validateAddresses(certifiedAddresses);
        Set<String> current = currentAddresses.isEmpty() ? Collections.emptySet() : validateAddresses(currentAddresses);
        if (!reusable(certificate, certifiedAddresses, now)) return null;
        // Match desktop identity policy: adapter additions/removals preserve trust,
        // while replacement of an address requires explicit enrollment in a new leaf.
        boolean added = !certified.containsAll(current), removed = !current.containsAll(certified);
        if (added && removed) return null;
        List<String> reachable = new ArrayList<>();
        for (String address : current) if (certified.contains(address)) reachable.add(address);
        Collections.sort(reachable);
        return Collections.unmodifiableList(reachable);
    }

    private static Set<String> validateAddresses(List<String> addresses) {
        if (addresses == null || addresses.isEmpty() || addresses.size() > 16) throw new IllegalArgumentException("invalid_addresses");
        Set<String> result = new LinkedHashSet<>();
        for (String address : addresses) {
            if (address == null || !address.matches("(?:0|[1-9][0-9]{0,2})(?:\\.(?:0|[1-9][0-9]{0,2})){3}")) {
                throw new IllegalArgumentException("invalid_address");
            }
            for (String part : address.split("\\.")) {
                if (Integer.parseInt(part) > 255) throw new IllegalArgumentException("invalid_address");
            }
            result.add(address);
        }
        return result;
    }
}
