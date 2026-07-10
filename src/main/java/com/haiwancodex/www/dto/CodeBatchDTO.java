package com.haiwancodex.www.dto;

import lombok.Data;
import java.time.LocalDateTime;
import java.util.List;

@Data
public class CodeBatchDTO {
    private Long batchId;
    private String desc;
    private Long promptTokens;
    private Long completionTokens;
    private Long totalTokens;
    private LocalDateTime createdAt;
    // 阶段1仅列表展示，暂不返回文件详情
}