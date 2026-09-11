-- AI 导入失败分类：稳定错误码供前端展示、统计与后续重试策略使用；error 字段仍保存安全的用户提示。
ALTER TABLE ai_import_job ADD COLUMN error_code VARCHAR(48);
