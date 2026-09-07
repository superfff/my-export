package com.example.export.recovery;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.example.export.entity.ExportJob;
import com.example.export.entity.ExportJobAttempt;
import com.example.export.enums.ExportJobStatus;
import com.example.export.mapper.ExportJobAttemptMapper;
import com.example.export.mapper.ExportJobMapper;
import com.example.export.support.ExportFileStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;

/**
 * 导出异常恢复扫描器：与 outbox dispatcher 职责分开的两个周期任务。
 *
 * <ol>
 *   <li><b>租约回收</b>（约 60s）：扫 {@code RUNNING AND (lease IS NULL OR lease &lt; NOW())} —— worker 心跳停止
 *       超过租约时长的卡死任务。每行同一事务置 FAILED（job + RUNNING attempt 两处，CAS 防与真实终态竞争），
 *       事务落定后删本任务目录（清遗留 .tmp 与"已发布未回写"的 .xlsx）。<b>只置 FAILED、不重投 outbox</b>，
 *       新执行由人工点"重试"触发。写序号 +1 让 FAILED 快照成为"更大版本"供读者识别。</li>
 *   <li><b>过期回收</b>（约 1h）：扫 {@code SUCCESS AND expires_at &lt;= NOW()}，<b>先删文件再置 EXPIRED</b>，
 *       置状态走 status+expires_at CAS 并写序号 +1。attempt 表不落 EXPIRED（SUCCESS attempt 是执行历史，保留）。</li>
 * </ol>
 */
@Component
public class ExportRecoveryScanner {

    private static final Logger log = LoggerFactory.getLogger(ExportRecoveryScanner.class);

    /** 单轮每批处理行数：内部循环到查空为止，长事务拆成每行一个短事务 */
    private static final int PAGE_SIZE = 50;

    private static final String MSG_LEASE_EXPIRED = "执行租约过期(心跳停止),由扫描回收";

    private final ExportJobMapper exportJobMapper;
    private final ExportJobAttemptMapper exportJobAttemptMapper;
    private final TransactionTemplate transactionTemplate;
    private final ExportFileStore store;

    public ExportRecoveryScanner(ExportJobMapper exportJobMapper,
                                 ExportJobAttemptMapper exportJobAttemptMapper,
                                 PlatformTransactionManager transactionManager,
                                 @Value("${export.file-dir:./data/export}") String fileDir) {
        this.exportJobMapper = exportJobMapper;
        this.exportJobAttemptMapper = exportJobAttemptMapper;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
        this.store = new ExportFileStore(fileDir);
    }

    /** 租约回收：RUNNING 且无租约或租约已过期的卡死任务 → job/attempt 同事务置 FAILED → 删任务目录。 */
    @Scheduled(fixedDelay = 60_000, initialDelay = 30_000)
    public void reclaimExpiredLeases() {
        while (true) {
            List<ExportJob> stale = exportJobMapper.selectList(new LambdaQueryWrapper<ExportJob>()
                    .eq(ExportJob::getStatus, ExportJobStatus.RUNNING.name())
                    .and(w -> w.isNull(ExportJob::getLease).or().apply("lease < NOW()"))
                    .orderByAsc(ExportJob::getId)
                    .last("LIMIT " + PAGE_SIZE));
            if (stale.isEmpty()) {
                return;
            }
            int handled = 0;
            for (ExportJob job : stale) {
                try {
                    handled += reclaimOne(job) ? 1 : 0;
                } catch (Exception e) {
                    // 单行失败(竞争/瞬时异常)不影响其它行
                    log.warn("租约回收单行失败(跳过): jobId={}, reason={}", job.getId(), e.getMessage());
                }
            }
            if (stale.size() < PAGE_SIZE || handled == 0) {
                return;   // 已扫空 / 满批却零处置(都已被并发接管)→ 终止本轮，防单次调用空转
            }
        }
    }

