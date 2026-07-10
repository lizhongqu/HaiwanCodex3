package com.haiwancodex.www.router;

import com.alibaba.fastjson2.JSON;
import com.haiwancodex.www.common.TaskType;
import com.haiwancodex.www.dto.ChatRequest;
import com.haiwancodex.www.dto.FileDiffItem;
import com.haiwancodex.www.dto.FileDiffRaw;
import com.haiwancodex.www.entity.CodeChangeBatch;
import com.haiwancodex.www.entity.CodeChangeFile;
import com.haiwancodex.www.service.CodeChangeService;
import com.haiwancodex.www.service.StatService;
import com.haiwancodex.www.tool.WorkspaceFileTools;
import com.haiwancodex.www.util.ChatClientUtil;
import com.haiwancodex.www.util.ProjectIndexScanner;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Slf4j
@Service
@RequiredArgsConstructor
public class CodexRouter {

    private final ChatClientUtil chatClientUtil;
    private final BrainPool brainPool;
    private final WorkspaceFileTools workspaceFileTools;
    private final ProjectIndexScanner projectIndexScanner;
    private final StatService statService;
    private final CodeChangeService codeChangeService;

    public Flux<ServerSentEvent<String>> routeAndExecute(ChatRequest chatRequest) {

        // 每次对话触发一次索引刷新，异步更新缓存，不阻塞主流程
        projectIndexScanner.scanWorkspace(chatRequest.getWorkspaceRoot(), chatRequest.getWorkspaceId());
        // 1. 生成本次消息唯一ID
        String messageId = UUID.randomUUID().toString().replace("-", "");
        // 2. 保留你原有的提示词组装、模型调用逻辑
        TaskClassification classification = brainPool.classifier(chatRequest);

        switch (classification.getTaskType()) {
            case CHAT, SINGLE_FILE -> {
                return brainPool.chat(chatRequest);
            }
            case AGENT_LOOP -> {
                return brainPool.agentLoop(chatRequest, classification.getReasoning());
            }
            default -> {
                throw new IllegalStateException("Unexpected value: " + classification.getTaskType());
            }
        }
    }
}