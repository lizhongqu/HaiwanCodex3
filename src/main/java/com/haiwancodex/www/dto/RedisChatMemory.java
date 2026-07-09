package com.haiwancodex.www.dto;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.time.Duration;
import java.util.*;

public class RedisChatMemory implements ChatMemory {

    private static final String KEY_PREFIX = "codex:memory:";
    private static final Duration TTL = Duration.ofHours(2);
    private static final int MAX_MESSAGES = 14;

    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public RedisChatMemory(StringRedisTemplate redisTemplate) {
        this.redisTemplate = redisTemplate;
    }

    @Override
    public List<Message> get(String conversationId) {
        String key = KEY_PREFIX + conversationId;
        String json = redisTemplate.opsForValue().get(key);
        if (json == null || json.isBlank()) {
            return new ArrayList<>();
        }
        try {
            List<Map<String, Object>> raw = objectMapper.readValue(json, new TypeReference<>() {});
            List<Message> messages = new ArrayList<>();
            for (Map<String, Object> map : raw) {
                String role = (String) map.get("role");
                String content = (String) map.get("content");
                if ("user".equals(role)) {
                    messages.add(new UserMessage(content));
                } else if ("assistant".equals(role)) {
                    messages.add(new AssistantMessage(content));
                }
            }
            return messages;
        } catch (JsonProcessingException e) {
            return new ArrayList<>();
        }
    }

    @Override
    public void add(String conversationId, List<Message> messages) {
        List<Message> existing = get(conversationId);
        existing.addAll(messages);

        // 只保留用户消息、无元数据的普通助手消息，所有带工具元数据的消息直接丢弃
        List<Message> pureDialog = new ArrayList<>();
        for (Message m : existing) {
            // 存在元数据 = 工具调用返回，直接跳过不保存
            if(isToolMessage(m)){
                continue;
            }
            pureDialog.add(m);
        }

        List<Message> finalMsgList;
        if (pureDialog.size() > MAX_MESSAGES) {
            finalMsgList = new ArrayList<>(pureDialog.subList(pureDialog.size() - MAX_MESSAGES, pureDialog.size()));
        } else {
            finalMsgList = pureDialog;
        }

        List<Map<String, String>> list = new ArrayList<>();
        for (Message msg : finalMsgList) {
            String role;
            String content;
            try {
                // 通过 Jackson 将 Message 对象转换成树模型，避免依赖具体方法
                JsonNode node = objectMapper.valueToTree(msg);

                // 获取角色：优先从 "messageType" 字段推断，否则根据 Java 类型
                if (node.has("messageType")) {
                    role = node.get("messageType").asText().toLowerCase();
                } else {
                    role = (msg instanceof UserMessage) ? "user" : "assistant";
                }

                // 获取内容：尝试 "content"、"text" 等常见字段，最后用 toString 兜底
                if (node.has("content")) {
                    content = node.get("content").asText();
                } else if (node.has("text")) {
                    content = node.get("text").asText();
                } else {
                    content = msg.toString();
                }
            } catch (Exception e) {
                role = "unknown";
                content = msg.toString();
            }
            content = compressText(content);
            list.add(Map.of("role", role, "content", content));
        }

        try {
            String json = objectMapper.writeValueAsString(list);
            redisTemplate.opsForValue().set(KEY_PREFIX + conversationId, json, TTL);
        } catch (JsonProcessingException ignored) {
        }
    }

    @Override
    public void clear(String conversationId) {
        redisTemplate.delete(KEY_PREFIX + conversationId);
    }

    // 文本清洗压缩
    private String compressText(String text) {
        if(text == null || text.isBlank()) return "";
        // 1. 全部换行统一为单换行
        String res = text.replaceAll("\\r\\n", "\n").replaceAll("\\r", "\n");
        // 2. 连续多行空行合并为一行
        res = res.replaceAll("\\n{3,}", "\n\n");
        // 3. 行首行尾多余空格清除
        res = res.replaceAll("^\\s+|\\s+$", "");
        // 4. 超长单条内容截断（单条最大字符，例如3000字符）
//        int MAX_SINGLE_CHAR = 3000;
//        if(res.length() > MAX_SINGLE_CHAR) {
//            res = res.substring(0, MAX_SINGLE_CHAR) + "\n【内容过长已截断】";
//        }
        return res;
    }

    // 正确判断：是否是工具调用消息
    private boolean isToolMessage(Message m) {
        // 先判断是否助手消息
        if (!(m instanceof AssistantMessage assistantMsg)) {
            return false;
        }
        // 存在工具调用记录 = 工具消息
        return assistantMsg.getToolCalls() != null && !assistantMsg.getToolCalls().isEmpty();
    }
}