-- Flyway V6: export_jobs 增加"任务完成时间 + 成功导出文件元信息"。
-- finished_at：终态(SUCCESS/FAILED)回填，与 export_job_attempt.finished_at 同一 now 双写同值，供导出中心【完成时间】列取值。
-- file_path/file_size：仅 SUCCESS 终态回填；FAILED/其它状态为 NULL。
ALTER TABLE export_jobs
    ADD COLUMN finished_at DATETIME DEFAULT NULL
        COMMENT '任务完成时间:终态(SUCCESS/FAILED)回填,与export_job_attempt.finished_at同值;导出中心【完成时间】列取此值'
        AFTER processed_rows,
    ADD COLUMN file_path VARCHAR(255) DEFAULT NULL
        COMMENT '成功导出文件相对路径(相对 export.file-dir, 形如 <jobId>/export.xlsx);仅SUCCESS后有值'
        AFTER finished_at,
    ADD COLUMN file_size BIGINT UNSIGNED DEFAULT NULL
        COMMENT '成功导出文件大小(字节);仅SUCCESS后有值'
        AFTER file_path;
