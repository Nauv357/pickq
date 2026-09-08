-- V12: AI 导入任务记录"实际处理路径"摘要（记录页展示：直传视觉 / MinerU 结构化 / 文本分块 等）
ALTER TABLE ai_import_job ADD COLUMN process_path VARCHAR(32);
