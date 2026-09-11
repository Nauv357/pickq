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
    /** 整页渲染 DPI（与扫描件路径一致） */
    private static final int VISION_PAGE_DPI = 150;
    /** 分析/裁剪用整页渲染 DPI（占位符裁剪、PDF 矢量图兜底裁剪——质量优先，不发模型） */
    private static final int ANALYSIS_RENDER_DPI = 300;
    /** 整页渲染 JPEG 质量（整页图只作版式参考，压缩控制请求体；10 页约 1.4MB） */
    private static final float VISION_PAGE_JPEG_QUALITY = 0.8f;
    /** 视觉分页页数上限：超过回退旧路径（每块一次多模态调用，页数过多成本/超时不可控） */
    private static final int MAX_VISION_PAGES = 24;
    /**
     * 视觉分块：每块页数。实测（deepseek-v4-flash-vision-exp）：每块 4 张整页图 + 内嵌图（约 9-10 张图）
     * 时模型严重劣化（题号粘连/漏题/丢选项/丢图引用）；1-3 张整页图时题号归位与文字精确性完美。
     * 故每块 2 页（2 张整页图 + 该块内嵌图 ≈ 4-7 张），重叠 1 页保证跨页题完整。
     */
    private static final int VISION_CHUNK_PAGES = 2;
    /** 视觉分块：相邻块重叠页数（跨页题在下一块完整出现，去重兜底） */
    private static final int VISION_OVERLAP_PAGES = 1;

    /** 题数差异检测：输出题数 < 参考题数 * 该比例 时提示用户（复杂排版/模型劣化） */
    private static final double QUESTION_DIFF_RATIO = 0.6;
    /** 视觉路径差异检测比例（视觉路径应更完整，阈值更严） */
    private static final double VISION_DIFF_RATIO = 0.75;
    /** 题数差异检测：参考题数低于该值不检测（题号不可靠） */
    private static final int QUESTION_DIFF_MIN_REF = 8;

    /** 答案列表行：如 "1.B" "12. C" "3:ABD" "5√"；含学科卷 "【1题答案】B"（统一 AiAnswerFormat） */
    private static final Pattern ANSWER_LINE = AiAnswerFormat.ANSWER_LINE;
    /** 每块目标题数（下限 10，保证每块输出量适中） */
    private static final int CHUNK_TARGET_QUESTIONS = 12;
    /** 每块图片配额（marksEmbedded 路径）：块内唯一图片数超过则按题号边界二次拆块——
     *  学科卷（如物理 15 题 100+ 张公式/插图）单块全图会导致模型严重劣化 */
    private static final int MAX_CHUNK_IMAGES = 10;
    /** MinerU 路径每块目标题数（更小：块内图片更少，降低多图块模型劣化率；实测矩阵对比 16 题/块效果最好） */
    private static final int CHUNK_TARGET_MINERU = 16;
    /** 最多块数（并行上限，也避免一次任务发出过多调用） */
    private static final int CHUNK_MAX = 6;
    /** 分块策略实验：AI_IMPORT_CHUNK_TARGET 环境变量覆盖 MinerU 路径块目标（8=小块 / 16=大块 / 999=不分块），
     *  用于对比"块边界漏题"与"大输入劣化"的权衡；默认 16（矩阵实测最优）。 */
    private static final int CHUNK_TARGET_MINERU_OVERRIDE =
            Integer.getInteger("tiku.ai-import.chunk-target", CHUNK_TARGET_MINERU);

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
                           AiImportJobStorageService jobStorageService) {
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
    }

    /**
     * 应用启动自愈（桌面应用经常直接关闭/重启）：
     * 1) 上一进程中断遗留的 PENDING/PROCESSING 任务 → 标记 FAILED（错误信息可读）并清理其文件，
     *    避免永久"整理中"徽标（getJob 的 10 分钟兜底只覆盖 PENDING，PROCESSING 无兜底）；
     * 2) 兜底清理：终态任务目录超 7 天未清理（含已物理删行但目录残留）→ 删除，防磁盘持续泄漏。
     */
    @jakarta.annotation.PostConstruct
    public void recoverInterruptedJobsOnStartup() {
        try {
            if (jobMapper == null) {
                return;
            }
            LocalDateTime now = LocalDateTime.now();
            List<AiImportJob> interrupted = jobMapper.selectList(new LambdaQueryWrapper<AiImportJob>()
                    .in(AiImportJob::getStatus, "PENDING", "PROCESSING"));
            for (AiImportJob job : interrupted) {
                job.setStatus("FAILED");
                job.setStage("DONE");
                job.setError("应用上次退出中断了该任务，请删除后重新导入");
                job.setErrorCode("APPLICATION_INTERRUPTED");
                job.setFinishedAt(now);
                jobMapper.updateById(job);
                deleteJobFiles(job.getId());
            }
            java.time.LocalDateTime deadline = now.minusDays(7);
            List<AiImportJob> stale = jobMapper.selectList(new LambdaQueryWrapper<AiImportJob>()
                    .in(AiImportJob::getStatus, "SUCCESS", "FAILED", "CANCELED")
                    .isNotNull(AiImportJob::getFinishedAt)
                    .lt(AiImportJob::getFinishedAt, deadline));
            for (AiImportJob job : stale) {
                deleteJobFiles(job.getId());
            }
            //任务行已被物理删除但目录残留（>7 天）也清理
            java.time.Instant deadlineInstant = deadline.atZone(java.time.ZoneId.systemDefault()).toInstant();
            for (Long orphanId : jobStorageService.findDirectoriesOlderThan(deadlineInstant)) {
                if (jobMapper.selectById(orphanId) == null) {
                    deleteJobFiles(orphanId);
                }
            }
            if (!interrupted.isEmpty() || !stale.isEmpty()) {
                log.info("AI 导入启动自愈：中断任务标记失败 {} 个，清理过期终态目录 {} 个", interrupted.size(), stale.size());
            }
        } catch (Exception e) {
            log.warn("AI 导入启动自愈失败：{}", e.getMessage());
        }
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
            deleteJobFiles(job.getId());
            throw e;
        }

        //异步执行
        Long jobId = job.getId();
        try {
            aiImportExecutor.execute(() -> executeJob(jobId));
        } catch (java.util.concurrent.RejectedExecutionException e) {
            //单线程执行器 + 有界队列已满：清理刚插入的任务行与已落盘文件，防止"排队中但永不执行"的孤儿任务
            jobMapper.deleteById(jobId);
            deleteJobFiles(jobId);
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
            deleteJobFiles(jobId);
            return;
        }
        try {
            updateStage(job, "PROCESSING", "PARSING", 10);
            aiJobEventService.publish(jobId, getJob(jobId));

            //1. 解析所有文件并合并（文本拼接 + 图片合并；答案文件与题目文件拼接后等价于"带卷末答案的文档"）
            //   进度按文件数推进（PARSING 10 → 35），多文件时用户可见"解析 2/3"的反馈
            Path jobDir = jobStorageService.jobDirectory(jobId);
            AiImportDocumentPipeline.PreparedDocument prepared = documentPipeline.prepare(job, (current, total) -> {
                job.setCurrentFileIndex(current);
                updateStage(job, "PROCESSING", "PARSING", 10 + current * 25 / total);
                aiJobEventService.publish(jobId, getJob(jobId));
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

            updateStage(job, "PROCESSING", "AI_GENERATING", 40);
            aiJobEventService.publish(jobId, getJob(jobId));

            //用户取消检查（AI 调用前，节省一次模型调用）；取消确认后清理文件再停
            if (isCanceled(jobId)) {
                cleanupCanceledJobFiles(jobId);
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
                            Files.readAllBytes(jobDir.resolve("0-" + names[0])), VISION_PAGE_DPI, VISION_PAGE_JPEG_QUALITY);
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
                parsedResult = chatChunked(settings, systemPrompt, texts, warning.toString().trim(),
                        job, jobId, aiSupplement, pageTexts, extracted, true, localEvidence, null);
            } else if (visionSingle) {
                //视觉单次（兜底）：整页渲染 + 页文本层 + 简化 prompt（位置占位符）→ 单次多模态调用
                visionPages = true;
                parsedResult = chatVisionSingle(settings, pageTexts, extracted,
                        mergedText, warning.toString().trim(), job, jobId, aiSupplement, jobDir, names[0]);
            } else if (thinkingOn && singlePdfWithText && pageTexts.size() <= MAX_VISION_PAGES && !autoPlain && !pdfDirect) {
                //视觉分页路径（兜底）：思考模式 + 整页渲染图 + 页文本层 + 内嵌图编号
                visionPages = true;
                parsedResult = chatVisionPages(settings, systemPrompt, pageTexts, extracted,
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
                parsedResult = chatChunked(settings, systemPrompt, texts, warning.toString().trim(),
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
                parsedResult = parseAndValidate(aiOutput, aiSupplement, mergedText, true, false);
            } else if (hasExtracted) {
                //单文件 PDF 内嵌图：分块按页归属图片并行（思考模式下图片编号引用）
                parsedResult = chatChunked(settings, systemPrompt, texts, warning.toString().trim(),
                        job, jobId, aiSupplement, pageTexts, extracted, false, null, null);
            } else {
                //文本路径：按题号切块并行（卷末答案列表附加到每块），不可切分时内部回退单次调用
                parsedResult = chatChunked(settings, systemPrompt, texts, warning.toString().trim(),
                        job, jobId, aiSupplement, null, List.of(), false, null, null);
            }

            //题干题号前缀剥离：模型常把块文本行首题号抄进题干（"45. 为庆祝…"），与题号一致时剥除
            if (parsedResult != null && !parsedResult.questions().isEmpty()) {
                stripStemQuestionNumberPrefix(parsedResult.questions());
            }

            //PDF 直传图题兜底归位（配图以"模型看图主导"——块输入含整页截图+块内图，模型按截图引用 [图片N]；
            //本步只对模型未引用的图形题按版面坐标补图，模型已引用的题一律不动）
            if (pdfDirect && parsedResult != null && !parsedResult.questions().isEmpty()) {
                parsedResult = assignPdfFigureImages(parsedResult, pageTexts, extracted, jobDir, names[0], jobId);
            }

            //残留公式图 LaTeX 转写兜底（docx 公式路径）：模型整理时可能漏转公式图 [图片N]
            //（非思考/模型波动实测均见）→ 按"docx 解析标记的公式图编号"收集残留，一次思考调用批量转写回填
            if (parsedResult != null && !formulaNosAll.isEmpty() && !extracted.isEmpty()) {
                parsedResult = formulaService.replaceResidualFormulaImages(parsedResult, formulaNosAll, extracted, settings, jobId);
            }

            //用户取消检查（AI 调用后：丢弃结果，不写库）；取消确认后清理文件再停
            if (isCanceled(jobId)) {
                cleanupCanceledJobFiles(jobId);
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

            updateStage(job, "PROCESSING", "VALIDATING", 85);
            aiJobEventService.publish(jobId, getJob(jobId));

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
            if (!writeTerminal(jobId, "SUCCESS", null, null, resultJson)) {
                cleanupCanceledJobFiles(jobId);
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
                    : truncate(failure.userMessage() + "（诊断：" + failure.diagnosticSummary() + "）", 500);
            if (!writeTerminal(jobId, "FAILED", failureMessage, failure.code(), null)) {
                cleanupCanceledJobFiles(jobId);
                return;
            }
        }
        //推送最终快照并结束事件流（SSE 订阅方收到后跳预览/展示失败原因）
        //取消的任务不推送终态：deleteJob 已发布 CANCELED 快照，订阅方各自处理
        aiJobEventService.complete(jobId, getJob(jobId));
    }

    /**
     * 终态落库（条件更新）：仅当任务尚未被取消时写入 SUCCESS/FAILED，
     * 返回是否真正落库（false = 任务已被用户取消，调用方应停止并清理）。
     */
    private boolean writeTerminal(Long jobId, String status, String error, String errorCode, String resultJson) {
        LambdaUpdateWrapper<AiImportJob> wrapper = new LambdaUpdateWrapper<AiImportJob>()
                .eq(AiImportJob::getId, jobId)
                .ne(AiImportJob::getStatus, "CANCELED")
                .set(AiImportJob::getStatus, status)
                .set(AiImportJob::getStage, "DONE")
                .set(AiImportJob::getProgress, 100)
                .set(AiImportJob::getFinishedAt, LocalDateTime.now());
        if (error != null) {
            wrapper.set(AiImportJob::getError, error);
        }
        if (errorCode != null) {
            wrapper.set(AiImportJob::getErrorCode, errorCode);
        }
        if (resultJson != null) {
            wrapper.set(AiImportJob::getResultJson, resultJson);
        }
        return jobMapper.update(null, wrapper) > 0;
    }

    /** 任务取消确认后的文件清理（仅执行线程在"已停不会再读文件"时调用，避免与在途读取竞态） */
    private void cleanupCanceledJobFiles(Long jobId) {
        deleteJobFiles(jobId);
    }

    /** 任务是否已被用户取消（deleteJob 标记 CANCELED） */
    private boolean isCanceled(Long jobId) {
        AiImportJob current = jobMapper.selectById(jobId);
        return current != null && "CANCELED".equals(current.getStatus());
    }

    /**
     * 进度/阶段更新（条件更新）：任务被取消（CANCELED）后不再改写行——防止与用户取消竞态时
     * 把 CANCELED 复活成 PROCESSING。更新成功才同步本地实体镜像（供后续代码读取）。
     */
    private void updateStage(AiImportJob job, String status, String stage, int progress) {
        int rows = jobMapper.update(null, new LambdaUpdateWrapper<AiImportJob>()
                .eq(AiImportJob::getId, job.getId())
                .ne(AiImportJob::getStatus, "CANCELED")
                .set(AiImportJob::getStatus, status)
                .set(AiImportJob::getStage, stage)
                .set(AiImportJob::getProgress, progress));
        if (rows > 0) {
            job.setStatus(status);
            job.setStage(stage);
            job.setProgress(progress);
        }
    }

    // ==================== 查询与确认 ====================

    /** 进行中的任务列表（前端全局监控：侧边栏徽标、完成通知） */
    public List<AiJobResponse> listActiveJobs() {
        List<AiImportJob> jobs = jobMapper.selectList(new LambdaQueryWrapper<AiImportJob>()
                .in(AiImportJob::getStatus, "PENDING", "PROCESSING")
                .orderByDesc(AiImportJob::getCreatedAt));
        List<AiJobResponse> result = new ArrayList<>();
        for (AiImportJob job : jobs) {
            result.add(toResponse(job, false));
        }
        return result;
    }

    /**
     * SSE 订阅任务事件流：任务已终态时立即推送最终快照并关闭（含 CANCELED）。
     * 先注册订阅、再重读状态（不能只依赖订阅前快照——worker 可能在注册窗口内完成/取消；
     * 注册后补查终态 + complete 保证任何交错下订阅方都能收到终态并被关闭）。
     */
    public org.springframework.web.servlet.mvc.method.annotation.SseEmitter subscribeJob(Long jobId) {
        org.springframework.web.servlet.mvc.method.annotation.SseEmitter emitter = aiJobEventService.subscribe(jobId);
        AiImportJob job = findByIdOrThrow(jobId);
        if ("SUCCESS".equals(job.getStatus()) || "FAILED".equals(job.getStatus()) || "CANCELED".equals(job.getStatus())) {
            aiJobEventService.complete(jobId, getJob(jobId));
        }
        return emitter;
    }

    public AiJobResponse getJob(Long jobId) {
        AiImportJob job = findByIdOrThrow(jobId);
        //兜底：排队超过 10 分钟仍未开始执行（如应用重启/线程异常），标记失败而不是永久 PENDING
        if ("PENDING".equals(job.getStatus()) && job.getCreatedAt() != null
                && job.getCreatedAt().isBefore(LocalDateTime.now().minusMinutes(10))) {
            job.setStatus("FAILED");
            job.setStage("DONE");
            job.setError("任务排队超时（可能因应用中断未能执行），请重新导入");
            job.setErrorCode("QUEUE_TIMEOUT");
            job.setFinishedAt(LocalDateTime.now());
            jobMapper.updateById(job);
        }
        return toResponse(job, true);
    }

    /** 统一构造任务快照（轮询/SSE/active/recent 共用） */
    private AiJobResponse toResponse(AiImportJob job, boolean includeQuestions) {
        List<ContentPackageQuestion> questions = null;
        List<ContentPackageMaterial> materials = null;
        if (includeQuestions && job.getResultJson() != null && !job.getResultJson().isBlank()) {
            try {
                AiImportResult parsed = resultCodec.parse(job.getResultJson());
                questions = parsed.questions();
                materials = parsed.materials();
            } catch (IOException ignored) {
                //结果解析失败按空处理
            }
        }
        int fileCount = 1;
        if (job.getFileNames() != null && !job.getFileNames().isBlank()) {
            fileCount = job.getFileNames().split(",").length;
        }
        return new AiJobResponse(job.getId(), job.getStatus(), job.getStage(), job.getProgress(),
                job.getFileName(), job.getFileType(), job.getAiSupplement(), job.getThinking(),
                job.getEngine(), job.getProcessPath(), job.getConfirmed(),
                fileCount, job.getCurrentFileIndex(),
                questions, materials, job.getWarningHint(), job.getErrorCode(), job.getError(),
                job.getCreatedAt(), job.getFinishedAt());
    }

    // ==================== 最近未确认任务 / 取消删除 ====================

    /** 最近未确认导入的任务（含已取消——补二段删除入口；confirmed=true 不再展示），createdAt 倒序 */
    public List<AiJobResponse> listRecentJobs(int limit) {
        int safeLimit = Math.min(Math.max(limit, 1), 50);
        List<AiImportJob> jobs = jobMapper.selectList(new LambdaQueryWrapper<AiImportJob>()
                .eq(AiImportJob::getConfirmed, false)
                .orderByDesc(AiImportJob::getCreatedAt)
                .last("LIMIT " + safeLimit));
        List<AiJobResponse> result = new ArrayList<>();
        for (AiImportJob job : jobs) {
            //顺带处理排队超时（与 getJob 一致的兜底）
            if ("PENDING".equals(job.getStatus()) && job.getCreatedAt() != null
                    && job.getCreatedAt().isBefore(LocalDateTime.now().minusMinutes(10))) {
                job.setStatus("FAILED");
                job.setStage("DONE");
                job.setError("任务排队超时（可能因应用中断未能执行），请重新导入");
                job.setErrorCode("QUEUE_TIMEOUT");
                job.setFinishedAt(LocalDateTime.now());
                jobMapper.updateById(job);
            }
            result.add(toResponse(job, true));
        }
        return result;
    }

    /**
     * 取消/删除任务（两阶段）：
     * - 进行中（PENDING/PROCESSING）：仅标记 CANCELED（记录保留供再次删除）。
     *   文件不在这里删——执行线程可能正在读取，删文件会令线程异常并以 FAILED 覆盖 CANCELED（取消竞态）；
     *   由执行线程在取消检查点自行清理（cleanupCanceledJobFiles）；若线程已不存在（应用重启中断），
     *   残留文件在用户再次删除（终态物理删除）时清理。
     * - 终态（SUCCESS/FAILED/CANCELED）：物理删除记录 + 清理文件目录。
     */
    public void deleteJob(Long jobId) {
        AiImportJob job = findByIdOrThrow(jobId);
        String status = job.getStatus();
        if ("PENDING".equals(status) || "PROCESSING".equals(status)) {
            job.setStatus("CANCELED");
            job.setStage("DONE");
            job.setFinishedAt(LocalDateTime.now());
            jobMapper.updateById(job);
            //发布 CANCELED 快照并结束事件流（complete = publish + 关闭订阅方），前端立即感知"已取消"
            aiJobEventService.complete(jobId, getJob(jobId));
            return;
        }
        //终态：物理删除记录 + 清理文件目录
        jobMapper.deleteById(jobId);
        deleteJobFiles(jobId);
    }

    private void deleteJobFiles(Long jobId) {
        try {
            jobStorageService.deleteJobDirectory(jobId);
        } catch (IOException e) {
            log.warn("清理 AI 导入文件失败 job={}: {}", jobId, e.getMessage());
        }
    }

    // ==================== 任务临时图片（预览素材区） ====================

    /** 素材区图片信息：编号 + 文件名 + 扩展名（imports/{jobId}/images/ 目录，按编号排序） */
    public record JobImageInfo(int num, String fileName, String ext) {
    }

    /**
     * 任务临时图片列表（预览页"图片素材区"：展示所有提取的图片，供用户拖入/点击插入 [图片N] 标记）。
     * 图片文件：imports/{jobId}/images/{N}.png（MinerU 路径转 PNG；本地路径同契约）。
     */
    public List<JobImageInfo> listJobImages(Long jobId) {
        findByIdOrThrow(jobId);
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
        findByIdOrThrow(jobId);
        return jobStorageService.readImage(jobId, num);
    }

    // ==================== 材料素材（资料分析/阅读材料题） ====================

    /**
     * 任务材料素材列表（预览页"材料素材区"）：任务结果中的共享材料（本地检测的 m1 或模型输出的材料）。
     * 材料与图片素材同级：展示为素材块，用户拖入/点击关联到题目材料区（confirm 提交编辑后的题目携带 materialKey）。
     * content 中可能含 [图片N] 标记（图表），前端经 /images/{num} 渲染。
     */
    public List<ContentPackageMaterial> listMaterialSnippets(Long jobId) {
        AiImportJob job = findByIdOrThrow(jobId);
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
        deleteJobFiles(jobId);
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

    /**
     * Markdown 输出解析（分块路径）：模型按标准 MD 模板输出 → MdQuestionParser 确定性解析 → validate 校验。
     * 题号直接取自标题（免源文定位回填）；材料由模型声明（## 材料 mN 块），题目按位置自动关联。
     * 解析失败/坏题丢弃不抛异常（补漏 + 预览兜底）。
     */
    private List<ContentPackageQuestion> mdParseQuestions(String mdOutput, boolean aiSupplement) {
        return resultParser.parseMarkdownQuestions(mdOutput);
    }

    /** Markdown 输出解析（含材料块） */
    private AiImportResult mdParseResult(String mdOutput, boolean aiSupplement) {
        return resultParser.parseMarkdown(mdOutput);
    }

    /**
     * 解析并校验 AI 输出。sourceText 为源文档文本（文本路径必传；纯图片路径传 null 跳过答案证据检查）。
     * aiSupplement=false 时：模型给出的答案必须能在源文中找到证据（题号附近标记 / 卷末答案列表），
     * 否则视为模型编造，强制清空（防止关闭思考后模型自行作答污染题库）。
     * enableMaterials=true（单次调用）时解析顶层 materials（材料组识别）；分块路径传 false。
     * skipSourceRepair=true（视觉分页路径）：跳过 completeMaterialFromSource 源文回填/校验——
     * 该逻辑按"行首题号"定位源文，对"题号+题干同行"版式会误杀完整题（实测：视觉路径 45 题被误杀约 10 题），
     * 视觉路径模型输出文字已来自文本层（逐字采用），直接信任；残版/粘连题由 chatVisionPages 合并前过滤。
     * 图片编号引用（[图片N]）原样保留，确认导入时替换为 [图片:正式文件名]。
     */
    private AiImportResult parseAndValidate(String aiOutput, boolean aiSupplement, String sourceText,
                                            boolean enableMaterials, boolean skipSourceRepair) {
        return resultParser.parseJson(aiOutput, aiSupplement, sourceText, enableMaterials, skipSourceRepair);
    }

    /** 剥离 markdown 代码块（```json ... ```） */
    private String stripCodeFence(String output) {
        String normalized = output == null ? "" : output.trim();
        int start = normalized.indexOf('{');
        int end = normalized.lastIndexOf('}');
        if (start >= 0 && end > start) {
            return normalized.substring(start, end + 1);
        }
        return normalized;
    }

    /** 模型输出是否为 JSON 形态（无思考重试 + json_object 响应格式时模型可能输出旧版 JSON 结构） */
    private boolean looksLikeJson(String out) {
        return resultParser.looksLikeJson(out);
    }

    /** 端点降级快速失败：源文题号 ≥5 但结果 <3 题 → 模型持续返回空白/垃圾，整单报错让用户稍后重试。
     *  （静默产出 1-2 题垃圾比明确失败更糟——用户以为导入成功） */
    private void assertNotDegraded(List<ContentPackageQuestion> questions, String fullSource) {
        resultParser.assertNotDegraded(questions, fullSource);
    }

    // ==================== 分块并行（文本路径加速） ====================

    /** 图片引用标记：[图片N] */
    private static final Pattern IMAGE_REF = Pattern.compile("\\[图片(\\d+)]");

    /**
     * 文本路径 AI 整理（Markdown 输出管线）：按题号边界切块并行调用，模型按标准 MD 模板输出，
     * 后端用 MdQuestionParser 确定性解析（题号取自标题，免源文定位），合并后走答案后处理。
     * 不可切分（无连续题号 / 答案文件无法识别）时回退单次调用（同样 MD 输出）。
     * 卷末答案列表会附加到每一块，供模型对照（不输出答案本身）。
     * PDF 直传（pageRenders 非空）：每块附带其页范围的整页截图（版式真相，网页端同款输入）。
     */
    private AiImportResult chatChunked(AiSettings settings, String systemPrompt, List<String> texts,
                                       String warning, AiImportJob job, Long jobId, boolean aiSupplement,
                                       List<String> pageTexts, List<DocumentParserService.ExtractedImage> extracted,
                                       boolean marksEmbedded, String evidenceText,
                                       List<AiClientService.ImageData> pageRenders) {
        //两阶段分离：整理阶段只提取题目结构（MD 模板输出，"答案/解析"留空），
        //答案与解析由返回前 postProcessAnswers 统一处理（原文证据恢复 + 思考模式补充）
        final String extractPrompt = promptFactory.buildMdExtractPrompt();
        //答案证据校验用源文（含答案文件；纯图片场景不走到这里）
        String fullSource = String.join("\n\n========== 下一份文件 ==========\n\n", texts);
        //答案溯源文本：MinerU 路径附本地解析文本（其卷末答案行干净可靠），内容定位仍以主文本为准（在前）
        String evidenceSource = (evidenceText == null || evidenceText.isBlank())
                ? fullSource
                : fullSource + "\n\n========== 本地解析文本（答案溯源用） ==========\n\n" + evidenceText;
        //分块基础文本：单文件 PDF（有逐页文本）用清洗后逐页拼接（页偏移表映射图片）；其余用 texts 拼接
        String baseText = fullSource;
        int[] pageStarts = null;
        if (pageTexts != null && !pageTexts.isEmpty() && texts.size() == 1) {
            StringBuilder sb = new StringBuilder();
            int[] starts = new int[pageTexts.size() + 1];
            int acc = 0;
            for (int i = 0; i < pageTexts.size(); i++) {
                starts[i] = acc;
                acc += pageTexts.get(i).length();
                sb.append(pageTexts.get(i));
            }
            starts[pageTexts.size()] = acc;
            baseText = sb.toString();
            pageStarts = starts;
        }
        List<int[]> ranges = List.of();
        String chunkSource = null;
        String tail = "";
        if (texts.size() == 1) {
            //单文件：剥离卷末答案区（"参考答案"标题/答案行起），切块时附加到每一块。
            //只剥离答案区本身——旧实现把"最后一题 + 答案区"整段当 tail，最后一题文本被混入答案区导致漏题
            String t = baseText;
            List<Integer> bounds = detectQuestionBoundaries(t);
            if (!bounds.isEmpty()) {
                String tailCandidate = t.substring(bounds.get(bounds.size() - 1)).trim();
                int sectionStart = answerSectionStartOffset(tailCandidate);
                if (sectionStart >= 0) {
                    int cut = t.length() - tailCandidate.length() + sectionStart;
                    tail = t.substring(cut).trim();
                    t = t.substring(0, cut);
                }
            }
            chunkSource = t;
            ranges = splitChunks(t, marksEmbedded ? CHUNK_TARGET_MINERU_OVERRIDE : CHUNK_TARGET_QUESTIONS);
        } else if (AiAnswerFormat.looksLikeAnswerList(texts.get(texts.size() - 1))) {
            //多文件且最后一份是答案文件：题目部分 = 前面所有文件，答案列表附加到每块（多文件含图已分流单次，此处无图）
            tail = texts.get(texts.size() - 1);
            chunkSource = String.join("\n\n========== 下一份文件 ==========\n\n", texts.subList(0, texts.size() - 1));
            ranges = splitChunks(chunkSource, marksEmbedded ? CHUNK_TARGET_MINERU_OVERRIDE : CHUNK_TARGET_QUESTIONS);
        }
        //图片配额二次拆块（marksEmbedded）：块内图片过多模型劣化（学科卷整卷公式图实测翻车）
        if (marksEmbedded && chunkSource != null) {
            ranges = splitRangesByImageQuota(chunkSource, ranges);
        }
        List<String> chunks = new ArrayList<>();
        List<int[]> chunkRanges = new ArrayList<>(); // {start, end}（清洗后空间，页映射用）
        int[] chunkExpect = new int[ranges.size()]; // 每块期望题数（重试阈值 = min(5, expect)，小块解析齐不重试）
        for (int i = 0; i < ranges.size(); i++) {
            int[] r = ranges.get(i);
            //孤儿修剪仅在"与相邻块重叠"时执行（splitChunks 的重叠切块：块首是上一块末题残留、块尾是下一块首题）。
            //配额拆块是连续切分（块边界 = 题号行本身），修剪会删掉块首题号行/题干首行与块尾计算题题干 —— 不修剪，
            //边界处的"无题号材料/题干"由块 prompt 的"不完整题目跳过"指令处理
            boolean headOverlap = i > 0 && ranges.get(i - 1)[1] > ranges.get(i)[0];
            boolean tailOverlap = i < ranges.size() - 1 && ranges.get(i)[1] > ranges.get(i + 1)[0];
            String trimmed = trimOrphans(chunkSource.substring(r[0], r[1]), headOverlap, tailOverlap);
            chunks.add(trimmed);
            chunkRanges.add(new int[]{r[0], r[1]});
            //期望题数 = 修剪后块文本中的题号边界数（物理 docx 配额拆块后每块 1-4 题，
            //旧阈值"<5 即重试"让小块白白多调 2-3 次 thinking——每次 3-4 分钟）
            int expect = detectQuestionBoundaries(trimmed).size();
            chunkExpect[i] = Math.max(1, expect);
        }
        if (chunks.isEmpty()) {
            //回退：单次调用（多文件无法识别答案文件的情况；带图则全图随调用 + 编号规则）
            String merged = String.join("\n\n========== 下一份文件 ==========\n\n", texts);
            String user = promptFactory.buildUserPrompt(merged, warning);
            String out;
            if (!extracted.isEmpty()) {
                user += pageRenders != null
                        ? promptFactory.buildPdfVisionImageRule(1, pageRenders.size(), pageRenders.size(), 1, extracted.size())
                        : promptFactory.buildImageRefRule(1, extracted.size());
                List<AiClientService.ImageData> all = new ArrayList<>();
                for (DocumentParserService.ExtractedImage e : extracted) {
                    all.add(e.image());
                }
                try {
                    out = aiClientService.chatWithImages(promptFactory.buildVisionSettings(settings), extractPrompt, user, all, true);
                } catch (Exception e) {
                    log.warn("AI 导入任务 {} 回退单次 vision 调用失败（{}），改用纯文本模型重试", jobId, e.getMessage());
                    out = aiClientService.chat(settings, extractPrompt, user, true);
                }
            } else {
                out = aiClientService.chat(settings, extractPrompt, user, true);
            }
            //回退单次路径同样补漏 + 答案后处理（与分块路径统一；带标记时补漏带图）
            AiImportResult single = mdParseResult(out, aiSupplement);
            if (single.questions().isEmpty() && looksLikeJson(out)) {
                single = parseAndValidate(out, aiSupplement, fullSource, true, false);
            }
            if (extracted.isEmpty() || marksEmbedded || pageRenders != null) {
                single = new AiImportResult(fillMissingQuestions(single.questions(), fullSource,
                        settings, extractPrompt, aiSupplement, jobId, extracted), single.materials());
            }
            assertNotDegraded(single.questions(), fullSource);
            return answerService.postProcess(single, aiSupplement, fullSource, evidenceSource, settings, jobId, extracted);
        }

        //分块并行：每块 prompt = 块文本 + 卷末答案列表 + （本块图片编号规则）
        String tailMarker = (tail == null || tail.isBlank()) ? "" :
                "\n\n【整份文档的参考答案（按题号对应到各题，供对照使用；不要把这些答案行当作题目）】\n" + tail;
        boolean hasImages = !extracted.isEmpty() && (marksEmbedded || pageStarts != null);
        List<ImageChunk> imageChunks = new ArrayList<>();
        if (hasImages) {
            for (int i = 0; i < chunks.size(); i++) {
                if (marksEmbedded) {
                    //MinerU 路径：块文本中的 [图片N] 标记（content_list 阅读顺序内嵌）→ 取对应图。
                    //图片消息与标记编号精确一致（编号 = extracted 顺序 = 消息顺序），
                    //避免"按页归属"把相邻页其他题的图混入本块（实测 26 张跨题图导致模型错乱）。
                    List<DocumentParserService.ExtractedImage> blockImages = new ArrayList<>();
                    int firstNum = 0;
                    int lastNum = 0;
                    Matcher m = IMAGE_REF.matcher(chunks.get(i));
                    while (m.find()) {
                        int n = Integer.parseInt(m.group(1));
                        if (n >= 1 && n <= extracted.size()) {
                            DocumentParserService.ExtractedImage e = extracted.get(n - 1);
                            if (!blockImages.contains(e)) {
                                if (firstNum == 0) {
                                    firstNum = n;
                                }
                                lastNum = n;
                                blockImages.add(e);
                            }
                        }
                    }
                    imageChunks.add(new ImageChunk(blockImages, firstNum, lastNum));
                } else {
                    //每块 → 页范围 → 本块图片（编号 = extracted 顺序 index+1，页连续 → 编号连续）
                    int firstPage = pageIndexOf(pageStarts, chunkRanges.get(i)[0]);
                    int lastPage = pageIndexOf(pageStarts, chunkRanges.get(i)[1]);
                    List<DocumentParserService.ExtractedImage> blockImages = new ArrayList<>();
                    for (DocumentParserService.ExtractedImage e : extracted) {
                        if (e.pageNo() >= firstPage && e.pageNo() <= lastPage) {
                            blockImages.add(e);
                        }
                    }
                    int firstNum = blockImages.isEmpty() ? 0 : extracted.indexOf(blockImages.get(0)) + 1;
                    int lastNum = blockImages.isEmpty() ? 0 : extracted.indexOf(blockImages.get(blockImages.size() - 1)) + 1;
                    imageChunks.add(new ImageChunk(blockImages, firstNum, lastNum));
                }
            }
        }
        //PDF 直传：每块对应页范围的整页截图（版式真相，网页端同款输入）
        List<List<AiClientService.ImageData>> chunkPageImages = new ArrayList<>();
        int[] chunkFirstPage = new int[chunks.size()];
        int[] chunkLastPage = new int[chunks.size()];
        if (pageRenders != null && !pageRenders.isEmpty() && pageStarts != null) {
            for (int i = 0; i < chunks.size(); i++) {
                int firstPage = pageIndexOf(pageStarts, chunkRanges.get(i)[0]);
                int lastPage = Math.min(pageRenders.size() - 1, pageIndexOf(pageStarts, chunkRanges.get(i)[1]));
                chunkFirstPage[i] = firstPage;
                chunkLastPage[i] = lastPage;
                chunkPageImages.add(firstPage <= lastPage
                        ? new ArrayList<>(pageRenders.subList(firstPage, lastPage + 1)) : List.of());
            }
        }
        List<String> prompts = new ArrayList<>();
        for (int i = 0; i < chunks.size(); i++) {
            StringBuilder sb = new StringBuilder();
            if (warning != null && !warning.isBlank()) {
                sb.append("注意：").append(warning).append('\n');
            }
            sb.append("以下是整份文档的第 ").append(i + 1).append('/').append(chunks.size())
                    .append(" 部分（已按题号切分，只整理这一部分中的题目）：\n")
                    .append("提示：文档版式可能把题号与选项排在题干附近（如先题号选项、后材料题干，或双栏排版），")
                    .append("请以题号为准把材料、题干、选项配对。");
            if (i > 0 && i < chunks.size() - 1) {
                sb.append("本部分开头可能残留上一部分的最后一道题（只有选项没有题干），末尾也可能包含下一部分第一题的题干（没有题号和选项），这类不完整题目一律跳过，不要补写题干或选项。");
            } else if (i > 0) {
                sb.append("本部分开头可能残留上一部分的最后一道题（只有选项没有题干），这类不完整题目一律跳过，不要补写题干或选项。");
            } else if (chunks.size() > 1) {
                sb.append("本部分从文档开头开始：开头的标题/广告/说明行忽略即可，其后所有题目（包括第 1 题、第 2 题）必须全部输出，禁止跳过；末尾可能包含下一部分第一题的题干（没有题号和选项），跳过。");
            } else {
                sb.append("本部分从文档开头开始：开头的标题/广告/说明行忽略即可，其后所有题目（包括第 1 题、第 2 题）必须全部输出，禁止跳过。");
            }
            if (pageRenders != null && !chunkPageImages.isEmpty() && !chunkPageImages.get(i).isEmpty()) {
                sb.append("\n【页面截图】已随本消息提供本部分对应的第 ").append(chunkFirstPage[i] + 1)
                        .append(" 至 ").append(chunkLastPage[i] + 1)
                        .append(" 页整页截图（位于消息图片最前），用于判断题号位置、题目边界、图形与选项的视觉归属。")
                        .append("正文文字以文本层为准，不要重新转写截图中的正文；")
                        .append("唯一例外：文本层明显断档（句中空格处）且截图对应位置是公式/化学式/分数或填空横线时，")
                        .append("依截图补全该处：公式转为文本（如 NH3、1/150），填空横线用 ____ 表示。");
            }
            sb.append("\n\n").append(chunks.get(i)).append(tailMarker);
            if (imageChunks != null && i < imageChunks.size() && !imageChunks.get(i).images().isEmpty()) {
                if (marksEmbedded) {
                    //标记路径：列出块内实际的图片编号（块内编号可能不连续，如公式图被二次拆块隔开），
                    //避免"firstNum~lastNum"误导模型把编号与消息顺序错配
                    sb.append(promptFactory.buildImageRefRuleList(imageChunkNumbers(chunks.get(i))));
                } else if (pageRenders != null) {
                    //PDF 直传（模型看图主导）：整页截图 + 块内嵌图随消息提供，模型按截图判断归属并引用 [图片N]。
                    //测试验证（91-95 区）模型看图配图全对，含同题干图形题与跨页题；程序坐标仅作兜底（见 assignPdfFigureImages）
                    sb.append(promptFactory.buildPdfVisionImageRule(chunkFirstPage[i] + 1, chunkLastPage[i] + 1,
                            chunkPageImages.get(i).size(),
                            imageChunks.get(i).firstNum(), imageChunks.get(i).lastNum()));
                } else {
                    sb.append(promptFactory.buildImageRefRule(imageChunks.get(i).firstNum(), imageChunks.get(i).lastNum()));
                }
            }
            //诊断日志：块 prompt 全文（定位模型输入问题）
            log.info("AI 导入任务 {} 块 {}/{} prompt（{} 字符）：{}",
                    jobId, i + 1, chunks.size(), sb.length(), truncate(sb.toString().replaceAll("\\R+", " | "), 3000));
            prompts.add(sb.toString());
        }

        log.info("AI 导入任务 {} 分块并行：{} 块{}{}", jobId, prompts.size(), hasImages ? "（含图，按页归属）" : "",
                pageRenders != null ? "（含整页截图）" : "");
        List<Future<String>> futures = new ArrayList<>();
        for (int i = 0; i < prompts.size(); i++) {
            final int idx = i;
            futures.add(aiChunkExecutor.submit(() -> {
                String out;
                //按块路由：无任何图 → 纯文本模型（快、稳）；有图 → vision 模型看图（保证归属）。
                //PDF 直传块恒带整页截图；其余路径按块内 [图片N] 标记取图。
                List<AiClientService.ImageData> imgs = new ArrayList<>();
                if (chunkPageImages != null && idx < chunkPageImages.size()) {
                    imgs.addAll(chunkPageImages.get(idx));
                }
                if (imageChunks != null && !imageChunks.isEmpty() && !imageChunks.get(idx).images().isEmpty()) {
                    for (DocumentParserService.ExtractedImage e : imageChunks.get(idx).images()) {
                        imgs.add(e.image());
                    }
                }
                if (imgs.isEmpty()) {
                    out = aiClientService.chat(settings, extractPrompt, prompts.get(idx), true);
                    log.info("AI 导入任务 {} 块 {}/{} 路由：纯文本模型", jobId, idx + 1, prompts.size());
                } else {
                    int pageCount = chunkPageImages != null && idx < chunkPageImages.size() ? chunkPageImages.get(idx).size() : 0;
                    log.info("AI 导入任务 {} 块 {}/{} 路由：vision 模型（页图 {} 张 + 标记图 {} 张）",
                            jobId, idx + 1, prompts.size(), pageCount, imgs.size() - pageCount);
                    out = aiClientService.chatWithImages(promptFactory.buildVisionSettings(settings), extractPrompt, prompts.get(idx), imgs, true);
                }
                return out;
            }));
        }
        List<ContentPackageQuestion> all = new ArrayList<>();
        //MD 输出管线：材料由模型声明（## 材料 mN 块），跨块收集后在末尾归一去重
        List<ContentPackageMaterial> blockMaterials = new ArrayList<>();
        int done = 0;
        for (int i = 0; i < futures.size(); i++) {
            if (isCanceled(jobId)) {
                //用户取消：放弃剩余块（尽力中断）
                cancelFutures(futures, i);
                return new AiImportResult(all, blockMaterials);
            }
            List<ContentPackageQuestion> parsed = new ArrayList<>();
            try {
                String out = futures.get(i).get(6, TimeUnit.MINUTES);
                //诊断日志：模型原始输出（定位缺题在模型层还是解析层）
                log.info("AI 导入任务 {} 块 {}/{} 模型原始输出（{} 字符）：{}",
                        jobId, i + 1, futures.size(), out.length(), truncate(out.replaceAll("\\R+", " | "), 1600));
                //Markdown 输出管线：确定性解析（题号取自标题）；材料块合并（跨块去重见末尾归一）
                AiImportResult blockResult = mdParseResult(out, aiSupplement);
                if (blockResult.questions().isEmpty() && looksLikeJson(out)) {
                    //无思考重试 + json_object 响应格式 → 模型可能输出旧版 JSON 结构；本地兼容解析
                    blockResult = parseAndValidate(out, aiSupplement, fullSource, true, false);
                }
                parsed = blockResult.questions();
                mergeMaterials(blockMaterials, blockResult.materials());
            } catch (CancellationException e) {
                return new AiImportResult(all, blockMaterials);
            } catch (Exception e) {
                //块调用失败（网络/限流）→ 不整体失败，走重试
                log.warn("AI 导入任务 {} 第 {}/{} 块调用失败（{}），自动重试该块", jobId, i + 1, futures.size(), e.getMessage());
            }
            //块级兜底：模型偶发劣化（输出不足/全是残片）→ 重试，取结果多的。
            //阈值 = min(5, 块期望题数)：大块（判断推理 10+ 题）仍按 <5 重试；
            //小块（docx 配额拆块后 1-4 题）解析齐全即不再重试——旧固定 <5 阈值让小块
            //白白多调 2-3 次 thinking（每次 3-4 分钟），15 题物理卷因此多花约 20 分钟
            int retryThreshold = Math.min(5, i < chunkExpect.length ? chunkExpect[i] : 5);
            if (parsed.size() < retryThreshold && !isCanceled(jobId)) {
                List<AiClientService.ImageData> blockImgs = new ArrayList<>();
                if (chunkPageImages != null && i < chunkPageImages.size()) {
                    blockImgs.addAll(chunkPageImages.get(i));
                }
                if (imageChunks != null && i < imageChunks.size() && !imageChunks.get(i).images().isEmpty()) {
                    for (DocumentParserService.ExtractedImage e : imageChunks.get(i).images()) {
                        blockImgs.add(e.image());
                    }
                }
                for (int attempt = 0; attempt < 3 && parsed.size() < retryThreshold && !isCanceled(jobId); attempt++) {
                    try {
                        //按块路由重试：块内有图才 vision（同上）；第 2 次切换思考模式，第 3 次改纯文本模型
                        //（端点 vision 持续降级时文本模型仍可用；图形题已由程序确定性配图，不依赖模型看图）
                        AiSettings rs = attempt == 1 ? promptFactory.withThinking(settings, false) : settings;
                        String retry;
                        if (attempt == 2 || blockImgs.isEmpty()) {
                            retry = aiClientService.chat(rs, extractPrompt, prompts.get(i), true);
                        } else {
                            retry = aiClientService.chatWithImages(promptFactory.buildVisionSettings(rs), extractPrompt, prompts.get(i), blockImgs, true);
                        }
                        List<ContentPackageQuestion> retryParsed = mdParseQuestions(retry, aiSupplement);
                        if (retryParsed.isEmpty() && looksLikeJson(retry)) {
                            retryParsed = parseAndValidate(retry, aiSupplement, fullSource, true, false).questions();
                        }
                        if (retryParsed.size() > parsed.size()) {
                            parsed = retryParsed;
                        }                    } catch (Exception e) {
                        //重试仍失败 → 跳过该块，缺失题由补漏兜底
                        log.warn("AI 导入任务 {} 第 {}/{} 块重试失败（{}），跳过该块（补漏兜底）", jobId, i + 1, futures.size(), e.getMessage());
                    }
                }
                log.info("AI 导入任务 {} 第 {}/{} 块重试后 {} 题", jobId, i + 1, futures.size(), parsed.size());
            }
            log.info("AI 导入任务 {} 第 {}/{} 块：解析 {} 题", jobId, i + 1, futures.size(), parsed.size());
            all.addAll(parsed);
            done++;
            updateStage(job, "PROCESSING", "AI_GENERATING", 40 + 40 * done / futures.size());
            aiJobEventService.publish(jobId, getJob(jobId));
        }
        //去重：重叠块可能重复产出同一题（题干忠实原文，规范化后完全一致），保留先出现的
        List<ContentPackageQuestion> unique = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (ContentPackageQuestion q : all) {
            String norm = visionQualityService.normalizeQuestion(q);
            if (seen.add(norm)) {
                unique.add(q);
            }
        }
        if (unique.size() != all.size()) {
            log.info("AI 导入任务 {} 去重：{} → {} 题", jobId, all.size(), unique.size());
        }
        if (unique.isEmpty()) {
            //兜底：分块结果为空（切块异常时可能发生），回退单次调用
            log.warn("AI 导入任务 {} 分块结果为空，回退单次调用", jobId);
            String merged = String.join("\n\n========== 下一份文件 ==========\n\n", texts);
            String user = promptFactory.buildUserPrompt(merged, warning);
            String out;
            if (!extracted.isEmpty()) {
                user += pageRenders != null
                        ? promptFactory.buildPdfVisionImageRule(1, pageRenders.size(), pageRenders.size(), 1, extracted.size())
                        : promptFactory.buildImageRefRule(1, extracted.size());
                List<AiClientService.ImageData> allImgs = new ArrayList<>();
                for (DocumentParserService.ExtractedImage e : extracted) {
                    allImgs.add(e.image());
                }
                //回退单次：有图才 vision（同上）；vision 失败（端点降级）改纯文本模型重试一次
                try {
                    out = allImgs.isEmpty()
                            ? aiClientService.chat(settings, extractPrompt, user, true)
                            : aiClientService.chatWithImages(promptFactory.buildVisionSettings(settings), extractPrompt, user, allImgs, true);
                } catch (Exception e) {
                    log.warn("AI 导入任务 {} 回退单次 vision 调用失败（{}），改用纯文本模型重试", jobId, e.getMessage());
                    out = aiClientService.chat(settings, extractPrompt, user, true);
                }
            } else {
                out = aiClientService.chat(settings, extractPrompt, user, true);
            }
            AiImportResult single = mdParseResult(out, aiSupplement);
            if (single.questions().isEmpty() && looksLikeJson(out)) {
                single = parseAndValidate(out, aiSupplement, fullSource, true, false);
            }
            //回退单次路径同样补漏（无图时本地路径；MinerU 路径带图补漏），模型可能漏短题（如判断题"（ ）"格式）
            if (extracted.isEmpty() || marksEmbedded || pageRenders != null) {
                single = new AiImportResult(fillMissingQuestions(single.questions(), fullSource,
                        settings, extractPrompt, aiSupplement, jobId, extracted), single.materials());
            }
            assertNotDegraded(single.questions(), fullSource);
            return answerService.postProcess(single, aiSupplement, fullSource, evidenceSource, settings, jobId, extracted);
        }
        //精确补漏：模型偶发漏题（块输出 9-12 题不等）→ 按题号与源文对比，缺失的题单独补一次调用。
        //本地路径含图时跳过补漏（补漏调用原本无法带图）；MinerU 路径带图补漏（marksEmbedded）——
        //补漏片段含 [图片N] 标记，对应图随消息提供，图形题丢失也能补回（实测第 40 题图形题）
        if (extracted.isEmpty() || marksEmbedded || pageRenders != null) {
            unique = fillMissingQuestions(unique, fullSource, settings, extractPrompt, aiSupplement, jobId, extracted);
        }
        assertNotDegraded(unique, fullSource);
        //题号回填：从源文定位每题题号写入 questionNumber（列表/做题按 (questionNumber, id) 排序，
        //题号 = 文档顺序，保证 AI 导入的题与录入/列表顺序一致）
        if (!unique.isEmpty()) {
            //前导块题号兜底（确定性）：模型省略标题的题（实测 Q1/Q2）位于首个标题之前，
            //按文档顺序依次对应"小于最小标题号且未被占用的题号"
            List<ContentPackageQuestion> unnumbered = new ArrayList<>();
            int minNum = Integer.MAX_VALUE;
            Set<Integer> usedNums = new HashSet<>();
            for (ContentPackageQuestion q : unique) {
                if (q.getQuestionNumber() == null) {
                    unnumbered.add(q);
                } else {
                    usedNums.add(q.getQuestionNumber());
                    minNum = Math.min(minNum, q.getQuestionNumber());
                }
            }
            if (!unnumbered.isEmpty() && minNum != Integer.MAX_VALUE) {
                for (ContentPackageQuestion q : unnumbered) {
                    for (int n = 1; n < minNum; n++) {
                        if (!usedNums.contains(n)) {
                            q.setQuestionNumber(n);
                            usedNums.add(n);
                            log.info("AI 导入任务 {} 前导块题号兜底：无标题题 → 第 {} 题", jobId, n);
                            break;
                        }
                    }
                }
            }
            String[] lines = fullSource.split("\\R", -1);
            for (ContentPackageQuestion q : unique) {
                if (q.getQuestionNumber() != null || q.getContent() == null || q.getContent().isBlank()) {
                    continue;
                }
                String content = q.getContent().replaceAll("\\[图片\\d+]", "").trim();
                if (content.isBlank()) {
                    continue;
                }
                int line = sourceTextService.locateContentLine(lines, content);
                if (line >= 0) {
                    int num = sourceTextService.findQuestionNumber(lines, line, true);
                    if (num > 0) {
                        q.setQuestionNumber(num);
                    }
                } else {
                    log.warn("AI 导入任务 {} 题号回填定位失败 content={}", jobId, truncate(content, 40));
                }
            }
        }
        //同题号去重（重叠块可能产出同题不同表述的两份，如 Q11 定义句排版差异）→ 保留质量高者
        if (!unique.isEmpty()) {
            Map<Integer, ContentPackageQuestion> byNum = new LinkedHashMap<>();
            for (ContentPackageQuestion q : unique) {
                Integer n = q.getQuestionNumber();
                if (n == null) {
                    continue;
                }
                ContentPackageQuestion existing = byNum.get(n);
                if (existing == null || visionQualityService.quality(q) > visionQualityService.quality(existing)) {
                    byNum.put(n, q);
                }
            }
            if (byNum.size() < unique.stream().filter(q -> q.getQuestionNumber() != null).count()) {
                List<ContentPackageQuestion> dedup2 = new ArrayList<>();
                for (ContentPackageQuestion q : unique) {
                    if (q.getQuestionNumber() == null || byNum.get(q.getQuestionNumber()) == q) {
                        dedup2.add(q);
                    }
                }
                log.info("AI 导入任务 {} 同题号去重：{} → {} 题", jobId, unique.size(), dedup2.size());
                unique = dedup2;
            }
        }
        //材料处理：MD 路径材料由模型声明（## 材料 mN 块，解析时按位置关联题目）。
        //本地检测（detectMaterialGroups）已退役——实测把 MinerU 文本中的题干碎片误判为 7 组假材料。
        //跨块归一：不同块的 m1 会重复 → 按内容去重后重新编号，题目引用同步重映射
        List<ContentPackageMaterial> materials = new ArrayList<>();
        Map<String, String> keyRemap = new HashMap<>();
        Set<String> seenContent = new HashSet<>();
        int mi = 0;
        for (ContentPackageMaterial m : blockMaterials) {
            String norm = m.getContent() == null ? "" : m.getContent().replaceAll("\\s+", "");
            if (norm.isEmpty() || !seenContent.add(norm)) {
                continue;
            }
            mi++;
            String newKey = "m" + mi;
            keyRemap.put(m.getMaterialKey(), newKey);
            m.setMaterialKey(newKey);
            materials.add(m);
        }
        for (ContentPackageQuestion q : unique) {
            if (q.getMaterialKey() != null && keyRemap.containsKey(q.getMaterialKey())) {
                q.setMaterialKey(keyRemap.get(q.getMaterialKey()));
            }
        }
        if (!materials.isEmpty()) {
            log.info("AI 导入任务 {} 模型声明共享材料 {} 组", jobId, materials.size());
        }
        return answerService.postProcess(new AiImportResult(unique, materials), aiSupplement, fullSource,
                evidenceSource, settings, jobId, extracted);
    }

    /** 合并材料列表（按 materialKey 去重，保留先出现的） */
    private void mergeMaterials(List<ContentPackageMaterial> target, List<ContentPackageMaterial> source) {
        if (source == null || source.isEmpty()) {
            return;
        }
        Set<String> keys = new HashSet<>();
        for (ContentPackageMaterial m : target) {
            if (m.getMaterialKey() != null) {
                keys.add(m.getMaterialKey());
            }
        }
        for (ContentPackageMaterial m : source) {
            if (m.getMaterialKey() != null && keys.add(m.getMaterialKey())) {
                target.add(m);
            }
        }
    }


    /** 按题干内容在源文定位题号（主观题卷末答案恢复用；定位失败返回 null） */
    private Integer locateNumberByContent(String fullSource, ContentPackageQuestion q) {
        if (fullSource == null || fullSource.isBlank() || q.getContent() == null || q.getContent().isBlank()) {
            return null;
        }
        String[] lines = fullSource.split("\\R", -1);
        int line = sourceTextService.locateContentLine(lines, stripImageRefs(q.getContent()));
        if (line < 0) {
            return null;
        }
        int num = sourceTextService.findQuestionNumber(lines, line, true);
        return num > 0 ? num : null;
    }

    /**
     * 思考模式补充答案/解析（分批并行）。批内题目带 [图片N] 标记 → 对应图随消息提供。
     * 材料作为作答上下文附加（材料题必须看材料才能作答；不输出材料文字、不修改题目引用——
     * 材料与题目的关联由预览页用户拖入完成，AI 不自动关联）。
     * 补充结果直接写回批内题目对象；校验 answerKeys 必须来自选项 key（无效丢弃留空，预览页用户补）。
     */
    private void supplementAnswers(List<ContentPackageQuestion> missing, AiSettings settings, Long jobId,
                                   List<DocumentParserService.ExtractedImage> extracted,
                                   List<ContentPackageMaterial> materials) {
        //强制思考模式（补充答案值得思考；不动原 settings 对象）
        AiSettings think = new AiSettings();
        think.setBaseUrl(settings.getBaseUrl());
        think.setApiKey(settings.getApiKey());
        think.setModel(settings.getModel());
        think.setVisionModel(settings.getVisionModel());
        think.setThinking(true);
        int batchSize = 10;
        List<Future<Void>> futures = new ArrayList<>();
        for (int start = 0; start < missing.size(); start += batchSize) {
            List<ContentPackageQuestion> batch = missing.subList(start, Math.min(missing.size(), start + batchSize));
            futures.add(aiChunkExecutor.submit(() -> {
                supplementBatch(batch, think, jobId, extracted, materials);
                return null;
            }));
        }
        for (Future<Void> f : futures) {
            try {
                f.get(5, TimeUnit.MINUTES);
            } catch (Exception e) {
                log.warn("AI 导入任务 {} 答案补充批次失败：{}", jobId, e.getMessage());
            }
        }
    }

    /** 单批补充：构建题目列表 prompt → 思考模式调用 → 解析答案数组 → 回填到批内题目 */
    private void supplementBatch(List<ContentPackageQuestion> batch, AiSettings think, Long jobId,
                                 List<DocumentParserService.ExtractedImage> extracted,
                                 List<ContentPackageMaterial> materials) {
        StringBuilder sb = new StringBuilder();
        sb.append("你是题库答案助手。下面是 ").append(batch.size())
                .append(" 道题目（题干+选项），请为每道题给出正确答案和简要解析。\n")
                .append("严格输出一个 JSON 对象：{\"answers\":[{\"index\":1,\"answerKeys\":[\"B\"],\"analysis\":\"...\"}]}\n")
                .append("规则：index 从 1 开始对应题目顺序；answerKeys 必须是选项 key（单选/判断一个，多选多个）；")
                .append("判断题选项固定 A=正确/B=错误；题目是图片时用 [图片N] 标记引用；")
                .append("无法确定答案时 answerKeys 返回空数组 []，不要编造；解析简明即可。\n\n");
        List<AiClientService.ImageData> imgs = new ArrayList<>();
        //共享材料作为作答上下文（资料分析/阅读材料题）：仅用于判断答案，不要输出/转写材料文字，
        //不要修改题目内容——材料与题目的关联由预览页用户拖入完成
        if (materials != null && !materials.isEmpty()) {
            sb.append("【共享材料（供作答参考，本文档部分题目依赖以下材料才能作答；仅用于判断答案，不要输出材料文字）】\n");
            for (ContentPackageMaterial m : materials) {
                sb.append(m.getContent()).append('\n');
                if (extracted != null && m.getContent() != null) {
                    Matcher mat = IMAGE_REF.matcher(m.getContent());
                    while (mat.find()) {
                        int num = Integer.parseInt(mat.group(1));
                        if (num >= 1 && num <= extracted.size()) {
                            AiClientService.ImageData img = extracted.get(num - 1).image();
                            if (!imgs.contains(img)) {
                                imgs.add(img);
                            }
                        }
                    }
                }
            }
            sb.append("\n\n");
        }
        for (int i = 0; i < batch.size(); i++) {
            ContentPackageQuestion q = batch.get(i);
            sb.append(i + 1).append(". ").append(q.getContent() == null ? "" : q.getContent()).append('\n');
            if (q.getOptions() != null) {
                for (OptionItem o : q.getOptions()) {
                    sb.append("   ").append(o.key()).append(". ").append(o.text()).append('\n');
                }
            }
            sb.append('\n');
            if (extracted != null && q.getContent() != null) {
                Matcher m = IMAGE_REF.matcher(q.getContent());
                while (m.find()) {
                    int num = Integer.parseInt(m.group(1));
                    if (num >= 1 && num <= extracted.size()) {
                        AiClientService.ImageData img = extracted.get(num - 1).image();
                        if (!imgs.contains(img)) {
                            imgs.add(img);
                        }
                    }
                }
            }
        }
        try {
            String out = imgs.isEmpty()
                    ? aiClientService.chat(think, promptFactory.buildSystemPrompt(true, false), sb.toString(), true)
                    : aiClientService.chatWithImages(promptFactory.buildVisionSettings(think), promptFactory.buildSystemPrompt(true, false), sb.toString(), imgs, true);
            JsonNode root = objectMapper.readTree(stripCodeFence(out));
            JsonNode answers = root.path("answers");
            if (!answers.isArray()) {
                log.warn("AI 导入任务 {} 答案补充输出格式异常（无 answers 数组），批次留空", jobId);
                return;
            }
            for (JsonNode item : answers) {
                int index = item.path("index").asInt() - 1;
                if (index < 0 || index >= batch.size()) {
                    continue;
                }
                ContentPackageQuestion q = batch.get(index);
                List<String> keys = new ArrayList<>();
                JsonNode keysNode = item.path("answerKeys");
                if (keysNode.isArray()) {
                    for (JsonNode k : keysNode) {
                        keys.add(k.asText().trim().toUpperCase());
                    }
                } else if (keysNode.isTextual() && !keysNode.asText().isBlank()) {
                    for (char c : keysNode.asText().toUpperCase().toCharArray()) {
                        String v = String.valueOf(c);
                        if (v.matches("[A-H]")) {
                            keys.add(v);
                        }
                    }
                }
                //校验：答案必须在选项 key 内
                if (q.getOptions() != null && !q.getOptions().isEmpty()) {
                    List<String> optionKeys = q.getOptions().stream().map(OptionItem::key).map(String::toUpperCase).toList();
                    keys.removeIf(k -> !optionKeys.contains(k));
                }
                if (!keys.isEmpty()) {
                    q.setAnswerKeys(keys);
                    q.setAnswerSource("AI_SUPPLEMENT");
                }
                if (item.hasNonNull("analysis")) {
                    q.setAnalysis(item.path("analysis").asText(null));
                }
                if (item.hasNonNull("answerText")) {
                    q.setAnswerText(item.path("answerText").asText(null));
                }
                if (item.hasNonNull("referenceAnswer")
                        && (q.getReferenceAnswer() == null || q.getReferenceAnswer().isBlank())) {
                    //已从卷末【N题答案】恢复的参考答案（ORIGINAL）优先保留，AI 补充不覆盖
                    q.setReferenceAnswer(item.path("referenceAnswer").asText(null));
                }
            }
        } catch (Exception e) {
            log.warn("AI 导入任务 {} 答案补充批次失败：{}", jobId, e.getMessage());
        }
    }

    /** 块内图片信息：图片列表 + 全局编号范围（[图片firstNum]~[图片lastNum]） */
    private record ImageChunk(List<DocumentParserService.ExtractedImage> images, int firstNum, int lastNum) {
    }

    /** 材料组：key（m1/m2…）+ 本地截取内容 + 覆盖的题号区间 [numStart, numEnd]（位置自动关联用） */
    private record MaterialGroup(String key, String content, Integer numStart, Integer numEnd) {
    }

    /**
     * 材料检测专用的"块边界题号行"：题号行 + 行尾非句号/分号。
     * 材料段落被 MinerU 拆行时可能产生"4.万个，比上年下降1.2%…。"（"40.6万个"残片）——
     * 行首 "4." 满足题号行但行尾是句号（陈述句材料行）→ 不是题号行，否则材料被腰斩漏检。
     */
    private boolean isMaterialBoundaryLine(String t) {
        if (!isQuestionNumberLine(t)) {
            return false;
        }
        String s = t.trim();
        return !s.endsWith("。") && !s.endsWith(";") && !s.endsWith("；");
    }

    /**
     * 材料组本地检测（资料分析/阅读材料题，多组支持）：
     * 以题号行为分隔，收集所有"题号行之间的连续无题号文本块"，过滤封面噪声/答案列表/
     * 无题号题的题干（选项行/问号行/冒号结尾过半/单行），按文档顺序编号 m1、m2…
     * 每组的题号区间 = 该材料块后出现的题号行（到下一个材料块前），用于位置自动关联题目。
     * 材料由后端本地截取（保真、跨块一致）：整理阶段不送 Agent，作为素材供预览页展示/自动关联。
     */
    private List<MaterialGroup> detectMaterialGroups(String fullSource) {
        List<MaterialGroup> groups = new ArrayList<>();
        if (fullSource == null || fullSource.isBlank()) {
            return groups;
        }
        String[] lines = fullSource.split("\\R", -1);
        List<String> current = new ArrayList<>();
        int currentStart = -1;
        int keyIdx = 1;
        int lastGroupIdx = -1;
        for (int i = 0; i < lines.length; i++) {
            String t = lines[i].trim();
            if (t.isEmpty()) {
                continue;
            }
            if (isMaterialBoundaryLine(t)) {
                //当前累积块完成判定（题号行出现 → 之前的无题号文本是一个候选块）
                //关键：块内可能混入"上一题的残留"（选项行/题干"？"行/嵌入题号行）——
                //整块排除会漏检真材料（行测材料前的上一题选项行污染实测）；
                //改为先修剪残留行，剩余陈述行再判材料
                if (!current.isEmpty()) {
                    List<String> materialLines = pruneResidualLines(current);
                    if (isMaterialBlock(materialLines)) {
                        MaterialGroup g = new MaterialGroup("m" + keyIdx++, String.join("\n", materialLines),
                                null, null);
                        groups.add(g);
                        lastGroupIdx = groups.size() - 1;
                    }
                    current = new ArrayList<>();
                    currentStart = -1;
                }
                //行首 [图片N] 前缀（材料图表与题号同行，如 "[图片1][图片2]6.2012-2022年…"）：
                //前缀图片是前置材料（纯图表材料组），该题号及后续题归属它；
                //要求前缀图片数 ≥2（单个 [图片N] 是题干图/选项图，不是材料组）
                if (t.matches("^(\\[图片\\d+]){2,}.*")) {
                    String stripped = t.replaceAll("^(\\[图片\\d+])+", "").trim();
                    String prefix = t.substring(0, t.length() - stripped.length()).trim();
                    MaterialGroup imgGroup = new MaterialGroup("m" + keyIdx++, prefix, null, null);
                    groups.add(imgGroup);
                    lastGroupIdx = groups.size() - 1;
                }
                //题号行归属最近的材料组（更新 numStart/numEnd）
                if (lastGroupIdx >= 0) {
                    int num = parseLeadingNumber(t);
                    MaterialGroup g = groups.get(lastGroupIdx);
                    if (g.numStart() == null) {
                        groups.set(lastGroupIdx, new MaterialGroup(g.key(), g.content(), num, num));
                    } else {
                        groups.set(lastGroupIdx, new MaterialGroup(g.key(), g.content(), g.numStart(), num));
                    }
                }
            } else {
                //封面/引导噪声（粉笔模板）——不是材料
                if (t.contains("专项智能练习") || t.contains("听课刷题") || t.contains("扫描二维码")
                        || t.contains("粉笔") || t.contains("打开客户端") || t.contains("提交答案后")) {
                    continue;
                }
                if (currentStart < 0) {
                    currentStart = i;
                }
                current.add(t);
            }
        }
        //末尾块（最后一个题号行之后）：卷末答案列表等，无后续题号 → 不关联题（同样先修剪残留）
        if (!current.isEmpty()) {
            List<String> materialLines = pruneResidualLines(current);
            if (isMaterialBlock(materialLines)) {
                MaterialGroup g = new MaterialGroup("m" + keyIdx++, String.join("\n", materialLines), null, null);
                groups.add(g);
            }
        }
        return groups;
    }

    /**
     * 修剪材料候选块中的"上一题残留"行（剔除后不整块排除）：
     * 行首选项行（"A.①③④…"）、行内含选项标记（题干+选项同行）、行尾问号（题干）、
     * 含嵌入题号（"…有几项？11.要…"）、答案列表行、部分引导语（"一. 常识判断"/"根据题目要求"）、
     * 冒号结尾（题干开头）。
     * 剩余陈述行（句号/数字/百分号结尾）→ 材料候选。matches() 是全串匹配，规则正则带结尾 .*。
     */
    private List<String> pruneResidualLines(List<String> block) {
        List<String> kept = new ArrayList<>();
        for (String t : block) {
            if (t.matches("^[A-Da-d]\\s*[.．、)）].*")) {
                continue; //选项行
            }
            if (t.matches(".*[A-Da-d]\\s*[.．、)].*")) {
                continue; //行内含选项标记（题干+选项同行）
            }
            if (t.endsWith("？") || t.endsWith("?") || t.endsWith("?。")) {
                continue; //题干疑问句
            }
            if (t.matches(".*(?<![\\d.．])\\d{1,3}\\s*[.．、](?![\\d.．]).*")) {
                continue; //嵌入题号（"1.5倍/10.2%"小数不匹配）
            }
            if (ANSWER_LINE.matcher(t).matches() || AiAnswerFormat.RANGE_ANSWER.matcher(t).matches()) {
                continue; //答案列表行
            }
            if (t.matches("^[一二三四五六七八九十]+[.、].*") || t.contains("根据题目要求")) {
                continue; //部分引导语
            }
            if (t.endsWith("：") || t.endsWith(":")) {
                continue; //题干冒号结尾
            }
            kept.add(t);
        }
        return kept;
    }

    /**
     * 候选块是否为材料（行测整卷实测收紧）：
     * - 行数≥2、总长≥60、冒号结尾行不过半（题干特征）；
     * - 排除项（任一命中即非材料）：行首选项行；答案列表行；问号结尾（题干）；
     *   行内含选项标记（题干+选项同行）；行内含嵌入题号；部分引导语；
     * - 单行例外：≥60 字符的"陈述句单行"（MinerU 常把整段材料合成一行，实测法律热线材料单行被漏检）
     *   或含 <table 的长行（表格 HTML 单行）算材料；陈述句 = 无问号/冒号结尾、无选项标记、无题号；
     *   纯图片标记行不算材料（图形题图组误检）。
     */
    private boolean isMaterialBlock(List<String> block) {
        if (block.isEmpty()) {
            return false;
        }
        if (block.size() == 1) {
            String only = block.get(0).trim();
            if (only.length() < 60) {
                return false; //过短：残留/图标
            }
            if (only.matches("^\\[图片\\d+].*")) {
                return false; //行首图片标记（图形题图区/图片路径行）不是材料；材料图表走"图片前缀组"
            }
            if (only.contains("<table")) {
                return true; //表格 HTML 单行
            }
            //陈述句单行：无问号/冒号结尾、无选项标记、无嵌入题号、非答案行 → 材料段落（MinerU 整段合成一行）
            if (only.endsWith("？") || only.endsWith("?") || only.endsWith("：") || only.endsWith(":")) {
                return false; //题干
            }
            if (only.matches(".*[A-Da-d]\\s*[.．、)].*")) {
                return false; //题干+选项同行
            }
            if (only.matches(".*(?<![\\d.．])\\d{1,3}\\s*[.．、](?![\\d.．]).*")) {
                return false; //嵌入题号（"…有几项？11.要…"）
            }
            return true;
        }
        int total = 0;
        int colonEnd = 0;
        for (String t : block) {
            total += t.length();
            //matches() 是全串匹配，行首规则必须带结尾 .*（"C.福建…D.山东…"选项同行也排除）
            if (t.matches("^[A-Da-d]\\s*[.．、)）].*")) {
                return false; //选项行
            }
            if (ANSWER_LINE.matcher(t).matches() || AiAnswerFormat.RANGE_ANSWER.matcher(t).matches()) {
                return false; //答案列表行
            }
            if (t.endsWith("？") || t.endsWith("?") || t.endsWith("?。")) {
                return false; //题干疑问句
            }
            //matches() 是全串匹配，规则正则必须带结尾 .*（否则匹配到目标后还有剩余字符 → 整体 false）
            if (t.matches(".*[A-Da-d]\\s*[.．、)].*")) {
                return false; //行内含选项标记（"…A.普惠金融…"题干+选项同行）
            }
            if (t.matches(".*(?<![\\d.．])\\d{1,3}\\s*[.．、](?![\\d.．]).*")) {
                return false; //行内含嵌入题号（"…有几项？11.要…"）；"1.5倍/10.2%"等小数不匹配
            }
            if (t.matches("^[一二三四五六七八九十]+[.、].*") || t.contains("根据题目要求")) {
                return false; //部分引导语（"一. 常识判断…"/"根据题目要求…"）
            }
            if (t.endsWith("：") || t.endsWith(":")) {
                colonEnd++;
            }
        }
        if (total < 60) {
            return false; //过短：封面文字/引导语
        }
        return colonEnd * 2 <= block.size();
    }

    /** 提取行首题号（isQuestionNumberLine 已保证匹配；兼容行首 [图片N] 标记前缀） */
    private int parseLeadingNumber(String t) {
        String s = t.replaceAll("^(\\[图片\\d+])+", "").trim();
        Matcher m = Pattern.compile("^(\\d{1,3})").matcher(s);
        return m.find() ? Integer.parseInt(m.group(1)) : -1;
    }

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

    /** 二分页偏移表：字符位置所在页（最后一个 start <= pos 的索引） */
    private int pageIndexOf(int[] pageStarts, int pos) {
        int lo = 0, hi = pageStarts.length - 1;
        while (lo < hi) {
            int mid = (lo + hi + 1) >>> 1;
            if (pageStarts[mid] <= pos) {
                lo = mid;
            } else {
                hi = mid - 1;
            }
        }
        return lo;
    }

    /**
     * 按题号对比源文，找出模型漏掉的题并单独补一次小调用（最多补 12 题，防劣化循环）。
     * 题号定位：输出题 content（已回填，必在源文）→ 其位置之后的第一个题号行 = 题号。
     * 支持孤立题号行（粉笔）与"题号+题干同行"（txt）两种格式。
     */
    private List<ContentPackageQuestion> fillMissingQuestions(List<ContentPackageQuestion> questions, String fullSource,
                                                              AiSettings settings, String systemPrompt,
                                                              boolean aiSupplement, Long jobId,
                                                              List<DocumentParserService.ExtractedImage> extracted) {
        String[] lines = fullSource.split("\\R", -1);
        //源文题号全集（孤立题号行 + 行首题号行，排除小数/答案列表行）；
        //第一个章节标题行之前的题号不计（试卷"注意事项 1．2．3．"是说明区，补漏会补出垃圾题）
        int firstSection = firstSectionHeaderLine(lines);
        List<Integer> sourceNums = new ArrayList<>();
        for (int i = firstSection; i < lines.length; i++) {
            String t = lines[i].trim();
            if (isQuestionNumberLine(t)) {
                Matcher m = Pattern.compile("^(\\d{1,3})").matcher(t);
                if (m.find()) {
                    sourceNums.add(Integer.parseInt(m.group(1)));
                }
            }
        }
        if (sourceNums.size() < 5) {
            return questions; //题号不可靠，跳过补漏
        }
        //输出题 → 题号。MD 管线的题号直接取自标题（可靠），优先用；题号缺失的题再走源文定位兜底
        //（旧实现一律源文定位：docx 模型把 [图片N] 转写成 LaTeX 后 content 与源文行不匹配 → 定位失败 →
        // 已存在的题被误判缺失 → 每轮白补漏 2-3 分钟带图调用，实测 15 题卷误补 4/9/12）
        Set<Integer> covered = new HashSet<>();
        for (ContentPackageQuestion q : questions) {
            if (q.getQuestionNumber() != null && q.getQuestionNumber() > 0) {
                covered.add(q.getQuestionNumber());
                continue;
            }
            String content = q.getContent() == null ? "" : q.getContent();
            if (content.isBlank()) {
                continue;
            }
            int line = sourceTextService.locateContentLine(lines, content);
            if (line < 0) {
                continue;
            }
            int num = sourceTextService.findQuestionNumber(lines, line, true); //向后找题号行
            if (num > 0) {
                covered.add(num);
            }
        }
        //缺失题号（上限 12：模型劣化时整块缺失也能补回；每题单独调用，成本低）
        List<Integer> missing = new ArrayList<>();
        for (int n : sourceNums) {
            if (!covered.contains(n) && missing.size() < 12) {
                missing.add(n);
            }
        }
        if (missing.isEmpty()) {
            return questions;
        }
        log.warn("AI 导入任务 {} 缺失题号 {}，开始补漏", jobId, missing);
        //补漏并行 + 强制无思考：补漏是"照抄源文片段出结构"，不需要思考（答案由补充阶段思考处理）。
        //实测：思考模式 + 逐题串行补漏 6 题耗时 13 分钟（每次调用 60-100s）；无思考 + 并行 ≈ 1-2 分钟。
        AiSettings noThink = new AiSettings();
        noThink.setBaseUrl(settings.getBaseUrl());
        noThink.setApiKey(settings.getApiKey());
        noThink.setModel(settings.getModel());
        noThink.setVisionModel(settings.getVisionModel());
        noThink.setThinking(false);
        List<ContentPackageQuestion> result = new ArrayList<>(questions);
        List<Future<List<ContentPackageQuestion>>> futures = new ArrayList<>();
        for (int n : missing) {
            futures.add(aiChunkExecutor.submit(() ->
                    fillOne(n, lines, sourceNums, fullSource, noThink, settings, systemPrompt, aiSupplement, jobId, extracted)));
        }
        for (Future<List<ContentPackageQuestion>> f : futures) {
            try {
                result.addAll(f.get(5, TimeUnit.MINUTES));
            } catch (Exception e) {
                log.warn("AI 导入任务 {} 补漏任务失败：{}", jobId, e.getMessage());
            }
        }
        //再次去重
        List<ContentPackageQuestion> dedup = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (ContentPackageQuestion q : result) {
            if (seen.add(visionQualityService.normalizeQuestion(q))) {
                dedup.add(q);
            }
        }
        return dedup;
    }

    /** 补漏单题：取源文片段 → 模型调用（无思考，带图）→ 题号匹配过滤 → 返回匹配题 */
    private List<ContentPackageQuestion> fillOne(int n, String[] lines, List<Integer> sourceNums, String fullSource,
                                                 AiSettings noThink, AiSettings settings, String systemPrompt,
                                                 boolean aiSupplement, Long jobId,
                                                 List<DocumentParserService.ExtractedImage> extracted) {
        int idx = sourceNums.indexOf(n);
        //起始：上一题题号行（片段含上一题，供模型参照边界）；第一题向前看 3 行（保留章节标题"一、选择题"作题型上下文）
        int startLine = idx > 0 ? sourceTextService.findLineOf(lines, sourceNums.get(idx - 1)) : Math.max(0, sourceTextService.findLineOf(lines, n) - 3);
        //结束：下一题题号行（整题完整片段——多行计算题/实验题不再被 +4 行截断）；最后一题到文末
        int endLine = idx + 1 < sourceNums.size() ? sourceTextService.findLineOf(lines, sourceNums.get(idx + 1)) : lines.length;
        StringBuilder frag = new StringBuilder();
        for (int i = startLine; i < endLine; i++) {
            frag.append(lines[i]).append('\n');
        }
        //不修剪（trimHead=false）：修剪会把目标题行当"上一题残片"删掉（实测第 1 题补漏整体丢失）；
        //上一题内容由 prompt"只整理第 N 题"约束，模型自行忽略
        String cleanFrag = frag.toString();
        String user = "以下是整份文档中第 " + n + " 题的原文（可能含少量上一题残留，请忽略），请只整理第 " + n
                + " 题，按 Markdown 模板输出（标题用 ## 第" + n + "题 · 题型）：\n\n" + cleanFrag;
        //带图补漏（MinerU 路径）：片段中的 [图片N] 标记 → 对应图随消息提供
        List<AiClientService.ImageData> imgs = new ArrayList<>();
        if (extracted != null && !extracted.isEmpty()) {
            Matcher refM = IMAGE_REF.matcher(cleanFrag);
            while (refM.find()) {
                int num = Integer.parseInt(refM.group(1));
                if (num >= 1 && num <= extracted.size()) {
                    AiClientService.ImageData img = extracted.get(num - 1).image();
                    if (!imgs.contains(img)) {
                        imgs.add(img);
                    }
                }
            }
        }
        List<ContentPackageQuestion> matched = new ArrayList<>();
        for (int attempt = 0; attempt < 2; attempt++) {
            try {
                //第 1 次无思考（快），第 2 次思考（稳）——端点偶发空白内容时交替可恢复
                AiSettings rs = attempt == 0 ? noThink : promptFactory.withThinking(settings, true);
                String out = imgs.isEmpty()
                        ? aiClientService.chat(rs, systemPrompt, user, true)
                        : aiClientService.chatWithImages(promptFactory.buildVisionSettings(rs), systemPrompt, user, imgs, true);
                log.info("AI 导入任务 {} 补漏第 {} 题原始输出（{} 字符）：{}",
                        jobId, n, out.length(), truncate(out.replaceAll("\\R+", " | "), 900));
                List<ContentPackageQuestion> parsed = mdParseQuestions(out, aiSupplement);
                //补漏结果必须对应目标题号：content 定位 → 题号 == n，否则丢弃。
                //（模型劣化时可能输出其他题的内容——"角误差+乱配选项"实测——校验只查源文存在性查不出）
                for (ContentPackageQuestion p : parsed) {
                    if (p.getContent() == null || p.getContent().isBlank()) {
                        continue;
                    }
                    int pLine = sourceTextService.locateContentLine(lines, p.getContent().replaceAll("\\[图片\\d+]", "").trim());
                    int pNum = pLine >= 0 ? sourceTextService.findQuestionNumber(lines, pLine, true) : -1;
                    if (pNum == n) {
                        //补漏题直接写死题号（定位已确认）——补漏结果原本追加在列表末尾，
                        //无题号时排序归位失效（图形题等被排到末尾）；此处显式设置后按题号排序即归位
                        p.setQuestionNumber(n);
                        matched.add(p);
                    } else {
                        log.warn("AI 导入任务 {} 补漏第 {} 题输出错配（定位题号 {}），丢弃", jobId, n, pNum);
                    }
                }
                log.info("AI 导入任务 {} 补漏第 {} 题（第 {} 次）：解析 {} 题{}", jobId, n, attempt + 1, matched.size(),
                        imgs.isEmpty() ? "" : "（带图 " + imgs.size() + " 张）");
                if (!matched.isEmpty()) {
                    break; //有题即成功；空结果重试一次
                }
            } catch (Exception e) {
                log.warn("AI 导入任务 {} 补漏第 {} 题（第 {} 次）失败：{}", jobId, n, attempt + 1, e.getMessage());
            }
        }
        return matched;
    }

    /** 规范化题目内容（去空白 + 剥离图片编号标记）用于去重：同题干+同选项视为同一题（题干忠实原文） */
    private String normalizeQuestion(ContentPackageQuestion q) {
        StringBuilder sb = new StringBuilder(normForDedup(q.getContent()));
        if (q.getOptions() != null) {
            for (OptionItem o : q.getOptions()) {
                sb.append('|').append(normForDedup(o.text()));
            }
        }
        return sb.toString();
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





    private String stripOutOfRangeRefs(String text, int firstNum, int lastNum) {
        Matcher m = Pattern.compile("\\[图片(\\d+)\\]").matcher(text);
        StringBuilder sb = new StringBuilder();
        while (m.find()) {
            int n = Integer.parseInt(m.group(1));
            if (n < firstNum || n > lastNum) {
                m.appendReplacement(sb, "");
            }
        }
        m.appendTail(sb);
        return sb.toString();
    }

    /** 视觉路径合并后按源文位置排序（还原文档顺序；补漏题按源文行归位，不再追加在末尾） */
    private List<ContentPackageQuestion> sortBySourceOrder(List<ContentPackageQuestion> questions, String fullSource) {
        if (fullSource == null || fullSource.isBlank()) {
            return questions;
        }
        String[] lines = fullSource.split("\\R", -1);
        List<ContentPackageQuestion> sorted = new ArrayList<>(questions);
        sorted.sort((a, b) -> {
            int la = sourceTextService.locateContentLine(lines, stripImageRefs(a.getContent() == null ? "" : a.getContent()));
            int lb = sourceTextService.locateContentLine(lines, stripImageRefs(b.getContent() == null ? "" : b.getContent()));
            if (la < 0 && lb < 0) {
                return 0;
            }
            if (la < 0) {
                return 1;
            }
            if (lb < 0) {
                return -1;
            }
            return Integer.compare(la, lb);
        });
        return sorted;
    }

    // ==================== 视觉单次路径（主路径：简化 prompt + 整份 PDF 单次调用） ====================

    /**
     * PDF-MD 矢量图兜底：无 [图片N] 引用的题（图形题的图是矢量绘制、无内嵌位图，粉笔公考 PDF 常见）
     * → 按题干在源文中的页内位置，从 300 DPI 整页渲染中裁剪"题干下方第一个图形块"，就地追加 [图片N]。
     * 只做题干图（不塞选项）；定位失败/无图形块 → 保持原样（预览人工补图）。
     */
    private AiImportResult resolvePdfStemImages(AiImportResult parsed, List<String> pageTexts,
                                                List<DocumentParserService.ExtractedImage> extracted,
                                                Path jobDir, String pdfFileName, Long jobId) {
        try {
            byte[] pdfBytes = Files.readAllBytes(jobDir.resolve("0-" + pdfFileName));
            List<AiClientService.ImageData> pageImages = documentParserService.renderAllPages(pdfBytes, ANALYSIS_RENDER_DPI);
            List<List<DocumentParserService.LinePos>> linePositions = documentParserService.collectLinePositions(pdfBytes);
            float scale = ANALYSIS_RENDER_DPI / 72f;
            float pageHeightPt = 842f;
            try {
                BufferedImage img0 = javax.imageio.ImageIO.read(new java.io.ByteArrayInputStream(pageImages.get(0).data()));
                if (img0 != null) {
                    pageHeightPt = img0.getHeight() / scale;
                }
            } catch (Exception ignored) {
            }
            //源文行 → 页 映射（fullSource = 逐页文本拼接，行结构与 pageTexts 一致）
            String source = String.join("", pageTexts);
            String[] lines = source.split("\\R", -1);
            int[] lineStarts = new int[lines.length];
            int acc = 0;
            for (int i = 0; i < lines.length; i++) {
                lineStarts[i] = acc;
                acc += lines[i].length() + 1;
            }
            int[] pageStarts = new int[pageTexts.size() + 1];
            acc = 0;
            for (int i = 0; i < pageTexts.size(); i++) {
                pageStarts[i] = acc;
                acc += pageTexts.get(i).length();
            }
            pageStarts[pageTexts.size()] = acc;
            Map<Integer, List<float[]>> blocksByPage = new HashMap<>();
            int nextImageNo = extracted.size() + 1;
            List<ContentPackageQuestion> out = new ArrayList<>(parsed.questions());
            //垃圾块过滤：无题号且题干（剥离图片引用后）为空——模型在块首臆造的图片题残块（实测判断推理块首
            //"[图片1]+空选项"垃圾块），丢弃（真正的题必有序号或题干文字）
            out.removeIf(q -> q.getQuestionNumber() == null
                    && stripImageRefs(q.getContent() == null ? "" : q.getContent()).replaceAll("[^\\p{L}\\p{N}]", "").isEmpty());
            log.info("AI 导入任务 {} 矢量图兜底：内嵌图页号分布 [{}]，共 {} 题",
                    jobId, extracted.stream().map(x -> String.valueOf(x.pageNo()))
                            .collect(java.util.stream.Collectors.joining(",")), out.size());
            //第一遍：为每道无图题定位题干行 y（游标推进——相同引导语的题如 Q19/Q20 按出现顺序归位，
            //否则都定位到第一处，第二题会裁到第一题的图）
            Map<Integer, List<float[]>> located = new HashMap<>(); // page -> {contentY}（与 out 顺序对应存索引）
            List<Integer> order = new ArrayList<>(); // 可处理题在 out 中的索引
            List<Float> ys = new ArrayList<>();
            List<Integer> pages = new ArrayList<>();
            List<Integer> locLines = new ArrayList<>(); // 各题定位到的源文行（判断"选项是否为图形"用）
            Map<String, Integer> probeCursor = new HashMap<>();
            int lastGlobalLine = -1; // 上一道已定位题的源文全局行（内容空题回填题干时从这里往后找题号行）
            for (int qi = 0; qi < out.size(); qi++) {
                ContentPackageQuestion q = out.get(qi);
                //题干文字回填：模型偶尔只输出 [图片N] 或把引导语整个丢掉（图形题引导语在题号行），
                //从源文按题号行回填引导语文字（先回填，后面 locateContentLine 才能定位 → 矢量图兜底裁剪）
                String textOnly = stripImageRefs(q.getContent() == null ? "" : q.getContent());
                if (textOnly.replaceAll("[^\\p{L}\\p{N}]", "").isEmpty() && q.getQuestionNumber() != null) {
                    String stem = backfillStemLine(lines, Math.max(0, lastGlobalLine + 1), q.getQuestionNumber());
                    if (stem != null && !stem.isBlank()) {
                        String imgs = (q.getContent() == null ? "" : q.getContent()).replaceAll("[^\\[\\]\\d图片]", "");
                        imgs = (imgs == null || imgs.isBlank()) ? "" : "\n" + imgs.trim();
                        q.setContent(stem + imgs);
                        log.info("AI 导入任务 {} 题干回填：题号 {} 从源文回填引导语（{} 字符）",
                                jobId, q.getQuestionNumber(), stem.length());
                    }
                }
                if (q.getContent() == null || textOnly.isBlank()) {
                    continue;
                }
                int line = sourceTextService.locateContentLine(lines, textOnly);
                if (line < 0) {
                    continue;
                }
                int page = pageIndexOf(pageStarts, lineStarts[line]);
                if (page < 0 || page >= pageImages.size() || page >= linePositions.size()) {
                    continue;
                }
                String probe = stripImageRefs(q.getContent()).split("\\R", 2)[0].replaceAll("\\s+", "");
                if (probe.length() > 8) {
                    probe = probe.substring(0, 8);
                }
                if (probe.isBlank()) {
                    continue;
                }
                List<DocumentParserService.LinePos> lps = linePositions.get(page);
                int from = probeCursor.getOrDefault(probe, 0);
                int best = -1;
                for (int li = Math.max(0, from); li < lps.size(); li++) {
                    if (lps.get(li).text().replaceAll("\\s+", "").contains(probe)) {
                        best = li;
                        break;
                    }
                }
                if (best < 0 && from > 0) {
                    for (int li = 0; li < lps.size(); li++) { //跨页游标失效兜底
                        if (lps.get(li).text().replaceAll("\\s+", "").contains(probe)) {
                            best = li;
                            break;
                        }
                    }
                }
                if (best < 0) {
                    continue;
                }
                probeCursor.put(probe, best + 1);
                lastGlobalLine = line;
                order.add(qi);
                pages.add(page);
                ys.add(lps.get(best).y());
                locLines.add(line);
            }
            Map<Integer, List<Integer>> byPage = new HashMap<>();
            for (int i = 0; i < order.size(); i++) {
                byPage.computeIfAbsent(pages.get(i), k -> new ArrayList<>()).add(i);
            }
            //第二遍：按页（升序）、页内按 y 排序 → 每题带 = [自身 y, 下一题 y)（页内最后一题到页底）。
            //图形题确定性配图（不信任模型引用）：选项是图形 → 按文档顺序分配未用内嵌图 / 带内裁剪；
            //题干图（图形推理/六图分类/问号题）→ 带内图形块裁剪，相邻图形题各裁各的带。
            Set<Integer> usedEmbedded = new HashSet<>();
            for (ContentPackageQuestion q : out) {
                String stem = stripImageRefs(q.getContent() == null ? "" : q.getContent());
                if (FIGURE_STEM.matcher(stem).find()) {
                    continue; //图形题不占用内嵌图编号（模型引用会被重配）
                }
                Matcher refM = IMAGE_REF.matcher(q.getContent() == null ? "" : q.getContent());
                while (refM.find()) {
                    usedEmbedded.add(Integer.parseInt(refM.group(1)));
                }
                if (q.getOptions() != null) {
                    for (OptionItem o : q.getOptions()) {
                        Matcher om = IMAGE_REF.matcher(o.text() == null ? "" : o.text());
                        while (om.find()) {
                            usedEmbedded.add(Integer.parseInt(om.group(1)));
                        }
                    }
                }
            }
            List<Map.Entry<Integer, List<Integer>>> pageOrder = new ArrayList<>(byPage.entrySet());
            pageOrder.sort(Map.Entry.comparingByKey());
            for (Map.Entry<Integer, List<Integer>> e : pageOrder) {
                int page = e.getKey();
                List<Integer> idxs = e.getValue();
                idxs.sort(java.util.Comparator.comparingDouble(i -> ys.get(i)));
                List<float[]> blocks = blocksByPage.get(page);
                float pageWidthPt = pageHeightPt;
                if (blocks == null) {
                    List<DocumentParserService.LinePos> textLines = linePositions.get(page);
                    try {
                        BufferedImage pageImg = javax.imageio.ImageIO.read(
                                new java.io.ByteArrayInputStream(pageImages.get(page).data()));
                        if (pageImg != null) {
                            pageWidthPt = pageImg.getWidth() / scale;
                            blocks = detectGraphBlocks(pageImg, scale, pageHeightPt, textLines);
                        } else {
                            blocks = List.of();
                        }
                    } catch (Exception ex) {
                        blocks = List.of();
                    }
                    blocksByPage.put(page, blocks);
                }
                Set<String> usedBlocks = new HashSet<>(); // 本页已配给某题的图形块（同页双题共用合并簇时防重复认领）
                for (int si = 0; si < idxs.size(); si++) {
                    int qi = order.get(idxs.get(si));
                    ContentPackageQuestion q = out.get(qi);
                    String stemText = stripImageRefs(q.getContent() == null ? "" : q.getContent()).trim();
                    boolean isFigure = FIGURE_STEM.matcher(stemText).find();
                    float bandTop = ys.get(idxs.get(si));
                    float bandBot = si + 1 < idxs.size() ? ys.get(idxs.get(si + 1)) : pageHeightPt - 10;
                    if (bandBot - bandTop < 10) {
                        continue;
                    }
                    //带内图形块（整块落在带内的才算——相邻题图形簇相连时各归各的带；已配给其他题的块跳过）
                    List<float[]> inBand = new ArrayList<>();
                    for (float[] b : blocks) {
                        if (b[0] >= bandTop - 5 && b[1] <= bandBot + 2 && !usedBlocks.contains(blockKey(b))) {
                            inBand.add(b);
                        }
                    }
                    log.info("AI 导入任务 {} 配图调试：题号 {} 页 {} y={} band=[{}..{}] isFigure={} 全页块[{}] 带内块[{}]",
                            jobId, q.getQuestionNumber(), page, String.format("%.1f", ys.get(idxs.get(si))),
                            String.format("%.1f", bandTop), String.format("%.1f", bandBot), isFigure,
                            blocks.stream().map(b -> String.format("%.0f-%.0f", b[0], b[1]))
                                    .collect(java.util.stream.Collectors.joining(" ")),
                            inBand.stream().map(b -> String.format("%.0f-%.0f", b[0], b[1]))
                                    .collect(java.util.stream.Collectors.joining(" ")));
                    //选项是否为图形：源文题干行之后、下一题之前没有文字选项行 → 选项是图形（俯视图/展开图等）。
                    //停步：下一题号行 / 下一题设问行（结尾 ：？ 或行尾题号"…8."）/ 设问引导词行。
                    int locLine = locLines.get(idxs.get(si));
                    boolean sourceHasTextOpts = false;
                    for (int li = locLine + 1; li < lines.length; li++) {
                        String t = lines[li].trim();
                        if (t.isEmpty()) {
                            continue;
                        }
                        if (t.matches("^\\d{1,3}\\s*[.．、)）].*")
                                || t.matches(".*[：:？?]$")
                                || t.matches(".*\\d{1,3}\\s*[.．、]$")
                                || t.matches("^(根据上述定义|下列|要使|据此|由此|以下|上述|从所给|若|如果|除非).*")) {
                            break; //下一题开始
                        }
                        if (t.matches("^[A-Ha-h]\\s*[.．、].*")) {
                            sourceHasTextOpts = true;
                            break;
                        }
                    }
                    int optionSlots = q.getOptions() == null ? 0 : q.getOptions().size();
                    boolean optsImageOnly = optionSlots > 0 && q.getOptions().stream().allMatch(o -> {
                        String t = o.text() == null ? "" : o.text().trim();
                        return t.isEmpty() || t.matches("\\[图片\\d+\\]");
                    });
                    boolean figureImgOpts = isFigure && !sourceHasTextOpts && (optionSlots == 0 || optsImageOnly);
                    if (figureImgOpts) {
                        //题干图（"上图"/引图）与选项图组分离：带内 ≥2 个图形块时最上块为题干图，最下块为选项图组
                        float[] stemBlock = null;
                        float[] gridBlock = null;
                        if (inBand.size() >= 2) {
                            stemBlock = inBand.get(0);
                            gridBlock = inBand.get(inBand.size() - 1);
                        } else if (inBand.size() == 1) {
                            gridBlock = inBand.get(0);
                        }
                        int need = Math.max(optionSlots, 4);
                        List<String> assigned = new ArrayList<>();
                        //1) 未用内嵌图（页号 ≥ 本题页，按文档顺序）——选项图跨页时内嵌图按页归属拆散，此处全量找回
                        for (int idx = 0; idx < extracted.size() && assigned.size() < need; idx++) {
                            int n = idx + 1;
                            if (usedEmbedded.contains(n)) {
                                continue;
                            }
                            if (extracted.get(idx).pageNo() >= page) {
                                assigned.add("[图片" + n + "]");
                                usedEmbedded.add(n);
                            }
                        }
                        //2) 不足 → 选项图组网格拆分（x/y 暗像素投影聚类，1x4 / 2x2 排版均适用；聚类失败退化为 2x2 四分）
                        boolean gridSplitUsed = false;
                        if (assigned.size() < need && gridBlock != null) {
                            float cropTop = Math.max(gridBlock[0], bandTop - 2);
                            float cropBot = Math.min(gridBlock[1], bandBot + 2);
                            if (cropBot - cropTop >= 24) {
                                List<float[]> cells = List.of();
                                try {
                                    BufferedImage gridImg = javax.imageio.ImageIO.read(
                                            new java.io.ByteArrayInputStream(pageImages.get(page).data()));
                                    if (gridImg != null) {
                                        cells = detectGridCells(gridImg, scale, cropTop, cropBot,
                                                40f, pageWidthPt - 40f, need - assigned.size());
                                    }
                                } catch (Exception ignored) {
                                }
                                if (cells.size() < 2) {
                                    float midY = (cropTop + cropBot) / 2;
                                    float midX = pageWidthPt / 2;
                                    cells = new ArrayList<>();
                                    cells.add(new float[]{cropTop, midY, 40f, midX});
                                    cells.add(new float[]{cropTop, midY, midX, pageWidthPt - 40f});
                                    cells.add(new float[]{midY, cropBot, 40f, midX});
                                    cells.add(new float[]{midY, cropBot, midX, pageWidthPt - 40f});
                                }
                                log.info("AI 导入任务 {} 配图调试：题号 {} 选项图组网格 {}-{}pt 拆出 {} 格（前 2 格 [{},{},{},{}]）",
                                        jobId, q.getQuestionNumber(), String.format("%.1f", cropTop),
                                        String.format("%.1f", cropBot), cells.size(),
                                        cells.isEmpty() ? "-" : String.format("%.0f", cells.get(0)[0]),
                                        cells.isEmpty() ? "-" : String.format("%.0f", cells.get(0)[1]),
                                        cells.isEmpty() ? "-" : String.format("%.0f", cells.get(0)[2]),
                                        cells.isEmpty() ? "-" : String.format("%.0f", cells.get(0)[3]));
                                for (float[] c : cells) {
                                    if (assigned.size() >= need) {
                                        break;
                                    }
                                    String num = cropBand(c[0], c[1], c[2], c[3], false, scale, page,
                                            Map.of(), pageImages, jobDir, nextImageNo);
                                    if (num != null) {
                                        assigned.add("[图片" + num + "]");
                                        nextImageNo = Integer.parseInt(num) + 1;
                                        gridSplitUsed = true;
                                    }
                                }
                                if (gridSplitUsed) {
                                    usedBlocks.add(blockKey(gridBlock));
                                }
                            }
                        }
                        if (assigned.size() >= 2) {
                            List<OptionItem> newOpts = new ArrayList<>();
                            String[] labels = {"A", "B", "C", "D"};
                            for (int k = 0; k < Math.min(assigned.size(), labels.length); k++) {
                                newOpts.add(new OptionItem(labels[k], assigned.get(k)));
                            }
                            q.setContent(stemText);
                            q.setOptions(newOpts);
                            q.setType("SINGLE");
                            log.info("AI 导入任务 {} 选项图配图：题号 {} 分配 {} 张选项图",
                                    jobId, q.getQuestionNumber(), newOpts.size());
                        }
                        //题干图（"上图"/引图）裁剪：带内 ≥2 块取最上块；单块且选项图已由内嵌图覆盖（未用于拆分）时该块即题干图
                        if (stemBlock != null || (assigned.size() >= need && inBand.size() == 1 && !gridSplitUsed)) {
                            float[] sb = stemBlock != null ? stemBlock : inBand.get(0);
                            float cropTop = Math.max(sb[0], bandTop - 2);
                            float cropBot = Math.min(sb[1], bandBot + 2);
                            if (cropBot - cropTop >= 12) {
                                String num = cropBand(cropTop, cropBot, 40f, -1f, false, scale, page,
                                        Map.of(), pageImages, jobDir, nextImageNo);
                                if (num != null) {
                                    q.setContent(q.getContent() + "\n[图片" + num + "]");
                                    byte[] png = Files.readAllBytes(jobDir.resolve("images").resolve(num + ".png"));
                                    extracted.add(new DocumentParserService.ExtractedImage(page, 40f, cropTop, pageHeightPt,
                                            new AiClientService.ImageData("image/png", png)));
                                    nextImageNo = Integer.parseInt(num) + 1;
                                    usedBlocks.add(blockKey(sb));
                                }
                            }
                        }
                        continue;
                    }
                    //题干图：带内图形块裁剪（六图分类簇/问号题图形；相邻题按带切割）。
                    //带内无块时图形题放宽：同页双题图形簇相邻/双栏错位，后一题图形块可能在题干行上方
                    //（Q20 六图簇在文本上方 35pt）→ 找最近的未用块（向题干行上方回探 150pt）；
                    //页内仍无 → 末题图形跨页（在下一页顶部）时取下一页第一个未用块
                    float[] stemBlockSel = null;
                    int cropPage = page;
                    boolean stemAboveText = false; // 图形块整体在题干行上方（双栏错位）→ 从块顶裁剪
                    if (!inBand.isEmpty()) {
                        stemBlockSel = inBand.get(0);
                    } else if (isFigure) {
                        for (float[] b : blocks) {
                            if (!usedBlocks.contains(blockKey(b)) && b[1] >= bandTop - 150 && b[0] <= bandBot + 2) {
                                stemBlockSel = b;
                                stemAboveText = b[1] < bandTop - 5;
                                break;
                            }
                        }
                        //跨页兜底仅限整份文档最后一题（下一页顶部块只可能是它的续图；其他情况
                        //下一页顶部块多半属于下一页第一题，禁止挪用）
                        if (stemBlockSel == null && qi == out.size() - 1 && page + 1 < pageImages.size()) {
                            List<float[]> np = blocksByPage.get(page + 1);
                            if (np == null) {
                                try {
                                    BufferedImage nImg = javax.imageio.ImageIO.read(
                                            new java.io.ByteArrayInputStream(pageImages.get(page + 1).data()));
                                    np = nImg == null ? List.of()
                                            : detectGraphBlocks(nImg, scale, pageHeightPt, linePositions.get(page + 1));
                                } catch (Exception ex) {
                                    np = List.of();
                                }
                                blocksByPage.put(page + 1, np);
                            }
                            for (float[] b : np) {
                                if (b[0] < pageHeightPt / 2) { // 下一页顶部区域（多半是本题续图）
                                    stemBlockSel = b;
                                    cropPage = page + 1;
                                    break;
                                }
                            }
                        }
                    }
                    if (stemBlockSel == null) {
                        continue;
                    }
                    if (!isFigure && q.getContent() != null && q.getContent().contains("[图片")) {
                        continue; //非图形题已有模型引用（照片/图表），不再裁剪
                    }
                    //裁剪区间：块整体在题干行上方（stemAboveText）时从块顶裁到带底，不向题干行收敛
                    //（否则 cropTop=题干行 > cropBot=块底 → 区间倒置被丢弃，Q20 六图簇在文本上方 35pt 即此情况）
                    float cropTop;
                    if (cropPage != page) {
                        cropTop = stemBlockSel[0];
                    } else if (stemAboveText) {
                        cropTop = stemBlockSel[0];
                    } else {
                        cropTop = Math.max(stemBlockSel[0], bandTop - 2);
                    }
                    float cropBot = cropPage != page ? stemBlockSel[1] : Math.min(stemBlockSel[1], bandBot + 2);
                    if (cropBot - cropTop < 12) {
                        continue;
                    }
                    String num = cropBand(cropTop, cropBot, 40f, -1f, false, scale, cropPage,
                            Map.of(), pageImages, jobDir, nextImageNo);
                    if (num == null) {
                        continue;
                    }
                    q.setContent(stemText + "\n[图片" + num + "]");
                    //同步扩展 extracted：确认导入时 [图片N] → images/{N}.png 映射依赖
                    byte[] png = Files.readAllBytes(jobDir.resolve("images").resolve(num + ".png"));
                    extracted.add(new DocumentParserService.ExtractedImage(cropPage, 40f, cropTop, pageHeightPt,
                            new AiClientService.ImageData("image/png", png)));
                    nextImageNo = Integer.parseInt(num) + 1;
                    usedBlocks.add(blockKey(stemBlockSel));
                    log.info("AI 导入任务 {} 矢量图兜底：题号 {} 裁剪题干图 [图片{}]", jobId, q.getQuestionNumber(), num);
                }
            }
            return new AiImportResult(out, parsed.materials());
        } catch (Exception e) {
            log.warn("AI 导入任务 {} PDF 矢量图兜底失败：{}", jobId, e.getMessage());
            return parsed;
        }
    }

    /**
     * PDF 直传图题归位（v2，替代矢量裁剪兜底）：
     * 粉笔类题库 PDF 的每道图形题 = 一张"整题图包"——题干图形与全部选项图形合并成一张内嵌图
     * （文本层无选项文字，实测 1.png=Q2 俯视图整题、2.png=Q7 四展开图、3.png=Q14 九宫格+选项）。
     * 因此正确模型是"每道图题一张图、放题干末尾"，而不是把图塞进选项或裁剪。
     * 实现：内嵌图按绘制位置（所在页 + 底边距页顶）与各图形题题干行做全局最近距离匹配；
     * 不再裁剪/拆分（裁剪只取到行切块的一半图形，质量不可用——实测六图组被切成 3+3 两半）。
     * 无内嵌图的图形题（矢量图）不留图 → 预览页人工补图。
     */
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

    private AiImportResult assignPdfFigureImages(AiImportResult parsed, List<String> pageTexts,
                                                 List<DocumentParserService.ExtractedImage> extracted,
                                                 Path jobDir, String pdfFileName, Long jobId) {
        try {
            byte[] pdfBytes = Files.readAllBytes(jobDir.resolve("0-" + pdfFileName));
            List<List<DocumentParserService.LinePos>> linePositions = documentParserService.collectLinePositions(pdfBytes);
            //源文行 → 页映射
            String source = String.join("", pageTexts);
            String[] lines = source.split("\\R", -1);
            int[] lineStarts = new int[lines.length];
            int acc = 0;
            for (int i = 0; i < lines.length; i++) {
                lineStarts[i] = acc;
                acc += lines[i].length() + 1;
            }
            int[] pageStarts = new int[pageTexts.size() + 1];
            acc = 0;
            for (int i = 0; i < pageTexts.size(); i++) {
                pageStarts[i] = acc;
                acc += pageTexts.get(i).length();
            }
            pageStarts[pageTexts.size()] = acc;
            List<ContentPackageQuestion> out = new ArrayList<>(parsed.questions());
            //垃圾块过滤：无题号且题干（剥离图片引用后）为空
            out.removeIf(q -> q.getQuestionNumber() == null
                    && stripImageRefs(q.getContent() == null ? "" : q.getContent()).replaceAll("[^\\p{L}\\p{N}]", "").isEmpty());
            //题干回填 + 每题定位（题干行 y 为 top-origin pt，与图锚点同坐标系）
            List<Integer> order = new ArrayList<>();
            List<Float> ys = new ArrayList<>();
            List<Integer> pages = new ArrayList<>();
            Map<String, Integer> probeCursor = new HashMap<>();
            int lastGlobalLine = -1;
            for (int qi = 0; qi < out.size(); qi++) {
                ContentPackageQuestion q = out.get(qi);
                String textOnly = stripImageRefs(q.getContent() == null ? "" : q.getContent());
                if (textOnly.replaceAll("[^\\p{L}\\p{N}]", "").isEmpty() && q.getQuestionNumber() != null) {
                    String stem = backfillStemLine(lines, Math.max(0, lastGlobalLine + 1), q.getQuestionNumber());
                    if (stem != null && !stem.isBlank()) {
                        String imgs = (q.getContent() == null ? "" : q.getContent()).replaceAll("[^\\[\\]\\d图片]", "");
                        imgs = (imgs == null || imgs.isBlank()) ? "" : "\n" + imgs.trim();
                        q.setContent(stem + imgs);
                        log.info("AI 导入任务 {} 题干回填：题号 {} 从源文回填引导语（{} 字符）",
                                jobId, q.getQuestionNumber(), stem.length());
                    }
                }
                String s2 = stripImageRefs(q.getContent() == null ? "" : q.getContent());
                if (s2.isBlank()) {
                    continue;
                }
                int line = sourceTextService.locateContentLine(lines, s2);
                if (line < 0) {
                    continue;
                }
                int page = pageIndexOf(pageStarts, lineStarts[line]);
                if (page < 0 || page >= linePositions.size()) {
                    continue;
                }
                String probe = s2.split("\\R", 2)[0].replaceAll("\\s+", "");
                if (probe.length() > 8) {
                    probe = probe.substring(0, 8);
                }
                if (probe.isBlank()) {
                    continue;
                }
                List<DocumentParserService.LinePos> lps = linePositions.get(page);
                int from = probeCursor.getOrDefault(probe, 0);
                int best = -1;
                for (int li = Math.max(0, from); li < lps.size(); li++) {
                    if (lps.get(li).text().replaceAll("\\s+", "").contains(probe)) {
                        best = li;
                        break;
                    }
                }
                if (best < 0 && from > 0) {
                    for (int li = 0; li < lps.size(); li++) {
                        if (lps.get(li).text().replaceAll("\\s+", "").contains(probe)) {
                            best = li;
                            break;
                        }
                    }
                }
                if (best < 0) {
                    continue;
                }
                probeCursor.put(probe, best + 1);
                lastGlobalLine = line;
                order.add(qi);
                ys.add(lps.get(best).y());
                pages.add(page);
            }
            //模型引用占用收集：模型看图主导——所有题（含图形题）的 [图片N] 引用都保留并占用编号；
            //越界/悬空编号由落库时保留原标记（预览可见可改），不在此清理
            Set<Integer> used = new HashSet<>();
            for (ContentPackageQuestion q : out) {
                Matcher m = IMAGE_REF.matcher((q.getContent() == null ? "" : q.getContent())
                        + (q.getOptions() == null ? "" : q.getOptions().stream()
                        .map(o -> o.text() == null ? "" : o.text())
                        .collect(java.util.stream.Collectors.joining())));
                while (m.find()) {
                    used.add(Integer.parseInt(m.group(1)));
                }
            }
            //兜底候选：仅对"模型未在题干引用任何图"的图形题，按版面坐标就近配同页未用图
            //（模型漏配时补救；程序坐标在题干重复/定位失败时不可靠，故只作兜底不主导）
            List<Object[]> cand = new ArrayList<>(); // {题索引, 图序号(1..N), 距离}
            for (int i = 0; i < order.size(); i++) {
                int qi = order.get(i);
                ContentPackageQuestion q = out.get(qi);
                String stemText = stripImageRefs(q.getContent() == null ? "" : q.getContent()).trim();
                if (!FIGURE_STEM.matcher(stemText).find()) {
                    continue;
                }
                String rawContent = q.getContent() == null ? "" : q.getContent();
                if (IMAGE_REF.matcher(rawContent).find()) {
                    continue; //模型已为本题题干引用图形 → 保留模型归属，不兜底覆盖
                }
                float bandTop = ys.get(i);
                float bandBot = i + 1 < order.size() ? ys.get(i + 1) : 100000f;
                //注意：带按文档顺序未必页内连续——同页内才比较（不同页候选由页匹配天然隔离）
                if (i + 1 < order.size() && !pages.get(i).equals(pages.get(i + 1))) {
                    bandBot = 100000f;
                }
                for (int idx = 0; idx < extracted.size(); idx++) {
                    DocumentParserService.ExtractedImage im = extracted.get(idx);
                    if (im.pageNo() != pages.get(i)) {
                        continue;
                    }
                    if (used.contains(idx + 1)) {
                        continue;
                    }
                    float anchor = im.pageHeight() - im.sortY(); // 图底边距页顶（top-origin pt）
                    if (anchor > bandBot + 20 || anchor < bandTop - 300) {
                        continue;
                    }
                    float d = Math.abs(anchor - bandTop);
                    cand.add(new Object[]{qi, idx + 1, d});
                }
            }
            cand.sort(java.util.Comparator.comparingDouble(o -> (Float) o[2]));
            int assigned = 0;
            Set<Integer> done = new HashSet<>();
            for (Object[] c : cand) {
                int qi = (Integer) c[0];
                int num = (Integer) c[1];
                if (done.contains(qi) || used.contains(num)) {
                    continue;
                }
                ContentPackageQuestion q = out.get(qi);
                q.setContent(q.getContent() + "\n[图片" + num + "]");
                used.add(num);
                done.add(qi);
                assigned++;
                log.info("AI 导入任务 {} 图题归位：题号 {} 内嵌图 [图片{}]", jobId, q.getQuestionNumber(), num);
            }
            log.info("AI 导入任务 {} 图题归位：{} 题配图完成（未配图图形题由预览页人工补图）", jobId, assigned);
            return new AiImportResult(out, parsed.materials());
        } catch (Exception e) {
            log.warn("AI 导入任务 {} PDF 图题归位失败：{}", jobId, e.getMessage());
            return parsed;
        }
    }


    /** 图形题题干特征：几何图形/俯视图/展开图/问号题/图形分类等。
     *  这类题的图形由程序按版面位置确定性裁剪配图，模型不引用 [图片N]（模型数图/归属不可靠）。 */
    private static final Pattern FIGURE_STEM = Pattern.compile("图形|俯视|展开图|问号|规律性|填入|折叠|折成|截面|纸盒");

    /**
     * 空题干回填：从源文 lines[from..] 中找"题号行"（N. / N．/ N、），取题号后的引导语文字。
     * 题号行本身为空时并入下一行（引导语换行版式）；下一行是选项/下一题时放弃。
     */
    private String backfillStemLine(String[] lines, int from, int questionNumber) {
        Pattern qno = Pattern.compile("^\\s*" + questionNumber + "\\s*[.．、)）]\\s*(.*)$");
        for (int i = from; i < lines.length; i++) {
            Matcher m = qno.matcher(lines[i]);
            if (!m.find()) {
                continue;
            }
            String rest = m.group(1) == null ? "" : m.group(1).trim();
            //答案表行（如 "19.C"）或纯编号行 → 并入下一非空行；下一行是选项/下一题号 → 放弃
            boolean restRich = rest.replaceAll("[^\\p{L}\\p{N}]", "").length() >= 6;
            if (!restRich) {
                String next = "";
                for (int j = i + 1; j < lines.length && j <= i + 3; j++) {
                    String t = lines[j].trim();
                    if (t.isEmpty()) {
                        continue;
                    }
                    if (t.matches("^[A-Da-d]\\s*[.．、].*") || t.matches("^\\d{1,3}\\s*[.．、)）].*")
                            || t.contains("答案")) {
                        break;
                    }
                    next = t;
                    break;
                }
                rest = (rest + " " + next).trim();
                if (rest.replaceAll("[^\\p{L}\\p{N}]", "").length() < 6) {
                    return null;
                }
            }
            return rest;
        }
        return null;
    }

    /**
     * 视觉单次路径（实测定稿）：整页渲染（JPEG 压缩）+ 页文本层 + 简化 prompt → 单次多模态调用。
     * 模型输出位置占位符（【题干图片】【选项A图片】）而非编号引用——不依赖模型数图能力，
     * 无思考模式也可靠（实测 40 题 10 页：无思考 48s / 思考 124s，均 40/40 不丢题、占位精准）。
     * 占位符 → 内嵌图编号：按"题号 → 页 → 页内图顺序 + 每题占位符数量"确定性分配（resolvePlaceholders）；
     * 匹配失败（矢量图等无内嵌图）→ 占位符保留，预览页手动补图。
     * 校验：跳过源文回填（行首题号定位会误杀"题号+题干同行"版式）；残版/粘连过滤 + 去重择优。
     */
    private AiImportResult chatVisionSingle(AiSettings settings, List<String> pageTexts,
                                            List<DocumentParserService.ExtractedImage> extracted,
                                            String fullSource, String warning, AiImportJob job, Long jobId,
                                            boolean aiSupplement, Path jobDir, String pdfFileName) throws IOException {
        //1. 整页渲染：JPEG（压缩，发给模型）+ PNG（无损，密度/裁剪分析用——JPEG 噪点会虚高密度导致误插）
        byte[] pdfBytes = Files.readAllBytes(jobDir.resolve("0-" + pdfFileName));
        List<AiClientService.ImageData> pageImages = documentParserService.renderAllPages(
                pdfBytes, VISION_PAGE_DPI, VISION_PAGE_JPEG_QUALITY);
        List<AiClientService.ImageData> analysisImages = documentParserService.renderAllPages(pdfBytes, ANALYSIS_RENDER_DPI, 0f);
        int pages = Math.min(pageTexts.size(), pageImages.size());
        if (pageTexts.size() != pageImages.size()) {
            log.warn("AI 导入任务 {} 文本页数 {} 与渲染页数 {} 不一致，取较小值 {}", jobId, pageTexts.size(), pageImages.size(), pages);
        }
        //2. 简化 prompt（位置占位符规则）
        String systemPrompt = promptFactory.buildVisionSingleSystemPrompt(aiSupplement);
        String userPrompt = promptFactory.buildVisionSingleUserPrompt(pageTexts, pages, warning);
        //3. 单次调用（thinking 跟随用户选择：默认无思考 48s；思考开启 124s 更稳）
        AiSettings vision = promptFactory.buildVisionSettings(settings);
        log.info("AI 导入任务 {} 视觉单次：{} 页 / {} 张整页图 / {} 张内嵌图（thinking={}）",
                jobId, pages, pageImages.size(), extracted.size(), settings.getThinking());
        String out = aiClientService.chatWithImages(vision, systemPrompt, userPrompt, pageImages, true);
        //4. 解析 + 残版/粘连过滤 + 去重择优（40/40 无粘连，防御保留）
        AiImportResult parsed = parseAndValidate(out, aiSupplement, fullSource, true, true);
        List<ContentPackageQuestion> cleaned = new ArrayList<>();
        for (ContentPackageQuestion q : parsed.questions()) {
            if (!visionQualityService.isJunk(q)) {
                cleaned.add(q);
            }
        }
        Map<String, ContentPackageQuestion> byNorm = new LinkedHashMap<>();
        for (ContentPackageQuestion q : cleaned) {
            String norm = visionQualityService.normalizeQuestion(q);
            ContentPackageQuestion existing = byNorm.get(norm);
            if (existing == null || visionQualityService.quality(q) > visionQualityService.quality(existing)) {
                byNorm.put(norm, q);
            }
        }
        List<ContentPackageQuestion> questions = new ArrayList<>(byNorm.values());
        if (questions.size() != parsed.questions().size()) {
            log.info("AI 导入任务 {} 视觉单次过滤去重：{} → {} 题", jobId, parsed.questions().size(), questions.size());
        }
        //5. 占位符 → 图片（确定性区域裁剪：题号行/选项行坐标 → 渲染图裁剪 + 白边裁剪；带内位图优先）
        questions = visionQualityService.sortBySourceOrder(questions, fullSource);
        List<List<DocumentParserService.LinePos>> linePositions =
                documentParserService.collectLinePositions(Files.readAllBytes(jobDir.resolve("0-" + pdfFileName)));
        resolvePlaceholders(questions, pageTexts, extracted, analysisImages, linePositions, jobDir);
        return new AiImportResult(questions, parsed.materials());
    }

    /** 题干图块顶部距题干行最大间距（pt）：超过说明块不属于本题（相邻图形题被漏掉时的无主块），跳过防错插 */
    private static final float STEM_IMAGE_MAX_GAP_PT = 80f;
    /** 块 0 距题干行超过此值（pt）→ 块 0 判定为第一个选项图（题干下方有引导语/空白，如太师椅 4 图竖排）而非题干图 */
    private static final float STEM_IMAGE_FAR_GAP_PT = 40f;
    /** 块 0 与块 1 间距（pt）超过此值 → 块 0 判定为第一个选项图（选项全图垂直排列，如太师椅 4 图竖排）而非题干图 */
    private static final float OPTION_VERTICAL_GAP_PT = 30f;

    /**
     * 位置占位符 → 图片（[图片N]）：
     * 1. 带内位图优先：该页内嵌位图（CTM 区域）与占位带重叠 → 直接用提取的位图（清晰、精确）；
     * 2. 否则从整页渲染图按带裁剪（题干带 / 选项带），白边裁剪后落盘（矢量图也能拿到）。
     * 裁剪图写入 imports/{jobId}/images/{N}.png（编号接在内嵌图之后），confirm 时统一落盘。
     * 定位失败/裁剪异常 → 占位符保留原文（预览页手动补图）。
     */
    private void resolvePlaceholders(List<ContentPackageQuestion> questions, List<String> pageTexts,
                                     List<DocumentParserService.ExtractedImage> extracted,
                                     List<AiClientService.ImageData> pageImages,
                                     List<List<DocumentParserService.LinePos>> linePositions,
                                     Path jobDir) {
        if (questions.isEmpty() || pageImages == null || pageImages.isEmpty()) {
            return;
        }
        //渲染参数（150 DPI，与 VISION_PAGE_DPI 一致）
        float scale = ANALYSIS_RENDER_DPI / 72f;
        float pageHeightPt = 842f;
        try {
            var img0 = javax.imageio.ImageIO.read(new java.io.ByteArrayInputStream(pageImages.get(0).data()));
            if (img0 != null) {
                pageHeightPt = img0.getHeight() / scale;
            }
        } catch (Exception ignored) {
        }
        //页内位图区域（CTM 从底 → 渲染从顶）：pageNo → [yTop, yBot, xLeft, xRight, 全局编号]
        Map<Integer, List<long[]>> bitmapBoxesByPage = new HashMap<>();
        for (int i = 0; i < extracted.size(); i++) {
            DocumentParserService.ExtractedImage e = extracted.get(i);
            float yTop = (pageHeightPt - (e.sortY() + 60f)) * scale;
            float yBot = (pageHeightPt - e.sortY()) * scale;
            //图宽未知：CTM x 起，估计 200pt 宽（匹配时按 x 重叠粗筛）
            float xLeft = e.sortX() * scale - 10;
            float xRight = (e.sortX() + 200f) * scale;
            bitmapBoxesByPage.computeIfAbsent(e.pageNo(), k -> new ArrayList<>())
                    .add(new long[]{Math.round(yTop), Math.round(yBot), Math.round(xLeft), Math.round(xRight), i});
        }
        //源文行 → 页（content 定位）
        String source = String.join("", pageTexts);
        String[] lines = source.split("\\R", -1);
        int[] lineStarts = new int[lines.length];
        int acc = 0;
        for (int i = 0; i < lines.length; i++) {
            lineStarts[i] = acc;
            acc += lines[i].length() + 1;
        }
        int[] pageStarts = new int[pageTexts.size() + 1];
        acc = 0;
        for (int i = 0; i < pageTexts.size(); i++) {
            pageStarts[i] = acc;
            acc += pageTexts.get(i).length();
        }
        pageStarts[pageTexts.size()] = acc;

        int nextImageNo = extracted.size() + 1;
        //第一步：逐题定位 content 行 y（游标推进，处理共用引导语）→ 每题 (q, page, contentY)
        //游标键用 probe（前 8 字）：相同引导语的题（如多条"从所给的四个选项中…"）共享游标按出现顺序归位，
        //否则独立游标会让后出现的题定位到第一处（区间重叠 → 图分配失败）
        List<Object[]> located = new ArrayList<>(); // {ContentPackageQuestion, Integer page, Float contentY}
        Map<String, Integer> locateCursor = new HashMap<>();
        for (ContentPackageQuestion q : questions) {
            String contentProbe = stripImageRefs(q.getContent() == null ? "" : q.getContent()).split("\\R", 2)[0]
                    .replaceAll("\\s+", "");
            int page = -1;
            float contentY = -1;
            if (!contentProbe.isEmpty()) {
                String probe = contentProbe.length() > 8 ? contentProbe.substring(0, 8) : contentProbe;
                int from = locateCursor.getOrDefault(probe, 0);
                int bestLine = -1;
                for (int li = from; li < lines.length; li++) {
                    if (lines[li].replaceAll("\\s+", "").contains(probe)) {
                        bestLine = li;
                        locateCursor.put(probe, li + 1);
                        break;
                    }
                }
                if (bestLine >= 0) {
                    page = pageIndexOf(pageStarts, lineStarts[bestLine]);
                    if (page >= 0 && page < linePositions.size()) {
                        for (DocumentParserService.LinePos lp : linePositions.get(page)) {
                            if (lp.text().replaceAll("\\s+", "").contains(probe)) {
                                contentY = lp.y();
                                break;
                            }
                        }
                    }
                }
            }
            located.add(new Object[]{q, page, contentY});
        }
        //第二步：每页图形块检测（整页扫描连续非白行段 ≥ 阈值；排除页眉/页脚）
        Map<Integer, List<float[]>> blocksByPage = new HashMap<>();
        for (int p = 0; p < pageImages.size(); p++) {
            try {
                BufferedImage pageImg = javax.imageio.ImageIO.read(
                        new java.io.ByteArrayInputStream(pageImages.get(p).data()));
                if (pageImg != null) {
                    blocksByPage.put(p, detectGraphBlocks(pageImg, scale, pageHeightPt,
                            p < linePositions.size() ? linePositions.get(p) : null));
                }
            } catch (Exception ignored) {
            }
        }
        if (log.isDebugEnabled()) {
            for (Object[] loc : located) {
                ContentPackageQuestion q = (ContentPackageQuestion) loc[0];
                log.debug("resolvePlaceholders located page={} y={} probe={}", loc[1], loc[2],
                        truncate(stripImageRefs(q.getContent() == null ? "" : q.getContent()), 26));
            }
        }
        //第三步：按页分组、按 contentY 排序 → 区间 [contentY_i, contentY_{i+1}) → 块归属
        Map<Integer, List<Object[]>> byPage = new LinkedHashMap<>();
        for (Object[] loc : located) {
            int page = (Integer) loc[1];
            if (page >= 0) {
                byPage.computeIfAbsent(page, k -> new ArrayList<>()).add(loc);
            }
        }
        for (Map.Entry<Integer, List<Object[]>> entry : byPage.entrySet()) {
            int page = entry.getKey();
            List<Object[]> pageQs = entry.getValue();
            pageQs.sort((a, b) -> Float.compare((Float) a[2], (Float) b[2]));
            List<float[]> blocks = blocksByPage.getOrDefault(page, List.of());
            //下一页第一题 contentY（跨页归属用：页底题的图可能在下一页顶部）
            float nextFirstY = -1;
            List<Object[]> nextPageQs = byPage.get(page + 1);
            if (nextPageQs != null && !nextPageQs.isEmpty()) {
                float minY = Float.MAX_VALUE;
                for (Object[] nq : nextPageQs) {
                    minY = Math.min(minY, (Float) nq[2]);
                }
                nextFirstY = minY;
            }
            for (int i = 0; i < pageQs.size(); i++) {
                ContentPackageQuestion q = (ContentPackageQuestion) pageQs.get(i)[0];
                float startY = (Float) pageQs.get(i)[2];
                float endY = i + 1 < pageQs.size() ? (Float) pageQs.get(i + 1)[2] : pageHeightPt - 10;
                //该题块 = 中心在 [startY, endY) 的图形块（y 排序）；块统一为 [yTop, yBot, xLeft, xRight]
                List<float[]> qBlocks = new ArrayList<>();
                for (float[] b : blocks) {
                    float center = (b[0] + b[1]) / 2;
                    if (center >= startY && center < endY) {
                        qBlocks.add(new float[]{b[0], b[1], 40f, -1f, page});
                    }
                }
                //跨页归属：页内最后一题且题干在页底（y > 页高-200pt）→ 收集下一页顶部块
                //（块完全位于下一页第一题题干上方，如页 2 底部六图分类题的六边形簇在页 3 顶部；
                //  也可能是页底题"选项图在下一页顶部"，如 Q14 的 2x2 选项图——按空壳选项归属）
                List<float[]> crossPageBlocks = new ArrayList<>();
                if (i == pageQs.size() - 1 && startY > pageHeightPt - 200 && nextFirstY > 0) {
                    for (float[] b : blocksByPage.getOrDefault(page + 1, List.of())) {
                        if (b[1] < nextFirstY - 10) {
                            crossPageBlocks.add(new float[]{b[0], b[1], 40f, -1f, page + 1});
                        }
                    }
                }
                int shellCount = 0;
                if (q.getOptions() != null) {
                    for (OptionItem o : q.getOptions()) {
                        String key = o.key() == null ? "" : o.key().trim();
                        if (isEmptyOptionShell(o.text()) && key.matches("[A-Da-d]")) {
                            shellCount++;
                        }
                    }
                }
                //本页无块 → 跨页块归属：有空壳选项 → 全部进选项池（页底题选项图跨页，如 Q14 的 2x2 选项图）；
                //无空壳选项（选项是文字）→ 跨页块合并为题干图（六图分类题）
                if (qBlocks.isEmpty() && !crossPageBlocks.isEmpty() && shellCount == 0) {
                    qBlocks.addAll(crossPageBlocks);
                    crossPageBlocks.clear();
                    qBlocks.sort((a, b) -> Float.compare(a[0], b[0]));
                }
                //纯跨页选项场景（本页无块、有跨页块、有空壳选项）：题干图无 → 跨页块全部进选项池直接分配
                //（跨页块自带 imgPage=page+1，切分/裁剪用正确渲染页）
                if (qBlocks.isEmpty() && !crossPageBlocks.isEmpty() && shellCount > 0) {
                    nextImageNo = assignOptionPool(q, crossPageBlocks, shellCount, scale, page + 1,
                            bitmapBoxesByPage, pageImages, jobDir, nextImageNo);
                    continue;
                }
                if (qBlocks.isEmpty() || q.getContent() == null) {
                    continue;
                }
                //块 0 语义判定：
                //  - 通常块 0 = 题干图（题干下方紧贴的图形区），其余块分配给空壳选项 A-D
                //  - 但"选项全部是图"的题（如太师椅：4 张椅子图竖排，块间大间距或距题干较远）没有题干图，
                //    块 0 实际是选项 A 的图 → 块 0 也参与选项分配
                float[] stemBlock = qBlocks.get(0);
                boolean firstIsOption = qBlocks.size() > 1
                        && (qBlocks.get(1)[0] - stemBlock[1] > OPTION_VERTICAL_GAP_PT
                        || stemBlock[0] - startY > STEM_IMAGE_FAR_GAP_PT);
                //块顶部距题干行过远（>80pt）说明该块不属于本题（相邻图形题被漏掉时的无主块，
                //或纯文字题下方是别题的图形区）→ 跳过防错插
                if (stemBlock[0] - startY > STEM_IMAGE_MAX_GAP_PT) {
                    continue;
                }
                //题干图 + 选项池统一处理：
                // - 题干块可能含"题干图 + 选项图同行"（如 Q7：3D 图左侧 + 4 截面图右侧同一行）→
                //   按列间隙切分，仅当"该题只有这一个块"（无独立选项块）且切出子块数 == 空壳选项数+1 且首块较窄时。
                //   注意：题干图本身可能是多图连排（如 Q2 的问号序列 5 图一行、六边形簇 ①-⑥），
                //   若题目另有独立选项块，题干块整块作为题干图，切分只用于选项块。
                List<float[]> optionPool = new ArrayList<>();
                if (!firstIsOption) {
                    float[] stemCrop = stemBlock;
                    //题干图合并近邻块（间隙 < 30pt）：跨页连续图形区（如六边形簇 ①-⑥ + 选项图 ④⑤⑥ 连排）应合并为一张题干图。
                    //仅当无空壳选项时合并（选项是文字 = 图形区都属题干）；有空壳选项时近邻块是独立选项块，不能合并
                    if (qBlocks.size() > 1 && shellCount == 0) {
                        float end = stemBlock[1];
                        int k = 1;
                        while (k < qBlocks.size() && qBlocks.get(k)[0] - end < 30) {
                            end = Math.max(end, qBlocks.get(k)[1]);
                            k++;
                        }
                        if (k > 1) {
                            stemCrop = new float[]{stemBlock[0], end, stemBlock[2], stemBlock[3], stemBlock[4]};
                            for (int rm = k - 1; rm >= 1; rm--) {
                                qBlocks.remove(rm);
                            }
                            log.info("题干块合并近邻块：page={} → [{} - {}]", page,
                                    Math.round(stemCrop[0]), Math.round(stemCrop[1]));
                        }
                    }
                    //题干块同行切分仅当"无独立选项块"（qBlocks 只剩题干块本身）
                    if (shellCount > 0 && qBlocks.size() == 1) {
                        try {
                            BufferedImage pageImg = javax.imageio.ImageIO.read(
                                    new java.io.ByteArrayInputStream(pageImages.get((int) stemBlock[4]).data()));
                            if (pageImg != null) {
                                List<float[]> slices = sliceOptionBlockByGaps(pageImg, scale, stemBlock, shellCount + 1);
                                if (slices.size() == shellCount + 1
                                        && (slices.get(0)[3] - slices.get(0)[2]) * scale < pageImg.getWidth() * 0.35f) {
                                    //题干图 + 选项图同行 → 首块题干图，其余进选项池
                                    stemCrop = new float[]{slices.get(0)[0], slices.get(0)[1], slices.get(0)[2],
                                            slices.get(0)[3], stemBlock[4]};
                                    for (int si = 1; si < slices.size(); si++) {
                                        optionPool.add(new float[]{slices.get(si)[0], slices.get(si)[1],
                                                slices.get(si)[2], slices.get(si)[3], stemBlock[4]});
                                    }
                                    log.info("题干块同行切分：page={} block=[{}-{}] → {} 子块（题干+{} 选项）",
                                            page, Math.round(stemBlock[0]), Math.round(stemBlock[1]), slices.size(), shellCount);
                                }
                            }
                        } catch (Exception ex) {
                            log.warn("题干块切分失败 page={}：{}", page, ex.getMessage());
                        }
                    }
                    String stemNum = cropBand(stemCrop[0], stemCrop[1], stemCrop[2], stemCrop[3], true, scale,
                            (int) stemCrop[4], bitmapBoxesByPage, pageImages, jobDir, nextImageNo);
                    if (stemNum != null) {
                        log.info("题干块插入图片：page={} block=[{}-{}] num=[图片{}] content={}", page,
                                Math.round(stemCrop[0]), Math.round(stemCrop[1]), stemNum,
                                truncate(stripImageRefs(q.getContent()), 40));
                        q.setContent(q.getContent() + "\n[图片" + stemNum + "]");
                        nextImageNo = Math.max(nextImageNo, Integer.parseInt(stemNum) + 1);
                    }
                    for (int bi = 1; bi < qBlocks.size(); bi++) {
                        optionPool.add(qBlocks.get(bi));
                    }
                } else {
                    optionPool.addAll(qBlocks);
                }
                //跨页块进选项池（页底题选项图在下一页顶部；题干图无壳时已在上面并入 qBlocks）
                optionPool.addAll(crossPageBlocks);
                //选项池切分补充 + 分配（空壳选项 A-D 按视觉顺序；块自带 imgPage）
                nextImageNo = assignOptionPool(q, optionPool, shellCount, scale, page,
                        bitmapBoxesByPage, pageImages, jobDir, nextImageNo);
            }
        }
    }

    /**
     * 选项池切分补充 + 分配：空壳选项 A-D 按视觉顺序取块；池不足时逐块做列间隙切分
     * （同行 4 图并排 / 2x2 选项图只检测成一块或两块）。块为 5 元素 [yTop,yBot,xLeft,xRight,imgPage]
     * （跨页块 imgPage = page+1）。返回更新后的 nextImageNo。
     */
    private int assignOptionPool(ContentPackageQuestion q, List<float[]> optionPool, int shellCount,
                                 float scale, int fallbackPage, Map<Integer, List<long[]>> bitmapBoxesByPage,
                                 List<AiClientService.ImageData> pageImages, Path jobDir, int nextImageNo) {
        if (q.getOptions() == null || shellCount <= 0 || optionPool.isEmpty()) {
            return nextImageNo;
        }
        //选项池不足时：逐块做列间隙切分补充（同行 4 图并排 / 2x2 选项图只检测成一块或两块）
        if (shellCount > optionPool.size()) {
            try {
                int guard = 0;
                while (shellCount > optionPool.size() && guard++ < 8) {
                    //选最宽（或 x 全宽）的块切分
                    int widestIdx = -1;
                    float widestW = -1;
                    for (int pi = 0; pi < optionPool.size(); pi++) {
                        float[] b = optionPool.get(pi);
                        float bw = b[3] < 0 ? Float.MAX_VALUE : (b[3] - b[2]);
                        if (bw > widestW) {
                            widestW = bw;
                            widestIdx = pi;
                        }
                    }
                    if (widestIdx < 0) {
                        break;
                    }
                    float[] widest = optionPool.get(widestIdx);
                    BufferedImage pageImg = javax.imageio.ImageIO.read(
                            new java.io.ByteArrayInputStream(pageImages.get((int) widest[4]).data()));
                    if (pageImg == null) {
                        break;
                    }
                    List<float[]> sliced = sliceOptionBlockByGaps(pageImg, scale, widest, shellCount);
                    if (sliced.size() <= 1) {
                        break;
                    }
                    optionPool.remove(widestIdx);
                    for (float[] s : sliced) {
                        optionPool.add(widestIdx, new float[]{s[0], s[1], s[2], s[3], widest[4]});
                        widestIdx++;
                    }
                }
                if (shellCount <= optionPool.size()) {
                    log.info("选项切分完成：{} 份（列间隙分析）", optionPool.size());
                }
            } catch (Exception ex) {
                log.warn("选项列间隙切分失败：{}", ex.getMessage());
            }
        }
        //选项分配（空壳选项 A-D 按视觉顺序）
        log.info("选项分配：content={} pool={} shell={}", truncate(stripImageRefs(q.getContent()), 25),
                optionPool.size(), shellCount);
        List<OptionItem> fixed = new ArrayList<>();
        int poolIdx = 0;
        for (OptionItem o : q.getOptions()) {
            String key = o.key() == null ? "" : o.key().trim();
            if (isEmptyOptionShell(o.text()) && key.matches("[A-Da-d]")) {
                if (poolIdx < optionPool.size()) {
                    float[] optBlock = optionPool.get(poolIdx);
                    String num = cropBand(optBlock[0], optBlock[1], optBlock[2], optBlock[3], false, scale,
                            (int) optBlock[4], bitmapBoxesByPage, pageImages, jobDir, nextImageNo);
                    if (num != null) {
                        fixed.add(new OptionItem(o.key(), "[图片" + num + "]"));
                        nextImageNo = Math.max(nextImageNo, Integer.parseInt(num) + 1);
                        poolIdx++;
                        continue;
                    }
                }
                fixed.add(o);
            } else {
                fixed.add(o);
            }
        }
        q.setOptions(fixed);
        return nextImageNo;
    }

    /**
     * 同行选项图 x 切分（渲染图列间隙分析）：块内统计每列非白像素数，
     * 列非白 < 带高 15% 且连续 ≥4px 的列为"间隙"，用间隙中点切分块 → 子块 [yTop,yBot,xLeft,xRight]。
     * 注意：不能用 PDF 文本层的选项字母 x（实测 TextPosition 坐标与渲染位置存在矩阵变换偏移）。
     */
    private List<float[]> sliceOptionBlockByGaps(BufferedImage pageImg, float scale, float[] block, int maxParts) {
        int yTop = Math.round(block[0] * scale);
        int yBot = Math.min(Math.round(block[1] * scale), pageImg.getHeight());
        int w = pageImg.getWidth();
        int h = yBot - yTop;
        if (h <= 0 || w <= 0) {
            return List.of();
        }
        int[] colCount = new int[w];
        for (int y = yTop; y < yBot; y++) {
            for (int x = 0; x < w; x++) {
                int rgb = pageImg.getRGB(x, y);
                int r = (rgb >> 16) & 0xFF, g = (rgb >> 8) & 0xFF, b = rgb & 0xFF;
                if (r < 240 || g < 240 || b < 240) {
                    colCount[x]++;
                }
            }
        }
        //间隙段：列非白数 < 带高 15%，连续 ≥ 4px；合并相邻间隙（中间非间隙段 < 8px——图间装饰线打断的间隙）
        List<int[]> gaps = new ArrayList<>();
        boolean inGap = false;
        int gs = 0;
        int threshold = Math.max(2, (int) (h * 0.15));
        for (int x = 0; x < w; x++) {
            boolean gap = colCount[x] < threshold;
            if (gap && !inGap) {
                inGap = true;
                gs = x;
            } else if (!gap && inGap) {
                if (x - gs >= 4) {
                    gaps.add(new int[]{gs, x - 1});
                }
                inGap = false;
            }
        }
        if (inGap && w - gs >= 4) {
            gaps.add(new int[]{gs, w - 1});
        }
        List<int[]> mergedGaps = new ArrayList<>();
        for (int[] g : gaps) {
            if (!mergedGaps.isEmpty() && g[0] - mergedGaps.get(mergedGaps.size() - 1)[1] < 8) {
                mergedGaps.get(mergedGaps.size() - 1)[1] = g[1];
            } else {
                mergedGaps.add(new int[]{g[0], g[1]});
            }
        }
        //内容区 = 去掉左右边缘空白间隙；仅内部间隙（两侧都有内容）参与切分
        int contentL = 0;
        if (!mergedGaps.isEmpty() && mergedGaps.get(0)[0] <= 0) {
            contentL = mergedGaps.get(0)[1] + 1;
        }
        int contentR = w - 1;
        if (!mergedGaps.isEmpty() && mergedGaps.get(mergedGaps.size() - 1)[1] >= w - 1) {
            contentR = mergedGaps.get(mergedGaps.size() - 1)[0] - 1;
        }
        if (contentR - contentL < 40) {
            return List.of();
        }
        //间隙中点 → 子块边界
        List<Integer> bounds = new ArrayList<>();
        bounds.add(contentL);
        for (int[] g : mergedGaps) {
            if (g[0] > contentL && g[1] < contentR) {
                bounds.add((g[0] + g[1]) / 2);
            }
        }
        bounds.add(contentR);
        List<float[]> out = new ArrayList<>();
        for (int i = 0; i + 1 < bounds.size() && out.size() < maxParts; i++) {
            int x1 = bounds.get(i);
            int x2 = bounds.get(i + 1);
            if (x2 - x1 >= 40) {
                out.add(new float[]{block[0], block[1], x1 / scale, x2 / scale});
            }
        }
        return out;
    }

    /**
     * 整页图形块检测：扫描渲染图，找"连续非白行段"（y 步长 2，x 全采样）≥ 阈值 的段。
     * 文字行段 ~12px（行间空白分隔），图形块 60-120px；排除页眉（y<70pt）页脚（y>页高-50pt）。
     * 文字行覆盖的 y（LinePos 坐标 ± 缓冲）直接跳过——防止"密集文字段落"（题干+选项连排、
     * 行间空隙 < 采样步长）被误判成图形块；图形内部的文字标签（①②③/A/B/C/D）被跳过不影响图形主体。
     * 返回块列表 [yTopPt, yBotPt]（按 y 升序）。
     */
    private List<float[]> detectGraphBlocks(BufferedImage pageImg, float scale, float pageHeightPt,
                                            List<DocumentParserService.LinePos> textLines) {
        //文字行覆盖区间（pt，从顶）：行高 + 上下缓冲（行距 ~7.7pt，缓冲取 ±3pt 防相邻行区间重叠吞掉图形）
        float[][] textRanges = null;
        if (textLines != null && !textLines.isEmpty()) {
            List<float[]> ranges = new ArrayList<>();
            for (DocumentParserService.LinePos lp : textLines) {
                float top = lp.y() - 3f;
                float bot = lp.y() + lp.height() + 3f;
                if (bot > top) {
                    ranges.add(new float[]{top, bot});
                }
            }
            ranges.sort((a, b) -> Float.compare(a[0], b[0]));
            //合并重叠区间（相邻行行距 < 缓冲时连成一段）
            List<float[]> mergedRanges = new ArrayList<>();
            for (float[] r : ranges) {
                if (!mergedRanges.isEmpty() && r[0] - mergedRanges.get(mergedRanges.size() - 1)[1] < 2) {
                    float[] last = mergedRanges.get(mergedRanges.size() - 1);
                    last[1] = Math.max(last[1], r[1]);
                } else {
                    mergedRanges.add(new float[]{r[0], r[1]});
                }
            }
            textRanges = mergedRanges.toArray(new float[0][]);
        }
        List<float[]> blocks = new ArrayList<>();
        int run = 0, runStart = 0;
        for (int y = 0; y < pageImg.getHeight(); y += 2) {
            float yPt = y / scale;
            if (textRanges != null && inTextRange(textRanges, yPt)) {
                //文字行覆盖：视为空白（不参与 run，也断开 run）
                if (run * 2 >= IMAGE_MIN_RUN_PX) {
                    blocks.add(new float[]{runStart / scale, y / scale});
                }
                run = 0;
                continue;
            }
            boolean has = false;
            for (int x = 0; x < pageImg.getWidth(); x++) {
                int rgb = pageImg.getRGB(x, y);
                int r = (rgb >> 16) & 0xFF, g = (rgb >> 8) & 0xFF, b = rgb & 0xFF;
                if (r < 220 || g < 220 || b < 220) {
                    has = true;
                    break;
                }
            }
            if (has) {
                if (run == 0) {
                    runStart = y;
                }
                run++;
            } else {
                if (run * 2 >= IMAGE_MIN_RUN_PX) {
                    blocks.add(new float[]{runStart / scale, (y) / scale});
                }
                run = 0;
            }
        }
        if (run * 2 >= IMAGE_MIN_RUN_PX) {
            blocks.add(new float[]{runStart / scale, pageImg.getHeight() / scale});
        }
        //排除页眉/页脚块
        List<float[]> filtered = new ArrayList<>();
        for (float[] b : blocks) {
            if (b[1] > 70 && b[0] < pageHeightPt - 50) {
                filtered.add(b);
            }
        }
        //合并相邻块（间隙 < 12pt：图形块间的小空隙）
        List<float[]> merged = new ArrayList<>();
        for (float[] b : filtered) {
            if (!merged.isEmpty() && b[0] - merged.get(merged.size() - 1)[1] < 12) {
                float[] last = merged.get(merged.size() - 1);
                last[1] = Math.max(last[1], b[1]);
            } else {
                merged.add(new float[]{b[0], b[1]});
            }
        }
        return merged;
    }

    /** yPt 是否落在任一文字行覆盖区间内 */
    private boolean inTextRange(float[][] textRanges, float yPt) {
        //区间按 y 升序，二分查找
        int lo = 0, hi = textRanges.length - 1;
        while (lo <= hi) {
            int mid = (lo + hi) >>> 1;
            if (yPt < textRanges[mid][0]) {
                hi = mid - 1;
            } else if (yPt > textRanges[mid][1]) {
                lo = mid + 1;
            } else {
                return true;
            }
        }
        return false;
    }

    /** 图形块占用键（同页多题共用相邻簇时，已配给前题的块不重复认领） */
    private static String blockKey(float[] b) {
        return String.format("%.0f_%.0f", b[0], b[1]);
    }

    /** 选项图组网格拆分：x/y 暗像素投影聚类出网格单元（行优先序），1x4 / 2x2 排版通用。
     *  返回每个单元 {top, bot, left, right}（pt）；聚类不足 2 单元时返回空（调用方退化为 2x2 四分）。 */
    private List<float[]> detectGridCells(BufferedImage img, float scale, float topPt, float botPt,
                                          float leftPt, float rightPt, int need) {
        int x0 = Math.max(0, Math.round(leftPt * scale));
        int x1 = Math.min(img.getWidth(), Math.round(rightPt * scale));
        int y0 = Math.max(0, Math.round(topPt * scale));
        int y1 = Math.min(img.getHeight(), Math.round(botPt * scale));
        if (x1 - x0 < 30 || y1 - y0 < 24) {
            return List.of();
        }
        //多档间隙阈值尝试（图间空隙可能小于整页 1/40，逐档收紧直到格数够用）
        List<int[]> bestCols = null;
        List<int[]> bestRows = null;
        int bestCells = 0;
        for (int denom : new int[]{20, 40, 80, 160, 320}) {
            int gap = Math.max(4, (x1 - x0) / denom);
            List<int[]> cols = clusterProjection(img, x0, x1, y0, y1, true, gap);
            List<int[]> rows = clusterProjection(img, y0, y1, x0, x1, false, gap);
            if (cols.isEmpty() || rows.isEmpty()) {
                continue;
            }
            int cells = Math.min(cols.size() * rows.size(), need);
            if (cells > bestCells) {
                bestCells = cells;
                bestCols = cols;
                bestRows = rows;
            }
            if (cells >= need) {
                break; // 已够用
            }
        }
        if (bestCols == null || bestRows == null || bestCells < 2) {
            return List.of();
        }
        //聚类不足 need：图与图紧贴（无白色间隙）时投影只出 r×c 格 → 把较宽维度均分补足
        //（如 1×4 排版的图两两紧贴 → 2 列各均分成 2 → 4 格，行优先序仍为 A B C D）
        int r = bestRows.size();
        int c = bestCols.size();
        if (r * c < need) {
            int factor = Math.max(2, (int) Math.ceil((double) need / (r * c)));
            if (c >= r) {
                List<int[]> finer = new ArrayList<>();
                for (int[] col : bestCols) {
                    int w = col[1] - col[0];
                    for (int k = 0; k < factor; k++) {
                        finer.add(new int[]{col[0] + w * k / factor, col[0] + w * (k + 1) / factor});
                    }
                }
                bestCols = finer;
            } else {
                List<int[]> finer = new ArrayList<>();
                for (int[] row : bestRows) {
                    int h = row[1] - row[0];
                    for (int k = 0; k < factor; k++) {
                        finer.add(new int[]{row[0] + h * k / factor, row[0] + h * (k + 1) / factor});
                    }
                }
                bestRows = finer;
            }
        }
        List<float[]> cells = new ArrayList<>();
        for (int[] row : bestRows) {
            for (int[] col : bestCols) {
                cells.add(new float[]{row[0] / scale, row[1] / scale, col[0] / scale, col[1] / scale});
                if (cells.size() >= need) {
                    return cells;
                }
            }
        }
        return cells;
    }

    /** 沿 a 轴的暗像素投影聚类：axisX=true 聚类 x 列（每列在 [b0,b1] 内是否有暗像素），否则聚类 y 行。
     *  合并窄间隙（图形内部笔画空隙），保留大间隙（图与图之间），过滤过窄噪声单元并外扩 2px 白边。 */
    private List<int[]> clusterProjection(BufferedImage img, int a0, int a1, int b0, int b1, boolean axisX,
                                          int gapThreshold) {
        List<int[]> runs = new ArrayList<>();
        int runStart = -1, runEnd = -1;
        for (int a = a0; a < a1; a++) {
            boolean dark = false;
            for (int b = b0; b < b1; b++) {
                int rgb = axisX ? img.getRGB(a, b) : img.getRGB(b, a);
                int r = (rgb >> 16) & 0xFF, g = (rgb >> 8) & 0xFF, bl = rgb & 0xFF;
                if (r < 220 || g < 220 || bl < 220) {
                    dark = true;
                    break;
                }
            }
            if (dark) {
                if (runStart < 0) {
                    runStart = a;
                }
                runEnd = a;
            } else if (runStart >= 0) {
                runs.add(new int[]{runStart, runEnd + 1});
                runStart = -1;
            }
        }
        if (runStart >= 0) {
            runs.add(new int[]{runStart, runEnd + 1});
        }
        List<int[]> merged = new ArrayList<>();
        for (int[] run : runs) {
            if (!merged.isEmpty() && run[0] - merged.get(merged.size() - 1)[1] < gapThreshold) {
                int[] last = merged.get(merged.size() - 1);
                last[1] = Math.max(last[1], run[1]);
            } else {
                merged.add(new int[]{run[0], run[1]});
            }
        }
        int minWidth = Math.max(10, (a1 - a0) / 60);
        List<int[]> out = new ArrayList<>();
        for (int[] run : merged) {
            if (run[1] - run[0] >= minWidth) {
                out.add(new int[]{Math.max(a0, run[0] - 2), Math.min(a1, run[1] + 2)});
            }
        }
        return out;
    }

    /** 带内取图：位图重叠（allowBitmap）或裁剪（块已确认有图，直接裁剪 + 白边） */
    private String cropBand(float topPt, float botPt, float leftPt, float rightPt, boolean allowBitmap,
                            float scale, int page, Map<Integer, List<long[]>> bitmapBoxesByPage,
                            List<AiClientService.ImageData> pageImages, Path jobDir, int nextImageNo) {
        if (page < 0 || page >= pageImages.size() || botPt <= topPt) {
            return null;
        }
        int yTop = Math.max(0, Math.round(topPt * scale));
        int yBot = Math.round(botPt * scale);
        int xLeft = Math.max(0, Math.round(leftPt * scale));
        int xRight = rightPt < 0 ? Integer.MAX_VALUE : Math.round(rightPt * scale);
        //位图优先（独立块场景：一行一图 y 不重叠）
        if (allowBitmap) {
            List<long[]> boxes = bitmapBoxesByPage.get(page);
            if (boxes != null) {
                long[] best = null;
                long bestOverlap = -1;
                for (long[] b : boxes) {
                    long overlapY = Math.min(b[1], yBot) - Math.max(b[0], yTop);
                    long overlapX = Math.min(b[3], xRight) - Math.max(b[2], xLeft);
                    if (overlapY > 0 && overlapX > 0 && overlapY > bestOverlap) {
                        best = b;
                        bestOverlap = overlapY;
                    }
                }
                if (best != null) {
                    return String.valueOf((int) best[4] + 1);
                }
            }
        }
        //裁剪 + 白边
        try {
            BufferedImage pageImg = javax.imageio.ImageIO.read(
                    new java.io.ByteArrayInputStream(pageImages.get(page).data()));
            if (pageImg == null) {
                return null;
            }
            yBot = Math.min(yBot, pageImg.getHeight());
            int x1 = Math.min(xLeft, pageImg.getWidth() - 1);
            int x2 = Math.min(xRight == Integer.MAX_VALUE ? pageImg.getWidth() : xRight, pageImg.getWidth());
            int w = x2 - x1 - 10;
            if (yBot <= yTop || w <= 0) {
                return null;
            }
            BufferedImage trimmed = trimWhite(pageImg.getSubimage(x1, yTop, w, yBot - yTop));
            if (trimmed == null) {
                return null;
            }
            byte[] png = toPngBytes(trimmed);
            java.nio.file.Files.write(jobDir.resolve("images").resolve(nextImageNo + ".png"), png);
            return String.valueOf(nextImageNo);
        } catch (Exception e) {
            log.warn("块裁剪失败 page={} block=[{}-{}]：{}", page, topPt, botPt, e.getMessage());
            return null;
        }
    }

    /** 空壳选项：模型输出 "A." / "A" / 空（无实际内容，可被图片替换） */
    private boolean isEmptyOptionShell(String text) {
        if (text == null) {
            return true;
        }
        String t = text.trim();
        return t.isEmpty() || t.matches("[A-Da-d][.．、]?");
    }


    /**
     * 带内图片检测（后端版式分析，不依赖模型位置判断）：
     * 1. 位图重叠（allowBitmap 时）→ 返回位图编号；
     * 2. 否则"最长连续非白行段" ≥ 阈值 → 渲染图裁剪 + 白边裁剪落盘 → 新编号；
     *    （形态指标：文字行段 ~12px，图形块 ~60-120px——密度阈值无法区分"多行文字"与"图形"）
     * 3. 否则 null（无图）。
     */
    private String detectImageInBand(float topPt, float botPt, float leftPt, float rightPt, boolean allowBitmap,
                                     float scale, int page, Map<Integer, List<long[]>> bitmapBoxesByPage,
                                     List<AiClientService.ImageData> pageImages, Path jobDir,
                                     int nextImageNo, float densityThreshold, boolean useCoverage) {
        if (page < 0 || page >= pageImages.size()) {
            return null;
        }
        int yTop = Math.max(0, Math.round(topPt * scale));
        int yBot = Math.round(botPt * scale);
        int xLeft = Math.max(0, Math.round(leftPt * scale));
        int xRight = rightPt < 0 ? Integer.MAX_VALUE : Math.round(rightPt * scale);
        //1. 位图优先（仅独立选项行/题干带：一行一图 y 不重叠）
        if (allowBitmap) {
            List<long[]> boxes = bitmapBoxesByPage.get(page);
            if (boxes != null) {
                long[] best = null;
                long bestOverlap = -1;
                for (long[] b : boxes) {
                    long overlapY = Math.min(b[1], yBot) - Math.max(b[0], yTop);
                    long overlapX = Math.min(b[3], xRight) - Math.max(b[2], xLeft);
                    if (overlapY > 0 && overlapX > 0 && overlapY > bestOverlap) {
                        best = b;
                        bestOverlap = overlapY;
                    }
                }
                if (best != null) {
                    return String.valueOf((int) best[4] + 1);
                }
            }
        }
        //2. 渲染图形态检测 + 裁剪
        try {
            BufferedImage pageImg = javax.imageio.ImageIO.read(
                    new java.io.ByteArrayInputStream(pageImages.get(page).data()));
            if (pageImg == null) {
                return null;
            }
            yBot = Math.min(yBot, pageImg.getHeight());
            int x1 = Math.min(xLeft, pageImg.getWidth() - 1);
            int x2 = Math.min(xRight == Integer.MAX_VALUE ? pageImg.getWidth() : xRight, pageImg.getWidth());
            int w = x2 - x1 - 10;
            if (yBot <= yTop || w <= 0) {
                return null;
            }
            BufferedImage band = pageImg.getSubimage(x1, yTop, w, yBot - yTop);
            //最长连续非白行段（px）：文字行 ~12px，图形块 ~60-120px
            int run = maxNonWhiteRun(band, 220);
            log.info("带内检测 page={} band=[{}-{}]pt run={}px x=[{}-{}]px", page,
                    Math.round(topPt), Math.round(botPt), run, x1, x2);
            if (run < IMAGE_MIN_RUN_PX) {
                return null;
            }
            BufferedImage trimmed = trimWhite(band);
            if (trimmed == null) {
                return null;
            }
            int no = nextImageNo;
            byte[] png = toPngBytes(trimmed);
            java.nio.file.Files.write(jobDir.resolve("images").resolve(no + ".png"), png);
            return String.valueOf(no);
        } catch (Exception e) {
            log.warn("带内图片检测失败 page={} band=[{}-{}]：{}", page, topPt, botPt, e.getMessage());
            return null;
        }
    }

    /** 图形块最小连续非白行段（渲染像素；150DPI 下文字行 ~12px、图形块 60-120px） */
    private static final int IMAGE_MIN_RUN_PX = 36;

    /** 最长连续"有非白像素的行"段（px）；x 全采样（细线条图形 1-3px，大步长会漏检） */
    private int maxNonWhiteRun(BufferedImage img, int threshold) {
        int run = 0, maxRun = 0;
        for (int y = 0; y < img.getHeight(); y += 2) {
            boolean has = false;
            for (int x = 0; x < img.getWidth(); x++) {
                int rgb = img.getRGB(x, y);
                int r = (rgb >> 16) & 0xFF, g = (rgb >> 8) & 0xFF, b = rgb & 0xFF;
                if (r < threshold || g < threshold || b < threshold) {
                    has = true;
                    break;
                }
            }
            if (has) {
                run++;
            } else {
                if (run > maxRun) {
                    maxRun = run;
                }
                run = 0;
            }
        }
        if (run > maxRun) {
            maxRun = run;
        }
        return maxRun * 2;
    }

    /** 题号行判定（行坐标用）：孤立题号行（"11."）、行首题号（"11. 题干…"）或行尾题号（"宜居带：液态水13."）；排除小数/答案行 */
    private boolean isNumberLine(String text) {
        if (text == null) {
            return false;
        }
        String t = text.trim();
        if (t.matches("^\\d{1,3}\\s*[.．、)）]\\s*$")) {
            return true;
        }
        if (t.matches("^\\d{1,3}\\s*[.．、)）].*") && !isDecimalLikeLine(t)
                && !ANSWER_LINE.matcher(t).matches()) {
            return true;
        }
        //行尾题号："宜居带：液态水13." / "…规律性：14."（排除小数结尾 "…15.8" 与答案行 "1.B"）
        if (ANSWER_LINE.matcher(t).matches()) {
            return false;
        }
        return t.matches(".*\\d{1,2}\\s*[.．、)）]\\s*$");
    }

    /** 白边裁剪：按非白像素边界框裁剪（容忍带定位偏差，去除空白边缘） */
    private BufferedImage trimWhite(BufferedImage img) {
        int w = img.getWidth(), h = img.getHeight();
        int minX = w, minY = h, maxX = -1, maxY = -1;
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                int rgb = img.getRGB(x, y);
                int r = (rgb >> 16) & 0xFF, g = (rgb >> 8) & 0xFF, b = rgb & 0xFF;
                if (r < 250 || g < 250 || b < 250) {
                    if (x < minX) minX = x;
                    if (x > maxX) maxX = x;
                    if (y < minY) minY = y;
                    if (y > maxY) maxY = y;
                }
            }
        }
        if (maxX < 0) {
            return null; //全白
        }
        int bw = maxX - minX + 1, bh = maxY - minY + 1;
        if (bw < 4 || bh < 4) {
            return null; //太小（噪声）
        }
        return img.getSubimage(minX, minY, bw, bh);
    }

    private byte[] toPngBytes(BufferedImage img) throws java.io.IOException {
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        javax.imageio.ImageIO.write(img, "png", out);
        return out.toByteArray();
    }

    // ==================== 视觉分页路径（思考模式 + 单文件 PDF 有文本层） ====================

    /**
     * 视觉分页路径：整页渲染图（版式真相——题号位置/图形归属/跨页边界）+ 页文本层（文字精确）
     * + 内嵌图（[图片N] 资源引用）。针对"题号与题干同行 / 图形题内容在图片 / 两栏排版文本流错乱"
     * 等复杂版式：题号归位靠模型视觉理解版式，文字由文本层兜底（防 OCR 误差），图片资源由内嵌图编号带出。
     *
     * 分块：每块 VISION_CHUNK_PAGES 页、相邻块重叠 VISION_OVERLAP_PAGES 页（跨页题在下一块完整出现，
     * 合并时按题干去重保留先出现）。每块一次多模态调用（块内整页图按页序不编号 + 块内内嵌图按全局编号）。
     * 答案证据校验 / 题干回填 / 材料识别复用 parseAndValidate（sourceText = 全文文本）。
     * 不做正则题号补漏（fillMissingQuestions 对同行题号失效），重叠块 + 差异检测兜底。
     */
    private AiImportResult chatVisionPages(AiSettings settings, String systemPrompt,
                                           List<String> pageTexts, List<DocumentParserService.ExtractedImage> extracted,
                                           String fullSource, String warning, AiImportJob job, Long jobId,
                                           boolean aiSupplement, Path jobDir, String pdfFileName) throws IOException {
        //1. 整页渲染（页序 = 全局页号 0..n-1；渲染与扫描件路径同一参数）
        List<AiClientService.ImageData> pageImages = documentParserService.renderAllPages(
                Files.readAllBytes(jobDir.resolve("0-" + pdfFileName)), VISION_PAGE_DPI);
        int pages = Math.min(pageTexts.size(), pageImages.size());
        if (pageTexts.size() != pageImages.size()) {
            log.warn("AI 导入任务 {} 文本页数 {} 与渲染页数 {} 不一致，取较小值 {}", jobId, pageTexts.size(), pageImages.size(), pages);
        }
        //2. 分块：[0..3], [3..6], [6..9]...（重叠页保证跨页题在下一块完整出现）
        List<int[]> blocks = new ArrayList<>();
        int step = VISION_CHUNK_PAGES - VISION_OVERLAP_PAGES;
        for (int start = 0; start < pages; start += step) {
            int end = Math.min(start + VISION_CHUNK_PAGES, pages);
            blocks.add(new int[]{start, end});
            if (end == pages) {
                break;
            }
        }
        //3. 构建每块 prompt 与图片列表（块内整页图按页序 + 块内内嵌图按全局编号 [图片N]）
        AiSettings vision = promptFactory.buildVisionSettings(settings);
        List<String> prompts = new ArrayList<>();
        List<List<AiClientService.ImageData>> blockImages = new ArrayList<>();
        List<int[]> blockNumRanges = new ArrayList<>(); //{firstNum, lastNum}（无内嵌图 = {0,0}）
        for (int[] block : blocks) {
            int start = block[0], end = block[1];
            String prompt = promptFactory.buildVisionPagePrompt(pageTexts, start, end, pages, warning);
            List<AiClientService.ImageData> imgs = new ArrayList<>(pageImages.subList(start, end));
            int firstNum = 0, lastNum = 0;
            for (int idx = 0; idx < extracted.size(); idx++) {
                DocumentParserService.ExtractedImage e = extracted.get(idx);
                if (e.pageNo() >= start && e.pageNo() < end) {
                    if (firstNum == 0) {
                        firstNum = idx + 1;
                    }
                    lastNum = idx + 1;
                    imgs.add(e.image());
                }
            }
            if (firstNum > 0) {
                prompt += promptFactory.buildVisionImageRefRule(firstNum, lastNum);
            }
            prompts.add(prompt);
            blockImages.add(imgs);
            blockNumRanges.add(new int[]{firstNum, lastNum});
        }
        //4. 并行调用（每块一次多模态；失败/劣化块重试一次，与分块路径一致）
        log.info("AI 导入任务 {} 视觉分页：{} 页 / {} 块（思考模式）", jobId, pages, blocks.size());
        List<Future<String>> futures = new ArrayList<>();
        for (int i = 0; i < prompts.size(); i++) {
            final int idx = i;
            futures.add(aiChunkExecutor.submit(() ->
                    aiClientService.chatWithImages(vision, systemPrompt, prompts.get(idx), blockImages.get(idx), true)));
        }
        List<ContentPackageQuestion> all = new ArrayList<>();
        List<ContentPackageMaterial> allMaterials = new ArrayList<>();
        Set<String> matKeys = new HashSet<>();
        int done = 0;
        for (int i = 0; i < futures.size(); i++) {
            if (isCanceled(jobId)) {
                cancelFutures(futures, i);
                return new AiImportResult(all, allMaterials);
            }
            AiImportResult parsed = new AiImportResult(List.of(), List.of());
            try {
                String out = futures.get(i).get(6, TimeUnit.MINUTES);
                parsed = parseAndValidate(out, aiSupplement, fullSource, true, true);
            } catch (CancellationException e) {
                return new AiImportResult(all, allMaterials);
            } catch (Exception e) {
                //块调用失败（网络/限流/非法 JSON）→ 不整体失败，走重试
                log.warn("AI 导入任务 {} 第 {}/{} 块（视觉）调用失败（{}），自动重试该块", jobId, i + 1, futures.size(), e.getMessage());
            }
            if (parsed.questions().size() < 5 && !isCanceled(jobId)) {
                try {
                    String retry = aiClientService.chatWithImages(vision, systemPrompt, prompts.get(i), blockImages.get(i), true);
                    AiImportResult retryParsed = parseAndValidate(retry, aiSupplement, fullSource, true, true);
                    if (retryParsed.questions().size() > parsed.questions().size()) {
                        parsed = retryParsed;
                    }
                } catch (Exception e) {
                    log.warn("AI 导入任务 {} 第 {}/{} 块（视觉）重试失败，跳过该块", jobId, i + 1, futures.size());
                }
            }
            log.info("AI 导入任务 {} 第 {}/{} 块（视觉）：解析 {} 题", jobId, i + 1, futures.size(), parsed.questions().size());
            //越界图片编号清除（模型偶发引用本块范围外的 [图片N] → 防确认导入时错图）
            int[] range = blockNumRanges.get(i);
            visionQualityService.sanitizeImageReferences(parsed.questions(), range[0], range[1]);
            all.addAll(parsed.questions());
            for (ContentPackageMaterial m : parsed.materials()) {
                if (m.getMaterialKey() != null && !m.getMaterialKey().isBlank() && matKeys.add(m.getMaterialKey())) {
                    allMaterials.add(m);
                }
            }
            done++;
            updateStage(job, "PROCESSING", "AI_GENERATING", 40 + 40 * done / futures.size());
            aiJobEventService.publish(jobId, getJob(jobId));
        }
        //5. 残版/粘连过滤：视觉路径模型偶发输出残版（选项全同/全空）或粘连题（题号与下一题题干混合）
        //   ——坏题直接丢弃，不靠 quality 择优保留（块重叠会输出干净版；全部劣化时题缺失由差异检测提示）
        List<ContentPackageQuestion> cleaned = new ArrayList<>();
        for (ContentPackageQuestion q : all) {
            if (visionQualityService.isJunk(q)) {
                continue;
            }
            cleaned.add(q);
        }
        //6. 去重：重叠块可能重复产出同一题（题干+选项规范化后一致）；同题多版本优先保留质量高的
        //   （块边界残版选项少/无答案，完整版选项全/有答案；另防同题干不同选项的题误删，如两题题干同为
        //   "把下面的六个图形分为两类…"但选项不同）
        Map<String, ContentPackageQuestion> byNorm = new LinkedHashMap<>();
        for (ContentPackageQuestion q : cleaned) {
            String norm = visionQualityService.normalizeQuestion(q);
            ContentPackageQuestion existing = byNorm.get(norm);
            if (existing == null || visionQualityService.quality(q) > visionQualityService.quality(existing)) {
                byNorm.put(norm, q);
            }
        }
        List<ContentPackageQuestion> unique = new ArrayList<>(byNorm.values());
        if (unique.size() != all.size()) {
            log.info("AI 导入任务 {} 视觉过滤去重：{} → {} 题（丢弃残版/粘连 {} 题）", jobId, all.size(), unique.size(), all.size() - cleaned.size());
        }
        if (unique.isEmpty()) {
            //兜底：视觉分块全部失败 → 回退单次多模态（全页图 + 全文 + 全内嵌图）
            log.warn("AI 导入任务 {} 视觉分块结果为空，回退单次多模态调用", jobId);
            String user = promptFactory.buildUserPrompt(fullSource, warning);
            if (!extracted.isEmpty()) {
                user += promptFactory.buildImageRefRule(1, extracted.size());
                List<AiClientService.ImageData> allImgs = new ArrayList<>(pageImages);
                for (DocumentParserService.ExtractedImage e : extracted) {
                    allImgs.add(e.image());
                }
                String out = aiClientService.chatWithImages(vision, systemPrompt, user, allImgs, true);
                return parseAndValidate(out, aiSupplement, fullSource, true, true);
            }
            String out = aiClientService.chat(vision, systemPrompt, user, true);
            return parseAndValidate(out, aiSupplement, fullSource, true, true);
        }
        //7. 页级视觉补漏：图形推理等"内容全在图片"的题模型偶发跳过 → 对比每页参考题数（行首+行尾题号）
        //   与实际输出题数（content 定位源文行 → 页），缺题的页单独补一次视觉调用（该页图 + 文本）
        unique = visionMissingPageService.fillMissingPages(unique, allMaterials, matKeys, pageTexts, pageImages, extracted,
                fullSource, warning, vision, systemPrompt, aiSupplement, jobId, () -> isCanceled(jobId));
        //8. 按源文位置排序（还原文档顺序；补漏题归位，不再追加在末尾）
        unique = visionQualityService.sortBySourceOrder(unique, fullSource);
        return new AiImportResult(unique, allMaterials);
    }


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

    /** 第一个章节标题行（"一、选择题…"）；找不到返回 0。说明区题号（注意事项）从题号统计中排除 */
    private int firstSectionHeaderLine(String[] lines) {
        return textStructure.firstSectionHeaderLine(lines);
    }

    private void cancelFutures(List<Future<String>> futures, int from) {
        for (int j = from; j < futures.size(); j++) {
            futures.get(j).cancel(true);
        }
    }

    /**
     * 按题号边界把文本切成若干块（每块约 CHUNK_TARGET_QUESTIONS 题，最多 CHUNK_MAX 块）。
     * 返回 {start, end} 偏移区间（清洗后空间）；块 0 包含题号前的头部内容；
     * 检测不到连续题号时返回单块（调用方回退单次调用）。
     */
    private List<int[]> splitChunks(String text, int chunkTarget) {
        List<int[]> ranges = textStructure.splitChunks(text, chunkTarget);
        log.info("分块边界检测：{} 个题号边界（chunkTarget={}）",
                textStructure.detectQuestionBoundaries(text).size(), chunkTarget);
        return ranges;
    }

    /**
     * 修剪块首/块尾孤儿（重叠切块的残留）：
     * - 块首：上一题残留的"题号行 + 选项行"（模型会把它错配给本块首题 → 题干选项跨题、无法定答案）；
     * - 块尾：下一题的"材料+题干"（无题号选项）。
     * 修剪后每块只含完整题目序列；孤儿在相邻块中是完整题，不丢失。
     */
    private String trimOrphans(String chunk, boolean trimHead, boolean trimTail) {
        return textStructure.trimOrphans(chunk, trimHead, trimTail);
    }

    /**
     * 小数/编号行排除（题号判定用）："1.5"、"3.14%"、"10.2万吨" 是小数行（不是题号）；
     * "1.2020年，全国服装出口额…" 是"题号+年份"（MinerU 重建文本中题号与题干同行）——
     * 4 位数字紧接"年"不算小数，否则材料检测会把题目行收进材料区、题号回填错乱。
     */
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
     * 行首题号行：孤立题号行（"10."）或"题号+题干同行"（"10. 题干…"），排除小数行与答案列表行。
     * 行首允许 [图片N] 标记前缀（MinerU 重建文本中材料图表标记可能与题号同行："[图片1][图片2]6.2012年…"）。
     */
    private boolean isQuestionNumberLine(String t) {
        return textStructure.isQuestionNumberLine(t);
    }

    /**
     * 检测题号边界：行首 "1." / "2、" 等，取最长的连续递增（允许跳号）序列。
     * 排除答案列表行（"1.B" 这类紧凑格式），避免把卷末答案误当题号。
     *
     * @return 边界行起始位置列表（按文档顺序），不足 3 个返回空
     */
    private List<Integer> detectQuestionBoundaries(String text) {
        return textStructure.detectQuestionBoundaries(text);
    }

    /**
     * 判断文本是否像"卷末答案列表"：每行是 "题号+答案码"（如 1.B / 2. C / 3:ABD / 5√ / 【1题答案】B），
     * 或整块是 "答案：1.A 2.B ..." 紧凑格式。多文件时用于识别最后一份是否为答案文件。
     * （逻辑统一在 AiAnswerFormat）
     */
    private boolean looksLikeAnswerList(String s) {
        return AiAnswerFormat.looksLikeAnswerList(s);
    }

    /**
     * 从"最后一个题号边界到文末"的尾段中定位卷末答案区的起始偏移：
     * 首个答案行（ANSWER_LINE / 区间式）或"参考答案"标题行即为答案区起点。
     * 找不到返回 -1（尾段整体是最后一题的内容，不含答案列表）。
     */
    private int answerSectionStartOffset(String tailCandidate) {
        return textStructure.answerSectionStartOffset(tailCandidate);
    }

    /**
     * 图片配额二次拆块（marksEmbedded 路径）：块内唯一图片数超过 MAX_CHUNK_IMAGES 时，
     * 按内部题号边界切分（题号行是该题的起点，图片跟随其后的文本），直到配额满足或新增块数用尽（CHUNK_MAX）。
     * 不可拆（无内部边界/预算耗尽）保持原块。
     */
    private List<int[]> splitRangesByImageQuota(String text, List<int[]> ranges) {
        return textStructure.splitRangesByImageQuota(text, ranges);
    }

    // ==================== Prompt 构建 ====================

    /** 块内图片编号（按文本出现顺序去重） */
    private List<Integer> imageChunkNumbers(String chunkText) {
        return textStructure.imageChunkNumbers(chunkText);
    }

    /** 剥离 AI 输出中的图片编号标记（[图片N]）——文本定位/去重时用（标记不在源文文本中） */
    private String stripImageRefs(String text) {
        return textStructure.stripImageRefs(text);
    }

    // ==================== 工具 ====================

    private AiImportJob findByIdOrThrow(Long id) {
        AiImportJob job = jobMapper.selectById(id);
        if (job == null) {
            throw new NoSuchElementException("任务不存在：" + id);
        }
        return job;
    }

    private String detectType(String fileName) {
        String lower = fileName.toLowerCase();
        if (lower.endsWith(".pdf")) return "PDF";
        if (lower.endsWith(".docx")) return "DOCX";
        if (lower.endsWith(".md") || lower.endsWith(".markdown")) return "MD";
        if (lower.endsWith(".png") || lower.endsWith(".jpg") || lower.endsWith(".jpeg")
                || lower.endsWith(".webp") || lower.endsWith(".bmp")) return "IMAGE";
        return "TXT";
    }

    private boolean isDocxType(String fileName) {
        return fileName != null && fileName.toLowerCase().endsWith(".docx");
    }

    private String fileBaseName(String fileName) {
        int dot = fileName.lastIndexOf('.');
        String base = dot > 0 ? fileName.substring(0, dot) : fileName;
        return base.length() > 100 ? base.substring(0, 100) : base;
    }

    private String truncate(String s, int max) {
        if (s == null) return null;
        return s.length() <= max ? s : s.substring(0, max);
    }
}
