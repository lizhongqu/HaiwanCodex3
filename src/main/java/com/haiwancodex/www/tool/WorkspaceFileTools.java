package com.haiwancodex.www.tool;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.haiwancodex.www.common.ToolErrType;
import com.haiwancodex.www.dto.CodeDiffItem;
import com.haiwancodex.www.dto.FileDiffResult;
import com.haiwancodex.www.dto.MultiFileDiffResult;
import com.haiwancodex.www.service.CodeDiffService;
import com.haiwancodex.www.service.StatService;
import com.haiwancodex.www.util.DiffUtil;
import com.haiwancodex.www.util.ProjectIndexScanner;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.ai.tool.method.MethodToolCallbackProvider;
import org.springframework.stereotype.Component;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * 工作区文件操作工具集
 * 所有路径必须相对于 workspaceRoot（从 ToolContext 中获取）
 */
@Component
@RequiredArgsConstructor
public class WorkspaceFileTools {


    private final ProjectIndexScanner projectIndexScanner;
    private final StatService statService;
    private final CodeDiffService codeDiffService;
    private final ObjectMapper objectMapper;
    private final DiffUtil diffUtil;

    private static final Logger log = LoggerFactory.getLogger(WorkspaceFileTools.class);

    // -------------------- 读文件 --------------------
    private static final int MAX_FILE_CHAR = 2000; // 单文件最大返回字符，超过截断

    @Tool(description = "读取指定路径的完整文件内容，仅完整浏览文件结构时使用；仅查看局部代码优先使用readFileByRange减少token消耗，支持传入Java类名自动匹配路径")
    public String readFile(
            @ToolParam(description = "文件相对路径 / Java类名") String pathOrClassName,
            ToolContext toolContext
    ) {
        if (pathOrClassName == null || pathOrClassName.isBlank()) {
            return errorResult(ToolErrType.PARAM_EMPTY, "pathOrClassName 不能为空");
        }
        String workspaceId = (String) toolContext.getContext().get("workspaceId");
        String workspaceRoot = (String) toolContext.getContext().get("workspaceRoot");
        Map<String, String> classMap = projectIndexScanner.getClassMap(workspaceId);
        // 类名自动翻译路径
        String realPath = classMap.getOrDefault(pathOrClassName, pathOrClassName);

        Path file = resolveSafePath(realPath, toolContext);
        if (file == null) {
            return errorResult(ToolErrType.PATH_INVALID, "路径解析校验失败，超出工作区范围或路径格式非法：" + realPath);
        }
        if (!Files.isRegularFile(file)) {
            return errorResult(ToolErrType.PATH_INVALID, "目标不是文件：" + realPath);
        }

        try {
            String rawContent = Files.readString(file);
            // 文本压缩：清理连续空行、首尾空白
            String content = rawContent
                    .replaceAll("\\r\\n", "\n")
                    .replaceAll("\\n{3,}", "\n\n")
                    .strip();

            // 超长截断，杜绝万级Token
            if (content.length() > MAX_FILE_CHAR) {
                content = content.substring(0, MAX_FILE_CHAR)
                        + "\n【文件内容过长已截断，缩小读取范围改用readFileByRange指定行读取】";
            }

            // 统计埋点
            statService.recordFileRead(workspaceId);
            String language = guessLanguage(realPath);

            return String.format(
                    "成功读取文件 `%s`。仅完整浏览文件结构使用，查看局部代码请切换readFileByRange；请原样展示代码，不要删减内容：\n\n```%s\n%s\n```",
                    realPath,
                    language,
                    content
            );
        } catch (IOException e) {
            log.error("readFile读取失败 path={}", realPath, e);
            return errorResult(ToolErrType.IO_PERMISSION_ERROR, "文件读取IO异常：" + e.getMessage());
        }
    }

