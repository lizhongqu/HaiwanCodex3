package com.haiwancodex.www.entity;

import java.time.LocalDateTime;
import lombok.Data;

@Data
public class CodeChangeBatch {
    private Long id;
    private String workspaceId;
    private Long messageId;
    private String desc;
    private Long promptTokens;
    private Long completionTokens;
    private Long totalTokens;
    private LocalDateTime createdAt;
}