-- ============================================================
-- 列宽对齐：让"能发布到广场的包"必然"能导入本地"（2026-09-11）
--
-- 背景：发布侧（ContentPackageInspector / 官网 upload.post.ts / index.post.ts）
-- 按内容包契约校验长度，其中若干字段的允许长度大于本地表列宽：
--   description 2000 > 500、packageKey 100 > 64、version 40 > 20、questionKey 100 > 64（无长度校验）
-- 结果是一个合法内容包能成功发布、但下载后导入本地时因列超长而落库失败。
-- 这里把本地列宽放宽到不低于契约上限（只放宽，不收窄；老数据不受影响）。
--
-- 契约见 docs/package-format.md §5.4；改动本文件时请同步该文档与 docs/data-model.md。
-- ============================================================

-- 题库：title/description/source/身份字段
ALTER TABLE question_bank ALTER COLUMN name        VARCHAR(120);   -- 契约 title ≤ 120（登记接口）
ALTER TABLE question_bank ALTER COLUMN description VARCHAR(2000);  -- 契约 description ≤ 2000
ALTER TABLE question_bank ALTER COLUMN source      VARCHAR(500);   -- 契约 source ≤ 500
ALTER TABLE question_bank ALTER COLUMN package_key VARCHAR(100);   -- KEY_RE 允许 1-100
ALTER TABLE question_bank ALTER COLUMN parent_key  VARCHAR(100);   -- 同上（分支导入的父包 key）
ALTER TABLE question_bank ALTER COLUMN version     VARCHAR(40);    -- 登记接口限 40（校验正则允许 100）

-- 题目：questionKey（= external_id）与来源、答案文字
ALTER TABLE question ALTER COLUMN external_id VARCHAR(100) NOT NULL;  -- 契约 KEY_RE 1-100
ALTER TABLE question ALTER COLUMN source      VARCHAR(500);
ALTER TABLE question ALTER COLUMN answer_text VARCHAR(2000);

-- 刷题记录：冗余的 question_key 必须能容纳放宽后的 external_id
ALTER TABLE study_record ALTER COLUMN question_key VARCHAR(100) NOT NULL;

-- 导出记录：同样记录包身份，跟随放宽
ALTER TABLE export_records ALTER COLUMN package_key VARCHAR(100);
ALTER TABLE export_records ALTER COLUMN version     VARCHAR(40);
