package com.haiwancodex.www.router;

import com.alibaba.fastjson2.JSON;
import com.haiwancodex.www.dto.*;
import com.haiwancodex.www.entity.CodeChangeBatch;
import com.haiwancodex.www.entity.CodeChangeFile;
import com.haiwancodex.www.service.CodeChangeService;
import com.haiwancodex.www.service.StatService;
import com.haiwancodex.www.tool.WorkspaceFileTools;
import com.haiwancodex.www.util.ChatClientUtil;
import com.haiwancodex.www.util.CodeChangeParser;
import com.haiwancodex.www.util.ProjectIndexScanner;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.metadata.Usage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.publisher.SynchronousSink;

import java.time.Duration;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

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

    public Flux<ServerSentEvent<String>> chat(ChatRequest chatRequest, String reasoning) {


        String chatSystemPrompt = """
                        你是一个专业的 AI 编程助手 (Codex)。
                        你擅长解答编程问题、编写代码片段、解释技术概念。
                        请用清晰、专业且友好的语气回答。如果用户要求写代码，请直接提供高质量、带注释的 Markdown 代码块。
                        **绝对禁止创建、修改或读取任何本地文件。**
                        """;
        // ⚠️ 核心修改 1：传入 false，彻底没收普通对话的文件操作工具！
        ChatClient chatClient = chatClientUtil.buildBigmodelChatClient(chatRequest, chatSystemPrompt, false);
        AtomicReference<ChatResponse> finalResponse = new AtomicReference<>();
        AtomicBoolean loadingFlag = new AtomicBoolean(true);
        // 普通对话无代码变更，无需缓存完整文本入库
        return chatClient.prompt()
                .user(chatRequest.getMessage())
                .advisors(a -> a.param(ChatMemory.CONVERSATION_ID, chatRequest.getWorkspaceId()))
                .stream()
                .chatResponse()
                // 建立连接，打开加载状态
                .doOnSubscribe(sub -> loadingFlag.set(true))
                // 超时兜底，防止长时间无响应卡死
                .timeout(Duration.ofSeconds(90))
                // 逐块处理：原样下发，只拼接原始全文，不做任何清洗
                .handle((ChatResponse response, SynchronousSink<ServerSentEvent<String>> sink) -> {
                    finalResponse.set(response);
                    String text = response.getResult().getOutput().getText();
                    if (text == null || text.isBlank()) {
                        return;
                    }
                    sink.next(buildSseEventWithEvent("message", text));
                })
                // 过滤空无效事件，减少前端无用推送
                .filter(event -> event.data() != null && !event.data().isBlank())
                // 【核心逻辑】仅流正常完整结束时执行
                .doOnComplete(() -> {
                })
                // 异常日志打印，不拦截异常
                .doOnError(err -> log.error("Agent流式输出异常", err))
                // 异常兜底：返回错误提示 + 空done事件，前端关闭loading
                .onErrorResume(err -> {
                    String errMsg = "\n\n⚠️ 模型连接中断，本次生成未完成";
                    ServerSentEvent<String> errEvent = buildSseEventWithEvent("message", errMsg);
                    Map tokens = new HashMap();
                    tokens.put("promptTokens", 0);
                    tokens.put("completionTokens", 0);
                    tokens.put("totalTokens", 0);
                    ServerSentEvent<String> doneEvent = buildSseEventWithEvent("done", JSON.toJSONString(tokens));
                    return Flux.just(errEvent, doneEvent);
                })
                // 无论成功/失败/前端关闭，统一关闭加载状态
                .doFinally(signalType -> loadingFlag.set(false))
                .concatWith(Mono.fromSupplier(() -> {
                    Usage usage = finalResponse.get().getMetadata().getUsage();
                    long promptTokens = usage.getPromptTokens() == null ? 0 : usage.getPromptTokens();
                    long completionTokens = usage.getCompletionTokens() == null ? 0 : usage.getCompletionTokens();
                    long totalTokens = usage.getTotalTokens() == null ? 0 : usage.getTotalTokens();
                    Map tokens = new HashMap();
                    tokens.put("promptTokens", promptTokens);
                    tokens.put("completionTokens", completionTokens);
                    tokens.put("totalTokens", totalTokens);
                    return buildSseEventWithEvent("done", JSON.toJSONString(tokens));
                }));
    }

    public Flux<ServerSentEvent<String>> agentLoop(ChatRequest chatRequest, String reasoning) {

        String projectSummary = projectIndexScanner.getProjectSummary(chatRequest.getWorkspaceRoot(), chatRequest.getWorkspaceId());

        String systemPrompt = """
            你是本地专业代码编程Agent Codex，精通Java/SpringBoot/Vue3全栈开发与项目重构，严格遵循已有编码规范。
        
            # 全局硬性执行流程（不可跳过）
            1. 需求拆解：先输出分步规划，明确需要读取/修改/新增/删除的所有文件路径。
            2. 上下文读取：未知的类、方法、接口必须通过 readFile / readFileByRange 等只读工具获取，严禁猜测。
            3. 代码生成：
               - 新增/修改文件：生成完整可运行的新版代码，含校验、异常处理、日志、注释。
               - 删除文件：仅输出标记，fullNewCode 填空字符串。
               - 所有代码使用标准 Markdown 代码块（```java / ```vue / ```sql 等）。
            4. 自校验（生成后必须自查）：
               - 语法/依赖/导入是否完整。
               - 是否符合项目命名、分层、工具类习惯。
               - 有无安全/性能隐患。
            5. 变更输出规则（最高优先级！）：
               完成所有代码生成后，**每条文件单独输出一行固定标记**，格式必须严格遵守：
               [CODE_JSON]{"filePath":"相对路径","fullNewCode":"完整源码","isDeleteFile":0}[/CODE_JSON]
            注意：
            - 整个 JSON 对象紧凑输出（不要换行）。
            - fullNewCode 中的双引号必须转义为 \\"，换行转义为 \\\\n，禁止裸换行破坏 JSON。
            - isDeleteFile=0 表示新增/修改；isDeleteFile=1 表示删除（此时 fullNewCode 为空字符串 ""）。
            - **不要**在聊天正文中输出任何代码差异（diff）或重复粘贴源码，所有变更通过标记传递给系统。
            
            # 重要行为约束
            - **你无权直接修改项目文件**，所有文件变更只能通过上述标记提交，由用户在工作台手动审核后应用。
            - 回答中只包含：实现思路、关键注意事项、运行说明，不要生成 diff 或重复粘贴大段代码。
            - 工具调用限制：最多 3 轮只读工具交互（如 readFile、listDirectory 等），达到上限后立即总结当前进度与待办。
            - 需求模糊时，只提 1~2 个关键澄清问题，不盲目编造。
            
            # 项目上下文
            %s
            
            # 用户需求
            %s
            """.formatted(projectSummary, reasoning);

        ChatClient chatClient = chatClientUtil.buildBigmodelChatClient(chatRequest, systemPrompt, true);

        AtomicReference<ChatResponse> finalResponse = new AtomicReference<>();
        AtomicBoolean loadingFlag = new AtomicBoolean(true);
        StringBuilder fullAiText = new StringBuilder();

        return chatClient.prompt()
                .user(chatRequest.getMessage())
                .advisors(a -> a.param(ChatMemory.CONVERSATION_ID, chatRequest.getWorkspaceId()))
                .stream()
                .chatResponse()
                // 建立连接，打开加载状态
                .doOnSubscribe(sub -> loadingFlag.set(true))
                // 超时兜底，防止长时间无响应卡死
                .timeout(Duration.ofSeconds(90))
                // 逐块处理：原样下发，只拼接原始全文，不做任何清洗
                .handle((ChatResponse response, SynchronousSink<ServerSentEvent<String>> sink) -> {
                    finalResponse.set(response);
                    String text = response.getResult().getOutput().getText();
                    if (text == null || text.isBlank()) {
                        return;
                    }
                    fullAiText.append(text);
                    sink.next(buildSseEventWithEvent("message", text));
                })
                // 过滤空无效事件，减少前端无用推送
                .filter(event -> event.data() != null && !event.data().isBlank())
                // 【核心入库逻辑】仅流正常完整结束时执行
                .doOnComplete(() -> {
                    String totalText = fullAiText.toString();
                    String workspaceId = chatRequest.getWorkspaceId();
                    String messageId = chatRequest.getMessageId();
                    String message = chatRequest.getMessage();
                    Usage usage = finalResponse.get().getMetadata().getUsage();
                    long promptTokens = usage.getPromptTokens() == null ? 0 : usage.getPromptTokens();
                    long completionTokens = usage.getCompletionTokens() == null ? 0 : usage.getCompletionTokens();
                    long totalTokens = usage.getTotalTokens() == null ? 0 : usage.getTotalTokens();

                    CodeChangeBatch codeChangeBatch = CodeChangeBatch.builder()
                            .workspaceId(workspaceId)
                            .messageId(messageId)
                            .desc(message)
                            .promptTokens(promptTokens)
                            .completionTokens(completionTokens)
                            .totalTokens(totalTokens)
                            .build();

                    // 一次性解析所有文件代码块，自动兼容N个文件
                    List<CodeChangeFile> CodeChangeFileList = CodeChangeParser.parseAllFileCode(totalText);
                    if (!CodeChangeFileList.isEmpty()) {
                        codeChangeService.saveBatchAndFiles(codeChangeBatch, CodeChangeFileList);
                    }
                })
                // 异常日志打印，不拦截异常
                .doOnError(err -> log.error("Agent流式输出异常", err))
                // 异常兜底：返回错误提示 + 空done事件，前端关闭loading
                .onErrorResume(err -> {
                    String errMsg = "\n\n⚠️ 模型连接中断，本次生成未完成";
                    ServerSentEvent<String> errEvent = buildSseEventWithEvent("message", errMsg);
                    Map tokens = new HashMap();
                    tokens.put("promptTokens", 0);
                    tokens.put("completionTokens", 0);
                    tokens.put("totalTokens", 0);
                    ServerSentEvent<String> doneEvent = buildSseEventWithEvent("done", JSON.toJSONString(tokens));
                    return Flux.just(errEvent, doneEvent);
                })
                // 无论成功/失败/前端关闭，统一关闭加载状态
                .doFinally(signalType -> loadingFlag.set(false))
                .concatWith(Mono.fromSupplier(() -> {
                    Usage usage = finalResponse.get().getMetadata().getUsage();
                    long promptTokens = usage.getPromptTokens() == null ? 0 : usage.getPromptTokens();
                    long completionTokens = usage.getCompletionTokens() == null ? 0 : usage.getCompletionTokens();
                    long totalTokens = usage.getTotalTokens() == null ? 0 : usage.getTotalTokens();
                    Map tokens = new HashMap();
                    tokens.put("promptTokens", promptTokens);
                    tokens.put("completionTokens", completionTokens);
                    tokens.put("totalTokens", totalTokens);
                    return buildSseEventWithEvent("done", JSON.toJSONString(tokens));
                }));
    }

    // 构建自定义事件SSE
    private ServerSentEvent<String> buildSseEventWithEvent(String event, String data) {
        return ServerSentEvent.<String>builder()
                .event(event)
                .data(data)
                .build();
    }
}
