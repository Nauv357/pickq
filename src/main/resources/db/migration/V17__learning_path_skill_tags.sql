-- 学习路径引擎 · 阶段 0（标签地基）
-- 设计见 docs/learning-path-design.md §3/§4：技能图（受控词表）+ 题目标签（三级来源）+ 分组映射缓存。
-- 说明：本迁移只加阶段 0 需要的表；阶段 1–3 的 tutor_session / card / plan / daily_task /
--        user_skill_state / learner_profile 在各自阶段新增迁移，避免一次引入无人使用的结构。

-- 技能节点（受控词表：题目标签只能引用这里的 node_id）
CREATE TABLE skill_node (
    id          BIGINT       NOT NULL AUTO_INCREMENT,
    template_id VARCHAR(64)  NOT NULL,
    node_id     VARCHAR(96)  NOT NULL,
    name        VARCHAR(120) NOT NULL,
    parent_id   VARCHAR(96),
    level       INT          NOT NULL DEFAULT 3,
    weight      DOUBLE       NOT NULL DEFAULT 1.0,
    optional    TINYINT      NOT NULL DEFAULT 0,
    keywords    VARCHAR(500),
    stage_id    VARCHAR(48),
    stage_name  VARCHAR(120),
    stage_order INT          NOT NULL DEFAULT 0,
    updated_at  TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    CONSTRAINT uk_skill_node_template UNIQUE (template_id, node_id)
);

-- 前置关系（决定学习顺序与外缘；type = prereq | related）
CREATE TABLE skill_edge (
    id          BIGINT      NOT NULL AUTO_INCREMENT,
    template_id VARCHAR(64) NOT NULL,
    from_id     VARCHAR(96) NOT NULL,
    to_id       VARCHAR(96) NOT NULL,
    type        VARCHAR(16) NOT NULL DEFAULT 'prereq',
    PRIMARY KEY (id),
    CONSTRAINT uk_skill_edge UNIQUE (template_id, from_id, to_id, type)
);

-- 题目标签：同一题同一节点只一行；source 表示这一行当前由谁定（user > author > ai）
CREATE TABLE question_skill (
    id          BIGINT      NOT NULL AUTO_INCREMENT,
    question_id BIGINT      NOT NULL,
    bank_id     BIGINT      NOT NULL,
    node_id     VARCHAR(96) NOT NULL,
    template_id VARCHAR(64) NOT NULL,
    source      VARCHAR(16) NOT NULL,
    confidence  DOUBLE      NOT NULL DEFAULT 0.5,
    confirmed   TINYINT     NOT NULL DEFAULT 0,
    origin      VARCHAR(24),
    shadowed    TINYINT     NOT NULL DEFAULT 0,
    updated_at  TIMESTAMP   NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    CONSTRAINT uk_question_skill UNIQUE (question_id, node_id)
);
CREATE INDEX idx_question_skill_bank ON question_skill (bank_id, node_id);
CREATE INDEX idx_question_skill_node ON question_skill (node_id, confirmed);

-- 分组映射缓存：两级映射的"第一级"结果（作者 topic/category → 技能节点）
-- 它同时是**用户批量纠错的单位**：改一组 = 改一类题，避免逐题点。
CREATE TABLE skill_group_map (
    id            BIGINT       NOT NULL AUTO_INCREMENT,
    template_id   VARCHAR(64)  NOT NULL,
    group_key     VARCHAR(160) NOT NULL,
    graph_version VARCHAR(64)  NOT NULL,
    nodes_json    TEXT         NOT NULL,
    source        VARCHAR(16)  NOT NULL DEFAULT 'ai',
    updated_at    TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    CONSTRAINT uk_skill_group_map UNIQUE (template_id, group_key)
);
