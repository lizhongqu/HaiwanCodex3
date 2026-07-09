package com.haiwancodex.www.mapper;

import com.haiwancodex.www.entity.CodeChangeBatch;
import org.apache.ibatis.annotations.Mapper;
import java.util.List;

@Mapper
public interface CodeChangeBatchMapper {
    // 新增
    int insert(CodeChangeBatch record);
    // 根据ID查询
    CodeChangeBatch selectById(Long id);
    // 根据工作区+消息ID查询
    List<CodeChangeBatch> selectByWsMsg(String workspaceId, Long messageId);
    // 更新
    int updateById(CodeChangeBatch record);
    // 删除
    int deleteById(Long id);
}