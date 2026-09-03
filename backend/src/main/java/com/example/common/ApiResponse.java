package com.example.common;

/**
 * 统一响应包装。约定 code=0 表示成功，非 0 表示失败。
 * traceId 用于日志链路追踪，由 TraceIdFilter 统一注入。
 */
public record ApiResponse<T>(int code, String message, T data, String traceId) {

    private static final ThreadLocal<String> CURRENT_TRACE_ID = new ThreadLocal<>();

    /** 设置当前请求的 traceId（由 Filter 调用） */
    public static void setTraceId(String traceId) {
        CURRENT_TRACE_ID.set(traceId);
    }

    /** 清除当前请求的 traceId（由 Filter 调用） */
    public static void clearTraceId() {
        CURRENT_TRACE_ID.remove();
    }

    /** 获取当前请求的 traceId */
    public static String getTraceId() {
        return CURRENT_TRACE_ID.get();
    }

    public static <T> ApiResponse<T> ok(T data) {
        return new ApiResponse<>(0, "ok", data, getTraceId());
    }

    public static <T> ApiResponse<T> error(int code, String message) {
        return new ApiResponse<>(code, message, null, getTraceId());
    }
}
