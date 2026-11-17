package com.lizhi.utils;

import com.lizhi.domain.po.AgvPath;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 单次 tick 内所有车辆共享的只读/半只读数据：地图快照 + 各车占用路径。
 * <p>
 * 占用路径整轮 tick 只从 agv_path 查一次（旧实现每台运行车查一次全表）；本轮内某台车重写了自己的
 * 占用路径时同步更新这里，后面的车看到的仍是最新占用，语义与旧实现一致。仅在 tick 线程内使用。
 */
public class TickContext {

    private final MapCache.Snapshot map;
    private final Map<String, List<AgvPath>> pathsByAgv = new HashMap<>();

    public TickContext(MapCache.Snapshot map, List<AgvPath> allPaths) {
        this.map = map;
        if (allPaths != null) {
            for (AgvPath path : allPaths) {
                if (path.getAgvCode() != null) {
                    pathsByAgv.computeIfAbsent(path.getAgvCode(), k -> new ArrayList<>()).add(path);
                }
            }
        }
    }

    /** 当前地图快照，没有地图时为 null */
    public MapCache.Snapshot map() {
        return map;
    }

    /** 除指定车辆外，其它所有车辆的占用路径点 */
    public List<AgvPath> pathsOfOthers(String agvCode) {
        List<AgvPath> others = new ArrayList<>();
        for (Map.Entry<String, List<AgvPath>> entry : pathsByAgv.entrySet()) {
            if (!entry.getKey().equals(agvCode)) {
                others.addAll(entry.getValue());
            }
        }
        return others;
    }

    /** 指定格子是否在其它车辆的占用路径上 */
    public boolean isOccupiedByOthers(String agvCode, int x, int y) {
        for (Map.Entry<String, List<AgvPath>> entry : pathsByAgv.entrySet()) {
            if (entry.getKey().equals(agvCode)) {
                continue;
            }
            for (AgvPath path : entry.getValue()) {
                if (path.getX() != null && path.getY() != null && path.getX() == x && path.getY() == y) {
                    return true;
                }
            }
        }
        return false;
    }

    /** 本轮内某台车的占用路径被重写/清空后同步 */
    public void replacePaths(String agvCode, List<AgvPath> paths) {
        if (paths == null || paths.isEmpty()) {
            pathsByAgv.remove(agvCode);
        } else {
            pathsByAgv.put(agvCode, paths);
        }
    }
}
