package com.tiku.service;

import com.tiku.model.ContentPackageQuestion;
import com.tiku.model.OptionItem;
import lombok.extern.slf4j.Slf4j;
import org.apache.pdfbox.text.TextPosition;
import org.springframework.stereotype.Service;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * PDF 版面算法：把"整页渲染图 + 文本行坐标 + 内嵌图"换算成题目的配图。
 *
 * 从 {@link AiImportService} 整体迁出（该服务原本 3000+ 行、混着编排与算法）。
 * 这里只做**版面计算**：入参是页文本/渲染页/行坐标/内嵌图/任务临时目录，出参是改写过的题目或裁剪出的临时图片编号；
 * 不读写任务表、不发 AI 请求、不发事件——因此可以脱离模型单独测试。
 *
 * 三条主线：
 * 1. {@link #assignPdfFigureImages}：PDF 直传路径下，模型漏引用的图形题按版面坐标补图（模型已引用的题不动）；
 * 2. {@link #resolvePlaceholders}：占位符 → 图片编号，按"题号 → 页 → 页内块 / 内嵌位图"确定性分配；
 * 3. {@link #cropBand} / {@link #detectGraphBlocks} 等：整页图上的非白块检测与按带裁剪（矢量图也能拿到）。
 *
 * 约定：裁剪出的图片写入 imports/{jobId}/images/{N}.png，编号接在内嵌图之后（由调用方维护 nextImageNo）。
 */
@Slf4j
@Service
public class AiImportVisionLayoutService {

    /** 版面分析用的整页渲染 DPI（与视觉路径 150 DPI 区分：这里是坐标级精度需求） */
    public static final int ANALYSIS_RENDER_DPI = 300;

    /** 题干图与首个选项块的纵向间距阈值：超过则认为块 0 也是选项图 */
    private static final float OPTION_VERTICAL_GAP_PT = 30f;
    /** 图形块顶部距题干行的最大距离（超过则判定为无主块，防止错插到相邻题） */
    private static final float STEM_IMAGE_MAX_GAP_PT = 80f;
    /** 题干图"离题干过远"的判定：块顶部距题干行超过该值说明它更可能是选项图 */
    private static final float STEM_IMAGE_FAR_GAP_PT = 40f;
    /** 判定"连续非白像素段"的最小长度（像素）：过滤扫描噪点 */
    private static final int IMAGE_MIN_RUN_PX = 36;
    /** 题干含这些词时更可能出现图形区（用于块归属的语义判定） */
    private static final Pattern FIGURE_STEM = Pattern.compile("图形|俯视|展开图|问号|规律性|填入|折叠|折成|截面|纸盒");

    private final DocumentParserService documentParserService;
    private final AiImportSourceTextService sourceTextService;

    public AiImportVisionLayoutService(DocumentParserService documentParserService,
                                       AiImportSourceTextService sourceTextService) {
        this.documentParserService = documentParserService;
        this.sourceTextService = sourceTextService;
    }

    /**
     * 位置占位符 → 图片（[图片N]）：
     * 1. 带内位图优先：该页内嵌位图（CTM 区域）与占位带重叠 → 直接用提取的位图（清晰、精确）；
     * 2. 否则从整页渲染图按带裁剪（题干带 / 选项带），白边裁剪后落盘（矢量图也能拿到）。
     * 裁剪图写入 imports/{jobId}/images/{N}.png（编号接在内嵌图之后），confirm 时统一落盘。
     * 定位失败/裁剪异常 → 占位符保留原文（预览页手动补图）。
     */
    public void resolvePlaceholders(List<ContentPackageQuestion> questions, List<String> pageTexts,
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
            String contentProbe = AiImportTexts.stripImageRefs(q.getContent() == null ? "" : q.getContent()).split("\\R", 2)[0]
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
                    page = AiImportTexts.pageIndexOf(pageStarts, lineStarts[bestLine]);
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
                        AiImportTexts.truncate(AiImportTexts.stripImageRefs(q.getContent() == null ? "" : q.getContent()), 26));
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
                                AiImportTexts.truncate(AiImportTexts.stripImageRefs(q.getContent()), 40));
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

    public AiImportResult assignPdfFigureImages(AiImportResult parsed, List<String> pageTexts,
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
                    && AiImportTexts.stripImageRefs(q.getContent() == null ? "" : q.getContent()).replaceAll("[^\\p{L}\\p{N}]", "").isEmpty());
            //题干回填 + 每题定位（题干行 y 为 top-origin pt，与图锚点同坐标系）
            List<Integer> order = new ArrayList<>();
            List<Float> ys = new ArrayList<>();
            List<Integer> pages = new ArrayList<>();
            Map<String, Integer> probeCursor = new HashMap<>();
            int lastGlobalLine = -1;
            for (int qi = 0; qi < out.size(); qi++) {
                ContentPackageQuestion q = out.get(qi);
                String textOnly = AiImportTexts.stripImageRefs(q.getContent() == null ? "" : q.getContent());
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
                String s2 = AiImportTexts.stripImageRefs(q.getContent() == null ? "" : q.getContent());
                if (s2.isBlank()) {
                    continue;
                }
                int line = sourceTextService.locateContentLine(lines, s2);
                if (line < 0) {
                    continue;
                }
                int page = AiImportTexts.pageIndexOf(pageStarts, lineStarts[line]);
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
                Matcher m = AiImportTexts.IMAGE_REF.matcher((q.getContent() == null ? "" : q.getContent())
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
                String stemText = AiImportTexts.stripImageRefs(q.getContent() == null ? "" : q.getContent()).trim();
                if (!FIGURE_STEM.matcher(stemText).find()) {
                    continue;
                }
                String rawContent = q.getContent() == null ? "" : q.getContent();
                if (AiImportTexts.IMAGE_REF.matcher(rawContent).find()) {
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
        log.info("选项分配：content={} pool={} shell={}", AiImportTexts.truncate(AiImportTexts.stripImageRefs(q.getContent()), 25),
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
}
