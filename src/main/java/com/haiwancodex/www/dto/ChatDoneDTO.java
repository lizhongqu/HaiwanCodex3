package com.haiwancodex.www.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class ChatDoneDTO {
    /** 本次消息唯一ID，关联变更批次 */
    private String messageId;
    private Long promptTokens;
    private Long completionTokens;
    private Long totalTokens;
    /** 本次生成的文件变更数量，前端提示用 */
    private Integer fileChangeCount;
}