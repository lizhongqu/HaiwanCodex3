package com.haiwancodex.www.controller;

import com.haiwancodex.www.entity.CodeChangeBatch;
import com.haiwancodex.www.entity.CodeChangeFile;
import com.haiwancodex.www.service.CodeChangeService;
import com.haiwancodex.www.service.DiffService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/codex/change")
@RequiredArgsConstructor
public class CodeChangeController {

    private final CodeChangeService codeChangeService;
    private final DiffService diffService;

    @GetMapping("/file/diff")
    public String getFileDiff(@RequestParam Long fileId) {
        CodeChangeFile file = codeChangeService.getFileById(fileId);
        if (file == null) {
            throw new RuntimeException("文件记录不存在");
        }
        return diffService.computeDiff(file);
    }

    @GetMapping("/batch/list")
    public List<CodeChangeBatch> listBatches(@RequestParam String workspaceId) {
        return codeChangeService.listBatchByWorkspaceId(workspaceId);
    }

    /**
     * 获取指定批次下所有文件代码
     */
    @GetMapping("/file/list")
    public List<CodeChangeFile> getFileList(@RequestParam Long batchId) {
        return codeChangeService.listFileByBatchId(batchId);
    }

    /**
     * 删除单个批次及关联文件
     */
    @DeleteMapping("/batch")
    public void deleteBatch(@RequestParam Long batchId) {
        codeChangeService.deleteBatch(batchId);
    }

    /**
     * 清空当前工作空间全部变更记录
     */
    @DeleteMapping("/batch/clear")
    public void clearWorkspace(@RequestParam String workspaceId) {
        codeChangeService.clearAllByWorkspace(workspaceId);
    }

    /**
     * 根据文件ID获取单文件完整代码（工作台预览）
     */
    @GetMapping("/file/detail")
    public CodeChangeFile getFileDetail(@RequestParam Long fileId) {
        return codeChangeService.getFileById(fileId);
    }
}