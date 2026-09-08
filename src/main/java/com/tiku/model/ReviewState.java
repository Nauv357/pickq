package com.tiku.model;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 复习状态（简化间隔重复，每道题一条）。
 * 更新规则（提交作答时同步）：
 * - 答对：level+1，间隔翻倍（1→2→4→8→16→30 封顶），due = now + interval 天
 * - 答错：level 归 0，间隔 1 天，due = now（立即可复习）
 * suspended=1：用户标记"不再复习此题"，不进待复习队列（可随时恢复）
 */
@Data
@NoArgsConstructor
@TableName("review_state")
public class ReviewState {

    @TableId(type = IdType.INPUT)
    @TableField("question_id")
    private Long questionId;

    @TableField("bank_id")
    private Long bankId;

    /** 熟练等级 0-5（连续答对次数封顶 5） */
    private Integer level;

    /** 当前复习间隔（天） */
    @TableField("interval_days")
    private Integer intervalDays;

    /** 下次复习时间（<= now 即进入待复习队列） */
    @TableField("due_at")
    private LocalDateTime dueAt;

    /** 暂停复习标记 */
    private Boolean suspended;
}
