package com.haiwancodex.www.mapper;

import com.haiwancodex.www.entity.CodeChangeFile;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import java.util.List;

@Mapper
public interface CodeChangeFileMapper {

    /**
     * 批量插入一批次下所有变更文件
     */
    int insertBatch(@Param("list") List<CodeChangeFile> fileList);

    /**
     * 根据批次ID删除该批次全部关联文件记录
     */
    int deleteByBatchId(@Param("batchId") Long batchId);

    /**
     * 根据工作空间ID，清空该空间全部文件变更记录
     */
    int deleteAllByWs(@Param("workspaceId") String workspaceId);

    /**
     * 根据批次ID查询该批次所有修改文件
     */
    List<CodeChangeFile> selectByBatchId(@Param("batchId") Long batchId);

    // 根据文件主键查询单条文件记录
    CodeChangeFile selectById(@Param("fileId") Long fileId);
}