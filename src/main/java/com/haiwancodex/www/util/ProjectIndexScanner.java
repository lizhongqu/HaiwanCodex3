package com.haiwancodex.www.util;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.*;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

@Slf4j
@Component
@RequiredArgsConstructor
public class ProjectIndexScanner {

    private final StringRedisTemplate redisTemplate;
    private static final long EXPIRE_SECONDS = 30 * 60L;
    private static final long SCAN_COOL_DOWN_SEC = 10;
    private static final String TREE_FLAG_PREFIX = "codex:tree-flag:";
    private static final String SCAN_MTIME_PREFIX = "codex:scan-mtime:";
    private static final String SCAN_COOL_PREFIX = "codex:scan-cool:";

    // 忽略目录黑名单
    private static final String[] IGNORE_DIR = {
            ".git", "target", "node_modules", ".idea", "build", "out",
            ".mvn", ".fastRequest", "logs", "maven-wrapper", "null20260514",
            "bin", "boot", "conf", "lib", "ext", "jansi-native",
            "wrapper", "maven-status", "maven-archiver", "generated-sources"
    };

    /**
     * 异步重建索引
     */
    @Async
    public void scanWorkspace(String workspaceRootPath, String workspaceId) {
        Path globalRoot;
        try {
            globalRoot = Paths.get(workspaceRootPath).normalize().toAbsolutePath();
        } catch (Exception e) {
            log.error("全局根路径解析失败 {} ", workspaceRootPath, e);
            return;
        }
        Path wsRoot = globalRoot.resolve(workspaceId);
        if (!Files.isDirectory(wsRoot)) {
            log.warn("当前 workspace 目录不存在:{}", wsRoot);
            return;
        }

        redisTemplate.opsForValue().set(SCAN_COOL_PREFIX + workspaceId, "1", SCAN_COOL_DOWN_SEC, TimeUnit.SECONDS);

        String treeText = buildTree(wsRoot, wsRoot, 3);
        String treeKey = "codex:tree:" + workspaceId;
        redisTemplate.opsForValue().set(treeKey, treeText, EXPIRE_SECONDS, TimeUnit.SECONDS);

        Map<String, String> classMap = new HashMap<>();
        try (Stream<Path> walk = Files.walk(wsRoot)) {
            walk.filter(Files::isRegularFile)
                    .filter(p -> p.toString().endsWith(".java"))
                    .forEach(file -> {
                        try {
                            String fileName = file.getFileName().toString();
                            String className = fileName.replace(".java", "");
                            String relPath = wsRoot.relativize(file).toString().replace("\\", "/");
                            classMap.put(className, relPath);
                        } catch (Exception ex) {
                            log.warn("解析Java文件路径异常:{}", file, ex);
                        }
                    });
        } catch (IOException e) {
            log.error("遍历Java源码文件失败", e);
        }

        String hashKey = "codex:class_map:" + workspaceId;
        redisTemplate.delete(hashKey);
        if (!classMap.isEmpty()) {
            redisTemplate.opsForHash().putAll(hashKey, classMap);
            redisTemplate.expire(hashKey, EXPIRE_SECONDS, TimeUnit.SECONDS);
        }

        // 修复：捕获 getLastModifiedTime IO异常
        long dirMtime = 0;
        try {
            dirMtime = Files.getLastModifiedTime(wsRoot).toMillis();
        } catch (IOException e) {
            log.error("读取目录修改时间失败 wsRoot:{}", wsRoot, e);
        }
        if (dirMtime > 0) {
            redisTemplate.opsForValue().set(SCAN_MTIME_PREFIX + workspaceId, String.valueOf(dirMtime), EXPIRE_SECONDS, TimeUnit.SECONDS);
        }
        clearTreeFlag(workspaceId);
        log.info("自动重建索引完成 wsId:{}", workspaceId);
    }

