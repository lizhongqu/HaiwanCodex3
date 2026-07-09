package com.haiwancodex.www.config;

import com.haiwancodex.www.common.AiCommon;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Duration;
import java.util.HashMap;
import java.util.Map;

@Configuration
public class AiModelConfig {

    @Value("${API_KEY_BIGMODEL}")
    public String API_KEY_BIGMODEL;
    @Value("${API_KEY_OPENROUTER}")
    public String API_KEY_OPENROUTER;
    @Value("${API_KEY_SILICONFLOW}")
    public String API_KEY_SILICONFLOW;
    @Value("${API_KEY_VOLCENGINE}")
    public String API_KEY_VOLCENGINE;

    @Bean(name = "bigmodelModel")
    public OpenAiChatModel bigmodelModel() {
        OpenAiChatOptions options = OpenAiChatOptions.builder()
                .baseUrl(AiCommon.BASEURL_BIGMODEL)   // 假设仍用同一个 LM Studio
                .apiKey(API_KEY_BIGMODEL)
                .model("glm-4.7-flash")   // 代码模型
//                .model("glm-4-flash-250414")   // 代码模型
                .build();

        return OpenAiChatModel.builder()
                .options(options)
                .build();
    }

    @Bean(name = "openrouterModel")
    public OpenAiChatModel openrouterModel() {
        OpenAiChatOptions options = OpenAiChatOptions.builder()
                .baseUrl(AiCommon.BASEURL_OPENROUTER)   // 假设仍用同一个 LM Studio
                .apiKey(API_KEY_OPENROUTER)
                .model("cohere/north-mini-code:free")   // 代码模型
                .build();

        return OpenAiChatModel.builder()
                .options(options)
                .build();
    }

    @Bean(name = "siliconflowModel")
    public OpenAiChatModel siliconflowModel() {
        OpenAiChatOptions options = OpenAiChatOptions.builder()
                .baseUrl(AiCommon.BASEURL_SILICONFLOW)   // 假设仍用同一个 LM Studio
                .apiKey(API_KEY_SILICONFLOW)
                .model("deepseek-ai/DeepSeek-V4-Pro")   // 代码模型
                .build();

        return OpenAiChatModel.builder()
                .options(options)
                .build();
    }


    @Bean(name = "volcengineModel")
    public OpenAiChatModel volcengineModel() {
        OpenAiChatOptions options = OpenAiChatOptions.builder()
                .baseUrl(AiCommon.BASEURL_VOLCENGINE)   // 假设仍用同一个 LM Studio
                .apiKey(API_KEY_VOLCENGINE)
                .model("doubao-seed-evolving")   // 代码模型
                .build();

        return OpenAiChatModel.builder()
                .options(options)
                .build();
    }

}
