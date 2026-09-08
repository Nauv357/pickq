package com.tiku.model;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 共享材料（资料分析大题干：文字 + 图片标记 [图片:文件名]）。
 * 组内题通过 question.material_id 引用；删除题库时级联清理。
 */
@Data
@NoArgsConstructor
@TableName("material")
public class Material {

    @TableId(type = IdType.AUTO)
    private Long id;

    @TableField("bank_id")
    private Long bankId;

    /** 材料内容（文字 + 图片标记） */
    private String content;

    @TableField("sort_order")
    private Integer sortOrder = 0;

    @TableField("created_at")
    private LocalDateTime createdAt;

    @TableField("updated_at")
    private LocalDateTime updatedAt;
}
