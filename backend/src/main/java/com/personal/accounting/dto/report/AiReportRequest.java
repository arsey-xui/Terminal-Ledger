package com.personal.accounting.dto.report;

import lombok.Data;

@Data
public class AiReportRequest {
    private Integer year;
    private Integer month;
    /** RULE / LLM；缺省自动选择 */
    private String engine;
    private String prompt;
    /** 可选：客户端自带的大模型 Key（未传则用服务端 AI_API_KEY） */
    private String apiKey;
    private String baseUrl;
    private String model;
}
