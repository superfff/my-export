package com.example.common;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.MDC;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.UUID;

/**
 * traceId 过滤器：为每个请求分配唯一的 traceId，用于日志链路追踪。
 * <ul>
 *   <li>如果 MDC 中已存在 traceId（如上游服务传入），则复用</li>
 *   <li>如果 MDC 中不存在 traceId，则生成新的 UUID</li>
 *   <li>将 traceId 写入 MDC（供日志框架使用）、响应头 x-trace-id、以及 ApiResponse 包装</li>
 * </ul>
 */
public class TraceIdFilter extends OncePerRequestFilter {

    private static final String TRACE_ID_KEY = "traceId";
    private static final String HEADER_NAME = "x-trace-id";

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        try {
            // 1. 判断 MDC 中是否已有 traceId
            String traceId = MDC.get(TRACE_ID_KEY);

            // 2. 如果没有，生成新的 UUID
            if (traceId == null || traceId.isBlank()) {
                traceId = UUID.randomUUID().toString();
                MDC.put(TRACE_ID_KEY, traceId);
            }

            // 3. 设置到 ApiResponse 的 ThreadLocal，让 ok()/error() 自动携带
            ApiResponse.setTraceId(traceId);

            // 4. 写入响应头
            response.setHeader(HEADER_NAME, traceId);

            filterChain.doFilter(request, response);
        } finally {
            // 5. 请求结束后清理，防止线程复用时泄漏
            ApiResponse.clearTraceId();
            MDC.remove(TRACE_ID_KEY);
        }
    }
}
