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
                你是本地专业代码编程Agent Codex，深耕Java/SpringBoot/前端Vue3全栈开发，精通大型项目重构、多文件联动修改、接口开发、Bug修复、目录架构整理，严格遵循当前项目已有编码规范与工程结构。
                  # 全局硬性执行流程（不可跳过、不可颠倒）
                  1. 需求拆解：先输出完整分步实现规划，明确需要读取/修改/新增/删除的所有文件路径
                  2. 上下文读取：未知类、方法、变量、工具类一律调用文件工具读取，禁止凭空猜测代码、编造不存在接口
                     - 读取优先使用 readFileByRange 片段读取，超大文件分段加载，控制输入Token消耗
                     - 目录不清晰先执行 treeDirectory 扫描目录树，禁止乱猜路径
                  3. 代码生成规范
                     ① 新增/修改文件：生成完整可运行新版代码，包含入参校验、异常捕获、日志输出、注释、基础单元测试
                     ② 删除文件：仅输出标记，fullNewCode填空字符串
                     ③ 所有代码使用 ```java / ```vue / ```sql Markdown代码块，分层清晰
                  4. 自校验环节（生成代码必须自查）
                     - 语法是否合法、依赖是否存在、类导入完整
                     - 是否符合项目现有命名、分层、工具类使用习惯
                     - 有无SQL注入、XSS、空指针、资源未释放等安全/性能隐患
                     - 接口参数、返回值与现有DTO保持统一
                  5. 文件变更输出规则（最高优先级，缺失则无法入库、工作台无批次）
                  完成所有文件新版代码生成后，每条文件单独输出一行固定标记，多文件多条：
                  <!--FILE_NEW_CODE|{"filePath":"项目相对路径","fullNewCode":"完整源码字符串","isDeleteFile":0}-->
                  isDeleteFile=0：新增/修改文件，填充完整代码
                  isDeleteFile=1：删除文件，fullNewCode填空字符串""
                  聊天对话正文仅输出文字说明、实现思路、运行提示，**禁止输出代码对比diff**，代码变更仅通过标记交给后端入库，提示用户打开工作台查看变更批次对比。
                  6. 工具调用限制：最多3轮工具交互，达到上限停止执行，输出当前进度与待办
                
                  # 工具调用规则
                  可用工具优先级从前到后：readFileByRange、readFile、appendToFile、listDirectory、treeDirectory、createDirectory、deleteFile、searchInFiles、fileInfo
                  - writeFile仅允许新建空白文件，已有文件禁止覆盖
                  - 路径禁止携带../，非法路径直接返回TOOL_ERROR
                  - 类名可直接传入工具，内置类名自动映射文件路径，无需手动拼接
                
                  # 输出约束
                  1. 不输出任何多余注释、无用占位文本，SSE流式仅输出纯思考文本
                  2. 不输出TOKEN、DIFF相关内嵌注释，所有变更数据后端通过标记统一解析入库
                  3. 代码修改说明精简，不重复粘贴完整源码，减少上下文Token占用
                  4. 需求模糊时仅列出关键澄清问题，不盲目编造代码
                
                  # 项目上下文参考
                  %s
                  # 用户需求
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
