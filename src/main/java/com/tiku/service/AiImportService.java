package com.tiku.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tiku.config.AiSettings;
import com.tiku.dto.AiImportConfirmResponse;
import com.tiku.dto.AiJobResponse;
import com.tiku.mapper.AiImportJobMapper;
import com.tiku.model.AiImportJob;
import com.tiku.model.ContentPackageMaterial;
import com.tiku.model.ContentPackageQuestion;
import com.tiku.model.OptionItem;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CancellationException;
import java.util.concurrent.Executor;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * AI 辅助文件导入（见 doc/ai-import-spec.md）。
 * 流程：上传建任务（文件落盘）→ 异步执行（解析→AI整理→校验）→ 前端轮询 → 预览确认导入。
 * 性能：文本类文档按题号边界切块（每块约 10-15 题），并行调用模型后合并，40 题从 3-5 分钟降到 1 分钟左右；
 * 扫描件/图片仍走单次多模态调用（不分块）。卷末答案列表会附加到每一块，保证答案来源标记正确。
 */
@Slf4j
@Service
public class AiImportService {

    private static final Set<String> VALID_TYPES = Set.of("SINGLE", "MULTIPLE", "JUDGE", "SUBJECTIVE");

    /**
     * 内嵌图调用策略（实测对比定稿）：单次多模态调用图片引用更精准（分块按页归属时模型偶发
     * 选项错配/同编号重复）。图片 ≤ MAX_SINGLE_CALL_IMAGES 走单次调用（质量优先）；
     * 超过（超大文档）回退分块按页归属（防上下文过大/超时）。
     */
    private static final int MAX_SINGLE_CALL_IMAGES = 20;

    // ===== 视觉单次路径（简化 prompt + 整份 PDF 单次调用，实测定稿） =====
    /** 单次视觉调用的页数上限：超过走视觉分页（防请求体过大/超时） */
    private static final int MAX_SINGLE_VISION_PAGES = 15;
    /** 视觉分页页数上限：超过回退旧路径（每块一次多模态调用，页数过多成本/超时不可控） */
    private static final int MAX_VISION_PAGES = 24;
    /** 题数差异检测：输出题数 < 参考题数 * 该比例 时提示用户（复杂排版/模型劣化） */
    private static final double QUESTION_DIFF_RATIO = 0.6;
    /** 视觉路径差异检测比例（视觉路径应更完整，阈值更严） */
    private static final double VISION_DIFF_RATIO = 0.75;
    /** 题数差异检测：参考题数低于该值不检测（题号不可靠） */
    private static final int QUESTION_DIFF_MIN_REF = 8;

    private final AiImportJobMapper jobMapper;
    private final AiConfigService aiConfigService;
    private final AiClientService aiClientService;
    private final DocumentParserService documentParserService;
    private final MineruParseService mineruParseService;
    private final ContentPackageService contentPackageService;
    private final QuestionBankService questionBankService;
    private final ImageStorageService imageStorageService;
    private final com.tiku.mapper.MaterialMapper materialMapper;
    private final ObjectMapper objectMapper;
    private final AiImportResultCodec resultCodec;
    private final AiImportPromptFactory promptFactory;
    private final AiImportDocumentPipeline documentPipeline;
    private final AiImportTextStructure textStructure;
    private final AiImportSourceTextService sourceTextService;
    private final AiImportResultParser resultParser;
    private final AiImportFormulaService formulaService;
    private final AiImportAnswerService answerService;
    private final AiImportVisionQualityService visionQualityService;
    private final AiImportVisionMissingPageService visionMissingPageService;
    private final AiImportImageReferenceService imageReferenceService;
    private final AiImportFailureClassifier failureClassifier;
    private final Executor aiImportExecutor;
    private final java.util.concurrent.ExecutorService aiChunkExecutor;
    private final AiJobEventService aiJobEventService;
    private final AiImportJobStorageService jobStorageService;
    private final AiImportVisionLayoutService visionLayout;
    private final AiImportJobLifecycleService lifecycle;
    private final AiImportModelCallService modelCalls;

    public AiImportService(AiImportJobMapper jobMapper,
                           AiConfigService aiConfigService,
                           AiClientService aiClientService,
                           DocumentParserService documentParserService,
                           MineruParseService mineruParseService,
                           ContentPackageService contentPackageService,
                           QuestionBankService questionBankService,
                           ImageStorageService imageStorageService,
                           com.tiku.mapper.MaterialMapper materialMapper,
                           ObjectMapper objectMapper,
                           AiImportResultCodec resultCodec,
                           AiImportPromptFactory promptFactory,
                           AiImportDocumentPipeline documentPipeline,
                           AiImportTextStructure textStructure,
                           AiImportSourceTextService sourceTextService,
                           AiImportResultParser resultParser,
                           AiImportFormulaService formulaService,
                           AiImportAnswerService answerService,
                           AiImportVisionQualityService visionQualityService,
                           AiImportVisionMissingPageService visionMissingPageService,
                           AiImportImageReferenceService imageReferenceService,
                           AiImportFailureClassifier failureClassifier,
                           @Qualifier("aiImportExecutor") Executor aiImportExecutor,
                           @Qualifier("aiChunkExecutor") java.util.concurrent.ExecutorService aiChunkExecutor,
                           AiJobEventService aiJobEventService,
                           AiImportJobStorageService jobStorageService,
                           AiImportVisionLayoutService visionLayout,
                           AiImportJobLifecycleService lifecycle,
                           AiImportModelCallService modelCalls) {
        this.jobMapper = jobMapper;
        this.aiConfigService = aiConfigService;
        this.aiClientService = aiClientService;
        this.documentParserService = documentParserService;
        this.mineruParseService = mineruParseService;
        this.contentPackageService = contentPackageService;
        this.questionBankService = questionBankService;
        this.imageStorageService = imageStorageService;
        this.materialMapper = materialMapper;
        this.objectMapper = objectMapper;
        this.resultCodec = resultCodec;
        this.promptFactory = promptFactory;
        this.documentPipeline = documentPipeline;
        this.textStructure = textStructure;
        this.sourceTextService = sourceTextService;
        this.resultParser = resultParser;
        this.formulaService = formulaService;
        this.answerService = answerService;
        this.visionQualityService = visionQualityService;
        this.visionMissingPageService = visionMissingPageService;
        this.imageReferenceService = imageReferenceService;
        this.failureClassifier = failureClassifier;
        this.aiImportExecutor = aiImportExecutor;
        this.aiChunkExecutor = aiChunkExecutor;
        this.aiJobEventService = aiJobEventService;
        this.jobStorageService = jobStorageService;
        this.visionLayout = visionLayout;
        this.lifecycle = lifecycle;
        this.modelCalls = modelCalls;
    }

