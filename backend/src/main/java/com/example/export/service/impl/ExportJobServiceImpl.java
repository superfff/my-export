package com.example.export.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.example.common.BizException;
import com.example.common.PageResult;
import com.example.export.dto.ExportCreateRequest;
import com.example.export.dto.ExportJobQueryDTO;
import com.example.export.dto.ExportJobVO;
import com.example.export.entity.ExportJob;
import com.example.export.entity.ExportJobAttempt;
import com.example.export.entity.OutboxEvent;
import com.example.export.enums.ExportJobStatus;
import com.example.export.enums.ExportMode;
import com.example.export.mapper.ExportJobAttemptMapper;
import com.example.export.mapper.ExportJobMapper;
import com.example.export.mapper.OutboxEventMapper;
import com.example.export.service.ExportJobService;
import com.example.export.support.OrderExportColumns;
import com.example.order.entity.Order;
import com.example.order.mapper.OrderMapper;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.streaming.SXSSFWorkbook;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.util.StringUtils;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 导出任务业务实现。
 *
 * <p>create：校验 → request_hash → 订单库聚合快照 → 同一事务写 export_jobs(PENDING) + outbox_events(未发布, trace_id)；
 * page：只读分页查询（导出中心列表）；
 * claim：消费者抢占（CAS PENDING→RUNNING，同事务记一条 RUNNING 的 export_job_attempt 并回写 outbox attempt_count）；
 * executeExport：抢占后的真实执行 —— 按冻结的 scope_snapshot/max_order_id keyset 分批发读 t_order，
 * SXSSF 流式写 excel，每批推进 processed_rows，结束时 job/attempt 一并落 SUCCESS/FAILED。
 */
@Service
public class ExportJobServiceImpl implements ExportJobService {

    private static final Logger log = LoggerFactory.getLogger(ExportJobServiceImpl.class);

    /** traceId：TraceIdFilter 写入 MDC 的键 */
    private static final String TRACE_KEY = "traceId";

    private static final int MAX_ID_COUNT = 100;
    private static final ZoneId ZONE = ZoneId.systemDefault();

    private static final String MSG_DUP_CONTENT = "已有相同导出任务";
    private static final String MSG_CONFLICT = "幂等值冲突";

    /** 错误原因落库上限（export_job_attempt.error_message 为 VARCHAR(1000)） */
    private static final int MAX_ERROR_LEN = 1000;

    private final ExportJobMapper exportJobMapper;
    private final ExportJobAttemptMapper exportJobAttemptMapper;
    private final OutboxEventMapper outboxEventMapper;
    private final OrderMapper orderMapper;
    private final ObjectMapper objectMapper;
    private final TransactionTemplate transactionTemplate;

    /** keyset 每批读取行数 */
    private final int batchSize;
    /** SXSSF 内存行窗口 */
    private final int sxssfRowWindow;
    /** 生成 xlsx 保留目录 */
    private final String fileDir;

    public ExportJobServiceImpl(ExportJobMapper exportJobMapper,
                                ExportJobAttemptMapper exportJobAttemptMapper,
                                OutboxEventMapper outboxEventMapper,
                                OrderMapper orderMapper,
                                ObjectMapper objectMapper,
                                PlatformTransactionManager transactionManager,
                                @Value("${export.batch-size:1000}") int batchSize,
                                @Value("${export.sxssf-row-window:100}") int sxssfRowWindow,
                                @Value("${export.file-dir:./data/export}") String fileDir) {
        this.exportJobMapper = exportJobMapper;
        this.exportJobAttemptMapper = exportJobAttemptMapper;
        this.outboxEventMapper = outboxEventMapper;
        this.orderMapper = orderMapper;
        this.objectMapper = objectMapper;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
        this.batchSize = batchSize;
        this.sxssfRowWindow = sxssfRowWindow;
        this.fileDir = fileDir;
    }

