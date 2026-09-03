-- ============================================================
-- 测试数据生成脚本：向 t_order 表插入 10000 条有规律但随机的数据
-- 使用方式：手动执行，不属于 Flyway 迁移链
--   mysql -u order_user -p order_db < scripts/10000-test-data.sql
--   或进入 MySQL 容器后 source /path/to/10000-test-data.sql
-- ============================================================

USE order_db;

SET NAMES utf8mb4;

DROP PROCEDURE IF EXISTS insert_test_data;

DELIMITER //

CREATE PROCEDURE insert_test_data()
BEGIN
    DECLARE i INT DEFAULT 1;
    DECLARE v_order_no VARCHAR(64);
    DECLARE v_customer_name VARCHAR(64);
    DECLARE v_phone VARCHAR(20);
    DECLARE v_status TINYINT;
    DECLARE v_amount DECIMAL(12,2);
    DECLARE v_created_at DATETIME;
    DECLARE v_remark VARCHAR(255);

    -- 常用姓氏池（20 个）
    DECLARE name_count INT DEFAULT 20;

    WHILE i <= 10000 DO
        -- 订单号：ORD + 日期（2026-08-01 起，每天约 333 条）+ 4 位序号
        SET v_order_no = CONCAT(
            'ORD',
            DATE_FORMAT(DATE_ADD('2026-08-01', INTERVAL FLOOR((i - 1) / 333) DAY), '%Y%m%d'),
            LPAD(i, 4, '0')
        );

        -- 客户姓名：从 20 个常见姓氏 + 20 个常见名字中随机组合
        SET v_customer_name = CONCAT(
            ELT(1 + FLOOR(RAND() * name_count),
                '张','李','王','刘','陈','杨','赵','黄','周','吴',
                '徐','孙','马','朱','胡','郭','何','林','罗','郑'),
            ELT(1 + FLOOR(RAND() * name_count),
                '伟','娜','芳','强','静','磊','敏','军','艳','涛',
                '霞','明','超','平','丽','勇','娟','杰','倩','刚')
        );

        -- 手机号：13x/15x/18x 开头 + 8 位随机数字
        SET v_phone = CONCAT(
            ELT(1 + FLOOR(RAND() * 3), '138', '150', '186'),
            LPAD(FLOOR(RAND() * 100000000), 8, '0')
        );

        -- 订单状态：1-未支付 40%、2-已支付 40%、3-已取消 20%
        SET v_status = CASE
            WHEN RAND() < 0.4 THEN 1
            WHEN RAND() < 0.8 THEN 2
            ELSE 3
        END;

        -- 订单金额：10.00 ~ 2000.00 之间，保留两位小数
        SET v_amount = ROUND(10 + RAND() * 1990, 2);

        -- 创建时间：2026-08-01 00:00 ~ 2026-08-31 23:59 之间随机
        SET v_created_at = DATE_ADD(
            '2026-08-01 00:00:00',
            INTERVAL FLOOR(RAND() * 31 * 24 * 60 * 60) SECOND
        );

        -- 备注：约 15% 有备注，其余为 NULL
        SET v_remark = IF(
            RAND() < 0.15,
            ELT(1 + FLOOR(RAND() * 4), '加急', '客户取消', '备注示例', '需跟进'),
            NULL
        );

        INSERT INTO t_order (order_no, customer_name, phone, status, amount, created_at, remark)
        VALUES (v_order_no, v_customer_name, v_phone, v_status, v_amount, v_created_at, v_remark);

        SET i = i + 1;
    END WHILE;
END //

DELIMITER ;

-- 执行存储过程
CALL insert_test_data();

-- 清理存储过程
DROP PROCEDURE IF EXISTS insert_test_data;
