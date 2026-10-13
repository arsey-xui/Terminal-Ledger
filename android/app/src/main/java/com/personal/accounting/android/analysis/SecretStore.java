package com.personal.accounting.android.analysis;

import android.os.Build;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.Log;

import java.security.KeyStore;

import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;

/**
 * 小机密的加解密入口：密钥取自 Android Keystore。
 *
 * <p>Keystore 里的 AES 密钥不可导出，在支持 TEE / StrongBox 的机器上不出安全硬件，
 * 所以即使整个 {@code /data} 目录被读走（或被备份恢复到另一台设备），没有那台设备的
 * Keystore 也解不开密文。密文本身用 AES-256-GCM，格式见 {@link SecretCrypto}。</p>
 *
 * <p>三种降级/兼容情况：</p>
 * <ul>
 *   <li><b>旧明文</b>：读到没有 {@code enc:} 前缀的值原样返回；下一次保存时自动换成密文。</li>
 *   <li><b>Android 6.0 以下</b>（API &lt; 23）没有 Keystore AES，退回明文存储。</li>
 *   <li><b>解不开</b>（换设备、Keystore 密钥失效、密文被改）：返回 null 让调用方清掉坏值、
 *       提示用户重填，而不是让应用崩掉或者一直拿一个解不开的值去请求接口。</li>
 * </ul>
 */
final class SecretStore {

    private static final String TAG = "SecretStore";
    private static final String KEYSTORE = "AndroidKeyStore";
    private static final String ALIAS = "terminal_ledger_secret_v1";

    private SecretStore() {
    }

    /** 落盘前的编码：能加密就加密，不能就原样返回。 */
    static String encode(String value) {
        if (value == null || value.isEmpty()) return "";
        SecretKey key = keyForWrite();
        if (key == null) return value;
        try {
            return SecretCrypto.encrypt(key, value);
        } catch (Exception e) {
            Log.w(TAG, "加密失败，按明文存储", e);
            return value;
        }
    }

    /** 读盘后的解码；返回 null 表示「这是密文但我解不开」，调用方应清掉它。 */
    static String decode(String stored) {
        if (stored == null || stored.isEmpty()) return "";
        if (!SecretCrypto.isEncrypted(stored)) return stored;   // 旧明文
        SecretKey key = existingKey();
        if (key == null) return null;
        try {
            return SecretCrypto.decrypt(key, stored);
        } catch (Exception e) {
            Log.w(TAG, "解密失败", e);
            return null;
        }
    }

    /** 读路径：只找现成的密钥，**不**新建 —— 否则会把解不开的旧密文永远留在那。 */
    private static SecretKey existingKey() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return null;   // API 23 以下没有 Keystore AES
        try {
            KeyStore ks = KeyStore.getInstance(KEYSTORE);
            ks.load(null);
            if (!ks.containsAlias(ALIAS)) return null;
            java.security.Key k = ks.getKey(ALIAS, null);
            return (k instanceof SecretKey) ? (SecretKey) k : null;
        } catch (Exception e) {
            Log.w(TAG, "访问 Keystore 失败", e);
            return null;
        }
    }

    /** 写路径：没有就生成一把。 */
    private static SecretKey keyForWrite() {
        SecretKey existing = existingKey();
        if (existing != null) return existing;
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return null;
        try {
            KeyGenerator generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE);
            generator.init(new KeyGenParameterSpec.Builder(ALIAS,
                    KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setKeySize(256)
                    .build());
            return generator.generateKey();
        } catch (Exception e) {
            Log.w(TAG, "生成 Keystore 密钥失败，按明文存储", e);
            return null;
        }
    }
}
