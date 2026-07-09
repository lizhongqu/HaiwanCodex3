package com.haiwancodex.www.dto;

import lombok.Data;
import java.util.List;

@Data
public class MultiFileDiffResult {
    // 所有待修改文件差异集合（单文件长度=1，多文件>1）
    private List<FileDiffResult> fileDiffList;
    // 是否存在任意文件变更
    private Boolean hasAnyChange;
}