package com.haiwancodex.www.service;

import com.haiwancodex.www.dto.CodeBatchDTO;
import com.haiwancodex.www.entity.CodeChangeBatch;
import com.haiwancodex.www.entity.CodeChangeFile;
import com.haiwancodex.www.mapper.CodeChangeBatchMapper;
import com.haiwancodex.www.mapper.CodeChangeFileMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.CollectionUtils;

import java.util.List;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class CodeChangeService {

    private final CodeChangeBatchMapper batchMapper;
    private final CodeChangeFileMapper fileMapper;

    /**
     * 保存批次+批量文件记录，返回批次主键ID
     */
    @Transactional(rollbackFor = Exception.class)
    public Long saveBatchAndFiles(CodeChangeBatch batch, List<CodeChangeFile> fileList) {
        batchMapper.insert(batch);
        Long batchId = batch.getId();
        if (!CollectionUtils.isEmpty(fileList)) {
            fileList.forEach(f -> f.setBatchId(batchId));
            fileMapper.insertBatch(fileList);
        }
        return batchId;
    }

    /**
     * 删除单条批次，级联删除文件记录
     */
    @Transactional(rollbackFor = Exception.class)
    public void deleteBatch(Long batchId) {
        fileMapper.deleteByBatchId(batchId);
        batchMapper.deleteById(batchId);
    }

    /**
     * 清空当前工作空间所有变更
     */
    @Transactional(rollbackFor = Exception.class)
    public void clearAllByWorkspace(String workspaceId) {
        fileMapper.deleteAllByWs(workspaceId);
        batchMapper.deleteAllByWs(workspaceId);
    }

    /**
     * 根据工作空间查询所有变更批次（转为前端列表DTO）
     */
    public List<CodeBatchDTO> listBatchByWorkspace(String workspaceId) {
        List<CodeChangeBatch> batchList = batchMapper.selectByWorkspaceId(workspaceId);
        return batchList.stream().map(batch -> {
            CodeBatchDTO dto = new CodeBatchDTO();
            dto.setBatchId(batch.getId());
            dto.setDesc(batch.getDesc());
            dto.setPromptTokens(batch.getPromptTokens());
            dto.setCompletionTokens(batch.getCompletionTokens());
            dto.setTotalTokens(batch.getTotalTokens());
            dto.setCreatedAt(batch.getCreatedAt());
            return dto;
        }).collect(Collectors.toList());
    }

    /**
     * 根据批次ID查询该批次全部修改文件
     */
    public List<CodeChangeFile> listFileByBatchId(Long batchId) {
        return fileMapper.selectByBatchId(batchId);
    }

    /**
     * 根据批次ID查询批次基础信息
     */
    public CodeChangeBatch getBatchById(Long batchId) {
        return batchMapper.selectById(batchId);
    }

    /**
     * 根据文件ID获取单条文件完整代码
     */
    public CodeChangeFile getFileById(Long fileId) {
        return fileMapper.selectById(fileId);
    }
}