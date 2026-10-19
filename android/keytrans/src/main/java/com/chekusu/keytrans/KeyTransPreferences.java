package com.chekusu.keytrans;

import android.content.Context;
import android.content.SharedPreferences;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.Base64;

import java.security.GeneralSecurityException;
import java.security.KeyStore;
import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

/** Private preferences with the provider credential encrypted by an Android Keystore key. */
public final class KeyTransPreferences {
    private static final String PREFS = "keytrans_preferences";
    private static final String KEY_ALIAS = "com.chekusu.keytrans.api-key";
    private static final String PAIRING_ALIAS = "com.chekusu.keytrans.hosttap-pairing";
    private static final String KEY_IV = "provider_key_iv";
    private static final String KEY_VALUE = "provider_key_ciphertext";
    private static final String PAIRING_IV = "hosttap_pairing_iv";
    private static final String PAIRING_VALUE = "hosttap_pairing_ciphertext";
    private final SharedPreferences values;

    public KeyTransPreferences(Context context) {
        values = context.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    public String baseUrl() { return values.getString("base_url", "https://api.openai.com/v1/chat/completions"); }
    public String model() { return values.getString("model", "gpt-3.5-turbo"); }
    public String motherLanguage() { return values.getString("mother_language", "Chinese (Simplified)"); }
    public String foreignLanguage() { return values.getString("foreign_language", "English"); }
    public boolean startsInTypeMode() { return values.getBoolean("start_in_type_mode", false); }
    public boolean autoTranslate() { return values.getBoolean("auto_translate", false); }
    public boolean deleteOriginal() { return values.getBoolean("delete_original", false); }
    public boolean autoSendAfterTranslate() { return values.getBoolean("auto_send_after_translate", false); }
    public boolean autoLineBreak() { return values.getBoolean("auto_line_break", false); }
    public boolean autoSwitchKeyboard() { return values.getBoolean("auto_switch_keyboard", false); }
    public String hostTapDraft() { return values.getString("hosttap_local_draft", ""); }
    public String hostTapSentPrefix() { return values.getString("hosttap_sent_prefix", ""); }
    public boolean hostTapUncertain() { return values.getBoolean("hosttap_delivery_uncertain", false); }

    public void saveSettings(String baseUrl, String model, String motherLanguage, String foreignLanguage) {
        values.edit()
            .putString("base_url", baseUrl.trim())
            .putString("model", model.trim())
            .putString("mother_language", motherLanguage.trim())
            .putString("foreign_language", foreignLanguage.trim())
            .apply();
    }

    public void setStartsInTypeMode(boolean enabled) { values.edit().putBoolean("start_in_type_mode", enabled).apply(); }
    public void setAutoTranslate(boolean enabled) { values.edit().putBoolean("auto_translate", enabled).apply(); }
    public void setDeleteOriginal(boolean enabled) { values.edit().putBoolean("delete_original", enabled).apply(); }
    public void setAutoSendAfterTranslate(boolean enabled) { values.edit().putBoolean("auto_send_after_translate", enabled).apply(); }
    public void setAutoLineBreak(boolean enabled) { values.edit().putBoolean("auto_line_break", enabled).apply(); }
    public void setAutoSwitchKeyboard(boolean enabled) { values.edit().putBoolean("auto_switch_keyboard", enabled).apply(); }
    public void setHostTapDraft(String draft) { values.edit().putString("hosttap_local_draft", draft == null ? "" : draft).apply(); }
    public void setHostTapSentPrefix(String value) { values.edit().putString("hosttap_sent_prefix", value == null ? "" : value).commit(); }
    public void setHostTapUncertain(boolean value) { values.edit().putBoolean("hosttap_delivery_uncertain", value).commit(); }

    public boolean hasApiKey() { return !apiKey().isEmpty(); }

    public String apiKey() {
        String ivValue = values.getString(KEY_IV, null);
        String ciphertextValue = values.getString(KEY_VALUE, null);
        if (ivValue == null || ciphertextValue == null) return "";
        try {
            byte[] iv = Base64.decode(ivValue, Base64.NO_WRAP);
            byte[] ciphertext = Base64.decode(ciphertextValue, Base64.NO_WRAP);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, keystoreKey(), new GCMParameterSpec(128, iv));
            return new String(cipher.doFinal(ciphertext), java.nio.charset.StandardCharsets.UTF_8);
        } catch (GeneralSecurityException | IllegalArgumentException ignored) {
            // A damaged or invalidated key is treated as missing. Never log credential data.
            clearApiKey();
            return "";
        }
    }

    public void saveApiKey(String apiKey) throws GeneralSecurityException {
        if (apiKey == null || apiKey.trim().isEmpty()) return;
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.ENCRYPT_MODE, keystoreKey());
        byte[] ciphertext = cipher.doFinal(apiKey.trim().getBytes(java.nio.charset.StandardCharsets.UTF_8));
        values.edit()
            .putString(KEY_IV, Base64.encodeToString(cipher.getIV(), Base64.NO_WRAP))
            .putString(KEY_VALUE, Base64.encodeToString(ciphertext, Base64.NO_WRAP))
            .apply();
    }

    public void clearApiKey() {
        values.edit().remove(KEY_IV).remove(KEY_VALUE).apply();
        try {
            KeyStore store = KeyStore.getInstance("AndroidKeyStore");
            store.load(null);
            if (store.containsAlias(KEY_ALIAS)) store.deleteEntry(KEY_ALIAS);
        } catch (GeneralSecurityException | java.io.IOException ignored) { }
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

    public void saveHostTapPairingLink(String link) throws GeneralSecurityException {
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.ENCRYPT_MODE, keystoreKey(PAIRING_ALIAS));
        byte[] ciphertext = cipher.doFinal(link.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        values.edit().putString(PAIRING_IV, Base64.encodeToString(cipher.getIV(), Base64.NO_WRAP))
            .putString(PAIRING_VALUE, Base64.encodeToString(ciphertext, Base64.NO_WRAP)).apply();
    }

    public void clearHostTapPairing() {
        values.edit().remove(PAIRING_IV).remove(PAIRING_VALUE).apply();
        try {
            KeyStore store = KeyStore.getInstance("AndroidKeyStore");
            store.load(null);
            if (store.containsAlias(PAIRING_ALIAS)) store.deleteEntry(PAIRING_ALIAS);
        } catch (GeneralSecurityException | java.io.IOException ignored) { }
    }

    private SecretKey keystoreKey() throws GeneralSecurityException {
        return keystoreKey(KEY_ALIAS);
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
}
