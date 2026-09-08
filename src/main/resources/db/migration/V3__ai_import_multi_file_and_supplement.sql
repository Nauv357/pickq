-- ============================================================
-- AI 导入：多文件支持 + AI 补充开关
-- file_names：所有上传文件名（逗号分隔，第一个为主文件）；file_name 保留为主文件展示名
-- ai_supplement：true=原文答案优先、缺失时 AI 补充答案与解析；false=只用原文信息
-- ============================================================
ALTER TABLE ai_import_job ADD COLUMN file_names VARCHAR(500);
ALTER TABLE ai_import_job ADD COLUMN ai_supplement TINYINT NOT NULL DEFAULT 1;
