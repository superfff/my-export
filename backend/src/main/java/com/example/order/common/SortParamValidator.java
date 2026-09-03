package com.example.order.common;

import java.util.Set;

/**
 * 排序参数白名单校验器。
 * 防止前端传入非法的排序字段或方向，避免 SQL 注入风险和不可控的查询行为。
 */
public final class SortParamValidator {

    /** 允许的排序字段（对应 Java 实体属性名） */
    private static final Set<String> ALLOWED_FIELDS = Set.of("amount", "createdAt");

    /** 允许的排序方向 */
    private static final Set<String> ALLOWED_ORDERS = Set.of("asc", "desc");

    private SortParamValidator() {}

    /**
     * 校验排序参数，不合法时返回错误信息，合法时返回 null。
     *
     * @param sortField 排序字段，null 表示不排序
     * @param sortOrder 排序方向，null 表示不排序
     * @return 错误信息，或 null 表示校验通过
     */
    public static String validate(String sortField, String sortOrder) {
        // 两个都为空 → 不排序，合法
        if (sortField == null && sortOrder == null) {
            return null;
        }
        // 只传了其中一个 → 提示需要同时传入
        if (sortField == null) {
            return "排序字段不能为空，请同时传入排序字段和排序方向";
        }
        if (sortOrder == null) {
            return "排序方向不能为空，请同时传入排序字段和排序方向";
        }
        // 校验字段白名单
        if (!ALLOWED_FIELDS.contains(sortField)) {
            return "不支持的排序字段：" + sortField + "，仅支持：" + String.join("、", ALLOWED_FIELDS);
        }
        // 校验方向白名单
        if (!ALLOWED_ORDERS.contains(sortOrder)) {
            return "不支持的排序方向：" + sortOrder + "，仅支持：asc（升序）、desc（降序）";
        }
        return null;
    }
}
