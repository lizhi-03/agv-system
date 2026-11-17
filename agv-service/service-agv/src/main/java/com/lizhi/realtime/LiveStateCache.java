package com.lizhi.realtime;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.lizhi.domain.po.AgvMap;
import com.lizhi.domain.po.AgvPath;
import com.lizhi.domain.po.AgvSnapshot;
import com.lizhi.mapper.AgvMapper;
import com.lizhi.mapper.AgvPathMapper;
import com.lizhi.utils.MapCache;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.BeanUtils;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

/**
 * 实时状态内存缓存（V2.1）。
 * <p>
 * 由 stepJob 每秒 tick 后调用 {@link #rebuild()} 重建一次快照；所有读接口（/agv/list、/map/current、
 * /agv/live、SSE）都从内存读取，客户端请求不再打 DB。写接口在写库后同步调用 {@link #rebuild()}，
 * 保证“写后立刻读”能读到新状态（R-V2-08）。
 * <p>
 * 每次重建计算内容指纹 etag：内容未变时 etag 不变，/agv/live 可返回 304、SSE 不推帧。
 */
@Slf4j
@Component
public class LiveStateCache {

    private final AgvMapper agvMapper;
    private final AgvPathMapper agvPathMapper;
    private final MapCache mapCache;
    private final ObjectMapper objectMapper;

    /** 变更监听（SSE 推送用），在锁外通知，避免慢客户端阻塞 tick / 写请求 */
    private final List<Consumer<LiveState>> listeners = new CopyOnWriteArrayList<>();

    private volatile LiveState state;
    private volatile List<AgvSnapshot> cars = Collections.emptyList();
    private volatile AgvMap currentMap;
    private volatile String mapEtag = "\"map-0-0\"";

    public LiveStateCache(AgvMapper agvMapper, AgvPathMapper agvPathMapper,
                          MapCache mapCache, ObjectMapper objectMapper) {
        this.agvMapper = agvMapper;
        this.agvPathMapper = agvPathMapper;
        this.mapCache = mapCache;
        this.objectMapper = objectMapper;
    }

    public void addChangeListener(Consumer<LiveState> listener) {
        if (listener != null) {
            listeners.add(listener);
        }
    }

    /** 当前实时快照（缓存为空时同步重建） */
    public LiveState getLiveState() {
        LiveState current = state;
        if (current == null) {
            rebuild();
            current = state;
        }
        return current;
    }

    /** /agv/list 使用的车辆列表（含 agvPaths，保持旧响应结构） */
    public List<AgvSnapshot> getCars() {
        if (state == null) {
            rebuild();
        }
        return cars;
    }

    /** /map/current 使用的地图（含 paths，保持旧响应结构） */
    public AgvMap getCurrentMap() {
        if (state == null) {
            rebuild();
        }
        return currentMap;
    }

    /** 地图版本指纹，用于 /map/current 的 304 协商 */
    public String getMapEtag() {
        if (state == null) {
            rebuild();
        }
        return mapEtag;
    }

    /**
     * 重新从数据库构建快照。
     *
     * @return true 表示内容（etag）发生变化，需要推送 SSE 帧
     */
    public boolean rebuild() {
        LiveState changed = null;
        boolean changedFlag;
        synchronized (this) {
            changedFlag = doRebuild();
            if (changedFlag) {
                changed = state;
            }
        }
        if (changedFlag && changed != null) {
            for (Consumer<LiveState> listener : listeners) {
                try {
                    listener.accept(changed);
                } catch (Exception e) {
                    log.warn("实时状态变更监听处理失败: {}", e.getMessage());
                }
            }
        }
        return changedFlag;
    }

    /** 调用方需持有 this 锁 */
    @SuppressWarnings("unchecked")
    private boolean doRebuild() {
        List<AgvSnapshot> freshCars = agvMapper.getAllAgv();
        if (freshCars == null) {
            freshCars = new ArrayList<>();
        }
        freshCars.sort(Comparator.comparing(AgvSnapshot::getAgvCode,
                Comparator.nullsLast(Comparator.naturalOrder())));

        List<AgvPath> allPaths = agvPathMapper.selectList(null);
        if (allPaths == null) {
            allPaths = new ArrayList<>();
        }
        allPaths.sort(Comparator
                .comparing(AgvPath::getAgvCode, Comparator.nullsLast(Comparator.naturalOrder()))
                .thenComparing(AgvPath::getPathId, Comparator.nullsLast(Comparator.naturalOrder())));

        Map<String, List<AgvPath>> pathsByAgv = new HashMap<>();
        for (AgvPath path : allPaths) {
            if (path.getAgvCode() == null) {
                continue;
            }
            pathsByAgv.computeIfAbsent(path.getAgvCode(), k -> new ArrayList<>()).add(path);
        }
        for (AgvSnapshot car : freshCars) {
            car.setAgvPaths(pathsByAgv.getOrDefault(car.getAgvCode(), new ArrayList<>()));
        }

        // 地图取缓存（只在版本变化时才重新查库、解析障碍/站点 JSON）；
        // 缓存对象是共享只读的，这里浅拷贝一份再回填 paths
        MapCache.Snapshot mapSnapshot = mapCache.current();
        AgvMap map = null;
        if (mapSnapshot != null) {
            map = new AgvMap();
            BeanUtils.copyProperties(mapSnapshot.map(), map);
        }
        if (map != null) {
            // 与旧 /map/current 行为一致：只回填当前地图的占用路径
            List<AgvPath> mapPaths = new ArrayList<>();
            for (AgvPath path : allPaths) {
                if (Objects.equals(path.getMapId(), map.getMapId())) {
                    mapPaths.add(path);
                }
            }
            map.setPaths(mapPaths);
        }

        LiveState next = new LiveState();
        next.setMap(LiveState.MapInfo.from(map));
        List<LiveState.CarState> carStates = new ArrayList<>(freshCars.size());
        for (AgvSnapshot car : freshCars) {
            carStates.add(LiveState.CarState.from(car));
        }
        next.setCars(carStates);
        List<LiveState.PathPoint> pathPoints = new ArrayList<>(allPaths.size());
        for (AgvPath path : allPaths) {
            pathPoints.add(LiveState.PathPoint.from(path));
        }
        next.setPaths(pathPoints);

        String etag = computeEtag(next);
        boolean changed = state == null || !Objects.equals(state.getEtag(), etag);
        next.setEtag(etag);
        next.setTimestamp(Instant.now().toString());

        this.cars = Collections.unmodifiableList(freshCars);
        this.currentMap = map;
        if (map != null) {
            this.mapEtag = "\"map-" + map.getMapId() + "-" + map.getVersion() + "\"";
        }
        this.state = next;
        return changed;
    }

    /** 计算内容指纹（不含 timestamp/etag 本身，避免每 tick 必变） */
    private String computeEtag(LiveState next) {
        next.setTimestamp(null);
        next.setEtag(null);
        String json;
        try {
            json = objectMapper.writeValueAsString(next);
        } catch (Exception e) {
            json = String.valueOf(Objects.hash(next.getMap(), next.getCars(), next.getPaths()));
        }
        return "\"" + sha256(json).substring(0, 32) + "\"";
    }

    private static String sha256(String text) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] bytes = digest.digest(text.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(bytes.length * 2);
            for (byte b : bytes) {
                sb.append(Character.forDigit((b >> 4) & 0xF, 16));
                sb.append(Character.forDigit(b & 0xF, 16));
            }
            return sb.toString();
        } catch (Exception e) {
            return Integer.toHexString(text.hashCode());
        }
    }
}
