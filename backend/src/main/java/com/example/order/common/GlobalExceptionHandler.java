package com.example.order.common;

import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * 全局异常处理器：将业务异常转换为统一的 ApiResponse 错误格式。
 * 确保前端收到的错误响应也遵循 JSON envelope 规范（包含 traceId）。
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    /**
     * 处理参数校验异常（如排序白名单校验失败）。
     * 返回 HTTP 200 + code=400，让前端能在常规流程中读取错误信息。
     */
    @ExceptionHandler(IllegalArgumentException.class)
    public ApiResponse<Void> handleIllegalArgument(IllegalArgumentException e) {
        return ApiResponse.error(400, e.getMessage());
    }
}
