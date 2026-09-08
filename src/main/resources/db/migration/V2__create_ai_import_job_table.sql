-- ============================================================
-- AI 辅助文件导入任务（见 doc/ai-import-spec.md）
-- 任务异步执行：解析 -> AI 整理 -> 校验；前端轮询进度。
-- ============================================================
CREATE TABLE ai_import_job (
    id           BIGINT       NOT NULL AUTO_INCREMENT,
    -- 目标题库：NULL = 新建题库；否则追加到该题库
    bank_id      BIGINT,
    file_name    VARCHAR(255) NOT NULL,
    -- TXT / MD / DOCX / PDF / IMAGE
    file_type    VARCHAR(20)  NOT NULL,
    -- PENDING / PROCESSING / SUCCESS / FAILED
    status       VARCHAR(20)  NOT NULL DEFAULT 'PENDING',
    -- PARSING / AI_GENERATING / VALIDATING / DONE
    stage        VARCHAR(30),
    progress     INT          NOT NULL DEFAULT 0,
    -- 解析出的题目数组（ContentPackageQuestion 列表 JSON）
    result_json  TEXT,
    error        VARCHAR(500),
    created_at   TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    finished_at  TIMESTAMP,
    PRIMARY KEY (id)
);
