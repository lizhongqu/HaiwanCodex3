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
            <!--FILE_NEW_CODE|{"filePath":"文件相对路径","fullNewCode":"完整代码字符串","isDeleteFile":0}-->
            字段规则：
            isDeleteFile=0 新增/修改文件，fullNewCode填完整代码
            isDeleteFile=1 删除文件，fullNewCode填空字符串""
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
            
            ## 项目结构参考（减少重复扫描）
            %s
            ## 用户需求分析
            %s""".formatted(projectSummary, reasoning);

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