    // ==================== 创建任务 ====================

    /**
     * 创建任务：支持多文件（题目/答案分文件时一起上传）+ aiSupplement 开关 + thinking（本次任务覆盖全局思考配置）。
     * 注意：不使用 @Transactional——executeJob 在另一线程立即执行，
     * 事务未提交时 selectById 会查不到记录（竞态导致任务永久 PENDING）。
     */
    public Long createJob(List<String> fileNames, List<byte[]> files, Long bankId, Boolean aiSupplement,
                          Boolean thinking, String engine) throws IOException {
        if (!aiConfigService.isConfigured()) {
            throw new IllegalArgumentException("请先在「设置-AI 配置」中填写模型信息");
        }
        if (bankId != null) {
            questionBankService.findByIdOrThrow(bankId);
        }
        if (fileNames == null || fileNames.isEmpty()) {
            throw new IllegalArgumentException("请至少选择一个文件");
        }
        //文件名净化：剔除路径分隔符/控制字符/逗号等（逗号是 file_names 的拼接与解析分隔符），
        //防 `..\` 路径穿越写盘与含逗号文件名回读错位
        fileNames = fileNames.stream().map(AiImportJobStorageService::sanitizeFileName).toList();
        if ("MINERU".equals(engine) && !aiConfigService.isMineruConfigured()) {
            throw new IllegalArgumentException("已选择 MinerU 云端解析，但「设置」中未配置 MinerU 解析 API Key");
        }
        try {
            //扩展名预检（避免无效任务）
            for (int i = 0; i < fileNames.size(); i++) {
                documentParserService.parse(fileNames.get(i), files.get(i));
            }
        } catch (IOException e) {
            throw new IllegalArgumentException("文件读取失败：" + e.getMessage());
        }

        AiImportJob job = new AiImportJob();
        job.setBankId(bankId);
        job.setFileName(fileNames.get(0));
        job.setFileNames(String.join(",", fileNames));
        job.setFileType(detectType(fileNames.get(0)));
        job.setAiSupplement(aiSupplement == null || aiSupplement);
        job.setThinking(thinking);
        job.setEngine(engine == null || engine.isBlank() ? "AUTO" : engine);
        job.setStatus("PENDING");
        job.setStage("PARSING");
        job.setProgress(0);
        job.setCreatedAt(LocalDateTime.now());
        jobMapper.insert(job);

        //文件落盘（异步线程不能安全持有 MultipartFile）。落盘失败时回收刚创建的任务，
        //避免数据库遗留永远无法执行的 PENDING 任务。
        try {
            jobStorageService.storeInputFiles(job.getId(), fileNames, files);
        } catch (IOException | RuntimeException e) {
            jobMapper.deleteById(job.getId());
            lifecycle.deleteJobFiles(job.getId());
            throw e;
        }

        //异步执行
        Long jobId = job.getId();
        try {
            aiImportExecutor.execute(() -> executeJob(jobId));
        } catch (java.util.concurrent.RejectedExecutionException e) {
            //单线程执行器 + 有界队列已满：清理刚插入的任务行与已落盘文件，防止"排队中但永不执行"的孤儿任务
            jobMapper.deleteById(jobId);
            lifecycle.deleteJobFiles(jobId);
            throw new IllegalArgumentException("AI 导入任务较多，请等待进行中的任务完成后再试");
        }
        return jobId;
    }

    // ==================== 异步执行 ====================

