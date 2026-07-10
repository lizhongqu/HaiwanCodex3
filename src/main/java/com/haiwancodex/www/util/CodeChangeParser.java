package com.haiwancodex.www.util;

import cn.hutool.core.util.StrUtil;
import com.haiwancodex.www.entity.CodeChangeFile;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class CodeChangeParser {

    /**
     * 匹配文件变更块：<!--FILE_START|文件路径--> 代码内容 <!--FILE_END-->
     */
    private static final Pattern FILE_BLOCK_PATTERN = Pattern.compile(
            "<!--FILE_START\\|(.+?)-->([\\s\\S]*?)<!--FILE_END-->",
            Pattern.MULTILINE
    );

    /** 匹配删除文件标记：<!--FILE_DELETE|文件路径--> */
    private static final Pattern FILE_DELETE_PATTERN = Pattern.compile(
            "<!--FILE_DELETE\\|(.+?)-->",
            Pattern.MULTILINE
    );

    /**
     * 从AI完整回复中解析所有变更文件
     * @param aiFullContent AI完整返回文本
     * @return 变更文件列表
     */
    public static List<CodeChangeFile> parseFileChanges(String aiFullContent) {
        List<CodeChangeFile> fileList = new ArrayList<>();
        if (StrUtil.isBlank(aiFullContent)) {
            return fileList;
        }

        // 1. 解析新增/修改文件（原生Matcher提取双分组）
        Matcher blockMatcher = FILE_BLOCK_PATTERN.matcher(aiFullContent);
        while (blockMatcher.find()) {
            String filePath = blockMatcher.group(1).trim();
            String newCode = blockMatcher.group(2).trim();
            if (StrUtil.isBlank(filePath)) continue;

            CodeChangeFile file = new CodeChangeFile();
            file.setFilePath(filePath);
            file.setNewCode(newCode);
            file.setIsDeleteFile(0);
            fileList.add(file);
        }

        // 2. 解析删除文件（单分组，Hutool原写法保留，兼容正常）
        Matcher deleteMatcher = FILE_DELETE_PATTERN.matcher(aiFullContent);
        while (deleteMatcher.find()) {
            String filePath = deleteMatcher.group(1).trim();
            if (StrUtil.isBlank(filePath)) continue;

            CodeChangeFile file = new CodeChangeFile();
            file.setFilePath(filePath);
            file.setNewCode("");
            file.setIsDeleteFile(1);
            fileList.add(file);
        }

        return fileList;
    }

    /**
     * 移除所有HTML注释，返回纯文本聊天内容
     */
    public static String cleanChatContent(String content) {
        if (StrUtil.isBlank(content)) return content;
        // 移除所有<!--xxx-->格式的注释
        return content.replaceAll("<!--[\\s\\S]*?-->", "").trim();
    }
}