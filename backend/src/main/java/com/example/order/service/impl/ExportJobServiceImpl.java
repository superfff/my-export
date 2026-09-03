package com.example.order.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.example.order.common.ExportBizException;
import com.example.order.dto.ExportCreateRequest;
import com.example.order.dto.ExportJobVO;
import com.example.order.entity.ExportJob;
import com.example.order.entity.Order;
import com.example.order.enums.ExportJobStatus;
import com.example.order.enums.ExportMode;
import com.example.order.mapper.ExportJobMapper;
import com.example.order.mapper.OrderMapper;
import com.example.order.service.ExportJobService;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 导出任务业务实现。
 * 单方法 create：校验 → request_hash → 订单库聚合快照 → 入库（捕获唯一键冲突按幂等规则返回 409）。
 */
@Service
public class ExportJobServiceImpl implements ExportJobService {

    /** 导出列白名单（与订单列表展示列一致） */
    private static final Set<String> COLUMN_WHITELIST =
            Set.of("orderNo", "customerName", "phone", "amount", "status", "createdAt");

    private static final int MAX_ID_COUNT = 100;
    private static final ZoneId ZONE = ZoneId.systemDefault();

    private static final String MSG_DUP_CONTENT = "已有相同导出任务";
    private static final String MSG_CONFLICT = "幂等值冲突";

    private final ExportJobMapper exportJobMapper;
    private final OrderMapper orderMapper;
    private final ObjectMapper objectMapper;

    public ExportJobServiceImpl(ExportJobMapper exportJobMapper, OrderMapper orderMapper, ObjectMapper objectMapper) {
        this.exportJobMapper = exportJobMapper;
        this.orderMapper = orderMapper;
        this.objectMapper = objectMapper;
    }

    @Override
    public ExportJobVO create(ExportCreateRequest request, String idempotencyKey) {
        // 1. 归一化 + 校验（非法即抛 400）
        String key = idempotencyKey == null ? null : idempotencyKey.trim();
        if (!StringUtils.hasText(key)) {
            throw new ExportBizException(400, "缺少幂等键 Idempotency-Key");
        }
        if (key.length() > 64) {
            throw new ExportBizException(400, "幂等键长度不能超过 64");
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

        // 读回以取 DB 默认时间戳，映射 VO
        ExportJob saved = exportJobMapper.selectById(job.getId());
        return new ExportJobVO(
                saved.getId(),
                saved.getFilename(),
                ExportMode.valueOf(saved.getExportMode()),
                ExportJobStatus.valueOf(saved.getStatus()),
                saved.getExpectedTotal(),
                saved.getMaxOrderId(),
                saved.getCreatedAt()
        );
    }

    // ---------- 校验 / 归一化 ----------

    private ExportMode requireMode(ExportCreateRequest request) {
        if (request.mode() == null) {
            throw new ExportBizException(400, "导出模式 mode 不能为空");
        }
        return request.mode();
    }

    /** fields：trim → 保序去重 → 校验白名单，返回规范化列清单 */
    private List<String> normalizeFields(List<String> fields) {
        if (fields == null || fields.isEmpty()) {
            throw new ExportBizException(400, "导出字段不能为空");
        }
        List<String> normalized = new ArrayList<>();
        for (String field : fields) {
            String f = field == null ? "" : field.trim();
            if (f.isEmpty()) {
                continue;
            }
            if (!COLUMN_WHITELIST.contains(f)) {
                throw new ExportBizException(400, "包含不支持的导出字段：" + f);
            }
            if (!normalized.contains(f)) {
                normalized.add(f);
            }
        }
        if (normalized.isEmpty()) {
            throw new ExportBizException(400, "导出字段不能为空");
        }
        return normalized;
    }

    private String normalizeFilename(String filename) {
        String name = filename == null ? "" : filename.trim();
        if (name.isEmpty()) {
            throw new ExportBizException(400, "文件名不能为空");
        }
        if (name.length() > 255) {
            throw new ExportBizException(400, "文件名长度不能超过 255");
        }
        return name;
    }

    private List<Long> normalizeSelectedIds(List<Long> selectedIds) {
        List<Long> ids = dedupeSorted(selectedIds);
        if (ids.isEmpty()) {
            throw new ExportBizException(400, "导出已选缺少 selectedIds");
        }
        if (ids.size() > MAX_ID_COUNT) {
            throw new ExportBizException(400, "已选订单数量不能超过 100");
        }
        return ids;
    }

    private List<Long> normalizeExcludedIds(List<Long> excludedIds) {
        List<Long> ids = dedupeSorted(excludedIds);
        if (ids.size() > MAX_ID_COUNT) {
            throw new ExportBizException(400, "排除订单数量不能超过 100");
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

    // ---------- 范围聚合谓词（与订单列表筛选语义一致，独立声明） ----------

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

    // ---------- 幂等冲突判别 ----------

    private void respondConflictByKey(String key, String requestHash, DuplicateKeyException original) {
        ExportJob existing = exportJobMapper.selectOne(
                new LambdaQueryWrapper<ExportJob>().eq(ExportJob::getIdempotencyKey, key));
        if (existing != null) {
            if (requestHash.equals(existing.getRequestHash())) {
                throw new ExportBizException(409, MSG_DUP_CONTENT);
            }
            throw new ExportBizException(409, MSG_CONFLICT);
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
}
