package com.tiku.service;

import org.apache.pdfbox.contentstream.PDFGraphicsStreamEngine;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.graphics.image.PDImage;
import org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject;
import org.apache.pdfbox.rendering.ImageType;
import org.apache.pdfbox.rendering.PDFRenderer;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.pdfbox.util.Matrix;
import org.apache.poi.hemf.usermodel.HemfPicture;
import org.apache.poi.hwmf.usermodel.HwmfPicture;
import org.apache.poi.xwpf.usermodel.Document;
import org.apache.poi.xwpf.usermodel.IBodyElement;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.XWPFParagraph;
import org.apache.poi.xwpf.usermodel.XWPFPictureData;
import org.apache.poi.xwpf.usermodel.XWPFRun;
import org.apache.poi.xwpf.usermodel.XWPFSDT;
import org.apache.poi.xwpf.usermodel.XWPFTable;
import org.apache.poi.xwpf.usermodel.XWPFTableCell;
import org.apache.xmlbeans.XmlObject;
import org.springframework.stereotype.Service;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.geom.Point2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 文档解析（全部本地）：txt/md 直读，docx→POI，pdf→PDFBox，
 * 扫描件（无文本层）按页转图片交多模态模型，图片直读。
 * PDF 文本层提取后做本地清洗（页眉页脚剥离/小数折行修复/空白归一化/孤立题号合并），
 * 消除"15.8%"折行后行首像题号"15."这类噪声，降低模型误判。
 *
 * v4.1（阶段 4）：PDF（有文本层）/docx 提取**内嵌图片**（题干/选项/材料图），
 * 过滤装饰图（页眉 logo/小图标），按页内视觉顺序排序；逐页文本随 pageTexts 返回，
 * 供分块时按"页"把图片分配到对应文本块。图片编号由 AiImportService 合并时统一分配。
 */
@Service
public class DocumentParserService {

    /** 判定为扫描件（无文本层）的文本量阈值 */
    private static final int SCANNED_PDF_TEXT_THRESHOLD = 200;
    /** 扫描件最多转图页数 */
    private static final int MAX_SCAN_PAGES = 20;
    /** 长文本截断阈值（字符） */
    private static final int MAX_TEXT_LENGTH = 50_000;
    /** 图片大小上限 */
    private static final int MAX_IMAGE_BYTES = 10 * 1024 * 1024;

    /** 页脚模板行：如 "· 本试卷由粉笔用户…生成 第 2 页，共 9 页" */
    private static final Pattern PAGE_FOOTER = Pattern.compile("第\\s*[0-9０-９]+\\s*页\\s*[,，]?\\s*共\\s*[0-9０-９]+\\s*页");
    /** 小数折行：行首 "数字.数字"（如 15.8%、13.5亿），为上一行被折断的小数，需并入上一行 */
    private static final Pattern DECIMAL_WRAP = Pattern.compile("^\\d{1,3}\\.\\d");

    /** 孤立题号行（"1." / "2、"）——题号归位归一化用 */
    private static final Pattern ISOLATED_NUM = Pattern.compile("^\\d{1,3}\\s*[.．、]\\s*$");

    /** 内嵌图片过滤：渲染尺寸（PDF 为页坐标点；docx 用像素）小于该值视为装饰图/小图标 */
    private static final float MIN_IMAGE_SIZE = 50f;
    /** 页眉装饰横条：渲染高度小于该值（如 24pt 的页眉 logo 横条）直接丢弃 */
    private static final float MIN_IMAGE_HEIGHT = 30f;

    /** 解析结果：文本 和/或 图片（多模态直读）；pageTexts = 逐页文本（PDF 有文本层时）；extractedImages = 内嵌图（题干/选项/材料图）；
     *  formulaImageCount = docx 中 WMF/EMF 公式图数量（MathType OLE 渲染；供调用方判断"公式密集 → 强制思考模式保证 LaTeX 转写"）；
     *  formulaImageNos = 公式图在 extracted 中的编号集合（整理后残留公式图 [图片N] 的确定性 LaTeX 转写兜底）。 */
    public record ParseResult(String fileType, String text, List<AiClientService.ImageData> images,
                              List<String> pageTexts, List<ExtractedImage> extractedImages, String warning,
                              int formulaImageCount, java.util.Set<Integer> formulaImageNos) {
    }

    /** 内嵌图片（页内按视觉顺序排序：sortY = CTM 图底 y（页面左下原点）；sortX = CTM x；pageHeight 用于坐标换算；跨页顺序 = 列表顺序，编号由调用方统一分配） */
    public record ExtractedImage(int pageNo, float sortX, float sortY, float pageHeight, AiClientService.ImageData image) {
    }

    /** 文本行坐标：行文本 + 页面坐标（YDirAdj：y = 从页面顶部向下的距离）+ 行高（pt）+ 行内选项字母 x 列表（"A.A B.B" 同行选项用） */
    public record LinePos(String text, float x, float y, float height, List<Float> optXs) {
    }

