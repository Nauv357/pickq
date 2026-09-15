-- 学习路径引擎 · 阶段 3（掌握度闭环：抽测降级 + 闪卡 + 自适应难度）
-- 设计见 docs/learning-path-design.md §5.4、§5.5、§7.5。
-- 说明：
--   抽测降级本身**不建表**——"是不是假掌握"由既有证据推导（过关之后的失败证据 → REGRESSED），
--   抽测任务复用 daily_task（kind=SPOT_CHECK），与"今天做什么"同一套机制。
--   本迁移只加两样：闪卡（card）与每题难度（question_difficulty，85% 规则用）。

-- 闪卡：从解析里抽"关键结论/易错点"挖空，调度与题目同一套阶梯（答对升一级、答错清零）
CREATE TABLE card (
    id            BIGINT       NOT NULL AUTO_INCREMENT,
    bank_id       BIGINT       NOT NULL,
    node_id       VARCHAR(96),                    -- 关联知识点（可空：按题生成时取该题的标签）
    question_id   BIGINT       NOT NULL,          -- 出处题（必须可溯源，卡片界面点开能回原题）
    front         VARCHAR(1000) NOT NULL,         -- 挖空后的提示（含 ____）
    back          VARCHAR(1000) NOT NULL,         -- 答案要点
    source        VARCHAR(16)  NOT NULL,          -- ai / user
    confirmed     TINYINT      NOT NULL DEFAULT 0,-- AI 生成的默认未确认：未确认不参与复习调度
    level         INT          NOT NULL DEFAULT 0,
    interval_days INT          NOT NULL DEFAULT 1,
    due_at        TIMESTAMP,                      -- 到期时间（确认后才有）
    lapses        INT          NOT NULL DEFAULT 0,
    last_result   VARCHAR(16),                    -- REMEMBERED / FORGOT（最近一次自评）
    last_reviewed_at TIMESTAMP,
    created_at    TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at    TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (id)
);
CREATE INDEX idx_card_bank_due ON card (bank_id, confirmed, due_at);
CREATE INDEX idx_card_question ON card (question_id);

-- 每题难度（Elo-lite，设计 §5.5）：初值按题型估计，之后随每次作答更新（d_q ← d_q − K×(实际 − p)）
CREATE TABLE question_difficulty (
    question_id BIGINT      NOT NULL,
    difficulty  DOUBLE      NOT NULL DEFAULT 400,  -- 400 = 与初始能力(θ=0)打平
    attempts    INT         NOT NULL DEFAULT 0,
    updated_at  TIMESTAMP   NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (question_id)
);
