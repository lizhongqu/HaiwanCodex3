package com.haiwancodex.www.mapper;

import com.haiwancodex.www.entity.CodeChangeFile;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

@Mapper
public interface CodeChangeFileMapper {
    // 单条新增
    int insert(CodeChangeFile record);
    // 批量插入文件变更
    int insertBatch(@Param("list") List<CodeChangeFile> list);
    // 根据批次ID查询所有文件
    List<CodeChangeFile> selectByBatchId(Long batchId);
    // 根据ID查询
    CodeChangeFile selectById(Long id);
    // 更新
    int updateById(CodeChangeFile record);
    // 删除单条
    int deleteById(Long id);
    // 根据批次批量删除子文件
    int deleteByBatchId(Long batchId);
}