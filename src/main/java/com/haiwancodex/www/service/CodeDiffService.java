package com.haiwancodex.www.service;

import com.haiwancodex.www.dto.CodeDiffItem;
import com.github.difflib.DiffUtils;
import com.github.difflib.patch.AbstractDelta;
import com.github.difflib.patch.DeltaType;
import com.github.difflib.patch.Patch;
import com.haiwancodex.www.dto.FileDiffResult;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

@Slf4j
@Service
public class CodeDiffService {

    /**
     * 对比本地磁盘文件 和 AI生成新代码，生成结构化差异
     */
    public FileDiffResult generateDiff(Path fileRealPath, String newCode) throws Exception {
        List<String> oldLines = Files.readAllLines(fileRealPath);
        String originalText = String.join("\n", oldLines);
        List<String> newLines = newCode.lines().toList();

        Patch<String> patch = DiffUtils.diff(oldLines, newLines);
        List<CodeDiffItem> diffItems = new ArrayList<>();

        for (AbstractDelta<String> delta : patch.getDeltas()) {
            DeltaType deltaType = delta.getType();
            int startLine = delta.getSource().getPosition() + 1;
            int endLine = delta.getSource().getPosition() + delta.getSource().size();

            String oldBlock = String.join("\n", delta.getSource().getLines());
            String newBlock = String.join("\n", delta.getTarget().getLines());

            String typeStr = switch (deltaType) {
                case INSERT -> "INSERT";
                case DELETE -> "DELETE";
                case CHANGE -> "MODIFY";
                default -> "NONE";
            };
            // 默认全部勾选
            diffItems.add(new CodeDiffItem(startLine, endLine, oldBlock, newBlock, typeStr, true));
        }

        return new FileDiffResult(
                fileRealPath.toString(),
                originalText,
                diffItems,
                !diffItems.isEmpty()
        );
    }

    /**
     * 根据用户勾选的变更，合并生成最终文件文本
     * 逆序修改，防止行号偏移错乱
     */
    public String applySelectedPatch(String originalText, List<CodeDiffItem> allDiffItems) {
        List<String> baseLines = originalText.lines().toList();
        List<String> resultLines = new ArrayList<>(baseLines);

        List<CodeDiffItem> selectedDiffs = allDiffItems.stream()
                .filter(CodeDiffItem::getSelected)
                .sorted((a, b) -> Integer.compare(b.getStartLine(), a.getStartLine()))
                .toList();

        for (CodeDiffItem diff : selectedDiffs) {
            int startIdx = diff.getStartLine() - 1;
            int endIdx = diff.getEndLine();
            List<String> replaceLines = diff.getNewContent().lines().toList();
            resultLines.subList(startIdx, endIdx).clear();
            resultLines.addAll(startIdx, replaceLines);
        }
        return String.join("\n", resultLines);
    }
}