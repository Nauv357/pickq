-- V10: AI 导入任务支持"解析引擎选择"（AUTO 自动检测 / LOCAL 本地解析 / MINERU 云端解析）
-- 对应前端三模式：快速=LOCAL+无思考、标准=AUTO+无思考、深度=AUTO+思考
ALTER TABLE ai_import_job ADD COLUMN engine VARCHAR(16) DEFAULT 'AUTO';