    /** @return true = job CAS 命中并已置 FAILED + 清目录；false = 行已被并发处置 */
    private boolean reclaimOne(ExportJob job) {
        long jobId = job.getId();
        transactionTemplate.executeWithoutResult(tx -> {
            // attempt 先落 FAILED（本任务当前 RUNNING 那次执行 = 卡死执行）
            exportJobAttemptMapper.update(null, new LambdaUpdateWrapper<ExportJobAttempt>()
                    .eq(ExportJobAttempt::getJobId, jobId)
                    .eq(ExportJobAttempt::getStatus, ExportJobStatus.RUNNING.name())
                    .set(ExportJobAttempt::getStatus, ExportJobStatus.FAILED.name())
                    .set(ExportJobAttempt::getFinishedAt, java.time.LocalDateTime.now())
                    .set(ExportJobAttempt::getErrorMessage, MSG_LEASE_EXPIRED));
            // job 置 FAILED：status CAS 防与真实终态/并发竞争；0 行 → 抛出让本事务整体回滚
            int updated = exportJobMapper.update(null, new LambdaUpdateWrapper<ExportJob>()
                    .eq(ExportJob::getId, jobId)
                    .eq(ExportJob::getStatus, ExportJobStatus.RUNNING.name())
                    .set(ExportJob::getStatus, ExportJobStatus.FAILED.name())
                    .set(ExportJob::getHeartbeatAt, null)
                    .set(ExportJob::getLease, null)
                    .setSql("job_version = job_version + 1"));
            if (updated == 0) {
                throw new IllegalStateException("租约回收 job CAS 未命中(已被接管/已终态), 回滚: jobId=" + jobId);
            }
        });
        // 事务落定后删本任务目录：卡死 worker 的遗留 .tmp 与"已发布未回写"的 .xlsx 一并清掉
        store.deleteTaskDir(jobId);
        log.info("租约回收: jobId={} 置 FAILED 并清理任务目录", jobId);
        return true;
    }

    /** 过期回收：SUCCESS 且已到过期时间 → 先删文件再置 EXPIRED（写序号 +1）。 */
    @Scheduled(fixedDelay = 3_600_000, initialDelay = 60_000)
    public void expireStaleSuccessFiles() {
        while (true) {
            List<ExportJob> expired = exportJobMapper.selectList(new LambdaQueryWrapper<ExportJob>()
                    .eq(ExportJob::getStatus, ExportJobStatus.SUCCESS.name())
                    .isNotNull(ExportJob::getExpiresAt)
                    .apply("expires_at <= NOW()")
                    .orderByAsc(ExportJob::getExpiresAt)
                    .last("LIMIT " + PAGE_SIZE));
            if (expired.isEmpty()) {
                return;
            }
            int handled = 0;
            for (ExportJob job : expired) {
                try {
                    handled += expireOne(job) ? 1 : 0;
                } catch (Exception e) {
                    log.warn("过期回收单行失败(跳过): jobId={}, reason={}", job.getId(), e.getMessage());
                }
            }
            if (expired.size() < PAGE_SIZE || handled == 0) {
                return;   // 已扫空 / 满批却零处置 → 终止本轮，防单次调用空转
            }
        }
    }

    /** @return true = 已置 EXPIRED（status+expires_at CAS 命中）；false = 已被并发处置 */
    private boolean expireOne(ExportJob job) {
        long jobId = job.getId();
        // 先删文件（照"先删除文件，再进行状态回写"）
        store.deleteTaskDir(jobId);
        // 再置 EXPIRED：status + expires_at 双 CAS 防并发/边界竞争；0 行 = 已被处理
        int updated = exportJobMapper.update(null, new LambdaUpdateWrapper<ExportJob>()
                .eq(ExportJob::getId, jobId)
                .eq(ExportJob::getStatus, ExportJobStatus.SUCCESS.name())
                .eq(ExportJob::getExpiresAt, job.getExpiresAt())
                .set(ExportJob::getStatus, ExportJobStatus.EXPIRED.name())
                .setSql("job_version = job_version + 1"));
        if (updated == 0) {
            log.warn("过期回收 CAS 未命中(已处理/状态已变), 跳过: jobId={}", jobId);
            return false;
        }
        log.info("过期回收: jobId={} 文件已删并置 EXPIRED", jobId);
        return true;
    }
}
