package com.haiwancodex.www.router;

import com.haiwancodex.www.common.TaskType;
import com.haiwancodex.www.dto.ChatRequest;
import com.haiwancodex.www.service.StatService;
import com.haiwancodex.www.tool.WorkspaceFileTools;
import com.haiwancodex.www.util.ChatClientUtil;
import com.haiwancodex.www.util.ProjectIndexScanner;
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

import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

@Slf4j
@Service
public class CodexRouter {

    private final ChatClientUtil chatClientUtil;
    private final WorkspaceFileTools workspaceFileTools;
    private final ProjectIndexScanner projectIndexScanner;
    private final StatService statService;

    // 构造器追加
    public CodexRouter(ChatClientUtil chatClientUtil,
                       WorkspaceFileTools workspaceFileTools,
                       ProjectIndexScanner projectIndexScanner,
                       StatService statService) {
        this.chatClientUtil = chatClientUtil;
        this.workspaceFileTools = workspaceFileTools;
        this.projectIndexScanner = projectIndexScanner;
        this.statService = statService;
    }

    public Flux<ServerSentEvent<String>> routeAndExecute(ChatRequest chatRequest) {

        // 每次对话触发一次索引刷新，异步更新缓存，不阻塞主流程
        projectIndexScanner.scanWorkspace(chatRequest.getWorkspaceRoot(), chatRequest.getWorkspaceId());

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

        log.info("🧠 分类器得出结果，类别为： {}, 原因: {}", classification.taskType(), classification.reasoning());

        return switch (classification.taskType()) {
            case CHAT, SINGLE_FILE -> {
                String chatSystemPrompt = """
                        你是一个专业的 AI 编程助手 (Codex)。
                        你擅长解答编程问题、编写代码片段、解释技术概念。
                        请用清晰、专业且友好的语气回答。如果用户要求写代码，请直接提供高质量、带注释的 Markdown 代码块。
                        **绝对禁止创建、修改或读取任何本地文件。**
                        """;
                // ⚠️ 核心修改 1：传入 false，彻底没收普通对话的文件操作工具！
                ChatClient chatClient = chatClientUtil.buildBigmodelChatClient(chatRequest, chatSystemPrompt, false);
                yield executeStreamChat(chatClient, chatRequest, "Flash/Chat模型");
            }
            case AGENT_LOOP -> {
                yield executeAgentLoop(chatRequest, classification.reasoning());
            }
            default -> throw new IllegalStateException("Unexpected value: " + classification.taskType());
        };
    }

