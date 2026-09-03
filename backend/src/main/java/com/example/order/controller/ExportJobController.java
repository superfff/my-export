package com.example.order.controller;

import com.example.order.common.ApiResponse;
import com.example.order.dto.ExportCreateRequest;
import com.example.order.dto.ExportJobVO;
import com.example.order.service.ExportJobService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 异步导出任务 RESTful 接口。
 * 本阶段仅提供创建；幂等 Key 缺失 / 长度等校验在 Service 层给清晰 400。
 */
@RestController
@RequestMapping("/api/export-job")
public class ExportJobController {

    private final ExportJobService exportJobService;

    public ExportJobController(ExportJobService exportJobService) {
        this.exportJobService = exportJobService;
    }

    /**
     * 创建导出任务：校验 + 幂等去重 + 范围快照聚合 + 入库（PENDING）。
     * 返回真实 HTTP 200；幂等重复 409、校验失败 400（由全局异常处理器按 envelope 返回）。
     */
    @PostMapping
    public ResponseEntity<ApiResponse<ExportJobVO>> create(
            @RequestBody ExportCreateRequest request,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey) {
        ExportJobVO vo = exportJobService.create(request, idempotencyKey);
        return ResponseEntity.ok(ApiResponse.ok(vo));
    }
}
