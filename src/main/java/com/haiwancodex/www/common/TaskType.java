package com.haiwancodex.www.common;

public enum TaskType {
    CHAT,          // 日常闲聊或简单的 API 概念问答 (例: "Spring AI 怎么配置？")
    SINGLE_FILE,   // 简单的单文件代码片段生成 (例: "写一个计算斐波那契数列的工具类")
    AGENT_LOOP     // 需要理解现有项目上下文、多文件修改、运行测试的复杂 Bug 修复或重构
}