-- V13: 主观题自评支持自由给分（0~满分实得分；self_grade 仍写派生档位 CORRECT/PARTIAL/WRONG 供对错统计）
ALTER TABLE study_record ADD COLUMN self_score DOUBLE;
