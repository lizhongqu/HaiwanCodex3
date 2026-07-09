package com.haiwancodex.www.service;

import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;

@Service
@RequiredArgsConstructor
public class StatService {

    private final StringRedisTemplate redisTemplate;

    private static final String PREFIX_GLOBAL = "codex:stat:global:";
    private static final String PREFIX_WS = "codex:stat:ws:";

    // 会话计数上报
    public void recordChat(String workspaceId, long inputToken, long outputToken) {
        // 全局自增
        redisTemplate.opsForValue().increment(PREFIX_GLOBAL + "chatCount", 1);
        redisTemplate.opsForValue().increment(PREFIX_GLOBAL + "inputToken", inputToken);
        redisTemplate.opsForValue().increment(PREFIX_GLOBAL + "outputToken", outputToken);

        // 当前工作区自增
        String wsPrefix = PREFIX_WS + workspaceId + ":";
        redisTemplate.opsForValue().increment(wsPrefix + "chatCount", 1);
        redisTemplate.opsForValue().increment(wsPrefix + "inputToken", inputToken);
        redisTemplate.opsForValue().increment(wsPrefix + "outputToken", outputToken);
    }

    // 文件读取计数上报
    public void recordFileRead(String workspaceId) {
        redisTemplate.opsForValue().increment(PREFIX_GLOBAL + "fileRead", 1);
        String wsPrefix = PREFIX_WS + workspaceId + ":";
        redisTemplate.opsForValue().increment(wsPrefix + "fileRead", 1);
    }

    // 查询全局总统计
    public Map<String, Long> getGlobalStat() {
        Map<String, Long> map = new HashMap<>();
        map.put("chatCount", getLong(PREFIX_GLOBAL + "chatCount"));
        map.put("fileRead", getLong(PREFIX_GLOBAL + "fileRead"));
        map.put("inputToken", getLong(PREFIX_GLOBAL + "inputToken"));
        map.put("outputToken", getLong(PREFIX_GLOBAL + "outputToken"));
        map.put("totalToken", map.get("inputToken") + map.get("outputToken"));
        return map;
    }

    // 查询单个工作区统计
    public Map<String, Long> getWorkspaceStat(String workspaceId) {
        String wsPrefix = PREFIX_WS + workspaceId + ":";
        Map<String, Long> map = new HashMap<>();
        map.put("chatCount", getLong(wsPrefix + "chatCount"));
        map.put("fileRead", getLong(wsPrefix + "fileRead"));
        map.put("inputToken", getLong(wsPrefix + "inputToken"));
        map.put("outputToken", getLong(wsPrefix + "outputToken"));
        map.put("totalToken", map.get("inputToken") + map.get("outputToken"));
        return map;
    }

    private Long getLong(String key) {
        String val = redisTemplate.opsForValue().get(key);
        return val == null ? 0L : Long.parseLong(val);
    }

    /**
     * 清空单个工作区所有统计数据
     */
    public void clearWorkspaceStat(String workspaceId) {
        String wsPrefix = PREFIX_WS + workspaceId + ":";
        // 匹配当前工作区全部统计key
        Set<String> keys = redisTemplate.keys(wsPrefix + "*");
        if (keys != null && !keys.isEmpty()) {
            redisTemplate.delete(keys);
        }
    }

    /**
     * 清空全局所有工作区统计（管理员重置）
     */
    public void clearAllGlobalStat() {
        Set<String> keys = redisTemplate.keys(PREFIX_GLOBAL + "*");
        if (keys != null && !keys.isEmpty()) {
            redisTemplate.delete(keys);
        }
    }
}