package com.tiku.model;

import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * 内容包 JSON 文件结构（schemaVersion = 1；v1.1 可选扩展：materials / images / referenceAnswer / materialKey）。
 * 合并模型下：导出 = 题库序列化为本结构；导入 = 本结构反序列化为题库 + 题目。
 * 字段顺序固定（Jackson record/字段声明顺序），用于 checksum 规范化计算。
 */
@Data
@NoArgsConstructor
public class ContentPackageFile {

    /** 内容包格式版本，当前为 1 */
    private Integer schemaVersion;

    /** 内容包稳定身份 UUID，用户不可手动编辑 */
    private String packageKey;

    /** 内容包标题（= 题库名称） */
    private String title;

    /** 描述 */
    private String description;

    /** 语义化版本号，如 1.0.0 */
    private String version;

    /** 作者账号 ID，本地为 null */
    private Long authorId;

    /** 作者展示名 */
    private String authorName;

    /** 来源声明 */
    private String source;

    /** 派生来源（分支导入时记录原 packageKey） */
    private String parentKey;

    /**
     * 内容指纹（SHA-256，与 question_bank.checksum 一致）。
     * 仅出现在导出**响应**中（供桌面端生成登记清单 manifest）；内容包文件本体不含此字段，
     * 前端保存文件时应剥离（导入端若文件内带此字段也会被忽略，不影响 checksum 计算）。
     */
    private String checksum;

    /** 混编来源数组 */
    private List<String> sources;

    /** 文件创建时间 */
    private LocalDateTime createdAt;

    /** 题目数组 */
    private List<ContentPackageQuestion> questions;

    /** 共享材料数组（资料分析大题干，v1.1 可选扩展） */
    private List<ContentPackageMaterial> materials;

    /** 图片资源（name → base64；题目/材料 content 内 [图片:name] 引用，v1.1 可选扩展） */
    private Map<String, String> images;
}
