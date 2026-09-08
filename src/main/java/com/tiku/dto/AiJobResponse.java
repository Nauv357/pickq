package com.tiku.dto;

import java.time.LocalDateTime;
import java.util.List;

import com.tiku.model.ContentPackageMaterial;
import com.tiku.model.ContentPackageQuestion;

/**
 * AI 导入任务状态（前端轮询 / SSE 推送）
 */
public record AiJobResponse(
        Long id,
        String status,        //PENDING / PROCESSING / SUCCESS / FAILED
        String stage,         //PARSING / AI_GENERATING / VALIDATING / DONE
        Integer progress,
        String fileName,
        String fileType,
        Boolean aiSupplement, //是否允许 AI 补充缺失答案/解析
        Boolean thinking,     //本次任务是否开启思考模式（true=开启；null=跟随全局配置）
        String engine,        //本次任务引擎意图：AUTO（智能推荐）/ LOCAL（本地）/ MINERU（扫描件增强）
        String processPath,   //实际处理路径摘要（执行后回写）：直传视觉 / MinerU 结构化 / 视觉直读 / 文本分块 / 文本整理
        Boolean confirmed,    //已确认导入（confirmImport 成功后 true）
        Integer fileCount,           //文件总数（多文件导入）
        Integer currentFileIndex,    //当前解析的文件序号（1-based，PARSING 阶段有效；前端显示"解析 2/3"）
        List<ContentPackageQuestion> questions,  //SUCCESS 后返回（预览用）
        List<ContentPackageMaterial> materials,  //共享材料（材料组识别，预览用；可空）
        String warningHint,  //题数差异检测提示（可空；预览页展示，不阻塞确认导入）
        String error,
        LocalDateTime createdAt,
        LocalDateTime finishedAt
) {
}