    /**
     * 收集 PDF 各页文本行坐标（PDFTextStripper 子类；YDirAdj 实测 = 从顶部向下的距离，rotation=0 页面）。
     * 用于"占位符 → 区域裁剪"：题号行/选项行的精确位置 → 从整页渲染图裁剪图形区域。
     */
    public List<List<LinePos>> collectLinePositions(byte[] bytes) throws IOException {
        List<List<LinePos>> pages = new ArrayList<>();
        try (PDDocument doc = PDDocument.load(bytes)) {
            for (int p = 0; p < doc.getNumberOfPages(); p++) {
                List<LinePos> lines = new ArrayList<>();
                PDFTextStripper stripper = new PDFTextStripper() {
                    private final StringBuilder line = new StringBuilder();
                    private final List<Float> optXs = new ArrayList<>();
                    private float x = 0, y = 0, h = 0;

                    @Override
                    protected void writeString(String text, List<org.apache.pdfbox.text.TextPosition> textPositions) {
                        for (int i = 0; i < textPositions.size(); i++) {
                            org.apache.pdfbox.text.TextPosition tp = textPositions.get(i);
                            if (line.length() == 0) {
                                x = tp.getXDirAdj();
                                y = tp.getYDirAdj();
                                h = tp.getHeightDir();
                            }
                            String u = tp.getUnicode();
                            char c = u.isEmpty() ? ' ' : u.charAt(0);
                            line.append(u);
                            //选项字母：A-D 且后跟 .．、（孤立选项标记）
                            if ((c == 'A' || c == 'B' || c == 'C' || c == 'D' || c == 'a' || c == 'b' || c == 'c' || c == 'd')
                                    && i + 1 < textPositions.size()) {
                                String nu = textPositions.get(i + 1).getUnicode();
                                char next = nu.isEmpty() ? ' ' : nu.charAt(0);
                                if (next == '.' || next == '．' || next == '、') {
                                    optXs.add(tp.getXDirAdj());
                                }
                            }
                        }
                    }

                    @Override
                    protected void writeLineSeparator() {
                        if (line.length() > 0) {
                            lines.add(new LinePos(line.toString(), x, y, h,
                                    optXs.isEmpty() ? null : new ArrayList<>(optXs)));
                            line.setLength(0);
                            optXs.clear();
                        }
                    }

                    @Override
                    public void endPage(org.apache.pdfbox.pdmodel.PDPage page) {
                        if (line.length() > 0) {
                            lines.add(new LinePos(line.toString(), x, y, h,
                                    optXs.isEmpty() ? null : new ArrayList<>(optXs)));
                            line.setLength(0);
                            optXs.clear();
                        }
                    }
                };
                stripper.setStartPage(p + 1);
                stripper.setEndPage(p + 1);
                stripper.getText(doc);
                pages.add(lines);
            }
        }
        return pages;
    }

    /** 按文件名+字节解析（异步线程中 MultipartFile 不可用，先落盘再读） */
    public ParseResult parse(String fileName, byte[] bytes) throws IOException {
        String lower = fileName.toLowerCase();

        if (lower.endsWith(".txt") || lower.endsWith(".md") || lower.endsWith(".markdown")) {
            return new ParseResult("TXT", readText(bytes, lower.endsWith(".md") ? "MD" : "TXT"), List.of(), null, List.of(), null, 0, java.util.Set.of());
        }
        if (lower.endsWith(".docx")) {
            return parseDocx(bytes);
        }
        if (lower.endsWith(".doc")) {
            throw new IllegalArgumentException("不支持 .doc 老格式，请用 Word 另存为 .docx 后重试");
        }
        if (lower.endsWith(".pdf")) {
            return parsePdf(bytes);
        }
        if (lower.endsWith(".png") || lower.endsWith(".jpg") || lower.endsWith(".jpeg") || lower.endsWith(".webp") || lower.endsWith(".bmp")) {
            if (bytes.length > MAX_IMAGE_BYTES) {
                throw new IllegalArgumentException("图片过大（超过 10MB）");
            }
            String mime = switch (lower.substring(lower.lastIndexOf('.') + 1)) {
                case "png" -> "image/png";
                case "webp" -> "image/webp";
                case "bmp" -> "image/bmp";
                default -> "image/jpeg";
            };
            return new ParseResult("IMAGE", null, List.of(new AiClientService.ImageData(mime, bytes)),
                    null, List.of(), null, 0, java.util.Set.of());
        }
        throw new IllegalArgumentException("不支持的文件格式：" + fileName + "（支持 txt/md/docx/pdf/图片）");
    }

    // ==================== 具体解析 ====================

    private String readText(byte[] bytes, String type) {
        //优先 UTF-8，失败尝试 GBK（中文老文档）
        String utf8 = new String(bytes, StandardCharsets.UTF_8);
        if (!utf8.contains("\uFFFD")) {
            return truncate(utf8, type);
        }
        return truncate(new String(bytes, Charset.forName("GBK")), type);
    }

