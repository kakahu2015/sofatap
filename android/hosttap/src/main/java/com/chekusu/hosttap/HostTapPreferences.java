package com.chekusu.hosttap;

import android.content.Context;
import android.content.SharedPreferences;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.Base64;

import org.json.JSONArray;

import java.security.GeneralSecurityException;
import java.security.KeyStore;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;

import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

/** Stores HostTap drafts and encrypts pairing links with Android Keystore. */
public final class HostTapPreferences {
    // Keep the existing storage name and aliases so current HostTap pairings survive the module rename.
    private static final String PREFS = "keytrans_preferences";
    private static final String PAIRING_ALIAS = "com.chekusu.keytrans.hosttap-pairing";
    private static final String PAIRINGS_ALIAS = "com.chekusu.keytrans.hosttap-pairings";
    private static final String PAIRING_IV = "hosttap_pairing_iv";
    private static final String PAIRING_VALUE = "hosttap_pairing_ciphertext";
    private static final String PAIRINGS_IV = "hosttap_pairings_iv";
    private static final String PAIRINGS_VALUE = "hosttap_pairings_ciphertext";
    private final SharedPreferences values;

    public HostTapPreferences(Context context) {
        values = context.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    public String hostTapDraft() { return values.getString("hosttap_local_draft", ""); }
    public String hostTapSentPrefix() { return values.getString("hosttap_sent_prefix", ""); }
    public boolean hostTapUncertain() { return values.getBoolean("hosttap_delivery_uncertain", false); }

    public void setHostTapDraft(String draft) {
        values.edit().putString("hosttap_local_draft", draft == null ? "" : draft).apply();
    }

    public void setHostTapSentPrefix(String value) {
        values.edit().putString("hosttap_sent_prefix", value == null ? "" : value).commit();
    }

    public void setHostTapUncertain(boolean value) {
        values.edit().putBoolean("hosttap_delivery_uncertain", value).commit();
    }

    public String hostTapPairingLink() {
        String ivValue = values.getString(PAIRING_IV, null);
        String ciphertextValue = values.getString(PAIRING_VALUE, null);
        if (ivValue == null || ciphertextValue == null) return "";
        try {
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, keystoreKey(PAIRING_ALIAS),
                new GCMParameterSpec(128, Base64.decode(ivValue, Base64.NO_WRAP)));
            return new String(cipher.doFinal(Base64.decode(ciphertextValue, Base64.NO_WRAP)),
                java.nio.charset.StandardCharsets.UTF_8);
        } catch (GeneralSecurityException | IllegalArgumentException ignored) {
            clearHostTapPairing();
            return "";
        }
    }

    public List<String> hostTapPairingLinks() {
        String ivValue = values.getString(PAIRINGS_IV, null);
        String ciphertextValue = values.getString(PAIRINGS_VALUE, null);
        if (ivValue == null || ciphertextValue == null) {
            String legacy = hostTapPairingLink();
            List<String> links = new ArrayList<>();
            if (!legacy.isEmpty()) links.add(legacy);
            return links;
        }
        try {
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, keystoreKey(PAIRINGS_ALIAS),
                new GCMParameterSpec(128, Base64.decode(ivValue, Base64.NO_WRAP)));
            String json = new String(cipher.doFinal(Base64.decode(ciphertextValue, Base64.NO_WRAP)),
                java.nio.charset.StandardCharsets.UTF_8);
            JSONArray array = new JSONArray(json);
            List<String> links = new ArrayList<>();
            for (int i = 0; i < array.length(); i++) {
                String link = array.optString(i, "");
                if (link.isEmpty() || links.contains(link)) continue;
                try {
                    HostTapPairing.parse(link);
                    links.add(link);
                } catch (RuntimeException ignored) { }
            }
            return links;
        } catch (GeneralSecurityException | IllegalArgumentException | org.json.JSONException ignored) {
            clearHostTapPairingLinks();
            String legacy = hostTapPairingLink();
            List<String> links = new ArrayList<>();
            if (!legacy.isEmpty()) links.add(legacy);
            return links;
        }
    }

    public void rememberHostTapPairingLink(String link) throws GeneralSecurityException {
        List<String> links = hostTapPairingLinks();
        links.remove(link);
        links.add(0, link);
        saveHostTapPairingLinks(links);
        saveHostTapPairingLink(link);
    }

    public void saveHostTapPairingLinks(List<String> links) throws GeneralSecurityException {
        JSONArray array = new JSONArray();
        HashSet<String> seen = new HashSet<>();
        if (links != null) {
            for (String link : links) {
                if (link == null || link.isEmpty() || !seen.add(link)) continue;
                try {
                    HostTapPairing.parse(link);
                    array.put(link);
                } catch (RuntimeException ignored) { }
            }
        }
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.ENCRYPT_MODE, keystoreKey(PAIRINGS_ALIAS));
        byte[] ciphertext = cipher.doFinal(array.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8));
        values.edit().putString(PAIRINGS_IV, Base64.encodeToString(cipher.getIV(), Base64.NO_WRAP))
            .putString(PAIRINGS_VALUE, Base64.encodeToString(ciphertext, Base64.NO_WRAP)).apply();
    }

    public void clearHostTapPairingLinks() {
        values.edit().remove(PAIRINGS_IV).remove(PAIRINGS_VALUE).apply();
        deleteKey(PAIRINGS_ALIAS);
    }

    public void saveHostTapPairingLink(String link) throws GeneralSecurityException {
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.ENCRYPT_MODE, keystoreKey(PAIRING_ALIAS));
        byte[] ciphertext = cipher.doFinal(link.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        values.edit().putString(PAIRING_IV, Base64.encodeToString(cipher.getIV(), Base64.NO_WRAP))
            .putString(PAIRING_VALUE, Base64.encodeToString(ciphertext, Base64.NO_WRAP)).apply();
    }

    public void clearHostTapPairing() {
        values.edit().remove(PAIRING_IV).remove(PAIRING_VALUE).apply();
        deleteKey(PAIRING_ALIAS);
    }

    private SecretKey keystoreKey(String alias) throws GeneralSecurityException {
        try {
            KeyStore store = KeyStore.getInstance("AndroidKeyStore");
            store.load(null);
            java.security.Key existing = store.getKey(alias, null);
            if (existing instanceof SecretKey secretKey) return secretKey;

            KeyGenerator generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore");
            generator.init(new KeyGenParameterSpec.Builder(alias,
                KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setRandomizedEncryptionRequired(true)
                .build());
            return generator.generateKey();
        } catch (java.io.IOException | java.security.KeyStoreException | java.security.UnrecoverableKeyException e) {
            throw new GeneralSecurityException("Secure storage is unavailable");
        }
    }

    private void deleteKey(String alias) {
        try {
            KeyStore store = KeyStore.getInstance("AndroidKeyStore");
            store.load(null);
            if (store.containsAlias(alias)) store.deleteEntry(alias);
        } catch (GeneralSecurityException | java.io.IOException ignored) { }
    }
}
