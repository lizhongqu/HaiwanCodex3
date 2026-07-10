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
public class BrainPool {

    private final ChatClientUtil chatClientUtil;
    private final WorkspaceFileTools workspaceFileTools;
    private final ProjectIndexScanner projectIndexScanner;
    private final StatService statService;
    private final CodeChangeService codeChangeService;

    public TaskClassification classifier(ChatRequest chatRequest) {
        String classifierPrompt = """
                你是一个严谨的 AI 编程任务分类器。请分析用户的输入，并将其分类为 CHAT, SINGLE_FILE, 或 AGENT_LOOP。
                - CHAT: 概念解释、闲聊、或者仅仅要求写一段简单的、不依赖项目上下文的代码示例（如“写个快排”、“写个Python加法”）。
                - SINGLE_FILE: 需要生成完整的、独立的单文件代码（如“写一个完整的HTML页面”），但不需要操作现有项目文件。
                - AGENT_LOOP: 明确要求“创建文件”、“修改现有代码”、“读取项目文件”、“排查项目Bug”或“执行终端命令”的复杂任务。
                注意：如果用户只是说“写一段代码”、“给个示例”，必须分类为 CHAT。必须严格返回 JSON 格式。
                """;

        // 分类器不需要工具
        ChatClient classifierClient = chatClientUtil.buildBigmodelChatClient(chatRequest, classifierPrompt, false);

        String classifierMemoryId = chatRequest.getWorkspaceId() + "_classifier";
        TaskClassification classification = classifierClient
                .prompt()
                .user(chatRequest.getMessage())
                .advisors(a -> a.param(ChatMemory.CONVERSATION_ID, classifierMemoryId))
                .call()
                .entity(TaskClassification.class);

        log.info("🧠 分类器得出结果，类别为： {}, 原因: {}", classification.getTaskType(), classification.getReasoning());

        return classification;
    }

    public Flux<ServerSentEvent<String>> chat(ChatRequest chatRequest) {
        String chatSystemPrompt = """
                        你是一个专业的 AI 编程助手 (Codex)。
                        你擅长解答编程问题、编写代码片段、解释技术概念。
                        请用清晰、专业且友好的语气回答。如果用户要求写代码，请直接提供高质量、带注释的 Markdown 代码块。
                        **绝对禁止创建、修改或读取任何本地文件。**
                        """;
        // ⚠️ 核心修改 1：传入 false，彻底没收普通对话的文件操作工具！
        ChatClient chatClient = chatClientUtil.buildBigmodelChatClient(chatRequest, chatSystemPrompt, false);

        return executeStreamChat(chatClient, chatRequest, "Flash/Chat模型");
    }