    /**
     * docx：按 body 元素顺序（段落/表格交错）提取文本；图片（插图 + MathType OLE 的 WMF/EMF 公式预览图）
     * 在锚点处插入 [图片N] 标记进文本流——与 MinerU 路径同构，下游分块按标记取图（不依赖页归属）。
     * 栅格图（PNG/JPEG/GIF/BMP）缩放保留；WMF/EMF 用 POI HwmfPicture/HemfPicture 渲染为 PNG（4x 放大）。
     * 渲染/解码失败的图片跳过并记 warning。
     * 关键修复：旧实现把 WMF 原始字节标注成 image/png 发给模型（84 张坏图 → 模型劣化缺题错题）。
     */
    private ParseResult parseDocx(byte[] bytes) throws IOException {
        DocxAccumulator acc = new DocxAccumulator();
        try (InputStream in = new ByteArrayInputStream(bytes);
             XWPFDocument doc = new XWPFDocument(in)) {
            for (IBodyElement el : doc.getBodyElements()) {
                if (el instanceof XWPFParagraph p) {
                    processDocxParagraph(doc, p, acc);
                } else if (el instanceof XWPFTable t) {
                    processDocxTable(doc, t, acc);
                } else if (el instanceof XWPFSDT sdt) {
                    String text = sdt.getContent() == null ? null : sdt.getContent().getText();
                    if (text != null && !text.isBlank()) {
                        acc.text.append(text.trim()).append('\n');
                    }
                }
            }
        }
        String warning = acc.skippedImages > 0
                ? "docx 有 " + acc.skippedImages + " 张图片无法解码（矢量渲染失败或未知格式）已跳过，相应内容可能缺失"
                : null;
        String text = truncate(acc.text.toString(), "DOCX");
        return new ParseResult("DOCX", text, List.of(), null, acc.extracted, warning, acc.formulaCount, acc.formulaNos);
    }

    /** docx 命名空间常量（游标按 QName 判断，不依赖 ooxml 完整 schema 类） */
    private static final String NS_WML = "http://schemas.openxmlformats.org/wordprocessingml/2006/main";
    private static final String NS_DRAWINGML = "http://schemas.openxmlformats.org/drawingml/2006/main";
    private static final String NS_VML = "urn:schemas-microsoft-com:vml";
    private static final String NS_REL = "http://schemas.openxmlformats.org/officeDocument/2006/relationships";

    /** docx 解析累积器：文本流 + 内嵌图列表 + 跳过计数 + 公式图计数 + 文档级图片去重（run 级扫描与段落级 VML 扫描会重复命中同一图） */
    private static final class DocxAccumulator {
        final StringBuilder text = new StringBuilder();
        final List<ExtractedImage> extracted = new ArrayList<>();
        final java.util.Set<String> seenParts = new java.util.HashSet<>();
        final java.util.Set<Integer> formulaNos = new java.util.HashSet<>(); //WMF/EMF 公式图编号（残留转写兜底用）
        int skippedImages = 0;
        int formulaCount = 0; //WMF/EMF 公式图数量（MathType OLE 预览渲染）
    }

    /** 段落：按 run 顺序输出文本（tab→空格、换行保留），图片锚点插入 [图片N] 标记 */
    private void processDocxParagraph(XWPFDocument doc, XWPFParagraph p, DocxAccumulator acc) {
        StringBuilder line = new StringBuilder();
        for (XWPFRun run : p.getRuns()) {
            line.append(runInlineText(run));
            appendRunImages(doc, run, line, acc);
        }
        //段落级 VML 图（w:pict 直接挂在段落下的老格式）：追加到段尾
        appendParagraphVmlImages(doc, p.getCTP(), line, acc);
        String text = line.toString().trim();
        if (!text.isEmpty()) {
            acc.text.append(text).append('\n');
        }
    }

    /** 表格：按行输出（单元格内段落/嵌套表格递归处理），保持"|"分隔结构 */
    private void processDocxTable(XWPFDocument doc, XWPFTable table, DocxAccumulator acc) {
        for (var row : table.getRows()) {
            List<String> cells = new ArrayList<>();
            for (XWPFTableCell cell : row.getTableCells()) {
                StringBuilder cellText = new StringBuilder();
                for (XWPFParagraph p : cell.getParagraphs()) {
                    StringBuilder line = new StringBuilder();
                    for (XWPFRun run : p.getRuns()) {
                        line.append(runInlineText(run));
                        appendRunImages(doc, run, line, acc);
                    }
                    appendParagraphVmlImages(doc, p.getCTP(), line, acc);
                    String t = line.toString().trim();
                    if (!t.isEmpty()) {
                        cellText.append(t).append('\n');
                    }
                }
                for (XWPFTable nested : cell.getTables()) {
                    processDocxTable(doc, nested, acc);
                }
                cells.add(cellText.toString().trim());
            }
            acc.text.append(String.join(" | ", cells)).append('\n');
        }
    }

