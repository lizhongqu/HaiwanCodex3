package com.haiwancodex.www.controller;

import com.haiwancodex.www.dto.ChatRequest;
import com.haiwancodex.www.dto.RedisChatMemory;
import com.haiwancodex.www.util.ProjectIndexScanner;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.messages.Message;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Slf4j
@RestController
@RequestMapping("/admin/codex")
@RequiredArgsConstructor
public class CodexAdminController {

    private final StringRedisTemplate redisTemplate;
    private final RedisChatMemory chatMemory;
    private final ProjectIndexScanner projectIndexScanner;

    // ====================== 会话历史管理 ======================
    /**
     * 查看指定 workspace 完整对话历史
     */
    @GetMapping("/chat/history/{workspaceId}")
    public List<Message> getChatHistory(@PathVariable String workspaceId) {
        return chatMemory.get(workspaceId);
    }

    /**
     * 删除单个会话全部历史
     */
    @DeleteMapping("/chat/{workspaceId}")
    public String clearChatHistory(@PathVariable String workspaceId) {
        chatMemory.clear(workspaceId);
        // 同时清理分类器缓存会话
        String classifierMemId = workspaceId + "_classifier";
        chatMemory.clear(classifierMemId);
        log.info("已清空会话记忆 workspaceId:{}", workspaceId);
        return "success";
    }

    // ====================== 项目上下文索引查看/重建 ======================
    /**
     * 查看缓存的项目目录树摘要
     */
    @GetMapping("/index/tree/{workspaceId}")
    public String getCachedProjectTree(@PathVariable String workspaceId) {
        String key = "codex:tree:" + workspaceId;
        return redisTemplate.opsForValue().get(key);
    }

    /**
     * 查看缓存的类名映射表（全部）
     */
    @GetMapping("/index/class-map/{workspaceId}")
    public Map<String, String> getClassMapping(@PathVariable String workspaceId) {
        String hashKey = "codex:class_map:" + workspaceId;
        Map<Object, Object> raw = redisTemplate.opsForHash().entries(hashKey);
        Map<String, String> result = new HashMap<>();
        if (raw != null) {
            raw.forEach((k, v) -> {
                String keyStr = String.valueOf(k);
                String valStr = String.valueOf(v);
                result.put(keyStr, valStr);
            });
        }
        return result;
    }

    /**
     * 手动重建项目上下文索引（同步执行，方便调试看耗时）
     */
    @PostMapping("/index/rebuild")
    public String rebuildIndex(@RequestBody ChatRequest req) {
        String wsId = req.getWorkspaceId();
        String root = req.getWorkspaceRoot();
        projectIndexScanner.scanWorkspace(root, wsId);
        log.info("手动重建索引完成 workspaceId:{} root:{}", wsId, root);
        return "rebuild success, workspaceId:" + wsId;
    }

    /**
     * 清空项目索引缓存（目录树+类映射）
     */
    @DeleteMapping("/index/{workspaceId}")
    public String clearIndexCache(@PathVariable String workspaceId) {
        String treeKey = "codex:tree:" + workspaceId;
        String classKey = "codex:class_map:" + workspaceId;
        redisTemplate.delete(treeKey);
        redisTemplate.delete(classKey);
        log.info("清空项目索引缓存 workspaceId:{}", workspaceId);
        return "index cache cleared";
    }

    /**
     * 批量查询所有存在的索引缓存 workspaceId（仅调试用）
     */
    @GetMapping("/index/all-ws")
    public Set<String> listAllIndexWorkspace() {
        Set<String> treeKeys = redisTemplate.keys("codex:tree:*");
        return treeKeys;
    }
}