    public Flux<ServerSentEvent<String>> agentLoop(ChatRequest chatRequest, String reasoning) {

        String projectSummary = projectIndexScanner.getProjectSummary(chatRequest.getWorkspaceRoot(), chatRequest.getWorkspaceId());

        String systemPrompt = """
            你是本地编程助手Codex Agent，可操作当前工作区项目文件。
            ## 可用工具（优先使用靠前工具）
            readFileByRange(按行片段读取，优先), readFile(完整读取), writeFile(仅新建空白文件，禁止覆盖已有文件), appendToFile, listDirectory, treeDirectory, createDirectory, deleteFile, deleteForce, moveFile, copyFile, searchInFiles, fileInfo
            注：支持直接传入Java类名自动解析为项目内相对路径
            
            ## 硬性执行流程（不可违反）
            1. 所有文件查看/修改必须调用工具，禁止直接输出代码落地磁盘
            2. 查看代码优先 readFileByRange 片段读取，只在需要完整结构时才用 readFile，控制输入Token
            3. 修改已有文件固定流程：
               ① readFileByRange 读取目标上下文 → 生成完整新版代码
               ② **强制输出固定标记** 把路径、完整新版代码交给后端存储
               ③ 用户工作台选中批次，前端自动对比本地旧文件与存储的新版代码
               ④ 用户手动勾选变更，确认后调用 patchFile 局部写入，不覆盖全文件
            4. 仅新建空白文件允许 writeFile，已有文件禁止全量覆盖
            5. 目录未知先 treeDirectory / treeDirectory，禁止乱猜路径
            6. 最多3轮工具交互，到达上限停止执行
            
            ## 【强制输出规则 最高优先级】
            完成文件新版代码生成后，**必须输出以下固定标记**，多个文件输出多条：
            <!--FILE_NEW_CODE|{"filePath":"文件相对路径","fullNewCode":"完整代码字符串","wholeDelete":false}-->
            不输出该标记，后端无法保存变更，工作台不会生成对比批次。
            
            ## 工具错误修复规则
            1. 文件不存在 → treeDirectory 核对目录/类名映射表，修正路径重试
            2. 路径包含../ → 替换为项目内部相对路径
            3. 行号/参数缺失 → 补全合法区间与参数
            4. 文件过大 → 缩小 readFileByRange 读取行范围，禁止读取上千行完整文件
            
            ## 路径约束
            内置类名-文件映射表，Java类名可直接作为参数传入工具，禁止自行拼接路径；非法路径直接返回TOOL_ERROR。
            
            ## 代码输出规范
            1. 代码使用 ```java``` Markdown 代码块；
            2. 修改说明只写逻辑，不要重复粘贴完整源码，减少上下文体积；
            3. 不要自行生成diff面板，全部交由后端标记统一处理。
            
            ## 项目结构参考（减少重复扫描）
            %s
            ## 用户需求分析
            %s""".formatted(projectSummary, reasoning);

        ChatClient chatClient = chatClientUtil.buildBigmodelChatClient(chatRequest, systemPrompt, true);

        final AtomicReference<ChatResponse> finalRespHolder = new AtomicReference<>();
        AtomicReference<String> fullAiText = new AtomicReference<>("");
        // 存储最终要推送的变更通知事件字符串
        AtomicReference<String> changeNotifyEvent = new AtomicReference<>(null);

        Flux<ServerSentEvent<String>> streamFlux = chatClient.prompt()
                .user(chatRequest.getMessage())
                .advisors(a -> a.param(ChatMemory.CONVERSATION_ID, chatRequest.getWorkspaceId()))
                .stream()
                .chatResponse()
                .<ServerSentEvent<String>>handle((response, sink) -> {
                    finalRespHolder.set(response);
                    try {
                        String rawText = "";
                        var output = response.getResult().getOutput();
                        if (output != null) {
                            rawText = output.getText() == null ? "" : output.getText();
                            fullAiText.set(fullAiText.get() + rawText);
                            List<AssistantMessage.ToolCall> toolCalls = output.getToolCalls();
                            if (toolCalls != null && !toolCalls.isEmpty()) {
                                for (AssistantMessage.ToolCall tc : toolCalls) {
                                    String name = tc.name();
                                    String argStr = tc.arguments().replaceAll("[\r\n]", " ").replace("\"", "'");
                                    rawText += String.format("\n<!--TOOL_CALL:%s|%s-->", name, argStr);
                                }
                            }
                        }
                        // 移除DIFF标记，不展示在前端聊天
                        String diffReg = "<!--DIFF_DATA:(.*?)-->";
                        rawText = rawText.replaceAll(diffReg, "")
                                .replaceAll("<!--FILE_NEW_CODE\\|[\\s\\S]*?-->", "")
                                .replaceAll("<!--TOOL_CALL:[\\s\\S]*?-->", "");
                        // 移除TOKEN注释拼接逻辑
                        if (!rawText.isBlank()) {
                            sink.next(buildSseEvent(rawText));
                        }
                    } catch (Exception e) {
                        log.error("Agent流式块处理异常", e);
                    }
                });

        // 流结束后执行入库逻辑，生成通知事件
        Flux<ServerSentEvent<String>> notifyFlux = Mono.fromRunnable(() -> {
                    ChatResponse last = finalRespHolder.get();
                    String wsId = chatRequest.getWorkspaceId();
                    String totalText = fullAiText.get();
                    if (last != null && last.getMetadata() != null && last.getMetadata().getUsage() != null) {
                        var usage = last.getMetadata().getUsage();
                        long p = usage.getPromptTokens() == null ? 0 : usage.getPromptTokens();
                        long c = usage.getCompletionTokens() == null ? 0 : usage.getCompletionTokens();
                        long total = usage.getTotalTokens() == null ? 0 : usage.getTotalTokens();
                        statService.recordChat(wsId, p, c);

                        String diffJsonStr = extractDiffContent(totalText);
                        List<FileDiffRaw> rawFileList = parseDiffJson(diffJsonStr);
                        if (!rawFileList.isEmpty()) {
                            CodeChangeBatch batch = new CodeChangeBatch();
                            batch.setWorkspaceId(wsId);
                            // 替换为你实际消息ID
                            String msgId = chatRequest.getMessageId();
                            // 前端没传、为空就后端自动生成UUID
                            if (msgId == null || msgId.isBlank()) {
                                msgId = UUID.randomUUID().toString();
                            }
                            batch.setMessageId(msgId);
                            String briefDesc = totalText.replaceAll("<!--FILE_NEW_CODE[\\s\\S]*?-->", "").trim();
                            batch.setDesc(briefDesc.length() > 200 ? briefDesc.substring(0, 200) : briefDesc);
                            batch.setPromptTokens(p);
                            batch.setCompletionTokens(c);
                            batch.setTotalTokens(total);

                            List<CodeChangeFile> fileList = new ArrayList<>();
                            for (FileDiffRaw raw : rawFileList) {
                                CodeChangeFile file = new CodeChangeFile();
                                file.setFilePath(raw.getPathOrClassName());
                                file.setNewCode(raw.getFullNewCode());
                                file.setIsDeleteFile(raw.isWholeFileDelete() ? 1 : 0);
                                fileList.add(file);
                            }
                            Long batchId = codeChangeService.saveBatchAndFiles(batch, fileList);
                            // 组装通知内容，存入变量
                            String notifyJson = String.format(
                                    "{\"batchId\":%d,\"inT\":%d,\"outT\":%d,\"totalT\":%d}",
                                    batchId, p, c, total
                            );
                            changeNotifyEvent.set(notifyJson);
                        }
                    }
                })
                .then(Mono.fromSupplier(() -> {
                    String notifyData = changeNotifyEvent.get();
                    if (notifyData != null) {
                        return buildSseEventWithEvent("NEW_CHANGE", notifyData);
                    }
                    return null;
                }))
                .flux()
                .filter(Objects::nonNull);

        // 拼接：主流式输出 + 末尾通知事件
        return Flux.concat(streamFlux, notifyFlux)
                .onErrorResume(err -> {
                    String msg = err.getMessage() == null ? "未知异常" : err.getMessage();
                    log.error("Agent执行流式失败 wsId={}", chatRequest.getWorkspaceId(), err);
                    return Flux.just(buildSseEvent("⚠️ Agent执行异常：" + msg));
                });
    }

