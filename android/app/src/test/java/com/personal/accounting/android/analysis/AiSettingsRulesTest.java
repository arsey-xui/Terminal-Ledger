package com.personal.accounting.android.analysis;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

import org.junit.jupiter.api.Test;

/**
 * 恢复备份时 AI 设置的合并规则（「只补不清」）。
 *
 * <p>核心回归：恢复一份「配 Key 之前」做的旧备份，不能把本机现在配好的 Key 清掉 ——
 * 这是 {@code aiSettings} 字段本身要解决的问题，也是用户实际报过的缺陷。</p>
 */
class AiSettingsRulesTest {

    private static AiSettingsRules.Values local() {
        return new AiSettingsRules.Values("local-key", "https://local.example/v1", "local-model", "本地提示词");
    }

    @Test
    void backupWithoutKeyLeavesLocalSettingsAlone() {
        assertNull(AiSettingsRules.merge(
                        new AiSettingsRules.Values(null, "https://b/v1", "b-model", "p"), local()),
                "备份里没有 Key 时应整块跳过，不能清掉本机的 Key");
        assertNull(AiSettingsRules.merge(
                new AiSettingsRules.Values("   ", "https://b/v1", "b-model", "p"), local()));
        assertNull(AiSettingsRules.merge(null, local()));
    }

    @Test
    void backupWithKeyWins() {
        AiSettingsRules.Values merged = AiSettingsRules.merge(
                new AiSettingsRules.Values(" backup-key ", "https://b/v1", "b-model", "备份提示词"), local());
        assertNotNull(merged);
        assertEquals("backup-key", merged.apiKey);
        assertEquals("https://b/v1", merged.baseUrl);
        assertEquals("b-model", merged.model);
        assertEquals("备份提示词", merged.prompt);
    }

    @Test
    void blankBackupFieldsKeepLocalValues() {
        AiSettingsRules.Values merged = AiSettingsRules.merge(
                new AiSettingsRules.Values("backup-key", "  ", null, null), local());
        assertNotNull(merged);
        assertEquals("backup-key", merged.apiKey);
        assertEquals("https://local.example/v1", merged.baseUrl, "地址为空应保留本机值");
        assertEquals("local-model", merged.model, "模型为空应保留本机值");
        assertEquals("本地提示词", merged.prompt, "提示词缺失（null）应保留本机值");
    }

    @Test
    void emptyPromptInBackupIsTreatedAsCleared() {
        // prompt 的语义：null = 备份里没这一项；空串 = 用户确实清空了提示词。照写。
        AiSettingsRules.Values merged = AiSettingsRules.merge(
                new AiSettingsRules.Values("backup-key", null, null, ""), local());
        assertNotNull(merged);
        assertEquals("", merged.prompt);
    }

    @Test
    void mergeWorksWithoutLocalSettings() {
        AiSettingsRules.Values merged = AiSettingsRules.merge(
                new AiSettingsRules.Values("backup-key", null, null, null), null);
        assertNotNull(merged);
        assertEquals("backup-key", merged.apiKey);
        assertEquals("", merged.baseUrl);
        assertEquals("", merged.model);
        assertNull(merged.prompt);
    }
}
