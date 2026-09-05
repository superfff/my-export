package com.example.export.support;

import com.example.order.entity.Order;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.function.Function;

/**
 * 导出列元数据 —— 列白名单唯一来源。create 的字段校验与 execute 的表头/取值共用，避免两处漂移。
 *
 * <p>key（枚举名）= 导出列键，即 export_columns JSON 元素 / 前端勾选 dataIndex；
 * header = 列表所见的中文列标题（导出文件第一行表头文案，与订单列表列名一致）；
 * {@link #value(Order)} = 单元格值：amount 走 STRING 单元格（固定 2 位小数的文本，见 {@link #formatAmount}），
 * 其余文本列返回"净化后"字符串（{@link #sanitize}）。numeric 单元格（Excel 可求和）本期已无列使用，
 * ExcelFileWriter 的 Number 分支仅为将来数值列保留。
 */
public enum OrderExportColumns {

    orderNo("订单号", o -> sanitize(o.getOrderNo())),
    customerName("客户名称", o -> sanitize(o.getCustomerName())),
    phone("客户手机号", o -> sanitize(o.getPhone())),
    amount("订单金额", o -> formatAmount(o.getAmount())),
    status("订单状态", o -> statusText(o.getStatus())),
    createdAt("订单创建时间", o -> o.getCreatedAt() == null ? "" : formatDateTime(o.getCreatedAt()));

    /** 时间格式与订单列表展示一致（yyyy-MM-dd HH:mm:ss）；DateTimeFormatter 线程安全 */
    private static final DateTimeFormatter DATE_TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final String header;
    private final Function<Order, Object> value;

    OrderExportColumns(String header, Function<Order, Object> value) {
        this.header = header;
        this.value = value;
    }

    /** 导出文件第一行表头文案（列表所见列名） */
    public String header() {
        return header;
    }

    /** 单元格值：amount 返回固定 2 位小数的文本，其余文本列返回净化后字符串；恒非 null */
    public Object value(Order row) {
        return value.apply(row);
    }

    /** 金额 → 固定 2 位小数的纯文本（"12"→"12.00"，"12.5"→"12.50"，null→"0.00"）；setScale 后 toPlainString 防科学计数 */
    static String formatAmount(BigDecimal amount) {
        BigDecimal v = amount == null ? BigDecimal.ZERO : amount;
        return v.setScale(2, RoundingMode.HALF_UP).toPlainString();
    }

    /** 按列键取元数据；未知键返回 null（create 字段校验 / execute 反序列化 export_columns 的白名单校验用） */
    public static OrderExportColumns byKey(String key) {
        for (OrderExportColumns column : values()) {
            if (column.name().equals(key)) {
                return column;
            }
        }
        return null;
    }

    /**
     * 单元格文本净化（纯函数，可单测）：
     * 1) null → ""；
     * 2) 逐码点剔除"控制/非法 XML"字符（&lt;0x20 全部，含换行/回车/制表，及 0xFFFE/0xFFFF）→ 单个空格，
     *    避免单元格换行 / POI 对非法字符抛错；
     * 3) 结果非空且首字符为 {@code = + - @} 之一时，前置单引号 {@code '}（Excel 视为纯文本，防公式/注入）；
     * 4) 返回结果。amount 走 STRING 单元格、不经本方法（纯数值文本，负号/数字不会被当公式；走本方法会给负值多加前导 '）。
     */
    public static String sanitize(String raw) {
        if (raw == null) {
            return "";
        }
        StringBuilder sb = new StringBuilder(raw.length());
        raw.codePoints().forEach(cp -> {
            if (cp < 0x20 || cp == 0xFFFE || cp == 0xFFFF) {
                sb.append(' ');
            } else {
                sb.appendCodePoint(cp);
            }
        });
        String s = sb.toString();
        if (!s.isEmpty()) {
            char first = s.charAt(0);
            if (first == '=' || first == '+' || first == '-' || first == '@') {
                s = "'" + s;
            }
        }
        return s;
    }

    /** 订单创建时间格式化为列表所见 yyyy-MM-dd HH:mm:ss（经方法中转，避免枚举常量初始化期的前向引用） */
    private static String formatDateTime(LocalDateTime time) {
        return DATE_TIME.format(time);
    }

    /** 订单状态文案映射，与前端 constants/order.ts ORDER_STATUS 一致（未支付/已支付/已取消） */
    private static String statusText(Integer status) {
        if (status == null) {
            return "";
        }
        return switch (status) {
            case 1 -> "未支付";
            case 2 -> "已支付";
            case 3 -> "已取消";
            default -> "";
        };
    }
}