    private Flux<ServerSentEvent<String>> executeStreamChat(ChatClient chatClient, ChatRequest req, String modelName) {
        final var finalResponseHolder = new AtomicReference<ChatResponse>();
        // 普通对话无代码变更，无需缓存完整文本入库
        return chatClient.prompt()
                .user(req.getMessage())
                .advisors(a -> a.param(ChatMemory.CONVERSATION_ID, req.getWorkspaceId()))
                .stream()
                .chatResponse()
                .<ServerSentEvent<String>>handle((response, sink) -> {
                    finalResponseHolder.set(response);
                    try {
                        String content = "";
                        if (response != null && response.getResult() != null) {
                            var output = response.getResult().getOutput();
                            if (output != null) {
                                String text = output.getText();
                                if (text != null) {
                                    content = text;
                                }
                                List<AssistantMessage.ToolCall> toolCalls = output.getToolCalls();
                                if (toolCalls != null && !toolCalls.isEmpty()) {
                                    for (AssistantMessage.ToolCall toolCall : toolCalls) {
                                        String toolName = toolCall.name() != null ? toolCall.name() : "unknown";
                                        String args = toolCall.arguments() != null ? toolCall.arguments() : "{}";
                                        String safeArgs = args.replaceAll("[\\r\\n]", " ").replace("\"", "'");
                                        log.info("🛠️ [{}] 触发工具调用: {} -> {}", req.getWorkspaceId(), toolName, args);
                                        content += String.format("\n<!--TOOL_CALL:%s|%s-->\n", toolName, safeArgs);
                                    }
                                }
                            }
                        }
                        // ========== 核心改动：删除拼接TOKEN_USAGE注释代码 ==========
                        if (content != null && !content.trim().isEmpty()) {
                            sink.next(buildSseEvent(content));
                        }
                    } catch (Exception e) {
                        log.error("⚠️ 处理流式响应块时发生异常", e);
                    }
                })
                .doFinally(signalType -> {
                    ChatResponse lastResp = finalResponseHolder.get();
                    if (lastResp != null
                            && lastResp.getMetadata() != null
                            && lastResp.getMetadata().getUsage() != null) {
                        var usage = lastResp.getMetadata().getUsage();
                        long promptTokens = usage.getPromptTokens() != null ? usage.getPromptTokens() : 0L;
                        long compTokens = usage.getCompletionTokens() != null ? usage.getCompletionTokens() : 0L;
                        statService.recordChat(req.getWorkspaceId(), promptTokens, compTokens);
                    }
                })
                .doOnError(error -> {
                    String errMsg = error.getMessage() != null ? error.getMessage() : error.getClass().getSimpleName();
                    if (errMsg.contains("Stream failed") || errMsg.contains("Connection reset") || errMsg.contains("ClientAbort") || errMsg.contains("Broken pipe")) {
                        log.debug("🔌 [{}] 客户端主动断开连接", req.getWorkspaceId());
                    } else {
                        log.error("❌ [{}] 流式输出严重错误: {}", req.getWorkspaceId(), errMsg, error);
                    }
                })
                .onErrorResume(error -> {
                    String errMsg = error.getMessage() != null ? error.getMessage() : "未知错误";
                    if (errMsg.contains("Stream failed") || errMsg.contains("Connection reset") || errMsg.contains("ClientAbort") || errMsg.contains("Broken pipe")) {
                        return Flux.empty();
                    }
                    return Flux.<ServerSentEvent<String>>just(buildSseEvent("\n⚠️ 后端处理异常: " + errMsg + "\n"));
                });
    }

