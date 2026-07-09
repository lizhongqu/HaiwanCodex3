package com.haiwancodex.www.dto;

import lombok.Data;

@Data
public class SinglePatchItem {
    // 文件路径 / Java类名
    private String pathOrClassName;
    // 当前文件选中变更数组 JSON
    private String diffJson;
}