    /** run 内联文本：w:t 原样、w:tab→空格（"A. 1种[tab]B. 2种"→"A. 1种 B. 2种"，选项同行可拆）、w:br/w:cr→换行。
     *  用 selectPath 并集保持文档顺序（XmlCursor token 遍历 + getTextValue 会跳过元素子树，实测提取为空） */
    private String runInlineText(XWPFRun run) {
        XmlObject[] nodes = run.getCTR().selectPath(
                "declare namespace w='" + NS_WML + "' .//w:t | .//w:tab | .//w:br | .//w:cr");
        StringBuilder sb = new StringBuilder();
        for (XmlObject o : nodes) {
            String local = localName(o);
            if ("t".equals(local)) {
                sb.append(elementText(o));
            } else if ("tab".equals(local)) {
                sb.append(' ');
            } else if ("br".equals(local) || "cr".equals(local)) {
                sb.append('\n');
            }
        }
        return sb.toString();
    }

    /** XML 元素本地名（"w:t" → "t"） */
    private String localName(XmlObject o) {
        String name = o.getDomNode().getNodeName();
        int i = name.indexOf(':');
        return i < 0 ? name : name.substring(i + 1);
    }

    /** 元素内文本（DOM Level-1 子节点收集；XMLBeans 的 DOM 实现不支持 getTextContent） */
    private String elementText(XmlObject o) {
        org.w3c.dom.Node dom = o.getDomNode();
        org.w3c.dom.NodeList kids = dom.getChildNodes();
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < kids.getLength(); i++) {
            org.w3c.dom.Node k = kids.item(i);
            if (k.getNodeType() == org.w3c.dom.Node.TEXT_NODE
                    || k.getNodeType() == org.w3c.dom.Node.CDATA_SECTION_NODE) {
                sb.append(k.getNodeValue());
            }
        }
        return sb.toString();
    }

    /** run 内的图片（w:drawing 的 a:blip + w:object 里 OLE 公式的 v:imagedata 预览）→ 追加 [图片N] 标记 */
    private void appendRunImages(XWPFDocument doc, XWPFRun run, StringBuilder line, DocxAccumulator acc) {
        appendImagesInTree(doc, run.getCTR(), line, acc);
    }

    /** 段落级 VML 图（w:pict 老格式）：追加到段落文本末尾 */
    private void appendParagraphVmlImages(XWPFDocument doc, XmlObject pCtp, StringBuilder line, DocxAccumulator acc) {
        appendImagesInTree(doc, pCtp, line, acc);
    }

    /** 在 XML 子树中按文档顺序收集图片关系 id（a:blip 的 r:embed + v:imagedata 的 r:id）→ 追加 [图片N] 标记 */
    private void appendImagesInTree(XWPFDocument doc, XmlObject root, StringBuilder line, DocxAccumulator acc) {
        XmlObject[] nodes = root.selectPath(
                "declare namespace a='" + NS_DRAWINGML + "' declare namespace v='" + NS_VML + "' .//a:blip | .//v:imagedata");
        java.util.LinkedHashSet<String> relIds = new java.util.LinkedHashSet<>();
        for (XmlObject o : nodes) {
            org.w3c.dom.Node dom = o.getDomNode();
            org.w3c.dom.NamedNodeMap attrs = dom.getAttributes();
            org.w3c.dom.Node rel = null;
            if ("blip".equals(localName(o))) {
                rel = attrs == null ? null : attrs.getNamedItemNS(NS_REL, "embed");
            } else {
                rel = attrs == null ? null : attrs.getNamedItemNS(NS_REL, "id");
            }
            if (rel != null && rel.getNodeValue() != null && !rel.getNodeValue().isBlank()) {
                relIds.add(rel.getNodeValue());
            }
        }
        appendImagesByRelIds(doc, relIds, line, acc);
    }

    /** 按关系 id 取图 → 转换为可解码 PNG → 追加 [图片N]（N 全局递增 = extracted 列表下标+1） */
    private void appendImagesByRelIds(XWPFDocument doc, java.util.LinkedHashSet<String> relIds,
                                      StringBuilder line, DocxAccumulator acc) {
        //同一 media 文件被 a:blip 与 VML 回退（mc:AlternateContent）双引、或段落级扫描与 run 级扫描重复命中时
        //只插一个标记：POI 按关系 id 各建一个 part 实例（对象身份不同），必须按 part 名做文档级去重
        java.util.Set<XWPFPictureData> seenObjs = java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>());
        for (String relId : relIds) {
            XWPFPictureData pic;
            try {
                var part = doc.getRelationById(relId);
                if (!(part instanceof XWPFPictureData p)) {
                    continue;
                }
                pic = p;
            } catch (Exception e) {
                continue;
            }
            if (!seenObjs.add(pic)) {
                continue;
            }
            String partName = null;
            try {
                if (pic.getPackagePart() != null) {
                    partName = pic.getPackagePart().getPartName().getName();
                }
            } catch (Exception ignored) {
                //part 名不可用 → 仅按对象身份去重
            }
            if (partName != null && !acc.seenParts.add(partName)) {
                continue;
            }
            //WMF/EMF = MathType OLE 公式预览（矢量）→ 计为公式图（"公式密集"判断：需思考模式保证 LaTeX 转写；
            //编号记录：整理后残留公式图 [图片N] 的确定性 LaTeX 转写兜底）
            boolean isFormula = false;
            try {
                String ext = pic.suggestFileExtension();
                if ("wmf".equalsIgnoreCase(ext) || "emf".equalsIgnoreCase(ext)) {
                    acc.formulaCount++;
                    isFormula = true;
                }
            } catch (Exception ignored) {
                //扩展名不可用 → 不计
            }
            byte[] png = toPortablePng(pic);
            if (png == null) {
                acc.skippedImages++; //part 保留在 seenParts：重复命中只计一次跳过
                continue;
            }
            int n = acc.extracted.size() + 1;
            acc.extracted.add(new ExtractedImage(0, 0f, 0f, 0f,
                    new AiClientService.ImageData("image/png", png)));
            if (isFormula) {
                acc.formulaNos.add(n);
            }
            line.append("[图片").append(n).append("]");
        }
    }

    /**
     * 图片字节 → 可解码 PNG：栅格图缩放；WMF/EMF（MathType 公式等矢量图）用 POI HwmfPicture/HemfPicture
     * 渲染为白底 PNG（4x 放大，公式才可读）。渲染/解码失败 → null 跳过并计入 warning，
     * 绝不把原始矢量字节当 PNG 发给模型（旧实现坏图根因）。
     */
    private byte[] toPortablePng(XWPFPictureData pic) {
        byte[] data = pic.getData();
        if (data == null || data.length == 0 || data.length > MAX_IMAGE_BYTES) {
            return null;
        }
        int picType = pic.getPictureType();
        if (picType == Document.PICTURE_TYPE_WMF || picType == Document.PICTURE_TYPE_EMF) {
            return renderVectorToPng(data, picType);
        }
        try {
            return scaleToPngBytes(data);
        } catch (IOException e) {
            return null;
        }
    }

    /** WMF/EMF → PNG（POI 矢量渲染；ImageIO 不支持矢量格式） */
    private byte[] renderVectorToPng(byte[] data, int picType) {
        try (InputStream in = new ByteArrayInputStream(data)) {
            if (picType == Document.PICTURE_TYPE_WMF) {
                HwmfPicture picture = new HwmfPicture(in);
                return renderVectorPicture(picture, picture.getSize());
            }
            HemfPicture picture = new HemfPicture(in);
            return renderVectorPicture(picture, picture.getSize());
        } catch (Exception e) {
            return null;
        }
    }

    /** 矢量图渲染为白底 PNG（4x 放大，长边 ≤1600px；数学公式 WMF 原尺寸仅数十 pt，需放大才可读）。
     *  画布四周留 50% 边距、内容居中：MathType 公式 WMF 的 placeable bounds 常小于实际绘制范围
     *  （斜体右伸/上标/分数横线溢出），按 bounds 建画布会切字（实测 $mgh$ 的 h 右半被切掉，
     *  模型看残图放弃 LaTeX 转写改引用图片 → 用户看到"截取的公式图"）。 */
    private byte[] renderVectorPicture(Object picture, java.awt.geom.Dimension2D dim) {
        if (dim == null || dim.getWidth() <= 0 || dim.getHeight() <= 0) {
            return null;
        }
        double dw = dim.getWidth();
        double dh = dim.getHeight();
        double scale = 4.0;
        double padRatio = 0.5;
        int w = (int) Math.ceil(dw * scale * (1 + padRatio));
        int h = (int) Math.ceil(dh * scale * (1 + padRatio));
        int maxSide = 1600;
        if (Math.max(w, h) > maxSide) {
            double k = maxSide / (double) Math.max(w, h);
            w = (int) (w * k);
            h = (int) (h * k);
            scale = scale * k; //画布缩小后坐标系同步缩小，内容仍居中
        }
        w = Math.max(1, w);
        h = Math.max(1, h);
        BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = img.createGraphics();
        g.setColor(Color.WHITE);
        g.fillRect(0, 0, w, h);
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
        try {
            double ox = (w - dw * scale) / 2.0;
            double oy = (h - dh * scale) / 2.0;
            g.scale(scale, scale);
            //有界绘制：内容映射到中央矩形（等比 fit 到 bounds，避免 draw(g) 直接按内部坐标溢出画布）
            java.awt.geom.Rectangle2D target = new java.awt.geom.Rectangle2D.Double(ox / scale, oy / scale, dw, dh);
            if (picture instanceof HwmfPicture hwmf) {
                hwmf.draw(g, target);
            } else if (picture instanceof HemfPicture hemf) {
                hemf.draw(g, target);
            }
            g.dispose();
            return toPngBytes(img);
        } catch (IOException e) {
            g.dispose();
            return null;
        }
    }

    private ParseResult parsePdf(byte[] bytes) throws IOException {
        try (PDDocument doc = PDDocument.load(bytes)) {
            //逐页提取文本（保留页边界，供分块按页归属图片）
            PDFTextStripper stripper = new PDFTextStripper();
            List<String> rawPages = new ArrayList<>();
            for (int p = 0; p < doc.getNumberOfPages(); p++) {
                stripper.setStartPage(p + 1);
                stripper.setEndPage(p + 1);
                String pageText = stripper.getText(doc);
                rawPages.add(pageText == null ? "" : pageText);
            }
            //全局行频（页眉页脚剥离依赖跨页重复统计）+ 逐页清洗 → 页偏移表基于清洗后文本（分块映射用）
            List<String> cleanedPages = cleanPdfPages(rawPages);
            String text = String.join("", cleanedPages);
            //文本过少 → 判定为扫描件，按页转图交多模态模型（无内嵌图可提取）
            if (text == null || text.trim().length() < SCANNED_PDF_TEXT_THRESHOLD) {
                List<AiClientService.ImageData> images = new ArrayList<>();
                int pages = Math.min(doc.getNumberOfPages(), MAX_SCAN_PAGES);
                PDFRenderer renderer = new PDFRenderer(doc);
                for (int i = 0; i < pages; i++) {
                    BufferedImage img = renderer.renderImageWithDPI(i, 150, ImageType.RGB);
                    images.add(new AiClientService.ImageData("image/png", toPngBytes(img)));
                }
                String warning = doc.getNumberOfPages() > MAX_SCAN_PAGES
                        ? "扫描件超过 " + MAX_SCAN_PAGES + " 页，已截取前 " + MAX_SCAN_PAGES + " 页"
                        : "扫描件（无文本层），将以图片方式识别";
                return new ParseResult("PDF", null, images, null, List.of(), warning, 0, java.util.Set.of());
            }
            //有文本层：逐页提取内嵌图片（封面页无题，其图片视为装饰跳过）
            List<ExtractedImage> extracted = new ArrayList<>();
            for (int p = 0; p < doc.getNumberOfPages(); p++) {
                String pageText = rawPages.get(p);
                //封面/答案页判定：该页几乎无文本 → 图片视为装饰（宣传横幅/广告），不提取
                if (pageText == null || pageText.trim().length() < SCANNED_PDF_TEXT_THRESHOLD) {
                    continue;
                }
                extracted.addAll(collectPageImages(doc.getPage(p), p));
            }
            return new ParseResult("PDF", truncate(text, "PDF"), List.of(), cleanedPages, extracted, null, 0, java.util.Set.of());
        }
    }

    /**
     * 渲染 PDF 每页为整页图（多模态直读；视觉分页路径用：版式真相——题号位置/图形归属/跨页边界）。
     * 页图按页序返回；与扫描件路径同一渲染参数（150 DPI），保证与内嵌图路径视觉一致。
     */
    public List<AiClientService.ImageData> renderAllPages(byte[] bytes, int dpi) throws IOException {
        return renderAllPages(bytes, dpi, 0f);
    }

    /**
     * 渲染 PDF 每页为整页图；quality > 0 时输出 JPEG（整页图只作版式参考，压缩控制请求体），否则 PNG。
     */
    public List<AiClientService.ImageData> renderAllPages(byte[] bytes, int dpi, float jpegQuality) throws IOException {
        try (PDDocument doc = PDDocument.load(bytes)) {
            List<AiClientService.ImageData> result = new ArrayList<>();
            PDFRenderer renderer = new PDFRenderer(doc);
            for (int i = 0; i < doc.getNumberOfPages(); i++) {
                BufferedImage img = renderer.renderImageWithDPI(i, dpi, ImageType.RGB);
                if (jpegQuality > 0) {
                    result.add(new AiClientService.ImageData("image/jpeg", toJpegBytes(img, jpegQuality)));
                } else {
                    result.add(new AiClientService.ImageData("image/png", toPngBytes(img)));
                }
            }
            return result;
        }
    }

    private byte[] toJpegBytes(BufferedImage img, float quality) throws IOException {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        javax.imageio.ImageWriter writer = javax.imageio.ImageIO.getImageWritersByFormatName("jpg").next();
        javax.imageio.plugins.jpeg.JPEGImageWriteParam param =
                (javax.imageio.plugins.jpeg.JPEGImageWriteParam) writer.getDefaultWriteParam();
        param.setCompressionMode(javax.imageio.ImageWriteParam.MODE_EXPLICIT);
        param.setCompressionQuality(quality);
        writer.setOutput(javax.imageio.ImageIO.createImageOutputStream(bos));
        writer.write(null, new javax.imageio.IIOImage(img, null, null), param);
        writer.dispose();
        return bos.toByteArray();
    }

    /** 提取单页内嵌图片：内容流扫描（CTM 定位）+ 尺寸/页眉过滤 + 页内按 y 降序（上→下）排序 */
    private List<ExtractedImage> collectPageImages(PDPage page, int pageNo) throws IOException {
        float pageHeight = page.getMediaBox() == null ? 842f : page.getMediaBox().getHeight();
        List<ExtractedImage> hits = new ArrayList<>();
        PDFGraphicsStreamEngine engine = new PDFGraphicsStreamEngine(page) {
            @Override public void appendRectangle(Point2D p0, Point2D p1, Point2D p2, Point2D p3) {}
            @Override public void drawImage(PDImage image) {
                if (!(image instanceof PDImageXObject img)) {
                    return;
                }
                try {
                    Matrix ctm = getGraphicsState().getCurrentTransformationMatrix();
                    float w = ctm.getScalingFactorX();
                    float h = ctm.getScalingFactorY();
                    float y = ctm.getTranslateY();
                    //过滤：页眉/页脚区横条（渲染高度过小）与小图（图标/水印）
                    if (h < MIN_IMAGE_HEIGHT || w < MIN_IMAGE_SIZE || h < MIN_IMAGE_SIZE) {
                        return;
                    }
                    //过滤：紧贴页顶的横条装饰（页眉 logo 已按高度过滤，这里兜底页眉区大图）
                    if (y > pageHeight - 60 && h < 80) {
                        return;
                    }
                    BufferedImage bi = img.getImage();
                    if (bi == null) {
                        return;
                    }
                    //最长边 1000px 缩放 + PNG
                    hits.add(new ExtractedImage(pageNo, ctm.getTranslateX(), y, pageHeight,
                            new AiClientService.ImageData("image/png", toPngBytes(scale(bi, 1000)))));
                } catch (Exception ignored) {
                    //单张失败不影响其他
                }
            }
            @Override public void clip(int windingRule) {}
            @Override public void moveTo(float x, float y) {}
            @Override public void lineTo(float x, float y) {}
            @Override public void curveTo(float x1, float y1, float x2, float y2, float x3, float y3) {}
            @Override public Point2D getCurrentPoint() { return new Point2D.Float(0, 0); }
            @Override public void closePath() {}
            @Override public void endPath() {}
            @Override public void strokePath() {}
            @Override public void fillPath(int windingRule) {}
            @Override public void fillAndStrokePath(int windingRule) {}
            @Override public void shadingFill(COSName shadingName) {}
        };
        engine.processPage(page);
        //页内按 y 降序（上→下；页坐标原点在左下，y 大 = 靠上）；同 y 保持内容流顺序
        hits.sort(Comparator.comparing((ExtractedImage e) -> -e.sortY()));
        return hits;
    }

    /**
     * PDF 文本层清洗（本地、确定性）：逐页清洗，保留页边界（页偏移表基于清洗后文本，供分块按页归属图片）。
     * 1) 页眉页脚剥离：全文重复≥3次的相同行 + "第 N 页，共 N 页"模板行（频次跨页统计）；
     * 2) 小数折行修复：行首 "数字.数字"（PDF 把 "15.8%" 折行成 "15." 开头）并入上一行，
     *    避免被误判为题号（曾导致 40 题文档识别成 41 题）；
     * 3) 空白归一化：零宽字符剔除、全角空格转半角、行尾空白清除、连续空行折叠。
     * 注意：孤立题号行（"1." 单独成行）保持原样，不与其后选项合并——合并后块首的
     * "孤儿题号行"会像完整题目，模型不再按提示跳过，且题号会混入题干。
     */
    private List<String> cleanPdfPages(List<String> rawPages) {
        if (rawPages == null || rawPages.isEmpty()) {
            return rawPages;
        }
        //全局行频（页眉页脚剥离：跨页重复≥3次视为模板行）
        Map<String, Integer> freq = new HashMap<>();
        for (String page : rawPages) {
            if (page == null) {
                continue;
            }
            for (String line : page.split("\\R", -1)) {
                String t = line.trim();
                if (!t.isEmpty()) {
                    freq.merge(t, 1, Integer::sum);
                }
            }
        }
        List<String> cleanedPages = new ArrayList<>();
        for (String page : rawPages) {
            cleanedPages.add(page == null ? "" : cleanPage(page, freq));
        }
        return cleanedPages;
    }

    /** 行尾题号归位："下列选项中，属于上图的俯视图的是：2." → 设问行 + "2." 孤立题号行。
     *  仅当数字紧跟设问符号（：？））之后（粉笔 PDF 定义题常见版式）。
     *  不处理长行行尾（"调查督办8."）——误拆"蓬勃发展12."类词内数字会切断句意，实测反而丢题。 */
    private static final Pattern TRAILING_QNO = Pattern.compile("^(.+[：:？?）)]\\s*)(\\d{1,3})\\s*[.．、]\\s*$");

    /** 题干行特征：以句末标点结尾，或含定义/设问引导词。
     *  题号归位向后收集题干行时，遇到既无句末标点又无引导词的行（如选项折行残片"力一扫而空"）即停——
     *  否则会把上一题选项的折行搬进本题干（实测判断推理 Q2："2." 后混入 Q1 选项 D 残行，模型产出垃圾块）。 */
    private static final Pattern STEM_LINE = Pattern.compile("[。！？：:；]$|是指|指的是|下列|属于|根据|据此|从所给|要求|关于|以下|上述|主要|说明|表明|体现|推断|能够|应当|必须|填入|选择|正确|错误|相当于|方式|关系");

    /** 单页清洗（使用全局行频） */
    private String cleanPage(String page, Map<String, Integer> freq) {
        String[] lines = page.split("\\R", -1);
        List<String> step1 = new ArrayList<>();
        for (String line : lines) {
            String t = line.trim();
            if (!t.isEmpty() && (freq.getOrDefault(t, 0) >= 3 || PAGE_FOOTER.matcher(t).find())) {
                continue; //页眉页脚剥离
            }
            if (DECIMAL_WRAP.matcher(t).find() && !step1.isEmpty()) {
                int last = step1.size() - 1;
                step1.set(last, step1.get(last) + t); //小数折行并入上一行
                continue;
            }
            //行尾题号归位（"…的是：2." → 设问行 + "2."），否则分块/补漏检测不到该题号（实测判断推理 Q2 丢失）
            Matcher tm = TRAILING_QNO.matcher(t);
            if (tm.matches()) {
                step1.add(tm.group(1));
                step1.add(tm.group(2) + ".");
                continue;
            }
            step1.add(line);
        }
        //题号归位归一化：定义/设问句在题号行之前（"正向情绪价值…能力。"/"下列…的是："/"1."）时，
        //移到题号行之后——视觉模型对该版式系统性跳过开头题目（实测判断推理 Q1/Q2/Q11-14 丢失），
        //本地确定性重排后模型输入为正常顺序。保护：只移动短段（≤4 行且 ≤300 字符），
        //资料分析的长材料段落保持在题号行之前不动。
        List<String> work = new ArrayList<>(step1);
        for (int idx = 0; idx < work.size(); idx++) {
            String t = work.get(idx).trim();
            if (!ISOLATED_NUM.matcher(t).matches()) {
                continue;
            }
            int j = idx - 1;
            List<String> stem = new ArrayList<>();
            int stemLen = 0;
            while (j >= 0) {
                String prev = work.get(j).trim();
                if (prev.isEmpty()) {
                    break;
                }
                if (ISOLATED_NUM.matcher(prev).matches()
                        || prev.matches("^[A-Ha-h]\\s*[.．、].*")
                        || prev.matches("^(答案|参考答案|解析)[:：]?.*")) {
                    break;
                }
                if (!STEM_LINE.matcher(prev).find()) {
                    break; //选项折行残片/非题干行：停止收集（见 STEM_LINE 注释）
                }
                stem.add(0, prev);
                stemLen += prev.length();
                if (stem.size() > 4 || stemLen > 300) {
                    stem.clear();
                    break; //过长：是材料段落，不动
                }
                j--;
            }
            if (stem.isEmpty()) {
                continue;
            }
            for (int k = idx - 1; k > j; k--) {
                work.remove(k);
            }
            work.addAll(j + 2, stem); //题号行现在位于 j+1，题干插入其后
            idx = j + 1 + stem.size();
        }
        step1 = work;
        //孤立题号行不合并：保持 "1." 单独成行（合并后块首"孤儿题号行"会像完整题目，模型不再跳过，且题号会混入题干）
        //空白归一化
        StringBuilder sb = new StringBuilder();
        int blanks = 0;
        for (String line : step1) {
            String cleaned = line.replaceAll("[\\u200B-\\u200D\\uFEFF]", "")
                    .replace('\u3000', ' ')
                    .replaceAll("[ \\t]+$", "");
            if (cleaned.trim().isEmpty()) {
                if (blanks == 0) {
                    sb.append('\n');
                }
                blanks++;
                continue;
            }
            blanks = 0;
            sb.append(cleaned).append('\n');
        }
        return sb.toString();
    }

    private byte[] toPngBytes(BufferedImage img) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(img, "png", out);
        return out.toByteArray();
    }

    /** 最长边缩放到 maxSide（保持比例）后转 PNG；无法解码（WDP/损坏/矢量图）返回 null，调用方跳过 */
    private byte[] scaleToPngBytes(byte[] data) throws IOException {
        BufferedImage src = ImageIO.read(new ByteArrayInputStream(data));
        if (src == null) {
            return null;
        }
        return toPngBytes(scale(src, 1000));
    }

    private BufferedImage scale(BufferedImage src, int maxSide) {
        int w = src.getWidth(), h = src.getHeight();
        int max = Math.max(w, h);
        if (max <= maxSide) {
            return src;
        }
        double k = maxSide * 1.0 / max;
        int nw = Math.max(1, (int) Math.round(w * k));
        int nh = Math.max(1, (int) Math.round(h * k));
        BufferedImage out = new BufferedImage(nw, nh, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = out.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
        g.drawImage(src, 0, 0, nw, nh, null);
        g.dispose();
        return out;
    }

    private String truncate(String text, String type) {
        if (text.length() <= MAX_TEXT_LENGTH) {
            return text;
        }
        return text.substring(0, MAX_TEXT_LENGTH / 2) + "\n...[内容过长已截断]...\n" + text.substring(text.length() - MAX_TEXT_LENGTH / 2);
    }
}