    /**
     * 辅助方法：根据文件后缀猜测编程语言，提供给 Markdown 代码块使用
     */
    private String guessLanguage(String path) {
        if (path == null) return "";
        String lowerPath = path.toLowerCase();
        if (lowerPath.endsWith(".java")) return "java";
        if (lowerPath.endsWith(".py")) return "python";
        if (lowerPath.endsWith(".js")) return "javascript";
        if (lowerPath.endsWith(".ts")) return "typescript";
        if (lowerPath.endsWith(".html")) return "html";
        if (lowerPath.endsWith(".css")) return "css";
        if (lowerPath.endsWith(".json")) return "json";
        if (lowerPath.endsWith(".xml")) return "xml";
        if (lowerPath.endsWith(".yml") || lowerPath.endsWith(".yaml")) return "yaml";
        if (lowerPath.endsWith(".md")) return "markdown";
        if (lowerPath.endsWith(".sh")) return "bash";
        if (lowerPath.endsWith(".sql")) return "sql";
        if (lowerPath.endsWith(".go")) return "go";
        if (lowerPath.endsWith(".rs")) return "rust";
        if (lowerPath.endsWith(".cpp") || lowerPath.endsWith(".c")) return "cpp";
        return ""; // 默认无语言
    }


    // -------------------- 写文件（覆盖） --------------------
    @Tool(description = "将内容写入指定文件（覆盖模式），路径相对于工作区根目录，会自动创建父目录")
    public String writeFile(
            @ToolParam(description = "相对于工作区的文件路径") String path,
            @ToolParam(description = "要写入的内容") String content,
            ToolContext toolContext) {
        if (path == null || path.isBlank()) {
            return errorResult(ToolErrType.PARAM_EMPTY, "path 路径参数不能为空");
        }
        log.info("writeFile 被调用: path={}, workspaceRoot={}",
                path, getWorkspaceRoot(toolContext));
        Path file = resolveSafePath(path, toolContext);
        if (file == null) return errorResult(ToolErrType.PATH_INVALID, "路径解析校验失败，超出工作区范围或路径格式非法");
        try {
            Files.createDirectories(file.getParent());
            Files.writeString(file, content);
            return successResult("文件写入成功: " + file);
        } catch (IOException e) {
            log.error("写入文件失败: {}", file, e);
            return errorResult(ToolErrType.IO_PERMISSION_ERROR, "文件写入IO异常：" + e.getMessage());
        }
    }