    @Override
    @Transactional
    public ExportJobVO create(ExportCreateRequest request, String idempotencyKey) {
        // 0. 捕获当前请求 traceId（供 outbox_events 记录，贯通 创建→投递→消费 日志链）
        String traceId = MDC.get(TRACE_KEY);

        // 1. 归一化 + 校验（非法即抛 400）
        String key = idempotencyKey == null ? null : idempotencyKey.trim();
        if (!StringUtils.hasText(key)) {
            throw new BizException(400, "缺少幂等键 Idempotency-Key");
        }
        if (key.length() > 64) {
            throw new BizException(400, "幂等键长度不能超过 64");
        }

        ExportMode mode = requireMode(request);
        List<String> fields = normalizeFields(request.fields());
        String filename = normalizeFilename(request.filename());
        // id 范围仅对对应 mode 生效并校验，避免无关 mode 的误伤
        List<Long> selectedIds = mode == ExportMode.SELECTED
                ? normalizeSelectedIds(request.selectedIds()) : List.of();
        List<Long> excludedIds = mode == ExportMode.ALL_EXCLUDE
                ? normalizeExcludedIds(request.excludedIds()) : List.of();

        // 2. request_hash：仅列 + 范围（mode/query/ids），不含快照列/文件名/幂等键
        String requestHash = computeHash(fields, mode, request.query(), selectedIds, excludedIds);

        // 3. 只读聚合：按本任务"会导出的行集合"取 count(*) 与 max(id)（单条一致读）
        LambdaQueryWrapper<Order> scopeWrapper = buildScopeWrapper(mode, request.query(), selectedIds, excludedIds);
        Map<String, Object> stats = orderMapper.selectExportScopeStats(scopeWrapper);
        long expectedTotal = ((Number) stats.get("total")).longValue();
        long maxOrderId = ((Number) stats.get("maxId")).longValue();

        // 4. 组装入库（状态恒 PENDING）
        ExportJob job = new ExportJob();
        job.setIdempotencyKey(key);
        job.setRequestHash(requestHash);
        job.setExportMode(mode.name());
        job.setStatus(ExportJobStatus.PENDING.name());
        job.setFilename(filename);
        job.setExportColumns(writeJson(fields));
        job.setScopeSnapshot(writeJson(buildSnapshot(mode, request.query(), selectedIds, excludedIds)));
        job.setExpectedTotal(expectedTotal);
        job.setMaxOrderId(maxOrderId);

        try {
            exportJobMapper.insert(job);
        } catch (DuplicateKeyException e) {
            // 并发 / 顺序重复：唯一索引兜底，重查已存在行按 request_hash 判别两种 409
            respondConflictByKey(key, requestHash, e);
        }

        // 5. outbox：同一事务写 outbox_events(未发布, published_at 空)，保证 job 与事件同生同灭；
        //    insert 失败会整体回滚（job 也不落库），409 分支在 catch 抛异常同样回滚、不留 outbox 残行。
        OutboxEvent event = new OutboxEvent();
        event.setJobId(job.getId());
        event.setTraceId(traceId);
        event.setAttemptCount(0);
        outboxEventMapper.insert(event);

        // 读回以取 DB 默认时间戳，映射 VO
        return toVO(exportJobMapper.selectById(job.getId()));
    }

    @Override
    @Transactional
    public boolean claim(long eventId, long jobId) {
        // 1. 乐观锁抢占（状态即版本，无独立 version 列）：仅 PENDING→RUNNING 且影响行数=1 才算抢到
        int claimed = exportJobMapper.update(null, new LambdaUpdateWrapper<ExportJob>()
                .eq(ExportJob::getId, jobId)
                .eq(ExportJob::getStatus, ExportJobStatus.PENDING.name())
                .set(ExportJob::getStatus, ExportJobStatus.RUNNING.name()));
        if (claimed == 0) {
            // 未抢到：已被其它消费者领取 / 已非 PENDING（重复投递、历史终态）→ 幂等丢弃，不留执行痕迹
            return false;
        }

        // 2. 同一事务：outbox attempt_count +1（导出执行尝试次数），以其为本次 attempt_no
        outboxEventMapper.update(null, new LambdaUpdateWrapper<OutboxEvent>()
                .eq(OutboxEvent::getId, eventId)
                .setSql("attempt_count = attempt_count + 1"));
        OutboxEvent event = outboxEventMapper.selectById(eventId);
        int attemptNo = event == null ? 1 : event.getAttemptCount();

        // 3. 导出动作表记录本次执行：任务级(RUNNING)已在上方 CAS 落定，此处落"本次执行级"状态
        ExportJobAttempt attempt = new ExportJobAttempt();
        attempt.setEventId(eventId);
        attempt.setJobId(jobId);
        attempt.setAttemptNo(attemptNo);
        attempt.setStatus(ExportJobStatus.RUNNING.name());
        attempt.setStartedAt(LocalDateTime.now());
        exportJobAttemptMapper.insert(attempt);

        log.info("导出任务被消费者领取执行: eventId={}, jobId={}, attemptNo={}", eventId, jobId, attemptNo);
        return true;
    }

