package com.chekusu.sofatap.tv;

import android.content.Context;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.AtomicFile;
import okhttp3.tls.HeldCertificate;
import org.json.JSONObject;
import javax.crypto.*;
import javax.crypto.spec.GCMParameterSpec;
import javax.net.ssl.*;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.util.*;

/** Exportable TLS identity encrypted at rest under a non-exportable Android Keystore key. */
final class ReceiverIdentity {
    private static final String KEY_ALIAS = "sofatap.tv.storage.v1";
    private final AtomicFile file;
    private final SecretKey storageKey;
    final HeldCertificate certificate;
    private String pairedDigest;
    private final List<String> certificateAddresses;
    volatile List<String> addresses;

    ReceiverIdentity(Context context) throws Exception {
        this(context, localAddresses(), System.currentTimeMillis());
    }
    ReceiverIdentity(Context context, List<String> currentAddresses, long now) throws Exception {
        file = new AtomicFile(new File(context.getNoBackupFilesDir(), "receiver-identity.enc"));
        KeyStore android = KeyStore.getInstance("AndroidKeyStore"); android.load(null);
        if (!android.containsAlias(KEY_ALIAS)) {
            KeyGenerator generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore");
            generator.init(new KeyGenParameterSpec.Builder(KEY_ALIAS, KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build());
            generator.generateKey();
        }
        storageKey = (SecretKey)android.getKey(KEY_ALIAS, null);
        HeldCertificate restored = null;
        List<String> savedAddresses = null, reachable = null;
        if (file.getBaseFile().exists()) {
            // Storage/key corruption is visible; never silently replace an encrypted identity.
            JSONObject saved = new JSONObject(decrypt(file.readFully()));
            restored = HeldCertificate.decode(saved.getString("pem"));
            savedAddresses = Arrays.asList(saved.getString("addresses").split(",", -1));
            if (!ReceiverCertificate.matchesAddresses(restored.certificate(), savedAddresses)) throw new IOException("invalid_identity");
            if (!saved.has("pairedDigest")) throw new IOException("invalid_identity");
            pairedDigest = saved.isNull("pairedDigest") ? null : saved.getString("pairedDigest");
            if (pairedDigest != null && !pairedDigest.matches("[a-f0-9]{64}")) throw new IOException("invalid_identity");
            reachable = ReceiverCertificate.reachableAddressesIfReusable(restored.certificate(), savedAddresses, currentAddresses, now);
            if (reachable == null) { restored = null; pairedDigest = null; } // Changed address/certificate requires visible new QR enrollment.
        }
        boolean created = restored == null;
        if (restored == null) {
            // Never overwrite a persisted identity while there is no reachable network.
            if (currentAddresses.isEmpty()) throw new IOException("no_private_network");
            savedAddresses = new ArrayList<>(new TreeSet<>(currentAddresses));
            restored = ReceiverCertificate.generate(savedAddresses, now);
            reachable = Collections.unmodifiableList(new ArrayList<>(savedAddresses));
        }
        certificate = restored;
        certificateAddresses = Collections.unmodifiableList(new ArrayList<>(savedAddresses));
        addresses = reachable;
        if (created) saveDigest(pairedDigest);
    }
    synchronized boolean refreshAddresses(List<String> currentAddresses, long now) {
        List<String> reachable = ReceiverCertificate.reachableAddressesIfReusable(certificate.certificate(), certificateAddresses, currentAddresses, now);
        if (reachable == null) return false;
        addresses = reachable;
        return true;
    }
    synchronized String pairedDigest() { return pairedDigest; }
    synchronized void saveDigest(String digest) throws Exception {
        JSONObject value = new JSONObject().put("pem", certificate.certificatePem() + certificate.privateKeyPkcs8Pem())
            .put("addresses", String.join(",", certificateAddresses)).put("pairedDigest", digest == null ? JSONObject.NULL : digest);
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding"); cipher.init(Cipher.ENCRYPT_MODE, storageKey);
        byte[] body = cipher.doFinal(value.toString().getBytes(StandardCharsets.UTF_8)), iv = cipher.getIV();
        FileOutputStream output = file.startWrite();
        try { output.write(iv.length); output.write(iv); output.write(body); file.finishWrite(output); pairedDigest = digest; }
        catch (Exception error) { file.failWrite(output); throw error; }
    }
    private String decrypt(byte[] bytes) throws Exception {
        if (bytes.length < 30 || bytes.length > 32_768 || (bytes[0] & 255) != 12) throw new IOException("invalid_identity");
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.DECRYPT_MODE, storageKey, new GCMParameterSpec(128, Arrays.copyOfRange(bytes, 1, 13)));
        return new String(cipher.doFinal(bytes, 13, bytes.length - 13), StandardCharsets.UTF_8);
    }
    String fingerprint() throws Exception {
        return PairingAuthority.hex(MessageDigest.getInstance("SHA-256").digest(certificate.certificate().getEncoded()));
    }
    SSLContext tls() throws Exception {
        KeyStore keys = KeyStore.getInstance(KeyStore.getDefaultType()); keys.load(null);
        char[] password = PairingAuthority.randomToken().toCharArray();
        keys.setKeyEntry("receiver", certificate.keyPair().getPrivate(), password,
            new java.security.cert.Certificate[]{certificate.certificate()});
        KeyManagerFactory km = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm()); km.init(keys, password);
        SSLContext tls = SSLContext.getInstance("TLS"); tls.init(km.getKeyManagers(), null, new SecureRandom());
        return tls;
    }
    static List<String> localAddresses() throws SocketException {
        List<String> result = new ArrayList<>();
        Enumeration<NetworkInterface> interfaces = NetworkInterface.getNetworkInterfaces();
        while (interfaces != null && interfaces.hasMoreElements()) {
            NetworkInterface network = interfaces.nextElement();
            if (!network.isUp() || network.isLoopback()) continue;
            Enumeration<InetAddress> items = network.getInetAddresses();
            while (items.hasMoreElements()) {
                InetAddress address = items.nextElement();
                if (address instanceof Inet4Address && address.isSiteLocalAddress()) result.add(address.getHostAddress());
            }
        }
        return new ArrayList<>(new TreeSet<>(result));
    }
}
