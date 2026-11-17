package com.lizhi.job;

import com.lizhi.domain.po.AgvLog;
import com.lizhi.domain.po.AgvSnapshot;
import com.lizhi.mapper.AgvLogMapper;
import com.lizhi.mapper.AgvMapper;
import com.lizhi.realtime.LiveStateCache;
import com.lizhi.service.AgvService;
import com.lizhi.utils.AgvSimulator;
import com.lizhi.utils.TickContext;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Slf4j
@Component
public class stepJob {

    /** 单次 tick 耗时告警阈值：超过后写 warn，便于发现车辆多时的调度瓶颈 */
    private static final long TICK_WARN_MS = 300L;
    /** 触发自动充电的电量阈值 */
    private static final double LOW_BATTERY = 30;

    private final AgvMapper agvMapper;
    private final AgvSimulator agvSimulator;
    private final AgvLogMapper agvLogMapper;
    private final AgvService agvService;
    private final LiveStateCache liveStateCache;
    /** 同一台车两次自动充电尝试的最小间隔，避免无法到达充电站时每秒重试 + 每秒写一条错误日志 */
    private final long autoChargeCooldownMs;
    private final Map<String, Long> lastAutoChargeAttempt = new ConcurrentHashMap<>();

    public stepJob(AgvMapper agvMapper, AgvSimulator agvSimulator, AgvLogMapper agvLogMapper,
                   AgvService agvService, LiveStateCache liveStateCache,
                   @Value("${agv.sim.auto-charge-cooldown-ms:5000}") long autoChargeCooldownMs) {
        this.agvMapper = agvMapper;
        this.agvSimulator = agvSimulator;
        this.agvLogMapper = agvLogMapper;
        this.agvService = agvService;
        this.liveStateCache = liveStateCache;
        this.autoChargeCooldownMs = autoChargeCooldownMs;
    }

    /**
     * 推进所有车辆，并在 tick 结束后重建一次内存实时快照（V2.1）。
     * 客户端读 /agv/live、/agv/list、/map/current 时直接读内存，不再打 DB。
     * <p>
     * 使用 fixedDelay（上一轮结束后再等 1s）而不是 cron：tick 变慢时周期顺延，不会补拍、不会堆积。
     */
    @Scheduled(fixedDelayString = "${agv.sim.tick-delay-ms:1000}")
    public void steploop() {
        long startedAt = System.currentTimeMillis();
        List<AgvSnapshot> agvList = agvMapper.getAllAgv();
        TickContext context = agvSimulator.newTickContext();
        for (AgvSnapshot agvSnapshot : agvList) {
            String agvCode = agvSnapshot.getAgvCode();
            try {
                agvSimulator.step(agvCode, context);
            } catch (Exception e) {
                // 单车失败（事务已回滚）不影响其它车辆和本轮快照重建
                log.error("车辆 {} 本轮推进失败: {}", agvCode, e.getMessage(), e);
            }
            autoChargeIfNeeded(agvSnapshot);
        }
        // 每轮重建一次内存快照：内容变化时 SSE 会推帧，未变化时客户端 /agv/live 走 304
        liveStateCache.rebuild();
        long cost = System.currentTimeMillis() - startedAt;
        if (cost > TICK_WARN_MS) {
            log.warn("stepJob 单次 tick 耗时 {}ms（{} 台车），超过 {}ms 阈值", cost, agvList.size(), TICK_WARN_MS);
        } else {
            log.debug("stepJob tick 完成，{} 台车，耗时 {}ms", agvList.size(), cost);
        }
    }

    /**
     * 低电量自动充电：直接调用本地 Service（旧实现在 tick 线程里通过 Feign 对自己发 HTTP 请求），
     * 并对每台车做冷却，失败后不会每秒重试。
     */
    private void autoChargeIfNeeded(AgvSnapshot agv) {
        if (agv.getBattery() > LOW_BATTERY || !Integer.valueOf(0).equals(agv.getIsCharge())) {
            return;
        }
        String agvCode = agv.getAgvCode();
        long now = System.currentTimeMillis();
        Long last = lastAutoChargeAttempt.get(agvCode);
        if (last != null && now - last < autoChargeCooldownMs) {
            return;
        }
        lastAutoChargeAttempt.put(agvCode, now);
        try {
            agvService.chargeAgv(agvCode);
        } catch (Exception e) {
            log.warn("低电量车辆 {} 自动充电失败，{}ms 后重试: {}", agvCode, autoChargeCooldownMs, e.getMessage());
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
