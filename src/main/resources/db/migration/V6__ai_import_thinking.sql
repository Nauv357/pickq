-- V6: AI 导入任务支持"本次任务开启思考模式"（thinking=true 覆盖全局配置，前端导入对话框已传该参数）
ALTER TABLE ai_import_job ADD COLUMN thinking BOOLEAN;