    private void executeJob(Long jobId) {
        AiImportJob job = jobMapper.selectById(jobId);
        if (job == null) {
            //兜底：理论上 createJob 无事务后不会发生，防止任务永久 PENDING
            log.error("AI 导入任务不存在，无法执行：{}", jobId);
            return;
        }
        //排队期间被取消（PENDING→CANCELED，如用户删除任务或应用重启前已标记）：
        //不允许执行（否则会把 CANCELED 复活成 PROCESSING/SUCCESS），残留文件在此清理
        if ("CANCELED".equals(job.getStatus())) {
            lifecycle.deleteJobFiles(jobId);
            return;
        }
        try {
            lifecycle.updateStage(job, "PROCESSING", "PARSING", 10);
            aiJobEventService.publish(jobId, lifecycle.getJob(jobId));

            //1. 解析所有文件并合并（文本拼接 + 图片合并；答案文件与题目文件拼接后等价于"带卷末答案的文档"）
            //   进度按文件数推进（PARSING 10 → 35），多文件时用户可见"解析 2/3"的反馈
            Path jobDir = jobStorageService.jobDirectory(jobId);
            AiImportDocumentPipeline.PreparedDocument prepared = documentPipeline.prepare(job, (current, total) -> {
                job.setCurrentFileIndex(current);
                lifecycle.updateStage(job, "PROCESSING", "PARSING", 10 + current * 25 / total);
                aiJobEventService.publish(jobId, lifecycle.getJob(jobId));
            });
            List<String> texts = prepared.texts();
            List<AiClientService.ImageData> images = prepared.images();
            List<DocumentParserService.ExtractedImage> extracted = prepared.extractedImages();
            List<String> pageTexts = prepared.pageTexts();
            StringBuilder warning = new StringBuilder(prepared.warning());
            if (!warning.isEmpty()) {
                warning.append(' ');
            }
            String[] names = prepared.fileNames().toArray(String[]::new);
            boolean mineruUsed = prepared.mineruUsed();
            boolean allPlain = prepared.allPlain();
            String localEvidence = prepared.localEvidence();
            int formulaTotal = prepared.formulaImageCount();
            Set<Integer> formulaNosAll = prepared.formulaImageNumbers();
            boolean engineMineru = "MINERU".equals(job.getEngine());
            //AUTO/LOCAL 引擎的纯文本检测（全部文件纯文本才生效）：强制走"本地文本分块"（跳过视觉单次/分页）——
            //纯文本文本层完整，文本分块保真且无 OCR 噪声；视觉路径对短题（判断题）漏题（quiz 实测 5/7）。
            //LOCAL 同样生效："最快"预设（LOCAL+关思考）对纯文字文档应走最快的文本分块
            boolean autoPlain = !engineMineru && allPlain;
            if (autoPlain) {
                log.info("AI 导入任务 {} 检测为纯文本（无图 + 文本层完整），走本地文本分块", jobId);
            }
            String mergedText = texts.isEmpty() ? null : String.join("\n\n========== 下一份文件 ==========\n\n", texts);
            //视觉单次路径条件提前计算（PDF-MD 路由依赖）
            boolean singlePdfWithText = texts.size() == 1 && pageTexts != null && !pageTexts.isEmpty()
                    && "PDF".equals(detectType(names[0]));
            //PDF 直传路由（未走 MinerU 的有文本层 PDF）：整页渲染（版式真相，网页端同款输入）+ 页文本层
            //（就地 [图片N] 标记）+ MD 输出——不再先过 MinerU；MinerU 降级为可选（开关显式开启才用）
            boolean pdfDirect = singlePdfWithText && !mineruUsed;
            //文本流带 [图片N] 标记 = 图片锚点已确定性写入文本（docx 本地解析 / MinerU 重建 / PDF 就地标记）：
            //分块按标记取图，不依赖模型数图能力 → 图片路径不强制思考模式
            boolean marksInText = (mergedText != null && mergedText.contains("[图片")) || pdfDirect;
            //PDF 直传封面/说明页图片过滤：第一页粉笔介绍图等广告图会被模型当题目图引用
            //（实测输出"[图片1]+空选项"垃圾块，且抢占开头题目的注意力）
            if (pdfDirect && !extracted.isEmpty() && pageTexts != null && !pageTexts.isEmpty()) {
                int firstQuestionPage = -1;
                for (int p = 0; p < pageTexts.size(); p++) {
                    if (looseQuestionCount(pageTexts.get(p)) >= 1) {
                        firstQuestionPage = p;
                        break;
                    }
                }
                if (firstQuestionPage > 0) {
                    List<DocumentParserService.ExtractedImage> kept = new ArrayList<>();
                    for (DocumentParserService.ExtractedImage e : extracted) {
                        if (e.pageNo() >= firstQuestionPage) {
                            kept.add(e);
                        }
                    }
                    if (kept.size() != extracted.size()) {
                        log.info("AI 导入任务 {} PDF 直传过滤封面图片：{} → {} 张", jobId, extracted.size(), kept.size());
                        extracted = kept;
                    }
                }
            }

            lifecycle.updateStage(job, "PROCESSING", "AI_GENERATING", 40);
            aiJobEventService.publish(jobId, lifecycle.getJob(jobId));

            //用户取消检查（AI 调用前，节省一次模型调用）；取消确认后清理文件再停
            if (lifecycle.isCanceled(jobId)) {
                lifecycle.cleanupCanceledJobFiles(jobId);
                return;
            }

            //2. AI 整理（aiSupplement=false 时禁止 AI 补充缺失答案/解析）
            AiSettings settings = aiConfigService.load();
            //本次任务的思考模式覆盖全局配置（前端导入对话框"AI 思考模式"开关）
            if (job.getThinking() != null) {
                settings.setThinking(job.getThinking());
            }
            boolean aiSupplement = Boolean.TRUE.equals(job.getAiSupplement());
            //内嵌图片路径仅思考开启时启用（实测：关闭思考时模型漏引用/错配/幻觉编号，不可靠）；
            //思考关闭时保持纯文本路径（图忽略），提示文案由前端导入对话框负责（用户自行选择）
            boolean thinkingOn = Boolean.TRUE.equals(settings.getThinking());
            //MinerU 路径的图片引用是确定性的（content_list 顺序 + 页归属），不依赖模型数图能力 → 思考模式非必需；
            //docx 本地解析的 [图片N] 标记同理（marksInText）→ 同样不依赖思考模式
            boolean hasExtracted = !extracted.isEmpty() && (thinkingOn || mineruUsed || marksInText);

            //视觉单次路径：单文件 PDF 有文本层 + 页数 ≤ 上限（简化 prompt + 整份单次调用，
            //实测 40 题 10 页：无思考 48s / 思考 124s，均 40/40 不丢题、图片位置占位精准）。
            //PDF 直传路由优先（pdfDirect）：视觉路径仅剩"未命中直传条件"的兜底
            boolean visionSingle = singlePdfWithText && pageTexts.size() <= MAX_SINGLE_VISION_PAGES
                    && !autoPlain && !pdfDirect;

            //阶段 4：内嵌图临时落盘（imports/{jobId}/images/{N}.png，N = 全局编号从 1 开始），确认导入时转正式存储
            //（视觉单次路径无需思考也可用图片：占位符方案不依赖模型编号能力；无思考时也落盘供匹配）
            if (hasExtracted || (visionSingle && !extracted.isEmpty())) {
                jobStorageService.storeExtractedImages(jobId, extracted);
                log.info("AI 导入任务 {} 提取内嵌图片 {} 张（按编号 1..{} 临时落盘）", jobId,
                        extracted.size(), extracted.size());
                //把 [图片N] 标记按页内位置插入逐页文本（模型可见图在文本中的位置，引用更精准）
                //视觉单次路径不用文本标记（占位符方案）；MinerU 路径由 chatChunked 按页归属编号；
                //PDF 直传路径也不插标记——整页截图已给版式真相，文本标记位置是 y 坐标近似，
                //实测误插到选项区/页首导致模型输出"[图片1]+空选项"垃圾块并跳过开头题目
                if (hasExtracted && !pageTexts.isEmpty() && !visionSingle && !mineruUsed && !pdfDirect) {
                    pageTexts = insertImageMarks(pageTexts, extracted);
                }
            }
            //PDF 直传：整页渲染（版式真相，网页端同款输入；JPEG 压缩控请求体）——渲染失败退化为纯文本分块
            List<AiClientService.ImageData> pdfPageImages = null;
            if (pdfDirect) {
                try {
                    pdfPageImages = documentParserService.renderAllPages(
                            Files.readAllBytes(jobDir.resolve("0-" + names[0])), AiImportModelCallService.VISION_PAGE_DPI, AiImportModelCallService.VISION_PAGE_JPEG_QUALITY);
                    log.info("AI 导入任务 {} PDF 直传：{} 页整页渲染", jobId, pdfPageImages.size());
                } catch (IOException e) {
                    log.warn("AI 导入任务 {} 整页渲染失败，PDF 直传退化为纯文本分块：{}", jobId, e.getMessage());
                }
            }
            //视觉路径（chatVisionSingle/chatVisionPages）保持原行为（思考模式下答案较可靠，模型直接输出）；
            //分块路径（chatChunked）内部改为"整理阶段"prompt（extractOnly），答案由 postProcessAnswers 统一处理
            String systemPrompt = promptFactory.buildSystemPrompt(aiSupplement, false);
            AiImportResult parsedResult;
            boolean visionPages = false;
            if (mineruUsed) {
                //MinerU 路径（可选开关显式开启）：增强文本（阅读顺序 + 表格 HTML + 公式 LaTeX + [图片N] 标记内嵌）
                //→ 分块并行出题；块内图片按"文本流中的 [图片N] 标记"取图（marksEmbedded=true）
                visionPages = false;
                parsedResult = modelCalls.chatChunked(settings, systemPrompt, texts, warning.toString().trim(),
                        job, jobId, aiSupplement, pageTexts, extracted, true, localEvidence, null);
            } else if (visionSingle) {
                //视觉单次（兜底）：整页渲染 + 页文本层 + 简化 prompt（位置占位符）→ 单次多模态调用
                visionPages = true;
                parsedResult = modelCalls.chatVisionSingle(settings, pageTexts, extracted,
                        mergedText, warning.toString().trim(), job, jobId, aiSupplement, jobDir, names[0]);
            } else if (thinkingOn && singlePdfWithText && pageTexts.size() <= MAX_VISION_PAGES && !autoPlain && !pdfDirect) {
                //视觉分页路径（兜底）：思考模式 + 整页渲染图 + 页文本层 + 内嵌图编号
                visionPages = true;
                parsedResult = modelCalls.chatVisionPages(settings, systemPrompt, pageTexts, extracted,
                        mergedText, warning.toString().trim(), job, jobId, aiSupplement, jobDir, names[0]);
            } else if (marksInText && (hasExtracted || pdfDirect)) {
                //docx 本地解析（文本流带标记）→ marksEmbedded 分块按标记取图；
                //PDF 直传（整页截图给版式真相）→ 按页归属取图 + 整页截图，文本不插标记
                boolean mdMarks = marksInText && !pdfDirect;
                //公式密集 docx（MathType OLE 渲染图 ≥5）：非思考模式模型不细看公式图 → 残留 [图片N] 不转 LaTeX
                //（实测物理卷 Q11 十五个符号图全残留，思考模式全部转写）→ 自动启用思考保证公式转写
                if (mdMarks && formulaTotal >= 5 && !thinkingOn) {
                    log.info("AI 导入任务 {} 检测到 {} 个公式图（WMF/EMF），自动启用思考模式保证公式 LaTeX 转写", jobId, formulaTotal);
                    warning.append("检测到 ").append(formulaTotal)
                            .append(" 个公式图，已自动启用思考模式以保证公式正确转写为 LaTeX ");
                    settings.setThinking(true);
                    thinkingOn = true;
                }
                parsedResult = modelCalls.chatChunked(settings, systemPrompt, texts, warning.toString().trim(),
                        job, jobId, aiSupplement, pageTexts, extracted, mdMarks, null,
                        pdfDirect ? pdfPageImages : null);
            } else if (!images.isEmpty() || (hasExtracted && (pageTexts.isEmpty() || names.length > 1
                    || extracted.size() <= MAX_SINGLE_CALL_IMAGES))) {
                //多模态单次调用：扫描件/纯图片；docx 内嵌图（无页概念）；多文件含图（页归属不可靠）
                //（单文件 PDF 内嵌图走分块按页归属，见 chatChunked）
                List<AiClientService.ImageData> allImages = new ArrayList<>(images);
                for (DocumentParserService.ExtractedImage e : extracted) {
                    allImages.add(e.image());
                }
                AiSettings vision = promptFactory.buildVisionSettings(settings);
                String userPrompt = promptFactory.buildUserPrompt(mergedText, warning.toString().trim());
                //内嵌图编号规则（[图片1]..[图片N]，图片随消息按编号顺序提供）
                if (hasExtracted) {
                    userPrompt += promptFactory.buildImageRefRule(1, extracted.size());
                }
                String aiOutput = aiClientService.chatWithImages(vision, systemPrompt, userPrompt, allImages, true);
                //单次多模态路径（扫描件/纯图片/含图）：sourceText 传 mergedText（可能为 null → 跳过答案证据检查）；
                //材料组识别启用（资料分析扫描件/图片常见）
                parsedResult = modelCalls.parseAndValidate(aiOutput, aiSupplement, mergedText, true, false);
            } else if (hasExtracted) {
                //单文件 PDF 内嵌图：分块按页归属图片并行（思考模式下图片编号引用）
                parsedResult = modelCalls.chatChunked(settings, systemPrompt, texts, warning.toString().trim(),
                        job, jobId, aiSupplement, pageTexts, extracted, false, null, null);
            } else {
                //文本路径：按题号切块并行（卷末答案列表附加到每块），不可切分时内部回退单次调用
                parsedResult = modelCalls.chatChunked(settings, systemPrompt, texts, warning.toString().trim(),
                        job, jobId, aiSupplement, null, List.of(), false, null, null);
            }

            //题干题号前缀剥离：模型常把块文本行首题号抄进题干（"45. 为庆祝…"），与题号一致时剥除
            if (parsedResult != null && !parsedResult.questions().isEmpty()) {
                stripStemQuestionNumberPrefix(parsedResult.questions());
            }

            //PDF 直传图题兜底归位（配图以"模型看图主导"——块输入含整页截图+块内图，模型按截图引用 [图片N]；
            //本步只对模型未引用的图形题按版面坐标补图，模型已引用的题一律不动）
            if (pdfDirect && parsedResult != null && !parsedResult.questions().isEmpty()) {
                parsedResult = visionLayout.assignPdfFigureImages(parsedResult, pageTexts, extracted, jobDir, names[0], jobId);
            }

            //残留公式图 LaTeX 转写兜底（docx 公式路径）：模型整理时可能漏转公式图 [图片N]
            //（非思考/模型波动实测均见）→ 按"docx 解析标记的公式图编号"收集残留，一次思考调用批量转写回填
            if (parsedResult != null && !formulaNosAll.isEmpty() && !extracted.isEmpty()) {
                parsedResult = formulaService.replaceResidualFormulaImages(parsedResult, formulaNosAll, extracted, settings, jobId);
            }

            //用户取消检查（AI 调用后：丢弃结果，不写库）；取消确认后清理文件再停
            if (lifecycle.isCanceled(jobId)) {
                lifecycle.cleanupCanceledJobFiles(jobId);
                return;
            }

            //回写"实际处理路径"摘要（记录页展示本次走了哪条路；按优先级取主导路径）
            String processPath;
            if (mineruUsed) {
                processPath = "MinerU 结构化";
            } else if (pdfDirect) {
                processPath = "直传视觉";
            } else if (visionPages || visionSingle) {
                processPath = "视觉直读";
            } else if (autoPlain) {
                processPath = "文本分块";
            } else if (!images.isEmpty() || hasExtracted) {
                processPath = "视觉配图";
            } else {
                processPath = "文本整理";
            }
            if (!processPath.equals(job.getProcessPath())) {
                job.setProcessPath(processPath);
                jobMapper.updateById(job);
                log.info("AI 导入任务 {} 实际处理路径：{}", jobId, processPath);
            }

            lifecycle.updateStage(job, "PROCESSING", "VALIDATING", 85);
            aiJobEventService.publish(jobId, lifecycle.getJob(jobId));

            //题数差异检测：宽松统计参考题数（行首+行尾题号，说明区排除）与输出题数差距过大 → 提示用户
            //（复杂排版如题号与题干同行时非思考模式会漏检题号，模型忠实还原残缺输入 → 题数偏少）
            int refCount = referenceQuestionCount(mergedText);
            int gotCount = parsedResult.questions().size();
            double diffRatio = visionPages ? VISION_DIFF_RATIO : QUESTION_DIFF_RATIO;
            if (refCount >= QUESTION_DIFF_MIN_REF && gotCount > 0 && gotCount < refCount * diffRatio) {
                String hint;
                if (visionPages) {
                    hint = "文档约 " + refCount + " 题，本次整理出 " + gotCount + " 题，请在预览中检查是否有遗漏。";
                } else if (warning.toString().contains("本地无法解码") || warning.toString().contains("回退本地解析")) {
                    //公式图/插图缺失导致的缺题：思考模式帮不上，应引导走 MinerU/转 PDF
                    hint = "文档约 " + refCount + " 题，本次整理出 " + gotCount + " 题：解析提示有公式图/图片缺失，"
                            + "建议开启 MinerU 解析，或将文档另存为 PDF 后重新导入。";
                } else {
                    hint = "文档约 " + refCount + " 题，本次整理出 " + gotCount + " 题（复杂排版如题号与题干同行时可能遗漏），"
                            + "建议开启「AI 思考模式」重新导入以获得完整结果。";
                }
                job.setWarningHint(hint);
            }
            //MinerU 题号差异预警（解析阶段 ParseResult.warning 含"疑似缺失题号"）→ 提升为 warningHint（预览可见）
            //题数差异检测未命中时（参考题数统计漏检）也要让用户看到解析层的缺题预警
            if ((job.getWarningHint() == null || job.getWarningHint().isBlank())
                    && warning.toString().contains("疑似缺失题号")) {
                String w = warning.toString().trim();
                job.setWarningHint(w.length() > 300 ? w.substring(0, 300) + "…" : w);
            }

            //3. 结果入库（校验已在各块 parseAndValidate 中完成）
            //按题号排序：补漏题（fillMissingQuestions/视觉页补漏）是追加在结果末尾的——
            //图形题等主整理漏掉的题经补漏后排在最后（实测 91/92/93/94/45/113 等图形题被排到末尾）。
            //有题号的按题号升序归位，无题号（定位失败）保持相对顺序排末尾；稳定排序保证同题号相对顺序。
            List<ContentPackageQuestion> sorted = new ArrayList<>(parsedResult.questions());
            sorted.sort(java.util.Comparator.comparingInt(
                    (ContentPackageQuestion q) -> q.getQuestionNumber() == null
                            ? Integer.MAX_VALUE : q.getQuestionNumber()));
            parsedResult = new AiImportResult(sorted, parsedResult.materials());
            String resultJson = resultCodec.serialize(parsedResult);
            //终态落库走条件更新（WHERE status <> 'CANCELED'）：取消竞态下线程只能停，
            //绝不能把 CANCELED 复活成 SUCCESS/FAILED（旧实现整行 updateById 会覆盖）
            if (!lifecycle.writeTerminal(jobId, "SUCCESS", null, null, resultJson)) {
                lifecycle.cleanupCanceledJobFiles(jobId);
                return;
            }
        } catch (Exception e) {
            AiImportFailureClassifier.Failure failure = failureClassifier.classify(e);
            log.error("AI 导入任务 {} 处理失败：code={}, diagnostic={}", jobId,
                    failure.code(), failure.diagnosticSummary());
            //失败终态同样条件更新；任务已被取消时不落失败原因（不覆盖用户的取消意图），只清理文件退出。
            //面向用户的文案 = 分类后的可操作提示 + 脱敏诊断摘要（截断到列长上限）：
            //既保留"具体是什么错"（旧实现直接写 e.getMessage()，用户能看到可动手的原因），
            //又不会把 API Key 之类的凭据写进数据库（分类器已按 sk-…/apiKey= 规则脱敏）。
            String failureMessage = failure.diagnosticSummary().isBlank()
                    ? failure.userMessage()
                    : AiImportTexts.truncate(failure.userMessage() + "（诊断：" + failure.diagnosticSummary() + "）", 500);
            if (!lifecycle.writeTerminal(jobId, "FAILED", failureMessage, failure.code(), null)) {
                lifecycle.cleanupCanceledJobFiles(jobId);
                return;
            }
        }
        //推送最终快照并结束事件流（SSE 订阅方收到后跳预览/展示失败原因）
        //取消的任务不推送终态：deleteJob 已发布 CANCELED 快照，订阅方各自处理
        aiJobEventService.complete(jobId, lifecycle.getJob(jobId));
    }


