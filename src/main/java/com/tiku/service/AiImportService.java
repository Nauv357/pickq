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
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.awt.image.BufferedImage;
import javax.imageio.ImageIO;
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
import java.util.TreeSet;
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

    // ===== 分块并行（文本路径加速） =====
    /** 每题号边界正则：行首 1-3 位数字 + 半角/全角点、顿号或括号 */
    private static final Pattern QUESTION_START = Pattern.compile("(?m)^\\s*(\\d{1,3})\\s*[.．、)）]");
    /** 答案列表行：如 "1.B" "12. C" "3:ABD" "5√"；含学科卷 "【1题答案】B"（统一 AiAnswerFormat） */
    private static final Pattern ANSWER_LINE = AiAnswerFormat.ANSWER_LINE;
    /**
     * 源文答案标记：答案：X / 答：X / （X） / 【答案】X / 【N题答案】X / （对）等。
     * 用于 aiSupplement=false 时校验模型给出的答案是否真有源文证据（防关闭思考后模型编造答案）。
     */
    private static final Pattern ANSWER_MARKER = Pattern.compile(
            "(?:答案|参考答案|正确答案)\\s*[:：]?\\s*[（(]?\\s*([A-Ha-h]{1,6}|[对错√×])\\s*[）)]?"
                    + "|答\\s*[:：]\\s*[（(]?\\s*([A-Ha-h]{1,6}|[对错√×])\\s*[）)]?"
                    + "|[（(]\\s*([A-Ha-h]{1,6}|[对错√×])\\s*[）)]"
                    + "|【\\s*答案\\s*】\\s*[:：]?\\s*([A-Ha-h]{1,6}|[对错√×])"
                    + "|【\\s*(?:第)?\\s*\\d{1,3}\\s*题\\s*(?:的)?\\s*答案\\s*】\\s*[:：]?\\s*([A-Ha-h]{1,6}|[对错√×])");
    /** 材料补齐时向前收集的停止行：上一题的题号行或选项行 */
    private static final Pattern MATERIAL_STOP = Pattern.compile("^\\d{1,3}\\s*[.．、)）]|^[A-Da-d][.．、]");
    /** 裸题号内容（如 "30."），材料补齐时整体替换 */
    private static final Pattern BARE_NUMBER_CONTENT = Pattern.compile("^\\d{1,3}\\s*[.．、)）]?\\s*$");
    /** 孤立题号行（如 "1." 单独成行） */
    private static final Pattern QUESTION_NUMBER_ALONE = Pattern.compile("^\\d{1,3}\\s*[.．、)）]\\s*$");
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
    private final Executor aiImportExecutor;
    private final java.util.concurrent.ExecutorService aiChunkExecutor;
    private final AiJobEventService aiJobEventService;
    private final Path importsDir;

    public AiImportService(AiImportJobMapper jobMapper,                           AiConfigService aiConfigService,
                           AiClientService aiClientService,
                           DocumentParserService documentParserService,
                           MineruParseService mineruParseService,
                           ContentPackageService contentPackageService,
                           QuestionBankService questionBankService,
                           ImageStorageService imageStorageService,
                           com.tiku.mapper.MaterialMapper materialMapper,
                           ObjectMapper objectMapper,
                           @Qualifier("aiImportExecutor") Executor aiImportExecutor,
                           @Qualifier("aiChunkExecutor") java.util.concurrent.ExecutorService aiChunkExecutor,
                           AiJobEventService aiJobEventService,
                           @Value("${tiku.data-dir}") String dataDir) {
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
        this.aiImportExecutor = aiImportExecutor;
        this.aiChunkExecutor = aiChunkExecutor;
        this.aiJobEventService = aiJobEventService;
        this.importsDir = Path.of(dataDir, "imports");
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
            if (Files.isDirectory(importsDir)) {
                try (var stream = Files.list(importsDir)) {
                    stream.filter(Files::isDirectory).forEach(dir -> {
                        String dirName = dir.getFileName().toString();
                        if (!dirName.matches("\\d+")) {
                            return;
                        }
                        try {
                            long orphanId = Long.parseLong(dirName);
                            if (jobMapper.selectById(orphanId) == null) {
                                java.nio.file.attribute.FileTime ft = Files.getLastModifiedTime(dir);
                                if (ft.toInstant().isBefore(
                                        deadline.atZone(java.time.ZoneId.systemDefault()).toInstant())) {
                                    deleteJobFiles(orphanId);
                                }
                            }
                        } catch (Exception ignored) {
                            //单目录清理失败不影响其他
                        }
                    });
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
        fileNames = fileNames.stream().map(AiImportService::sanitizeFileName).toList();
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

        //文件落盘（异步线程不能安全持有 MultipartFile）
        Path jobDir = importsDir.resolve(String.valueOf(job.getId()));
        Files.createDirectories(jobDir);
        for (int i = 0; i < fileNames.size(); i++) {
            Files.write(jobDir.resolve(i + "-" + fileNames.get(i)), files.get(i));
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

    /**
     * 上传文件名净化（display/落盘共用）：路径分隔符与非法字符替换为下划线、控制字符剔除、
     * 逗号替换（file_names 以逗号拼接存储）、去首尾空白、拒绝 "."/".."、限长 150（截头留尾保扩展名）。
     */
    private static String sanitizeFileName(String raw) {
        if (raw == null || raw.isBlank()) {
            return "file";
        }
        String name = raw.replace('\\', '_').replace('/', '_')
                .replace(',', '_')
                .replaceAll("[\\\\/:*?\"<>|]", "_")
                .replaceAll("[\\p{Cntrl}]", "")
                .trim();
        if (name.isEmpty() || ".".equals(name) || "..".equals(name)) {
            name = "file";
        }
        if (name.length() > 150) {
            name = name.substring(name.length() - 150);
        }
        return name;
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
            Path jobDir = importsDir.resolve(String.valueOf(jobId));
            List<String> texts = new ArrayList<>();
            List<AiClientService.ImageData> images = new ArrayList<>();
            //阶段 4：内嵌图（题干/选项/材料图）+ 逐页文本（PDF 分块按页归属图片用）
            List<DocumentParserService.ExtractedImage> extracted = new ArrayList<>();
            List<String> pageTexts = new ArrayList<>();
            StringBuilder warning = new StringBuilder();
            String[] names = job.getFileNames().split(",");
            int globalPageOffset = 0;
            //引擎选择：MINERU 仅当用户显式勾选"扫描件 MinerU 增强"时使用（打字版文件一律本地——
            //文本层充足时本地直传视觉/文本分块效果优于 MinerU，实测 125 题卷 MinerU 反而不如 agent 看图）；
            //AUTO/LOCAL 永不自动走 MinerU；扫描件（文本层≈0）默认整页渲染交视觉模型直读。
            //autoPlain（纯文本检测）对 AUTO 与 LOCAL 均生效：纯文字文档走本地文本分块（最快、判断题不漏）
            boolean mineruConfigured = aiConfigService.isMineruConfigured();
            String mineruKey = mineruConfigured ? aiConfigService.load().getMineruKey() : null;
            String engine = job.getEngine() == null || job.getEngine().isBlank() ? "AUTO" : job.getEngine();
            boolean engineLocal = "LOCAL".equals(engine);
            boolean engineMineru = "MINERU".equals(engine);
            boolean mineruUsed = false;
            boolean allPlain = true; //纯文本检测（全部文件无图+文本层完整）——AUTO/LOCAL 生效
            List<String> localTexts = new ArrayList<>(); //本地解析文本（MinerU 路径的答案溯源兜底）
            int formulaTotal = 0; //docx 公式图（WMF/EMF MathType OLE）总数——公式密集 → 整理阶段强制思考
            Set<Integer> formulaNosAll = new HashSet<>(); //公式图编号全集（残留转写兜底用；docx 编号=全局 extracted 编号）
            for (int i = 0; i < names.length; i++) {
                byte[] fileBytes = Files.readAllBytes(jobDir.resolve(i + "-" + names[i]));
                DocumentParserService.ParseResult parsed;
                //先本地解析一次（AUTO 检测用；MinerU 失败回退复用）
                DocumentParserService.ParseResult localParsed = documentParserService.parse(names[i], fileBytes);
                if (localParsed.text() != null && !localParsed.text().isBlank()) {
                    localTexts.add(localParsed.text());
                }
                //MinerU 仅显式引擎（用户勾选"扫描件增强"）触发；打字版/扫描件默认本地
                boolean wantMineru = engineMineru && mineruConfigured
                        && mineruParseService.isMineruFile(names[i]);
                if (wantMineru) {
                    try {
                        parsed = mineruParseService.parse(names[i], fileBytes, mineruKey);
                        mineruUsed = true;
                    } catch (Exception e) {
                        //MinerU 失败（网络/API/额度）→ 回退本地解析，不整体失败
                        log.warn("AI 导入任务 {} 文件 {} 走 MinerU 解析失败，回退本地解析：{}",
                                jobId, names[i], e.getMessage());
                        parsed = localParsed;
                        warning.append("MinerU 解析不可用（").append(e.getMessage())
                                .append("），该文件已回退本地解析 ");
                    }
                } else {
                    parsed = localParsed;
                }
                if (engineMineru) {
                    allPlain = false; //MinerU 引擎：数据流不同，不参与纯文本检测
                } else {
                    allPlain &= plainTextCandidate(localParsed);
                }
                if (parsed.text() != null && !parsed.text().isBlank()) {
                    texts.add(parsed.text());
                }
                if (parsed.images() != null) {
                    images.addAll(parsed.images());
                }
                if (parsed.pageTexts() != null) {
                    pageTexts.addAll(parsed.pageTexts());
                }
                if (parsed.extractedImages() != null) {
                    for (DocumentParserService.ExtractedImage e : parsed.extractedImages()) {
                        //跨文件页号连续（docx 无页概念 pageNo=0，多文件含图走单次调用兜底）
                        extracted.add(new DocumentParserService.ExtractedImage(e.pageNo() + globalPageOffset, e.sortX(), e.sortY(), e.pageHeight(), e.image()));
                    }
                }
                globalPageOffset += parsed.pageTexts() == null ? 0 : parsed.pageTexts().size();
                formulaTotal += parsed.formulaImageCount();
                if (parsed.formulaImageNos() != null) {
                    formulaNosAll.addAll(parsed.formulaImageNos());
                }
                if (parsed.warning() != null) {
                    warning.append(parsed.warning()).append(' ');
                }
                //当前文件序号落库（前端"解析 2/3"），进度按文件数推进（PARSING 10 → 35）
                job.setCurrentFileIndex(i + 1);
                updateStage(job, "PROCESSING", "PARSING", 10 + (i + 1) * 25 / names.length);
                aiJobEventService.publish(jobId, getJob(jobId));
            }
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
            //MinerU 答案溯源兜底：MinerU 重建文本可能把卷末答案行识别坏（公式误读/丢行/样式串污染），
            //本地解析文本中的 【N题答案】X / N.X 行是干净证据 → 供 postProcessAnswers 恢复原文答案
            String localEvidence = null;
            if (mineruUsed && !localTexts.isEmpty()) {
                localEvidence = String.join("\n\n========== 下一份文件 ==========\n\n", localTexts);
            }
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
                Path imgDir = jobDir.resolve("images");
                Files.createDirectories(imgDir);
                int n = 0;
                for (DocumentParserService.ExtractedImage e : extracted) {
                    n++;
                    Files.write(imgDir.resolve(n + ".png"), e.image().data());
                }
                log.info("AI 导入任务 {} 提取内嵌图片 {} 张（按编号 1..{} 临时落盘）", jobId, n, n);
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
            String systemPrompt = buildSystemPrompt(aiSupplement, false);
            AiParsedResult parsedResult;
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
                AiSettings vision = buildVisionSettings(settings);
                String userPrompt = buildUserPrompt(mergedText, warning.toString().trim());
                //内嵌图编号规则（[图片1]..[图片N]，图片随消息按编号顺序提供）
                if (hasExtracted) {
                    userPrompt += buildImageRefRule(1, extracted.size());
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
                parsedResult = fixResidualFormulaImages(parsedResult, formulaNosAll, extracted, settings, jobId);
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
            parsedResult = new AiParsedResult(sorted, parsedResult.materials());
            String resultJson = serializeResult(parsedResult);
            //终态落库走条件更新（WHERE status <> 'CANCELED'）：取消竞态下线程只能停，
            //绝不能把 CANCELED 复活成 SUCCESS/FAILED（旧实现整行 updateById 会覆盖）
            if (!writeTerminal(jobId, "SUCCESS", null, resultJson)) {
                cleanupCanceledJobFiles(jobId);
                return;
            }
        } catch (Exception e) {
            log.error("AI 导入任务 {} 处理失败", jobId, e);
            //失败终态同样条件更新；任务已被取消时不落失败原因（不覆盖用户的取消意图），只清理文件退出
            if (!writeTerminal(jobId, "FAILED", truncate(e.getMessage(), 500), null)) {
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
    private boolean writeTerminal(Long jobId, String status, String error, String resultJson) {
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
                AiParsedResult parsed = parseStoredResult(job.getResultJson());
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
                questions, materials, job.getWarningHint(), job.getError(), job.getCreatedAt(), job.getFinishedAt());
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
            Path dir = importsDir.resolve(String.valueOf(jobId));
            if (Files.exists(dir)) {
                try (var stream = Files.walk(dir)) {
                    stream.sorted(java.util.Comparator.reverseOrder()).forEach(p -> {
                        try {
                            Files.deleteIfExists(p);
                        } catch (IOException ignored) {
                        }
                    });
                }
            }
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
        Path imgDir = importsDir.resolve(String.valueOf(jobId)).resolve("images");
        List<JobImageInfo> result = new ArrayList<>();
        if (!Files.isDirectory(imgDir)) {
            return result;
        }
        try (var stream = Files.list(imgDir)) {
            stream.filter(Files::isRegularFile).forEach(p -> {
                String name = p.getFileName().toString();
                Matcher m = Pattern.compile("^(\\d+)\\.([a-zA-Z0-9]+)$").matcher(name);
                if (m.find()) {
                    result.add(new JobImageInfo(Integer.parseInt(m.group(1)), name, m.group(2).toLowerCase()));
                }
            });
        } catch (IOException e) {
            log.warn("读取任务 {} 图片列表失败：{}", jobId, e.getMessage());
        }
        result.sort(java.util.Comparator.comparingInt(JobImageInfo::num));
        return result;
    }

    /** 读取任务临时图片字节（素材区渲染用；编号不存在 → 抛异常由 404 处理） */
    public byte[] readJobImage(Long jobId, int num) throws IOException {
        findByIdOrThrow(jobId);
        Path imgDir = importsDir.resolve(String.valueOf(jobId)).resolve("images");
        Path file = imgDir.resolve(num + ".png");
        if (!Files.exists(file)) {
            //兼容非 png 命名
            try (var stream = Files.list(imgDir)) {
                for (Path p : (Iterable<Path>) stream::iterator) {
                    String name = p.getFileName().toString();
                    if (name.startsWith(num + ".") && Files.isRegularFile(p)) {
                        file = p;
                        break;
                    }
                }
            }
        }
        if (!Files.exists(file) || !Files.isRegularFile(file)) {
            throw new IllegalArgumentException("图片不存在：" + num);
        }
        return Files.readAllBytes(file);
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
            return parseStoredResult(job.getResultJson()).materials();
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
        AiParsedResult parsed;
        if (editedQuestions != null && !editedQuestions.isEmpty()) {
            //预览页编辑后的题目/材料（含素材区拖入的材料引用）：以提交内容为准；
            //请求内容绕过 parseAndValidate（后端校验路径），必须在此补全校验（防非法内容直落库）
            validateEditedContent(editedQuestions, editedMaterials);
            parsed = new AiParsedResult(editedQuestions,
                    editedMaterials == null ? List.of() : editedMaterials);
        } else {
            try {
                parsed = parseStoredResult(job.getResultJson());
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
        Map<String, String> imageRefs = importImages(jobId, targetBankId, parsed);
        //材料入库（materialKey → 本地 material_id；content 中图片引用已替换）
        Map<String, Long> materialIdByKey = new HashMap<>();
        if (!parsed.materials().isEmpty()) {
            int order = 0;
            for (ContentPackageMaterial m : parsed.materials()) {
                com.tiku.model.Material material = new com.tiku.model.Material();
                material.setBankId(targetBankId);
                material.setContent(replaceImageRefs(m.getContent(), imageRefs));
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
            q.setContent(replaceImageRefs(q.getContent(), imageRefs));
            q.setReferenceAnswer(replaceImageRefs(q.getReferenceAnswer(), imageRefs));
            if (q.getOptions() != null) {
                q.setOptions(q.getOptions().stream()
                        .map(o -> new OptionItem(o.key(), replaceImageRefs(o.text(), imageRefs)))
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

    /** 图片编号 → 正式文件名映射（[图片N] → [图片:name]） */
    private Map<String, String> importImages(Long jobId, Long bankId, AiParsedResult parsed) {
        Map<String, String> byNumber = new HashMap<>();
        //收集题目/材料文本中实际引用的编号
        Set<String> referenced = new LinkedHashSet<>();
        for (ContentPackageQuestion q : parsed.questions()) {
            collectImageRefs(q.getContent(), referenced);
            collectImageRefs(q.getReferenceAnswer(), referenced);
            if (q.getOptions() != null) {
                for (OptionItem o : q.getOptions()) {
                    collectImageRefs(o.text(), referenced);
                }
            }
        }
        for (ContentPackageMaterial m : parsed.materials()) {
            collectImageRefs(m.getContent(), referenced);
        }
        if (referenced.isEmpty()) {
            return byNumber;
        }
        Path imgDir = importsDir.resolve(String.valueOf(jobId)).resolve("images");
        for (String num : referenced) {
            Path tmp = imgDir.resolve(num + ".png");
            if (!Files.exists(tmp)) {
                continue; //引用不存在的编号：保留原标记（预览可见）
            }
            try {
                String name = imageStorageService.importImage(bankId, Files.readAllBytes(tmp));
                byNumber.put(num, name);
            } catch (IOException e) {
                log.warn("AI 导入任务 {} 图片 {} 落盘失败：{}", jobId, num, e.getMessage());
            }
        }
        if (!byNumber.isEmpty()) {
            log.info("AI 导入任务 {} 图片落盘：{} 张（引用 {} 个编号）", jobId, byNumber.size(), referenced.size());
        }
        return byNumber;
    }

    /** 收集文本中的 [图片N] 编号 */
    private void collectImageRefs(String text, Set<String> refs) {
        if (text == null) {
            return;
        }
        Matcher m = Pattern.compile("\\[图片(\\d+)]").matcher(text);
        while (m.find()) {
            refs.add(m.group(1));
        }
    }

    /** 文本中的 [图片N] → [图片:name]（仅替换存在映射的编号；不存在的保留原标记） */
    private String replaceImageRefs(String text, Map<String, String> refs) {
        if (text == null || refs.isEmpty()) {
            return text;
        }
        Matcher m = Pattern.compile("\\[图片(\\d+)]").matcher(text);
        StringBuffer sb = new StringBuffer();
        while (m.find()) {
            String name = refs.get(m.group(1));
            if (name != null) {
                m.appendReplacement(sb, Matcher.quoteReplacement("[图片:" + name + "]"));
            }
        }
        m.appendTail(sb);
        return sb.toString();
    }

    private int countQuestions(AiImportJob job) {
        if (job.getResultJson() == null || job.getResultJson().isBlank()) {
            return 0;
        }
        try {
            return parseStoredResult(job.getResultJson()).questions().size();
        } catch (IOException e) {
            return 0;
        }
    }

    // ==================== AI 输出解析校验 ====================

    /** AI 整理结果：题目 + 共享材料（材料组识别仅单次调用路径启用，分块路径不输出材料） */
    private record AiParsedResult(List<ContentPackageQuestion> questions, List<ContentPackageMaterial> materials) {
    }

    /**
     * Markdown 输出解析（分块路径）：模型按标准 MD 模板输出 → MdQuestionParser 确定性解析 → validate 校验。
     * 题号直接取自标题（免源文定位回填）；材料由模型声明（## 材料 mN 块），题目按位置自动关联。
     * 解析失败/坏题丢弃不抛异常（补漏 + 预览兜底）。
     */
    private List<ContentPackageQuestion> mdParseQuestions(String mdOutput, boolean aiSupplement) {
        return mdParseResult(mdOutput, aiSupplement).questions();
    }

    /** Markdown 输出解析（含材料块） */
    private AiParsedResult mdParseResult(String mdOutput, boolean aiSupplement) {
        String md = unwrapJsonOutput(mdOutput);
        MdQuestionParser.ParseOutcome outcome = MdQuestionParser.parseWithMaterials(md);
        List<ContentPackageQuestion> parsed = new ArrayList<>();
        for (ContentPackageQuestion q : outcome.questions()) {
            if (validate(q, aiSupplement)) {
                parsed.add(q);
            }
        }
        return new AiParsedResult(parsed, outcome.materials());
    }

    /** json_object 响应格式会把 Markdown 包成 {"output": "## 第1题…"}（DeepSeek 思考模式实测）→ 解包取 output。 */
    private String unwrapJsonOutput(String out) {
        String s = out == null ? "" : stripMdFence(out).trim();
        if (!s.startsWith("{")) {
            return out;
        }
        try {
            JsonNode node = objectMapper.readTree(s);
            JsonNode o = node.path("output");
            if (o.isTextual() && !o.asText().isBlank()) {
                return o.asText();
            }
        } catch (Exception ignored) {
        }
        return out;
    }

    /** Markdown 代码围栏剥离（```md … ``` / ```markdown … ```），不做大括号截断（公式 LaTeX 含 {}）。 */
    private String stripMdFence(String out) {
        String s = out == null ? "" : out.trim();
        if (s.startsWith("```")) {
            int nl = s.indexOf('\n');
            if (nl >= 0) {
                s = s.substring(nl + 1);
            }
            int endFence = s.lastIndexOf("```");
            if (endFence >= 0) {
                s = s.substring(0, endFence);
            }
        }
        return s;
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
    private AiParsedResult parseAndValidate(String aiOutput, boolean aiSupplement, String sourceText,
                                            boolean enableMaterials, boolean skipSourceRepair) {
        String json = stripCodeFence(aiOutput);
        JsonNode node;
        try {
            node = objectMapper.readTree(json);
        } catch (IOException e) {
            throw new IllegalStateException("AI 输出不是合法 JSON，请重试或更换模型");
        }
        List<ContentPackageMaterial> materials = new ArrayList<>();
        if (enableMaterials) {
            JsonNode matArr = node.path("materials");
            if (matArr.isArray()) {
                Set<String> seenKeys = new HashSet<>();
                for (JsonNode item : matArr) {
                    try {
                        ContentPackageMaterial m = objectMapper.treeToValue(item, ContentPackageMaterial.class);
                        if (m.getMaterialKey() == null || m.getMaterialKey().isBlank() || m.getContent() == null || m.getContent().isBlank()) {
                            continue;
                        }
                        if (!seenKeys.add(m.getMaterialKey())) {
                            continue; //materialKey 重复：保留先出现的
                        }
                        materials.add(m);
                    } catch (Exception ignored) {
                        //坏材料丢弃
                    }
                }
            }
        }
        Set<String> validMaterialKeys = materials.stream().map(ContentPackageMaterial::getMaterialKey).collect(java.util.stream.Collectors.toSet());

        JsonNode arr = node.isArray() ? node : node.path("questions");
        List<ContentPackageQuestion> result = new ArrayList<>();
        if (arr.isArray()) {
            for (JsonNode item : arr) {
                try {
                    ContentPackageQuestion q = objectMapper.treeToValue(item, ContentPackageQuestion.class);
                    if (validate(q, aiSupplement)) {
                        //材料引用校验：materialKey 必须存在于 materials（分块路径无 materials → 一律清空）
                        if (q.getMaterialKey() != null && !q.getMaterialKey().isBlank() && !validMaterialKeys.contains(q.getMaterialKey())) {
                            q.setMaterialKey(null);
                        }
                        //模型关闭思考后可能省略材料/只输出题号/把"题号+选项"或"孤儿材料"当题干 →
                        //本地从源文回填（确定性保真）；返回 false = 模型错配残片（题干与选项对不上），丢弃。
                        //视觉分页路径跳过（见方法注释：行首题号定位会误杀"题号+题干同行"版式的完整题）
                        if (!skipSourceRepair && !completeMaterialFromSource(q, sourceText)) {
                            continue;
                        }
                        //answerSource 统一校正（不信任模型标记）：
                        //- 无答案 → null（显示"无答案"）
                        //- 有答案：源文有证据 → ORIGINAL；无证据 → aiSupplement=true 时 AI_SUPPLEMENT，
                        //  aiSupplement=false 时视为编造，清空答案
                        if (q.getAnswerKeys() != null && !q.getAnswerKeys().isEmpty()) {
                            boolean evidence = hasSourceAnswerEvidence(sourceText, q);
                            if (!aiSupplement && !evidence) {
                                q.setAnswerKeys(List.of());
                                q.setAnswerSource(null);
                            } else {
                                q.setAnswerSource(evidence ? "ORIGINAL" : "AI_SUPPLEMENT");
                            }
                        } else {
                            q.setAnswerSource(null);
                        }
                        if (!aiSupplement) {
                            //禁止补充模式：模型可能违反"不生成解析"规则自行编写答案文字/解析（实测）
                            //→ 一律清空（用户预览页自行补填；原文解析属于 content 范畴，不入 analysis）
                            q.setAnswerText(null);
                            q.setAnalysis(null);
                            //主观题参考作答也属"补充内容"：禁止补充模式一律清空
                            if (q.getReferenceAnswer() != null) {
                                q.setReferenceAnswer(null);
                            }
                        }
                        result.add(q);
                    }
                } catch (Exception ignored) {
                    //坏题丢弃
                }
            }
        }
        return new AiParsedResult(result, materials);
    }

    private boolean validate(ContentPackageQuestion q, boolean aiSupplement) {
        if (q.getContent() == null || q.getContent().isBlank()) {
            return false;
        }
        String type = q.getType() == null ? "" : q.getType().toUpperCase();
        if (!VALID_TYPES.contains(type)) {
            return false;
        }
        q.setType(type);
        //主观题：无选项无答案，参考答案透传（referenceAnswer 允许为空，用户预览补填）
        if (!"SUBJECTIVE".equals(type)) {
            if (q.getOptions() == null || q.getOptions().isEmpty()) {
                if ("JUDGE".equals(type)) {
                    //判断题兜底：补"正确/错误"选项
                    q.setOptions(List.of(new OptionItem("A", "正确"), new OptionItem("B", "错误")));
                } else {
                    //选项缺失（图片选项题/模型漏拆同行选项）→ 转主观保留：
                    //整题丢弃是最坏结果（实测判断推理第 2 题图片选项被丢）；预览页可补选项/图
                    q.setType("SUBJECTIVE");
                    q.setOptions(List.of());
                    q.setAnswerKeys(List.of());
                    q.setAnswerText(null);
                    q.setAnswerSource(null);
                    type = "SUBJECTIVE";
                }
            }
        }
        //答案允许为空：aiSupplement=true 时模型可能漏补（实测某块 10/10 全漏，若整题丢弃会丢 9 题）；
        //两种模式统一"答案留空、不丢题"，由用户在预览页补填（answerSource 置 null 显示"无答案"）
        if (q.getAnswerKeys() == null || q.getAnswerKeys().isEmpty()) {
            q.setAnswerKeys(List.of());
        }
        //answerSource 由 parseAndValidate 按源文证据统一校正（此处不设，避免与证据校验冲突）
        //判断题选项兜底：AI 未给选项时补"正确/错误"
        if ("JUDGE".equals(type) && q.getOptions().size() < 2) {
            q.setOptions(List.of(new OptionItem("A", "正确"), new OptionItem("B", "错误")));
        }
        if (q.getQuestionKey() == null || q.getQuestionKey().isBlank()) {
            q.setQuestionKey("AI_" + UUID.randomUUID().toString().substring(0, 8).toUpperCase());
        }
        if (q.getScore() == null) {
            //主观题默认 5 分（自评半分行），客观题默认 1 分
            q.setScore("SUBJECTIVE".equals(type) ? 5.0 : 1.0);
        }
        return true;
    }

    /** 剥离 markdown 代码块（```json ... ```） */
    private String stripCodeFence(String output) {
        String s = output == null ? "" : output.trim();
        int start = s.indexOf('{');
        int end = s.lastIndexOf('}');
        if (start >= 0 && end > start) {
            return s.substring(start, end + 1);
        }
        return s;
    }

    /** 模型输出是否为 JSON 形态（无思考重试 + json_object 响应格式时模型可能输出旧版 JSON 结构） */
    private boolean looksLikeJson(String out) {
        String s = out == null ? "" : out.trim();
        return (s.startsWith("{") || s.startsWith("[")) && s.contains("question");
    }

    /** 端点降级快速失败：源文题号 ≥5 但结果 <3 题 → 模型持续返回空白/垃圾，整单报错让用户稍后重试。
     *  （静默产出 1-2 题垃圾比明确失败更糟——用户以为导入成功） */
    private void assertNotDegraded(List<ContentPackageQuestion> questions, String fullSource) {
        if (fullSource == null || fullSource.isBlank()) {
            return;
        }
        int nums = 0;
        for (String l : fullSource.split("\\R", -1)) {
            if (isQuestionNumberLine(l.trim())) {
                nums++;
            }
        }
        if (nums >= 5 && questions.size() < 3) {
            throw new IllegalStateException("模型端点持续返回空白内容（源文约 " + nums + " 题仅产出 "
                    + questions.size() + " 题），本次导入失败，请稍后重试");
        }
    }

    /**
     * 模型输出题干可疑（材料省略 / 裸题号 / 把"题号+选项行"或"孤儿材料"当题干）时，本地从源文回填题干。
     * 定位：① 题干/材料文本 + 首选项消歧；② 兜底：content 以题号开头 → 直接定位源文题号行；
     *      ③ 内容定位后向后找最近的题号行，统一锚到题号行（孤儿材料场景）。
     * 回填：从题号行向前收集非空行（直到上一题的题号行/选项行），反转 = 材料+题干（不含题号行）。
     * 校验：短题干（≤30）消歧失败 = 模型错配残片（题干与选项来自不同题）→ 丢弃；
     *      回填/保留后做选项一致性校验（题号行后 8 行内应含各选项文本，判断题跳过）→ 不通过丢弃。
     *
     * @return true 保留该题，false 丢弃（模型错配残片）
     */
    private boolean completeMaterialFromSource(ContentPackageQuestion q, String sourceText) {
        if (sourceText == null || sourceText.isBlank() || q.getContent() == null) {
            return true;
        }
        //带材料引用（materials 顶层输出）的题：材料已在 materials 里，禁止回填把材料再拼进题干
        if (q.getMaterialKey() != null && !q.getMaterialKey().isBlank()) {
            return true;
        }
        //图片引用（[图片N]）不在源文文本中：定位前剥离，避免定位失败/错位
        String content = stripImageRefs(q.getContent()).trim();
        if (content.isEmpty()) {
            return false;
        }
        //模型把"答案：X / 解析：…"行当题干输出（残片）→ 直接丢弃（[\s\S]* 跨行，.* 不匹配换行）
        if (content.matches("^(答案|参考答案|解析)[:：][\\s\\S]*")) {
            log.warn("题干回填丢弃（答案/解析开头残片）：content={}", truncate(content, 50));
            return false;
        }
        String optProbe = null;
        if (q.getOptions() != null && !q.getOptions().isEmpty()) {
            //图片选项（[图片N]）无法在文本中定位 → 剥离后为空则跳过消歧
            String t = stripImageRefs(q.getOptions().get(0).text() == null ? "" : q.getOptions().get(0).text());
            if (t != null) {
                optProbe = t.length() > 8 ? t.substring(0, 8) : t;
            }
        }
        //content 以题号开头（"30." 或 "10. A.xxx..."）→ 模型把"题号/题号+选项行"当题干输出（垃圾），
        //无论通过哪种路径定位都直接回填（锚点可信）
        Matcher numMatcher = Pattern.compile("^(\\d{1,3})\\s*[.．、)）]").matcher(content);
        boolean numberAnchored = numMatcher.find();
        String[] lines = sourceText.split("\\R", -1);
        int anchorLine = -1;
        //① 内容定位（含选项消歧）：先按"首行"匹配，再按"去空白全文"映射（多行 content 也能定位）
        int contentLine = locateContentLine(lines, content);
        if (contentLine >= 0) {
            //标题区残片：定位行（含该行）之前没有任何题号行（文件标题被当题干）→ 丢弃。
            //仅限短内容（≤60 字符）：粉笔 PDF 的 Q1 材料也在第一个题号行前，但材料长（100+ 字符），不能误杀
            boolean beforeFirstNum = true;
            for (int li = 0; li <= contentLine; li++) {
                if (isQuestionNumberLine(lines[li].trim())) {
                    beforeFirstNum = false;
                    break;
                }
            }
            if (beforeFirstNum && content.length() <= 60) {
                log.warn("题干回填丢弃（标题区残片）：contentLine={} content={}", contentLine, truncate(content, 50));
                return false;
            }
            anchorLine = contentLine;
            if (optProbe != null && !optProbe.isBlank()) {
                //消歧：定位行后 8 行内应能找到首选项文本
                StringBuilder after = new StringBuilder();
                for (int j = contentLine; j < Math.min(lines.length, contentLine + 8); j++) {
                    after.append(lines[j]);
                }
                if (!after.toString().contains(optProbe)) {
                    anchorLine = -1; //消歧失败 → 交②题号定位或丢弃判定
                }
            }
        }
        //② 兜底：内容定位失败且 content 以题号开头 → 按题号行定位
        if (anchorLine < 0 && numberAnchored) {
            String numPat = "^\\s*" + numMatcher.group(1) + "\\s*[.．、)）](?:\\s*[A-Da-d]|\\s*$)";
            for (int i = 0; i < lines.length; i++) {
                if (lines[i].matches(numPat)) {
                    anchorLine = i;
                    break;
                }
            }
        }
        if (anchorLine < 0) {
            //模型错配残片（题干与选项对不上）：短题干丢弃，长题干保留由预览人工核对
            //判断题豁免：判断题题干天然短（"判断：长江…（ ）"≈15-25 字符），且无选项溯源需求
            if (content.length() <= 30 && !"JUDGE".equals(q.getType())) {
                log.warn("题干回填丢弃（定位失败且题干过短）：content={}", truncate(content, 80));
                return false;
            }
            return true;
        }
        //③ 内容定位可能落在材料/题干行 → 向后找最近的题号行，统一锚到题号行
        int numLine = anchorLine;
        if (!QUESTION_NUMBER_ALONE.matcher(lines[anchorLine].trim()).matches()) {
            for (int j = anchorLine + 1; j < lines.length; j++) {
                if (QUESTION_NUMBER_ALONE.matcher(lines[j].trim()).matches()) {
                    numLine = j;
                    break;
                }
            }
        }
        //收集下界 = 文档第一个题号行（标题区"计算机基础题库（整理版）"不算材料）
        int firstNumLine = 0;
        for (int li = 0; li < lines.length; li++) {
            if (isQuestionNumberLine(lines[li].trim())) {
                firstNumLine = li;
                break;
            }
        }
        //仅"材料题"收集：content 定位在题号行之前（材料在题号行前）或裸题号（content 为"30."垃圾）才需要回填；
        //"题号+题干同行"格式（content 定位 = 题号行）content 已完整，不收集（防收集到标题/答案/解析行污染）
        boolean shouldCollect = numberAnchored || (contentLine >= 0 && contentLine < numLine);
        List<String> collected = new ArrayList<>();
        if (shouldCollect) {
            for (int i = numLine - 1; i >= firstNumLine; i--) {
                String t = lines[i].trim();
                if (t.isEmpty()) {
                    continue;
                }
                if (MATERIAL_STOP.matcher(t).find() || t.matches("^(答案|参考答案|解析)[:：].*")) {
                    break; //上一题题号/选项行、答案/解析行不是材料，停止（且不收集该行）
                }
                collected.add(t);
            }
        }
        //回填（收集到材料+题干时）；无材料 = 合法短题，保持原样。
        //定位已由"选项消歧/题号定位"保证可靠 → 直接回填（保真：材料/符号以下划线等以源文为准，
        //覆盖模型"省略材料/题干选项拼接/改写"等一切变体）
        if (!collected.isEmpty()) {
            java.util.Collections.reverse(collected);
            String backfill = String.join("\n", collected);
            if (backfill.length() >= 15) {
                q.setContent(backfill);
            }
        }
        //选项一致性校验（防模型把"块首孤儿题号+选项"错配给下一题 → 题干与选项来自不同题）：
        //按"题干归属题号"定位题号行 → 其后的选项区（1-5 行，含题号行本身以支持同行格式）
        //必须包含每个选项文本；不包含 → 模型错配/编造，丢弃。判断题跳过（选项为后端兜底）。
        //（不用"选项文本全局定位"：Q2/Q8 选项相同（键盘/鼠标…）时第一个匹配行会归属错题）
        //图片选项（[图片N]）无法在文本中验证 → 跳过该校验（图片题由预览确认把关）
        if (!"JUDGE".equals(q.getType()) && q.getOptions() != null && !q.getOptions().isEmpty()) {
            List<OptionItem> textOptions = new ArrayList<>();
            for (OptionItem o : q.getOptions()) {
                if (o.text() == null || o.text().isBlank() || stripImageRefs(o.text()).isBlank()) {
                    continue; //纯图片选项：跳过文本校验
                }
                textOptions.add(o);
            }
            if (textOptions.isEmpty()) {
                return true; //全部是图片选项：无法文本校验，直接保留
            }
            int contentNum = -1;
            if (contentLine >= 0) {
                contentNum = findQuestionNumber(lines, contentLine, true);
            }
            if (contentNum > 0) {
                int optAreaLine = findLineOf(lines, contentNum);
                if (optAreaLine >= 0) {
                    StringBuilder after = new StringBuilder();
                    for (int j = optAreaLine; j < Math.min(lines.length, optAreaLine + 5); j++) {
                        after.append(lines[j]).append('\n');
                    }
                    String afterText = after.toString();
                    for (OptionItem o : textOptions) {
                        String probe = o.text().length() > 6 ? o.text().substring(0, 6) : o.text();
                        if (!afterText.contains(probe)) {
                            log.warn("题干回填丢弃（选项不在本题选项区）：contentNum={} opt={} content={}",
                                    contentNum, probe, truncate(q.getContent(), 60));
                            return false;
                        }
                    }
                }
            } else {
                //题干归属未知（定位失败）→ 退回"选项行+归属题号"校验
                int optNum = -1;
                boolean mixed = false;
                for (OptionItem o : textOptions) {
                    String probe = o.text().length() > 6 ? o.text().substring(0, 6) : o.text();
                    Pattern optPat = Pattern.compile("[A-Da-d][.．、]\\s*" + Pattern.quote(probe));
                    int optLine = -1;
                    for (int li = 0; li < lines.length; li++) {
                        if (optPat.matcher(lines[li]).find()) {
                            optLine = li;
                            break;
                        }
                    }
                    if (optLine < 0) {
                        return false; //选项在源文找不到 → 模型编造，丢弃
                    }
                    int num = findQuestionNumber(lines, optLine, false);
                    if (num < 0) {
                        continue;
                    }
                    if (optNum < 0) {
                        optNum = num;
                    } else if (optNum != num) {
                        mixed = true;
                        break;
                    }
                }
                if (mixed) {
                    log.warn("题干回填丢弃（选项跨题）：content={}", truncate(q.getContent(), 80));
                    return false;
                }
            }
        }
        return true;
    }

    /**
     * 在源文行数组中定位 content 所在行：
     * ① content 首行（单行匹配）；② 去空白全文映射（content 跨行/含换行也能定位，返回材料开头所在行）。
     * 找不到返回 -1。
     */
    private int locateContentLine(String[] lines, String content) {
        String firstLine = content.split("\\R", 2)[0].trim();
        if (!firstLine.isEmpty()) {
            for (int i = 0; i < lines.length; i++) {
                if (lines[i].contains(firstLine)) {
                    return i;
                }
            }
        }
        //去空白全文映射
        StringBuilder norm = new StringBuilder();
        List<Integer> starts = new ArrayList<>();
        for (String line : lines) {
            starts.add(norm.length());
            norm.append(line.replaceAll("\\s+", ""));
        }
        String probe = content.replaceAll("\\s+", "");
        probe = probe.substring(0, Math.min(30, probe.length()));
        if (probe.isEmpty()) {
            return -1;
        }
        int pos = norm.indexOf(probe);
        if (pos >= 0) {
            for (int i = starts.size() - 1; i >= 0; i--) {
                if (starts.get(i) <= pos) {
                    return i;
                }
            }
        }
        return -1;
    }

    /**
     * 收集源文中本题的答案证据字母集合（阶段 2 补充答案复用：原文有证据 → 直接采用 ORIGINAL，不调模型）：
     * 1) 定位本题题号行 → 题号行后窗口内找答案标记（答案：X / 答：X / （X） / 【答案】X /（对）等）；
     * 2) 按题号查卷末答案列表（"N.X" / "【N题答案】X" / 区间式 "1-8：B D C…"）。
     * 题号优先取已回填的 questionNumber（分块路径可靠），定位失败时回退内容定位。
     * 判断题证据映射：对/√ → A，错/× → B。纯图片导入（sourceText 为 null）→ 返回空集合。
     */
    private Set<String> collectSourceAnswerEvidence(String sourceText, ContentPackageQuestion q) {
        Set<String> evidence = new HashSet<>();
        if (sourceText == null || sourceText.isBlank()) {
            return evidence;
        }
        String[] lines = sourceText.split("\\R", -1);
        int qNum = q.getQuestionNumber() == null ? -1 : q.getQuestionNumber();
        //1) 定位本题题号行（题干/材料行之后最近的题号行；放宽支持"1. 题干同行"格式）
        //   图片编号标记不在源文文本中，定位前剥离
        int contentLine = locateContentLine(lines, stripImageRefs(q.getContent() == null ? "" : q.getContent()));
        int numLine = -1;
        if (contentLine >= 0) {
            for (int li = contentLine; li < lines.length; li++) {
                String t = lines[li].trim();
                if (QUESTION_NUMBER_ALONE.matcher(t).matches()
                        || (t.matches("^\\d{1,3}\\s*[.．、)）].*") && !t.matches("^\\d{1,3}\\.\\d.*")
                        && !ANSWER_LINE.matcher(t).matches())) {
                    numLine = li;
                    break;
                }
            }
            //题号行窗口（含题号行本身——同行格式"1. 题干…答案：B"的答案标记在本行；
            //到下一个题号行前为止，防止判断题等短题窗口跨到相邻题的答案）
            if (numLine >= 0) {
                int winEnd = Math.min(lines.length, numLine + 10);
                for (int j = numLine + 1; j < winEnd; j++) {
                    if (isQuestionNumberLine(lines[j].trim())) {
                        winEnd = j;
                        break;
                    }
                }
                StringBuilder after = new StringBuilder();
                for (int j = numLine; j < winEnd; j++) {
                    after.append(lines[j]).append('\n');
                }
                collectEvidenceKeys(after.toString(), evidence);
            }
            //题号回退：内容定位成功但 questionNumber 未回填时从题号行解析
            if (qNum <= 0 && numLine >= 0) {
                Matcher nm = Pattern.compile("^(\\d{1,3})").matcher(lines[numLine].trim());
                if (nm.find()) {
                    qNum = Integer.parseInt(nm.group(1));
                }
            }
        }
        //2) 卷末答案列表（按题号；不依赖内容定位——MinerU 重建文本中题干可能带样式串无法定位）
        if (qNum > 0) {
            //卷末答案列表：N.X / 【N题答案】X / 区间式（"1-8：B D C C B A B D"）按题号对应
            for (String line : lines) {
                String letters = AiAnswerFormat.answerFor(line, qNum);
                if (letters != null) {
                    addAnswerChars(letters, evidence);
                }
                //紧凑单行答案列表（"答案：1.A 2.C 3.B" 同行）→ 行内题号映射
                Map<Integer, String> compactMap = AiAnswerFormat.compactAnswerMap(line);
                if (compactMap.containsKey(qNum)) {
                    addAnswerChars(compactMap.get(qNum), evidence);
                }
            }
        }
        return evidence;
    }

    /**
     * 校验源文中是否存在支持模型答案的明确证据（aiSupplement=false 防编造/错配答案）：
     * 模型 answerKeys 的每个字母必须在本题的证据字母集合中，否则视为编造/错配（返回 false 清空）。
     * 纯图片导入（sourceText 为 null）→ 放行。
     */
    private boolean hasSourceAnswerEvidence(String sourceText, ContentPackageQuestion q) {
        if (sourceText == null || sourceText.isBlank()) {
            return true;
        }
        Set<String> evidence = collectSourceAnswerEvidence(sourceText, q);
        //校验：模型答案每个字母都必须有本题证据
        if (q.getAnswerKeys() == null || q.getAnswerKeys().isEmpty()) {
            return true;
        }
        if (evidence.isEmpty()) {
            log.warn("答案证据校验：无证据 content={} ans={}", truncate(q.getContent(), 60), q.getAnswerKeys());
            return false;
        }
        for (String k : q.getAnswerKeys()) {
            if (!evidence.contains(k.toUpperCase())) {
                log.warn("答案证据校验：字母不符 evidence={} ans={} content={}", evidence, q.getAnswerKeys(),
                        truncate(q.getContent(), 60));
                return false; //模型答案与本题证据不符 → 编造/错配
            }
        }
        return true;
    }

    /** 从文本片段中收集答案证据字母（答案：X / 答：X / （X） / 【答案】X /（对）/ "答案：ABD" / "答案：正确"） */
    private void collectEvidenceKeys(String window, Set<String> evidence) {
        //"答案：ABD" 连续字母串（多选）与"答案：正确/错误"（判断题双字）
        Matcher multi = Pattern.compile("(?:答案|参考答案|正确答案)\\s*[:：]?\\s*[（(]?\\s*([A-Ha-h]{1,6}|正确|错误)").matcher(window);
        while (multi.find()) {
            addAnswerChars(multi.group(1), evidence);
        }
        Matcher m = ANSWER_MARKER.matcher(window);
        while (m.find()) {
            for (int g = 1; g <= m.groupCount(); g++) {
                if (m.group(g) != null) {
                    addAnswerChars(m.group(g), evidence);
                }
            }
        }
    }

    /** 答案码归一化并加入证据集合：A/a → A；对/√/正确 → A；错/×/错误 → B；"ABD" 逐个加入 */
    private void addAnswerChars(String s, Set<String> evidence) {
        if (s == null) {
            return;
        }
        if ("正确".equals(s) || "对".equals(s) || "√".equals(s)) {
            evidence.add("A");
            return;
        }
        if ("错误".equals(s) || "错".equals(s) || "×".equals(s)) {
            evidence.add("B");
            return;
        }
        for (char c : s.toUpperCase().toCharArray()) {
            String v = String.valueOf(c);
            if (v.matches("[A-H]")) {
                evidence.add(v);
            }
        }
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
    private AiParsedResult chatChunked(AiSettings settings, String systemPrompt, List<String> texts,
                                       String warning, AiImportJob job, Long jobId, boolean aiSupplement,
                                       List<String> pageTexts, List<DocumentParserService.ExtractedImage> extracted,
                                       boolean marksEmbedded, String evidenceText,
                                       List<AiClientService.ImageData> pageRenders) {
        //两阶段分离：整理阶段只提取题目结构（MD 模板输出，"答案/解析"留空），
        //答案与解析由返回前 postProcessAnswers 统一处理（原文证据恢复 + 思考模式补充）
        final String extractPrompt = buildMdExtractPrompt();
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
            String user = buildUserPrompt(merged, warning);
            String out;
            if (!extracted.isEmpty()) {
                user += pageRenders != null
                        ? buildPdfVisionImageRule(1, pageRenders.size(), pageRenders.size(), 1, extracted.size())
                        : buildImageRefRule(1, extracted.size());
                List<AiClientService.ImageData> all = new ArrayList<>();
                for (DocumentParserService.ExtractedImage e : extracted) {
                    all.add(e.image());
                }
                try {
                    out = aiClientService.chatWithImages(buildVisionSettings(settings), extractPrompt, user, all, true);
                } catch (Exception e) {
                    log.warn("AI 导入任务 {} 回退单次 vision 调用失败（{}），改用纯文本模型重试", jobId, e.getMessage());
                    out = aiClientService.chat(settings, extractPrompt, user, true);
                }
            } else {
                out = aiClientService.chat(settings, extractPrompt, user, true);
            }
            //回退单次路径同样补漏 + 答案后处理（与分块路径统一；带标记时补漏带图）
            AiParsedResult single = mdParseResult(out, aiSupplement);
            if (single.questions().isEmpty() && looksLikeJson(out)) {
                single = parseAndValidate(out, aiSupplement, fullSource, true, false);
            }
            if (extracted.isEmpty() || marksEmbedded || pageRenders != null) {
                single = new AiParsedResult(fillMissingQuestions(single.questions(), fullSource,
                        settings, extractPrompt, aiSupplement, jobId, extracted), single.materials());
            }
            assertNotDegraded(single.questions(), fullSource);
            return postProcessAnswers(single, aiSupplement, fullSource, evidenceSource, settings, jobId, extracted);
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
                    sb.append(buildImageRefRuleList(imageChunkNumbers(chunks.get(i))));
                } else if (pageRenders != null) {
                    //PDF 直传（模型看图主导）：整页截图 + 块内嵌图随消息提供，模型按截图判断归属并引用 [图片N]。
                    //测试验证（91-95 区）模型看图配图全对，含同题干图形题与跨页题；程序坐标仅作兜底（见 assignPdfFigureImages）
                    sb.append(buildPdfVisionImageRule(chunkFirstPage[i] + 1, chunkLastPage[i] + 1,
                            chunkPageImages.get(i).size(),
                            imageChunks.get(i).firstNum(), imageChunks.get(i).lastNum()));
                } else {
                    sb.append(buildImageRefRule(imageChunks.get(i).firstNum(), imageChunks.get(i).lastNum()));
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
                    out = aiClientService.chatWithImages(buildVisionSettings(settings), extractPrompt, prompts.get(idx), imgs, true);
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
                return new AiParsedResult(all, blockMaterials);
            }
            List<ContentPackageQuestion> parsed = new ArrayList<>();
            try {
                String out = futures.get(i).get(6, TimeUnit.MINUTES);
                //诊断日志：模型原始输出（定位缺题在模型层还是解析层）
                log.info("AI 导入任务 {} 块 {}/{} 模型原始输出（{} 字符）：{}",
                        jobId, i + 1, futures.size(), out.length(), truncate(out.replaceAll("\\R+", " | "), 1600));
                //Markdown 输出管线：确定性解析（题号取自标题）；材料块合并（跨块去重见末尾归一）
                AiParsedResult blockResult = mdParseResult(out, aiSupplement);
                if (blockResult.questions().isEmpty() && looksLikeJson(out)) {
                    //无思考重试 + json_object 响应格式 → 模型可能输出旧版 JSON 结构；本地兼容解析
                    blockResult = parseAndValidate(out, aiSupplement, fullSource, true, false);
                }
                parsed = blockResult.questions();
                mergeMaterials(blockMaterials, blockResult.materials());
            } catch (CancellationException e) {
                return new AiParsedResult(all, blockMaterials);
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
                        AiSettings rs = attempt == 1 ? withThinking(settings, false) : settings;
                        String retry;
                        if (attempt == 2 || blockImgs.isEmpty()) {
                            retry = aiClientService.chat(rs, extractPrompt, prompts.get(i), true);
                        } else {
                            retry = aiClientService.chatWithImages(buildVisionSettings(rs), extractPrompt, prompts.get(i), blockImgs, true);
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
            String norm = normalizeQuestion(q);
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
            String user = buildUserPrompt(merged, warning);
            String out;
            if (!extracted.isEmpty()) {
                user += pageRenders != null
                        ? buildPdfVisionImageRule(1, pageRenders.size(), pageRenders.size(), 1, extracted.size())
                        : buildImageRefRule(1, extracted.size());
                List<AiClientService.ImageData> allImgs = new ArrayList<>();
                for (DocumentParserService.ExtractedImage e : extracted) {
                    allImgs.add(e.image());
                }
                //回退单次：有图才 vision（同上）；vision 失败（端点降级）改纯文本模型重试一次
                try {
                    out = allImgs.isEmpty()
                            ? aiClientService.chat(settings, extractPrompt, user, true)
                            : aiClientService.chatWithImages(buildVisionSettings(settings), extractPrompt, user, allImgs, true);
                } catch (Exception e) {
                    log.warn("AI 导入任务 {} 回退单次 vision 调用失败（{}），改用纯文本模型重试", jobId, e.getMessage());
                    out = aiClientService.chat(settings, extractPrompt, user, true);
                }
            } else {
                out = aiClientService.chat(settings, extractPrompt, user, true);
            }
            AiParsedResult single = mdParseResult(out, aiSupplement);
            if (single.questions().isEmpty() && looksLikeJson(out)) {
                single = parseAndValidate(out, aiSupplement, fullSource, true, false);
            }
            //回退单次路径同样补漏（无图时本地路径；MinerU 路径带图补漏），模型可能漏短题（如判断题"（ ）"格式）
            if (extracted.isEmpty() || marksEmbedded || pageRenders != null) {
                single = new AiParsedResult(fillMissingQuestions(single.questions(), fullSource,
                        settings, extractPrompt, aiSupplement, jobId, extracted), single.materials());
            }
            assertNotDegraded(single.questions(), fullSource);
            return postProcessAnswers(single, aiSupplement, fullSource, evidenceSource, settings, jobId, extracted);
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
                int line = locateContentLine(lines, content);
                if (line >= 0) {
                    int num = findQuestionNumber(lines, line, true);
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
                if (existing == null || questionQuality(q) > questionQuality(existing)) {
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
        return postProcessAnswers(new AiParsedResult(unique, materials), aiSupplement, fullSource,
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

    /**
     * 两阶段分离的答案处理（整理完成后）：
     * 1) 清空整理阶段模型可能输出的答案/解析（extractOnly prompt 已禁止，这里兜底）——无思考答案不可信；
     * 2) 原文证据确定性恢复（恒执行，与 aiSupplement 无关）：源文有答案证据（题后"答案：X"、
     *    卷末答案列表、单独答案文件、教师版【N题答案】）→ 直接采用证据答案（ORIGINAL，零模型调用）；
     * 3) aiSupplement=true 且仍有缺答案题 → 思考模式分批补充（每题 10 题一批并行，强制 thinking）——
     *    思考只用在"真正需要生成"的步骤，整体等待时间远低于"整理时就思考"。
     *    不勾选 aiSupplement：原文答案已恢复，缺失题留空（预览页用户补）。
     * 主观题：原文证据 = 卷末【N题答案】分段参考答案；aiSupplement=true 时其余主观题思考补充 referenceAnswer。
     * evidenceSource：答案溯源文本（MinerU 路径 = 主文本 + 本地解析文本；其余 = 主文本）。
     */
    private AiParsedResult postProcessAnswers(AiParsedResult parsed, boolean aiSupplement, String fullSource,
                                              String evidenceSource, AiSettings settings, Long jobId,
                                              List<DocumentParserService.ExtractedImage> extracted) {
        List<ContentPackageQuestion> questions = parsed.questions();
        if (questions.isEmpty()) {
            return parsed;
        }
        //SUBJECTIVE 误判校验：选择题被输出为 SUBJECTIVE 时，仅当模型已给出 ≥2 个非空选项才强制转 SINGLE。
        //空选项一律保持 SUBJECTIVE——转成"空选项单选"会卡死确认导入（前端/后端都要求选项非空，实测"选项为空"报错）
        //学科卷主观句式扩充（实验题"填正确答案标号"/填空线/计算题"求…"），防止实验题被误转成选择题
        for (ContentPackageQuestion q : questions) {
            if (!"SUBJECTIVE".equals(q.getType())) {
                continue;
            }
            String c = q.getContent() == null ? "" : q.getContent();
            boolean subjectivePhrase = c.matches(".*(请简述|请论述|请说明|谈谈|说明理由|简述|论述|撰写|作答|请分析|填正确答案标号|（填|求|计算).*");
            boolean choicePhrase = c.matches(".*(哪个|哪项|以下|下列|多少|正确|错误|属于|能够|不能|选择|填入).*");
            boolean hasRealOptions = q.getOptions() != null && q.getOptions().size() >= 2
                    && q.getOptions().stream().allMatch(o -> o != null && o.text() != null && !o.text().isBlank());
            if (choicePhrase && !subjectivePhrase && hasRealOptions) {
                log.info("SUBJECTIVE 误判修正：{} → SINGLE（选项齐全）", truncate(c, 40));
                q.setType("SINGLE");
            }
        }
        //1) 清空整理阶段模型输出的答案/解析（统一由本方法处理）
        for (ContentPackageQuestion q : questions) {
            q.setAnswerKeys(List.of());
            q.setAnswerText(null);
            q.setAnalysis(null);
            q.setReferenceAnswer(null);
            q.setAnswerSource(null);
        }
        //2) 原文证据确定性恢复（独立于 aiSupplement：文档自带答案（卷末/题后/答案文件）是用户提供的事实，
        //   只要源文有证据就恢复为 ORIGINAL——即使不勾选"AI 补充"，原文答案也应正确填入；
        //   aiSupplement 只控制"原文缺失的题是否用思考模式补算"）
        int evidenceCount = 0;
        int recoveredSubjective = 0;
        for (ContentPackageQuestion q : questions) {
            if ("SUBJECTIVE".equals(q.getType())) {
                //主观题：从卷末【N题答案】恢复分段参考答案（教师版试卷常见"【13题答案】（1）…（2）…"）。
                //evidenceSource 优先（MinerU 路径含本地解析文本，答案行干净可靠）
                Integer qn = q.getQuestionNumber();
                if (qn == null) {
                    qn = locateNumberByContent(fullSource, q);
                }
                if (qn != null) {
                    String tail = AiAnswerFormat.bracketAnswerTail(evidenceSource, qn);
                    if (tail != null && !tail.isBlank()) {
                        q.setReferenceAnswer(tail);
                        q.setAnswerSource("ORIGINAL");
                        recoveredSubjective++;
                    }
                }
                continue;
            }
            if (q.getOptions() == null || q.getOptions().isEmpty()) {
                continue; //无选项：证据恢复不适用
            }
            Set<String> evidence = collectSourceAnswerEvidence(evidenceSource, q);
            if (evidence.isEmpty()) {
                continue;
            }
            List<String> optionKeys = q.getOptions().stream().map(OptionItem::key).map(String::toUpperCase).toList();
            List<String> keys = evidence.stream().filter(optionKeys::contains).distinct().sorted().toList();
            if (keys.isEmpty()) {
                continue;
            }
            q.setAnswerKeys(keys);
            q.setAnswerSource("ORIGINAL");
            evidenceCount++;
        }
        if (!aiSupplement) {
            log.info("AI 导入任务 {} 答案处理完成（未勾选 AI 补充）：{} 题原文证据恢复，{} 题主观题原文参考答案恢复，缺失留空",
                    jobId, evidenceCount, recoveredSubjective);
            return parsed; //不补充：缺失答案留空，预览页用户补
        }
        //3) 缺答案题（含全部主观题；已从卷末恢复参考答案的主观题跳过）→ 思考模式分批补充
        List<ContentPackageQuestion> missing = questions.stream()
                .filter(q -> q.getAnswerKeys() == null || q.getAnswerKeys().isEmpty())
                .filter(q -> !("SUBJECTIVE".equals(q.getType())
                        && q.getReferenceAnswer() != null && !q.getReferenceAnswer().isBlank()))
                .toList();
        if (missing.isEmpty()) {
            log.info("AI 导入任务 {} 答案处理完成：{} 题原文证据恢复，{} 题主观题参考答案恢复，无需 AI 补充",
                    jobId, evidenceCount, recoveredSubjective);
            return parsed;
        }
        supplementAnswers(missing, settings, jobId, extracted, parsed.materials());
        //4) 材料引用最终校验：materialKey 必须存在于最终 materials（本地截取可能只覆盖单材料组，
        //模型引用的其他 key 悬空会导致 confirm 失败"材料不存在"）→ 悬空引用清空（题目保留，预览可补）
        List<ContentPackageMaterial> finalMaterials = parsed.materials();
        if (finalMaterials != null && !finalMaterials.isEmpty()) {
            Set<String> keys = new HashSet<>();
            for (ContentPackageMaterial m : finalMaterials) {
                if (m.getMaterialKey() != null) {
                    keys.add(m.getMaterialKey());
                }
            }
            for (ContentPackageQuestion q : questions) {
                if (q.getMaterialKey() != null && !keys.contains(q.getMaterialKey())) {
                    log.warn("AI 导入任务 {} 题目引用材料 {} 但最终材料不存在，清空引用（预览可手动关联）",
                            jobId, q.getMaterialKey());
                    q.setMaterialKey(null);
                }
            }
        }
        log.info("AI 导入任务 {} 答案处理完成：{} 题原文证据恢复，{} 题主观题参考答案恢复，{} 题思考模式补充（含失败留空）",
                jobId, evidenceCount, recoveredSubjective, missing.size());
        //定界符统一：模型偶发输出 \(...\) / \[...\]（LaTeX 原生定界）——前端公式渲染只认 $...$ / $$...$$，
        //统一转写为 $ 定界（含 AI 补充的解析与原文恢复的参考答案）
        normalizeLatexInResult(parsed);
        return parsed;
    }

    /** 全结果（题干/选项/解析/参考答案/材料）LaTeX 定界符归一：\( → $、\) → $、\[ → $$、\] → $$ */
    private void normalizeLatexInResult(AiParsedResult parsed) {
        for (ContentPackageQuestion q : parsed.questions()) {
            q.setContent(normalizeLatexDelimiters(q.getContent()));
            if (q.getOptions() != null) {
                q.setOptions(q.getOptions().stream()
                        .map(o -> new OptionItem(o.key(), normalizeLatexDelimiters(o.text())))
                        .toList());
            }
            q.setAnalysis(normalizeLatexDelimiters(q.getAnalysis()));
            q.setReferenceAnswer(normalizeLatexDelimiters(q.getReferenceAnswer()));
            q.setAnswerText(normalizeLatexDelimiters(q.getAnswerText()));
        }
        if (parsed.materials() != null) {
            for (ContentPackageMaterial m : parsed.materials()) {
                m.setContent(normalizeLatexDelimiters(m.getContent()));
            }
        }
    }

    /** 单段定界符归一（先 \[ \] → $$，后 \( \) → $——$$ 内含 $，顺序不可反） */
    private String normalizeLatexDelimiters(String s) {
        if (s == null || s.isEmpty()) {
            return s;
        }
        return s.replace("\\[", "$$").replace("\\]", "$$")
                .replace("\\(", "$").replace("\\)", "$");
    }

    /**
     * 残留公式图 LaTeX 转写兜底（docx 公式路径）：模型整理阶段可能漏转公式图（实测非思考模式全残留、
     * 思考模式偶发残留大头针符号等）——本方法扫描题目 content/选项/参考答案中的 [图片N]，
     * 只处理"docx 解析标记为公式（WMF/EMF）"的编号（示意图/照片编号不在集合内 → 不动），
     * 一次思考调用批量转写 LaTeX 并回填。调用失败/转写缺失 → 保留原 [图片N]（预览可见，人工可补）。
     */
    private AiParsedResult fixResidualFormulaImages(AiParsedResult parsed, Set<Integer> formulaNos,
                                                    List<DocumentParserService.ExtractedImage> extracted,
                                                    AiSettings settings, Long jobId) {
        try {
            //收集残留公式图引用（题干/选项/参考答案/材料）与每张图的题目上下文
            Set<Integer> residual = new TreeSet<>();
            Map<Integer, String> ctxByRef = new HashMap<>();
            for (ContentPackageQuestion q : parsed.questions()) {
                String ctx = questionContext(q.getContent());
                collectResidualFormulaRefs(q.getContent(), formulaNos, residual, ctx, ctxByRef);
                if (q.getOptions() != null) {
                    for (OptionItem o : q.getOptions()) {
                        collectResidualFormulaRefs(o.text(), formulaNos, residual, ctx, ctxByRef);
                    }
                }
                collectResidualFormulaRefs(q.getReferenceAnswer(), formulaNos, residual, ctx, ctxByRef);
                collectResidualFormulaRefs(q.getAnswerText(), formulaNos, residual, ctx, ctxByRef);
            }
            if (parsed.materials() != null) {
                for (ContentPackageMaterial m : parsed.materials()) {
                    String ctx = questionContext(m.getContent());
                    collectResidualFormulaRefs(m.getContent(), formulaNos, residual, ctx, ctxByRef);
                }
            }
            if (residual.isEmpty()) {
                return parsed;
            }
            log.info("AI 导入任务 {} 残留公式图 {} 个（{}），逐张带上下文 LaTeX 转写兜底", jobId, residual.size(),
                    residual.stream().map(n -> "[图片" + n + "]").reduce("", (a, b) -> a + b));
            //逐张转写：一次一张 + 题目上下文 + 2x 放大（无上下文时模型对印刷公式乱猜，实测 98 图 "2.5 kPa"
            //被转成 "k0På"；带上下文与放大后转写准确）
            List<Integer> nums = new ArrayList<>(residual);
            AiSettings think = withThinking(settings, true);
            AiSettings vision = buildVisionSettings(think);
            Map<Integer, String> replacements = new HashMap<>();
            for (int n : nums) {
                if (n < 1 || n > extracted.size()) {
                    continue;
                }
                try {
                    String ctx = ctxByRef.getOrDefault(n, "");
                    String prompt = "题目背景：" + (ctx.isEmpty() ? "（该公式是某道题的官方答案）" : ctx)
                            + "\n下图中是该处对应的数学公式/数值答案，请逐字符完整转写为 LaTeX 文本"
                            + "（行内公式用 $...$ 包裹；图中每个数字、字母、单位、上下标都必须保留，禁止省略或简化，务必照图准确）；"
                            + "只输出 LaTeX，不要解释。";
                    byte[] img = upscale2x(extracted.get(n - 1).image().data());
                    String out = aiClientService.chatWithImages(vision,
                            "你是公式转写助手。", prompt,
                            List.of(new AiClientService.ImageData("image/png", img)), false);
                    String latex = out == null ? "" : out.trim();
                    //剥围栏，规范化 $ 包裹（模型可能输出 \(...\) 或裸 LaTeX）
                    latex = stripCodeFence(latex);
                    if (latex.startsWith("\\(") && latex.endsWith("\\)")) {
                        latex = "$" + latex.substring(2, latex.length() - 2).trim() + "$";
                    }
                    int d = latex.indexOf('$');
                    if (d >= 0) {
                        latex = latex.substring(d);
                        int e = latex.lastIndexOf('$');
                        if (e > d) {
                            latex = latex.substring(0, e + 1);
                        }
                    } else if (!latex.isEmpty()) {
                        latex = "$" + latex + "$";
                    }
                    if (latex.length() > 2 && latex.startsWith("$") && latex.endsWith("$")) {
                        replacements.put(n, latex);
                        log.info("AI 导入任务 {} 残留公式 [图片{}] 转写：{}", jobId, n, truncate(latex, 200));
                    } else {
                        log.warn("AI 导入任务 {} 残留公式 [图片{}] 转写无效（保留原图）：{}", jobId, n, truncate(out, 200));
                    }
                } catch (Exception e) {
                    log.warn("AI 导入任务 {} 残留公式 [图片{}] 转写失败（保留原图）：{}", jobId, n, e.getMessage());
                }
            }
            if (replacements.isEmpty()) {
                return parsed;
            }
            //回填（题干/选项/参考答案/材料）
            int replaced = 0;
            for (ContentPackageQuestion q : parsed.questions()) {
                replaced += replaceFormulaRefsIn(q::getContent, q::setContent, replacements);
                if (q.getOptions() != null && !q.getOptions().isEmpty()) {
                    List<OptionItem> newOpts = new ArrayList<>();
                    boolean changed = false;
                    for (OptionItem o : q.getOptions()) {
                        String nt = applyFormulaRefs(o.text(), replacements);
                        if (!nt.equals(o.text())) {
                            changed = true;
                        }
                        newOpts.add(new OptionItem(o.key(), nt));
                    }
                    if (changed) {
                        q.setOptions(newOpts);
                        replaced += replacements.size(); //计数近似（无法精确数每选项命中数）
                    }
                }
                replaced += replaceFormulaRefsIn(q::getReferenceAnswer, q::setReferenceAnswer, replacements);
                replaced += replaceFormulaRefsIn(q::getAnswerText, q::setAnswerText, replacements);
            }
            if (parsed.materials() != null) {
                for (ContentPackageMaterial m : parsed.materials()) {
                    replaced += replaceFormulaRefsIn(m::getContent, m::setContent, replacements);
                }
            }
            log.info("AI 导入任务 {} 公式转写回填完成：{} 处", jobId, replaced);
        } catch (Exception e) {
            log.warn("AI 导入任务 {} 残留公式转写兜底失败（保留原图标记）：{}", jobId, e.getMessage());
        }
        return parsed;
    }

    /** 题目上下文（转写参考）：题干前 120 字（去换行） */
    private String questionContext(String content) {
        if (content == null || content.isBlank()) {
            return "";
        }
        String flat = content.replaceAll("\\s+", " ").replaceAll("\\[图片\\d+\\]", " ").trim();
        return flat.length() > 120 ? flat.substring(0, 120) : flat;
    }

    /** 图片 2x 放大（BICUBIC，白底）——印刷公式小字号放大后模型识别显著改善 */
    private byte[] upscale2x(byte[] png) {
        try {
            BufferedImage img = ImageIO.read(new ByteArrayInputStream(png));
            if (img == null) {
                return png;
            }
            int w = img.getWidth() * 2;
            int h = img.getHeight() * 2;
            BufferedImage out = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
            java.awt.Graphics2D g = out.createGraphics();
            g.setColor(java.awt.Color.WHITE);
            g.fillRect(0, 0, w, h);
            g.setRenderingHint(java.awt.RenderingHints.KEY_INTERPOLATION,
                    java.awt.RenderingHints.VALUE_INTERPOLATION_BICUBIC);
            g.drawImage(img, 0, 0, w, h, null);
            g.dispose();
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            if (!ImageIO.write(out, "png", bos)) {
                return png;
            }
            return bos.toByteArray();
        } catch (Exception e) {
            return png;
        }
    }

    private void collectResidualFormulaRefs(String text, Set<Integer> formulaNos, Set<Integer> out,
                                            String ctx, Map<Integer, String> ctxByRef) {
        if (text == null || text.isEmpty()) {
            return;
        }
        Matcher m = IMAGE_REF.matcher(text);
        while (m.find()) {
            int n = Integer.parseInt(m.group(1));
            if (formulaNos.contains(n)) {
                out.add(n);
                if (ctx != null && !ctx.isBlank()) {
                    ctxByRef.putIfAbsent(n, ctx);
                }
            }
        }
    }

    /** 单段文本应用公式替换（命中任一编号返回替换后文本；未命中返回原文本） */
    private String applyFormulaRefs(String text, Map<Integer, String> replacements) {
        if (text == null || text.isEmpty() || replacements.isEmpty()) {
            return text;
        }
        String out = text;
        for (Map.Entry<Integer, String> e : replacements.entrySet()) {
            String mark = "[图片" + e.getKey() + "]";
            if (out.contains(mark)) {
                out = out.replaceAll("\\[图片" + e.getKey() + "]", Matcher.quoteReplacement(e.getValue()));
            }
        }
        return out;
    }

    /** 通用替换：getter 取文本 → 替换 [图片N] → setter 写回；返回命中替换数 */
    private int replaceFormulaRefsIn(java.util.function.Supplier<String> getter,
                                     java.util.function.Consumer<String> setter,
                                     Map<Integer, String> replacements) {
        String text = getter.get();
        if (text == null || text.isEmpty() || replacements.isEmpty()) {
            return 0;
        }
        String out = applyFormulaRefs(text, replacements);
        if (!out.equals(text)) {
            setter.accept(out);
            return 1;
        }
        return 0;
    }

    /** 按题干内容在源文定位题号（主观题卷末答案恢复用；定位失败返回 null） */
    private Integer locateNumberByContent(String fullSource, ContentPackageQuestion q) {
        if (fullSource == null || fullSource.isBlank() || q.getContent() == null || q.getContent().isBlank()) {
            return null;
        }
        String[] lines = fullSource.split("\\R", -1);
        int line = locateContentLine(lines, stripImageRefs(q.getContent()));
        if (line < 0) {
            return null;
        }
        int num = findQuestionNumber(lines, line, true);
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
                    ? aiClientService.chat(think, buildSystemPrompt(true, false), sb.toString(), true)
                    : aiClientService.chatWithImages(buildVisionSettings(think), buildSystemPrompt(true, false), sb.toString(), imgs, true);
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
            int line = locateContentLine(lines, content);
            if (line < 0) {
                continue;
            }
            int num = findQuestionNumber(lines, line, true); //向后找题号行
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
            if (seen.add(normalizeQuestion(q))) {
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
        int startLine = idx > 0 ? findLineOf(lines, sourceNums.get(idx - 1)) : Math.max(0, findLineOf(lines, n) - 3);
        //结束：下一题题号行（整题完整片段——多行计算题/实验题不再被 +4 行截断）；最后一题到文末
        int endLine = idx + 1 < sourceNums.size() ? findLineOf(lines, sourceNums.get(idx + 1)) : lines.length;
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
                AiSettings rs = attempt == 0 ? noThink : withThinking(settings, true);
                String out = imgs.isEmpty()
                        ? aiClientService.chat(rs, systemPrompt, user, true)
                        : aiClientService.chatWithImages(buildVisionSettings(rs), systemPrompt, user, imgs, true);
                log.info("AI 导入任务 {} 补漏第 {} 题原始输出（{} 字符）：{}",
                        jobId, n, out.length(), truncate(out.replaceAll("\\R+", " | "), 900));
                List<ContentPackageQuestion> parsed = mdParseQuestions(out, aiSupplement);
                //补漏结果必须对应目标题号：content 定位 → 题号 == n，否则丢弃。
                //（模型劣化时可能输出其他题的内容——"角误差+乱配选项"实测——校验只查源文存在性查不出）
                for (ContentPackageQuestion p : parsed) {
                    if (p.getContent() == null || p.getContent().isBlank()) {
                        continue;
                    }
                    int pLine = locateContentLine(lines, p.getContent().replaceAll("\\[图片\\d+]", "").trim());
                    int pNum = pLine >= 0 ? findQuestionNumber(lines, pLine, true) : -1;
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

    private int findLineOf(String[] lines, int num) {
        for (int i = 0; i < lines.length; i++) {
            String t = lines[i].trim();
            if (isQuestionNumberLine(t)) {
                Matcher m = Pattern.compile("^(\\d{1,3})").matcher(t);
                if (m.find() && Integer.parseInt(m.group(1)) == num) {
                    return i;
                }
            }
        }
        return 0;
    }

    /**
     * 从 startLine 开始查找题号归属：
     * forward=true 向后找（题干/材料在题号行之前）；forward=false 向前找（选项在题号行之后）。
     * 优先"孤立题号行"（粉笔 PDF 格式）；找不到时放宽为"行首题号"（"1. 题干同行"的 txt 格式），
     * 排除小数行（"15.8%"）与答案列表行（"1.A"）。找不到返回 -1。
     */
    /**
     * 从 startLine 开始查找题号归属：
     * forward=true 向后找（题干/材料在题号行之前）；forward=false 向前找（选项在题号行之后）。
     * 匹配"行首题号"（含孤立题号行"1."——".*"可匹配空），排除小数行（"15.8%"）与答案列表行（"1.A"）。
     * 注意：不做"孤立题号行优先"两遍扫描——实测 vlm 把题号拼在题干块末尾（"…评价。30."）时拆行产生孤立行，
     * "优先孤立行"会跳过近处的"题号+文字"行（"1.宽了…"）去命中远处孤立行（全部题定位成 30），
     * 单遍"最近行首题号"语义正确且覆盖孤立行。找不到返回 -1。
     */
    private int findQuestionNumber(String[] lines, int startLine, boolean forward) {
        int step = forward ? 1 : -1;
        int end = forward ? lines.length : -1;
        for (int li = startLine; li != end; li += step) {
            String t = lines[li].trim();
            String s = t.replaceAll("^(\\[图片\\d+])+", "").trim();
            if (s.matches("^\\d{1,3}\\s*[.．、)）].*") && !isDecimalLikeLine(s)
                    && !ANSWER_LINE.matcher(s).matches()) {
                Matcher m = Pattern.compile("^(\\d{1,3})").matcher(s);
                if (m.find()) {
                    return Integer.parseInt(m.group(1));
                }
            }
        }
        return -1;
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

    /** 题目质量分（视觉去重用：重叠块残版 vs 完整版；选项全 + 有答案 = 更完整） */
    private int questionQuality(ContentPackageQuestion q) {
        int score = q.getOptions() == null ? 0 : q.getOptions().size() * 10;
        if (q.getAnswerKeys() != null && !q.getAnswerKeys().isEmpty()) {
            score += 5;
        }
        if (q.getContent() != null && q.getContent().length() > 30) {
            score += 3;
        }
        return score;
    }

    /** 视觉路径坏题判定：残版（选项全部相同/全空）或粘连题（题干行尾残留题号、与下一题内容混合） */
    private static final Pattern GLUED_QUESTION = Pattern.compile("\\d{1,2}\\s*[.．、]\\s*(?:\\n|$)");
    /** 行尾孤立题号（"…规律性：2." 是本题题号被抄进题干行尾，剥除后保留题目；真粘连"…截面是：7.\n下一题…"由 isVisionJunk 判定过滤） */
    private static final Pattern TRAILING_QNO = Pattern.compile("\\d{1,2}\\s*[.．、]\\s*$");

    private boolean isVisionJunk(ContentPackageQuestion q) {
        //1. 残版：选项全部相同（非空且完全相同 → 真残版）。全空选项不视为残版：图形题的选项是图，
        //   模型无法输出文字 → 输出空壳（A./B./C./D. 或空），由 resolvePlaceholders 用图形块填充；
        //   误杀会导致图形题整题丢失、其图错插给相邻文字题
        if (q.getOptions() != null && q.getOptions().size() > 1) {
            String first = q.getOptions().get(0).text() == null ? "" : q.getOptions().get(0).text().trim();
            boolean allBlank = first.isEmpty();
            boolean allSame = true;
            for (OptionItem o : q.getOptions()) {
                String t = o.text() == null ? "" : o.text().trim();
                if (!t.isEmpty()) {
                    allBlank = false;
                }
                if (!t.equals(first)) {
                    allSame = false;
                    break;
                }
            }
            if (allSame && !allBlank) {
                log.info("isVisionJunk 过滤（选项全同）：{} | options={}", truncate(q.getContent() == null ? "" : q.getContent(), 30),
                        q.getOptions().stream().map(o -> o.text() == null ? "" : o.text()).collect(java.util.stream.Collectors.toList()));
                return true;
            }
        }
        //1b. 残版：客观题（非判断/主观）选项 < 3（跨页题的残版只有 1-2 个选项，如选项跨页的三明治法则题；
        //    完整版在同一题的其他块输出，重叠块保证）。选项是图的题选项数不受影响（如太师椅 4 个选项 key 齐全）
        if (!"JUDGE".equals(q.getType()) && !"SUBJECTIVE".equals(q.getType())
                && (q.getOptions() == null || q.getOptions().size() < 3)) {
            log.info("isVisionJunk 过滤（选项<3）：{} | options={}", truncate(q.getContent() == null ? "" : q.getContent(), 30),
                    q.getOptions() == null ? "null" : q.getOptions().stream().map(o -> o.text() == null ? "" : o.text()).collect(java.util.stream.Collectors.toList()));
            return true;
        }
        //2. 粘连：content 行尾残留题号（"…规律性：2." / "…截面是：7.\n某企业…" / "…切面？24.\n…"）。
        //   行尾孤立题号（"…规律性：2."）是"本题题号被模型抄进题干行尾"→ 剥除后保留（否则图形题被误杀、
        //   其图错插给相邻文字题）；题号后还有下一题内容（"…截面是：7.\n某企业…"）才是真粘连 → 过滤。
        String content = q.getContent() == null ? "" : q.getContent();
        String[] cl = content.split("\\R", -1);
        boolean stripped = false;
        for (int i = 0; i < cl.length; i++) {
            if (TRAILING_QNO.matcher(cl[i]).find()) {
                if (i == cl.length - 1 || cl[i + 1].isBlank()) {
                    cl[i] = TRAILING_QNO.matcher(cl[i]).replaceAll("");
                    stripped = true;
                } else {
                    log.info("isVisionJunk 过滤（真粘连）：{}", truncate(q.getContent() == null ? "" : q.getContent(), 50));
                    return true; // 题号后还有下一题内容 → 真粘连
                }
            }
        }
        if (stripped) {
            q.setContent(String.join("\n", cl));
            content = q.getContent();
        }
        if (GLUED_QUESTION.matcher(content).find()) {
            log.info("isVisionJunk 过滤（GLUED 残留）：{}", truncate(q.getContent() == null ? "" : q.getContent(), 50));
            return true;
        }
        return false;
    }

    /**
     * 清除视觉路径输出中越界/幻觉的图片编号引用（模型偶发引用本块编号范围外的 [图片N]，
     * 确认导入时会映射成其他页的图 → 错图；越界引用清除后该题图缺失，预览可见，优于错图）。
     */
    private void sanitizeImageRefs(List<ContentPackageQuestion> questions, int firstNum, int lastNum) {
        if (firstNum <= 0) {
            return;
        }
        for (ContentPackageQuestion q : questions) {
            if (q.getContent() != null) {
                q.setContent(stripOutOfRangeRefs(q.getContent(), firstNum, lastNum));
            }
            if (q.getOptions() != null) {
                List<OptionItem> fixed = new ArrayList<>();
                for (OptionItem o : q.getOptions()) {
                    fixed.add(new OptionItem(o.key(), o.text() == null ? null : stripOutOfRangeRefs(o.text(), firstNum, lastNum)));
                }
                q.setOptions(fixed);
            }
            if (q.getReferenceAnswer() != null) {
                q.setReferenceAnswer(stripOutOfRangeRefs(q.getReferenceAnswer(), firstNum, lastNum));
            }
        }
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
            int la = locateContentLine(lines, stripImageRefs(a.getContent() == null ? "" : a.getContent()));
            int lb = locateContentLine(lines, stripImageRefs(b.getContent() == null ? "" : b.getContent()));
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
    private AiParsedResult resolvePdfStemImages(AiParsedResult parsed, List<String> pageTexts,
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
                int line = locateContentLine(lines, textOnly);
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
            return new AiParsedResult(out, parsed.materials());
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

    private AiParsedResult assignPdfFigureImages(AiParsedResult parsed, List<String> pageTexts,
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
                int line = locateContentLine(lines, s2);
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
            return new AiParsedResult(out, parsed.materials());
        } catch (Exception e) {
            log.warn("AI 导入任务 {} PDF 图题归位失败：{}", jobId, e.getMessage());
            return parsed;
        }
    }

    /** 位置占位符：模型输出的【题干图片】【选项A图片】等（含"图片"二字即可匹配） */
    private static final Pattern PLACEHOLDER = Pattern.compile("【[^】]*图片[^】]*】");

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
    private AiParsedResult chatVisionSingle(AiSettings settings, List<String> pageTexts,
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
        String systemPrompt = buildVisionSingleSystemPrompt(aiSupplement);
        String userPrompt = buildVisionSingleUserPrompt(pageTexts, pages, warning);
        //3. 单次调用（thinking 跟随用户选择：默认无思考 48s；思考开启 124s 更稳）
        AiSettings vision = buildVisionSettings(settings);
        log.info("AI 导入任务 {} 视觉单次：{} 页 / {} 张整页图 / {} 张内嵌图（thinking={}）",
                jobId, pages, pageImages.size(), extracted.size(), settings.getThinking());
        String out = aiClientService.chatWithImages(vision, systemPrompt, userPrompt, pageImages, true);
        //4. 解析 + 残版/粘连过滤 + 去重择优（40/40 无粘连，防御保留）
        AiParsedResult parsed = parseAndValidate(out, aiSupplement, fullSource, true, true);
        List<ContentPackageQuestion> cleaned = new ArrayList<>();
        for (ContentPackageQuestion q : parsed.questions()) {
            if (!isVisionJunk(q)) {
                cleaned.add(q);
            }
        }
        Map<String, ContentPackageQuestion> byNorm = new LinkedHashMap<>();
        for (ContentPackageQuestion q : cleaned) {
            String norm = normalizeQuestion(q);
            ContentPackageQuestion existing = byNorm.get(norm);
            if (existing == null || questionQuality(q) > questionQuality(existing)) {
                byNorm.put(norm, q);
            }
        }
        List<ContentPackageQuestion> questions = new ArrayList<>(byNorm.values());
        if (questions.size() != parsed.questions().size()) {
            log.info("AI 导入任务 {} 视觉单次过滤去重：{} → {} 题", jobId, parsed.questions().size(), questions.size());
        }
        //5. 占位符 → 图片（确定性区域裁剪：题号行/选项行坐标 → 渲染图裁剪 + 白边裁剪；带内位图优先）
        questions = sortBySourceOrder(questions, fullSource);
        List<List<DocumentParserService.LinePos>> linePositions =
                documentParserService.collectLinePositions(Files.readAllBytes(jobDir.resolve("0-" + pdfFileName)));
        resolvePlaceholders(questions, pageTexts, extracted, analysisImages, linePositions, jobDir);
        return new AiParsedResult(questions, parsed.materials());
    }

    /** 占位符 → 图片匹配常量（实测校准：选项图区间 = [选项字母行 −15pt, 字母行 +55pt]，字母叠在图的上缘） */
    private static final float OPTION_IMG_OFFSET_PT = -15f;
    private static final float OPTION_IMG_HEIGHT_PT = 70f;
    private static final float STEM_IMG_MIN_PT = 24f;
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

    /** 题干带图片密度阈值（%）：图形题题干带 9-16%，纯文字 5-7% */
    private static final float STEM_IMAGE_DENSITY_THRESHOLD = 8f;
    /** 选项带行覆盖比例阈值（%）：同行文字选项 ~15%，图 ~60%+ */
    private static final float OPTION_COVERAGE_THRESHOLD = 40f;

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

    /** 视觉单次系统提示：简化（实测复杂 prompt 导致模型劣化/过度思考）。
     * 图片位置不由模型判断（实测模型把题干图误判为选项图）→ 后端按"题干带/选项带"像素与位图坐标分析自动插入。 */
    private String buildVisionSingleSystemPrompt(boolean aiSupplement) {
        String subjectiveRule = aiSupplement
                ? "主观题（无选项的作答类题目）输出 type=\"SUBJECTIVE\"，referenceAnswer 写参考答案（原文没有时由你生成）。"
                : "主观题（无选项的作答类题目）输出 type=\"SUBJECTIVE\"，referenceAnswer 写原文提供的参考答案；原文没有则留空，不要自行编写。";
        return """
                你是题库整理助手。把试卷整理为标准题目，严格输出一个 JSON 对象：{"questions":[...]}。
                每题字段：type("SINGLE"/"MULTIPLE"/"JUDGE"/"SUBJECTIVE")、content(题干)、
                options([{"key":"A","text":"..."}])、answerKeys(数组)、referenceAnswer(主观题可选)。
                规则：
                1. 题号位置与题目边界以页面截图的视觉排版为准（文本层的行顺序可能与视觉布局不一致，如题号出现在行尾）。
                2. 题干与选项文字以文本层为准，逐字保留，不要改写；图片内容不要转述，图片位置由系统自动处理，你无需标注。
                   例外：图片内容是数学公式/化学式时，直接转写为 LaTeX（$...$，如 $F=ma$）写进对应文本位置，不要跳过公式。
                3. 题干是纯图片（无文字）的题（如"从所给的四个选项中…"图形推理）：content 写文本层中的引导语（如"从所给的四个选项中，选择最合适的一个填入问号处，使之呈现一定的规律性："），
                   选项文字层有内容就写内容（如"A.①②⑥，③④⑤"），没有内容就写 "A."、"B."、"C."、"D."。
                4. 每道题都必须输出，包括图形推理题，禁止遗漏、禁止合并相邻题。
                5. 原文没有答案的题 answerKeys 返回空数组 []，不要编造。
                6. 共享材料题（多题共用大题干，如"材料一/资料分析"）输出顶层 "materials": [{"materialKey":"m1","content":"材料全文"}]，
                   题目加 "materialKey":"m1" 引用，content 只写问题部分；没有共享材料不要输出 materials。
                7. """ + subjectiveRule + """
                8. 只输出 JSON，不要任何其他文字。
                """;
    }

    /** 视觉单次用户提示：整页截图按页序 + 每页文本层 */
    private String buildVisionSingleUserPrompt(List<String> pageTexts, int pages, String warning) {
        StringBuilder sb = new StringBuilder();
        if (warning != null && !warning.isBlank()) {
            sb.append("注意：").append(warning).append('\n');
        }
        sb.append("以下是试卷的页面截图（按页顺序）与每页文本层。截图用于判断题号位置、题目边界、图形归属与跨页情况；")
                .append("文字一律以文本层为准（不要从截图重新识别文字）。\n\n");
        for (int p = 0; p < pages; p++) {
            sb.append("【第 ").append(p + 1).append(" 页文本层】\n").append(stripImageRefs(pageTexts.get(p))).append('\n');
        }
        return sb.toString();
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
    private AiParsedResult chatVisionPages(AiSettings settings, String systemPrompt,
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
        AiSettings vision = buildVisionSettings(settings);
        List<String> prompts = new ArrayList<>();
        List<List<AiClientService.ImageData>> blockImages = new ArrayList<>();
        List<int[]> blockNumRanges = new ArrayList<>(); //{firstNum, lastNum}（无内嵌图 = {0,0}）
        for (int[] block : blocks) {
            int start = block[0], end = block[1];
            String prompt = buildVisionPagePrompt(pageTexts, start, end, pages, warning);
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
                prompt += buildVisionImageRefRule(firstNum, lastNum);
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
                return new AiParsedResult(all, allMaterials);
            }
            AiParsedResult parsed = new AiParsedResult(List.of(), List.of());
            try {
                String out = futures.get(i).get(6, TimeUnit.MINUTES);
                parsed = parseAndValidate(out, aiSupplement, fullSource, true, true);
            } catch (CancellationException e) {
                return new AiParsedResult(all, allMaterials);
            } catch (Exception e) {
                //块调用失败（网络/限流/非法 JSON）→ 不整体失败，走重试
                log.warn("AI 导入任务 {} 第 {}/{} 块（视觉）调用失败（{}），自动重试该块", jobId, i + 1, futures.size(), e.getMessage());
            }
            if (parsed.questions().size() < 5 && !isCanceled(jobId)) {
                try {
                    String retry = aiClientService.chatWithImages(vision, systemPrompt, prompts.get(i), blockImages.get(i), true);
                    AiParsedResult retryParsed = parseAndValidate(retry, aiSupplement, fullSource, true, true);
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
            sanitizeImageRefs(parsed.questions(), range[0], range[1]);
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
            if (isVisionJunk(q)) {
                continue;
            }
            cleaned.add(q);
        }
        //6. 去重：重叠块可能重复产出同一题（题干+选项规范化后一致）；同题多版本优先保留质量高的
        //   （块边界残版选项少/无答案，完整版选项全/有答案；另防同题干不同选项的题误删，如两题题干同为
        //   "把下面的六个图形分为两类…"但选项不同）
        Map<String, ContentPackageQuestion> byNorm = new LinkedHashMap<>();
        for (ContentPackageQuestion q : cleaned) {
            String norm = normalizeQuestion(q);
            ContentPackageQuestion existing = byNorm.get(norm);
            if (existing == null || questionQuality(q) > questionQuality(existing)) {
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
            String user = buildUserPrompt(fullSource, warning);
            if (!extracted.isEmpty()) {
                user += buildImageRefRule(1, extracted.size());
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
        unique = visionFillMissingPages(unique, allMaterials, matKeys, pageTexts, pageImages, extracted,
                fullSource, warning, vision, systemPrompt, aiSupplement, job, jobId);
        //8. 按源文位置排序（还原文档顺序；补漏题归位，不再追加在末尾）
        unique = sortBySourceOrder(unique, fullSource);
        return new AiParsedResult(unique, allMaterials);
    }

    /**
     * 页级视觉补漏：模型漏掉整页中部分题目（典型：图形推理题题干/选项全在图片，文本层只有引导语）
     * 时，按"每页宽松参考题数 vs 实际输出题数"定位缺失页，单页重跑一次视觉调用。
     * 补漏结果与主流程同样过滤/去重后合并（质量择优）。
     */
    private List<ContentPackageQuestion> visionFillMissingPages(List<ContentPackageQuestion> unique,
            List<ContentPackageMaterial> allMaterials, Set<String> matKeys, List<String> pageTexts,
            List<AiClientService.ImageData> pageImages, List<DocumentParserService.ExtractedImage> extracted,
            String fullSource, String warning, AiSettings vision, String systemPrompt, boolean aiSupplement,
            AiImportJob job, Long jobId) {
        //行 → 字符偏移 → 页 映射（fullSource 单文件 = 页文本拼接，行结构一致）
        String[] srcLines = fullSource.split("\\R", -1);
        int[] lineStarts = new int[srcLines.length];
        int acc = 0;
        for (int i = 0; i < srcLines.length; i++) {
            lineStarts[i] = acc;
            acc += srcLines[i].length() + 1;
        }
        int[] pageStarts = new int[pageTexts.size() + 1];
        acc = 0;
        for (int i = 0; i < pageTexts.size(); i++) {
            pageStarts[i] = acc;
            acc += pageTexts.get(i).length();
        }
        pageStarts[pageTexts.size()] = acc;
        //每页参考题数（宽松统计：行首 + 行尾题号）
        int[] refPerPage = new int[pageTexts.size()];
        for (int p = 0; p < pageTexts.size(); p++) {
            refPerPage[p] = looseQuestionCount(pageTexts.get(p));
        }
        //每页实际输出题数（content 定位源文行 → 页）
        int[] gotPerPage = new int[pageTexts.size()];
        for (ContentPackageQuestion q : unique) {
            int line = locateContentLine(srcLines, stripImageRefs(q.getContent() == null ? "" : q.getContent()));
            if (line >= 0) {
                int page = pageIndexOf(pageStarts, lineStarts[line]);
                if (page >= 0 && page < gotPerPage.length) {
                    gotPerPage[page]++;
                }
            }
        }
        //缺题页：参考 ≥ 2 且实际 < 参考（至少缺 1 题）
        List<Integer> missingPages = new ArrayList<>();
        for (int p = 0; p < pageTexts.size(); p++) {
            if (refPerPage[p] >= 2 && gotPerPage[p] < refPerPage[p]) {
                missingPages.add(p);
            }
        }
        if (missingPages.isEmpty()) {
            return unique;
        }
        log.info("AI 导入任务 {} 视觉补漏：页 {} 参考题数 {} vs 实际 {}，缺题，单页重跑", jobId,
                missingPages.stream().map(p -> String.valueOf(p + 1)).collect(java.util.stream.Collectors.joining(",")),
                java.util.Arrays.toString(missingPages.stream().mapToInt(p -> refPerPage[p]).toArray()),
                java.util.Arrays.toString(missingPages.stream().mapToInt(p -> gotPerPage[p]).toArray()));
        //补漏调用：单页图 + 单页文本 + 本页内嵌图编号（串行，避免并发劣化；最多补 4 页防循环）
        List<ContentPackageQuestion> fillAll = new ArrayList<>();
        int filled = 0;
        for (int p : missingPages) {
            if (filled >= 4 || isCanceled(jobId)) {
                break;
            }
            String prompt = buildVisionPagePrompt(pageTexts, p, p + 1, pageTexts.size(), warning)
                    + "\n该页在上一轮整理中有题目缺失（可能因题目内容全在图片中），请重新识别本页【全部】题目："
                    + "包括图形推理题（题干/选项是图片的题，在对应位置写 [图片N] 标记），一题不漏。";
            List<AiClientService.ImageData> imgs = new ArrayList<>(pageImages.subList(p, p + 1));
            int firstNum = 0, lastNum = 0;
            for (int idx = 0; idx < extracted.size(); idx++) {
                DocumentParserService.ExtractedImage e = extracted.get(idx);
                if (e.pageNo() == p) {
                    if (firstNum == 0) {
                        firstNum = idx + 1;
                    }
                    lastNum = idx + 1;
                    imgs.add(e.image());
                }
            }
            if (firstNum > 0) {
                prompt += buildVisionImageRefRule(firstNum, lastNum);
            }
            try {
                String out = aiClientService.chatWithImages(vision, systemPrompt, prompt, imgs, true);
                AiParsedResult parsed = parseAndValidate(out, aiSupplement, fullSource, true, true);
                //越界图片编号清除（防错图）
                sanitizeImageRefs(parsed.questions(), firstNum, lastNum);
                for (ContentPackageQuestion q : parsed.questions()) {
                    if (!isVisionJunk(q)) {
                        fillAll.add(q);
                    }
                }
                for (ContentPackageMaterial m : parsed.materials()) {
                    if (m.getMaterialKey() != null && !m.getMaterialKey().isBlank() && matKeys.add(m.getMaterialKey())) {
                        allMaterials.add(m);
                    }
                }
                log.info("AI 导入任务 {} 视觉补漏第 {} 页：解析 {} 题", jobId, p + 1, parsed.questions().size());
            } catch (Exception e) {
                log.warn("AI 导入任务 {} 视觉补漏第 {} 页失败：{}", jobId, p + 1, e.getMessage());
            }
            filled++;
        }
        //合并（过滤去重已对补漏结果执行；与主结果按质量择优合并）
        Map<String, ContentPackageQuestion> byNorm = new LinkedHashMap<>();
        for (ContentPackageQuestion q : unique) {
            byNorm.put(normalizeQuestion(q), q);
        }
        for (ContentPackageQuestion q : fillAll) {
            String norm = normalizeQuestion(q);
            ContentPackageQuestion existing = byNorm.get(norm);
            if (existing == null || questionQuality(q) > questionQuality(existing)) {
                byNorm.put(norm, q);
            }
        }
        List<ContentPackageQuestion> merged = new ArrayList<>(byNorm.values());
        if (merged.size() > unique.size()) {
            log.info("AI 导入任务 {} 视觉补漏合并：{} → {} 题", jobId, unique.size(), merged.size());
        }
        return merged;
    }

    /**
     * 视觉分块 prompt：块内页文本层（文字精确、题号可能行尾）+ 整页截图说明（版式真相、题号归位）。
     * 关键约束：文字以文本层为准（防 OCR 误差）；题号以截图视觉位置为准（行尾题号归位）；
     * 块边界不完整题目跳过（重叠块兜底）；文本层中的 [图片N] 标记剥离（近似位置无意义，插图位置看截图）。
     */
    private String buildVisionPagePrompt(List<String> pageTexts, int start, int end, int totalPages, String warning) {
        StringBuilder sb = new StringBuilder();
        if (warning != null && !warning.isBlank()) {
            sb.append("注意：").append(warning).append('\n');
        }
        sb.append("以下是试卷第 ").append(start + 1).append('-').append(end)
                .append(" 页（共 ").append(totalPages).append(" 页）的内容。\n\n");
        sb.append("【文本层】（PDF 精确提取，文字准确，请逐字采用；但行顺序可能与视觉布局不一致：")
                .append("题号有时出现在行尾（如“…规律性：2.”里的 2 其实是第 2 题的题号，排版上位于题干左侧）")
                .append("——请以截图为准归位）\n");
        for (int p = start; p < end; p++) {
            sb.append("【第 ").append(p + 1).append(" 页】\n").append(stripImageRefs(pageTexts.get(p))).append('\n');
        }
        sb.append("\n【页面截图】已随本消息按页顺序提供（第 ").append(start + 1).append('-').append(end)
                .append(" 页截图）：截图仅用于判断题号位置、题目边界、图形/选项的视觉归属与跨页情况；")
                .append("**不要从截图重新识别文字**，题干与选项文字一律以文本层为准（避免 OCR 误差）；")
                .append("插图/图形的位置以截图为准。\n");
        sb.append("本部分开头/结尾不完整的题目（题干或选项超出本部分范围）跳过不输出，不要补写；")
                .append("完整题目必须全部输出，禁止遗漏。\n");
        return sb.toString();
    }

    /**
     * 视觉路径图片引用规则：与 buildImageRefRule 同构，但删除"文本中已插入 [图片N] 标记"的说法
     * （视觉路径文本层已剥离标记，插图位置看截图；编号 = 内嵌图全局顺序，与消息中图片顺序一致）。
     */
    private String buildVisionImageRefRule(int first, int last) {
        int count = last - first + 1;
        return """

                【图片引用规则】本部分共 %d 张插图，编号为 [图片%d]~[图片%d]（已随本消息按编号顺序提供，编号即图片顺序）。
                插图的视觉位置请对照页面截图判断（截图中的图形即插图所在位置）。
                当题干、选项或共享材料是图片（或含图片）时，必须在对应文本位置写入 [图片N] 标记：
                - 题干有图：content = 完整题干文字 + [图片N]；题干只有图没有文字时，content 只写 [图片N]
                - 选项是图：该选项 text 只写 [图片N]；文字与图混合：文字 + [图片N]
                - 共享材料有图：material.content 中写 [图片N]
                禁止编造编号：只引用实际提供的 [图片%d]~[图片%d]；无法确定图片归属时宁可少引用；
                每个选项通常对应不同的图片，禁止把多个选项写成同一个编号。
                公式图（内容是数学公式/化学式的 [图片N]）禁止引用：题干与选项中的公式一律转写为 $...$ LaTeX 文本，
                禁止把公式图编号写进 content 或选项 text；[图片N] 只用于照片、几何图形、曲线图等非公式内容。
                """.formatted(count, first, last, first, last);
    }

    /**
     * 宽松参考题数（仅用于差异检测，不拆分文本）：
     * 行首题号（现有 isQuestionNumberLine）+ 行尾题号（"题号+题干同行"格式，如"…规律性：2."、"火炉：蒲扇10."）。
     * 行尾限定：行尾 1-2 位数字 + 半角/全角点/顿号/右括号；排除孤立题号行（已计）与小数结尾（"15.8" 结尾非点号）。
     * 第一个"一、…"章节标题行之前的题号不计（试卷"注意事项 1．2．3．"是说明区不是题，高考卷常见）。
     * 参考值允许少量误计（如"增长15."），差异检测阈值（0.6）留有裕量。
     */
    private int looseQuestionCount(String text) {
        if (text == null || text.isBlank()) {
            return 0;
        }
        Pattern tailNum = Pattern.compile("\\d{1,2}\\s*[.．、)]\\s*$");
        String[] lines = text.split("\\R");
        int count = 0;
        int from = firstSectionHeaderLine(lines);
        for (int i = from; i < lines.length; i++) {
            String t = lines[i].trim();
            if (t.isEmpty()) {
                continue;
            }
            if (isQuestionNumberLine(t)) {
                count++;
            } else if (!t.matches("^\\d{1,3}\\s*[.．、)）]\\s*$") && tailNum.matcher(t).find()
                    && !ANSWER_LINE.matcher(t).matches()) {
                count++;
            }
        }
        return count;
    }

    /**
     * 参考题数（题数差异检测用）：取"宽松统计"与"最长连续递增题号段"的较大值。
     * 前者对双栏/乱序题号宽容，后者对说明区噪声免疫；两者互补。
     */
    private int referenceQuestionCount(String text) {
        int loose = looseQuestionCount(text);
        int run = 0;
        if (text != null && !text.isBlank()) {
            run = detectQuestionBoundaries(text).size();
        }
        return Math.max(loose, run);
    }

    /** 第一个章节标题行（"一、选择题…"）；找不到返回 0。说明区题号（注意事项）从题号统计中排除 */
    private int firstSectionHeaderLine(String[] lines) {
        for (int i = 0; i < lines.length; i++) {
            String t = lines[i].trim();
            if (t.matches("^[一二三四五六七八九十]+[、.．]\\s*.*")) {
                return i;
            }
        }
        return 0;
    }

    /**
     * "纯文本候选"判定（AUTO 引擎：纯文本走本地，避免 MinerU 的 OCR 噪声/云端开销）：
     * 无内嵌图 + 文本量足（每页平均 ≥100 字符） + 文本层题号充分（≥3）。
     * 有图（常识判断 12 图承载 27 题）/文本不足（扫描件）/无题号（论文类）→ 非纯文本 → 走 MinerU。
     * 注意：不做 pageTexts 检查（docx 无页概念 pageTexts 为空会误判非纯文本——实测 docx 被误走 MinerU 只出 2/8 题）。
     */
    private boolean plainTextCandidate(DocumentParserService.ParseResult r) {
        if (r == null || r.text() == null || r.text().isBlank()) {
            return false;
        }
        if (r.extractedImages() != null && !r.extractedImages().isEmpty()) {
            return false; //有内嵌图 → 复杂文档
        }
        int pages = Math.max(1, r.pageTexts() == null ? 1 : r.pageTexts().size());
        if (r.text().length() < pages * 100L) {
            return false; //文本量不足（扫描件/图片型）
        }
        return looseQuestionCount(r.text()) >= 3; //题号充分（分块/回填依赖题号边界）
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
        List<Integer> bounds = detectQuestionBoundaries(text);
        log.info("分块边界检测：{} 个题号边界（chunkTarget={}）", bounds.size(), chunkTarget);
        if (bounds.size() < 3) {
            return List.of(new int[]{0, text.length()});
        }
        int chunkCount = Math.min(CHUNK_MAX, Math.max(1, (bounds.size() + chunkTarget - 1) / chunkTarget));
        int perChunk = (bounds.size() + chunkCount - 1) / chunkCount;
        List<int[]> ranges = new ArrayList<>();
        for (int c = 0; c < chunkCount; c++) {
            int startIdx = c * perChunk;
            int endIdx = Math.min(bounds.size(), (c + 1) * perChunk);
            //重叠一块：从上一块的最后一道题开始切（双栏 PDF 提取顺序中，题的"材料+题干"可能紧贴上一题的
            //选项行，从上一块末题开始可保证本块首题完整）；随后修剪块首/块尾"孤儿"
            //（上一题残留的"题号+选项"、下一题的"材料+题干"），消除模型把它们错配给相邻题的诱因
            int start = (startIdx == 0) ? 0 : bounds.get(startIdx - 1);
            int end = (endIdx >= bounds.size()) ? text.length() : bounds.get(endIdx);
            ranges.add(new int[]{start, end});
        }
        return ranges;
    }

    /**
     * 修剪块首/块尾孤儿（重叠切块的残留）：
     * - 块首：上一题残留的"题号行 + 选项行"（模型会把它错配给本块首题 → 题干选项跨题、无法定答案）；
     * - 块尾：下一题的"材料+题干"（无题号选项）。
     * 修剪后每块只含完整题目序列；孤儿在相邻块中是完整题，不丢失。
     */
    private String trimOrphans(String chunk, boolean trimHead, boolean trimTail) {
        String[] lines = chunk.split("\\R", -1);
        int start = 0;
        int end = lines.length;
        if (trimHead) {
            //跳过块首的"上一题残留"（题号行 + 选项/答案/解析行），直到本块第一个题号行。
            //支持孤立题号行（粉笔 "10." 单独成行）与"题号+题干同行"（txt "10. 题干…"）。
            boolean seenNumber = false;
            while (start < end) {
                String t = lines[start].trim();
                if (t.isEmpty()) {
                    start++;
                    continue;
                }
                if (isQuestionNumberLine(t) && !seenNumber) {
                    seenNumber = true; //第一个题号行 = 上一题残留 → 跳过
                    start++;
                    continue;
                }
                if (seenNumber && (t.matches("^[A-Da-d][.．、].*")
                        || t.matches("^(答案|参考答案|解析)[:：]?.*"))) {
                    start++; //上一题的选项/答案/解析行 → 跳过
                    continue;
                }
                break; //遇到本块首题（题号行或材料）→ 停止
            }
        }
        if (trimTail) {
            //从尾往前：只删除"下一题残留"（材料/题干行）；选项行、题号行、答案行、解析行一律保留。
            //（旧实现把选项行也 end-- 删除，导致每块最后一题缺选项，靠补漏兜底才没暴露）
            while (end > start) {
                String t = lines[end - 1].trim();
                if (t.isEmpty()) {
                    end--;
                    continue;
                }
                if (t.matches("^[A-Da-d][.．、].*")) {
                    break; //选项行 → 保留，停止
                }
                if (QUESTION_NUMBER_ALONE.matcher(t).matches() || t.matches("^\\d{1,3}\\s*[.．、)）].*")) {
                    break; //题号行（孤立或"题号+题干"同行）→ 保留，停止
                }
                if (t.matches("^(答案|参考答案|解析)[:：]?.*")) {
                    break; //答案/解析行（txt 格式块尾正常内容）→ 保留，停止
                }
                if (t.contains("[图片") && t.endsWith("]")) {
                    break; //图片标记行（"[图片21][图片22]"）→ 保留：图标记紧跟所属题干，
                    //跨页题的题干在页尾、图标记在页首（切块重叠时会被误当"下一题材料"删除 → 末题丢图，实测 Q14）
                }
                end--; //其余（下一题材料/题干=尾孤儿）→ 删除
            }
        }
        return String.join("\n", java.util.Arrays.copyOfRange(lines, start, end));
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
        if (t.matches("^\\d{1,3}\\.\\d{4}(\\s*[-—~～]\\s*\\d{4})?\\s*年.*")) {
            return false; //题号+年份（"1.2020年" / "13.2019-2021年"）
        }
        return t.matches("^\\d{1,3}\\.\\d.*");
    }

    /**
     * 行首题号行：孤立题号行（"10."）或"题号+题干同行"（"10. 题干…"），排除小数行与答案列表行。
     * 行首允许 [图片N] 标记前缀（MinerU 重建文本中材料图表标记可能与题号同行："[图片1][图片2]6.2012年…"）。
     */
    private boolean isQuestionNumberLine(String t) {
        if (QUESTION_NUMBER_ALONE.matcher(t).matches()) {
            return true;
        }
        String s = t.replaceAll("^(\\[图片\\d+])+", "").trim();
        if (s.isEmpty()) {
            return false;
        }
        return s.matches("^\\d{1,3}\\s*[.．、)）].*") && !isDecimalLikeLine(s)
                && !ANSWER_LINE.matcher(s).matches();
    }

    /**
     * 检测题号边界：行首 "1." / "2、" 等，取最长的连续递增（允许跳号）序列。
     * 排除答案列表行（"1.B" 这类紧凑格式），避免把卷末答案误当题号。
     *
     * @return 边界行起始位置列表（按文档顺序），不足 3 个返回空
     */
    private List<Integer> detectQuestionBoundaries(String text) {
        List<int[]> candidates = new ArrayList<>(); // {number, start}
        Matcher m = QUESTION_START.matcher(text);
        while (m.find()) {
            int lineEnd = text.indexOf('\n', m.start());
            String line = text.substring(m.start(), lineEnd < 0 ? text.length() : lineEnd).trim();
            if (ANSWER_LINE.matcher(line).matches()) {
                continue; //答案列表行不算题号
            }
            candidates.add(new int[]{Integer.parseInt(m.group(1)), m.start()});
        }
        if (candidates.size() < 3) {
            return List.of();
        }
        //最长连续递增（+1，允许跳号）序列：扫描每个候选作起点，取最长
        List<int[]> best = List.of();
        for (int s = 0; s < candidates.size(); s++) {
            List<int[]> run = new ArrayList<>();
            int expect = candidates.get(s)[0];
            for (int i = s; i < candidates.size(); i++) {
                int num = candidates.get(i)[0];
                if (num == expect) {
                    run.add(candidates.get(i));
                    expect++;
                } else if (num > expect) {
                    //跳号（题目可能缺号）：接受并继续
                    expect = num + 1;
                    run.add(candidates.get(i));
                }
            }
            if (run.size() > best.size()) {
                best = run;
            }
        }
        if (best.size() < 3) {
            return List.of();
        }
        List<Integer> positions = new ArrayList<>();
        for (int[] c : best) {
            positions.add(c[1]);
        }
        return positions;
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
        if (tailCandidate == null || tailCandidate.isBlank()) {
            return -1;
        }
        Matcher lm = Pattern.compile("(?m)^.*$").matcher(tailCandidate);
        while (lm.find()) {
            String l = lm.group().trim();
            if (ANSWER_LINE.matcher(l).matches()
                    || AiAnswerFormat.RANGE_ANSWER.matcher(l).matches()
                    || l.matches("^(答案|参考答案|正确答案)[:：]?$")) {
                return lm.start();
            }
        }
        return -1;
    }

    /**
     * 图片配额二次拆块（marksEmbedded 路径）：块内唯一图片数超过 MAX_CHUNK_IMAGES 时，
     * 按内部题号边界切分（题号行是该题的起点，图片跟随其后的文本），直到配额满足或新增块数用尽（CHUNK_MAX）。
     * 不可拆（无内部边界/预算耗尽）保持原块。
     */
    private List<int[]> splitRangesByImageQuota(String text, List<int[]> ranges) {
        List<int[]> out = new ArrayList<>();
        int budget = CHUNK_MAX - ranges.size(); //可新增块数（并行上限）
        for (int[] r : ranges) {
            if (budget <= 0) {
                out.add(r);
                continue;
            }
            String seg = text.substring(r[0], r[1]);
            Set<String> nums = new HashSet<>();
            Matcher m = IMAGE_REF.matcher(seg);
            while (m.find()) {
                nums.add(m.group(1));
            }
            if (nums.size() <= MAX_CHUNK_IMAGES) {
                out.add(r);
                continue;
            }
            List<Integer> inner = new ArrayList<>();
            for (int b : detectQuestionBoundaries(seg)) {
                if (b > 0) {
                    inner.add(b);
                }
            }
            if (inner.isEmpty()) {
                out.add(r);
                continue;
            }
            //贪心切分：累计块内图片数超配额即在下个题号前切开
            List<Integer> cuts = new ArrayList<>();
            int lastCut = 0;
            Set<String> acc = new HashSet<>();
            for (int b : inner) {
                Matcher mm = IMAGE_REF.matcher(seg.substring(lastCut, b));
                while (mm.find()) {
                    acc.add(mm.group(1));
                }
                if (acc.size() > MAX_CHUNK_IMAGES && b > lastCut) {
                    cuts.add(b);
                    lastCut = b;
                    acc.clear();
                }
            }
            if (cuts.isEmpty()) {
                out.add(r);
                continue;
            }
            int prev = 0;
            for (int c : cuts) {
                if (budget <= 0) {
                    break;
                }
                out.add(new int[]{r[0] + prev, r[0] + c});
                prev = c;
                budget--;
            }
            out.add(new int[]{r[0] + prev, r[1]});
        }
        return out;
    }

    // ==================== Prompt 构建 ====================

    /** 多模态模型配置（visionModel 缺省 = model） */
    private AiSettings buildVisionSettings(AiSettings settings) {
        AiSettings vision = new AiSettings();
        vision.setBaseUrl(settings.getBaseUrl());
        vision.setApiKey(settings.getApiKey());
        vision.setModel(settings.getVisionModel() != null && !settings.getVisionModel().isBlank()
                ? settings.getVisionModel() : settings.getModel());
        vision.setThinking(settings.getThinking());
        return vision;
    }

    /** 复制连接配置并强制思考开关（重试切换思考/无思考用——端点偶发空白内容时交替重试可恢复） */
    private AiSettings withThinking(AiSettings base, boolean thinking) {
        AiSettings s = new AiSettings();
        s.setBaseUrl(base.getBaseUrl());
        s.setApiKey(base.getApiKey());
        s.setModel(base.getModel());
        s.setVisionModel(base.getVisionModel());
        s.setThinking(thinking);
        return s;
    }

    /**
     * 图片编号引用规则（附加到 user prompt；图片已随消息按编号顺序提供）。
     * 仅思考开启时启用（实测关闭思考不可靠：漏引用/错配/幻觉编号）。
     * 强调"题干有文字也有图时 content = 文字 + [图片N]"（防模型把整个题干替换成图片引用，实测发生过）。
     */
    /**
     * PDF 直传（模型看图主导）图片规则：整页截图 + 块内内嵌图随消息提供，模型按截图判断图形归属并引用编号。
     * 测试验证（91-95 区，同题干图形题 + 纸盒题 + 跨页场景）：模型看图配图全部正确，且整页图下不丢题。
     * 消息图片顺序：整页截图（firstPage..lastPage）在前，内嵌图（[图片N1]..[图片N2] 升序）在后。
     */
    private String buildPdfVisionImageRule(int firstPage, int lastPage, int pageImageCount,
                                           int first, int last) {
        int count = last - first + 1;
        return """

                【图片引用规则】本部分随消息提供整页截图 %d 张（第 %d~%d 页，位于消息图片最前，用于判断版式与图形归属），
                以及内嵌图 %d 张（编号 [图片%d]~[图片%d]，紧随截图之后、按编号顺序提供，编号即顺序）。
                图形与图片归属（重要，逐题核对，禁止错配）：
                - 每题若有图形/照片/图表：对照整页截图判断该图属于哪道题，在对应位置引用正确的 [图片N]：
                  题干有图 → content = 完整题干文字 + [图片N]（题干末尾）；
                  选项是图 → 该选项 text 写 [图片N]（文字与图混合则文字 + [图片N]）；
                  选项区每个选项通常对应不同图，禁止多个选项引用同一编号。
                - 题干文字几乎相同的相邻图形题（如"从所给的四个选项中…"系列）：以截图中的图形为准区分，
                  每题的图必须引用自己对应的编号，禁止把上一题的图配给下一题。
                - 公式图（内嵌图内容是数学公式/化学式的）禁止引用编号，一律转写为 $...$ LaTeX 文本。
                - 正文文字以文本层为准，不要转写截图中的正文文字；文本层断档处的公式/横线例外（见页面截图说明）。
                - 禁止编造编号：只引用实际提供的 [图片%d]~[图片%d]；无法确定归属时宁可不引用（未配图题预览页会提示，可人工补图）。
                """.formatted(pageImageCount, firstPage, lastPage, count, first, last, first, last);
    }

    private String buildImageRefRule(int first, int last) {
        int count = last - first + 1;
        return """

                【图片引用规则】本部分共 %d 张图片，编号为 [图片%d]~[图片%d]（已随本消息按编号顺序提供，编号即图片顺序）。
                文本中可能已在图片所在位置插入了 [图片N] 标记（有标记则据此判断图片归属）；没有标记时按编号顺序对照文档判断。
                当题干、选项或共享材料是图片（或含图片）时，必须在对应文本位置写入 [图片N] 标记：
                - 题干有图：content = 完整题干文字 + [图片N]；题干只有图没有文字时，content 只写 [图片N]
                - 选项是图：该选项 text 只写 [图片N]；文字与图混合：文字 + [图片N]
                - 共享材料有图：material.content 中写 [图片N]
                禁止编造编号：只引用实际提供的 [图片%d]~[图片%d]；无法确定图片归属时宁可少引用；
                每个选项通常对应不同的图片，禁止把多个选项写成同一个编号。
                公式图（内容是数学公式/化学式的 [图片N]）禁止引用：题干与选项中的公式一律转写为 $...$ LaTeX 文本，
                禁止把公式图编号写进 content 或选项 text；[图片N] 只用于照片、几何图形、曲线图等非公式内容。
                """.formatted(count, first, last, first, last);
    }

    /**
     * 图片引用规则（标记路径）：列出块内实际的图片编号（按文本出现顺序），与随消息提供的图片一一对应。
     * 块内编号可能不连续（配额拆块把公式图隔开），不能用 firstNum~lastNum 描述。
     */
    private String buildImageRefRuleList(List<Integer> nums) {
        String list = nums.stream().map(n -> "[图片" + n + "]").collect(java.util.stream.Collectors.joining("、"));
        return """

                【图片引用规则】本部分共 %d 张图片：%s（已随本消息按此顺序提供，编号即图片顺序）。
                文本中已在图片所在位置插入了 [图片N] 标记，请据此判断图片归属。
                当题干、选项或共享材料是图片（或含图片）时，必须在对应文本位置写入 [图片N] 标记：
                - 题干有图：content = 完整题干文字 + [图片N]；题干只有图没有文字时，content 只写 [图片N]
                - 选项是图：该选项 text 只写 [图片N]；文字与图混合：文字 + [图片N]
                - 共享材料有图：material.content 中写 [图片N]
                禁止编造编号：只引用实际提供的编号；无法确定图片归属时宁可少引用；
                每个选项通常对应不同的图片，禁止把多个选项写成同一个编号。
                公式图（内容是数学公式/化学式的 [图片N]）禁止引用：题干与选项中的公式一律转写为 $...$ LaTeX 文本，
                禁止把公式图编号写进 content 或选项 text；[图片N] 只用于照片、几何图形、曲线图等非公式内容。
                """.formatted(nums.size(), list);
    }

    /** 块内图片编号（按文本出现顺序去重） */
    private List<Integer> imageChunkNumbers(String chunkText) {
        List<Integer> nums = new ArrayList<>();
        if (chunkText == null) {
            return nums;
        }
        Set<Integer> seen = new HashSet<>();
        Matcher m = IMAGE_REF.matcher(chunkText);
        while (m.find()) {
            int n = Integer.parseInt(m.group(1));
            if (seen.add(n)) {
                nums.add(n);
            }
        }
        return nums;
    }

    /** 剥离 AI 输出中的图片编号标记（[图片N]）——文本定位/去重时用（标记不在源文文本中） */
    private String stripImageRefs(String text) {
        if (text == null) {
            return null;
        }
        return text.replaceAll("\\[图片\\d+\\]", " ");
    }

    /** 结果序列化：{"materials":[...], "questions":[...]}（无材料时退化为 questions 数组，兼容旧解析） */
    private String serializeResult(AiParsedResult result) {
        try {
            if (result.materials() == null || result.materials().isEmpty()) {
                return objectMapper.writeValueAsString(result.questions());
            }
            com.fasterxml.jackson.databind.node.ObjectNode node = objectMapper.createObjectNode();
            node.set("materials", objectMapper.valueToTree(result.materials()));
            node.set("questions", objectMapper.valueToTree(result.questions()));
            return objectMapper.writeValueAsString(node);
        } catch (IOException e) {
            throw new IllegalStateException("AI 导入结果序列化失败", e);
        }
    }

    /** 解析结果 JSON（兼容：数组 = 仅题目；对象 = {"materials":[...], "questions":[...]}） */
    private AiParsedResult parseStoredResult(String resultJson) throws IOException {
        JsonNode node = objectMapper.readTree(resultJson);
        List<ContentPackageQuestion> questions = new ArrayList<>();
        List<ContentPackageMaterial> materials = new ArrayList<>();
        if (node.isArray()) {
            for (JsonNode item : node) {
                try {
                    questions.add(objectMapper.treeToValue(item, ContentPackageQuestion.class));
                } catch (Exception ignored) {
                }
            }
        } else {
            JsonNode matArr = node.path("materials");
            if (matArr.isArray()) {
                for (JsonNode item : matArr) {
                    try {
                        materials.add(objectMapper.treeToValue(item, ContentPackageMaterial.class));
                    } catch (Exception ignored) {
                    }
                }
            }
            JsonNode qArr = node.path("questions");
            if (qArr.isArray()) {
                for (JsonNode item : qArr) {
                    try {
                        questions.add(objectMapper.treeToValue(item, ContentPackageQuestion.class));
                    } catch (Exception ignored) {
                    }
                }
            }
        }
        return new AiParsedResult(questions, materials);
    }

    /**
     * 整理阶段系统提示（Markdown 输出，替代 JSON extractOnly）。
     * MD 是模型更擅长的书写格式：无 schema 转义/数组截断压力，长题干/公式/子题/图片位置保真；
     * 后端用确定性解析器 MdQuestionParser 把标准模板还原为题目结构（题号直接取自标题，免源文定位）。
     * 图片原则（用户实测网页端 DeepSeek 行为对齐）：就地引用不重排（题干图留题干、不塞选项）；
     * 公式图一律转写 LaTeX（前端 KaTeX 渲染），不再截图保存公式。
     */
    private String buildMdExtractPrompt() {
        return """
                你是题库整理助手。把用户提供的文档内容整理成题目清单，严格按以下 Markdown 模板输出（只输出 Markdown 模板内容，不要输出其他格式或任何说明文字）：

                ## 第N题 · 题型
                **题干**：
                （题干全文，逐字保留原文，可多行；公式以 LaTeX（$...$）输出；含 (1)(2)(3) 子问的题目把全部子问合并在一题内，按原文换行保留）
                **选项**：
                - A. 选项内容
                - B. 选项内容
                **答案**：留空（本阶段不要写答案）
                **解析**：留空

                规则（重要）：
                1. 题号必须与文档一致、按文档顺序输出；**每个题块必须以"## 第N题 · 题型"标题开头，禁止省略标题**；
                   禁止合并相邻题、禁止遗漏任何一题（包括图形题、表格题、题号在行尾的题）。
                2. 题型写：单选 / 多选 / 判断 / 主观。填空题、实验题、作图题、计算题等非选择题 → 主观，且不写"**选项**"行。
                3. 题干含"填正确答案标号"等字样时，其中出现的 A/B/C/D 是填空标号不是选择题选项 → 题型为主观，不写选项。
                4. 选项挤在同一行（"A. …B. …C. …D. …"）时拆成独立选项行。
                5. 公式必须转写为 LaTeX：图片内容是数学公式/化学式时，直接输出 $...$ 公式文本（如 $F=ma$、$\\frac{1}{2}mv^2$、$kL^2$），
                   不要引用图片、不要描述图片、不要跳过公式。
                6. [图片N] 标记表示该位置存在一张图片（本块内图片已随消息提供）。图片就地保留：题干的图写在题干中原位置（通常在题干末尾），
                   只有选项本身是图片（如图线选项、几何图形选项）时该选项才写 [图片N]；禁止把题干图移到选项里、禁止重排图片位置；
                   公式图（内容是数学公式/化学式的 [图片N]）例外：一律按规则 5 转写为 $...$ LaTeX 文本，禁止引用公式图编号；
                   图形推理题的图在题干、选项是文字（如"①②⑥，③④⑤"）时照写文字。禁止编造编号，禁止转述图片内容。
                   若消息附带页面截图：截图仅用于判断版式（题号位置、题目边界、图形与选项的视觉归属），文字一律以文本层为准，图片引用仍用 [图片N]。
                7. 卷末"参考答案"区的答案行（"1.B"、"【1题答案】B"、"1-8：B D C…"）不是题目，不要输出；试卷开头的注意事项/答题说明也不是题目，跳过。
                   封面/宣传页的图片与广告内容（logo、二维码、课程推广等）不是题目内容：禁止引用其图片、禁止输出为题目。
                8. 题干与选项逐字保留原文：下划线、填空线、括号、引号、公式、特殊符号一律原样，禁止改写、删除或规范化。
                9. 题号紧跟在定义/说明文字之后（如"正向情绪价值：指……能力。1.下列属于……"）时，题号前的定义句属于该题题干，并入题干输出，禁止跳过。
                10. 共享材料：文档存在多题共用的大题干/表格/图表（如"材料一"、资料分析材料）时，在该组题之前输出一个"## 材料 m1"块（后续材料依次 m2、m3…），
                    材料全文写在块内（可含公式与 [图片N]）；材料后的题目 content 只写问题部分，不重复材料文字。没有共享材料时禁止输出材料块。
                11. 每个题块之间空一行。
                """;
    }

    /**
     * 系统提示。extractOnly=true（整理阶段）时：不输出答案/解析（answerKeys 空数组、不写 answerText/analysis），
     * 答案与解析由后续"补充阶段"单独处理（原文证据确定性恢复 + 思考模式 AI 补充）——
     * 无思考整理时模型自算的答案可信度低（实测），且省去答案生成可显著提速。
     */
    private String buildSystemPrompt(boolean aiSupplement, boolean extractOnly) {
        String answerRule;
        if (extractOnly) {
            answerRule = "2. 本阶段只整理题目结构：所有题目的 answerKeys 一律返回空数组 []，不要输出 answerText 和 analysis，"
                    + "不要自行计算或猜测答案（答案与解析由后续阶段单独补充）；原文中的答案信息（题后\"答案：X\"、卷末答案列表如 \"1.B\"、\"【1题答案】B\"）照常保留在文档里即可，不要写进题目字段。\n"
                    + "   图片标记规则（重要）：文档文本中的 [图片N] 标记表示该位置存在一张图片（公式图/插图，本块内图片已随消息提供）。"
                    + "题干/选项是图片（或公式图）的题：紧跟题干文字之后的标记通常是题干图，保留在 content 末尾；选项区的标记按出现顺序写入对应选项的 text（\"[图片N]\"）；"
                    + "同一位置的多个连续标记按顺序对应；无法确定归属的标记保留在 content 末尾，不要丢弃也不要编造编号，禁止转述图片内容。";
        } else if (aiSupplement) {
            answerRule = "2. 只有原文完全没有答案的题目，才由你补充答案（基于内容判断正确），并将 answerSource 标记为 \"AI_SUPPLEMENT\"；使用原文答案的题目标记为 \"ORIGINAL\"。";
        } else {
            answerRule = "2. 原文没有答案的题目，保留 answerKeys 为空数组 []，不要自行补充答案，也不要生成解析；使用原文答案的题目标记 answerSource 为 \"ORIGINAL\"。";
        }
        String subjectiveRule;
        if (extractOnly) {
            subjectiveRule = "8. 主观题（应用题/简答/论述/计算题/实验题/填空题，无选项的作答类题目）：输出 type=\"SUBJECTIVE\"，不输出 options/answerKeys（空数组），"
                    + "referenceAnswer 留空（参考答案由后续阶段补充）。";
        } else if (aiSupplement) {
            subjectiveRule = "8. 主观题（应用题/简答/论述/计算题/实验题/填空题，无选项的作答类题目）：输出 type=\"SUBJECTIVE\"，不输出 options/answerKeys（空数组），"
                    + "referenceAnswer 字段写参考答案（可含 [图片N] 标记；原文的分段答案如（1）…（2）…原样保留）；原文没有参考答案时由你生成参考作答。";
        } else {
            subjectiveRule = "8. 主观题（应用题/简答/论述/计算题/实验题/填空题，无选项的作答类题目）：输出 type=\"SUBJECTIVE\"，不输出 options/answerKeys（空数组），"
                    + "referenceAnswer 写原文提供的参考答案（原文的分段答案如（1）…（2）…原样保留）；原文没有参考答案时 referenceAnswer 留空，不要自行编写。";
        }
        //材料规则：extractOnly（整理阶段）时材料由后端本地截取，作为"材料素材"供预览页用户拖入题目材料区
        //（不附加到 prompt、不转写、不自动关联——AI 对材料↔题目关联不可靠，人工兜底）；
        //非 extractOnly（视觉路径/补充阶段）保持模型输出 materials 的规则
        String materialRule = extractOnly
                ? "（材料题（多题共用大题干，如资料分析/阅读材料）处理：材料文字不需要你转写或输出，"
                + "也不要输出顶层 materials 字段、不要在题目上添加 materialKey 引用——"
                + "共享材料已由本地检测，预览页会作为素材块提供，用户可拖入题目材料区；"
                + "这类题 content 照常写题干原文（问题部分）即可）"
                : "（材料规则见上：材料单独输出到顶层 materials，题目用 materialKey 引用）";
        return """
                你是题库整理助手。把用户提供的文档内容整理为考试题目，严格输出一个 JSON 对象：{"questions":[...]}。
                每道题字段：
                type: "SINGLE"单选 / "MULTIPLE"多选 / "JUDGE"判断 / "SUBJECTIVE"主观题（无选项无答案）
                content: 题干（忠实原文，不做改写）
                options: [{"key":"A","text":"..."}]（顺序与原文一致；判断题固定 [{"key":"A","text":"正确"},{"key":"B","text":"错误"}]；主观题空数组）
                answerKeys: 正确答案 key 数组（单选/判断一个，多选多个；主观题空数组）
                answerText: 答案文字（可选）  analysis: 解析（可选）
                referenceAnswer: 主观题参考答案（仅 SUBJECTIVE，可选）
                topic: 主题（可选）  category: 分类（可选）  score: 分值（默认1，主观题默认5）
                answerSource: "ORIGINAL" 或 "AI_SUPPLEMENT"（见下方答案规则）

                共享材料规则（资料分析/阅读材料题）：
                若文档存在"多题共用的大题干"（如材料一/材料二、一段阅读材料带多道问题），
                把材料单独输出到顶层 "materials": [{"materialKey":"m1","content":"材料全文"}]，
                这些题的 content 只写问题部分，并在题目上加 "materialKey":"m1" 引用；
                禁止把材料文字重复写进每题 content；没有共享材料的文档不要输出 materials。
                """ + materialRule + """
                答案规则（最重要）：
                1. 原文提供答案的题目，必须使用原文答案，禁止自行计算或修改。原文答案可能出现在：
                   题后（"答案：B"、"答：C"）、括号标注（"（对）"、"（√）"）、
                   文档末尾的"参考答案/答案列表"（如 "1.B 2.C 3.ABD"、"【1题答案】B"、
                   "第1~8题答案 1-8：B D C…" 区间式，按题号对应到各题）；
                   若多个文件一并提供，其中一份可能是答案文件，其答案列表同样按题号对应，不要单独出题。
                """ + answerRule + """
                3. 主观题按下方"主观题规则"输出（见第 8 条），不要跳过。
                4. 原文明显笔误（如选项缺字母）可做最小修正并保持语义不变。
                5. 材料题（阅读材料+问题）按"共享材料规则"处理：材料进 materials、content 只写问题、题目带 materialKey。
                6. 题干与选项必须逐字保留原文：下划线 _、填空线、括号、引号、公式、特殊符号一律原样保留，
                   禁止删除、替换或规范化（如把 "___" 改成空格、把（ ）改成空白）。
                   公式（包括以图片形式出现的数学/化学公式）必须转写为 LaTeX（$...$，如 $F=ma$、$\\frac{1}{2}mv^2$），
                   不要用图片引用、不要截图式描述、不要跳过公式。
                   图片就地保留：题干的图写在题干中原位置（通常在题干末尾），只有选项本身是图片（如图线选项）时
                   该选项才写 [图片N]；禁止把题干图移到选项里、禁止重排图片位置。
                7. 文档中的每一道题都必须输出，禁止遗漏；确实无法确定答案时 answerKeys 返回空数组 []，
                   不要因此省略整道题。
                """ + subjectiveRule + """
                9. 学科卷（数理化生等）规则：填空题、实验题、作图题、计算题等非选择题 → type="SUBJECTIVE"；
                   题目含 (1)(2)(3) 等子问时合并为一题（子问文字按原文保留在题干中，换行分隔），禁止拆成多题；
                   题干含"填正确答案标号"等字样时，其中出现的 A/B/C/D 是填空标号不是选择题选项 → 不输出 options；
                   选项挤在同一行（"A. …B. …C. …D. …"）时拆成独立选项；
                   选项是图片（如图线选项）时，该选项 text 只写 [图片N]。
                10. 卷末"参考答案"区的答案行（"1.B"、"【1题答案】B"、"1-8：B D C…"）不是题目，不要输出；
                   试卷开头的注意事项/答题说明（"答题前…""注意事项…"开头段落）也不是题目，跳过。
                11. 禁止合并相邻题：每题独立输出；相邻题目之间内容不交叉。

                要求：题干完整、答案以原文为准、解析简明；只输出 JSON，不要任何其他文字。
                """;
    }

    private String buildUserPrompt(String text, String warning) {
        StringBuilder sb = new StringBuilder();
        if (warning != null) {
            sb.append("注意：").append(warning).append('\n');
        }
        sb.append("以下是文档内容，请整理为题目：\n\n");
        if (text != null && !text.isBlank()) {
            sb.append(text);
        } else {
            sb.append("（文档以图片形式提供，请识别图片中的内容出题）");
        }
        return sb.toString();
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
