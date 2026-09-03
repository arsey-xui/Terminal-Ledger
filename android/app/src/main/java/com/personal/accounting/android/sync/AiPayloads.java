package com.personal.accounting.android.sync;

/** 与后端 /api/report/ai 对应的传输对象。 */
public final class AiPayloads {

    private AiPayloads() {
    }

    /** AI 消费分析请求：year/month/engine，可选客户端自传 apiKey/baseUrl/model。 */
    public static class Request {
        public Integer year;
        public Integer month;
        /** RULE / LLM */
        public String engine;
        public String apiKey;
        public String baseUrl;
        public String model;
    }

    /** AI 消费分析响应。 */
    public static class Response {
        public String report;
        /** RULE / LLM */
        public String engine;
        public long generatedAt;
    }
}
