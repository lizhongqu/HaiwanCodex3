package com.haiwancodex.www.dto;

import lombok.Data;

@Data
public class FileDiffRaw {
    // 文件相对路径
    private String pathOrClassName;
    // AI生成完整新代码
    private String fullNewCode;
    // 是否整文件删除
    private boolean wholeFileDelete;
}