    // ==================== 任务行/文件/事件：委托给 AiImportJobLifecycleService ====================
    // 主服务保留这些门面是为了让控制器只依赖一个入口；实现都在生命周期服务里。

    public void recoverInterruptedJobsOnStartup() {
        lifecycle.recoverInterruptedJobsOnStartup();
    }

    public AiJobResponse getJob(Long jobId) {
        return lifecycle.getJob(jobId);
    }

    public List<AiJobResponse> listActiveJobs() {
        return lifecycle.listActiveJobs();
    }

    public List<AiJobResponse> listRecentJobs(int limit) {
        return lifecycle.listRecentJobs(limit);
    }

    public org.springframework.web.servlet.mvc.method.annotation.SseEmitter subscribeJob(Long jobId) {
        return lifecycle.subscribeJob(jobId);
    }

    public void deleteJob(Long jobId) {
        lifecycle.deleteJob(jobId);
    }

    // ==================== 查询与确认 ====================

    // ==================== 最近未确认任务 / 取消删除 ====================

    // ==================== 任务临时图片（预览素材区） ====================

    /** 素材区图片信息：编号 + 文件名 + 扩展名（imports/{jobId}/images/ 目录，按编号排序） */
    public record JobImageInfo(int num, String fileName, String ext) {
    }

