-- ============================================================
-- V8: study_record.is_correct 允许 NULL（主观题不自动判题，正确性由 self_grade 决定）
-- ============================================================

ALTER TABLE study_record ALTER COLUMN is_correct SET NULL;
