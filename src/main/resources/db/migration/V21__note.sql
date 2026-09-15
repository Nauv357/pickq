-- 笔记（2026-09-16，用户提出："加入笔记模块，方便用户在做错题或做题过程中把想法与 AI 给的好建议及时存放起来"）
--
-- 与"解析"的区别（这个边界必须写清楚，否则两个功能会互相蚕食）：
--   解析 = 题库的（写进 question.analysis，会随题库文件导出、会给别人看）；
--   笔记 = 我的（只存本机、**不进内容包**、不导出），是"我自己的记忆钩子与体会"。
--
-- question_id 可空：既能挂在某道题上，也能只挂在题库上（没有具体题时的随手记）。

CREATE TABLE note (
    id          BIGINT       NOT NULL AUTO_INCREMENT,
    bank_id     BIGINT       NOT NULL,
    question_id BIGINT,                            -- 可空 = 题库级随手记
    content     TEXT         NOT NULL,
    source      VARCHAR(16)  NOT NULL DEFAULT 'user', -- user 自己写的 / ai 从讲解存进来的
    created_at  TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at  TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP, -- 手工维护（H2/PG 兼容，不依赖 ON UPDATE）
    PRIMARY KEY (id)
);
CREATE INDEX idx_note_bank_time ON note (bank_id, updated_at);
CREATE INDEX idx_note_question ON note (question_id);
