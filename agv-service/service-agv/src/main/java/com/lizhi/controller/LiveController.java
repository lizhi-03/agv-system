package com.lizhi.controller;

import com.lizhi.realtime.HttpCacheUtils;
import com.lizhi.realtime.LiveState;
import com.lizhi.realtime.LiveStateCache;
import com.lizhi.realtime.LiveStreamService;
import com.lizhi.result.Result;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * V2.1 实时数据通道：
 * <ul>
 *   <li>{@code GET /agv/live}：实时快照（内存），支持 If-None-Match → 304；</li>
 *   <li>{@code GET /agv/stream}：SSE，状态变化才推帧（≤1 帧/s），15s 心跳；</li>
 *   <li>{@code GET /agv/stream/status}：当前 SSE 连接数。</li>
 * </ul>
 * 注意：/agv/live 返回裸快照对象（{timestamp, etag, map, cars, paths}），不套 Result 壳，
 * 便于前端 EventSource/轮询共用同一份数据结构。
 */
@RestController
@RequestMapping("/agv")
public class LiveController {

    private final LiveStateCache liveStateCache;
    private final LiveStreamService liveStreamService;

    public LiveController(LiveStateCache liveStateCache, LiveStreamService liveStreamService) {
        this.liveStateCache = liveStateCache;
        this.liveStreamService = liveStreamService;
    }

    /**
     * 实时快照。带 If-None-Match 且内容未变时返回 HTTP 304（无 body）；未知/缺失 etag 返回 200。
     */
    @GetMapping("/live")
    public ResponseEntity<LiveState> live(
            @RequestHeader(value = HttpHeaders.IF_NONE_MATCH, required = false) String ifNoneMatch) {
        LiveState state = liveStateCache.getLiveState();
        String etag = state == null ? null : state.getEtag();
        if (etag != null && HttpCacheUtils.matchesEtag(ifNoneMatch, etag)) {
            return ResponseEntity.status(HttpStatus.NOT_MODIFIED)
                    .header(HttpHeaders.ETAG, etag)
                    .cacheControl(CacheControl.noCache().noTransform())
                    .build();
        }
        return ResponseEntity.ok()
                .header(HttpHeaders.ETAG, etag)
                .header(HttpHeaders.VARY, HttpHeaders.IF_NONE_MATCH)
                .cacheControl(CacheControl.noCache().noTransform())
                .body(state);
    }

    /**
     * SSE 实时推送。事件：event: state + data: LiveState JSON；15s 心跳为注释行。
     */
    @GetMapping(value = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public ResponseEntity<SseEmitter> stream() {
        SseEmitter emitter = liveStreamService.subscribe();
        return ResponseEntity.ok()
                .contentType(MediaType.TEXT_EVENT_STREAM)
                .header(HttpHeaders.CACHE_CONTROL, "no-cache, no-transform")
                .header(HttpHeaders.CONNECTION, "keep-alive")
                // 关闭 nginx 等反向代理的响应缓冲，保证 SSE 帧实时到达
                .header("X-Accel-Buffering", "no")
                .body(emitter);
    }

    /** SSE 连接数 */
    @GetMapping("/stream/status")
    public Result<Map<String, Object>> streamStatus() {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("connections", liveStreamService.getConnectionCount());
        data.put("timestamp", Instant.now().toString());
        data.put("heartbeatSeconds", 15);
        return Result.success(data);
    }
}
