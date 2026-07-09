package com.haiwancodex.www.controller;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.TypeReference;
import com.haiwancodex.www.dto.ChatRequest;
import com.haiwancodex.www.dto.RedisChatMemory;
import com.haiwancodex.www.router.CodexRouter;
import com.haiwancodex.www.util.ProjectIndexScanner;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.MediaType;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Flux;

import java.util.List;
import java.util.Map;

@Slf4j
@RestController
@RequiredArgsConstructor
public class ChatController {

    private final  RedisChatMemory redisChatMemory;
    private final StringRedisTemplate redisTemplate;
    private final ProjectIndexScanner projectIndexScanner;

    private final CodexRouter codexRouter;

    // 保持与 RedisChatMemory 相同的 key 前缀
    private static final String CHAT_MEMORY_KEY_PREFIX = "codex:memory:";

    @GetMapping(value = "/chat/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<ServerSentEvent<String>> streamChat(
            @RequestParam(defaultValue = "0.3") Double temperature,
            @RequestParam(defaultValue = "0.9") Double topP,
            @RequestParam(defaultValue = "4096") Integer numCtx,
            @RequestParam(defaultValue = "HaiwanCodex3") String workspaceId,
            @RequestParam(defaultValue = "D:\\ideaSpace") String workspaceRoot,
            @RequestParam String message) {

        ChatRequest chatRequest = new ChatRequest();
        chatRequest.setTemperature(temperature);
        chatRequest.setTopP(topP);
        chatRequest.setNumCtx(numCtx);
        chatRequest.setWorkspaceId(workspaceId);
        chatRequest.setWorkspaceRoot(workspaceRoot);
        chatRequest.setMessage(message);

        return codexRouter.routeAndExecute(chatRequest);
    }

    /**
     * 查询对话历史
     */
    @GetMapping("/chat/history")
    public List<Map<String, String>> getHistory(@RequestParam String workspaceId) {
        String key = CHAT_MEMORY_KEY_PREFIX + workspaceId;
        String json = redisTemplate.opsForValue().get(key);
        if (json == null || json.isBlank()) {
            return List.of();
        }
        try {
            // 直接用 fastjson2 反序列化为 List<Map<String, String>>
            return JSON.parseObject(json, new TypeReference<List<Map<String, String>>>() {});
        } catch (Exception e) {
            return List.of();
        }
    }

    /**
     * 重置指定工作区的对话记忆
     */
    @DeleteMapping("/chat/history")
    public Map<String, String> clearHistory(@RequestParam String workspaceId) {
        redisChatMemory.clear(workspaceId);
        projectIndexScanner.clearTreeFlag(workspaceId);
        return Map.of("status", "success", "workspaceId", workspaceId);
    }

}