    /**
     * 获取项目摘要，自动检测文件变更
     */
    public String getProjectSummary(String globalRootPath, String workspaceId) {
        Path globalRoot;
        try {
            globalRoot = Paths.get(globalRootPath).normalize().toAbsolutePath();
        } catch (Exception e) {
            log.error("路径解析失败 {}", globalRootPath, e);
            return "";
        }
        Path wsRoot = globalRoot.resolve(workspaceId);
        if (!Files.isDirectory(wsRoot)) return "";

        String mtimeKey = SCAN_MTIME_PREFIX + workspaceId;
        String treeKey = "codex:tree:" + workspaceId;
        String flagKey = TREE_FLAG_PREFIX + workspaceId;
        String coolKey = SCAN_COOL_PREFIX + workspaceId;

        if (Boolean.TRUE.equals(redisTemplate.hasKey(coolKey))) {
            String tree = redisTemplate.opsForValue().get(treeKey);
            if (tree == null || tree.isBlank()) return "";
            return Boolean.FALSE.equals(redisTemplate.hasKey(flagKey))
                    ? "【项目文件夹结构，优先查表】\n" + tree
                    : "";
        }

        // 捕获IO异常
        long currentDirMtime;
        try {
            currentDirMtime = Files.getLastModifiedTime(wsRoot).toMillis();
        } catch (IOException e) {
            log.error("读取目录修改时间失败，路径:{}", wsRoot, e);
            return "";
        }
        String savedMtimeStr = redisTemplate.opsForValue().get(mtimeKey);
        long savedMtime = savedMtimeStr == null ? 0 : Long.parseLong(savedMtimeStr);

        if (currentDirMtime > savedMtime) {
            log.info("检测到项目文件变更，异步重建索引 wsId:{}", workspaceId);
            scanWorkspace(globalRootPath, workspaceId);
        }

        String tree = redisTemplate.opsForValue().get(treeKey);
        if (tree == null || tree.isBlank()) return "";

        if (Boolean.FALSE.equals(redisTemplate.hasKey(flagKey))) {
            redisTemplate.opsForValue().set(flagKey, "1", EXPIRE_SECONDS, TimeUnit.SECONDS);
            return "【项目文件夹结构，优先查表】\n" + tree;
        }
        return "";
    }

    public void clearTreeFlag(String workspaceId) {
        redisTemplate.delete(TREE_FLAG_PREFIX + workspaceId);
    }

    /**
     * 只输出文件夹，过滤全部文件、垃圾目录
     */
    private String buildTree(Path wsRoot, Path current, int maxDepth) {
        StringBuilder sb = new StringBuilder();
        String rootName = wsRoot.getFileName() == null ? "" : wsRoot.getFileName().toString();
        sb.append(rootName).append("\n");

        try (Stream<Path> stream = Files.walk(current, maxDepth)) {
            stream.filter(p -> !p.equals(wsRoot))
                    // 关键：先判断整条路径是否包含黑名单目录，直接整条分支跳过
                    .filter(path -> {
                        String pathStr = path.toString().replace("\\", "/");
                        // 拦截 .git 整个分支
                        if (pathStr.contains("/.git/")) {
                            return false;
                        }
                        String name = path.getFileName().toString();
                        // 拦截所有 . 开头目录
                        if (name.startsWith(".")) {
                            return false;
                        }
                        // 黑名单目录完全匹配拦截
                        for (String ignore : IGNORE_DIR) {
                            if (name.equals(ignore)) {
                                return false;
                            }
                        }
                        return true;
                    })
                    .filter(Files::isDirectory)
                    .sorted()
                    .forEach(p -> {
                        int depth = wsRoot.relativize(p).getNameCount();
                        String indent = " ".repeat(depth);
                        sb.append(indent).append(p.getFileName()).append("\n");
                    });
        } catch (IOException e) {
            log.error("生成目录树失败，路径:{}", current, e);
            sb.append(" 目录遍历异常\n");
        }
        return sb.toString().trim();
    }

    /**
     * 根据workspaceId读取类名→文件路径映射表（Redis Hash）
     */
    public Map<String, String> getClassMap(String workspaceId) {
        String hashKey = "codex:class_map:" + workspaceId;
        Map<Object, Object> rawMap = redisTemplate.opsForHash().entries(hashKey);
        if (rawMap.isEmpty()) {
            return new HashMap<>();
        }
        Map<String, String> result = new HashMap<>();
        rawMap.forEach((k, v) -> result.put(k.toString(), v.toString()));
        return result;
    }
}