package com.termux.app;

import android.content.Context;
import android.content.SharedPreferences;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.text.TextUtils;
import android.util.Base64;

import androidx.annotation.Nullable;

import java.nio.charset.StandardCharsets;
import java.security.KeyStore;

import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

/** Stores Hermes-mobile provider settings. Secrets are encrypted with Android Keystore. */
public final class AiProviderConfig {

    private static final String PREFS = "mobile_hermes_provider_config";
    private static final String KEYSTORE = "AndroidKeyStore";
    private static final String KEY_ALIAS = "mobile_hermes_provider_key";
    private static final String CIPHER = "AES/GCM/NoPadding";
    private static final int GCM_TAG_BITS = 128;
    private static final String KEY_SELECTED_PROVIDER = "selected_provider";
    private static final String KEY_MODEL = "model";
    private static final String KEY_BASE_URL = "base_url";
    private static final String KEY_SECRET_PREFIX = "secret_";

    private final SharedPreferences mPrefs;

    public AiProviderConfig(Context context) {
        mPrefs = context.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    public String getSelectedProviderId() {
        return mPrefs.getString(KEY_SELECTED_PROVIDER, AiProviderProfile.firstAgentProfile().id);
    }

    public void setSelectedProviderId(String providerId) {
        if (TextUtils.isEmpty(providerId)) return;
        mPrefs.edit().putString(KEY_SELECTED_PROVIDER, providerId).apply();
    }

    public String getModel(AiProviderProfile profile) {
        if (profile == null) return "";
        return mPrefs.getString(KEY_MODEL + "_" + profile.id, profile.defaultModel);
    }

    public void setModel(AiProviderProfile profile, String model) {
        if (profile == null) return;
        mPrefs.edit().putString(KEY_MODEL + "_" + profile.id,
            model == null ? "" : model.trim()).apply();
    }

    public String getBaseUrl(AiProviderProfile profile) {
        if (profile == null) return "";
        return mPrefs.getString(KEY_BASE_URL + "_" + profile.id, profile.defaultBaseUrl);
    }

    public void setBaseUrl(AiProviderProfile profile, String baseUrl) {
        if (profile == null) return;
        mPrefs.edit().putString(KEY_BASE_URL + "_" + profile.id,
            baseUrl == null ? "" : baseUrl.trim()).apply();
    }

    public boolean hasApiKey(AiProviderProfile profile) {
        return !TextUtils.isEmpty(getApiKey(profile));
    }

    @Nullable
    public String getApiKey(AiProviderProfile profile) {
        if (profile == null) return null;
        String encoded = mPrefs.getString(KEY_SECRET_PREFIX + profile.id, "");
        if (TextUtils.isEmpty(encoded)) return null;
        try {
            String[] parts = encoded.split(":", 2);
            if (parts.length != 2) return null;
            byte[] iv = Base64.decode(parts[0], Base64.NO_WRAP);
            byte[] encrypted = Base64.decode(parts[1], Base64.NO_WRAP);
            Cipher cipher = Cipher.getInstance(CIPHER);
            cipher.init(Cipher.DECRYPT_MODE, getOrCreateSecretKey(), new GCMParameterSpec(GCM_TAG_BITS, iv));
            String value = new String(cipher.doFinal(encrypted), StandardCharsets.UTF_8);
            return TextUtils.isEmpty(value) ? null : value;
        } catch (Exception e) {
            return null;
        }
    }

    public void setApiKey(AiProviderProfile profile, String apiKey) {
        if (profile == null) return;
        String clean = apiKey == null ? "" : apiKey.trim();
        SharedPreferences.Editor editor = mPrefs.edit();
        if (clean.isEmpty()) {
            editor.remove(KEY_SECRET_PREFIX + profile.id);
        } else {
            try {
                Cipher cipher = Cipher.getInstance(CIPHER);
                cipher.init(Cipher.ENCRYPT_MODE, getOrCreateSecretKey());
                byte[] encrypted = cipher.doFinal(clean.getBytes(StandardCharsets.UTF_8));
                String encoded = Base64.encodeToString(cipher.getIV(), Base64.NO_WRAP)
                    + ":" + Base64.encodeToString(encrypted, Base64.NO_WRAP);
                editor.putString(KEY_SECRET_PREFIX + profile.id, encoded);
            } catch (Exception e) {
                return;
            }
        }
        editor.apply();
    }

    private SecretKey getOrCreateSecretKey() throws Exception {
        KeyStore keyStore = KeyStore.getInstance(KEYSTORE);
        keyStore.load(null);
        if (keyStore.containsAlias(KEY_ALIAS)) {
            return (SecretKey) keyStore.getKey(KEY_ALIAS, null);
        }
        KeyGenerator generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE);
        generator.init(new KeyGenParameterSpec.Builder(KEY_ALIAS,
            KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setRandomizedEncryptionRequired(true)
            .build());
        return generator.generateKey();
    }
}