    // -------------------- 追加内容到文件 --------------------
    @Tool(description = "向文件末尾追加内容，路径相对于工作区根目录，若文件不存在则创建")
    public String appendToFile(
            @ToolParam(description = "相对于工作区的文件路径") String path,
            @ToolParam(description = "要追加的内容") String content,
            ToolContext toolContext) {
        if (path == null || path.isBlank()) {
            return errorResult(ToolErrType.PARAM_EMPTY, "path 路径参数不能为空");
        }
        Path file = resolveSafePath(path, toolContext);
        if (file == null) return errorResult(ToolErrType.PATH_INVALID, "路径解析校验失败，超出工作区范围或路径格式非法");
        try {
            Files.createDirectories(file.getParent());
            Files.writeString(file, content, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
            return successResult("内容追加成功: " + file);
        } catch (IOException e) {
            log.error("追加文件失败: {}", file, e);
            return errorResult(ToolErrType.IO_PERMISSION_ERROR, "文件追加IO异常：" + e.getMessage());
        }
    }

    // -------------------- 列出目录内容 --------------------
    @Tool(description = "列出指定目录下的文件和子目录，路径相对于工作区根目录")
    public String listDirectory(
            @ToolParam(description = "相对于工作区的目录路径") String path,
            ToolContext toolContext) {
        if (path == null || path.isBlank()) {
            return errorResult(ToolErrType.PARAM_EMPTY, "path 路径参数不能为空");
        }
        Path dir = resolveSafePath(path, toolContext);
        if (dir == null) return errorResult(ToolErrType.PATH_INVALID, "路径解析校验失败，超出工作区范围或路径格式非法");
        if (!Files.isDirectory(dir)) {
            return errorResult(ToolErrType.NOT_DIRECTORY, "传入路径是文件，不是目录：" + path);
        }
        try (Stream<Path> stream = Files.list(dir)) {
            List<String> items = stream
                    .map(p -> (Files.isDirectory(p) ? "[DIR]  " : "[FILE] ") + p.getFileName())
                    .sorted()
                    .collect(Collectors.toList());
            if (items.isEmpty()) {
                return successResult("目录为空: " + dir);
            }
            return successResult("目录内容 (" + items.size() + " 项):\n" + String.join("\n", items));
        } catch (IOException e) {
            log.error("列出目录失败: {}", dir, e);
            return errorResult(ToolErrType.IO_UNKNOWN, "读取目录失败：" + e.getMessage());
        }
    }

    // -------------------- 递归列出目录树 --------------------
    @Tool(description = "递归列出目录下所有文件（树形结构），路径相对于工作区根目录，最多显示3层")
    public String treeDirectory(
            @ToolParam(description = "相对于工作区的目录路径") String path,
            ToolContext toolContext) {
        if (path == null || path.isBlank()) {
            return errorResult(ToolErrType.PARAM_EMPTY, "path 路径参数不能为空");
        }
        Path dir = resolveSafePath(path, toolContext);
        if (dir == null) return errorResult(ToolErrType.PATH_INVALID, "路径解析校验失败，超出工作区范围或路径格式非法");
        if (!Files.isDirectory(dir)) {
            return errorResult(ToolErrType.NOT_DIRECTORY, "传入路径是文件，不是目录：" + path);
        }
        try {
            StringBuilder tree = new StringBuilder();
            tree.append(dir.getFileName()).append("\n");
            try (Stream<Path> stream = Files.walk(dir, 3)) {
                stream.filter(p -> !p.equals(dir))
                        .sorted()
                        .forEach(p -> {
                            int depth = dir.relativize(p).getNameCount();
                            String indent = "  ".repeat(depth);
                            String prefix = depth > 0 ? "|-- " : "";
                            tree.append(indent).append(prefix).append(p.getFileName()).append("\n");
                        });
            }
            return successResult(tree.toString().trim());
        } catch (IOException e) {
            log.error("生成目录树失败: {}", dir, e);
            return errorResult(ToolErrType.IO_UNKNOWN, "遍历目录树失败：" + e.getMessage());
        }
    }

    // -------------------- 创建目录 --------------------
    @Tool(description = "创建目录（包括必要的父目录），路径相对于工作区根目录")
    public String createDirectory(
            @ToolParam(description = "相对于工作区的目录路径") String path,
            ToolContext toolContext) {
        if (path == null || path.isBlank()) {
            return errorResult(ToolErrType.PARAM_EMPTY, "path 路径参数不能为空");
        }
        Path dir = resolveSafePath(path, toolContext);
        if (dir == null) return errorResult(ToolErrType.PATH_INVALID, "路径解析校验失败，超出工作区范围或路径格式非法");
        try {
            Files.createDirectories(dir);
            return successResult("目录创建成功: " + dir);
        } catch (IOException e) {
            log.error("创建目录失败: {}", dir, e);
            return errorResult(ToolErrType.IO_PERMISSION_ERROR, "创建目录IO异常：" + e.getMessage());
        }
    }

    // -------------------- 删除文件/空目录 --------------------
    @Tool(description = "删除指定文件或空目录，路径相对于工作区根目录")
    public String deleteFile(
            @ToolParam(description = "相对于工作区的文件或空目录路径") String path,
            ToolContext toolContext) {
        if (path == null || path.isBlank()) {
            return errorResult(ToolErrType.PARAM_EMPTY, "path 路径参数不能为空");
        }
        Path target = resolveSafePath(path, toolContext);
        if (target == null) return errorResult(ToolErrType.PATH_INVALID, "路径解析校验失败，超出工作区范围或路径格式非法");
        try {
            Files.delete(target);
            return successResult("删除成功: " + target);
        } catch (NoSuchFileException e) {
            return errorResult(ToolErrType.FILE_NOT_EXIST, "目标文件不存在，路径：" + path);
        } catch (DirectoryNotEmptyException e) {
            return errorResult(ToolErrType.DIR_NOT_EMPTY, "目标目录存在文件，禁止普通删除");
        } catch (IOException e) {
            log.error("删除失败: {}", target, e);
            return errorResult(ToolErrType.IO_UNKNOWN, "删除失败: " + e.getMessage());
        }
    }

    // -------------------- 强制删除（递归） --------------------
    @Tool(description = "强制删除指定文件或目录（包括其所有内容），路径相对于工作区根目录")
    public String deleteForce(
            @ToolParam(description = "相对于工作区的路径") String path,
            ToolContext toolContext) {
        if (path == null || path.isBlank()) {
            return errorResult(ToolErrType.PARAM_EMPTY, "path 路径参数不能为空");
        }
        Path target = resolveSafePath(path, toolContext);
        if (target == null) return errorResult(ToolErrType.PATH_INVALID, "路径解析校验失败，超出工作区范围或路径格式非法");
        try {
            if (Files.isDirectory(target)) {
                try (Stream<Path> stream = Files.walk(target)) {
                    stream.sorted(Comparator.reverseOrder())
                            .forEach(p -> {
                                try {
                                    Files.delete(p);
                                } catch (IOException e) {
                                    throw new UncheckedIOException(e);
                                }
                            });
                }
            } else {
                Files.delete(target);
            }
            return successResult("强制删除成功: " + target);
        } catch (IOException e) {
            log.error("强制删除失败: {}", target, e);
            return errorResult(ToolErrType.IO_UNKNOWN, "递归删除文件异常：" + e.getMessage());
        }
    }

    // -------------------- 移动/重命名 --------------------
    @Tool(description = "移动或重命名文件/目录，源路径和目标路径均相对于工作区根目录")
    public String moveFile(
            @ToolParam(description = "源路径") String source,
            @ToolParam(description = "目标路径") String target,
            ToolContext toolContext) {
        if (source == null || source.isBlank()) {
            return errorResult(ToolErrType.PARAM_EMPTY, "source 路径参数不能为空");
        }
        if (target == null || target.isBlank()) {
            return errorResult(ToolErrType.PARAM_EMPTY, "target 路径参数不能为空");
        }
        Path src = resolveSafePath(source, toolContext);
        Path dst = resolveSafePath(target, toolContext);
        if (src == null || dst == null) return errorResult(ToolErrType.PATH_INVALID, "路径解析校验失败，超出工作区范围或路径格式非法");
        try {
            Files.createDirectories(dst.getParent());
            Files.move(src, dst, StandardCopyOption.REPLACE_EXISTING);
            return successResult("移动成功: " + src + " -> " + dst);
        } catch (IOException e) {
            log.error("移动失败: {} -> {}", src, dst, e);
            return errorResult(ToolErrType.IO_UNKNOWN, "文件移动失败：" + e.getMessage());
        }
    }

    // -------------------- 复制文件 --------------------
    @Tool(description = "复制文件或目录（递归），源路径和目标路径均相对于工作区根目录")
    public String copyFile(
            @ToolParam(description = "源路径") String source,
            @ToolParam(description = "目标路径") String target,
            ToolContext toolContext) {
        if (source == null || source.isBlank()) {
            return errorResult(ToolErrType.PARAM_EMPTY, "source 路径参数不能为空");
        }
        if (target == null || target.isBlank()) {
            return errorResult(ToolErrType.PARAM_EMPTY, "target 路径参数不能为空");
        }
        Path src = resolveSafePath(source, toolContext);
        Path dst = resolveSafePath(target, toolContext);
        if (src == null || dst == null) return errorResult(ToolErrType.PATH_INVALID, "路径解析校验失败，超出工作区范围或路径格式非法");
        try {
            if (Files.isDirectory(src)) {
                try (Stream<Path> stream = Files.walk(src)) {
                    stream.forEach(s -> {
                        try {
                            Path d = dst.resolve(src.relativize(s));
                            if (Files.isDirectory(s)) {
                                Files.createDirectories(d);
                            } else {
                                Files.createDirectories(d.getParent());
                                Files.copy(s, d, StandardCopyOption.REPLACE_EXISTING);
                            }
                        } catch (IOException e) {
                            throw new UncheckedIOException(e);
                        }
                    });
                }
            } else {
                Files.createDirectories(dst.getParent());
                Files.copy(src, dst, StandardCopyOption.REPLACE_EXISTING);
            }
            return successResult("复制成功: " + src + " -> " + dst);
        } catch (IOException e) {
            log.error("复制失败: {} -> {}", src, dst, e);
            return errorResult(ToolErrType.IO_UNKNOWN, "文件复制失败：" + e.getMessage());
        }
    }

    // -------------------- 搜索文件内容 --------------------
    @Tool(description = "在工作区内搜索文件内容，支持简单字符串匹配，返回匹配的文件及行号")
    public String searchInFiles(
            @ToolParam(description = "要搜索的字符串") String query,
            @ToolParam(description = "搜索的文件扩展名，如 .java,.txt，留空表示所有文件") String extensions,
            ToolContext toolContext) {
        String workspaceRoot = getWorkspaceRoot(toolContext);
        if (workspaceRoot == null) return errorResult(ToolErrType.CONTEXT_MISS, "无法获取当前会话工作区根目录");
        Path root = Paths.get(workspaceRoot).normalize();
        if (!Files.isDirectory(root)) return errorResult(ToolErrType.CONTEXT_MISS, "工作区目录不存在或已失效");

        try (Stream<Path> walker = Files.walk(root)) {
            List<String> results = walker
                    .filter(Files::isRegularFile)
                    .filter(p -> {
                        if (extensions == null || extensions.isBlank()) return true;
                        String fileName = p.getFileName().toString().toLowerCase();
                        for (String ext : extensions.split(",")) {
                            if (fileName.endsWith(ext.trim().toLowerCase())) return true;
                        }
                        return false;
                    })
                    .flatMap(p -> {
                        try {
                            return Files.readAllLines(p).stream()
                                    .map(line -> line + "  // " + root.relativize(p) + ":" + line);
                        } catch (IOException e) {
                            return Stream.empty();
                        }
                    })
                    .filter(line -> line.contains(query))
                    .collect(Collectors.toList());

            if (results.isEmpty()) {
                return successResult("未找到匹配项: " + query);
            }
            return successResult("找到 " + results.size() + " 处匹配:\n" + String.join("\n", results));
        } catch (IOException e) {
            log.error("搜索文件内容失败", e);
            return errorResult(ToolErrType.IO_UNKNOWN, "全局文件检索IO异常：" + e.getMessage());
        }
    }

    // -------------------- 获取文件信息 --------------------
    @Tool(description = "获取文件或目录的详细信息（大小、修改时间等），路径相对于工作区根目录")
    public String fileInfo(
            @ToolParam(description = "相对于工作区的路径") String path,
            ToolContext toolContext) {
        if (path == null || path.isBlank()) {
            return errorResult(ToolErrType.PARAM_EMPTY, "path 路径参数不能为空");
        }
        Path target = resolveSafePath(path, toolContext);
        if (target == null) return errorResult(ToolErrType.PATH_INVALID, "路径解析校验失败，超出工作区范围或路径格式非法");
        try {
            BasicFileAttributes attr = Files.readAttributes(target, BasicFileAttributes.class);
            String type = attr.isDirectory() ? "目录" : "文件";
            String size = attr.isDirectory() ? "-" : String.format("%.2f KB", attr.size() / 1024.0);
            return successResult(String.format(
                    "路径: %s\n类型: %s\n大小: %s\n创建时间: %s\n最后修改: %s\n最后访问: %s",
                    target,
                    type,
                    size,
                    attr.creationTime(),
                    attr.lastModifiedTime(),
                    attr.lastAccessTime()
            ));
        } catch (IOException e) {
            log.error("获取文件信息失败: {}", target, e);
            return errorResult(ToolErrType.IO_UNKNOWN, "读取文件属性失败：" + e.getMessage());
        }
    }

    /**
     * 按指定行区间读取文件片段，大幅减少token消耗
     * 支持传入Java类名自动匹配文件路径
     * @param pathOrClassName 文件相对路径 / Java类名
     * @param startLine 起始行，最小1
     * @param endLine 结束行，不能小于startLine
     * @return 片段内容或标准错误提示
     */
    @Tool(description = """
        按行区间读取文件片段，优先使用该工具代替readFile读取完整文件，极大降低token消耗。
        支持传入Java类名，系统会自动匹配对应文件路径。
        适用场景：只需要查看某个方法、局部业务逻辑，不需要完整文件。
        参数说明：
        pathOrClassName：文件相对路径或Java类名
        startLine：起始行号，从1开始
        endLine：结束行号，必须大于等于startLine
        """)
    public String readFileByRange(
            @ToolParam(description = "文件路径或Java类名") String pathOrClassName,
            @ToolParam(description = "起始行号，最小1") int startLine,
            @ToolParam(description = "结束行号") int endLine,
            ToolContext toolContext
    ) {
        // 1. 参数非空校验
        if (pathOrClassName == null || pathOrClassName.isBlank()) {
            return errorResult(ToolErrType.PARAM_EMPTY, "pathOrClassName 路径/类名参数不能为空");
        }
        String workspaceRoot = (String) toolContext.getContext().get("workspaceRoot");
        String workspaceId = (String) toolContext.getContext().get("workspaceId");

        try {
            // 2. 类名自动映射真实相对路径
            Map<String, String> classMap = projectIndexScanner.getClassMap(workspaceId);
            String realPath = classMap.getOrDefault(pathOrClassName, pathOrClassName);

            // 3. 安全路径校验（拦截../越界、非法路径）
            Path fullPath = resolveSafePath(realPath, toolContext);
            if (fullPath == null) {
                return errorResult(ToolErrType.PATH_INVALID, "路径非法或超出工作区范围：" + realPath);
            }
            if (!Files.isRegularFile(fullPath)) {
                return errorResult(ToolErrType.PATH_INVALID, "目标不存在或不是普通文件：" + realPath);
            }

            // 4. 行号边界修正校验
            int safeStart = Math.max(1, startLine);
            List<String> allLines = Files.readAllLines(fullPath);
            int totalLines = allLines.size();
            int safeEnd = Math.min(endLine, totalLines);
            if (safeEnd < safeStart) {
                return errorResult(ToolErrType.PARAM_EMPTY, "结束行号不能小于起始行号");
            }

            // 5. 截取目标行区间
            List<String> slice = allLines.subList(safeStart - 1, safeEnd);
            String raw = String.join("\n", slice);

            // 6. 文本压缩清理多余空行
            String content = raw.replaceAll("\\r\\n", "\n")
                    .replaceAll("\\n{3,}", "\n\n")
                    .strip();

            // 7. 超长字符截断兜底
            final int MAX_CHAR = 2000;
            if (content.length() > MAX_CHAR) {
                content = content.substring(0, MAX_CHAR) + "\n【片段过长已截断，请缩小读取行范围】";
            }

            // 读取埋点统计
            statService.recordFileRead(workspaceId);
            // 识别代码语言，和readFile统一返回代码块格式
            String lang = guessLanguage(realPath);

            return String.format(
                    "【文件：%s 读取区间：%d ~ %d / 文件总行：%d】\n仅查看局部代码优先使用readFileByRange，不要读取完整文件\n\n```%s\n%s\n```",
                    realPath, safeStart, safeEnd, totalLines, lang, content
            );
        } catch (Exception e) {
            log.error("readFileByRange读取失败 path={}", pathOrClassName, e);
            return errorResult(ToolErrType.IO_PERMISSION_ERROR, "按行读取文件片段异常：" + e.getMessage());
        }
    }

    /**
     * 批量生成多文件结构化差异，输出前端可识别DIFF标记
     * AI修改文件标准收尾工具，修改完成后必须调用本工具生成代码对比面板
     * 支持单文件/多文件统一处理，自动读取磁盘原始文件与传入新版代码做行级对比
     */
    @Tool(description = """
        批量生成文件代码差异，修改文件后必须调用该工具生成对比面板。
        支持同时传入多个文件，自动读取工作区内原始文件，与传入的完整新版代码做行级Diff。
        自动过滤无变更文件，返回带前端识别标记的字符串，对话流会自动渲染左右分栏代码对比。
        适用场景：完成代码修改、新增/删除/调整代码后，生成可视化差异供用户勾选应用。
        参数说明：
        fileCodeMap：键=文件路径/Java类名，值=修改完成后的完整文件全部代码，不可只传片段
        """)
    public String batchGenerateDiff(
            @ToolParam(description = "文件映射：key为文件路径或Java类名，value为该文件完整新版全部代码")
            Map<String, String> fileCodeMap,
            ToolContext toolContext
    ) {
        // 1. 顶层参数校验
        if (fileCodeMap == null || fileCodeMap.isEmpty()) {
            return errorResult(ToolErrType.PARAM_EMPTY, "fileCodeMap不能为空，至少传入一个待修改文件");
        }

        String workspaceRoot = (String) toolContext.getContext().get("workspaceRoot");
        String workspaceId = (String) toolContext.getContext().get("workspaceId");
        Map<String, String> classPathMap = projectIndexScanner.getClassMap(workspaceId);

        MultiFileDiffResult multiFileDiffResult = new MultiFileDiffResult();
        List<FileDiffResult> fileDiffResultList = new ArrayList<>();

        try {
            // 2. 循环处理每一个待对比文件
            for (Map.Entry<String, String> entry : fileCodeMap.entrySet()) {
                String pathOrClassName = entry.getKey();
                String newFullCode = entry.getValue();

                // 子参数校验：新版代码不能为空
                if (newFullCode == null || newFullCode.isBlank()) {
                    return errorResult(ToolErrType.PARAM_EMPTY, "文件[" + pathOrClassName + "]新版代码不能为空，必须传入完整文件代码");
                }

                // 类名自动映射真实文件路径（和readFileByRange逻辑统一）
                String realRelativePath = classPathMap.getOrDefault(pathOrClassName, pathOrClassName);

                // 安全路径校验，拦截越界、非法路径
                Path fullFileAbsPath = resolveSafePath(realRelativePath, toolContext);
                if (fullFileAbsPath == null) {
                    return errorResult(ToolErrType.PATH_INVALID, "文件路径非法或超出工作区范围：" + realRelativePath);
                }
                // 路径存在，但不是普通文件（文件夹/软链接）才报错；文件不存在放行做新增
                if (Files.exists(fullFileAbsPath) && !Files.isRegularFile(fullFileAbsPath)) {
                    return errorResult(ToolErrType.PATH_INVALID, "目标路径不是普通文件：" + realRelativePath);
                }

                List<String> oldLines;
                if (Files.exists(fullFileAbsPath)) {
                    oldLines = Files.readAllLines(fullFileAbsPath);
                } else {
                    oldLines = new ArrayList<>();
                }
                String oldOriginCode = String.join("\n", oldLines);

                // 3. 调用Diff工具生成单文件结构化差异
                FileDiffResult singleFileDiff = diffUtil.generateSingleFileDiff(realRelativePath, oldOriginCode, newFullCode);
                // 过滤无任何变更的文件，不加入渲染列表
                if (singleFileDiff.getHasChange()) {
                    fileDiffResultList.add(singleFileDiff);
                }
            }

            // 4. 组装顶层统一多文件容器
            multiFileDiffResult.setFileDiffList(fileDiffResultList);
            multiFileDiffResult.setHasAnyChange(!fileDiffResultList.isEmpty());

            // 5. 序列化JSON，包裹前端唯一识别标记 <!--DIFF_ALL:xxx-->
            String diffJsonStr = objectMapper.writeValueAsString(multiFileDiffResult);
            String diffTag = "<!--DIFF_ALL:" + diffJsonStr + "-->";

            // 读取统计埋点（和readFileByRange保持统一埋点逻辑）
            statService.recordFileRead(workspaceId);

            // 标准返回格式，附带说明+diff标记
            if (!multiFileDiffResult.getHasAnyChange()) {
                return "所有传入文件对比后无代码变更，无需展示差异面板\n" + diffTag;
            }
            return String.format(
                    "已生成%d个文件代码差异，前端将自动展示左右分栏对比面板，可勾选变更后应用写入文件\n%s",
                    fileDiffResultList.size(),
                    diffTag
            );

        } catch (JsonProcessingException e) {
            log.error("batchGenerateDiff JSON序列化失败 workspaceId={}", workspaceId, e);
            return errorResult(ToolErrType.SERIALIZE_ERROR, "差异数据序列化失败：" + e.getMessage());
        } catch (SecurityException e) {
            log.error("batchGenerateDiff 文件权限异常 workspaceId={}", workspaceId, e);
            return errorResult(ToolErrType.IO_PERMISSION_ERROR, "文件读写权限不足：" + e.getMessage());
        } catch (Exception e) {
            log.error("batchGenerateDiff 生成差异异常 workspaceId={}", workspaceId, e);
            return errorResult(ToolErrType.IO_UNKNOWN, "生成文件差异失败：" + e.getMessage());
        }
    }

    // ========== 内部辅助方法 ==========

    /**
     * 从 ToolContext 获取完整工作区根目录 = workspaceRoot + workspaceId
     */
    private String getWorkspaceRoot(ToolContext toolContext) {
        if (toolContext == null || toolContext.getContext() == null) return null;
        String workspaceRoot = (String) toolContext.getContext().get("workspaceRoot");
        String workspaceId = (String) toolContext.getContext().get("workspaceId");

        if (workspaceRoot == null) return null;

        Path root = Paths.get(workspaceRoot);
        if (workspaceId != null && !workspaceId.isBlank()) {
            root = root.resolve(workspaceId);
        }
        return root.normalize().toAbsolutePath().toString();
    }

    /**
     * 将用户输入的路径解析为绝对路径，并校验必须在 workspaceRoot 内
     */
    private Path resolveSafePath(String userPath, ToolContext toolContext) {
        String root = getWorkspaceRoot(toolContext);
        if (root == null) {
            log.warn("ToolContext 中未找到 workspaceRoot");
            return null;
        }
        try {
            Path rootPath = Paths.get(root).normalize().toAbsolutePath();
            Path resolved = rootPath.resolve(userPath).normalize().toAbsolutePath();
            if (!resolved.startsWith(rootPath)) {
                log.warn("拒绝访问工作区外路径: {}", resolved);
                return null;
            }
            return resolved;
        } catch (InvalidPathException e) {
            log.warn("无效路径: {}", userPath, e);
            return null;
        }
    }

    public ToolCallback[] getToolCallbacks() {
        return MethodToolCallbackProvider.builder()
                .toolObjects(this)
                .build()
                .getToolCallbacks();
    }

    private String successResult(String msg) {
        return "[SUCCESS] " + msg;
    }

    /**
     * 标准错误返回结构，模型可解析识别并自动修正参数
     */
    private String errorResult(ToolErrType errType, String detailMsg) {
        return String.format("""
            [TOOL_ERROR]
            错误码：%s
            错误分类：%s
            详细原因：%s
            修复指引：%s
            """, errType.code, errType.desc, detailMsg, errType.fixHint);
    }


}