    private Flux<ServerSentEvent<String>> executeAgentLoop(ChatRequest chatRequest, String reasoning) {
        String projectSummary = projectIndexScanner.getProjectSummary(chatRequest.getWorkspaceRoot(), chatRequest.getWorkspaceId());

        String systemPrompt = """
            你是本地编程助手Codex Agent，可操作当前工作区文件。
              ## 可用工具
              readFile(读取完整文件), readFileByRange(按行读取代码片段，优先使用), diffFile(生成新旧代码差异面板，不修改磁盘), patchFile(应用用户勾选的局部变更写入文件), writeFile(仅新建空白文件使用，禁止覆盖已有文件), appendToFile, listDirectory, treeDirectory, createDirectory, deleteFile, deleteForce(强删), moveFile, copyFile, searchInFiles, fileInfo
              注：路径统一使用相对路径，支持直接传入Java类名自动匹配文件路径
            
              ## 执行规则
              1. 文件增删改查必须调用工具，禁止直接输出代码落地磁盘
              2. 查看代码优先使用 readFileByRange 指定行区间读取片段，仅需完整文件结构时才使用 readFile，避免消耗大量token
              3. 修改已有文件强制流程：
                 ① 调用readFileByRange读取目标代码上下文，生成新版完整代码
                 ② 必须调用 diffFile 对比本地原始文件与新版代码，生成行级结构化变更
                 ③ diff结果推送前端展示IDE风格对比面板，由用户手动勾选需要保留/舍弃的修改
                 ④ 用户确认勾选变更后，通过 patchFile 局部合并写入文件，不会全量覆盖源码
              4. 仅新建全新空白文件时，才允许使用 writeFile 全量写入
              5. 不确定文件路径、业务代码位置时先调用treeDirectory查看目录
              6. 最多交互3轮辩论/工具调用，到达上限停止执行并提示用户
            
              ## 工具报错处理规则
              工具返回以[TOOL_ERROR]开头即执行失败，请按指引修复并重试：
              1. 文件不存在 → 先treeDirectory查看目录 + 查表核对类名，修正路径后重试
              2. 路径包含../ → 替换为标准项目内相对路径
              3. 参数为空、行号非法 → 补全参数，修正startLine/endLine行号区间
              4. 读取代码过长 → 缩小readFileByRange读取行范围，不要读取完整大文件
            
              ## 路径查找强制规则
              1. 系统内置【类名-文件路径映射表】，所有Java类优先根据类名查表自动匹配完整相对路径；
              2. 映射表查询不到目标类时，再调用 searchInFiles 语义检索或 treeDirectory 遍历目录；
              3. 支持直接向工具传入Java类名，后端自动翻译为文件路径，无需手动拼接路径；
              4. 禁止自行拼接文件路径，路径格式错误会直接返回TOOL_ERROR。
            
              ## 文件读取强制约束（控制token消耗）
              1. 只需要查看单个方法、局部业务逻辑时，必须使用 readFileByRange 指定行号读取片段；
              2. 禁止无差别调用readFile加载上千行完整文件，会造成输入token暴涨；
              3. 向量检索、searchInFiles拿到代码片段后，优先使用片段信息编码，仅信息不足时再读取文件。
            
              ## 输出规范
              1. 展示Java代码必须使用```java``` Markdown代码块
              2. 输出修改说明时，只描述变更逻辑，不要重复粘贴完整源码，减少上下文体积
              3. diffFile生成的变更数据会自动交由前端渲染，你不需要手动罗列全部代码变更
            
              ## 项目结构参考（优先使用，减少重复目录遍历）
              %s
              ## 用户需求分析
              %s""".formatted(projectSummary, reasoning);

        ChatClient chatClient = chatClientUtil.buildBigmodelChatClient(chatRequest, systemPrompt, true);
        final AtomicReference<ChatResponse> finalRespHolder = new AtomicReference<>();

        // 流式返回，实时解析标记下发前端
        return chatClient.prompt()
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
                            // 处理工具调用标记
                            List<AssistantMessage.ToolCall> toolCalls = output.getToolCalls();
                            if (toolCalls != null && !toolCalls.isEmpty()) {
                                for (AssistantMessage.ToolCall tc : toolCalls) {
                                    String name = tc.name();
                                    String argStr = tc.arguments().replaceAll("[\r\n]", " ").replace("\"", "'");
                                    rawText += String.format("\n<!--TOOL_CALL:%s|%s-->", name, argStr);
                                }
                            }
                        }

                        // 解析DIFF标记，单独提取推送
                        String diffReg = "<!--DIFF_DATA:(.*?)-->";
                        java.util.regex.Pattern pattern = java.util.regex.Pattern.compile(diffReg);
                        java.util.regex.Matcher matcher = pattern.matcher(rawText);
                        while (matcher.find()) {
                            // diff数据原样下发，弹窗用
                            String diffJson = matcher.group(1);
                            sink.next(buildSseEvent(String.format("<!--DIFF_DATA:%s-->", diffJson)));
                        }
                        // 移除diff标记，不展示给用户
                        rawText = rawText.replaceAll(diffReg, "");

                        // 追加token标记
                        if (response.getMetadata() != null && response.getMetadata().getUsage() != null) {
                            var usage = response.getMetadata().getUsage();
                            long prompt = usage.getPromptTokens() == null ? 0 : usage.getPromptTokens();
                            long comp = usage.getCompletionTokens() == null ? 0 : usage.getCompletionTokens();
                            long total = usage.getTotalTokens() == null ? 0 : usage.getTotalTokens();
                            rawText += String.format("<!--TOKEN_USAGE:%d,%d,%d-->", prompt, comp, total);
                        }

