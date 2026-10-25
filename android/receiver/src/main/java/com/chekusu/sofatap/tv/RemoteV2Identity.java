package com.chekusu.sofatap.tv;

import android.content.Context;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.AtomicFile;
import okhttp3.tls.HeldCertificate;
import org.json.JSONObject;
import javax.crypto.*;
import javax.crypto.spec.GCMParameterSpec;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.util.*;
import java.util.concurrent.TimeUnit;

/** Separate encrypted credentials for the TV's own Remote Service; never included in QR payloads. */
final class RemoteV2Identity implements RemoteV2Client.Identity {
    private final AtomicFile file;
    private final SecretKey storageKey;
    private final HeldCertificate client;
    private volatile byte[] peer;
    RemoteV2Identity(Context context) throws Exception {
        file = new AtomicFile(new File(context.getNoBackupFilesDir(), "remote-v2-identity.enc"));
        String alias = "sofatap.tv.remote.v2.storage";
        KeyStore store = KeyStore.getInstance("AndroidKeyStore"); store.load(null);
        if (!store.containsAlias(alias)) {
            KeyGenerator generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore");
            generator.init(new KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build()); generator.generateKey();
        }
        storageKey = (SecretKey)store.getKey(alias, null);
        if (file.getBaseFile().exists()) {
            byte[] encrypted = file.readFully();
            if (encrypted.length < 30 || encrypted.length > 32768 || encrypted[0] != 12) throw new IOException("remote_identity_invalid");
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding"); cipher.init(Cipher.DECRYPT_MODE, storageKey, new GCMParameterSpec(128, Arrays.copyOfRange(encrypted, 1, 13)));
            JSONObject value = new JSONObject(new String(cipher.doFinal(encrypted, 13, encrypted.length - 13), StandardCharsets.UTF_8));
            client = HeldCertificate.decode(value.getString("pem"));
            if (!value.isNull("peer")) peer = Base64.getDecoder().decode(value.getString("peer"));
            if (peer != null && (peer.length < 128 || peer.length > 16384)) throw new IOException("remote_identity_invalid");
        } else {
            client = new HeldCertificate.Builder().rsa2048().commonName("Sofatap Android TV Remote").duration(3650, TimeUnit.DAYS).build(); savePin(null);
        }
    }
    @Override public HeldCertificate client() { return client; }
    @Override public byte[] pin() { byte[] value = peer; return value == null ? null : value.clone(); }
    @Override public synchronized void savePin(byte[] pin) throws Exception {
        JSONObject value = new JSONObject().put("pem", client.certificatePem() + client.privateKeyPkcs8Pem())
            .put("peer", pin == null ? JSONObject.NULL : Base64.getEncoder().encodeToString(pin));
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding"); cipher.init(Cipher.ENCRYPT_MODE, storageKey);
        byte[] encrypted = cipher.doFinal(value.toString().getBytes(StandardCharsets.UTF_8));
        FileOutputStream output = file.startWrite();
        try { output.write(12); output.write(cipher.getIV()); output.write(encrypted); file.finishWrite(output); peer = pin == null ? null : pin.clone(); }
        catch (Exception failure) { file.failWrite(output); throw failure; }
    }
}
