package com.example.common;

import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
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

    /**
     * 通用业务异常：按异常携带的 HTTP 状态码（400/409）返回真实状态。
     */
    @ExceptionHandler(BizException.class)
    public ResponseEntity<ApiResponse<Void>> handleBiz(BizException e) {
        return ResponseEntity.status(e.getHttpStatus())
                .body(ApiResponse.error(e.getHttpStatus(), e.getMessage()));
    }

    /**
     * 请求体解析失败（如 JSON 格式错误、枚举取值不合法），给 envelope 化的 400。
     * 仅影响带 JSON body 的请求，无 body 的 GET 接口不受影响。
     */
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ApiResponse<Void>> handleNotReadable(HttpMessageNotReadableException e) {
        return ResponseEntity.badRequest()
                .body(ApiResponse.error(400, "请求体格式错误或字段取值不合法"));
    }
}