    @Override
    public void executeExport(long jobId) {
        Path file = Paths.get(fileDir, "export_" + jobId + ".xlsx");
        ExcelFileWriter writer = new ExcelFileWriter(file, sxssfRowWindow);
        long committed = 0L;
        try {
            ExportJob job = exportJobMapper.selectById(jobId);
            if (job == null) {
                throw new IllegalStateException("导出任务不存在: jobId=" + jobId);
            }
            ExportScopeSnapshot snapshot = readSnapshot(job.getScopeSnapshot());
            List<OrderExportColumns> cols = readColumns(job.getExportColumns());

            writer.open();
            writer.writeHeader(cols.stream().map(OrderExportColumns::header).toList());
            committed = writeBatches(writer, job, snapshot, cols);
            writer.close();      // flush 落盘，文件保留
            finalizeJob(jobId, ExportJobStatus.SUCCESS, committed, null);
            log.info("导出执行成功: jobId={}, 实际导出 {} 行, file={}", jobId, committed, file);
        } catch (Throwable t) {
            // best-effort 收尾：释放 SXSSF 临时文件、删除半成品文件，再尝试落 FAILED 终态
            writer.disposeQuietly();
            deleteFileQuietly(file);
            String reason = reasonOf(t);
            log.error("导出执行失败, 尝试落 FAILED 终态: jobId={}, reason={}", jobId, reason, t);
            // 若连终态也落不下（DB 不可用）会在此抛出 → 交由消费者 nack 进死信（见 ExportJobConsumer 注释）
            finalizeJob(jobId, ExportJobStatus.FAILED, committed, reason);
        }
    }

    /**
     * keyset 游标批读 + 逐行写 excel，每成功写一批推进一次 processed_rows 与游标。
     *
     * @return 实际成功写入文件并已提交进度到 DB 的行数
     */
    private long writeBatches(ExcelFileWriter writer, ExportJob job,
                              ExportScopeSnapshot snapshot, List<OrderExportColumns> cols) throws IOException {
        long maxOrderId = job.getMaxOrderId() == null ? Long.MAX_VALUE : job.getMaxOrderId();
        long lastId = 0L;
        long processed = 0L;
        List<Object> values = new ArrayList<>(cols.size());
        while (true) {
            LambdaQueryWrapper<Order> w = buildScopeWrapper(snapshot.mode(), snapshot.query(),
                    snapshot.selectedIds(), snapshot.excludedIds());
            w.gt(Order::getId, lastId)
                    .le(Order::getId, maxOrderId)     // 高水位：不导出 max_order_id 之后新增的行
                    .orderByAsc(Order::getId)
                    .last("LIMIT " + batchSize);
            List<Order> batch = orderMapper.selectList(w);
            if (batch.isEmpty()) {
                break;
            }
            for (Order row : batch) {
                values.clear();
                for (OrderExportColumns column : cols) {
                    values.add(column.value(row));
                }
                writer.writeRow(values);
            }
            lastId = batch.get(batch.size() - 1).getId();
            // 本批全部成功写入后：独立短事务推进进度（updated_at 由 DDL ON UPDATE 自动刷新）
            int size = batch.size();
            exportJobMapper.update(null, new LambdaUpdateWrapper<ExportJob>()
                    .eq(ExportJob::getId, job.getId())
                    .setSql("processed_rows = processed_rows + " + size));
            processed += size;
            if (size < batchSize || lastId >= maxOrderId) {
                break;
            }
        }
        return processed;
    }

