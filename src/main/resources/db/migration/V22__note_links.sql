-- 笔记解耦（2026-09-16 用户反馈："笔记不要和题库/题目硬绑定，但一定要能关联——一条笔记要能挂到多个题库、多道题上"）
--
-- 以前 note 直接带 bank_id / question_id：一条笔记只能属于一个题库的一道题，
-- 想"把这条体会同时挂到另一道同类题上"只能复制一遍。改为**关联表**：
--   note         = 我写的内容（谁的、什么时候写的）
--   note_link    = 这条笔记关联到哪里（0..N 个题库、0..N 道题）
--
-- 关联可以为空（随手记一条，谁都不挂），题库/题目被删时**只删关联、不删笔记**——
-- 笔记是我的东西，不该因为题库被删就消失（列表里显示为"未归类"）。

CREATE TABLE note_link (
    note_id     BIGINT      NOT NULL,
    target_type VARCHAR(16) NOT NULL, -- bank 题库 / question 题目
    target_id   BIGINT      NOT NULL,
    created_at  TIMESTAMP   NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (note_id, target_type, target_id)
);
CREATE INDEX idx_note_link_target ON note_link (target_type, target_id);

-- 老数据平移：原来的归属原样变成两条关联（挂题的那条同时也挂在题所属题库上）
INSERT INTO note_link (note_id, target_type, target_id, created_at)
    SELECT id, 'bank', bank_id, created_at FROM note;
INSERT INTO note_link (note_id, target_type, target_id, created_at)
    SELECT id, 'question', question_id, created_at FROM note WHERE question_id IS NOT NULL;

-- 旧列删除：归属只留在 note_link 一处，避免两套真相
DROP INDEX IF EXISTS idx_note_bank_time;
DROP INDEX IF EXISTS idx_note_question;
ALTER TABLE note DROP COLUMN bank_id;
ALTER TABLE note DROP COLUMN question_id;
