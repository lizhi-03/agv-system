package com.lizhi.utils;

import com.lizhi.astart.map.GridMap;
import com.lizhi.astart.service.AStarSolver;
import com.lizhi.astart.service.NoPathFoundException;
import com.lizhi.domain.po.AgvLog;
import com.lizhi.domain.po.AgvPath;
import com.lizhi.domain.po.Node;
import com.lizhi.mapper.AgvLogMapper;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.List;

@Component
public class AgvUnlock {

    private final MapCache mapCache;
    private final AgvLogMapper agvLogMapper;

    public AgvUnlock(MapCache mapCache, AgvLogMapper agvLogMapper) {
        this.mapCache = mapCache;
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
        return realTimePath(mapCache.current(), startNode, endNode, agvPaths, agvCode, logOnError);
    }

    /**
     * 实时路径规划（使用调用方已取得的地图快照，tick 内整轮复用同一份）。
     * <p>
     * 网格从缓存模板复制（障碍、站点不可通行），不再每次规划都查库、解析地图 JSON、初始化网格。
     */
    public List<Node> realTimePath(MapCache.Snapshot map, Node startNode, Node endNode, List<AgvPath> agvPaths,
                                   String agvCode, boolean logOnError) throws NoPathFoundException {
        if (map == null || map.gridTemplate() == null) {
            throw new NoPathFoundException("当前没有可用地图，无法规划路径");
        }
        GridMap gridMap = map.gridTemplate().copy();
        gridMap.setWeight(startNode.getX(), startNode.getY(), 1);
        gridMap.setWeight(endNode.getX(), endNode.getY(), 1);
        // 注意：与改造前的行为保持一致 —— 旧代码先把其它车辆的占用路径格置为 -1，
        // 但在求解前又全部改回 1，净效果是"占用格可通行"（即使它原本是站点/障碍）。
        // 也就是说其它车辆的占用路径目前并不会让本车绕行。这里只保留同样的净效果，
        // 不擅自改变避让行为；若要真正避让，应把这里改成 -1 并评估对向会车死锁的处理。
        if (agvPaths != null) {
            for (AgvPath agvPath : agvPaths) {
                if (agvPath.getX() != null && agvPath.getY() != null) {
                    gridMap.setWeight(agvPath.getX(), agvPath.getY(), 1);
                }
            }
        }
        AStarSolver aStarSolver = new AStarSolver(gridMap);
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
