package com.lizhi.utils;

import com.lizhi.astart.map.GridMap;
import com.lizhi.domain.po.AgvMap;
import com.lizhi.domain.po.Node;
import com.lizhi.mapper.AgvMapMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * 当前地图缓存。
 * <p>
 * 地图只在保存新版本时变化，但旧实现里每台车每个 tick、每次路径规划都要查一次 agv_map 并反序列化
 * 全部障碍/站点 JSON。这里把"当前地图 + 已解析的站点/充电点 + 寻路网格模板"缓存起来：
 * <ul>
 *   <li>TTL（默认 1s）内直接返回缓存；</li>
 *   <li>TTL 过期后只查一次版本指纹（mapId / version / updatedAt，不含大字段），指纹没变继续用缓存；</li>
 *   <li>保存地图后调用 {@link #invalidate()} 立即失效。</li>
 * </ul>
 * 返回的 {@link Snapshot} 及其中的对象是共享的只读数据，调用方不得修改；
 * 需要改网格时用 {@code gridTemplate().copy()}。
 */
@Component
public class MapCache {

    /**
     * @param map          当前地图（只读）
     * @param stations     已转换为强类型的站点列表
     * @param chargeNodes  充电站坐标
     * @param gridTemplate 寻路网格模板：障碍与站点为不可通行，其余可通行
     */
    public record Snapshot(AgvMap map, List<AgvMap.Station> stations, List<Node> chargeNodes, GridMap gridTemplate) {
    }

    private final AgvMapMapper agvMapMapper;
    private final AgvConversion agvConversion;
    private final long ttlNanos;

    private Snapshot snapshot;
    private long checkedAtNanos;
    private boolean checked;

    public MapCache(AgvMapMapper agvMapMapper, AgvConversion agvConversion,
                    @Value("${agv.sim.map-cache-ttl-ms:1000}") long ttlMs) {
        this.agvMapMapper = agvMapMapper;
        this.agvConversion = agvConversion;
        this.ttlNanos = Math.max(0, ttlMs) * 1_000_000L;
    }

    /** 当前地图快照；没有当前地图时返回 null */
    public synchronized Snapshot current() {
        long now = System.nanoTime();
        if (checked && now - checkedAtNanos < ttlNanos) {
            return snapshot;
        }
        AgvMap fingerprint = agvMapMapper.getCurrentMapVersion();
        if (fingerprint == null) {
            snapshot = null;
        } else if (snapshot == null || !sameVersion(snapshot.map(), fingerprint)) {
            snapshot = load();
        }
        checked = true;
        checkedAtNanos = System.nanoTime();
        return snapshot;
    }

    /** 地图发生写入后调用，下一次读取必定重新加载 */
    public synchronized void invalidate() {
        snapshot = null;
        checked = false;
    }

    private static boolean sameVersion(AgvMap cached, AgvMap fingerprint) {
        return Objects.equals(cached.getMapId(), fingerprint.getMapId())
                && Objects.equals(cached.getVersion(), fingerprint.getVersion())
                && Objects.equals(cached.getUpdatedAt(), fingerprint.getUpdatedAt());
    }

    private Snapshot load() {
        AgvMap map = agvMapMapper.getCurrentMap();
        if (map == null) {
            return null;
        }
        List<AgvMap.Obstacle> obstacles = agvConversion.convertObstaclesToAgvObstacleObjects(map.getObstacles());
        List<AgvMap.Station> stations = agvConversion.convertStationsToAgvStationObjects(map.getStations());

        GridMap grid = null;
        if (map.getHeight() != null && map.getWidth() != null) {
            grid = new GridMap(false, map.getHeight(), map.getWidth());
            for (AgvMap.Obstacle obstacle : obstacles) {
                if (obstacle.getX() != null && obstacle.getY() != null) {
                    grid.setWeight(obstacle.getX(), obstacle.getY(), -1);
                }
            }
        }
        List<Node> chargeNodes = new ArrayList<>();
        for (AgvMap.Station station : stations) {
            if (station.getX() == null || station.getY() == null) {
                continue;
            }
            if (grid != null) {
                grid.setWeight(station.getX(), station.getY(), -1);
            }
            if ("charge".equals(station.getType())) {
                chargeNodes.add(new Node(station.getX(), station.getY()));
            }
        }
        return new Snapshot(map, Collections.unmodifiableList(stations),
                Collections.unmodifiableList(chargeNodes), grid);
    }
}