    /**
     * 任务临时图片列表（预览页"图片素材区"：展示所有提取的图片，供用户拖入/点击插入 [图片N] 标记）。
     * 图片文件：imports/{jobId}/images/{N}.png（MinerU 路径转 PNG；本地路径同契约）。
     */
    public List<JobImageInfo> listJobImages(Long jobId) {
        lifecycle.findByIdOrThrow(jobId);
        try {
            return jobStorageService.listImages(jobId).stream()
                    .map(image -> new JobImageInfo(image.num(), image.fileName(), image.ext()))
                    .toList();
        } catch (IllegalStateException e) {
            log.warn("读取任务 {} 图片列表失败：{}", jobId, e.getMessage());
            return List.of();
        }
    }

    /** 读取任务临时图片字节（素材区渲染用；编号不存在 → 抛异常由 404 处理） */
    public byte[] readJobImage(Long jobId, int num) throws IOException {
        lifecycle.findByIdOrThrow(jobId);
        return jobStorageService.readImage(jobId, num);
    }

    // ==================== 材料素材（资料分析/阅读材料题） ====================

    /**
     * 任务材料素材列表（预览页"材料素材区"）：任务结果中的共享材料（本地检测的 m1 或模型输出的材料）。
     * 材料与图片素材同级：展示为素材块，用户拖入/点击关联到题目材料区（confirm 提交编辑后的题目携带 materialKey）。
     * content 中可能含 [图片N] 标记（图表），前端经 /images/{num} 渲染。
     */
    public List<ContentPackageMaterial> listMaterialSnippets(Long jobId) {
        AiImportJob job = lifecycle.findByIdOrThrow(jobId);
        if (job.getResultJson() == null || job.getResultJson().isBlank()) {
            return List.of();
        }
        try {
            return resultCodec.parse(job.getResultJson()).materials();
        } catch (IOException e) {
            log.warn("读取任务 {} 材料素材失败：{}", jobId, e.getMessage());
            return List.of();
        }
    }

