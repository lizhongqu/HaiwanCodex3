package com.haiwancodex.www.controller;

import com.haiwancodex.www.service.StatService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/admin/codex/stat")
@RequiredArgsConstructor
public class StatController {

    private final StatService statService;

    // 获取全局全部用量
    @GetMapping("/global")
    public Map<String, Long> getGlobal() {
        return statService.getGlobalStat();
    }

    // 获取单个工作区用量
    @GetMapping("/ws/{workspaceId}")
    public Map<String, Long> getWsStat(@PathVariable String workspaceId) {
        return statService.getWorkspaceStat(workspaceId);
    }

    // 清空单个工作区token/对话统计
    @DeleteMapping("/ws/{workspaceId}")
    public String clearWsStat(@PathVariable String workspaceId) {
        statService.clearWorkspaceStat(workspaceId);
        return "success";
    }

    // 全局清空所有工作区统计
    @DeleteMapping("/global")
    public String clearGlobalStat() {
        statService.clearAllGlobalStat();
        return "success";
    }
}