package com.tiku.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.Map;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * MinerU 文档解析客户端（mineru.net Standard API，vlm 模型）。
 *
 * 职责：把 pdf/图片/docx 上传到 MinerU，轮询任务，下载结果 zip，
 * 用 content_list_v2.json（结构化中间格式，官方二次开发接口）重建"增强文本"：
 * - 文本块按阅读顺序输出（含表格 HTML、公式 LaTeX）
 * - 图片块提取为独立文件（转 PNG，按出现顺序编号，与页文本对齐）
 * - 页眉/页脚/页码块剔除（MinerU 已分类）
 * - 装饰图过滤：第一个含题号段落之前的图跳过（封面 logo/二维码，实测场景）
 *
 * 输出与 DocumentParserService.ParseResult 同构 → AiImportService 的
 * 合并/分块/回填/溯源/证据校验逻辑全部复用（AI 整理层零改动）。
 *
 * 设计依据（实测 2026）：vlm 输出 71/71 图片顺序与 content_list 一致、
 * 识别层零丢字；md 渲染层有选项错位 → 管道以 content_list 为准，md 仅调试用。
 */
@Slf4j
@Service
public class MineruParseService {

    private static final String API_BASE = "https://mineru.net/api/v4";
    private static final String MODEL_VERSION = "vlm";
    /** 轮询间隔与总超时 */
    private static final long POLL_INTERVAL_MS = 2000;
    private static final long POLL_TIMEOUT_MS = 5 * 60 * 1000;
    /** 单个块内提取字符串的最大长度（防解析异常数据） */
    private static final int MAX_TEXT_PER_BLOCK = 20_000;

    /** 题号段落检测：行首 N. / N、 / N）或行内 "N."（MinerU 文本中题号可能嵌入段落） */
    private static final Pattern QUESTION_NUM = Pattern.compile("(^|[^\\d])\\d{1,3}[.、．]");

    /** 支持走 MinerU 的文件类型（与 MinerU API 支持范围对齐；txt/md 走本地直读） */
    private static final Pattern MINERU_FILE = Pattern.compile("(?i)\\.(pdf|png|jpe?g|webp|bmp|docx?|pptx?|xlsx?)$");

    private final ObjectMapper objectMapper;
    private final HttpClient httpClient;
    private final DocumentParserService documentParserService;

