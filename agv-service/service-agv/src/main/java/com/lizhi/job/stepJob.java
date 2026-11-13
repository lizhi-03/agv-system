package com.lizhi.job;

import com.lizhi.astart.service.NoPathFoundException;
import com.lizhi.domain.po.AgvLog;
import com.lizhi.domain.po.AgvSnapshot;
import com.lizhi.feign.AgvFeignClient;
import com.lizhi.mapper.AgvLogMapper;
import com.lizhi.mapper.AgvMapper;
import com.lizhi.realtime.LiveStateCache;
import com.lizhi.service.AgvService;
import com.lizhi.utils.AgvCollisionAvoidance;
import com.lizhi.utils.AgvSimulator;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.List;

@Slf4j
@Component
public class stepJob {

    /** 单次 tick 耗时告警阈值：超过后写 warn，便于发现车辆多时的调度瓶颈 */
    private static final long TICK_WARN_MS = 300L;

    private final AgvMapper agvMapper;
    private final AgvSimulator agvSimulator;
    private final AgvFeignClient agvFeignClient;
    private final AgvLogMapper agvLogMapper;
    private final AgvCollisionAvoidance agvCollisionAvoidance;
    private final AgvService agvService;
    private final LiveStateCache liveStateCache;

    public stepJob(AgvMapper agvMapper, AgvSimulator agvSimulator, AgvFeignClient agvFeignClient,
                   AgvLogMapper agvLogMapper, AgvCollisionAvoidance agvCollisionAvoidance,
                   AgvService agvService, LiveStateCache liveStateCache) {
        this.agvMapper = agvMapper;
        this.agvSimulator = agvSimulator;
        this.agvFeignClient = agvFeignClient;
        this.agvLogMapper = agvLogMapper;
        this.agvCollisionAvoidance = agvCollisionAvoidance;
        this.agvService = agvService;
        this.liveStateCache = liveStateCache;
    }

    /**
     * 每秒推进所有车辆，并在 tick 结束后重建一次内存实时快照（V2.1）。
     * 客户端读 /agv/live、/agv/list、/map/current 时直接读内存，不再打 DB。
     */
    @Scheduled(cron = "0/1 * * * * ?")
    public void steploop() throws NoPathFoundException {
        long startedAt = System.currentTimeMillis();
        List<AgvSnapshot> agvList = agvMapper.getAllAgv();
        for (AgvSnapshot agvSnapshot : agvList) {
            agvSimulator.step(agvSnapshot);
            if (agvSnapshot.getBattery() <= 30 && agvSnapshot.getIsCharge() == 0) {
                try {
                    agvFeignClient.chargeAgv(agvSnapshot.getAgvCode());
                } catch (Exception e) {
                    log.warn("低电量车辆 {} 自动充电调用失败: {}", agvSnapshot.getAgvCode(), e.getMessage());
                }
            }
        }
        // 每秒重建一次内存快照：内容变化时 SSE 会推帧，未变化时客户端 /agv/live 走 304
        liveStateCache.rebuild();
        long cost = System.currentTimeMillis() - startedAt;
        if (cost > TICK_WARN_MS) {
            log.warn("stepJob 单次 tick 耗时 {}ms（{} 台车），超过 {}ms 阈值", cost, agvList.size(), TICK_WARN_MS);
        } else {
            log.debug("stepJob tick 完成，{} 台车，耗时 {}ms", agvList.size(), cost);
        }
    }

    /**
     * 检查当前小车的在线数量
     */
    @Scheduled(cron = "0 0 17 * * ?")
    public void agvNumLog() {
        List<AgvSnapshot> agvList = agvMapper.getAllAgv();
        int size = agvList.size();
        AgvLog agvLog = new AgvLog();
        agvLog.setTimestamp(LocalDateTime.now());
        agvLog.setLevel("info");
        agvLog.setMessage("系统启动完成，当前在线AGV: " + size + "辆");
        agvLogMapper.insert(agvLog);
    }
}