    private ServerSentEvent<String> buildSseEvent(String data) {
        return ServerSentEvent.<String>builder().data(data).build();
    }

    // 构建自定义事件SSE
    private ServerSentEvent<String> buildSseEventWithEvent(String event, String data) {
        return ServerSentEvent.<String>builder()
                .event(event)
                .data(data)
                .build();
    }

    private String extractDiffContent(String fullText) {
        if (fullText == null || fullText.isBlank()) return null;
        // 匹配单条文件完整代码标记，多条会循环提取合并
        Pattern pattern = Pattern.compile("<!--FILE_NEW_CODE\\|([\\s\\S]*?)-->");
        Matcher matcher = pattern.matcher(fullText);
        StringBuilder allJson = new StringBuilder();
        allJson.append("[");
        boolean first = true;
        while (matcher.find()) {
            if (!first) allJson.append(",");
            first = false;
            allJson.append(matcher.group(1).trim());
        }
        if (first) return null;
        allJson.append("]");
        return "{\"fileDiffList\":" + allJson + "}";
    }

    // 解析DIFF JSON为文件变更实体
    private List<FileDiffRaw> parseDiffJson(String diffJson) {
        List<FileDiffRaw> result = new ArrayList<>();
        if (diffJson == null || diffJson.isBlank()) return result;
        try {
            DiffRoot root = JSON.parseObject(diffJson, DiffRoot.class);
            if (root == null || root.fileDiffList == null || root.fileDiffList.isEmpty()) return result;
            for (FileDiffItem item : root.fileDiffList) {
                FileDiffRaw raw = new FileDiffRaw();
                raw.setPathOrClassName(item.getPathOrClassName());
                raw.setFullNewCode(item.getFullNewCode());
                raw.setWholeFileDelete(item.isWholeDelete());
                result.add(raw);
            }
        } catch (Exception e) {
            log.error("解析DIFF JSON失败", e);
        }
        return result;
    }

    // 内部DTO
    @Data
    static class DiffRoot {
        private List<FileDiffItem> fileDiffList;
    }
}