    /** 预览确认后导入：新建题库（bankId 空）或追加到指定题库；幂等（同目标重复确认返回上次结果） */
    @Transactional
    public AiImportConfirmResponse confirmImport(Long jobId, Long bankId,
                                                 List<ContentPackageQuestion> editedQuestions,
                                                 List<ContentPackageMaterial> editedMaterials) {
        //行锁读取：串行化"读 confirmed → 导入 → 写 confirmed"，防并发双击/重放重复入库
        AiImportJob job = jobMapper.selectByIdForUpdate(jobId);
        if (job == null) {
            throw new NoSuchElementException("任务不存在：" + jobId);
        }
        if (Boolean.TRUE.equals(job.getConfirmed())) {
            //幂等：请求为空（前端默认）或与上次目标一致 → 返回上次结果，避免重复点击创建重复题库
            //仅当显式传入不同的 bankId 时才允许重新导入到新目标
            if (bankId == null || java.util.Objects.equals(job.getBankId(), bankId)) {
                return new AiImportConfirmResponse(job.getBankId(), countQuestions(job));
            }
        }
        if (!"SUCCESS".equals(job.getStatus())) {
            throw new IllegalArgumentException("任务未完成或已失败，无法导入");
        }
        AiImportResult parsed;
        if (editedQuestions != null && !editedQuestions.isEmpty()) {
            //预览页编辑后的题目/材料（含素材区拖入的材料引用）：以提交内容为准；
            //请求内容绕过 parseAndValidate（后端校验路径），必须在此补全校验（防非法内容直落库）
            validateEditedContent(editedQuestions, editedMaterials);
            parsed = new AiImportResult(editedQuestions,
                    editedMaterials == null ? List.of() : editedMaterials);
        } else {
            try {
                parsed = resultCodec.parse(job.getResultJson());
            } catch (IOException e) {
                throw new IllegalStateException("任务结果解析失败", e);
            }
        }
        if (parsed.questions().isEmpty()) {
            throw new IllegalArgumentException("没有可导入的题目");
        }
        Long targetBankId = bankId;
        if (targetBankId == null) {
            targetBankId = questionBankService.createQuestionBank(
                    new com.tiku.dto.QuestionBankCreateRequest(fileBaseName(job.getFileName()), "由 AI 导入生成"));
        } else {
            questionBankService.findByIdOrThrow(targetBankId);
        }
        //阶段 4：内嵌图落盘（[图片N] → [图片:正式文件名]）；未引用的编号保留原标记（预览可见）
        Map<String, String> imageRefs = imageReferenceService.importReferencedImages(jobId, targetBankId, parsed);
        //材料入库（materialKey → 本地 material_id；content 中图片引用已替换）
        Map<String, Long> materialIdByKey = new HashMap<>();
        if (!parsed.materials().isEmpty()) {
            int order = 0;
            for (ContentPackageMaterial m : parsed.materials()) {
                com.tiku.model.Material material = new com.tiku.model.Material();
                material.setBankId(targetBankId);
                material.setContent(imageReferenceService.replaceReferences(m.getContent(), imageRefs));
                material.setSortOrder(order++);
                LocalDateTime now = LocalDateTime.now();
                material.setCreatedAt(now);
                material.setUpdatedAt(now);
                materialMapper.insert(material);
                materialIdByKey.put(m.getMaterialKey(), material.getId());
            }
        }
        //题目文本中的图片引用替换（题干/参考答案/选项）
        for (ContentPackageQuestion q : parsed.questions()) {
            q.setContent(imageReferenceService.replaceReferences(q.getContent(), imageRefs));
            q.setReferenceAnswer(imageReferenceService.replaceReferences(q.getReferenceAnswer(), imageRefs));
            if (q.getOptions() != null) {
                q.setOptions(q.getOptions().stream()
                        .map(o -> new OptionItem(o.key(), imageReferenceService.replaceReferences(o.text(), imageRefs)))
                        .toList());
            }
        }
        int imported = contentPackageService.importQuestionsToBank(targetBankId, parsed.questions(), materialIdByKey);
        //回写实际导入目标 + 确认标记（recent 不再展示，幂等判断依据）
        job.setBankId(targetBankId);
        job.setConfirmed(true);
        jobMapper.updateById(job);
        //确认导入成功：任务文件（源文档/临时图）已无用，即时清理防磁盘泄漏；
        //注意：清理后若想把同一任务再导入其他题库，文本题目仍可导入，但临时图素材不可复用
        lifecycle.deleteJobFiles(jobId);
        return new AiImportConfirmResponse(targetBankId, imported);
    }

