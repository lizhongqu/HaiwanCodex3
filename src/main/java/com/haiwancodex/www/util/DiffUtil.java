package com.haiwancodex.www.util;

import cn.hutool.core.util.ReUtil;
import com.alibaba.fastjson2.JSON;
import com.github.difflib.DiffUtils;
import com.github.difflib.patch.AbstractDelta;
import com.github.difflib.patch.Chunk;
import com.haiwancodex.www.dto.CodeDiffItem;
import com.haiwancodex.www.dto.FileDiffItem;
import com.haiwancodex.www.dto.FileDiffRaw;
import com.haiwancodex.www.dto.FileDiffResult;
import lombok.Data;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

@Component
public class DiffUtil {

    /**
     * 对比新旧文件文本，生成结构化FileDiffResult
     * @param filePath 文件路径
     * @param oldText 原始文件完整文本
     * @param newText AI生成新版完整文本
     * @return 单文件差异对象
     */
    public static FileDiffResult generateSingleFileDiff(String filePath, String oldText, String newText) {
        FileDiffResult result = new FileDiffResult();
        result.setFilePath(filePath);
        result.setOriginalText(oldText);

        // 按换行分割为行数组
        List<String> oldLines = oldText.lines().toList();
        List<String> newLines = newText.lines().toList();

        // 计算行级补丁
        var patch = DiffUtils.diff(oldLines, newLines);
        List<CodeDiffItem> diffItemList = new ArrayList<>();

        for (AbstractDelta<String> delta : patch.getDeltas()) {
            CodeDiffItem item = new CodeDiffItem();
            Chunk<String> originalChunk = delta.getSource();
            Chunk<String> revisedChunk = delta.getTarget();

            // 行号从1开始展示
            int start = originalChunk.getPosition() + 1;
            int end = originalChunk.getPosition() + originalChunk.size();
            item.setStartLine(start);
            item.setEndLine(end);

            // 拼接多行文本
            String oldContent = String.join("\n", originalChunk.getLines());
            String newContent = String.join("\n", revisedChunk.getLines());
            item.setOldContent(oldContent);
            item.setNewContent(newContent);

            // 判断变更类型
            switch (delta.getType()) {
                case INSERT:
                    item.setType("INSERT");
                    break;
                case DELETE:
                    item.setType("DELETE");
                    break;
                case CHANGE:
                    item.setType("MODIFY");
                    break;
            }
            diffItemList.add(item);
        }

        result.setDiffList(diffItemList);
        result.setHasChange(!diffItemList.isEmpty());
        return result;
    }

    /**
     * 从完整AI文本中提取 [DIFF] ... [/DIFF] 中间的JSON
     */
    public static String extractDiffContent(String fullText) {
        Pattern pattern = Pattern.compile("\\[DIFF\\]([\\s\\S]*?)\\[/DIFF\\]");
        String match = ReUtil.getGroup1(pattern, fullText);
        if (match == null || match.isBlank()) {
            return null;
        }
        return match.trim();
    }

    /**
     * 把diff json字符串 转成文件变更列表
     */
    public static List<FileDiffRaw> parseDiffJson(String diffJson) {
        List<FileDiffRaw> result = new ArrayList<>();
        if (diffJson == null || diffJson.isBlank()) {
            return result;
        }
        // 外层结构 {fileDiffList: [ {pathOrClassName, diffJson} ]}
        DiffRoot root = JSON.parseObject(diffJson, DiffRoot.class);
        if (root == null || root.getFileDiffList() == null || root.getFileDiffList().isEmpty()) {
            return result;
        }
        for (FileDiffItem item : root.getFileDiffList()) {
            FileDiffRaw raw = new FileDiffRaw();
            raw.setPathOrClassName(item.getPathOrClassName());
            // 阶段1：只存最终完整新代码，不计算行diff
            raw.setFullNewCode(item.getFullNewCode());
            raw.setWholeFileDelete(item.isWholeDelete());
            result.add(raw);
        }
        return result;
    }

    // 配套内部DTO
    @Data
    static class DiffRoot {
        private List<FileDiffItem> fileDiffList;
    }
}