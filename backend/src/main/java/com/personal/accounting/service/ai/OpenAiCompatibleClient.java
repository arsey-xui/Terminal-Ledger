package com.personal.accounting.service.ai;

import com.personal.accounting.config.AiProperties;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

import java.util.List;
import java.util.Map;

/**
 * 兼容 OpenAI Chat Completions 协议的占位实现。
 * 通过环境变量 AI_API_KEY / AI_MODEL / AI_BASE_URL 注入；未配置时抛出异常，
 * 由 {@link AiReportServiceImpl} 回退到规则引擎。
 */
@Component
@RequiredArgsConstructor
public class OpenAiCompatibleClient implements LlmClient {

    private static final RestTemplate REST_TEMPLATE = new RestTemplate();
    private final AiProperties props;

    @Override
    public String complete(String system, String user) {
        return complete(system, user, props.getApiKey(), props.getBaseUrl(), props.getModel());
    }

    @Override
    public String complete(String system, String user, String apiKey, String baseUrl, String model) {
        if (apiKey == null || apiKey.isBlank()) {
            throw new UnsupportedOperationException("AI_API_KEY 未配置");
        }
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.setBearerAuth(apiKey);

        Map<String, Object> body = Map.of(
                "model", model,
                "messages", List.of(
                        Map.of("role", "system", "content", system),
                        Map.of("role", "user", "content", user)),
                "temperature", 0.4);

        ResponseEntity<Map> resp = REST_TEMPLATE.postForEntity(baseUrl + "/chat/completions",
                new HttpEntity<>(body, headers), Map.class);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> choices = (List<Map<String, Object>>) resp.getBody().get("choices");
        Map<String, Object> first = choices.get(0);
        Map<String, Object> msg = (Map<String, Object>) first.get("message");
        return String.valueOf(msg.get("content"));
    }
}
