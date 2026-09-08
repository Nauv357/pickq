-- AI 导入任务：题数差异检测提示（非思考模式复杂排版时提醒用户换思考模式）
ALTER TABLE ai_import_job ADD COLUMN warning_hint VARCHAR(500);
