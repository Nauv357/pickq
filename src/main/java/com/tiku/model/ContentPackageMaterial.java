package com.tiku.model;

import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 内容包中的共享材料（资料分析大题干）。
 * materialKey 在内容包内唯一，题目通过 materialKey 引用；导入后映射为本地 material.id。
 */
@Data
@NoArgsConstructor
public class ContentPackageMaterial {

    /** 材料唯一标识（内容包内唯一，跨版本保持稳定） */
    private String materialKey;

    /** 材料内容（文字 + 图片标记 [图片:文件名]） */
    private String content;
}