    /**
     * 预览编辑内容的后端校验（confirm 请求绕过 AI 输出校验路径，需在此兜底；
     * 规则与前端预览页校验一致，防非法/恶意内容直落库）：
     * - 题型合法；题干非空且限长；非主观题选项 ≥2 且文本非空；
     * - 答案允许为空（先入库后补配）；非空时 key 必须存在于选项 key（防永远判错）；
     * - 数量上限（防止整包巨型提交）。
     */
    private void validateEditedContent(List<ContentPackageQuestion> questions,
                                       List<ContentPackageMaterial> materials) {
        if (questions == null || questions.isEmpty()) {
            return; //空数组 = 用存库结果（旧行为）
        }
        if (questions.size() > 5000) {
            throw new IllegalArgumentException("题目数量超出上限（5000）");
        }
        int idx = 0;
        for (ContentPackageQuestion q : questions) {
            idx++;
            String where = "第 " + idx + " 题：";
            String type = q.getType();
            if (type == null || !VALID_TYPES.contains(type)) {
                throw new IllegalArgumentException(where + "未知题型：" + type);
            }
            if (q.getContent() == null || q.getContent().isBlank()) {
                throw new IllegalArgumentException(where + "题目题干为空");
            }
            if (q.getContent().length() > 20000) {
                throw new IllegalArgumentException(where + "题干过长（超过 20000 字符）");
            }
            if (q.getScore() != null && (q.getScore() <= 0 || q.getScore() > 1000)) {
                throw new IllegalArgumentException(where + "分值超出范围（0-1000）");
            }
            if (!"SUBJECTIVE".equals(type)) {
                if (!"JUDGE".equals(type)) {
                    //选项完整性始终校验（与答案无关）
                    if (q.getOptions() == null || q.getOptions().size() < 2
                            || q.getOptions().stream().anyMatch(o -> o == null
                            || o.text() == null || o.text().isBlank())) {
                        throw new IllegalArgumentException(where + "选项不完整（至少 2 个且文本非空）");
                    }
                    //答案允许为空（先入库后补配：无答案题作答不判题，见 StudyRecordService.submitAnswer）；
                    //有答案时才要求 key 存在于选项（防永远判错）
                    if (q.getAnswerKeys() != null && !q.getAnswerKeys().isEmpty()) {
                        Set<String> optionKeys = q.getOptions().stream()
                                .filter(Objects::nonNull)
                                .map(OptionItem::key)
                                .filter(Objects::nonNull)
                                .map(String::trim)
                                .collect(java.util.stream.Collectors.toSet());
                        for (String k : q.getAnswerKeys()) {
                            if (k == null || !optionKeys.contains(k.trim())) {
                                throw new IllegalArgumentException(where + "答案 key 不在选项中：" + k);
                            }
                        }
                    }
                }
            }
        }
        if (materials != null) {
            if (materials.size() > 500) {
                throw new IllegalArgumentException("材料数量超出上限（500）");
            }
            for (ContentPackageMaterial m : materials) {
                if (m.getMaterialKey() == null || m.getMaterialKey().isBlank()) {
                    throw new IllegalArgumentException("材料缺少 materialKey");
                }
            }
        }
    }




    private int countQuestions(AiImportJob job) {
        if (job.getResultJson() == null || job.getResultJson().isBlank()) {
            return 0;
        }
        try {
            return resultCodec.parse(job.getResultJson()).questions().size();
        } catch (IOException e) {
            return 0;
        }
    }

    // ==================== AI 输出解析校验 ====================

    // ==================== 分块并行（文本路径加速） ====================


