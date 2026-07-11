package com.haiwancodex.www.util;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import com.haiwancodex.www.entity.CodeChangeFile;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Slf4j
@Component
public class CodeChangeParser {

    // 解析方法
    public static List<CodeChangeFile> parseAllFileCode(String totalText) {
        List<CodeChangeFile> result = new ArrayList<>();
        Pattern pattern = Pattern.compile("\\[CODE_JSON\\]([\\s\\S]*?)\\[/CODE_JSON\\]");
        Matcher matcher = pattern.matcher(totalText);
        while (matcher.find()) {
            String s = matcher.group(1).trim();
            log.info("原始标记JSON:{}", s);
            // 1. 正确还原双层转义：把 \\ 替换成 \
            String jsonStr = s.replace("\\\\", "\\").replace("\n", "\\n").replace("\r", "\\r");
            // 新增：把裸换行 \n 替换成转义 \\n，修复JSON非法换行
            JSONObject obj = JSON.parseObject(jsonStr);
            String filePath = obj.getString("filePath");
            String rawCode = obj.getString("fullNewCode");
            Integer isDeleteFile = obj.getInteger("isDeleteFile");
            // 只对代码内容转义，千万不要对整个jsonStr执行escapeJsonValue
            String safeCode = escapeJsonValue(rawCode);
            // 转实体
            // <!--FILE_NEW_CODE|{"filePath":"文件相对路径","fullNewCode":"完整代码字符串","wholeDelete":false}-->
            CodeChangeFile codeChangeFile = CodeChangeFile.builder()
                    .filePath(filePath)
                    .newCode(safeCode)
                    .isDeleteFile(isDeleteFile)
                    .build();

            result.add(codeChangeFile);
        }
        return result;
    }

    /**
     * 修复JSON字符串内部换行、引号转义问题
     */
    public static String escapeJsonValue(String raw) {
        if (raw == null) return "";
        StringBuilder sb = new StringBuilder();
        for (char c : raw.toCharArray()) {
            switch (c) {
                case '"': sb.append("\\\""); break;
                case '\\': sb.append("\\\\"); break;
                case '\n': sb.append("\\n"); break;
                case '\r': sb.append("\\r"); break;
                case '\t': sb.append("\\t"); break;
                default: sb.append(c);
            }
        }
        return sb.toString();
    }
}