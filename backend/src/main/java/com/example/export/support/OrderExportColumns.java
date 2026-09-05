package com.example.export.support;

import com.example.order.entity.Order;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.function.Function;

/**
 * 导出列元数据 —— 列白名单唯一来源。create 的字段校验与 execute 的表头/取值共用，避免两处漂移。
 *
 * <p>key（枚举名）= 导出列键，即 export_columns JSON 元素 / 前端勾选 dataIndex；
 * header = 列表所见的中文列标题（导出文件第一行表头文案，与订单列表列名一致）；
 * {@link #value(Order)} = 单元格值：数值列（amount）返回 Number 写 numeric 单元格，
 * 其余文本列返回"净化后"字符串（{@link #sanitize}）。
 */
public enum OrderExportColumns {

    orderNo("订单号", o -> sanitize(o.getOrderNo())),
    customerName("客户名称", o -> sanitize(o.getCustomerName())),
    phone("客户手机号", o -> sanitize(o.getPhone())),
    amount("订单金额", o -> (o.getAmount() == null ? BigDecimal.ZERO : o.getAmount()).doubleValue()),
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

    /** 单元格值：数值列返回 Number（写 numeric 单元格），文本列返回净化后字符串；恒非 null */
    public Object value(Order row) {
        return value.apply(row);
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
     * 4) 返回结果。numeric 单元格不走本方法。
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
