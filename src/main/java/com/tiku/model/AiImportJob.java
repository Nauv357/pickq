package com.tiku.model;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * AI 导入任务（见 doc/ai-import-spec.md）。
 * 异步执行：解析 -> AI 整理 -> 校验；前端轮询。
 */
@Data
@NoArgsConstructor
@TableName("ai_import_job")
public class AiImportJob {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 目标题库：null = 新建题库，否则追加 */
    @TableField("bank_id")
    private Long bankId;

    @TableField("file_name")
    private String fileName;

    /** 所有文件名（逗号分隔，第一个为主文件） */
    @TableField("file_names")
    private String fileNames;

    /** 是否允许 AI 补充缺失的答案与解析（默认 true） */
    @TableField("ai_supplement")
    private Boolean aiSupplement;

    /** 本次任务是否开启 AI 思考模式（true=显式开启，覆盖全局配置；null=跟随全局配置） */
    @TableField("thinking")
    private Boolean thinking;

    /** 解析引擎：AUTO 自动检测（默认）/ LOCAL 本地解析 / MINERU 云端解析（前端三模式映射） */
    @TableField("engine")
    private String engine;

    /** 实际处理路径摘要（执行后回写，记录页展示）：直传视觉 / MinerU 结构化 / 视觉直读 / 文本分块 / 文本整理 */
    @TableField("process_path")
    private String processPath;

    /** 当前解析的文件序号（1-based；PARSING 阶段有效，前端显示"解析 2/3"） */
    @TableField("current_file_index")
    private Integer currentFileIndex;

    /** 已确认导入（confirmImport 成功后置 true；recent 列表不再展示，同目标重复 confirm 幂等） */
    private Boolean confirmed;

    /** TXT / MD / DOCX / PDF / IMAGE */
    @TableField("file_type")
    private String fileType;

    /** PENDING / PROCESSING / SUCCESS / FAILED */
    private String status;

    /** PARSING / AI_GENERATING / VALIDATING / DONE */
    private String stage;

    private Integer progress;

    /** 解析出的题目数组（ContentPackageQuestion 列表 JSON） */
    @TableField("result_json")
    private String resultJson;

    /** 题数差异检测提示（非思考模式识别题数差距过大时提醒用户；可空） */
    @TableField("warning_hint")
    private String warningHint;

    private String error;

    @TableField("created_at")
    private LocalDateTime createdAt;

    @TableField("finished_at")
    private LocalDateTime finishedAt;
}
