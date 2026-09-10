-- ============================================================
-- V14: 本地发布中心——导出记录
-- 「我的作品」把题库导出成 .tiku 落到本地目录后落一条记录（文件在哪、多大、属于哪个
-- 内容包身份与版本），支撑：导出历史列表、从本地路径直接发布（不再经前端中转）、
-- 发布成功后标记（published/published_version）与"下一版本号"建议。
-- bank_id 不建外键：题库为物理删除，删除题库后导出记录（磁盘文件仍在）保留可查。
-- ============================================================
CREATE TABLE export_records (
    id                BIGINT       NOT NULL AUTO_INCREMENT,
    -- 来源题库（导出时的快照信息，题库删除后本表仍可读）
    bank_id           BIGINT       NOT NULL,
    bank_name         VARCHAR(100) NOT NULL,
    -- 导出内容包的身份与版本（package_key 为导出时实际写入包内的值）
    package_key       VARCHAR(64),
    version           VARCHAR(20),
    -- 落盘位置（绝对路径）与文件名，文件名已做 Windows 非法字符清理
    file_path         VARCHAR(1024) NOT NULL,
    file_name         VARCHAR(255)  NOT NULL,
    size_bytes        BIGINT        NOT NULL DEFAULT 0,
    -- 是否已上传到题库广场；published_version = 实际上传成功的版本（可空）
    published         TINYINT       NOT NULL DEFAULT 0,
    published_version VARCHAR(20),
    created_at        TIMESTAMP     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    -- 列表按时间倒序；按题库取"最近一次已发布"（下一版本号建议）走 bank_id 前缀
    KEY idx_export_records_created (created_at),
    KEY idx_export_records_bank (bank_id, published)
);
