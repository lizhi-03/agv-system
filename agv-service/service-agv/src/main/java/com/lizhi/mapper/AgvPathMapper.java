package com.lizhi.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.lizhi.domain.po.AgvPath;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

@Mapper
public interface AgvPathMapper extends BaseMapper<AgvPath> {
    /**
     * 根据agvCode删除路径
     * @param agvCode
     */
    void deleteByAgvCode(String agvCode);

    /**
     * 批量插入占用路径点（调用方负责分片，单次不要超过 500 条）
     * @param paths 非空列表
     */
    void insertBatch(@Param("paths") List<AgvPath> paths);
}
