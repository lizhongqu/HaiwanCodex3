package com.haiwancodex.www.mapper;

import com.haiwancodex.www.entity.CodeChangeBatch;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

@Mapper
public interface CodeChangeBatchMapper {

    /**
     * 插入代码变更批次
     */
    int insert(CodeChangeBatch batch);

    /**
     * 根据批次ID删除单条批次
     */
    int deleteById(@Param("batchId") Long batchId);

    /**
     * 根据工作空间ID查询全部批次，创建时间倒序
     */
    List<CodeChangeBatch> selectByWorkspaceId(@Param("workspaceId") String workspaceId);

    /**
     * 根据工作空间ID清空所有批次
     */
    int deleteAllByWs(@Param("workspaceId") String workspaceId);

    /**
     * 根据批次ID查询单条批次详情
     */
    CodeChangeBatch selectById(@Param("batchId") Long batchId);
}