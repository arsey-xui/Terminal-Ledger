package com.personal.accounting.desktop.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.personal.accounting.desktop.sync.Json;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * 备份载荷里 AI 设置的线上格式。
 *
 * <p>这段 JSON 是手机端与电脑端共用的契约：字段名对不上，一边导出的备份就没法在另一边恢复出
 * API Key，而这类错误编译期发现不了（两端各自都是合法的 JSON）。所以这里把字段名钉死，
 * 谁改了一端的字段名，这个测试就会提醒另一端的读取逻辑必须同步改。</p>
 */
class BackupPayloadAiSettingsTest {

    @Test
    void aiSettingsUsesTheSharedFieldNames() throws Exception {
        BackupService.BackupPayload payload = new BackupService.BackupPayload();
        BackupService.AiSettings ai = new BackupService.AiSettings();
        ai.apiKey = "sk-test";
        ai.baseUrl = "https://example.test/v1";
        ai.model = "gpt-4o-mini";
        ai.prompt = "自定义提示词";
        payload.aiSettings = ai;
        payload.exportedAt = 1L;

        JsonNode json = Json.MAPPER.readTree(Json.MAPPER.writeValueAsBytes(payload));
        JsonNode node = json.get("aiSettings");
        assertNotNull(node, "载荷必须带 aiSettings 字段（手机端按同名读取）");
        assertEquals("sk-test", node.get("apiKey").asText());
        assertEquals("https://example.test/v1", node.get("baseUrl").asText());
        assertEquals("gpt-4o-mini", node.get("model").asText());
        assertEquals("自定义提示词", node.get("prompt").asText());
    }

    @Test
    void roundTripsThroughJson() throws Exception {
        BackupService.BackupPayload payload = new BackupService.BackupPayload();
        BackupService.AiSettings ai = new BackupService.AiSettings();
        ai.apiKey = "sk-round-trip";
        ai.prompt = "第一行\n第二行";
        payload.aiSettings = ai;

        BackupService.BackupPayload back = Json.MAPPER.readValue(
                Json.MAPPER.writeValueAsBytes(payload), BackupService.BackupPayload.class);
        assertNotNull(back.aiSettings);
        assertEquals("sk-round-trip", back.aiSettings.apiKey);
        assertEquals("第一行\n第二行", back.aiSettings.prompt);
    }

    @Test
    void oldBackupWithoutAiSettingsParsesToNull() throws Exception {
        // 旧版本导出的包没有这个字段：要解析成 null，恢复逻辑才会跳过、而不是把本机 Key 清掉
        BackupService.BackupPayload back = Json.MAPPER.readValue(
                "{\"exportedAt\":1,\"transactions\":[]}", BackupService.BackupPayload.class);
        assertNull(back.aiSettings);
    }

    @Test
    void unknownFieldsFromTheOtherEndAreIgnored() throws Exception {
        // 手机端将来若在这个对象里加字段，电脑端仍然要能正常读出 Key
        BackupService.BackupPayload back = Json.MAPPER.readValue(
                "{\"exportedAt\":1,\"aiSettings\":{\"apiKey\":\"sk-x\",\"useLlm\":true}}",
                BackupService.BackupPayload.class);
        assertEquals("sk-x", back.aiSettings.apiKey);
    }
}