                        if (!rawText.isBlank()) {
                            sink.next(buildSseEvent(rawText));
                        }
                    } catch (Exception e) {
                        log.error("Agent流式块处理异常", e);
                    }
                })
                // 流结束上报Token统计
                .doFinally(signal -> {
                    ChatResponse last = finalRespHolder.get();
                    if (last != null && last.getMetadata() != null && last.getMetadata().getUsage() != null) {
                        var usage = last.getMetadata().getUsage();
                        long p = usage.getPromptTokens() == null ? 0 : usage.getPromptTokens();
                        long c = usage.getCompletionTokens() == null ? 0 : usage.getCompletionTokens();
                        statService.recordChat(chatRequest.getWorkspaceId(), p, c);
                    }
                })
                .onErrorResume(err -> {
                    String msg = err.getMessage() == null ? "未知异常" : err.getMessage();
                    log.error("Agent执行流式失败 wsId={}", chatRequest.getWorkspaceId(), err);
                    return Flux.just(buildSseEvent("⚠️ Agent执行异常：" + msg));
                });
    }

    private Flux<ServerSentEvent<String>> executeStreamChat(ChatClient chatClient, ChatRequest req, String modelName) {
        // 存储最终完整响应，用于流结束后统计token
        final var finalResponseHolder = new AtomicReference<ChatResponse>();

        return chatClient.prompt()
                .user(req.getMessage())
                .advisors(a -> a.param(ChatMemory.CONVERSATION_ID, req.getWorkspaceId()))
                .stream()
                .chatResponse()
                // ⚠️ 终极修复：使用 handle 替代 flatMap！
                // handle 是同步且保序的，完美解决 flatMap 导致的文本乱序问题，且不需要返回 Mono.empty()
                // ⚠️ 修复 1：在 handle 前面加上 .<ServerSentEvent<String>>，强制指定泛型！
                .<ServerSentEvent<String>>handle((response, sink) -> {
                    // 每一块分片进来，更新缓存为当前最新response
                    finalResponseHolder.set(response);

                    try {
                        String content = "";

                        if (response != null && response.getResult() != null) {
                            var output = response.getResult().getOutput();

                            if (output != null) {
                                // 兼容不同版本的 Spring AI (getText / getContent)
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

                        if (response != null && response.getMetadata() != null && response.getMetadata().getUsage() != null) {
                            var usage = response.getMetadata().getUsage();
                            long totalTokens = usage.getTotalTokens() != null ? usage.getTotalTokens() : 0;
                            if (totalTokens > 0) {
                                long promptTokens = usage.getPromptTokens() != null ? usage.getPromptTokens() : 0;
                                long completionTokens = usage.getCompletionTokens() != null ? usage.getCompletionTokens() : 0;
                                content += String.format("<!--TOKEN_USAGE:%d,%d,%d-->", promptTokens, completionTokens, totalTokens);
                            }
                        }

                        // ⚠️ 核心：只有内容不为空时，才通过 sink.next 发送数据，自动实现过滤！
                        if (content != null && !content.trim().isEmpty()) {
                            sink.next(buildSseEvent(content));
                        }
                    } catch (Exception e) {
                        log.error("⚠️ 处理流式响应块时发生异常", e);
                        // 发生异常不调用 sink.next，安全跳过当前块
                    }
                })
                // 新增：流结束钩子，统一统计token用量（核心改动）
                .doFinally(signalType -> {
                    ChatResponse lastResp = finalResponseHolder.get();
                    if (lastResp != null
                            && lastResp.getMetadata() != null
                            && lastResp.getMetadata().getUsage() != null) {
                        var usage = lastResp.getMetadata().getUsage();
                        long promptTokens = usage.getPromptTokens() != null ? usage.getPromptTokens() : 0L;
                        long compTokens = usage.getCompletionTokens() != null ? usage.getCompletionTokens() : 0L;
                        // 上报会话次数+token消耗
                        statService.recordChat(req.getWorkspaceId(), promptTokens, compTokens);
                    }
                })
                // ... 前面的 .handle 逻辑保持不变 ...
                .doOnError(error -> {
                    // ⚠️ 优雅处理客户端断开连接 (Stream failed / Connection reset)
                    String errMsg = error.getMessage() != null ? error.getMessage() : error.getClass().getSimpleName();
                    if (errMsg.contains("Stream failed") || errMsg.contains("Connection reset") || errMsg.contains("ClientAbort") || errMsg.contains("Broken pipe")) {
                        log.debug("🔌 [{}] 客户端主动断开了连接 (前端可能刷新、关闭或 JS 报错)", req.getWorkspaceId());
                    } else {
                        log.error("❌ [{}] 流式输出发生严重错误: {}", req.getWorkspaceId(), errMsg, error);
                    }
                })
                .onErrorResume(error -> {
                    String errMsg = error.getMessage() != null ? error.getMessage() : "未知错误";
                    // 如果是客户端断开，直接返回空流，不再向前端发送任何数据
                    if (errMsg.contains("Stream failed") || errMsg.contains("Connection reset") || errMsg.contains("ClientAbort") || errMsg.contains("Broken pipe")) {
                        return Flux.empty();
                    }
                    // 真正的服务端错误，才向前端发送提示
                    return Flux.<ServerSentEvent<String>>just(buildSseEvent("\n⚠️ 后端处理异常: " + errMsg + "\n"));
                });

    }

    public record TaskClassification(TaskType taskType, String reasoning) {}

    private ServerSentEvent<String> buildSseEvent(String data) {
        return ServerSentEvent.<String>builder().data(data).build();
    }
}
