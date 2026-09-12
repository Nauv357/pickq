package com.tiku.service;

import com.tiku.config.AiSettings;
import com.tiku.model.AiImportJob;
import com.tiku.model.ContentPackageMaterial;
import com.tiku.model.ContentPackageQuestion;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.CancellationException;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.HashMap;
import java.nio.file.Files;

/**
 * AI 模型调用编排：把"文本/视觉输入"按策略切块、并行调用模型、重试与合并，产出题目结构。
 *
 * 从 {@link AiImportService} 迁出（该服务原本把编排、模型调用、版面算法、任务状态机混在一起）。
 * 这里负责三条调用路径与它们的失败处理：
 * - {@link #chatChunked}：文本路径（Markdown 模板 → 按题号切块并行 → 确定性解析合并）；
 * - {@link #chatVisionSingle}：整份 PDF 单次多模态调用；
 * - {@link #chatVisionPages}：视觉分页路径（逐页/分块调用 + 缺题补跑 + 残版过滤）。
 *
 * 不负责：任务行与文件（见 {@link AiImportJobLifecycleService}）、PDF 版面换算（见 {@link AiImportVisionLayoutService}）、
 * 确认入库（仍在 AiImportService）。
 */
@Slf4j
@Service
public class AiImportModelCallService {

    /**
     * 视觉分块：每块页数。实测（deepseek-v4-flash-vision-exp）：每块 4 张整页图 + 内嵌图（约 9-10 张图）
     * 时模型严重劣化（题号粘连/漏题/丢选项/丢图引用）；1-3 张整页图时题号归位与文字精确性完美。
     * 故每块 2 页（2 张整页图 + 该块内嵌图 ≈ 4-7 张），重叠 1 页保证跨页题完整。
     */
    private static final int VISION_CHUNK_PAGES = 2;

    /** 视觉分块：相邻块重叠页数（跨页题在下一块完整出现，去重兜底） */
    private static final int VISION_OVERLAP_PAGES = 1;

    /** 答案列表行：如 "1.B" "12. C" "3:ABD" "5√"；含学科卷 "【1题答案】B"（统一 AiAnswerFormat） */
    private static final Pattern ANSWER_LINE = AiAnswerFormat.ANSWER_LINE;

    /** 每块目标题数（下限 10，保证每块输出量适中） */
    private static final int CHUNK_TARGET_QUESTIONS = 12;

    /** 每块图片配额（marksEmbedded 路径）：块内唯一图片数超过则按题号边界二次拆块——
     *  学科卷（如物理 15 题 100+ 张公式/插图）单块全图会导致模型严重劣化 */
    private static final int MAX_CHUNK_IMAGES = 10;

    /** 最多块数（并行上限，也避免一次任务发出过多调用） */
    private static final int CHUNK_MAX = 6;

    /** 分块策略实验：AI_IMPORT_CHUNK_TARGET 环境变量覆盖 MinerU 路径块目标（8=小块 / 16=大块 / 999=不分块），
     *  用于对比"块边界漏题"与"大输入劣化"的权衡；默认 16（矩阵实测最优）。 */
    private static final int CHUNK_TARGET_MINERU = 16;

    private static final int CHUNK_TARGET_MINERU_OVERRIDE =
            Integer.getInteger("tiku.ai-import.chunk-target", CHUNK_TARGET_MINERU);

    /** 整页渲染 DPI（与扫描件路径一致） */
    public static final int VISION_PAGE_DPI = 150;

    /** 整页渲染 JPEG 质量（整页图只作版式参考，压缩控制请求体；10 页约 1.4MB） */
    public static final float VISION_PAGE_JPEG_QUALITY = 0.8f;

    private final AiClientService aiClientService;
    private final DocumentParserService documentParserService;
    private final AiImportPromptFactory promptFactory;
    private final AiImportTextStructure textStructure;
    private final AiImportSourceTextService sourceTextService;
    private final AiImportResultParser resultParser;
    private final AiImportAnswerService answerService;
    private final AiImportVisionQualityService visionQualityService;
    private final AiImportVisionMissingPageService visionMissingPageService;
    private final ExecutorService aiChunkExecutor;
    private final AiJobEventService aiJobEventService;
    private final AiImportVisionLayoutService visionLayout;
    private final AiImportJobLifecycleService lifecycle;

