package com.tiku.config;

import lombok.Data;

/**
 * AI 模型配置（BYOK，存本机配置文件 ${tiku.data-dir}/ai-config.json）。
 * OpenAI 兼容协议：baseUrl + apiKey + model。
 */
@Data
public class AiSettings {
    /** OpenAI 兼容端点，如 https://api.deepseek.com/v1 */
    private String baseUrl;
    /** 用户自带 Key，仅存配置文件，前端只见脱敏 */
    private String apiKey;
    /** 文本整理模型，如 deepseek-chat */
    private String model;
    /** 多模态模型（图片/扫描件用），缺省 = model */
    private String visionModel;
    /**
     * 是否开启模型"思考"（reasoning）：
     * false/null = 关闭（请求带 thinking disabled，速度快约一倍，默认）；
     * true = 开启（慢但可能更准；部分模型/端点不支持该参数，后端会自动降级重试）
     */
    private Boolean thinking;
    /**
     * MinerU 文档解析 API Key（可空 = 不使用 MinerU，走本地解析路径）。
     * 用于 pdf/图片/docx 的结构化解析（版面/OCR/公式/表格/图片提取），
     * 输出增强文本后仍由本项目的 AI 整理管道（BYOK 模型）出题。
     */
    private String mineruKey;
}
