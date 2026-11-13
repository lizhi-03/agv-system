package com.lizhi.utils;

import com.lizhi.astart.map.GridMap;
import com.lizhi.astart.service.AStarSolver;
import com.lizhi.astart.service.NoPathFoundException;
import com.lizhi.domain.po.*;
import com.lizhi.mapper.AgvLogMapper;
import com.lizhi.mapper.AgvMapMapper;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.List;

@Component
public class AgvUnlock {

    private final AgvMapMapper agvMapMapper;
    private final AgvConversion agvConversion;
    private final AgvLogMapper agvLogMapper;

    public AgvUnlock(AgvMapMapper agvMapMapper, AgvConversion agvConversion, AgvLogMapper agvLogMapper) {
        this.agvMapMapper = agvMapMapper;
        this.agvConversion = agvConversion;
        this.agvLogMapper = agvLogMapper;
    }

    /**
     * 实时路径规划（规划失败时写一条 error 日志，供真实任务调度使用）。
     */
    public List<Node> realTimePath(Node startNode, Node endNode, List<AgvPath> agvPaths, String agvCode) throws NoPathFoundException {
        return realTimePath(startNode, endNode, agvPaths, agvCode, true);
    }

    /**
     * 实时路径规划。
     *
     * @param logOnError 规划失败时是否写 agv_log。`/agv/preview` 这类“只预览不落库”的接口传 false，
     *                   保证预览请求不产生任何数据库写入。
     */
    public List<Node> realTimePath(Node startNode, Node endNode, List<AgvPath> agvPaths, String agvCode,
                                   boolean logOnError) throws NoPathFoundException {
        AgvMap currentMap = agvMapMapper.getCurrentMap();
        if (currentMap == null) {
            throw new NoPathFoundException("当前没有可用地图，无法规划路径");
        }
        List<AgvMap.Obstacle> oldObstacles = currentMap.getObstacles();
        List<AgvMap.Obstacle> obstacles = agvConversion.convertObstaclesToAgvObstacleObjects(oldObstacles);
        GridMap gridMap = new GridMap(false, currentMap.getHeight(), currentMap.getWidth());
        for (AgvMap.Obstacle obstacle : obstacles) {
            gridMap.setWeight(obstacle.getX(), obstacle.getY(), -1);
        }
        List<AgvMap.Station> oldOstations = currentMap.getStations();
        List<AgvMap.Station> stations = agvConversion.convertStationsToAgvStationObjects(oldOstations);
        for (AgvMap.Station station : stations) {
            gridMap.setWeight(station.getX(), station.getY(), -1);
        }
        gridMap.setWeight(startNode.getX(), startNode.getY(), 1);
        gridMap.setWeight(endNode.getX(), endNode.getY(), 1);
        if (agvPaths != null) {
            for (AgvPath agvPath : agvPaths) {
                gridMap.setWeight(agvPath.getX(), agvPath.getY(), -1);
            }
        }
        AStarSolver aStarSolver = new AStarSolver(gridMap);
        if (agvPaths != null) {
            for (AgvPath agvPath : agvPaths) {
                gridMap.setWeight(agvPath.getX(), agvPath.getY(), 1);
            }
        }
        try {
            return aStarSolver.AstarFindPath(startNode, endNode);
        } catch (NoPathFoundException e) {
            if (logOnError) {
                AgvLog agvLog = new AgvLog();
                agvLog.setTimestamp(LocalDateTime.now());
                agvLog.setLevel("error");
                agvLog.setMessage(agvCode + " 未找到有效路径，无法到达目标点");
                agvLogMapper.insert(agvLog);
            }
            // 直接抛出业务异常（NoPathFoundException），由 GlobalExceptionHandler 统一转成
            // HTTP 200 + code=204 + msg，避免前端收到 500。
            throw e;
        }
    }
}
