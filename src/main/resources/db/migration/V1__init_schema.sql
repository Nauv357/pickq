-- ============================================================
-- 初始表结构（H2 文件模式，MODE=MySQL 兼容模式）
-- 合并模型：题库（question_bank）= 内容包在本地的生活形态。
-- 内容包身份（package_key / version / 作者 / 来源 / checksum）直接挂在题库上，
-- 不再有独立的 content_package / package_version 表；
-- 导入导出 = 题库与标准内容包 JSON 文件的序列化/反序列化。
-- ============================================================

-- 题库（本地使用容器 + 内容包身份合一）
CREATE TABLE question_bank (
    id             BIGINT       NOT NULL AUTO_INCREMENT,
    name           VARCHAR(100) NOT NULL,
    description    VARCHAR(500),
    -- 内容包身份（自建题库为 NULL，导入/导出时生成或记录）
    package_key    VARCHAR(64),
    version        VARCHAR(20),
    schema_version INT,
    checksum       VARCHAR(64),
    author_id      BIGINT,
    author_name    VARCHAR(100),
    source         VARCHAR(255),
    sources        TEXT,
    parent_key     VARCHAR(64),
    created_at     TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at     TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    -- 是否启用复习计划（默认关闭，用户显式开启，避免被动积累）
    review_enabled TINYINT      NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    -- 题库为物理删除（删除即级联删题删记录），唯一约束无需包含 deleted
    -- 同一内容包身份（package_key）允许不同版本并存；分支导入使用新 package_key
    CONSTRAINT uk_question_bank_package_key_version UNIQUE (package_key, version)
);

-- 题目（external_id = 内容包 questionKey，跨版本追踪同一道题）
CREATE TABLE question (
    id              BIGINT       NOT NULL AUTO_INCREMENT,
    external_id     VARCHAR(64)  NOT NULL,
    question_number INT,
    question_type   VARCHAR(20)  NOT NULL,
    content         TEXT         NOT NULL,
    options         TEXT,
    answer_keys     VARCHAR(255),
    answer_text     VARCHAR(500),
    analysis        TEXT,
    category        VARCHAR(100),
    topic           VARCHAR(100),
    volume          INT          NOT NULL DEFAULT 0,
    score           INT          NOT NULL,
    source          VARCHAR(255),
    bank_id         BIGINT,
    -- 收藏（做对也想二刷的题）
    favorite        TINYINT      NOT NULL DEFAULT 0,
    created_at      TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at      TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    deleted         TINYINT      NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    -- questionKey（external_id）在题库（=内容包版本）内唯一即可：
    -- 跨题库允许重复（如从题库 A 导出的包导入为题库 B，两库并存时 questionKey 相同是合法的）
    CONSTRAINT uk_question_bank_external_id_deleted UNIQUE (bank_id, external_id, deleted)
);

-- 刷题记录（纯本地；错题与学习进度均由此表派生）
-- 不做逻辑删除：记录是历史日志，删除题库时物理级联删除
CREATE TABLE study_record (
    id             BIGINT       NOT NULL AUTO_INCREMENT,
    bank_id        BIGINT       NOT NULL,
    question_id    BIGINT       NOT NULL,
    -- 冗余 question_key：题目被删除后历史记录仍可读（产品原则：删除前提示影响记录数）
    question_key   VARCHAR(64)  NOT NULL,
    selected_keys  VARCHAR(255),
    is_correct     TINYINT      NOT NULL,
    -- 所属刷题会话（单次刷题单元；单题直接提交为 NULL）
    session_id     BIGINT,
    answered_at    TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    KEY idx_study_record_bank_time (bank_id, answered_at),
    KEY idx_study_record_question (question_id)
);

-- 刷题会话（粉笔式：从题库选范围+数量组成单次刷题流程）
-- 成绩不落库，从 study_record 实时聚合（避免不一致）
CREATE TABLE practice_session (
    id             BIGINT       NOT NULL AUTO_INCREMENT,
    bank_id        BIGINT       NOT NULL,
    -- ALL 全部随机 / SEQUENCE 按题号顺序 / TOPIC 按分类 / REVIEW 复习队列到期题 / WRONG 错题 / FAVORITE 收藏
    mode           VARCHAR(20)  NOT NULL,
    scope_topic    VARCHAR(100),
    scope_category VARCHAR(100),
    question_count INT          NOT NULL,
    -- 交卷时间（null = 进行中；交卷后生成成绩报告）
    finished_at    TIMESTAMP,
    created_at     TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    KEY idx_practice_session_bank (bank_id)
);

-- 会话抽取的题目（回顾页需要展示完整题目列表，包括未作答的）
CREATE TABLE practice_session_question (
    session_id   BIGINT NOT NULL,
    question_id  BIGINT NOT NULL,
    sort         INT    NOT NULL,
    PRIMARY KEY (session_id, sort)
);

-- 复习状态（简化间隔重复：连续答对间隔翻倍 1→2→4→8→16→30 天封顶，答错归 1 天）
-- suspended=1：用户标记"不再复习此题"，不进待复习队列（可随时恢复）
CREATE TABLE review_state (
    question_id    BIGINT       NOT NULL,
    bank_id        BIGINT       NOT NULL,
    level          INT          NOT NULL DEFAULT 0,
    interval_days  INT          NOT NULL DEFAULT 1,
    due_at         TIMESTAMP    NOT NULL,
    suspended      TINYINT      NOT NULL DEFAULT 0,
    PRIMARY KEY (question_id)
);
