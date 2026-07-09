package com.haiwancodex.www.service;

import com.haiwancodex.www.entity.CodeChangeBatch;
import com.haiwancodex.www.mapper.CodeChangeBatchMapper;
import jakarta.annotation.Resource;
import org.springframework.stereotype.Service;
import java.util.List;

@Service
public class CodeChangeBatchService {
    @Resource
    private CodeChangeBatchMapper batchMapper;

    public Long createBatch(CodeChangeBatch batch) {
        batchMapper.insert(batch);
        return batch.getId();
    }

    public CodeChangeBatch getById(Long id) {
        return batchMapper.selectById(id);
    }

    public List<CodeChangeBatch> listByWsMsg(String workspaceId, Long messageId) {
        return batchMapper.selectByWsMsg(workspaceId, messageId);
    }

    public boolean updateBatch(CodeChangeBatch batch) {
        return batchMapper.updateById(batch) > 0;
    }

    public boolean deleteBatch(Long id) {
        return batchMapper.deleteById(id) > 0;
    }
}