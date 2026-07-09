package com.haiwancodex.www.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Data
@AllArgsConstructor
@NoArgsConstructor
public class FileDiffResult {
    // 文件完整路径
    private String filePath;
    // 文件原始完整文本（备用）
    private String originalText;
    // 当前文件所有变更列表
    private List<CodeDiffItem> diffList;
    // 当前文件是否存在修改
    private Boolean hasChange;
}