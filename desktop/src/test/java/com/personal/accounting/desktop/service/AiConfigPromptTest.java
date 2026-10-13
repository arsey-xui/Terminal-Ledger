package com.personal.accounting.desktop.service;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 自定义提示词的落盘编码与默认回退。 */
class AiConfigPromptTest {

    @Test
    void promptRoundTripsThroughBase64() {
        String custom = "只回答三句话。\n第二行带:冒号与#井号\n第三行";
        String encoded = AiConfig.encodePrompt(custom);
        assertFalse(encoded.contains("\n"));
        assertEquals(custom, AiConfig.decodePrompt(encoded));
    }

    @Test
    void blankPromptEncodesToEmpty() {
        assertEquals("", AiConfig.encodePrompt(null));
        assertEquals("", AiConfig.encodePrompt("   "));
        assertEquals("", AiConfig.decodePrompt(""));
        assertEquals("", AiConfig.decodePrompt(null));
    }

    @Test
    void brokenStoredValueFallsBackToEmpty() {
        assertEquals("", AiConfig.decodePrompt("这不是合法的 base64!!"));
    }
}