    public AiImportModelCallService(AiClientService aiClientService,
                                    DocumentParserService documentParserService,
                                    AiImportPromptFactory promptFactory,
                                    AiImportTextStructure textStructure,
                                    AiImportSourceTextService sourceTextService,
                                    AiImportResultParser resultParser,
                                    AiImportAnswerService answerService,
                                    AiImportVisionQualityService visionQualityService,
                                    AiImportVisionMissingPageService visionMissingPageService,
                                    @Qualifier("aiChunkExecutor") ExecutorService aiChunkExecutor,
                                    AiJobEventService aiJobEventService,
                                    AiImportVisionLayoutService visionLayout,
                                    AiImportJobLifecycleService lifecycle) {
        this.aiClientService = aiClientService;
        this.documentParserService = documentParserService;
        this.promptFactory = promptFactory;
        this.textStructure = textStructure;
        this.sourceTextService = sourceTextService;
        this.resultParser = resultParser;
        this.answerService = answerService;
        this.visionQualityService = visionQualityService;
        this.visionMissingPageService = visionMissingPageService;
        this.aiChunkExecutor = aiChunkExecutor;
        this.aiJobEventService = aiJobEventService;
        this.visionLayout = visionLayout;
        this.lifecycle = lifecycle;
    }

    /**
     * 文本路径 AI 整理（Markdown 输出管线）：按题号边界切块并行调用，模型按标准 MD 模板输出，
     * 后端用 MdQuestionParser 确定性解析（题号取自标题，免源文定位），合并后走答案后处理。
     * 不可切分（无连续题号 / 答案文件无法识别）时回退单次调用（同样 MD 输出）。
     * 卷末答案列表会附加到每一块，供模型对照（不输出答案本身）。
     * PDF 直传（pageRenders 非空）：每块附带其页范围的整页截图（版式真相，网页端同款输入）。
     */
    AiImportResult chatChunked(AiSettings settings, String systemPrompt, List<String> texts,
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
                    Matcher m = AiImportTexts.IMAGE_REF.matcher(chunks.get(i));
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
                    int firstPage = AiImportTexts.pageIndexOf(pageStarts, chunkRanges.get(i)[0]);
                    int lastPage = AiImportTexts.pageIndexOf(pageStarts, chunkRanges.get(i)[1]);
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
                int firstPage = AiImportTexts.pageIndexOf(pageStarts, chunkRanges.get(i)[0]);
                int lastPage = Math.min(pageRenders.size() - 1, AiImportTexts.pageIndexOf(pageStarts, chunkRanges.get(i)[1]));
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
                    jobId, i + 1, chunks.size(), sb.length(), AiImportTexts.truncate(sb.toString().replaceAll("\\R+", " | "), 3000));
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
            if (lifecycle.isCanceled(jobId)) {
                //用户取消：放弃剩余块（尽力中断）
                cancelFutures(futures, i);
                return new AiImportResult(all, blockMaterials);
            }
            List<ContentPackageQuestion> parsed = new ArrayList<>();
            try {
                String out = futures.get(i).get(6, TimeUnit.MINUTES);
                //诊断日志：模型原始输出（定位缺题在模型层还是解析层）
                log.info("AI 导入任务 {} 块 {}/{} 模型原始输出（{} 字符）：{}",
                        jobId, i + 1, futures.size(), out.length(), AiImportTexts.truncate(out.replaceAll("\\R+", " | "), 1600));
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
            if (parsed.size() < retryThreshold && !lifecycle.isCanceled(jobId)) {
                List<AiClientService.ImageData> blockImgs = new ArrayList<>();
                if (chunkPageImages != null && i < chunkPageImages.size()) {
                    blockImgs.addAll(chunkPageImages.get(i));
                }
                if (imageChunks != null && i < imageChunks.size() && !imageChunks.get(i).images().isEmpty()) {
                    for (DocumentParserService.ExtractedImage e : imageChunks.get(i).images()) {
                        blockImgs.add(e.image());
                    }
                }
                for (int attempt = 0; attempt < 3 && parsed.size() < retryThreshold && !lifecycle.isCanceled(jobId); attempt++) {
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
            lifecycle.updateStage(job, "PROCESSING", "AI_GENERATING", 40 + 40 * done / futures.size());
            aiJobEventService.publish(jobId, lifecycle.getJob(jobId));
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
                    log.warn("AI 导入任务 {} 题号回填定位失败 content={}", jobId, AiImportTexts.truncate(content, 40));
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
    AiImportResult chatVisionPages(AiSettings settings, String systemPrompt,
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
            if (lifecycle.isCanceled(jobId)) {
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
            if (parsed.questions().size() < 5 && !lifecycle.isCanceled(jobId)) {
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
            lifecycle.updateStage(job, "PROCESSING", "AI_GENERATING", 40 + 40 * done / futures.size());
            aiJobEventService.publish(jobId, lifecycle.getJob(jobId));
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
                fullSource, warning, vision, systemPrompt, aiSupplement, jobId, () -> lifecycle.isCanceled(jobId));
        //8. 按源文位置排序（还原文档顺序；补漏题归位，不再追加在末尾）
        unique = visionQualityService.sortBySourceOrder(unique, fullSource);
        return new AiImportResult(unique, allMaterials);
    }

    /**
     * 视觉单次路径（实测定稿）：整页渲染（JPEG 压缩）+ 页文本层 + 简化 prompt → 单次多模态调用。
     * 模型输出位置占位符（【题干图片】【选项A图片】）而非编号引用——不依赖模型数图能力，
     * 无思考模式也可靠（实测 40 题 10 页：无思考 48s / 思考 124s，均 40/40 不丢题、占位精准）。
     * 占位符 → 内嵌图编号：按"题号 → 页 → 页内图顺序 + 每题占位符数量"确定性分配（resolvePlaceholders）；
     * 匹配失败（矢量图等无内嵌图）→ 占位符保留，预览页手动补图。
     * 校验：跳过源文回填（行首题号定位会误杀"题号+题干同行"版式）；残版/粘连过滤 + 去重择优。
     */
    AiImportResult chatVisionSingle(AiSettings settings, List<String> pageTexts,
                                            List<DocumentParserService.ExtractedImage> extracted,
                                            String fullSource, String warning, AiImportJob job, Long jobId,
                                            boolean aiSupplement, Path jobDir, String pdfFileName) throws IOException {
        //1. 整页渲染：JPEG（压缩，发给模型）+ PNG（无损，密度/裁剪分析用——JPEG 噪点会虚高密度导致误插）
        byte[] pdfBytes = Files.readAllBytes(jobDir.resolve("0-" + pdfFileName));
        List<AiClientService.ImageData> pageImages = documentParserService.renderAllPages(
                pdfBytes, VISION_PAGE_DPI, VISION_PAGE_JPEG_QUALITY);
        List<AiClientService.ImageData> analysisImages = documentParserService.renderAllPages(pdfBytes, AiImportVisionLayoutService.ANALYSIS_RENDER_DPI, 0f);
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
        visionLayout.resolvePlaceholders(questions, pageTexts, extracted, analysisImages, linePositions, jobDir);
        return new AiImportResult(questions, parsed.materials());
    }

    /**
     * 按题号对比源文，找出模型漏掉的题并单独补一次小调用（最多补 12 题，防劣化循环）。
     * 题号定位：输出题 content（已回填，必在源文）→ 其位置之后的第一个题号行 = 题号。
     * 支持孤立题号行（粉笔）与"题号+题干同行"（txt）两种格式。
     */
    List<ContentPackageQuestion> fillMissingQuestions(List<ContentPackageQuestion> questions, String fullSource,
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
    List<ContentPackageQuestion> fillOne(int n, String[] lines, List<Integer> sourceNums, String fullSource,
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
            Matcher refM = AiImportTexts.IMAGE_REF.matcher(cleanFrag);
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
                        jobId, n, out.length(), AiImportTexts.truncate(out.replaceAll("\\R+", " | "), 900));
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

    /** 合并材料列表（按 materialKey 去重，保留先出现的） */
    void mergeMaterials(List<ContentPackageMaterial> target, List<ContentPackageMaterial> source) {
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

    void cancelFutures(List<Future<String>> futures, int from) {
        for (int j = from; j < futures.size(); j++) {
            futures.get(j).cancel(true);
        }
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
    AiImportResult parseAndValidate(String aiOutput, boolean aiSupplement, String sourceText,
                                            boolean enableMaterials, boolean skipSourceRepair) {
        return resultParser.parseJson(aiOutput, aiSupplement, sourceText, enableMaterials, skipSourceRepair);
    }

    /** 模型输出是否为 JSON 形态（无思考重试 + json_object 响应格式时模型可能输出旧版 JSON 结构） */
    boolean looksLikeJson(String out) {
        return resultParser.looksLikeJson(out);
    }

    /** 端点降级快速失败：源文题号 ≥5 但结果 <3 题 → 模型持续返回空白/垃圾，整单报错让用户稍后重试。
     *  （静默产出 1-2 题垃圾比明确失败更糟——用户以为导入成功） */
    void assertNotDegraded(List<ContentPackageQuestion> questions, String fullSource) {
        resultParser.assertNotDegraded(questions, fullSource);
    }

    /**
     * Markdown 输出解析（分块路径）：模型按标准 MD 模板输出 → MdQuestionParser 确定性解析 → validate 校验。
     * 题号直接取自标题（免源文定位回填）；材料由模型声明（## 材料 mN 块），题目按位置自动关联。
     * 解析失败/坏题丢弃不抛异常（补漏 + 预览兜底）。
     */
    List<ContentPackageQuestion> mdParseQuestions(String mdOutput, boolean aiSupplement) {
        return resultParser.parseMarkdownQuestions(mdOutput);
    }

    /** Markdown 输出解析（含材料块） */
    AiImportResult mdParseResult(String mdOutput, boolean aiSupplement) {
        return resultParser.parseMarkdown(mdOutput);
    }

    /**
     * 按题号边界把文本切成若干块（每块约 CHUNK_TARGET_QUESTIONS 题，最多 CHUNK_MAX 块）。
     * 返回 {start, end} 偏移区间（清洗后空间）；块 0 包含题号前的头部内容；
     * 检测不到连续题号时返回单块（调用方回退单次调用）。
     */
    List<int[]> splitChunks(String text, int chunkTarget) {
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
    String trimOrphans(String chunk, boolean trimHead, boolean trimTail) {
        return textStructure.trimOrphans(chunk, trimHead, trimTail);
    }

    /**
     * 检测题号边界：行首 "1." / "2、" 等，取最长的连续递增（允许跳号）序列。
     * 排除答案列表行（"1.B" 这类紧凑格式），避免把卷末答案误当题号。
     *
     * @return 边界行起始位置列表（按文档顺序），不足 3 个返回空
     */
    List<Integer> detectQuestionBoundaries(String text) {
        return textStructure.detectQuestionBoundaries(text);
    }

    /**
     * 从"最后一个题号边界到文末"的尾段中定位卷末答案区的起始偏移：
     * 首个答案行（ANSWER_LINE / 区间式）或"参考答案"标题行即为答案区起点。
     * 找不到返回 -1（尾段整体是最后一题的内容，不含答案列表）。
     */
    int answerSectionStartOffset(String tailCandidate) {
        return textStructure.answerSectionStartOffset(tailCandidate);
    }

    /**
     * 图片配额二次拆块（marksEmbedded 路径）：块内唯一图片数超过 MAX_CHUNK_IMAGES 时，
     * 按内部题号边界切分（题号行是该题的起点，图片跟随其后的文本），直到配额满足或新增块数用尽（CHUNK_MAX）。
     * 不可拆（无内部边界/预算耗尽）保持原块。
     */
    List<int[]> splitRangesByImageQuota(String text, List<int[]> ranges) {
        return textStructure.splitRangesByImageQuota(text, ranges);
    }

    /** 块内图片编号（按文本出现顺序去重） */
    List<Integer> imageChunkNumbers(String chunkText) {
        return textStructure.imageChunkNumbers(chunkText);
    }

    /**
     * 行首题号行：孤立题号行（"10."）或"题号+题干同行"（"10. 题干…"），排除小数行与答案列表行。
     * 行首允许 [图片N] 标记前缀（MinerU 重建文本中材料图表标记可能与题号同行："[图片1][图片2]6.2012年…"）。
     */
    boolean isQuestionNumberLine(String t) {
        return textStructure.isQuestionNumberLine(t);
    }

    /** 第一个章节标题行（"一、选择题…"）；找不到返回 0。说明区题号（注意事项）从题号统计中排除 */
    int firstSectionHeaderLine(String[] lines) {
        return textStructure.firstSectionHeaderLine(lines);
    }

    /** 块内图片信息：图片列表 + 全局编号范围（[图片firstNum]~[图片lastNum]） */
    private record ImageChunk(List<DocumentParserService.ExtractedImage> images, int firstNum, int lastNum) {
    }
}
