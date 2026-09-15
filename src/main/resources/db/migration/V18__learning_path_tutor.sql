-- 学习路径引擎 · 阶段 1（错题追问 / 提示楼梯 / 复盘）
-- 设计见 docs/learning-path-design.md §3.5、§7.4：
--   追问会话（tutor_session）+ 消息（tutor_message）；每级提示写 message.hint_level，
--   答错即问的快捷三选写 session.self_reason。
-- 闪卡（card）属于阶段 3，本迁移不引入。

-- 追问会话：单题追问 / 整场复盘
CREATE TABLE tutor_session (
    id                  BIGINT      NOT NULL AUTO_INCREMENT,
    bank_id             BIGINT      NOT NULL,
    question_id         BIGINT,                     -- 单题追问（复盘时为 NULL）
    practice_session_id BIGINT,                     -- 来自哪场练习（做题中追问时写入）
    kind                VARCHAR(24) NOT NULL,       -- PER_QUESTION / POST_REVIEW
    self_reason         VARCHAR(24),                -- CARELESS / NO_KNOWLEDGE / NEVER_SEEN
    self_note           VARCHAR(500),               -- 用户补充的一句话
    status              VARCHAR(16) NOT NULL DEFAULT 'OPEN',
    created_at          TIMESTAMP   NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at          TIMESTAMP   NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (id)
);
CREATE INDEX idx_tutor_session_question ON tutor_session (question_id, created_at);
CREATE INDEX idx_tutor_session_practice ON tutor_session (practice_session_id);
CREATE INDEX idx_tutor_session_bank ON tutor_session (bank_id, created_at);

-- 追问消息：user / assistant；助手每条带 hint_level（1/2/3 提示楼梯，NULL = 自由追问）
CREATE TABLE tutor_message (
    id          BIGINT      NOT NULL AUTO_INCREMENT,
    session_id  BIGINT      NOT NULL,
    role        VARCHAR(16) NOT NULL,               -- user / assistant
    content     TEXT        NOT NULL,
    hint_level  INT,                                -- 1 指方向 / 2 关键一步 / 3 完整解析
    model       VARCHAR(96),                        -- 生成这条的模型（便于排查）
    created_at  TIMESTAMP   NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (id)
);
CREATE INDEX idx_tutor_message_session ON tutor_message (session_id, id);
