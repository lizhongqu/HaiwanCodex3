package com.haiwancodex.www.router;

import com.haiwancodex.www.dto.ChatRequest;
import com.haiwancodex.www.dto.TaskClassification;
import com.haiwancodex.www.service.CodeChangeService;
import com.haiwancodex.www.util.ProjectIndexScanner;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class CodexRouter {

    private final BrainPool brainPool;
    private final ProjectIndexScanner projectIndexScanner;
    private final CodeChangeService codeChangeService;

    public Flux<ServerSentEvent<String>> routeAndExecute(ChatRequest chatRequest) {
        // 每次对话触发一次索引刷新，异步更新缓存，不阻塞主流程
        projectIndexScanner.scanWorkspace(chatRequest.getWorkspaceRoot(), chatRequest.getWorkspaceId());
        // 1. 生成本次消息唯一ID
        String messageId = UUID.randomUUID().toString().replace("-", "");

        chatRequest.setMessageId(messageId);

        // 2. 保留你原有的提示词组装、模型调用逻辑
        TaskClassification classification = brainPool.classifier(chatRequest);

        Flux<ServerSentEvent<String>> aiContentStream;
        switch (classification.getTaskType()) {
            case CHAT, SINGLE_FILE -> {
                return brainPool.chat(chatRequest, classification.getReasoning());
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