    public MineruParseService(ObjectMapper objectMapper, DocumentParserService documentParserService) {
        this.objectMapper = objectMapper;
        this.documentParserService = documentParserService;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(15))
                .build();
    }

    /** 该文件类型是否走 MinerU 解析（配置了 mineruKey 且格式匹配） */
    public boolean isMineruFile(String fileName) {
        return fileName != null && MINERU_FILE.matcher(fileName).find();
    }

    /**
     * 解析单个文件：MinerU 结构化解析 → 增强文本 + 逐页文本 + 图片列表（ParseResult 同构）。
     * PDF 额外做"文本层题号差异检测"：MinerU vlm 对同题干重复题会丢题（实测），
     * 用本地 PDFBox 文本层题号对比，**只预警不自动补题**（补题实测引入重复/位置错乱，交由预览页人工处理）。
     *
     * @throws IOException 网络/API 错误（消息已脱敏，不含 Key）
     */
    public DocumentParserService.ParseResult parse(String fileName, byte[] bytes, String mineruKey) throws IOException {
        byte[] zipBytes = submitAndPoll(bytes, fileName, mineruKey);
        DocumentParserService.ParseResult result = rebuild(zipBytes, fileName, mineruKey);
        if (result.fileType() != null && result.fileType().endsWith("PDF")) {
            result = warnMissingQuestions(result, fileName, bytes);
        }
        return result;
    }

    // ==================== PDFBox 题号差异检测（只预警，不补题） ====================

    /**
     * 行尾题号（"…正确的一项是：19."——PDFBox 文本的题号常在行尾；数字+分隔符到行尾）。
     * 行首题号（1-3 位数字 + 分隔符），排除小数行（"15.8%"）与答案列表行（"1.A"）。
     */
    private static final Pattern LINE_END_NUM = Pattern.compile("(?<![\\d.．])(\\d{1,3})\\s*[.．、]\\s*$");
    private static final Pattern LINE_START_NUM = Pattern.compile("^\\s*(\\d{1,3})\\s*[.．、)）](?!\\d)");
    /** 答案列表行（"1.B" / "【1题答案】B" 等，卷末答案不算题号；统一 AiAnswerFormat） */
    private static final Pattern ANSWER_LINE_PAT = AiAnswerFormat.ANSWER_LINE;

    /**
     * PDFBox 文本层题号 vs MinerU 增强文本题号差异检测：
     * MinerU 缺失的题号 → 追加到 warning（提示预览页人工核对），不自动补
     * （自动补题实测引入重复与位置错乱——第 20 题补回但顺序乱/重复多，路线 2.5 已放弃）。
     */
    private DocumentParserService.ParseResult warnMissingQuestions(DocumentParserService.ParseResult result,
                                                                   String fileName, byte[] pdfBytes) {
        String mineruText = result.text();
        if (mineruText == null || mineruText.isBlank()) {
            return result;
        }
        DocumentParserService.ParseResult local;
        try {
            local = documentParserService.parse(fileName, pdfBytes);
        } catch (Exception e) {
            log.warn("MinerU 题号检测：本地 PDFBox 解析失败（跳过）：{}", e.getMessage());
            return result;
        }
        String pdfText = local.text();
        if (pdfText == null || pdfText.isBlank() || pdfText.length() < 200) {
            return result; //扫描件/无文本层：无真相可对比
        }
        List<Integer> pdfNums = collectQuestionNums(pdfText);
        List<Integer> mineruNums = collectQuestionNums(mineruText);
        if (pdfNums.size() < 5 || mineruNums.size() < 5) {
            return result; //题号不可靠，跳过
        }
        Set<Integer> mineruSet = new HashSet<>(mineruNums);
        List<Integer> missing = new ArrayList<>();
        for (int n : pdfNums) {
            if (!mineruSet.contains(n) && !missing.contains(n)) {
                missing.add(n);
            }
        }
        if (missing.isEmpty()) {
            return result;
        }
        log.warn("MinerU 题号检测：PDFBox {} 题 vs MinerU {} 题，缺失题号 {}（仅预警，预览页人工核对）",
                pdfNums.size(), mineruNums.size(), missing);
        String warn = "文档约 " + pdfNums.size() + " 题，MinerU 识别出 " + mineruNums.size()
                + " 题，疑似缺失题号：" + missing + "（请预览核对，必要时手动补题）";
        String prev = result.warning();
        return new DocumentParserService.ParseResult(result.fileType(), result.text(), result.images(),
                result.pageTexts(), result.extractedImages(),
                prev == null || prev.isBlank() ? warn : prev + " " + warn,
                result.formulaImageCount(), result.formulaImageNos());
    }

    /** 收集题号（行首 + 行尾，排除小数/答案行，保持顺序去重） */
    private List<Integer> collectQuestionNums(String text) {
        List<Integer> nums = new ArrayList<>();
        Set<Integer> seen = new HashSet<>();
        for (String line : text.split("\\R", -1)) {
            String t = line.trim();
            if (t.isEmpty() || ANSWER_LINE_PAT.matcher(t).matches()) {
                continue;
            }
            int n = -1;
            Matcher ms = LINE_START_NUM.matcher(t);
            if (ms.find()) {
                n = Integer.parseInt(ms.group(1));
            } else {
                Matcher me = LINE_END_NUM.matcher(t);
                if (me.find()) {
                    n = Integer.parseInt(me.group(1));
                }
            }
            if (n >= 1 && n <= 999 && seen.add(n)) {
                nums.add(n);
            }
        }
        return nums;
    }

    // ==================== API 流程 ====================

    /** 1) file-urls/batch 2) PUT 上传 3) 轮询 extract-results/batch 4) 返回结果 zip 字节 */
    private byte[] submitAndPoll(byte[] bytes, String fileName, String mineruKey) throws IOException {
        try {
            //1. 申请签名上传 URL
            ObjectNode payload = objectMapper.createObjectNode();
            ArrayNode files = payload.putArray("files");
            ObjectNode f = files.addObject();
            f.put("name", fileName);
            f.put("data_id", sanitizeDataId(fileName));
            payload.put("model_version", MODEL_VERSION);
            JsonNode uploadRes = postJson(API_BASE + "/file-urls/batch", payload, mineruKey, 60_000);
            String batchId = uploadRes.path("data").path("batch_id").asText();
            String uploadUrl = uploadRes.path("data").path("file_urls").path(0).asText();
            if (batchId.isBlank() || uploadUrl.isBlank()) {
                throw new IOException("MinerU 返回异常：未获取到上传地址");
            }

            //2. PUT 上传（文档要求不带 Content-Type；Content-Length 由 HttpClient 自动计算——手动设置会触发 restricted header 异常）
            HttpRequest uploadReq = HttpRequest.newBuilder()
                    .uri(URI.create(uploadUrl))
                    .timeout(Duration.ofMinutes(5))
                    .PUT(HttpRequest.BodyPublishers.ofByteArray(bytes))
                    .build();
            HttpResponse<Void> uploadResp = httpClient.send(uploadReq, HttpResponse.BodyHandlers.discarding());
            if (uploadResp.statusCode() != 200 && uploadResp.statusCode() != 201 && uploadResp.statusCode() != 203) {
                throw new IOException("MinerU 文件上传失败 HTTP " + uploadResp.statusCode());
            }

            //3. 轮询任务结果
            long deadline = System.currentTimeMillis() + POLL_TIMEOUT_MS;
            while (System.currentTimeMillis() < deadline) {
                JsonNode pollRes = getJson(API_BASE + "/extract-results/batch/" + batchId, mineruKey, 30_000);
                JsonNode results = pollRes.path("data").path("extract_result");
                for (JsonNode entry : results) {
                    if (!fileName.equals(entry.path("file_name").asText())) {
                        continue;
                    }
                    String state = entry.path("state").asText();
                    if ("done".equals(state)) {
                        String zipUrl = entry.path("full_zip_url").asText();
                        if (zipUrl.isBlank()) {
                            throw new IOException("MinerU 任务完成但未返回结果地址");
                        }
                        HttpRequest dlReq = HttpRequest.newBuilder()
                                .uri(URI.create(zipUrl))
                                .timeout(Duration.ofMinutes(5))
                                .GET()
                                .build();
                        HttpResponse<byte[]> dlResp = httpClient.send(dlReq, HttpResponse.BodyHandlers.ofByteArray());
                        if (dlResp.statusCode() != 200) {
                            throw new IOException("MinerU 结果下载失败 HTTP " + dlResp.statusCode());
                        }
                        log.info("MinerU 解析完成：{}（zip {} KB）", fileName, dlResp.body().length / 1024);
                        return dlResp.body();
                    }
                    if ("failed".equals(state)) {
                        String msg = entry.path("err_msg").asText("未知错误");
                        throw new IOException("MinerU 解析失败：" + sanitize(msg, mineruKey));
                    }
                }
                Thread.sleep(POLL_INTERVAL_MS);
            }
            throw new IOException("MinerU 解析超时（>5 分钟），请稍后重试");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("MinerU 解析被中断", e);
        }
    }

    private JsonNode postJson(String url, ObjectNode payload, String key, int timeoutMs) throws IOException {
        try {
            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .timeout(Duration.ofMillis(timeoutMs))
                    .header("Content-Type", "application/json")
                    .header("Authorization", "Bearer " + key)
                    .POST(HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(payload), StandardCharsets.UTF_8))
                    .build();
            HttpResponse<String> resp = httpClient.send(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (resp.statusCode() != 200) {
                throw new IOException("MinerU API 错误 HTTP " + resp.statusCode() + "：" + sanitize(resp.body(), key));
            }
            JsonNode root = objectMapper.readTree(resp.body());
            if (root.path("code").asInt() != 0) {
                throw new IOException("MinerU API 错误：" + sanitize(root.path("msg").asText("未知错误"), key));
            }
            return root;
        } catch (IOException e) {
            throw e;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("MinerU API 调用被中断", e);
        } catch (Exception e) {
            throw new IOException("MinerU API 调用失败：" + sanitize(String.valueOf(e), key));
        }
    }

    private JsonNode getJson(String url, String key, int timeoutMs) throws IOException {
        try {
            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .timeout(Duration.ofMillis(timeoutMs))
                    .header("Authorization", "Bearer " + key)
                    .GET()
                    .build();
            HttpResponse<String> resp = httpClient.send(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (resp.statusCode() != 200) {
                throw new IOException("MinerU API 错误 HTTP " + resp.statusCode() + "：" + sanitize(resp.body(), key));
            }
            JsonNode root = objectMapper.readTree(resp.body());
            if (root.path("code").asInt() != 0) {
                throw new IOException("MinerU API 错误：" + sanitize(root.path("msg").asText("未知错误"), key));
            }
            return root;
        } catch (IOException e) {
            throw e;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("MinerU API 调用被中断", e);
        } catch (Exception e) {
            throw new IOException("MinerU API 调用失败：" + sanitize(String.valueOf(e), key));
        }
    }

    // ==================== 结果重建 ====================

    /**
     * 从结果 zip 重建 ParseResult（package-private：供同包测试直连离线 zip 验证合并逻辑）：
     * - content_list_v2.json（页面数组 → 块数组，阅读顺序）
     * - images/ 图片文件
     * - 跳过 page_header/page_footer/page_number 块
     * - 装饰图过滤：第一个含题号段落之前的图片跳过
     * - 图片转 PNG（现有管道的临时文件契约 {N}.png）
     * - 碎图合并：同页连续图块（仅夹数字圈标注）按 bbox 相邻合并
     */
    DocumentParserService.ParseResult rebuild(byte[] zipBytes, String fileName, String mineruKey) throws IOException {
        //解包：content_list JSON + 图片文件
        byte[] clBytes = null;
        Map<String, byte[]> images = new HashMap<>();
        try (ZipInputStream zis = new ZipInputStream(new ByteArrayInputStream(zipBytes))) {
            ZipEntry entry;
            while ((entry = zis.getNextEntry()) != null) {
                String name = entry.getName();
                if (!entry.isDirectory()) {
                    byte[] data = readAll(zis);
                    if (name.endsWith("_content_list_v2.json")) {
                        clBytes = data;
                    } else if (name.startsWith("images/") && looksLikeImage(name)) {
                        images.put(name, data);
                    }
                }
            }
        }
        if (clBytes == null) {
            throw new IOException("MinerU 结果缺少 content_list_v2.json（可能解析未完成）");
        }

        JsonNode pages;
        try {
            pages = objectMapper.readTree(clBytes);
        } catch (Exception e) {
            throw new IOException("MinerU 结果解析失败：" + sanitize(String.valueOf(e), mineruKey));
        }
        if (!pages.isArray() || pages.isEmpty()) {
            throw new IOException("MinerU 结果为空");
        }

        //收集：每页展平为带 bbox 的条目（text/image/tag），同时记录"第一个含题号文本块的页"。
        //文本块即使内容为空也保留顺序信息，但空内容无输出意义 → 直接跳过。
        //噪声文本（选项字母 A/B、答案码 A.A 等）在收集时剔除：它们是 vlm 从版式识别的
        //选项标签/识别噪声，与图块交错会误导模型；按版面排序后它们位于图行下方，无需打断图链。
        List<List<PageItem>> pageItems = new ArrayList<>();
        int firstQuestionPage = -1;
        //选项宽图切分候选（identity 语义：PageItem 是 record，同页内不会有两个字段全同的图，但保险用 identity）
        java.util.IdentityHashMap<PageItem, Boolean> splitWideImages = new java.util.IdentityHashMap<>();
        for (int p = 0; p < pages.size(); p++) {
            JsonNode page = pages.get(p);
            List<PageItem> items = new ArrayList<>();
            if (page.isArray()) {
                for (JsonNode node : page) {
                    String type = node.path("type").asText("");
                    //页眉/页脚/页码剔除（MinerU 已分类，属设计行为）
                    if (type.startsWith("page_")) {
                        continue;
                    }
                    if ("image".equals(type) || "chart".equals(type)) {
                        String imgPath = node.path("content").path("image_source").path("path").asText("");
                        float[] bx = parseBbox(node.path("bbox"));
                        //极小图（短边 < 24pt：数字圈 ② 被识别为 image、客户端小图标等版式噪声）→ 丢弃
                        if (bx != null && Math.min(bx[2] - bx[0], bx[3] - bx[1]) < MIN_IMAGE_SIDE) {
                            log.debug("MinerU 跳过极小噪声图：{} {}", imgPath,
                                    String.format("[%.0f,%.0f,%.0f,%.0f]", bx[0], bx[1], bx[2], bx[3]));
                            continue;
                        }
                        items.add(new PageItem("image", null, imgPath, bx));
                        continue;
                    }
                    List<String> texts = new ArrayList<>();
                    collectTexts(node.path("content"), texts);
                    String joined = String.join("", texts);
                    if (isLayoutNoise(joined)) {
                        //噪声行（选项字母/码行）保留为 noise 条目：不输出文本、切断图链，
                        //并供"选项宽图切分"判定（宽图下方紧邻整行选项码 = 横排选项图，可列间隙切分）。
                        //单个字母（"A"）与"B." 短码不参与切分信号（isOptionCodeLine 过滤）。
                        items.add(new PageItem("noise", joined, null, parseBbox(node.path("bbox"))));
                        continue;
                    }
                    if (joined.length() > MAX_TEXT_PER_BLOCK) {
                        joined = joined.substring(0, MAX_TEXT_PER_BLOCK);
                    }
                    //公式块包 LaTeX 定界符（无 $ 时）
                    if (isEquationType(type) && !joined.contains("$")) {
                        joined = "$$" + joined + "$$";
                    }
                    //嵌入题号拆行：vlm 把"题号+选项首字"误拼进上一段落（实测 "拓1.宽"、"谈3.判"），
                    //导致题号不在行首 → 分块边界检测失败（40 题只剩 19 边界 → 2 大块 → 模型劣化丢题）。
                    //拆成行首后边界恢复，切块粒度正常（每块约 10-12 题）。排除小数/年份（前后为数字）。
                    joined = splitEmbeddedQuestionNums(joined);
                    if (joined.isBlank()) {
                        continue;
                    }
                    if (firstQuestionPage < 0 && QUESTION_NUM.matcher(joined).find()) {
                        firstQuestionPage = p;
                    }
                    //数字圈标注（①②…/纯数字 ≤2 字符，六图间编号）：不输出文本，也不切断图链
                    String kind = isNumericTag(joined) ? "tag" : "text";
                    items.add(new PageItem(kind, joined, null, parseBbox(node.path("bbox"))));
                }
            }
            //版面排序：content_list 块序偶发乱序（实测 Q2 选项图被排到 Q1 段落之前），
            //文本流必须按 bbox 版面顺序重建（y0 升序，同带按 x0 升序）——文本块本身 y 递增，
            //排序只修正图片归属；噪声/数字圈已标记，不会污染输出。
            items.sort(PageItem::compareByLayout);
            //选项宽图切分候选标记：宽图（宽高比 ≥ 2.2、高 ≤ 300pt）且紧邻其后的条目是
            //"整行选项码"噪声（"A B C D A.A B.B C.C D.D"——vlm 对横排选项图下字母行的识别，
            //实测纯图 PDF Q14：MinerU 把 4 个横排选项图并成 1 张宽图，需按列间隙切回单图选项）。
            //条件严格防误切：题干 1x6 宽图后邻是选项图（image）非码行；单选项图宽高比 ≈1。
            for (int k = 0; k + 1 < items.size(); k++) {
                PageItem it = items.get(k);
                if (!"image".equals(it.kind()) || it.bbox() == null) {
                    continue;
                }
                float w = it.bbox()[2] - it.bbox()[0];
                float h = it.bbox()[3] - it.bbox()[1];
                if (h <= 0 || w / h < 2.2f || h > 300f) {
                    continue;
                }
                PageItem next = items.get(k + 1);
                if (!"noise".equals(next.kind()) || next.bbox() == null || next.text() == null) {
                    continue;
                }
                float gapY = next.bbox()[1] - it.bbox()[3];
                if (gapY < 0f || gapY > 80f) {
                    continue;
                }
                if (!isOptionCodeLine(next.text())) {
                    continue;
                }
                splitWideImages.put(it, Boolean.TRUE);
                log.debug("MinerU 选项宽图切分候选：{}（{}x{}pt，下方选项码行）", it.imgPath(),
                        Math.round(w), Math.round(h));
            }
            pageItems.add(items);
        }

        //输出：按版面顺序重建文本流 + 图片列表（装饰过滤 + 碎图合并）。
        //碎图合并：MinerU 把同一图形组切成多张小图（六图分类 = 6 张），发给模型时
        //"图多 → 归属错乱/散进题干"（实测单次 vision 调用 >15 图即劣化，纯图 PDF 每页可达 17 张）。
        //合并判定（确定性，不依赖块序）：连续 image 条目（仅允许夹数字圈 tag）构成图链；
        //链内按 bbox 行聚类，若构成 2-3 行的对齐网格（每行 ≥2 图、各行图数一致、列 x 对齐、
        //行距 ≤ 200pt 防跨题）→ 按 bbox 并集画布保版式合并为一张（六图 3x2 / 九宫格）。
        //选项行（1 行 4 图）、题干图+选项行（行大小 1+4 不对齐）等 → 逐张输出。
        List<DocumentParserService.ExtractedImage> extracted = new ArrayList<>();
        List<String> rebuiltPages = new ArrayList<>();
        float pageHeight = estimatePageHeight(pages);
        for (int p = 0; p < pages.size(); p++) {
            List<PageItem> items = p < pageItems.size() ? pageItems.get(p) : List.of();
            //无有效文本页判定：整页 text 条目经垃圾行过滤后为空（纯广告尾页"扫一扫 对答案"等）→
            //该页图片一并丢弃（图随文本块发模型会污染题尾；页眉/封面图已由装饰过滤处理）
            boolean hasText = false;
            for (PageItem it : items) {
                if ("text".equals(it.kind()) && !filterJunkLines(it.text()).isBlank()) {
                    hasText = true;
                    break;
                }
            }
            StringBuilder pageSb = new StringBuilder();
            List<PageItem> chain = new ArrayList<>(); //待结算的连续图链
            for (PageItem item : items) {
                if ("image".equals(item.kind())) {
                    if (firstQuestionPage > p) {
                        //封面/目录整页装饰图（位于第一个含题号页之前）→ 丢弃（不并入链）。
                        //按页过滤：MinerU 块序偶发把正文页图片排到题号段落前，按图计数会误删（实测 Q2 选项图）。
                        flushImageChain(chain, images, extracted, pageSb, pageHeight, p, splitWideImages);
                        log.debug("MinerU 跳过装饰图：{}", item.imgPath());
                        continue;
                    }
                    if (!hasText) {
                        //无有效文本页（纯广告/插图页）→ 图片丢弃
                        flushImageChain(chain, images, extracted, pageSb, pageHeight, p, splitWideImages);
                        log.debug("MinerU 跳过无文本页图片：{}", item.imgPath());
                        continue;
                    }
                    chain.add(item);
                    continue;
                }
                //tag（数字圈）夹在图链中 → 并入链不输出、不切断；否则孤立 tag 丢弃
                if ("tag".equals(item.kind())) {
                    if (chain.isEmpty()) {
                        continue;
                    }
                    chain.add(item); //仅占位不断链（flush 时忽略）
                    continue;
                }
                //noise（选项字母/码行）→ 结算图链，不输出文本（同 text 但内容为版式噪声）
                if ("noise".equals(item.kind())) {
                    flushImageChain(chain, images, extracted, pageSb, pageHeight, p, splitWideImages);
                    continue;
                }
                //普通文本 → 结算图链后输出
                flushImageChain(chain, images, extracted, pageSb, pageHeight, p, splitWideImages);
                pageSb.append(filterJunkLines(item.text())).append('\n');
            }
            flushImageChain(chain, images, extracted, pageSb, pageHeight, p, splitWideImages);
            rebuiltPages.add(pageSb.toString());
        }

        String fileType = detectType(fileName);
        //text = 逐页拼接（与 pageTexts 一致，保证 chatChunked 的页偏移表映射正确）
        String text = String.join("", rebuiltPages);
        if (text.isBlank()) {
            throw new IOException("MinerU 解析结果为空（无文本内容）");
        }
        log.info("MinerU 重建完成：{} 文本 {} 字符，图片 {} 张", fileName, text.length(), extracted.size());
        return new DocumentParserService.ParseResult(fileType, text, List.of(), rebuiltPages, extracted,
                "已使用 MinerU 云端结构化解析（版面/OCR/公式/表格）", 0, java.util.Set.of());
    }

    /**
     * 图链结算（chain 含连续 image + 可选 tag 占位）：
     * 1) 整链若构成 2-3 行的对齐网格（六图 3x2、九宫格 3x3）→ 整链合并为一张；
     * 2) 否则按行独立判定：行内 ≥6 张且高度相近 → 该行合并（1x6 序列题干/六图 1 行 6 列被 MinerU 切碎）；
     *    行内 <6 张（选项行 ≤4、题干图+选项行 5 张、题干切 2 段）→ 逐张输出。
     */
    private void flushImageChain(List<PageItem> chain, Map<String, byte[]> images,
                                 List<DocumentParserService.ExtractedImage> extracted,
                                 StringBuilder pageSb, float pageHeight, int pageNo,
                                 java.util.IdentityHashMap<PageItem, Boolean> splitWideImages) {
        if (chain.isEmpty()) {
            return;
        }
        try {
            //取出真实图片条目（tag 占位忽略）
            List<PageItem> imgs = new ArrayList<>();
            for (PageItem it : chain) {
                if ("image".equals(it.kind())) {
                    imgs.add(it);
                }
            }
            if (imgs.isEmpty()) {
                return;
            }
            List<byte[]> raws = new ArrayList<>();
            List<float[]> boxes = new ArrayList<>();
            List<PageItem> valid = new ArrayList<>();
            for (PageItem it : imgs) {
                byte[] raw = it.imgPath() == null ? null : images.get(it.imgPath());
                if (raw == null) {
                    log.warn("MinerU 图片文件缺失：{}", it.imgPath());
                    continue;
                }
                if (it.bbox() == null) {
                    continue; //无 bbox 无法定位 → 单张输出（不入链合并）
                }
                raws.add(raw);
                boxes.add(it.bbox());
                valid.add(it);
            }
            if (raws.isEmpty()) {
                return;
            }
            //行聚类
            List<List<Integer>> rows = clusterRows(boxes);
            boolean mergedWhole = false;
            //1) 整链网格合并
            if (isMergeableGrid(boxes, rows)) {
                byte[] merged = mergeAdjacentImages(raws, boxes);
                if (merged != null) {
                    outputImage(extracted, pageSb, pageHeight, pageNo, merged, boxes.get(0));
                    mergedWhole = true;
                }
            }
            if (!mergedWhole) {
                //2) 行级：≥6 张等高行合并，其余逐张
                int used = 0;
                float lastOutCy = Float.NaN;
                for (List<Integer> row : rows) {
                    if (row.size() >= 6 && row.size() <= 9 && rowHeightSimilar(boxes, row)) {
                        List<byte[]> rowRaw = new ArrayList<>();
                        List<float[]> rowBox = new ArrayList<>();
                        for (int idx : row) {
                            rowRaw.add(raws.get(idx));
                            rowBox.add(boxes.get(idx));
                        }
                        byte[] merged = mergeAdjacentImages(rowRaw, rowBox);
                        if (merged != null) {
                            float rowCy = rowBox.get(0)[1] + (rowBox.get(0)[3] - rowBox.get(0)[1]) / 2;
                            if (!Float.isNaN(lastOutCy) && Math.abs(rowCy - lastOutCy) > ROW_TOL) {
                                pageSb.append('\n'); //跨行图组换行分隔（题干图行/选项图行在文本流分组可见）
                            }
                            outputImage(extracted, pageSb, pageHeight, pageNo, merged, rowBox.get(0));
                            lastOutCy = rowCy;
                            used += row.size();
                            continue;
                        }
                    }
                    float rowCy = Float.NaN;
                    for (int idx : row) {
                        byte[] one = toPngBytes(raws.get(idx));
                        if (one != null) {
                            float cy = (boxes.get(idx)[1] + boxes.get(idx)[3]) / 2;
                            if (!Float.isNaN(lastOutCy) && !Float.isNaN(cy) && Math.abs(cy - lastOutCy) > ROW_TOL) {
                                pageSb.append('\n'); //跨行分隔：题干图行结束后换行再输出选项行
                            }
                            //选项宽图切分：MinerU 把横排选项图并成一张宽图（下方有整行选项码），
                            //按列间隙切回单图选项（pdfDirect 路线同款思路）。失败回退原图。
                            PageItem item = valid.get(idx);
                            boolean split = splitWideImages != null
                                    && splitWideImages.containsKey(item)
                                    && row.size() == 1; //独立成行才切（避免题干 1x6 与选项图同链时误切）
                            if (split) {
                                List<byte[]> parts = splitWideOptionImage(raws.get(idx));
                                if (parts.size() >= 2) {
                                    float[] bb = boxes.get(idx);
                                    float partW = (bb[2] - bb[0]) / parts.size();
                                    for (int s = 0; s < parts.size(); s++) {
                                        float[] partBox = {bb[0] + s * partW, bb[1],
                                                bb[0] + (s + 1) * partW, bb[3]};
                                        outputImage(extracted, pageSb, pageHeight, pageNo, parts.get(s), partBox);
                                        log.debug("MinerU 选项宽图切分：{} → 第 {}/{} 段", item.imgPath(), s + 1, parts.size());
                                    }
                                    lastOutCy = cy;
                                    continue;
                                }
                                log.warn("MinerU 选项宽图切分失败（回退原图）：{}", item.imgPath());
                            }
                            outputImage(extracted, pageSb, pageHeight, pageNo, one, boxes.get(idx));
                            lastOutCy = cy;
                        }
                    }
                    if (Float.isNaN(rowCy) && !row.isEmpty()) {
                        float cy = (boxes.get(row.get(0))[1] + boxes.get(row.get(0))[3]) / 2;
                        lastOutCy = cy;
                    }
                }
                if (used > 0) {
                    log.debug("MinerU 行级合并：{} 张 → 1 张", used);
                }
            }
        } catch (Exception e) {
            log.warn("MinerU 图链结算失败：{}", e.getMessage());
        } finally {
            chain.clear();
        }
    }

    /** 行距容差（同一行）：中心 y 差 ≤ 此值视为同一行 */
    private static final float ROW_TOL = 25f;    /** 列对齐容差：相邻行同列 x0 差 ≤ 此值视为对齐 */
    private static final float COL_TOL = 12f;
    /** 行距上限：相邻行中心距 ≤ 200pt（防跨题误并） */
    private static final float ROW_GAP_MAX = 200f;
    /** 行内等高容差：行内最高/最矮 ≤ 此值（1x6 序列碎图等高） */
    private static final float ROW_HEIGHT_RATIO = 1.8f;
    /** 极小噪声图阈值：短边 < 此值（pt）丢弃（数字圈 ②、客户端小图标等被识别为 image 的版式噪声） */
    private static final float MIN_IMAGE_SIDE = 24f;
    /** 选项宽图判定：宽高比 ≥ 2.2 且高 ≤ 300pt（横排多图选项；题干 1x6 也是宽图但后邻是选项图非码行） */
    private static final float WIDE_OPTION_RATIO = 2.2f;
    private static final float WIDE_OPTION_MAX_H = 300f;

    /**
     * 整行选项码判定（选项宽图切分的版面信号）：噪声行去除空格/点号后仍 ≥4 个字母字符
     * （"A B C D A.A B.B C.C D.D" 等 vlm 对横排选项图下字母行的识别）。
     * 单字母（"A"）、"B." 短码（Q11 竖排选项的字母标签）不满足 → 不触发切分。
     */
    private boolean isOptionCodeLine(String text) {
        if (text == null) {
            return false;
        }
        String t = text.trim();
        String compact = t.replaceAll("[\\s.．、]", "");
        return compact.length() >= 4 && compact.matches("[A-Da-d]+");
    }

    /**
     * 选项宽图按列切分：对"横排 N 个候选图并成一张"的宽图切回单图选项。
     * 两种版面信号（取都满足等宽校验者）：
     * 1) 近白缝：面板间空白列（扫描件白底可能有噪点 → 容差 h*2%）连续段中点；
     * 2) 全高框线：带黑框面板紧贴排列时框线列（内容 ≥ h*70% 连续段）中心（实测 Q14：604x162 四面板，
     *    框线在 x≈155/304/452）。图片左右边缘框线不算内部切点。
     * 切点 = 内部候选集；期望段数 = 候选数+1（2-5 段）；各切点须接近等分位置
     * （偏差 ≤ 段宽 40%），切出段宽 max/min ≤ 1.6（防 1x6 题干序列 6 格 5 缝被选 3 缝切出 2+1+2+1 不等宽）。
     * 失败返回空列表（调用方回退原图）。
     */
    private List<byte[]> splitWideOptionImage(byte[] png) {
        try {
            BufferedImage img = ImageIO.read(new ByteArrayInputStream(png));
            if (img == null || img.getWidth() < 200 || img.getHeight() < 20
                    || (float) img.getWidth() / img.getHeight() < WIDE_OPTION_RATIO) {
                return List.of();
            }
            int w = img.getWidth();
            int h = img.getHeight();
            //每列非白像素计数（阈值 <240 视为内容）
            int[] col = new int[w];
            for (int x = 0; x < w; x++) {
                int c = 0;
                for (int y = 0; y < h; y++) {
                    int rgb = img.getRGB(x, y);
                    int r = (rgb >> 16) & 0xFF, g = (rgb >> 8) & 0xFF, b = rgb & 0xFF;
                    if (r < 240 || g < 240 || b < 240) {
                        c++;
                    }
                }
                col[x] = c;
            }
            //候选切点（内部；去重容差 5px）
            TreeSet<Integer> cand = new TreeSet<>();
            //通道 1：近白缝段中点（列内容 ≤ h*2%，连续 ≥3px）
            collectCutCandidates(col, w, h, 0.02f, 3, true, cand);
            //通道 2：全高框线段中心（列内容 ≥ h*70%，连续 ≥2px）
            collectCutCandidates(col, w, h, 0.70f, 2, false, cand);
            if (cand.size() < 2) {
                return List.of();
            }
            List<Integer> cuts = new ArrayList<>(cand);
            //内容区（去掉左右边缘空白：首尾候选若在边缘 5% 内视为边缘，不算切点——通道已只收内部段，
            //此处直接校验切点都在中部 90% 内）
            int contentL = 0;
            int contentR = w - 1;
            int edge = Math.max(2, w / 20);
            List<Integer> internal = new ArrayList<>();
            for (int c : cuts) {
                if (c > edge && c < w - edge) {
                    internal.add(c);
                }
            }
            if (internal.size() < 2) {
                return List.of();
            }
            int parts = internal.size() + 1;
            if (parts < 2 || parts > 5) {
                return List.of(); //候选过多 → 图内有多余全高结构，放弃防误切
            }
            //等分位置校验 + 段宽一致性
            float segW = w / (float) parts;
            for (int k = 1; k < parts; k++) {
                float target = segW * k;
                int best = internal.get(0);
                float bestDist = Float.MAX_VALUE;
                for (int c : internal) {
                    float d = Math.abs(c - target);
                    if (d < bestDist) {
                        bestDist = d;
                        best = c;
                    }
                }
                if (bestDist > segW * 0.4f) {
                    return List.of();
                }
            }
            //按等分最近的候选排序切分
            List<Integer> sortedCuts = new ArrayList<>();
            for (int k = 1; k < parts; k++) {
                float target = segW * k;
                int best = internal.get(0);
                float bestDist = Float.MAX_VALUE;
                for (int c : internal) {
                    float d = Math.abs(c - target);
                    if (d < bestDist) {
                        bestDist = d;
                        best = c;
                    }
                }
                if (!sortedCuts.contains(best)) {
                    sortedCuts.add(best);
                }
            }
            if (sortedCuts.size() != parts - 1) {
                return List.of();
            }
            sortedCuts.sort(Integer::compareTo);
            //段宽一致性
            List<Integer> segs = new ArrayList<>();
            int prev = 0;
            for (int c : sortedCuts) {
                segs.add(c - prev);
                prev = c;
            }
            segs.add(w - prev);
            int minW = Integer.MAX_VALUE, maxW = 0;
            for (int s : segs) {
                minW = Math.min(minW, s);
                maxW = Math.max(maxW, s);
            }
            if (maxW / (float) Math.max(1, minW) > 1.6f) {
                return List.of();
            }
            //裁切子图（各段边界取切点，白底补边 1px）
            List<byte[]> out = new ArrayList<>();
            int x0 = 0;
            for (int c : sortedCuts) {
                out.add(cropPng(img, x0, c));
                x0 = c;
            }
            out.add(cropPng(img, x0, w));
            return out;
        } catch (Exception e) {
            return List.of();
        }
    }

    /** 收集候选切点：白缝模式（列内容占比 ≤ ratio 的段，中点）；框线模式（列内容占比 ≥ ratio 的段，中心） */
    private void collectCutCandidates(int[] col, int w, int h, float ratio, int minLen,
                                      boolean whiteGap, TreeSet<Integer> out) {
        boolean in = false;
        int s = 0;
        for (int x = 0; x <= w; x++) {
            boolean hit = x < w && (whiteGap ? col[x] <= h * ratio : col[x] >= h * ratio);
            if (hit && !in) {
                in = true;
                s = x;
            } else if (!hit && in) {
                if (x - s >= minLen) {
                    out.add((s + x - 1) / 2);
                }
                in = false;
            }
        }
    }

    /** 裁切 [x0, x1) 列为 PNG（白底补边 1px 防贴边） */
    private byte[] cropPng(BufferedImage src, int x0, int x1) {
        try {
            int pad = 1;
            int w = x1 - x0 + pad * 2;
            BufferedImage out = new BufferedImage(w, src.getHeight(), BufferedImage.TYPE_INT_RGB);
            java.awt.Graphics2D g = out.createGraphics();
            g.setColor(java.awt.Color.WHITE);
            g.fillRect(0, 0, w, out.getHeight());
            g.drawImage(src, pad - x0, 0, null);
            g.dispose();
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            ImageIO.write(out, "png", bos);
            return bos.toByteArray();
        } catch (Exception e) {
            return null;
        }
    }

    /** 按 bbox y 中心聚类成行（每行内按 x0 升序；行间按 y 升序），返回 boxes 下标 */
    private List<List<Integer>> clusterRows(List<float[]> boxes) {
        List<Integer> order = new ArrayList<>();
        for (int i = 0; i < boxes.size(); i++) {
            order.add(i);
        }
        order.sort(Comparator.comparingDouble(i -> (boxes.get(i)[1] + boxes.get(i)[3]) / 2.0));
        List<List<Integer>> rows = new ArrayList<>();
        float lastCy = Float.NaN;
        for (int idx : order) {
            float cy = (boxes.get(idx)[1] + boxes.get(idx)[3]) / 2;
            if (rows.isEmpty() || Float.isNaN(lastCy) || cy - lastCy > ROW_TOL) {
                List<Integer> row = new ArrayList<>();
                row.add(idx);
                rows.add(row);
            } else {
                rows.get(rows.size() - 1).add(idx);
            }
            lastCy = cy;
        }
        //行内按 x0 排序
        for (List<Integer> row : rows) {
            row.sort(Comparator.comparingDouble(i -> boxes.get(i)[0]));
        }
        return rows;
    }

    /** 行内等高判定：最高 bbox 高 / 最矮 ≤ ROW_HEIGHT_RATIO */
    private boolean rowHeightSimilar(List<float[]> boxes, List<Integer> row) {
        float minH = Float.MAX_VALUE, maxH = 0;
        for (int idx : row) {
            float h = boxes.get(idx)[3] - boxes.get(idx)[1];
            minH = Math.min(minH, h);
            maxH = Math.max(maxH, h);
        }
        return maxH / Math.max(1e-3f, minH) <= ROW_HEIGHT_RATIO;
    }

    /**
     * 网格判定：图 bbox 是否构成 2-3 行的对齐网格（每行 ≥2 图、各行图数一致、
     * 相邻行同列 x0 对齐、行距 ≤ 200pt）。六图 3x2、九宫格 3x3 命中；
     * 选项单行（1 行）、题干图+选项行（1+4 行大小不一）不命中。
     */
    private boolean isMergeableGrid(List<float[]> boxes, List<List<Integer>> rows) {
        if (rows == null || rows.size() < 2 || rows.size() > 3) {
            return false;
        }
        int cols = rows.get(0).size();
        if (cols < 2) {
            return false;
        }
        float prevRowCy = Float.NaN;
        for (List<Integer> row : rows) {
            if (row.size() != cols) {
                return false;
            }
            float rowCy = 0;
            for (int idx : row) {
                rowCy += (boxes.get(idx)[1] + boxes.get(idx)[3]) / 2;
            }
            rowCy /= row.size();
            if (!Float.isNaN(prevRowCy) && rowCy - prevRowCy > ROW_GAP_MAX) {
                return false; //跨题间距过大
            }
            prevRowCy = rowCy;
        }
        //列对齐：每行第 k 列 x0 与首行第 k 列 x0 差 ≤ COL_TOL
        for (int r = 1; r < rows.size(); r++) {
            for (int k = 0; k < cols; k++) {
                int a = rows.get(r).get(k);
                int b = rows.get(0).get(k);
                if (Math.abs(boxes.get(a)[0] - boxes.get(b)[0]) > COL_TOL) {
                    return false;
                }
            }
        }
        return true;
    }

    /** 输出一张图并追加 [图片N] 标记 */
    private void outputImage(List<DocumentParserService.ExtractedImage> extracted, StringBuilder pageSb,
                             float pageHeight, int pageNo, byte[] png, float[] bbox) {
        float sortX = 0f;
        float sortY = 0f;
        if (bbox != null && !Float.isNaN(bbox[0])) {
            sortX = bbox[0];
            sortY = bbox[1];
        }
        extracted.add(new DocumentParserService.ExtractedImage(pageNo, sortX, sortY, pageHeight,
                new AiClientService.ImageData("image/png", png)));
        pageSb.append("[图片").append(extracted.size()).append("]");
    }

    /** bbox [x0,y0,x1,y1] → float[4]；缺失/非法 → null */
    private float[] parseBbox(JsonNode bbox) {
        if (bbox == null || !bbox.isArray() || bbox.size() < 4) {
            return null;
        }
        float[] r = new float[4];
        for (int i = 0; i < 4; i++) {
            double v = bbox.get(i).asDouble();
            if (Double.isNaN(v) || Double.isInfinite(v)) {
                return null;
            }
            r[i] = (float) v;
        }
        if (r[2] <= r[0] || r[3] <= r[1]) {
            return null;
        }
        return r;
    }

    /** 数字圈标注：①②…⑳ / 纯数字（≤2 字符）。选项字母（A-D 码）不是标注，会打断合并（选项图保持单张）。 */
    private boolean isNumericTag(String s) {
        if (s == null) {
            return false;
        }
        String t = s.trim();
        if (t.isEmpty() || t.length() > 2) {
            return false;
        }
        return t.matches("[0-9①-⑳]{1,2}");
    }

    /**
     * 合并同组小图为一张：按各图 bbox 并集画布（白底），图按其 bbox 相对位置摆放，
     * 缩放 = 组内各图"原始像素宽 / bbox 宽"的中位数（同页切图渲染 dpi 一致）。
     * 任一无 bbox/比例异常 → 返回 null（调用方回退单图）。
     */
    private byte[] mergeAdjacentImages(List<byte[]> raws, List<float[]> boxes) {
        try {
            List<BufferedImage> imgs = new ArrayList<>();
            List<float[]> valid = new ArrayList<>();
            for (int i = 0; i < raws.size(); i++) {
                BufferedImage bi = ImageIO.read(new ByteArrayInputStream(raws.get(i)));
                if (bi == null) {
                    return null;
                }
                float[] bx = boxes.get(i);
                if (bx == null) {
                    return null;
                }
                imgs.add(bi);
                valid.add(bx);
            }
            //公共 dpi（px per pt）：各图 宽像素/bbox 宽，取中位数
            List<Float> dpis = new ArrayList<>();
            for (int i = 0; i < imgs.size(); i++) {
                float bw = valid.get(i)[2] - valid.get(i)[0];
                if (bw <= 0) {
                    return null;
                }
                dpis.add(imgs.get(i).getWidth() / bw);
            }
            dpis.sort(Float::compare);
            float dpi = dpis.get(dpis.size() / 2);
            //并集 bbox
            float minX = Float.MAX_VALUE, minY = Float.MAX_VALUE, maxX = -Float.MAX_VALUE, maxY = -Float.MAX_VALUE;
            for (float[] bx : valid) {
                minX = Math.min(minX, bx[0]);
                minY = Math.min(minY, bx[1]);
                maxX = Math.max(maxX, bx[2]);
                maxY = Math.max(maxY, bx[3]);
            }
            int w = Math.max(1, Math.round((maxX - minX) * dpi));
            int h = Math.max(1, Math.round((maxY - minY) * dpi));
            //长边上限（防超大画布）
            int maxSide = 3000;
            if (Math.max(w, h) > maxSide) {
                double k = maxSide / (double) Math.max(w, h);
                w = (int) (w * k);
                h = (int) (h * k);
                dpi = (float) (dpi * k);
            }
            BufferedImage canvas = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
            java.awt.Graphics2D g = canvas.createGraphics();
            g.setColor(java.awt.Color.WHITE);
            g.fillRect(0, 0, w, h);
            g.setRenderingHint(java.awt.RenderingHints.KEY_INTERPOLATION,
                    java.awt.RenderingHints.VALUE_INTERPOLATION_BICUBIC);
            for (int i = 0; i < imgs.size(); i++) {
                float[] bx = valid.get(i);
                int x = Math.round((bx[0] - minX) * dpi);
                int y = Math.round((bx[1] - minY) * dpi);
                int iw = Math.max(1, Math.round((bx[2] - bx[0]) * dpi));
                int ih = Math.max(1, Math.round((bx[3] - bx[1]) * dpi));
                g.drawImage(imgs.get(i), x, y, iw, ih, null);
            }
            g.dispose();
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            if (!ImageIO.write(canvas, "png", bos)) {
                return null;
            }
            return bos.toByteArray();
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * 版式噪声块判定：图形题中 vlm 把选项标签/识别噪声识别为独立文本块——
     * 单字母行（"A"）、选项码行（"A."/"A、"）、答案码行（"A.A"/"B.B"）等。
     * 这类块与图块交错会让模型错配（"题干[图片1]A[图片2]B…"）；选项标签由模型输出，文本流不需要。
     * 注意：只能匹配"纯字母码"形式，不得误伤真实选项文字（"A.体育课是增强…"不含在内）。
     */
    private boolean isLayoutNoise(String joined) {
        if (joined == null || joined.isEmpty()) {
            return false;
        }
        String t = joined.trim();
        if (t.isEmpty()) {
            return false;
        }
        //单字母 / "A." / "A、" / "A.A" / "A．B"（选项标签与答案码行）
        if (t.matches("^[A-Da-d]$")
                || t.matches("^[A-Da-d][.．、]?$")
                || t.matches("^[A-Da-d]\\s*[.．、]\\s*[A-Da-d]$")) {
            return true;
        }
        //整行选项码（"A B C D A.A B.B C.C D.D"——vlm 把选项行标签+答案码合并成一行文本块）：
        //仅含 A-D 字母与空格/点号 → 版式噪声（选项标签由模型生成，文本流不需要）
        if (t.length() <= 32 && t.matches("[A-Da-d.．、\\s]+") && t.matches(".*[A-Da-d].*")) {
            return true;
        }
        return false;
    }

    /**
     * 过滤 App 页脚/封面提示模板行（vlm 未标记为 footer 的干扰文本）：
     * "扫一扫，对答案"、"打开粉笔客户端"、"提交答案后即可评分并查看解析"、"听课刷题·就用粉笔"、"扫描二维码下载…"。
     * 这些行出现在文档尾部/封面，混入块尾会干扰模型（实测第 40 题后紧跟这些行时被模型漏掉）。
     */
    private String filterJunkLines(String content) {
        if (content == null || content.isBlank()) {
            return content;
        }
        StringBuilder sb = new StringBuilder();
        for (String line : content.split("\\R", -1)) {
            String t = line.trim();
            if (t.isEmpty()) {
                sb.append('\n');
                continue;
            }
            if (t.contains("扫一扫") || t.contains("粉笔客户端") || t.contains("提交答案后即可")
                    || t.contains("听课刷题") || t.contains("扫描二维码下载")) {
                continue;
            }
            //MinerU 偶发的图片引用文本（"images/xxx.jpg"）→ 丢弃（图片已按块提取为 [图片N] 标记；
            //可能是独立行，也可能与材料文本同行："[图片4]images/….jpg2014-2023…"）
            t = t.replaceAll("images/[A-Za-z0-9._-]+\\.(jpg|jpeg|png|webp)", "").trim();
            if (t.isEmpty()) {
                continue;
            }
            sb.append(t).append('\n');
        }
        return sb.toString();
    }

    /**
     * 递归收集 content 对象中的正文字符串。
     * 跳过元数据/样式/坐标键：type、style、bbox、image_source——
     * vlm 的内联 span 形如 {"type":"text","content":"h","style":["italic"]}，
     * 旧实现把 style 值当正文收集 → 文本被污染成 "hitalic"（实测物理卷整卷中招）。
     * 优先收集 latex（公式块 content 可能同时含 text/latex，避免重复）。
     */
    private void collectTexts(JsonNode node, List<String> out) {
        if (node == null || node.isMissingNode()) {
            return;
        }
        if (node.isTextual()) {
            String s = node.asText().trim();
            if (!s.isEmpty()) {
                out.add(s);
            }
            return;
        }
        if (node.isObject()) {
            JsonNode latex = node.get("latex");
            if (latex != null && latex.isTextual() && !latex.asText().isBlank()) {
                out.add(latex.asText().trim());
                return;
            }
            node.properties().forEach(e -> {
                String key = e.getKey();
                if ("type".equals(key) || "style".equals(key)
                        || "bbox".equals(key) || "image_source".equals(key)) {
                    return; //元数据/样式/坐标：不是正文
                }
                collectTexts(e.getValue(), out);
            });
            return;
        }
        if (node.isArray()) {
            node.forEach(n -> collectTexts(n, out));
        }
    }

    private boolean isEquationType(String type) {
        return "equation".equals(type) || "interline_equation".equals(type)
                || "inline_equation".equals(type) || "equation_interline".equals(type);
    }

    /**
     * 把嵌在段落文本中的题号拆成行首（"…拓1.宽…" → "…拓\n1.宽…"）。
     * 约束：题号前后不能是数字/小数点（排除 "2024.5"、"13.5亿" 等数字场景）。
     * 仅用于分块边界恢复；被拆的题号行会进入后续"孤立题号/粘连"防御（现有管道处理）。
     */
    private String splitEmbeddedQuestionNums(String s) {
        return s.replaceAll("(?<![0-9.\\p{N}])([0-9]{1,3})([.、．])(?![0-9.\\p{N}])", "\n$1$2");
    }

    /** 页面高度估算：所有块 bbox 的最大 y + 边距（仅用于 y 归一化近似，chatChunked 分块不依赖） */
    private float estimatePageHeight(JsonNode pages) {
        float maxY = 842f;
        for (JsonNode page : pages) {
            if (!page.isArray()) {
                continue;
            }
            for (JsonNode node : page) {
                JsonNode bbox = node.path("bbox");
                if (bbox.isArray() && bbox.size() >= 4) {
                    maxY = Math.max(maxY, (float) bbox.get(3).asDouble());
                }
            }
        }
        return maxY + 20f;
    }

    /** jpg/png → PNG 字节（现有管道临时文件契约 {N}.png） */
    private byte[] toPngBytes(byte[] data) {
        try {
            BufferedImage img = ImageIO.read(new ByteArrayInputStream(data));
            if (img == null) {
                return null;
            }
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            ImageIO.write(img, "png", out);
            return out.toByteArray();
        } catch (Exception e) {
            return null;
        }
    }

    private boolean looksLikeImage(String name) {
        String lower = name.toLowerCase(Locale.ROOT);
        return lower.endsWith(".jpg") || lower.endsWith(".jpeg") || lower.endsWith(".png")
                || lower.endsWith(".webp") || lower.endsWith(".bmp");
    }

    private String sanitizeDataId(String fileName) {
        String id = fileName.replaceAll("[^A-Za-z0-9_.-]", "_");
        if (id.isBlank()) {
            id = "file";
        }
        return id.length() > 128 ? id.substring(0, 128) : id;
    }

    /** 错误消息中的 Key 一律替换为 *** */
    private String sanitize(String message, String key) {
        if (message == null) {
            return "";
        }
        if (key != null && !key.isBlank()) {
            message = message.replace(key, "***");
        }
        return message.replaceAll("sk-[A-Za-z0-9]{4,}", "sk-***");
    }

    private byte[] readAll(InputStream in) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int n;
        while ((n = in.read(buf)) > 0) {
            out.write(buf, 0, n);
        }
        return out.toByteArray();
    }

    private String detectType(String fileName) {
        String lower = fileName.toLowerCase(Locale.ROOT);
        if (lower.endsWith(".pdf")) {
            return "PDF";
        }
        if (lower.endsWith(".docx") || lower.endsWith(".doc")) {
            return "DOCX";
        }
        return "IMAGE";
    }

    /**
     * 页内条目：kind = text|image|tag。
     * - text：正文（含公式/题号已处理），输出为文本行
     * - image：图片（imgPath = images/ 相对路径）
     * - tag：数字圈标注（①②…/纯数字 ≤2 字符，六图间编号）——不输出、不切断图链
     * bbox 用于版面排序与合并定位；缺失/非法时为 null（按原序、不参与合并）。
     */
    private record PageItem(String kind, String text, String imgPath, float[] bbox) {

        /** 版面排序：y0 升序，同带（差 < 3pt）按 x0 升序；无 bbox 条目保持相对原序（排在最后兜底） */
        static int compareByLayout(PageItem a, PageItem b) {
            float ay = a.bbox() == null ? Float.MAX_VALUE : a.bbox()[1];
            float by = b.bbox() == null ? Float.MAX_VALUE : b.bbox()[1];
            if (Math.abs(ay - by) >= 3f) {
                return Float.compare(ay, by);
            }
            float ax = a.bbox() == null ? Float.MAX_VALUE : a.bbox()[0];
            float bx = b.bbox() == null ? Float.MAX_VALUE : b.bbox()[0];
            return Float.compare(ax, bx);
        }
    }
}
