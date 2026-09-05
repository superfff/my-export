package com.example.export.support;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * 单元格净化纯函数的最小单测：剔除控制/非法 XML 字符、给 = + - @ 开头文本前置单引号防注入。
 */
class OrderExportColumnsTest {

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
