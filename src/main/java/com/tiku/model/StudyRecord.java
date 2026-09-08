package com.tiku.model;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 刷题记录（纯本地存储，不做逻辑删除）。
 * 错题与学习进度均由此表派生：
 * - 错题 = 每道题最近一次作答为错误的题目集合
 * - 进度 = 已答题数 / 总题数 + 正确率
 */
@Data
@NoArgsConstructor
@TableName("study_record")
public class StudyRecord {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 所属题库（冗余：题目删除后记录仍可按题库归类） */
    @TableField("bank_id")
    private Long bankId;

    /** 关联题目 */
    @TableField("question_id")
    private Long questionId;

    /** 题目业务键（冗余：题目删除后历史记录仍可读） */
    @TableField("question_key")
    private String questionKey;

    /** 用户提交的答案 key，逗号分隔 */
    @TableField("selected_keys")
    private String selectedKeys;

    /** 是否答对（客观题：自动判题结果；主观题：null，由 selfGrade 决定） */
    @TableField("is_correct")
    private Boolean correct;

    /** 主观题用户作答文本（文字 + 图片标记 [图片:文件名]；客观题为 null） */
    @TableField("user_answer")
    private String userAnswer;

    /** 主观题用户自评：CORRECT / PARTIAL / WRONG；null = 未自评（或客观题）。
     *  自由给分后为派生档（满分=CORRECT、0=WRONG、中间=PARTIAL），对错统计沿用此档 */
    @TableField("self_grade")
    private String selfGrade;

    /** 主观题自评实得分（0~题目满分，自由给分；null = 旧数据/未自评，按 selfGrade 映射） */
    @TableField("self_score")
    private Double selfScore;

    /** 所属刷题会话（单题直接提交为 null） */
    @TableField("session_id")
    private Long sessionId;

    /** 该题累计用时（秒；交卷统一判分时由前端统计随交卷提交；单题提交/旧数据为 null） */
    private Long seconds;

    /** 作答时间 */
    @TableField("answered_at")
    private LocalDateTime answeredAt;
}
