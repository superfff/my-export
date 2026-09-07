-- Flyway V7: 阶段12 异常处理支撑列 + 存量回填
-- job_version: 单调写序号(本job全生命周期只增不减)。凡是把 export_jobs 往前推进的状态/进度写入都 +1:
--   claim/每批进度/finalize终态/重试PENDING/租约回收/过期回收。角色=活跃worker写回的乐观CAS + 读者/将来SSE的快照序号。
ALTER TABLE export_jobs
    ADD COLUMN job_version BIGINT NOT NULL DEFAULT 0
        COMMENT '单调写序号:每次状态/进度回写+1(claim/每批/终态/重试/双回收);活跃worker写回按WHERE job_version=? 做CAS;读者按更大版本=更新,将来SSE按此排序'
        AFTER processed_rows,
    ADD COLUMN heartbeat_at DATETIME DEFAULT NULL
        COMMENT 'worker心跳:最近一次证明存活的批次/进度写入时间;claim重置,每次写批顺带刷新'
        AFTER file_size,
    ADD COLUMN lease DATETIME DEFAULT NULL
        COMMENT '租约截止=最近一次心跳+N秒;RUNNING且lease IS NULL或已过期→回收扫描置FAILED并清目录'
        AFTER heartbeat_at,
    ADD COLUMN expires_at DATETIME DEFAULT NULL
        COMMENT '成功文件过期时间=finished_at+24h(SUCCESS落终态同写);SUCCESS且expires_at<=now→扫描先删文件再置EXPIRED'
        AFTER lease;

-- 过期回收按 (status, expires_at) 命中，SUCCESS 行随时间无界增长，建复合索引
ALTER TABLE export_jobs ADD KEY idx_status_expires (status, expires_at);

-- 存量 SUCCESS 回填过期时间(默认24h)，避免永不回收
UPDATE export_jobs
   SET expires_at = DATE_ADD(finished_at, INTERVAL 24 HOUR)
 WHERE status = 'SUCCESS' AND expires_at IS NULL;

-- 存量 RUNNING 回填一次心跳/租约：给在跑 worker 5 分钟宽限，避免部署即被下一轮租约扫描误回收
UPDATE export_jobs
   SET heartbeat_at = NOW(), lease = DATE_ADD(NOW(), INTERVAL 300 SECOND)
 WHERE status = 'RUNNING' AND lease IS NULL;
