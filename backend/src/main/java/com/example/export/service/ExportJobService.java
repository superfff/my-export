package com.example.export.service;

import com.example.common.PageResult;
import com.example.export.dto.ExportCreateRequest;
import com.example.export.dto.ExportJobQueryDTO;
import com.example.export.dto.ExportJobVO;

import java.nio.file.Path;

/**
 * 异步导出任务业务层。职责：创建（校验/幂等/快照入库）、列表查询、
 * 消费者"抢占→真实执行"（PENDING→RUNNING→真实导出→SUCCESS/FAILED）。
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

    /**
     * 导出任务分页查询（导出中心列表）。
     * status 不传 = 全部；固定按创建时间降序 + id 降序，保证分页稳定。
     *
     * @param query 列表查询参数（status 可选；page/pageSize 缺省非法归一 1 / 20）
     * @return 分页结果，行对象复用 {@link ExportJobVO}
     */
    PageResult<ExportJobVO> page(ExportJobQueryDTO query);

    /**
     * 抢占并开始本次执行：乐观锁（状态即版本）把 export_jobs 由 PENDING 置 RUNNING；
     * 抢占成功才在同一事务回写 outbox attempt_count + 插入一条 status=RUNNING 的 export_job_attempt。
     * 由 @Transactional 保证两处状态（任务级 + 本次执行级）同生同灭。
     *
     * @param eventId outbox_events.id（消息体携带）
     * @param jobId   export_jobs.id（消息体携带）
     * @return true=本消费者抢占成功；false=未抢占到（重复投递/已非 PENDING），调用方应 ack 丢弃
     */
    boolean claim(long eventId, long jobId);

    /**
     * 真实执行（仅 {@link #claim} 返回 true 后调用，方法内不再做抢占）。
     * 按 export_jobs 冻结的 scope_snapshot/max_order_id keyset 分批发读 t_order，
     * SXSSF 流式写 excel，每批成功后推进 export_jobs.processed_rows。
     * 方法内部自行保证终态：全部成功→job/attempt=SUCCESS；任一步失败→尝试 job/attempt=FAILED 并落 error_message。
     * 仅当"终态本身也无法落库"（如 DB 完全不可用）才抛出，交由调用方死信/重投兜底。
     *
     * @param jobId export_jobs.id
     */
    void executeExport(long jobId);

    /** 下载源（已通过全部校验）：file 为 store.resolve 之后的绝对路径（已在受控 root 内）， */
    record DownloadSource(Path file, String filename) {
    }

    /**
     * 下载前的定位与校验（只读，不落任何状态）。
     * 仅 SUCCESS 提供；其余状态/文件缺失/路径越界一律抛 BizException（真实 4xx + envelope），
     * 由调用方（controller）流式返回文件。
     *
     * @param jobId export_jobs.id
     * @return 越界校验后的文件绝对路径 + 对外文件名
     */
    DownloadSource downloadSource(long jobId);

    /**
     * 重试失败导出任务：仅 FAILED 可重试。同事务把 job FAILED→PENDING（processed_rows 归零、写序号 +1），
     * 并复用同一 outbox 行置 published_at=NULL 交由 dispatcher 重投开新 attempt。
     * job 不存在 → 404；非 FAILED（PENDING/RUNNING/SUCCESS/EXPIRED）或并发抢占失败 → 409。
     *
     * @param jobId export_jobs.id
     * @return 重试后（PENDING）的导出任务 VO
     */
    ExportJobVO retry(long jobId);
}
