package com.haiwancodex.www.util;

import com.haiwancodex.www.dto.ChatRequest;
import com.haiwancodex.www.tool.WorkspaceFileTools;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.MessageChatMemoryAdvisor;
import org.springframework.ai.chat.client.advisor.SimpleLoggerAdvisor;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.Map;

@Component("chatClientUtil")
public class ChatClientUtil {

    @Autowired @Qualifier("bigmodelModel") private OpenAiChatModel bigmodelModel;
    @Autowired @Qualifier("openrouterModel") private OpenAiChatModel openrouterModel;
    @Autowired @Qualifier("siliconflowModel") private OpenAiChatModel siliconflowModel;
    @Autowired @Qualifier("volcengineModel") private OpenAiChatModel volcengineModel;

    @Autowired private WorkspaceFileTools workspaceFileTools;
    @Autowired private MessageChatMemoryAdvisor chatMemoryAdvisor;
    @Autowired private SimpleLoggerAdvisor simpleLoggerAdvisor;

    // ================= 智谱 Bigmodel =================
    public ChatClient buildBigmodelChatClient(ChatRequest req, String systemPrompt, boolean enableTools) {
        return buildClient(bigmodelModel, req, systemPrompt, enableTools);
    }

    // ================= OpenRouter =================
    public ChatClient buildOpenrouterChatClient(ChatRequest req, String systemPrompt, boolean enableTools) {
        return buildClient(openrouterModel, req, systemPrompt, enableTools);
    }

    // ================= SiliconFlow =================
    public ChatClient buildSiliconflowChatClient(ChatRequest req, String systemPrompt, boolean enableTools) {
        return buildClient(siliconflowModel, req, systemPrompt, enableTools);
    }

    // ================= SiliconFlow =================
    public ChatClient buildVolcengineChatClient(ChatRequest req, String systemPrompt, boolean enableTools) {
        return buildClient(volcengineModel, req, systemPrompt, enableTools);
    }

    /**
     * 核心构建逻辑
     */
    private ChatClient buildClient(OpenAiChatModel model, ChatRequest req, String systemPrompt, boolean enableTools) {
        Map<String, Object> toolContext = new HashMap<>();
        if (req.getWorkspaceId() != null && !req.getWorkspaceId().isBlank()) toolContext.put("workspaceId", req.getWorkspaceId());
        if (req.getWorkspaceRoot() != null && !req.getWorkspaceRoot().isBlank()) toolContext.put("workspaceRoot", req.getWorkspaceRoot());

        OpenAiChatOptions.Builder options = OpenAiChatOptions.builder()
                .temperature(req.getTemperature())
                .maxTokens(req.getNumCtx())
                .topP(req.getTopP())
                .streamUsage(true);

        ChatClient.Builder builder = ChatClient.builder(model)
                .defaultOptions(options)
                .defaultAdvisors(chatMemoryAdvisor, simpleLoggerAdvisor);

        if (systemPrompt != null && !systemPrompt.isBlank()) {
            builder.defaultSystem(systemPrompt);
        }

        // ⚠️ 核心修改：只有当 enableTools 为 true 时，才挂载文件工具
        if (enableTools) {
            builder.defaultToolContext(toolContext)
                    .defaultTools(workspaceFileTools.getToolCallbacks());
        }

        return builder.build();
    }
}
