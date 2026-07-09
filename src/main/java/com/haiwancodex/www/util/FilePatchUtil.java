package com.haiwancodex.www.util;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.haiwancodex.www.common.ToolErrType;
import com.haiwancodex.www.dto.BatchPatchDTO;
import com.haiwancodex.www.dto.CodeDiffItem;
import com.haiwancodex.www.dto.SinglePatchItem;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.stereotype.Component;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

@Slf4j
@Component
@RequiredArgsConstructor
public class FilePatchUtil {
    private final ObjectMapper objectMapper;
    private final ProjectIndexScanner projectIndexScanner;

    /**
     * 将用户输入的路径解析为绝对路径，并校验必须在 workspaceRoot 内
     */
    private Path resolveSafePath(String userPath, String workspaceRoot, String workspaceId) {
        String root = getWorkspaceRoot(workspaceRoot, workspaceId);
        if (root == null) {
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
    /**
     * 从 ToolContext 获取完整工作区根目录 = workspaceRoot + workspaceId
     */
    private String getWorkspaceRoot(String workspaceRoot, String workspaceId) {
        if (workspaceRoot == null) return null;
        Path root = Paths.get(workspaceRoot);
        if (workspaceId != null && !workspaceId.isBlank()) {
            root = root.resolve(workspaceId);
        }
        return root.normalize().toAbsolutePath().toString();
    }

    /**
     * 单文件局部应用变更
     */
    public void applySingleFilePatch(String workspaceRoot, String workspaceId, String filePath, List<CodeDiffItem> diffItems) throws Exception {
        Path fullFileAbsPath = resolveSafePath(filePath, workspaceRoot, workspaceId);
        if (fullFileAbsPath == null) {
            throw new RuntimeException("文件路径非法或超出工作区范围：" + filePath);
        }
        // 路径存在，但不是普通文件（文件夹/软链接）才报错；文件不存在放行做新增
        if (Files.exists(fullFileAbsPath) && !Files.isRegularFile(fullFileAbsPath)) {
            throw new RuntimeException("目标路径不是普通文件：" + filePath);
        }

        // 普通局部修改逻辑
        List<String> originLines;
        if (Files.exists(fullFileAbsPath)) {
            originLines = Files.readAllLines(fullFileAbsPath);
        } else {
            Files.createFile(fullFileAbsPath);
            originLines = new ArrayList<>();
        }

        // 判断是否为整文件删除场景：仅一条diff且类型是DELETE
        CodeDiffItem codeDiffItem = diffItems.get(0);
        // 简单判定：起始行1、结束行超大值代表清空整个文件
        boolean isDeleteWholeFile = "DELETE".equals(codeDiffItem.getType())
                && codeDiffItem.getStartLine() == 1
                && codeDiffItem.getEndLine() >= originLines.size();
        if (diffItems.size() == 1 && isDeleteWholeFile) {
            Files.delete(fullFileAbsPath);
            return;
        }

        List<String> modLines = new ArrayList<>(originLines);

        List<CodeDiffItem> sorted = diffItems.stream()
                .filter(CodeDiffItem::getSelected)
                .sorted(Comparator.comparingInt(CodeDiffItem::getStartLine).reversed())
                .toList();

        for (CodeDiffItem item : sorted) {
            int startIdx = item.getStartLine() - 1;
            int endIdx = item.getEndLine();
            List<String> newSlice = item.getNewContent().lines().toList();

            switch (item.getType()) {
                case "MODIFY":
                    if (startIdx < endIdx) {
                        modLines.subList(startIdx, endIdx).clear();
                    }
                    modLines.addAll(startIdx, newSlice);
                    break;
                case "DELETE":
                    // 仅删除局部行，不是删整个文件
                    if (startIdx < endIdx) {
                        modLines.subList(startIdx, endIdx).clear();
                    }
                    break;
                case "INSERT":
                    modLines.addAll(startIdx, newSlice);
                    break;
            }
        }

        String finalText = String.join("\n", modLines);
        Files.writeString(fullFileAbsPath, finalText);
    }

    /**
     * 批量处理多文件
     */
    public void batchApply(BatchPatchDTO dto, String workspaceId, String workspaceRoot) throws Exception {
        Map<String, String> classMap = projectIndexScanner.getClassMap(workspaceId);
        for (SinglePatchItem item : dto.getBatchDiffList()) {
            // 类名转路径
            String realPath = classMap.getOrDefault(item.getPathOrClassName(), item.getPathOrClassName());
            List<CodeDiffItem> diffList = objectMapper.readValue(
                    item.getDiffJson(),
                    new TypeReference<>() {}
            );
            applySingleFilePatch(workspaceRoot, workspaceId, realPath, diffList);
        }
    }
}