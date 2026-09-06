package com.example.export.controller;

import com.example.common.ApiResponse;
import com.example.common.BizException;
import com.example.common.PageResult;
import com.example.export.dto.ExportCreateRequest;
import com.example.export.dto.ExportJobQueryDTO;
import com.example.export.dto.ExportJobVO;
import com.example.export.service.ExportJobService;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

/**
 * 异步导出任务 RESTful 接口。创建（POST）与列表（GET）共用同一资源 /api/export-job。
 * 创建：幂等 Key 缺失 / 长度等校验在 Service 层给清晰 400；
 * 列表：只读分页，status 非法抛 IllegalArgumentException → HTTP 200 + code=400。
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

    /**
     * 导出任务分页查询（导出中心列表）。
     * GET /api/export-job?status=PENDING&page=1&pageSize=20
     * status 不传 = 全部；列表不支持 sortField/sortOrder，固定按创建时间降序 + id 降序。
     */
    @GetMapping
    public ApiResponse<PageResult<ExportJobVO>> list(ExportJobQueryDTO query) {
        return ApiResponse.ok(exportJobService.page(query));
    }

    /**
     * 下载成功导出文件：GET /api/export-job/{id}/download，仅 SUCCESS 任务有正确文件。
     * 定位/状态/越界校验在 service（已收敛为 BizException）；此处只负责编码文件名并流式返回。
     */
    @GetMapping("/{id}/download")
    public ResponseEntity<Resource> download(@PathVariable("id") Long id) {
        ExportJobService.DownloadSource src = exportJobService.downloadSource(id);
        FileSystemResource resource = new FileSystemResource(src.file());
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.parseMediaType(
                "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"));
        // R0/R1：下载名 = 库中 filename 原样；编码交给 Spring 生成 filename*/filename（绝不手拼，防 CRLF/乱码）
        headers.setContentDisposition(
                ContentDisposition.attachment().filename(src.filename(), StandardCharsets.UTF_8).build());
        long length;
        try {
            length = resource.contentLength();
        } catch (IOException e) {
            throw new BizException(500, "读取导出文件失败");
        }
        return ResponseEntity.ok().headers(headers).contentLength(length).body(resource);
    }
}
