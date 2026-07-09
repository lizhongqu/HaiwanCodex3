package com.haiwancodex.www.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@AllArgsConstructor
@NoArgsConstructor
public class CodeDiffItem {
    // 变更起始行（前端展示从1开始）
    private Integer startLine;
    // 变更结束行
    private Integer endLine;
    // 变更类型：INSERT 新增 / DELETE 删除 / MODIFY 修改
    private String type;
    // 原始代码片段
    private String oldContent;
    // 新代码片段
    private String newContent;
    // 前端勾选状态，默认选中
    private Boolean selected = true;
}