package com.example.order.service;

import com.example.order.dto.ExportCreateRequest;
import com.example.order.dto.ExportJobVO;

/**
 * 异步导出任务业务层。本阶段仅负责：校验、幂等去重、范围快照聚合与入库；
 * 不推进任务状态、不真正导出。
 */
public interface ExportJobService {

    /**
     * 创建导出任务。合法 Key 首次校验通过即入库（状态 PENDING）；
     * 已存在该 Key 时一律 409（request_hash 相同=已有相同导出任务，不同=幂等值冲突）。
     *
     * @param request        导出意图（已按契约解析）
     * @param idempotencyKey 幂等键（调用方 trim 后，非空、≤64）
     * @return 入库后读回的导出任务 VO（含 expectedTotal / maxOrderId）
     */
    ExportJobVO create(ExportCreateRequest request, String idempotencyKey);
}
