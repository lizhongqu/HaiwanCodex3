package com.haiwancodex.www.entity;

import java.time.LocalDateTime;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CodeChangeBatch {
    private Long id;
    private String workspaceId;
    private String messageId;
    private String desc;
    private Long promptTokens;
    private Long completionTokens;
    private Long totalTokens;
    private LocalDateTime createdAt;
}