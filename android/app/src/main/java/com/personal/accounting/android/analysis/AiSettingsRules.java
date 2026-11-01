package com.personal.accounting.android.analysis;

/**
 * 恢复备份时如何应用 AI 设置：纯逻辑，不碰 Android / Keystore，可在 JVM 上直接测。
 *
 * <p>规则是「只补不清」，与账本数据的 {@code upsertLww} 同一个思路：备份里没有的东西
 * 不能把本机现在有的东西抹掉。这里曾经出过一个真实缺陷 —— 恢复「配 Key 之前」做的旧备份，
 * 会把本机已经配好的 Key 清掉，那正是加 aiSettings 字段要解决的问题。</p>
 */
public final class AiSettingsRules {

    /** 一组 AI 设置。字段允许为 null，表示备份里没有这一项。 */
    public static final class Values {
        public final String apiKey;
        public final String baseUrl;
        public final String model;
        public final String prompt;

        public Values(String apiKey, String baseUrl, String model, String prompt) {
            this.apiKey = apiKey;
            this.baseUrl = baseUrl;
            this.model = model;
            this.prompt = prompt;
        }
    }

    private AiSettingsRules() {
    }

    /**
     * 把备份里的设置合并到本机设置上。
     *
     * @param backup 备份里的设置（可为 null）
     * @param local  本机当前设置（可为 null，按全空处理）
     * @return 要写回的设置；**备份里没有 Key 时返回 null**，表示整块跳过、不动本机设置
     */
    public static Values merge(Values backup, Values local) {
        if (backup == null) return null;
        String key = backup.apiKey == null ? "" : backup.apiKey.trim();
        if (key.isEmpty()) return null;                     // 没有 Key 就整块跳过
        String base = blankToLocal(backup.baseUrl, local == null ? "" : local.baseUrl);
        String model = blankToLocal(backup.model, local == null ? "" : local.model);
        // prompt 的语义与其它字段不同：null 表示备份里没这一项（保留本机），
        // 空串表示用户确实把提示词清空了（照写）。这条语义沿用原实现，不在这里改。
        String prompt = (backup.prompt == null)
                ? (local == null ? null : local.prompt)
                : backup.prompt;
        return new Values(key, base, model, prompt);
    }

    /** 备份里该字段为空（null 或空白）时保留本机值。 */
    public static String blankToLocal(String fromBackup, String local) {
        return (fromBackup == null || fromBackup.trim().isEmpty()) ? local : fromBackup.trim();
    }
}
