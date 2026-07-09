package com.haiwancodex.www.service;

import com.haiwancodex.www.entity.CodeChangeFile;
import com.haiwancodex.www.mapper.CodeChangeFileMapper;
import jakarta.annotation.Resource;
import org.springframework.stereotype.Service;
import java.util.List;

@Service
public class CodeChangeFileService {
    @Resource
    private CodeChangeFileMapper fileMapper;

    public int addFile(CodeChangeFile file) {
        return fileMapper.insert(file);
    }

    public int batchAddFile(List<CodeChangeFile> list) {
        return fileMapper.insertBatch(list);
    }

    public List<CodeChangeFile> listByBatch(Long batchId) {
        return fileMapper.selectByBatchId(batchId);
    }

    public CodeChangeFile getById(Long id) {
        return fileMapper.selectById(id);
    }

    public boolean updateFile(CodeChangeFile file) {
        return fileMapper.updateById(file) > 0;
    }

    public boolean deleteFile(Long id) {
        return fileMapper.deleteById(id) > 0;
    }

    public int deleteByBatch(Long batchId) {
        return fileMapper.deleteByBatchId(batchId);
    }
}