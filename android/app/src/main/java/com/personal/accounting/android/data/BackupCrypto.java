package com.personal.accounting.android.data;

import java.io.ByteArrayOutputStream;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;

import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.PBEKeySpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * 备份包的口令加解密。
 *
 * <p>参数与电脑端 {@code BackupService} 完全一致，所以两边导出的文件可以互相解开：
 * AES-256-GCM，密钥由 PBKDF2WithHmacSHA256（65536 轮、256 位）从口令派生，
 * 文件结构 = {@code salt(16) + iv(12) + 密文(含 GCM tag)}。</p>
 *
 * <p>纯 Java，不依赖 Android，方便单独验证与电脑端是否互通。</p>
 */
final class BackupCrypto {

    static final int SALT_LEN = 16;
    static final int IV_LEN = 12;
    private static final int PBKDF2_ROUNDS = 65536;
    private static final int KEY_BITS = 256;

    private BackupCrypto() {
    }

    static byte[] encrypt(byte[] plain, char[] password) throws Exception {
        byte[] salt = new byte[SALT_LEN];
        new SecureRandom().nextBytes(salt);
        byte[] iv = new byte[IV_LEN];
        new SecureRandom().nextBytes(iv);
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.ENCRYPT_MODE, deriveKey(password, salt), new GCMParameterSpec(128, iv));
        byte[] cipherText = cipher.doFinal(plain);

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(salt);
        out.write(iv);
        out.write(cipherText);
        return out.toByteArray();
    }

    /** 口令不对、文件被改过、或者根本不是本应用的备份，都会抛异常，由调用方翻译成人话。 */
    static byte[] decrypt(byte[] encrypted, char[] password) throws Exception {
        if (encrypted == null || encrypted.length <= SALT_LEN + IV_LEN) {
            throw new IllegalArgumentException("文件太短，不是一份有效备份");
        }
        byte[] salt = new byte[SALT_LEN];
        System.arraycopy(encrypted, 0, salt, 0, SALT_LEN);
        byte[] iv = new byte[IV_LEN];
        System.arraycopy(encrypted, SALT_LEN, iv, 0, IV_LEN);
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.DECRYPT_MODE, deriveKey(password, salt), new GCMParameterSpec(128, iv));
        return cipher.doFinal(encrypted, SALT_LEN + IV_LEN, encrypted.length - SALT_LEN - IV_LEN);
    }

    private static SecretKey deriveKey(char[] password, byte[] salt) throws Exception {
        PBEKeySpec spec = new PBEKeySpec(password, salt, PBKDF2_ROUNDS, KEY_BITS);
        try {
            // PBKDF2WithHmacSHA256 需要 Android 8.0（API 26）以上。低版本没有这个算法，
            // 换成 HmacSHA1 会派生出完全不同的密钥、和电脑端不兼容，所以宁可报错也不降级。
            SecretKeyFactory factory;
            try {
                factory = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256");
            } catch (NoSuchAlgorithmException e) {
                // 保持这个类不依赖 Android：版本号由上层补进提示里
                throw new IllegalStateException("系统不支持备份加密所需的算法，需要 Android 8.0 及以上；CSV 导出不受影响");
            }
            return new SecretKeySpec(factory.generateSecret(spec).getEncoded(), "AES");
        } finally {
            spec.clearPassword();
            if (password != null) {
                java.util.Arrays.fill(password, '\0');
            }
        }
    }
}
