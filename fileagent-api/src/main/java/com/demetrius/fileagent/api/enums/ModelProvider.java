package com.demetrius.fileagent.api.enums;

/**
 * 支持的模型 Provider。所有厂商均走 OpenAI 兼容协议（chat/completions），
 * 差异只在默认 base-url，用户可在配置里覆盖。
 */
public enum ModelProvider {

    /** DeepSeek（deepseek-chat / deepseek-reasoner） */
    DEEPSEEK("https://api.deepseek.com/v1"),

    /** 智谱 GLM（glm-4 系列） */
    ZHIPU("https://open.bigmodel.cn/api/paas/v4"),

    /** 阿里云百炼（qwen 系列） */
    DASHSCOPE("https://dashscope.aliyuncs.com/compatible-mode/v1"),

    /** Moonshot Kimi（moonshot-v1 / kimi 系列） */
    MOONSHOT("https://api.moonshot.cn/v1"),

    /** OpenAI 官方（gpt 系列） */
    OPENAI("https://api.openai.com/v1"),

    /** 自定义 OpenAI 兼容端点（必须手填 base-url） */
    CUSTOM("");

    private final String defaultBaseUrl;

    ModelProvider(String defaultBaseUrl) {
        this.defaultBaseUrl = defaultBaseUrl;
    }

    /** 默认 OpenAI 兼容端点（CUSTOM 返回空串，表示必须由用户填写） */
    public String getDefaultBaseUrl() {
        return defaultBaseUrl;
    }
}
