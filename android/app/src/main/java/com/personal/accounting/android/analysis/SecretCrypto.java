package com.personal.accounting.android.analysis;

import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;

import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

/**
 * API Key 这类小机密的加解密与存放格式。
 *
 * <p>纯 Java：只管「给一把 AES 密钥，怎么加解密、密文长什么样」，密钥从哪来交给
 * {@link SecretStore}（手机上是 Android Keystore）。这样这段逻辑可以在 JVM 上单独验证。</p>
 *
 * <p>存放格式：{@code enc:v1:<hex iv>:<hex 密文+tag>}。没有这个前缀的值一律当成
 * <b>旧版本留下的明文</b>原样返回，方便平滑升级，也不会把老用户的 Key 弄丢。</p>
 *
 * <p>用十六进制而不是 Base64：{@code java.util.Base64} 要 Android 8.0 以上，而这个应用的
 * minSdk 是 21，{@code android.util.Base64} 又会让这个类没法在 JVM 上测。</p>
 */
final class SecretCrypto {

    static final String PREFIX = "enc:v1:";
    private static final int IV_LEN = 12;
    private static final int TAG_BITS = 128;

    private SecretCrypto() {
    }

    /** 加密并带上格式前缀。每次都用新的随机 IV。 */
    static String encrypt(SecretKey key, String plain) throws Exception {
        byte[] iv = new byte[IV_LEN];
        new SecureRandom().nextBytes(iv);
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, iv));
        byte[] cipherText = cipher.doFinal(plain.getBytes(StandardCharsets.UTF_8));
        return PREFIX + toHex(iv) + ":" + toHex(cipherText);
    }

    /**
     * 解密。没有前缀的值（旧版明文）原样返回；密文被改过、格式不对、密钥不对都会抛异常，
     * 由调用方决定怎么处理（{@link SecretStore} 的做法是清掉并让用户重填）。
     */
    static String decrypt(SecretKey key, String stored) throws Exception {
        if (stored == null) return "";
        if (!stored.startsWith(PREFIX)) return stored;
        String body = stored.substring(PREFIX.length());
        int sep = body.indexOf(':');
        if (sep <= 0 || sep == body.length() - 1) throw new IllegalArgumentException("密文格式不对");
        byte[] iv = fromHex(body.substring(0, sep));
        byte[] cipherText = fromHex(body.substring(sep + 1));
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, iv));
        return new String(cipher.doFinal(cipherText), StandardCharsets.UTF_8);
    }

    /** 是否已经是密文形态（用来判断要不要迁移、要不要去 Keystore 取密钥）。 */
    static boolean isEncrypted(String stored) {
        return stored != null && stored.startsWith(PREFIX);
    }

    private static String toHex(byte[] bytes) {
        StringBuilder sb = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) {
            sb.append(Character.forDigit((b >> 4) & 0xF, 16));
            sb.append(Character.forDigit(b & 0xF, 16));
        }
        return sb.toString();
    }

    private static byte[] fromHex(String hex) {
        if (hex.length() % 2 != 0) throw new IllegalArgumentException("十六进制长度必须是偶数");
        byte[] out = new byte[hex.length() / 2];
        for (int i = 0; i < out.length; i++) {
            int hi = Character.digit(hex.charAt(i * 2), 16);
            int lo = Character.digit(hex.charAt(i * 2 + 1), 16);
            if (hi < 0 || lo < 0) throw new IllegalArgumentException("不是合法的十六进制");
            out[i] = (byte) ((hi << 4) | lo);
        }
        return out;
    }
}