    /** 终态回写：job（任务级）+ attempt（本次执行级）在同一事务落定 */
    private void finalizeJob(long jobId, ExportJobStatus status, long processed, String errorMessage) {
        transactionTemplate.executeWithoutResult(tx -> {
            String err = errorMessage == null ? null
                    : errorMessage.length() <= MAX_ERROR_LEN ? errorMessage
                    : errorMessage.substring(0, MAX_ERROR_LEN);
            LocalDateTime now = LocalDateTime.now();
            int attemptUpdated = exportJobAttemptMapper.update(null, new LambdaUpdateWrapper<ExportJobAttempt>()
                    .eq(ExportJobAttempt::getJobId, jobId)
                    .eq(ExportJobAttempt::getStatus, ExportJobStatus.RUNNING.name())   // 本任务当前那次执行
                    .set(ExportJobAttempt::getStatus, status.name())
                    .set(ExportJobAttempt::getFinishedAt, now)
                    .set(ExportJobAttempt::getErrorMessage, err));
            int jobUpdated = exportJobMapper.update(null, new LambdaUpdateWrapper<ExportJob>()
                    .eq(ExportJob::getId, jobId)
                    .eq(ExportJob::getStatus, ExportJobStatus.RUNNING.name())   // 状态即版本，防并发
                    .set(ExportJob::getStatus, status.name())
                    .set(ExportJob::getProcessedRows, processed));
            if (attemptUpdated == 0 || jobUpdated == 0) {
                // 理论不可达（RUNNING 状态由本执行独占）；仍抛出让调用方兜底，避免静默丢终态
                throw new IllegalStateException("导出任务终态回写影响行数为 0: jobId=" + jobId
                        + ", status=" + status + ", attempt=" + attemptUpdated + ", job=" + jobUpdated);
            }
        });
    }

    @Override
    public PageResult<ExportJobVO> page(ExportJobQueryDTO query) {
        // 1. status 规范化校验：trim 后非空才生效，须 ∈ 四枚举；非法抛 IllegalArgumentException（列表错误语义 HTTP 200 + code=400）
        String status = query.status() == null ? null : query.status().trim();
        if (StringUtils.hasText(status)) {
            String upper = status.toUpperCase(Locale.ROOT);
            try {
                ExportJobStatus.valueOf(upper);
            } catch (IllegalArgumentException e) {
                throw new IllegalArgumentException("不支持的状态筛选：" + status
                        + "，仅支持：PENDING、RUNNING、SUCCESS、FAILED");
            }
            status = upper;
        } else {
            status = null;
        }

        // 2. page/pageSize 归一化（缺省 / <1 → 1 / 20）
        long pageNum = query.page() == null || query.page() < 1 ? 1 : query.page();
        long pageSize = query.pageSize() == null || query.pageSize() < 1 ? 20 : query.pageSize();

        // 3. 列表固定按创建时间降序 + id 降序（同刻创建按较新 id 排前，分页稳定）
        LambdaQueryWrapper<ExportJob> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(status != null, ExportJob::getStatus, status);
        wrapper.orderByDesc(ExportJob::getCreatedAt);
        wrapper.orderByDesc(ExportJob::getId);

        // 4. 分页查询并映射 VO（行对象与创建响应共用同一 ExportJobVO，避免口径漂移）
        Page<ExportJob> page = exportJobMapper.selectPage(new Page<>(pageNum, pageSize), wrapper);
        List<ExportJobVO> list = page.getRecords().stream().map(this::toVO).toList();
        return new PageResult<>(list, page.getTotal(), pageNum, pageSize);
    }

