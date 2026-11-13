package com.lizhi.controller;

import com.lizhi.astart.service.NoPathFoundException;
import com.lizhi.result.Result;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * 全局异常处理：把业务异常转换为统一响应 {code:204,msg}，避免前端收到裸 500。
 */
@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    /**
     * 路径规划失败（A* 无解等）
     */
    @ExceptionHandler(NoPathFoundException.class)
    public Result<Void> handleNoPathFound(NoPathFoundException e) {
        log.warn("路径规划失败: {}", e.getMessage());
        return Result.error("路径规划失败：" + e.getMessage());
    }

    /**
     * 业务校验异常（参数缺失、车辆无任务、没有充电站等）
     */
    @ExceptionHandler({IllegalArgumentException.class, IllegalStateException.class})
    public Result<Void> handleBusinessException(RuntimeException e) {
        log.warn("业务异常: {}", e.getMessage());
        return Result.error(e.getMessage());
    }
}