    /**
     * 把图片编号标记按页内位置插入逐页清洗文本（模型可见图在文本中的位置）。
     * 行号映射：图中心 y 在页高中的相对位置 → 文本行（文本行序 = 视觉顺序，粉笔单栏 PDF 下近似成立）；
     * 页尾未落入任何行的图片追加到页尾。extracted 全局有序（页升序、页内 y 降序）。
     */
    private List<String> insertImageMarks(List<String> pageTexts, List<DocumentParserService.ExtractedImage> extracted) {
        List<String> result = new ArrayList<>();
        int cursor = 0;
        for (int p = 0; p < pageTexts.size(); p++) {
            String pageText = pageTexts.get(p);
            if (pageText == null || pageText.isBlank()) {
                result.add(pageText == null ? "" : pageText);
                continue;
            }
            //本页图片 = extracted[cursor .. cursor+count)（extracted 页升序、页内连续）
            int start = cursor;
            while (cursor < extracted.size() && extracted.get(cursor).pageNo() == p) {
                cursor++;
            }
            List<DocumentParserService.ExtractedImage> imgs = extracted.subList(start, cursor);
            if (imgs.isEmpty()) {
                result.add(pageText);
                continue;
            }
            String[] lines = pageText.split("\n", -1);
            int totalLines = Math.max(1, lines.length);
            List<String> out = new ArrayList<>();
            int imgIdx = 0;
            for (String line : lines) {
                out.add(line);
                while (imgIdx < imgs.size()) {
                    DocumentParserService.ExtractedImage e = imgs.get(imgIdx);
                    float centerY = e.sortY() + 30f; //近似中心（渲染高度未知，固定偏移）
                    float pageH = e.pageHeight() > 0 ? e.pageHeight() : 842f;
                    int targetLine = (int) ((1 - centerY / pageH) * totalLines);
                    if (targetLine <= out.size() - 1) {
                        out.add("[图片" + (start + imgIdx + 1) + "]");
                        imgIdx++;
                    } else {
                        break;
                    }
                }
            }
            //页尾剩余图片
            while (imgIdx < imgs.size()) {
                out.add("[图片" + (start + imgIdx + 1) + "]");
                imgIdx++;
            }
            result.add(String.join("\n", out));
        }
        return result;
    }

    /** 去重归一化：去空白、图片编号保留（"图21"）、删除下划线。
     *  下划线（____ 填空线）在转写中一个版本有、一个版本无是模型常见差异（言语理解填空线实测大量重复），
     *  去重键中删除（填空线语义等价；只影响去重键，不影响存储内容）。
     *  图片编号必须保留：图形题题干/选项=图，若剥离成空，同模板题干题（"从所给的四个选项中…规律性："）
     *  会被互相去重误删（实测 Q14 被 Q2 删、Q36 被 Q4 删——MinerU 图引用路径）；编号全局稳定 → 重叠块同题仍可去重。 */
    private String normForDedup(String s) {
        String t = (s == null ? "" : s).replaceAll("\\[图片(\\d+)\\]", "图$1");
        return t.replaceAll("\\s+", "").replace("_", "");
    }





    // ==================== 视觉单次路径（主路径：简化 prompt + 整份 PDF 单次调用） ====================

    /** 剥离题干开头的题号前缀（模型把块文本行首题号抄进题干："45. 为庆祝…" / "45.为庆祝…"）。
     *  仅当前缀数字与该题 questionNumber 一致时剥离，避免误伤以数字开头的正常题干。 */
    private void stripStemQuestionNumberPrefix(List<ContentPackageQuestion> questions) {
        for (ContentPackageQuestion q : questions) {
            Integer qn = q.getQuestionNumber();
            String c = q.getContent();
            if (qn == null || c == null) {
                continue;
            }
            String stripped = c.replaceFirst("^\\s*" + qn + "\\s*[.．、)）]\\s*", "");
            if (!stripped.equals(c)) {
                q.setContent(stripped);
                log.info("AI 导入任务 题干题号前缀剥离：题号 {} 前缀已移除", qn);
            }
        }
    }


    // ==================== 视觉分页路径（思考模式 + 单文件 PDF 有文本层） ====================

    /**
     * 宽松参考题数（仅用于差异检测，不拆分文本）：
     * 行首题号（现有 isQuestionNumberLine）+ 行尾题号（"题号+题干同行"格式，如"…规律性：2."、"火炉：蒲扇10."）。
     * 行尾限定：行尾 1-2 位数字 + 半角/全角点/顿号/右括号；排除孤立题号行（已计）与小数结尾（"15.8" 结尾非点号）。
     * 第一个"一、…"章节标题行之前的题号不计（试卷"注意事项 1．2．3．"是说明区不是题，高考卷常见）。
     * 参考值允许少量误计（如"增长15."），差异检测阈值（0.6）留有裕量。
     */
    private int looseQuestionCount(String text) {
        return textStructure.looseQuestionCount(text);
    }

    /**
     * 参考题数（题数差异检测用）：取"宽松统计"与"最长连续递增题号段"的较大值。
     * 前者对双栏/乱序题号宽容，后者对说明区噪声免疫；两者互补。
     */
    private int referenceQuestionCount(String text) {
        return textStructure.referenceQuestionCount(text);
    }

    /**
     * 小数/编号行排除（题号判定用）："1.5"、"3.14%"、"10.2万吨" 是小数行（不是题号）；
     * "1.2020年，全国服装出口额…"、"13.2019-2021年，我国集成电路…" 是"题号+年份（可带区间）"——
     * 4 位数字紧接"年"不算小数，否则材料检测会把题目行收进材料区、题号回填错乱。
     * 注意：String.matches 是全串匹配，必须带 .* 后缀。
     */
    private boolean isDecimalLikeLine(String t) {
        return textStructure.isDecimalLikeLine(t);
    }

    /**
     * 判断文本是否像"卷末答案列表"：每行是 "题号+答案码"（如 1.B / 2. C / 3:ABD / 5√ / 【1题答案】B），
     * 或整块是 "答案：1.A 2.B ..." 紧凑格式。多文件时用于识别最后一份是否为答案文件。
     * （逻辑统一在 AiAnswerFormat）
     */
    private boolean looksLikeAnswerList(String s) {
        return AiAnswerFormat.looksLikeAnswerList(s);
    }

    // ==================== Prompt 构建 ====================

    // ==================== 工具 ====================

    private String detectType(String fileName) {
        String lower = fileName.toLowerCase();
        if (lower.endsWith(".pdf")) return "PDF";
        if (lower.endsWith(".docx")) return "DOCX";
        if (lower.endsWith(".md") || lower.endsWith(".markdown")) return "MD";
        if (lower.endsWith(".png") || lower.endsWith(".jpg") || lower.endsWith(".jpeg")
                || lower.endsWith(".webp") || lower.endsWith(".bmp")) return "IMAGE";
        return "TXT";
    }

    private String fileBaseName(String fileName) {
        int dot = fileName.lastIndexOf('.');
        String base = dot > 0 ? fileName.substring(0, dot) : fileName;
        return base.length() > 100 ? base.substring(0, 100) : base;
    }

}