    /** 实体 → VO：创建与列表共用同一套映射，避免两处拼 VO 漂移 */
    private ExportJobVO toVO(ExportJob job) {
        return new ExportJobVO(
                job.getId(),
                job.getFilename(),
                ExportMode.valueOf(job.getExportMode()),
                ExportJobStatus.valueOf(job.getStatus()),
                job.getExpectedTotal(),
                job.getProcessedRows() == null ? 0L : job.getProcessedRows(),
                job.getMaxOrderId(),
                job.getCreatedAt()
        );
    }

    // ---------- 校验 / 归一化 ----------

    private ExportMode requireMode(ExportCreateRequest request) {
        if (request.mode() == null) {
            throw new BizException(400, "导出模式 mode 不能为空");
        }
        return request.mode();
    }

    /** fields：trim → 保序去重 → 校验白名单（OrderExportColumns 唯一来源），返回规范化列清单 */
    private List<String> normalizeFields(List<String> fields) {
        if (fields == null || fields.isEmpty()) {
            throw new BizException(400, "导出字段不能为空");
        }
        List<String> normalized = new ArrayList<>();
        for (String field : fields) {
            String f = field == null ? "" : field.trim();
            if (f.isEmpty()) {
                continue;
            }
            if (OrderExportColumns.byKey(f) == null) {
                throw new BizException(400, "包含不支持的导出字段：" + f);
            }
            if (!normalized.contains(f)) {
                normalized.add(f);
            }
        }
        if (normalized.isEmpty()) {
            throw new BizException(400, "导出字段不能为空");
        }
        return normalized;
    }

    private String normalizeFilename(String filename) {
        String name = filename == null ? "" : filename.trim();
        if (name.isEmpty()) {
            throw new BizException(400, "文件名不能为空");
        }
        if (name.length() > 255) {
            throw new BizException(400, "文件名长度不能超过 255");
        }
        return name;
    }

    private List<Long> normalizeSelectedIds(List<Long> selectedIds) {
        List<Long> ids = dedupeSorted(selectedIds);
        if (ids.isEmpty()) {
            throw new BizException(400, "导出已选缺少 selectedIds");
        }
        if (ids.size() > MAX_ID_COUNT) {
            throw new BizException(400, "已选订单数量不能超过 100");
        }
        return ids;
    }

    private List<Long> normalizeExcludedIds(List<Long> excludedIds) {
        List<Long> ids = dedupeSorted(excludedIds);
        if (ids.size() > MAX_ID_COUNT) {
            throw new BizException(400, "排除订单数量不能超过 100");
        }
        return ids;
    }

    /** 去重 + 升序（null 元素忽略），作为 hash 输入与范围快照的统一形式 */
    private List<Long> dedupeSorted(List<Long> ids) {
        if (ids == null) {
            return List.of();
        }
        return ids.stream().filter(id -> id != null).distinct().sorted().toList();
    }

    // ---------- request_hash ----------

    private String computeHash(List<String> fields, ExportMode mode,
                               ExportCreateRequest.Query query, List<Long> selectedIds, List<Long> excludedIds) {
        String fieldsCsv = fields.stream().sorted().reduce((a, b) -> a + "," + b).orElse("");
        String queryString = canonicalQuery(query);
        String scope;
        switch (mode) {
            case SELECTED -> scope = "SELECTED|" + joinCsv(selectedIds);
            case ALL_EXCLUDE -> scope = "ALL_EXCLUDE|" + queryString + "|" + joinCsv(excludedIds);
            default -> scope = "FILTERED|" + queryString;
        }
        String input = fieldsCsv + "#" + scope;
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(input.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 不可用", e);
        }
    }

    /** 固定键序 orderNo,customerName,phone,status,startTime,endTime，取非空拼 k=v&连接 */
    private String canonicalQuery(ExportCreateRequest.Query query) {
        if (query == null) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        appendKv(sb, "orderNo", trimToNull(query.orderNo()));
        appendKv(sb, "customerName", trimToNull(query.customerName()));
        appendKv(sb, "phone", trimToNull(query.phone()));
        appendKv(sb, "status", query.status() == null ? null : String.valueOf(query.status()));
        appendKv(sb, "startTime", query.startTime() == null ? null : String.valueOf(query.startTime()));
        appendKv(sb, "endTime", query.endTime() == null ? null : String.valueOf(query.endTime()));
        return sb.toString();
    }

