-- 学习路径引擎 · 阶段 2（只读学习路线：外缘 + 缺口 + 每日任务）
-- 设计见 docs/learning-path-design.md §3.3、§3.4、§5、§7.1、§7.2。
-- 关键取舍：**路线是算出来的**（确定性公式，日常零 token），只把"个体输入"与"当天任务"落库：
--   learner_profile  → 目标模板 / 目标原话 / 每日题量（个性化输入，§7.2）
--   daily_task       → 当天生成的任务清单（冻结题单，避免刷新一次换一套题）
--   user_skill_state 不建表：掌握度由 study_record + question_skill + tutor_message 现算（不引入第二份真相）

-- 个体输入（本机单用户：固定一行 id=1）
CREATE TABLE learner_profile (
    id               BIGINT      NOT NULL,
    goal_template_id VARCHAR(64),               -- 采用哪张技能图
    goal_text        VARCHAR(500),              -- 用户原话（阶段 2 只展示，不调模型）
    daily_questions  INT         NOT NULL DEFAULT 20,   -- 每日题量预算
    daily_minutes    INT,                       -- 每日时间预算（分钟，可空）
    target_date      DATE,                      -- 目标时间点（可空）
    baseline_json    TEXT,                      -- 首次自评问卷结果（阶段 3 用，先留位）
    updated_at       TIMESTAMP   NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (id)
);

-- 当天任务（确定性生成：外缘主攻节点 n 题 + 到期复习题）
CREATE TABLE daily_task (
    id          BIGINT       NOT NULL AUTO_INCREMENT,
    task_date   DATE         NOT NULL,
    kind        VARCHAR(24)  NOT NULL,           -- PRACTICE（新题主攻）/ REVIEW（到期复习）
    node_id     VARCHAR(96)  NOT NULL DEFAULT '',-- 主攻节点（REVIEW 时为空）
    template_id VARCHAR(64),
    plan_json   TEXT         NOT NULL,           -- 冻结的题 id 列表与说明
    created_at  TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    CONSTRAINT uk_daily_task UNIQUE (task_date, kind, node_id)
);

-- 会话按知识点抽题（阶段 2 的"一键开始今天的任务"用它；存节点 id 逗号分隔）
ALTER TABLE practice_session ADD COLUMN scope_node VARCHAR(500);
