package com.personal.accounting.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/** AI 消费分析配置（对应 application.yml 的 {@code accounting.ai}）。 */
@Data
@Configuration
@ConfigurationProperties(prefix = "accounting.ai")
public class AiProperties {
    /** 留空则使用内置规则引擎。 */
    private String apiKey = "";
    private String model = "gpt-4o-mini";
    private String baseUrl = "https://api.openai.com/v1";
}
