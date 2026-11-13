package com.lizhi.controller;


import com.lizhi.domain.dto.AgvMapDTO;
import com.lizhi.domain.po.AgvMap;
import com.lizhi.realtime.HttpCacheUtils;
import com.lizhi.realtime.LiveStateCache;
import com.lizhi.result.Result;
import com.lizhi.service.AgvMapService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Objects;

@RestController
@RequestMapping("/map")
@Slf4j
public class AgvMapController {

    private final AgvMapService agvMapService;
    private final LiveStateCache liveStateCache;

    public AgvMapController(AgvMapService agvMapService, LiveStateCache liveStateCache) {
        this.agvMapService = agvMapService;
        this.liveStateCache = liveStateCache;
    }

    /**
     * 保存地图信息
     * @return
     */
    @PostMapping("/save")
    public Result<AgvMap> saveMap(@RequestBody AgvMapDTO agvMapDTO) {
        return agvMapService.saveMap(agvMapDTO);
    }

    /**
     * 获取当前地图信息（V2.1：读内存快照）。
     * <p>
     * 响应结构与旧版完全一致（Result&lt;AgvMap&gt;）；额外支持两种 304 协商，便于前端避免重复下载全量地图：
     * <ul>
     *   <li>{@code If-None-Match: <ETag>} 与当前地图指纹一致 → HTTP 304 无 body；</li>
     *   <li>{@code ?version=<当前版本>} → HTTP 304 无 body。</li>
     * </ul>
     * 不传协商头/参数时永远返回 200，旧前端不回归。
     */
    @GetMapping("/current")
    public ResponseEntity<Result<AgvMap>> getCurrentMap(
            @RequestHeader(value = HttpHeaders.IF_NONE_MATCH, required = false) String ifNoneMatch,
            @RequestParam(value = "version", required = false) Integer version) {
        AgvMap agvMap = agvMapService.getCurrentMap();
        String mapEtag = liveStateCache.getMapEtag();
        boolean versionMatched = version != null && agvMap != null && Objects.equals(version, agvMap.getVersion());
        boolean etagMatched = HttpCacheUtils.matchesEtag(ifNoneMatch, mapEtag);
        if (agvMap != null && (versionMatched || etagMatched)) {
            return ResponseEntity.status(HttpStatus.NOT_MODIFIED)
                    .header(HttpHeaders.ETAG, mapEtag)
                    .cacheControl(CacheControl.noCache().noTransform())
                    .build();
        }
        return ResponseEntity.ok()
                .header(HttpHeaders.ETAG, mapEtag)
                .header(HttpHeaders.VARY, HttpHeaders.IF_NONE_MATCH)
                .cacheControl(CacheControl.noCache().noTransform())
                .body(Result.success(agvMap));
    }

    /**
     * 重置地图（返回当前地图）
     */
    @PostMapping("/reset")
    public Result<AgvMap> resetMap(){
        AgvMap agvMap = agvMapService.resetMap();
        return Result.success(agvMap);
    }

    /**
     * 获取当前站点
     */
    @GetMapping("/station")
    public Result<List<AgvMap.Station>> getStation(){
        AgvMap agvMap = agvMapService.getStation();
        return Result.success(agvMap.getStations());
    }
}