    private void appendKv(StringBuilder sb, String key, String value) {
        if (value == null || value.isEmpty()) {
            return;
        }
        if (!sb.isEmpty()) {
            sb.append('&');
        }
        sb.append(key).append('=').append(value);
    }

    private String joinCsv(List<Long> ids) {
        return ids.stream().map(String::valueOf).reduce((a, b) -> a + "," + b).orElse("");
    }

    // ---------- 范围聚合谓词（单一来源：create 统计与 execute 批读取共用，防两处漂移） ----------

    private LambdaQueryWrapper<Order> buildScopeWrapper(ExportMode mode, ExportCreateRequest.Query query,
                                                        List<Long> selectedIds, List<Long> excludedIds) {
        LambdaQueryWrapper<Order> wrapper = new LambdaQueryWrapper<>();
        // 仅 FILTERED / ALL_EXCLUDE 受筛选谓词约束
        if (mode != ExportMode.SELECTED && query != null) {
            wrapper.like(StringUtils.hasText(query.orderNo()), Order::getOrderNo, trimToNull(query.orderNo()));
            wrapper.like(StringUtils.hasText(query.customerName()), Order::getCustomerName, trimToNull(query.customerName()));
            wrapper.like(StringUtils.hasText(query.phone()), Order::getPhone, trimToNull(query.phone()));
            wrapper.eq(query.status() != null, Order::getStatus, query.status());
            if (query.startTime() != null) {
                wrapper.ge(Order::getCreatedAt, toLocalDateTime(query.startTime()));
            }
            if (query.endTime() != null) {
                wrapper.le(Order::getCreatedAt, toLocalDateTime(query.endTime()));
            }
        }
        if (mode == ExportMode.SELECTED) {
            wrapper.in(Order::getId, selectedIds);
        } else if (mode == ExportMode.ALL_EXCLUDE && !excludedIds.isEmpty()) {
            wrapper.notIn(Order::getId, excludedIds);
        }
        return wrapper;
    }

    private LocalDateTime toLocalDateTime(long epochMillis) {
        return LocalDateTime.ofInstant(Instant.ofEpochMilli(epochMillis), ZONE);
    }

    // ---------- 快照 / JSON ----------

    /** scope_snapshot 解析载体，与 buildSnapshot 写出的 JSON 结构一致（Jackson 解嵌套 record） */
    private record ExportScopeSnapshot(ExportMode mode,
                                       ExportCreateRequest.Query query,
                                       List<Long> selectedIds,
                                       List<Long> excludedIds) {
    }

    private Map<String, Object> buildSnapshot(ExportMode mode, ExportCreateRequest.Query query,
                                              List<Long> selectedIds, List<Long> excludedIds) {
        Map<String, Object> snapshot = new LinkedHashMap<>();
        snapshot.put("mode", mode.name());
        if (mode != ExportMode.SELECTED) {
            Map<String, Object> queryMap = new LinkedHashMap<>();
            if (query != null) {
                putIfPresent(queryMap, "orderNo", trimToNull(query.orderNo()));
                putIfPresent(queryMap, "customerName", trimToNull(query.customerName()));
                putIfPresent(queryMap, "phone", trimToNull(query.phone()));
                putIfPresent(queryMap, "status", query.status());
                putIfPresent(queryMap, "startTime", query.startTime());
                putIfPresent(queryMap, "endTime", query.endTime());
            }
            snapshot.put("query", queryMap);
        }
        if (mode == ExportMode.SELECTED) {
            snapshot.put("selectedIds", selectedIds);
        } else if (mode == ExportMode.ALL_EXCLUDE) {
            snapshot.put("excludedIds", excludedIds);
        }
        return snapshot;
    }

    private void putIfPresent(Map<String, Object> map, String key, Object value) {
        if (value != null) {
            map.put(key, value);
        }
    }

