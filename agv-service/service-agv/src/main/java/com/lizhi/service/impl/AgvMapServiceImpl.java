package com.lizhi.service.impl;


import com.lizhi.domain.dto.AgvMapDTO;
import com.lizhi.domain.po.AgvMap;
import com.lizhi.mapper.AgvMapMapper;
import com.lizhi.realtime.LiveStateCache;
import com.lizhi.result.Result;
import com.lizhi.service.AgvMapService;
import com.lizhi.utils.MapCache;
import org.springframework.beans.BeanUtils;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class AgvMapServiceImpl implements AgvMapService {

    private final AgvMapMapper agvMapMapper;
    private final LiveStateCache liveStateCache;
    private final MapCache mapCache;
    private final TransactionTemplate transactionTemplate;

    public AgvMapServiceImpl(AgvMapMapper agvMapMapper, LiveStateCache liveStateCache, MapCache mapCache,
                             TransactionTemplate transactionTemplate) {
        this.agvMapMapper = agvMapMapper;
        this.liveStateCache = liveStateCache;
        this.mapCache = mapCache;
        this.transactionTemplate = transactionTemplate;
    }

    /**
     * 保存地图（生成新版本）。
     * "旧版本置为历史 + 插入新版本"在同一事务内完成，不会出现没有当前地图的中间状态；
     * 提交后让地图缓存失效并刷新实时快照，保证写后读 /agv/live 能立刻看到新 mapVersion（R-V2-08）。
     */
    @Override
    public Result<AgvMap> saveMap(AgvMapDTO agvMapDTO) {
        if (agvMapDTO == null) {
            return Result.error("地图内容不能为空");
        }
        Boolean saved = transactionTemplate.execute(status -> doSaveMap(agvMapDTO));
        if (!Boolean.TRUE.equals(saved)) {
            return Result.error("当前没有可用地图，无法保存新版本");
        }
        mapCache.invalidate();
        liveStateCache.rebuild();
        return Result.success();
    }

    private boolean doSaveMap(AgvMapDTO agvMapDTO) {
        AgvMap oldMap = agvMapMapper.getCurrentMap();
        if (oldMap == null) {
            return false;
        }
        oldMap.setIsCurrent(0);
        oldMap.setMapName("旧地图");
        agvMapMapper.updateMap(oldMap);
        AgvMap newMap = new AgvMap();
        BeanUtils.copyProperties(agvMapDTO, newMap);
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
        return true;
    }

    /**
     * 获取当前地图信息（V2.1：读内存快照，客户端请求不再打 DB；原响应结构不变）
     */
    @Override
    public AgvMap getCurrentMap() {
        return liveStateCache.getCurrentMap();
    }

    /**
     * 重置地图（返回当前地图，不做写操作）
     */
    @Override
    public AgvMap resetMap() {
        return liveStateCache.getCurrentMap();
    }

    /**
     * 获取当前站点位置
     */
    @Override
    public AgvMap getStation() {
        return agvMapMapper.getStation();
    }
}
