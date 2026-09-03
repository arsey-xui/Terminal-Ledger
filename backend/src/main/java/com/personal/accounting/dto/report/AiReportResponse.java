package com.personal.accounting.dto.report;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class AiReportResponse {
    private String report;
    /** RULE（内置规则引擎）或 LLM（大模型） */
    private String engine;
    private long generatedAt;
}
