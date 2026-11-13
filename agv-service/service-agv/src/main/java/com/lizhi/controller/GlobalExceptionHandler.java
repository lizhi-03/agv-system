package com.lizhi.controller;

import com.lizhi.astart.service.NoPathFoundException;
import com.lizhi.result.Result;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

/**
 * 全局异常处理：把业务异常转换为统一响应 {code:204,msg}，避免前端收到裸 500/400。
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
        return Result.error("路径规划失败: " + e.getMessage());
    }

    /**
     * 业务校验异常（参数缺失、车辆无任务、没有充电站、批量加车数量非法等）
     */
    @ExceptionHandler({IllegalArgumentException.class, IllegalStateException.class})
    public Result<Void> handleBusinessException(RuntimeException e) {
        log.warn("业务异常: {}", e.getMessage());
        return Result.error(e.getMessage());
    }

    /**
     * 路径参数类型错误（如 /agv/batch/abc）：统一按业务错误返回 204 + msg。
     */
    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public Result<Void> handleTypeMismatch(MethodArgumentTypeMismatchException e) {
        String msg = "参数 " + e.getName() + " 格式错误: " + e.getValue();
        log.warn("参数类型错误: {}", msg);
        return Result.error(msg);
    }

    /**
     * 请求体缺失或 JSON 解析失败：统一按业务错误返回 204 + msg。
     */
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public Result<Void> handleUnreadableBody(HttpMessageNotReadableException e) {
        log.warn("请求体解析失败: {}", e.getMessage());
        return Result.error("请求体格式错误，请检查 JSON 参数");
    }
}
