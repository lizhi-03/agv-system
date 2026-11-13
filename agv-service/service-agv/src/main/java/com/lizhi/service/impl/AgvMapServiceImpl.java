package com.lizhi.service.impl;


import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.lizhi.domain.dto.AgvMapDTO;
import com.lizhi.domain.po.AgvMap;
import com.lizhi.domain.po.AgvPath;
import com.lizhi.mapper.AgvMapMapper;
import com.lizhi.mapper.AgvPathMapper;
import com.lizhi.result.Result;
import com.lizhi.service.AgvMapService;
import org.springframework.beans.BeanUtils;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class AgvMapServiceImpl implements AgvMapService {

    private final AgvMapMapper agvMapMapper;
    private final AgvPathMapper agvPathMapper;

    public AgvMapServiceImpl(AgvMapMapper agvMapMapper, AgvPathMapper agvPathMapper) {
        this.agvMapMapper = agvMapMapper;
        this.agvPathMapper = agvPathMapper;
    }

    /**
     * 保存地图
     * @param agvMapDTO
     */
    @Override
    public Result<AgvMap> saveMap(AgvMapDTO agvMapDTO) {
        AgvMap oldMap = agvMapMapper.getCurrentMap();
        if (oldMap == null) {
            return Result.error("当前没有可用地图，无法保存新版本");
        }
        if (agvMapDTO == null) {
            return Result.error("地图内容不能为空");
        }
        oldMap.setIsCurrent(0);
        oldMap.setMapName("旧地图");
        agvMapMapper.updateMap(oldMap);
        AgvMap newMap = new AgvMap();
        BeanUtils.copyProperties(agvMapDTO,newMap);
        // 入参缺省时继承旧地图，避免写入 NULL 后 version+1 触发空指针
        if (newMap.getWidth() == null) newMap.setWidth(oldMap.getWidth());
        if (newMap.getHeight() == null) newMap.setHeight(oldMap.getHeight());
        if (newMap.getCellSize() == null) newMap.setCellSize(oldMap.getCellSize());
        if (newMap.getObstacles() == null) newMap.setObstacles(oldMap.getObstacles());
        if (newMap.getStations() == null) newMap.setStations(oldMap.getStations());
        newMap.setMapId(null);
        newMap.setMapCode("map_" + System.currentTimeMillis());
        newMap.setMapName("新地图");
        newMap.setParentId(oldMap.getMapId());
        newMap.setVersion((oldMap.getVersion() == null ? 0 : oldMap.getVersion()) + 1);
        newMap.setIsCurrent(1);
        agvMapMapper.saveAgv(newMap);
        return Result.success();

    }
    /**
     * 获取当前地图信息
     * @return
     */
    @Override
    public AgvMap getCurrentMap() {
        //MyAssert.isTrue(count==0,"设备编码重复");
        AgvMap agvMap = agvMapMapper.getCurrentMap();
        Integer mapId = agvMap.getMapId();
        LambdaQueryWrapper<AgvPath> lambdaQueryWrapper = new LambdaQueryWrapper<>();
        lambdaQueryWrapper.eq(AgvPath::getMapId,mapId);
        List<AgvPath> agvPaths = agvPathMapper.selectList(lambdaQueryWrapper);
        if (agvPaths != null){
            agvMap.setPaths(agvPaths);
        }
        return agvMap;
    }

    /**
     * 重置地图
     *
     * @return
     */
    @Override
    public AgvMap resetMap() {
        AgvMap currentMap = agvMapMapper.getCurrentMap();
        return currentMap;
    }

    /**
     * 获取当前站点位置
     * @return
     */
    @Override
    public AgvMap getStation() {
        AgvMap agvMap = agvMapMapper.getStation();
        return agvMap;
    }
}
