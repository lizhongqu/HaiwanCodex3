package com.haiwancodex.www.service;

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

    public List<CodeChangeBatch> listBatchByWorkspaceId(String workspaceId) {
        return batchMapper.selectByWorkspaceId(workspaceId);
    }

    public List<CodeChangeBatch> listBatchByConditions(String workspaceId, String desc, String startTime, String endTime) {
        return batchMapper.selectByConditions(workspaceId, desc, startTime, endTime);
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