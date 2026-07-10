package com.haiwancodex.www.dto;

import lombok.Data;

@Data
public class ChatRequest {
    // 当前操作项目唯一ID（前端传入）
    private String workspaceId;
    // 当前项目本地根目录，前端直接传入绝对路径
    private String workspaceRoot;
    // 消息内容
    private String message;
    // 用户提问
    private String question;
    // 厂商
    private String provider = "ollama";
    // 推理模型名称
    private String modelName = "qwen2.5-coder:7b-instruct";

    private String apiKey = "";
//    private Integer connectTimeout = 30000;
    // 动态模型参数 前端传入
    private Double temperature = 0.2;
    private Double topP = 0.1;
    private Integer numCtx = 8192;
    private String messageId;
}