package com.personal.accounting.service.ai;

/**
 * 大模型调用抽象。生产环境可替换为 OpenAI / 通义 / Ollama 等实现；
 * 未配置 API Key 时由规则引擎兜底，此接口不强制接入。
 */
public interface LlmClient {
    /**
     * 调用对话补全。
     *
     * @param system 系统提示词
     * @param user   用户/上下文提示词
     * @return 模型返回文本
     * @throws UnsupportedOperationException 当未配置模型时抛出
     */
    String complete(String system, String user);

    /** 使用客户端自带的 Key / 接口地址 / 模型调用。 */
    String complete(String system, String user, String apiKey, String baseUrl, String model);
}
