package com.haiwancodex.www.util;

import com.alibaba.fastjson2.JSON;
import com.haiwancodex.www.entity.CodeChangeFile;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class CodeChangeParser {

    // 解析方法
    public static List<CodeChangeFile> parseAllFileCode(String totalText) {
        List<CodeChangeFile> result = new ArrayList<>();
        Pattern pattern = Pattern.compile("<!--FILE_NEW_CODE\\|([\\s\\S]*?)-->");
        Matcher matcher = pattern.matcher(totalText);
        while (matcher.find()) {
            // 拿到中间JSON字符串
            String jsonStr = matcher.group(1);
            // 转实体
            Map map = JSON.parseObject(jsonStr, Map.class);
            // <!--FILE_NEW_CODE|{"filePath":"文件相对路径","fullNewCode":"完整代码字符串","wholeDelete":false}-->
            CodeChangeFile codeChangeFile = CodeChangeFile.builder()
                    .filePath(map.get("filePath").toString())
                    .newCode(map.get("fullNewCode").toString())
                    .isDeleteFile(map.get("isDeleteFile").toString() == "1" ? 1 : 0)
                    .build();

            result.add(codeChangeFile);
        }
        return result;
    }
}