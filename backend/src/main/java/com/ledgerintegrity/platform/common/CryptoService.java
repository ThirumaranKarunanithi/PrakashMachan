package com.ledgerintegrity.platform.common;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.security.SecureRandom;
import java.util.Base64;

/**
 * At-rest encryption for evidence documents (SEC-004 / AC-16): AES-256-GCM with a
 * random 12-byte IV prepended to each ciphertext. The key comes from the
 * APP_ENCRYPTION_KEY environment variable (Base64, 32 bytes); when unset the
 * feature is off and a startup warning says so — existing plaintext rows remain
 * readable either way because encryption is flagged per document.
 */
@Service
public class CryptoService {

    private static final int IV_BYTES = 12;
    private static final int TAG_BITS = 128;

    private final SecretKeySpec key;
    private final SecureRandom random = new SecureRandom();

    public CryptoService(@Value("${app.security.encryption-key:}") String base64Key) {
        String trimmed = base64Key == null ? "" : base64Key.trim();
        if (trimmed.isEmpty()) {
            this.key = null;
            org.slf4j.LoggerFactory.getLogger(CryptoService.class)
                    .warn("APP_ENCRYPTION_KEY is not set - evidence documents will be stored UNENCRYPTED."
                            + " Generate a key with 'openssl rand -base64 32' before real client data.");
        } else {
            byte[] bytes = Base64.getDecoder().decode(trimmed);
            if (bytes.length != 32) {
                throw new IllegalStateException("APP_ENCRYPTION_KEY must decode to exactly 32 bytes (got "
                        + bytes.length + "). Generate one with: openssl rand -base64 32");
            }
            this.key = new SecretKeySpec(bytes, "AES");
        }
    }

    public boolean enabled() {
        return key != null;
    }

    public byte[] encrypt(byte[] plaintext) {
        try {
            byte[] iv = new byte[IV_BYTES];
            random.nextBytes(iv);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, iv));
            byte[] ciphertext = cipher.doFinal(plaintext);
            byte[] out = new byte[IV_BYTES + ciphertext.length];
            System.arraycopy(iv, 0, out, 0, IV_BYTES);
            System.arraycopy(ciphertext, 0, out, IV_BYTES, ciphertext.length);
            return out;
        } catch (Exception e) {
            throw new IllegalStateException("Encryption failed", e);
        }
    }

    public byte[] decrypt(byte[] stored) {
        try {
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, stored, 0, IV_BYTES));
            return cipher.doFinal(stored, IV_BYTES, stored.length - IV_BYTES);
        } catch (Exception e) {
            throw new IllegalStateException("Decryption failed - is APP_ENCRYPTION_KEY the same key"
                    + " the document was encrypted with?", e);
        }
    }
}
