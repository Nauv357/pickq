-- ============================================================
-- AI 导入：确认导入标记（防止用户丢失未确认的导入结果；幂等语义）
-- confirmed=1 后：recent 列表不再展示；同目标重复 confirm 幂等返回
-- ============================================================
ALTER TABLE ai_import_job ADD COLUMN confirmed TINYINT NOT NULL DEFAULT 0;
