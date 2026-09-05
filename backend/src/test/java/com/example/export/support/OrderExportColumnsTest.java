package com.example.export.support;

import com.example.order.entity.Order;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * 单元格净化 / 金额文本化纯函数的最小单测：
 * 剔除控制/非法 XML 字符、给 = + - @ 开头文本前置单引号防注入、amount 固定 2 位小数文本化。
 */
class OrderExportColumnsTest {

    @Test
    void formatAmount_keepsTwoDecimalsAndPlainString() {
        assertEquals("12.00", OrderExportColumns.formatAmount(new BigDecimal("12")));   // 整数补 .00
        assertEquals("12.50", OrderExportColumns.formatAmount(new BigDecimal("12.5")));
        assertEquals("0.00", OrderExportColumns.formatAmount(null));
        assertEquals("-123.00", OrderExportColumns.formatAmount(new BigDecimal("-123")));
        assertEquals("1200.00", OrderExportColumns.formatAmount(new BigDecimal("1200")));
    }

    @Test
    void amount_value_isFixedTwoDecimalText() {
        Order order = new Order();
        order.setAmount(new BigDecimal("1200"));
        Object v = OrderExportColumns.amount.value(order);
        assertInstanceOf(String.class, v);                       // STRING 单元格，非 numeric
        assertEquals("1200.00", v);
    }

    @Test
    void createdAt_value_isFormattedText() {
        Order order = new Order();
        order.setCreatedAt(LocalDateTime.of(2026, 9, 5, 10, 30, 45));
        assertEquals("2026-09-05 10:30:45", OrderExportColumns.createdAt.value(order));
    }

    @Test
    void sanitize_prefixesFormulaLikeTextWithQuote() {
        assertEquals("'=SUM(A1:A2)", OrderExportColumns.sanitize("=SUM(A1:A2)"));
        assertEquals("'+86...", OrderExportColumns.sanitize("+86..."));
        assertEquals("'-0.5", OrderExportColumns.sanitize("-0.5"));
        assertEquals("'@foo", OrderExportColumns.sanitize("@foo"));
    }

    @Test
    void sanitize_replacesControlAndInvalidXmlCodepointsWithSpace() {
        // 0x01 控制字符 → 空格；换行/回车/制表 一律 → 空格（避免单元格换行 / POI 非法字符报错）
        assertEquals("ab cd", OrderExportColumns.sanitize("ab\u0001cd"));
        assertEquals("a b", OrderExportColumns.sanitize("a\nb"));
        assertEquals("a b c", OrderExportColumns.sanitize("a\rb\tc"));
    }

    @Test
    void sanitize_leavesSafeTextUntouched() {
        assertEquals("普通文本 123", OrderExportColumns.sanitize("普通文本 123"));
        assertEquals("", OrderExportColumns.sanitize(""));
        assertEquals("", OrderExportColumns.sanitize(null));
    }

    @Test
    void byKey_resolvesWhitelistOnly() {
        assertEquals("订单金额", OrderExportColumns.byKey("amount").header());
        assertNull(OrderExportColumns.byKey("remark"));
        assertNull(OrderExportColumns.byKey(null));
    }
}
