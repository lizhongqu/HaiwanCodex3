package com.haiwancodex.www.service;

import com.github.difflib.DiffUtils;
import com.github.difflib.patch.AbstractDelta;
import com.github.difflib.patch.Patch;
import com.haiwancodex.www.entity.CodeChangeBatch;
import com.haiwancodex.www.entity.CodeChangeFile;
import com.haiwancodex.www.mapper.CodeChangeBatchMapper;
import com.haiwancodex.www.tool.WorkspaceFileTools;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Slf4j
@Service
@RequiredArgsConstructor
public class DiffService {
    private final WorkspaceFileTools workspaceFileTools;
    private final CodeChangeBatchMapper codeChangeBatchMapper;

    /**
     * 计算单个文件的差异
     * @param changeFile 数据库中的变更文件记录（含新代码和路径）
     * @return 差异文本（可自定义格式）
     */
    public String computeDiff(CodeChangeFile changeFile) {
        // 1. 读取工作空间中当前文件内容（旧代码）
        CodeChangeBatch codeChangeBatch = codeChangeBatchMapper.selectById(changeFile.getBatchId());
        String oldContent = readFileContent(changeFile.getFilePath(), "D:\\ideaSpace\\" + codeChangeBatch.getWorkspaceId());
        if (oldContent == null) {
            oldContent = ""; // 新文件，旧内容为空
        }
        String newContent = changeFile.getNewCode();
        if (newContent == null) {
            newContent = "";
        }

        // 2. 按行分割
        List<String> oldLines = Arrays.asList(oldContent.split("\n", -1));
        List<String> newLines = Arrays.asList(newContent.split("\n", -1));

        // 3. 计算差异
        Patch<String> patch = DiffUtils.diff(oldLines, newLines);

        // 4. 格式化为易读的文本（可自定义成 JSON 返回给前端，这里先用简单的 unified diff 格式）
        StringBuilder diffResult = new StringBuilder();
        for (AbstractDelta<String> delta : patch.getDeltas()) {
            diffResult.append("@@ -").append(delta.getSource().getPosition() + 1)
                    .append(",").append(delta.getSource().size())
                    .append(" +").append(delta.getTarget().getPosition() + 1)
                    .append(",").append(delta.getTarget().size())
                    .append(" @@\n");
            // 删除的行
            for (String line : delta.getSource().getLines()) {
                diffResult.append("- ").append(line).append("\n");
            }
            // 添加的行
            for (String line : delta.getTarget().getLines()) {
                diffResult.append("+ ").append(line).append("\n");
            }
        }
        return diffResult.toString();
    }
    /**
     * 手写的安全读文件方法（不再依赖 WorkspaceFileTools）
     */
    private String readFileContent(String relativePath, String workspaceRoot) {
        if (relativePath == null || relativePath.isBlank() || workspaceRoot == null) {
            return "";
        }
        try {
            Path root = Paths.get(workspaceRoot).normalize();
            Path filePath = root.resolve(relativePath).normalize();

            // 安全检查：防止 ../../../ 越权
            if (!filePath.startsWith(root)) {
                log.warn("非法路径访问尝试：{}", relativePath);
                return "";
            }
            if (!Files.exists(filePath) || !Files.isRegularFile(filePath)) {
                return ""; // 新文件，不存在则旧内容为空
            }
            return Files.readString(filePath);
        } catch (IOException e) {
            log.error("读取文件失败: {}", relativePath, e);
            return "";
        }
    }
}