    private String writeJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("JSON 序列化失败", e);
        }
    }

    private ExportScopeSnapshot readSnapshot(String snapshotJson) {
        if (!StringUtils.hasText(snapshotJson)) {
            throw new IllegalStateException("scope_snapshot 为空, 无法执行导出");
        }
        try {
            return objectMapper.readValue(snapshotJson, ExportScopeSnapshot.class);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("scope_snapshot 解析失败", e);
        }
    }

    /** export_columns(保序 JSON 数组) → 列元数据列表；含白名单外字段即解析失败 → 落 FAILED */
    private List<OrderExportColumns> readColumns(String columnsJson) {
        List<String> keys;
        try {
            keys = objectMapper.readValue(columnsJson, new TypeReference<List<String>>() {
            });
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("export_columns 解析失败", e);
        }
        List<OrderExportColumns> columns = new ArrayList<>(keys.size());
        for (String key : keys) {
            OrderExportColumns column = OrderExportColumns.byKey(key);
            if (column == null) {
                throw new IllegalStateException("export_columns 含白名单外字段: " + key);
            }
            columns.add(column);
        }
        return columns;
    }

    // ---------- 幂等冲突判别 / 工具 ----------

    private void respondConflictByKey(String key, String requestHash, DuplicateKeyException original) {
        ExportJob existing = exportJobMapper.selectOne(
                new LambdaQueryWrapper<ExportJob>().eq(ExportJob::getIdempotencyKey, key));
        if (existing != null) {
            if (requestHash.equals(existing.getRequestHash())) {
                throw new BizException(409, MSG_DUP_CONTENT);
            }
            throw new BizException(409, MSG_CONFLICT);
        }
        // 理论不可达：唯一键冲突必有已存在行
        throw original;
    }

    private String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private String reasonOf(Throwable t) {
        String msg = t.getMessage();
        return StringUtils.hasText(msg) ? msg : t.getClass().getSimpleName();
    }

    private void deleteFileQuietly(Path file) {
        try {
            Files.deleteIfExists(file);
        } catch (IOException e) {
            log.warn("删除导出半成品文件失败: file={}, reason={}", file, e.getMessage());
        }
    }

    /**
     * SXSSF 流式写 xlsx 的小封装：行内存窗口超出自动落临时文件防 OOM，close 时 flush 到目标文件。
     */
    private static final class ExcelFileWriter {
        private final Path file;
        private final int rowWindow;
        private SXSSFWorkbook workbook;
        private Sheet sheet;
        private int rowIndex;

        ExcelFileWriter(Path file, int rowWindow) {
            this.file = file;
            this.rowWindow = rowWindow;
        }

        void open() throws IOException {
            Path parent = file.toAbsolutePath().getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            workbook = new SXSSFWorkbook(rowWindow);
            workbook.setCompressTempFiles(true);
            sheet = workbook.createSheet("订单");
            rowIndex = 0;
        }

        void writeHeader(List<String> headers) {
            Row row = sheet.createRow(rowIndex++);
            for (int i = 0; i < headers.size(); i++) {
                row.createCell(i).setCellValue(headers.get(i));
            }
        }

        void writeRow(List<Object> values) {
            Row row = sheet.createRow(rowIndex++);
            for (int i = 0; i < values.size(); i++) {
                Object value = values.get(i);
                Cell cell = row.createCell(i);
                if (value instanceof Number number) {
                    cell.setCellValue(number.doubleValue());   // 数值单元格（可被 Excel 求和）
                } else {
                    cell.setCellValue((String) value);          // 文本单元格（已净化）
                }
            }
        }

        /** flush 落盘并释放 SXSSF 自身临时文件 */
        void close() throws IOException {
            if (workbook == null) {
                return;
            }
            try (OutputStream out = Files.newOutputStream(file)) {
                workbook.write(out);
            } finally {
                disposeQuietly();
            }
        }

        /** 异常路径释放临时文件，绝不再抛 */
        void disposeQuietly() {
            if (workbook != null) {
                try {
                    workbook.dispose();
                } catch (RuntimeException e) {
                    log.warn("SXSSF 释放临时文件失败: {}", e.getMessage());
                } finally {
                    workbook = null;
                }
            }
        }
    }
}
