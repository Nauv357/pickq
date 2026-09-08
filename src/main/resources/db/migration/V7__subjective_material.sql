-- ============================================================
-- V7: 主观题（SUBJECTIVE）与共享材料（资料分析）支持
-- 设计见 doc/subjective-question-design.md
-- ============================================================

-- 共享材料（资料分析大题干：文字 + 图片标记 [图片:文件名]）
CREATE TABLE material (
    id          BIGINT       NOT NULL AUTO_INCREMENT,
    bank_id     BIGINT       NOT NULL,
    content     TEXT         NOT NULL,
    sort_order  INT          NOT NULL DEFAULT 0,
    created_at  TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at  TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    KEY idx_material_bank (bank_id)
);

-- question 扩展
ALTER TABLE question ADD COLUMN material_id BIGINT;       -- 共享材料引用（资料分析组内题）
ALTER TABLE question ADD COLUMN reference_answer TEXT;    -- 主观题参考答案（文字 + 图片标记，可空）

-- score 支持小数（主观题自评"部分对" = score/2，如 5 分 → 2.5 分）
ALTER TABLE question ALTER COLUMN score DECIMAL(6,1) NOT NULL;

-- study_record 扩展（主观题作答与自评）
ALTER TABLE study_record ADD COLUMN user_answer TEXT;           -- 用户作答文本
ALTER TABLE study_record ADD COLUMN self_grade VARCHAR(16);     -- CORRECT / PARTIAL / WRONG / NULL(客观题)
