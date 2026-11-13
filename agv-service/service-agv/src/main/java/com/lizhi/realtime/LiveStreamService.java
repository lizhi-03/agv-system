package com.lizhi.realtime;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * SSE 实时推送（V2.1）。
 * <ul>
 *   <li>状态变化才推帧（LiveStateCache 的 etag 变化），每个 tick 最多一帧；</li>
 *   <li>15s 心跳，保持网关/代理连接不被判定空闲；</li>
 *   <li>断开/超时/异常自动移除连接。</li>
 * </ul>
 * 推送在单线程 executor 中串行执行，避免慢客户端阻塞 stepJob 与写请求。
 */
@Slf4j
@Component
public class LiveStreamService {

    private final LiveStateCache liveStateCache;
    private final ObjectMapper objectMapper;

    private final Set<SseEmitter> emitters = ConcurrentHashMap.newKeySet();
    private final AtomicInteger connections = new AtomicInteger();

    private final ExecutorService broadcaster = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "live-sse-broadcast");
        thread.setDaemon(true);
        return thread;
    });

    public LiveStreamService(LiveStateCache liveStateCache, ObjectMapper objectMapper) {
        this.liveStateCache = liveStateCache;
        this.objectMapper = objectMapper;
        // 缓存内容变化 → 异步推帧
        this.liveStateCache.addChangeListener(this::broadcastAsync);
    }

    /** 新建一个 SSE 连接：先推当前快照，之后按变化推送。
     *  设置 30 分钟安全超时：半开连接（如客户端被强杀未发 FIN）最迟在超时后被移除；
     *  正常浏览器 EventSource 会自动重连，15s 心跳保证连接活跃。 */
    public SseEmitter subscribe() {
        SseEmitter emitter = new SseEmitter(30 * 60 * 1000L);
        emitter.onCompletion(() -> removeEmitter(emitter, "completion"));
        emitter.onTimeout(() -> removeEmitter(emitter, "timeout"));
        emitter.onError(error -> removeEmitter(emitter, "error"));
        emitters.add(emitter);
        connections.incrementAndGet();
        LiveState current = liveStateCache.getLiveState();
        broadcaster.execute(() -> sendState(emitter, current));
        return emitter;
    }

    public int getConnectionCount() {
        return connections.get();
    }

    private void removeEmitter(SseEmitter emitter, String reason) {
        if (emitters.remove(emitter)) {
            connections.decrementAndGet();
            log.debug("SSE 连接移除({})，当前连接数={}", reason, connections.get());
        }
    }

    /** etag 变化 → 异步广播一帧 */
    private void broadcastAsync(LiveState state) {
        if (emitters.isEmpty()) {
            return;
        }
        broadcaster.execute(() -> {
            for (SseEmitter emitter : emitters) {
                sendState(emitter, state);
            }
        });
    }

    private void sendState(SseEmitter emitter, LiveState state) {
        if (state == null) {
            return;
        }
        try {
            String json = objectMapper.writeValueAsString(state);
            synchronized (emitter) {
                emitter.send(SseEmitter.event()
                        .name("state")
                        .data(json, MediaType.APPLICATION_JSON));
            }
        } catch (Exception e) {
            // 客户端断开/写失败：移除连接，不向上抛
            removeEmitter(emitter, "send-error");
        }
    }

    /** 15s 心跳（SSE 注释行，浏览器不触发 message 事件，仅保活） */
    @Scheduled(fixedRate = 15000)
    public void heartbeat() {
        if (emitters.isEmpty()) {
            return;
        }
        for (SseEmitter emitter : emitters) {
            try {
                synchronized (emitter) {
                    emitter.send(SseEmitter.event().comment("heartbeat"));
                }
            } catch (Exception e) {
                removeEmitter(emitter, "heartbeat-error");
            }
        }
    }
}
