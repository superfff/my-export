package com.example.order.common;

/**
 * 导出业务异常：携带期望的 HTTP 状态码与用户可读文案。
 * 由 GlobalExceptionHandler 捕获后按真实 HTTP 状态返回 envelope。
 */
public class ExportBizException extends RuntimeException {

    private final int httpStatus;

    public ExportBizException(int httpStatus, String message) {
        super(message);
        this.httpStatus = httpStatus;
    }

    public int getHttpStatus() {
        return httpStatus;
    }
}
