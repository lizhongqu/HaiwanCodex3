package com.haiwancodex.www.controller;

import com.haiwancodex.www.dto.BatchPatchDTO;
import com.haiwancodex.www.tool.WorkspaceFileTools;
import com.haiwancodex.www.util.FilePatchUtil;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.Map;

@Slf4j
@RestController
@RequestMapping("/agent")
@RequiredArgsConstructor
public class AgentFileController {

    private final FilePatchUtil filePatchUtil;

    @PostMapping("/patch")
    public String patch(
            @RequestParam(defaultValue = "HaiwanCodex3") String workspaceId,
            @RequestParam(defaultValue = "D:\\ideaSpace") String workspaceRoot,
            @RequestBody BatchPatchDTO dto
    ) throws Exception {
        filePatchUtil.batchApply(dto, workspaceId, workspaceRoot);
        return "success";
    }
}