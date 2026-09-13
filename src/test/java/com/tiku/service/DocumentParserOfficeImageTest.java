package com.tiku.service;

import org.apache.poi.hssf.usermodel.HSSFClientAnchor;
import org.apache.poi.hssf.usermodel.HSSFPatriarch;
import org.apache.poi.hssf.usermodel.HSSFWorkbook;
import org.apache.poi.sl.usermodel.PictureData;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.xslf.usermodel.XMLSlideShow;
import org.apache.poi.xslf.usermodel.XSLFPictureData;
import org.apache.poi.xslf.usermodel.XSLFPictureShape;
import org.apache.poi.xslf.usermodel.XSLFSlide;
import org.apache.poi.xslf.usermodel.XSLFTextBox;
import org.apache.poi.xssf.usermodel.XSSFClientAnchor;
import org.apache.poi.xssf.usermodel.XSSFDrawing;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * PPT / Excel 的**内嵌图片**本地提取回归（2026-09-13）。
 *
 * 背景（用户第二次反馈）："不开 MinerU 还是提不到图片"。此前 Excel/PPT 只抽文字，
 * 图片没进管线；而 MinerU 只是"识别失效时的最终手段"，质量与成本都不该当默认路径。
 * 本轮把"直接从文件里取原图"这条老路补上（与 docx 同一套机制）：
 *
 *  - **PPT**：`XSLFPictureShape` 逐页取图，按形状顺序就地插 `[图片N]`（标记位置 = 图在页内的位置）；
 *    同一张图片文件在多页复用（页眉 logo/背景）只输出一次；整页只有图的幻灯片也必须保留（纯图片题）。
 *  - **Excel**：`XSSFPicture.getClientAnchor().getRow1()` 给出图片锚定的行 → 标记插在**该行文本之后**；
 *    同一张图被两行共用 → 两行各出一份（Excel 的图是"放在某行上"，不是"文档级一次"）。
 *  - 两者都走 docx 同款契约：标记写进 `text`、`extractedImages` 带图、`pageTexts = null`
 *    （有 pageTexts 时 AiImportService 会再按坐标插一次标记 → 重复标记）。
 */
class DocumentParserOfficeImageTest {

    private final DocumentParserService service = new DocumentParserService();

    // ==================== PPT ====================

    @Test
    void extractsPptxPicturesWithMarkerInSlideOrder() throws Exception {
        byte[] bytes;
        try (XMLSlideShow show = new XMLSlideShow(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            XSLFSlide s1 = show.createSlide();
            XSLFTextBox box = s1.createTextBox();
            box.setText("1. 下图三角形的面积是多少？");
            XSLFPictureData pd = show.addPicture(png(0xFF0000), PictureData.PictureType.PNG);
            s1.createPicture(pd);
            // 第 2 页整页只有一张图（纯图片题）：不能因为"没有文字"被丢掉
            XSLFSlide s2 = show.createSlide();
            s2.createPicture(pd);
            show.write(out);
            bytes = out.toByteArray();
        }

        DocumentParserService.ParseResult r = service.parse("课件.pptx", bytes);

        assertEquals("PPTX", r.fileType());
        assertEquals(1, r.extractedImages().size(), "同一张图片文件复用两页只输出一次");
        assertTrue(r.text().contains("## 第 1 页") && r.text().contains("## 第 2 页"), r.text());
        assertTrue(r.text().contains("[图片1]"), r.text());
        // 标记在题干之后：模型据此知道图属于这一页的这道题
        assertTrue(r.text().indexOf("[图片1]") > r.text().indexOf("三角形的面积"), r.text());
        // pageTexts 必须为空：否则 AiImportService 会再按坐标插一次 [图片1]（重复标记）
        assertNull(r.pageTexts());
        assertTrue(r.images().isEmpty(), "images 是「整份文件就是图片」用的字段，这里应为空");
        assertPng(r.extractedImages().get(0).image().data());
    }

    @Test
    void keepsDistinctPicturesOnSeparateSlides() throws Exception {
        byte[] bytes;
        try (XMLSlideShow show = new XMLSlideShow(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            XSLFSlide s1 = show.createSlide();
            s1.createTextBox().setText("1. 第一题");
            s1.createPicture(show.addPicture(png(0xFF0000), PictureData.PictureType.PNG));
            XSLFSlide s2 = show.createSlide();
            s2.createTextBox().setText("2. 第二题");
            s2.createPicture(show.addPicture(png(0x00FF00), PictureData.PictureType.PNG));
            show.write(out);
            bytes = out.toByteArray();
        }

        DocumentParserService.ParseResult r = service.parse("课件.pptx", bytes);

        assertEquals(2, r.extractedImages().size());
        assertNull(r.pageTexts());
        assertTrue(r.text().contains("[图片1]") && r.text().contains("[图片2]"), r.text());
        // 编号顺序 = 出现顺序：第 1 页第 1 张（红）、第 2 页第 2 张（绿）
        assertTrue(isReddish(firstPixel(r.extractedImages().get(0).image().data())));
        assertTrue(isGreenish(firstPixel(r.extractedImages().get(1).image().data())));
    }

    // ==================== Excel ====================

    @Test
    void attachesXlsxPictureToItsOwnRow() throws Exception {
        byte[] bytes;
        try (XSSFWorkbook wb = new XSSFWorkbook(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            Sheet sheet = wb.createSheet("题库");
            writeRow(sheet, 0, "题干", "答案");
            writeRow(sheet, 1, "1. 看图选答案", "A");
            writeRow(sheet, 2, "2. 再看一题", "B");
            int red = wb.addPicture(png(0xFF0000), Workbook.PICTURE_TYPE_PNG);
            int green = wb.addPicture(png(0x00FF00), Workbook.PICTURE_TYPE_PNG);
            XSSFDrawing drawing = (XSSFDrawing) sheet.createDrawingPatriarch();
            drawing.createPicture(new XSSFClientAnchor(0, 0, 0, 0, 2, 1, 3, 2), red);
            drawing.createPicture(new XSSFClientAnchor(0, 0, 0, 0, 2, 2, 3, 3), green);
            wb.write(out);
            bytes = out.toByteArray();
        }

        DocumentParserService.ParseResult r = service.parse("题库表格.xlsx", bytes);

        assertEquals(2, r.extractedImages().size(), "两行的图都要提取（历史行为：0 张）");
        String text = r.text();
        int q1 = text.indexOf("1. 看图选答案");
        int m1 = text.indexOf("[图片1]");
        int q2 = text.indexOf("2. 再看一题");
        int m2 = text.indexOf("[图片2]");
        assertTrue(q1 >= 0 && m1 > q1, "第 1 张图要跟在第 1 行后面：\n" + text);
        assertTrue(q2 > m1 && m2 > q2, "第 2 张图要跟在第 2 行后面（不能全堆在表尾）：\n" + text);
        assertNull(r.pageTexts());
    }

    @Test
    void keepsSharedPictureOnEveryRowThatUsesIt() throws Exception {
        // 同一张图被两行共用（如"两个答案共用一个图"）→ 两行各出一份，不能像 PPT 那样按文件去重
        byte[] bytes;
        try (XSSFWorkbook wb = new XSSFWorkbook(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            Sheet sheet = wb.createSheet("题库");
            writeRow(sheet, 0, "1. 看图选答案", "A");
            writeRow(sheet, 1, "2. 同图再看", "B");
            int shared = wb.addPicture(png(0xFF0000), Workbook.PICTURE_TYPE_PNG);
            XSSFDrawing drawing = (XSSFDrawing) sheet.createDrawingPatriarch();
            drawing.createPicture(new XSSFClientAnchor(0, 0, 0, 0, 3, 0, 4, 1), shared);
            drawing.createPicture(new XSSFClientAnchor(0, 0, 0, 0, 3, 1, 4, 2), shared);
            wb.write(out);
            bytes = out.toByteArray();
        }

        DocumentParserService.ParseResult r = service.parse("题库表格.xlsx", bytes);

        assertEquals(2, r.extractedImages().size(), "同一张图锚在两行 → 两行各一份");
        assertTrue(r.text().contains("[图片1]") && r.text().contains("[图片2]"), r.text());
    }

    @Test
    void keepsXlsxPictureAnchoredOnEmptyRow() throws Exception {
        // 浮动图/锚在空行：文本行不输出，但图不能丢 → 附在工作表末尾
        byte[] bytes;
        try (XSSFWorkbook wb = new XSSFWorkbook(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            Sheet sheet = wb.createSheet("题库");
            writeRow(sheet, 0, "1. 第一题", "A");
            writeRow(sheet, 2, "2. 第三行才是第二题", "B");
            int pic = wb.addPicture(png(0x0000FF), Workbook.PICTURE_TYPE_PNG);
            XSSFDrawing drawing = (XSSFDrawing) sheet.createDrawingPatriarch();
            // 锚在第 2 行（Excel 的 row index 1 = 第 2 行），该行没有文字 → 空行
            drawing.createPicture(new XSSFClientAnchor(0, 0, 0, 0, 2, 1, 3, 2), pic);
            wb.write(out);
            bytes = out.toByteArray();
        }

        DocumentParserService.ParseResult r = service.parse("题库表格.xlsx", bytes);

        assertEquals(1, r.extractedImages().size(), "锚在空行的图也要保留");
        assertTrue(r.text().contains("[图片1]"), r.text());
    }

    @Test
    void extractsLegacyXlsPicture() throws Exception {
        byte[] bytes;
        try (HSSFWorkbook wb = new HSSFWorkbook(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            Sheet sheet = wb.createSheet("题库");
            writeRow(sheet, 0, "1. 老格式表格里的图", "A");
            int pic = wb.addPicture(png(0xFF0000), Workbook.PICTURE_TYPE_PNG);
            HSSFPatriarch drawing = ((org.apache.poi.hssf.usermodel.HSSFSheet) sheet).createDrawingPatriarch();
            drawing.createPicture(new HSSFClientAnchor(0, 0, 0, 0, (short) 2, 0, (short) 3, 1), pic);
            wb.write(out);
            bytes = out.toByteArray();
        }

        DocumentParserService.ParseResult r = service.parse("老表格.xls", bytes);

        assertEquals("XLS", r.fileType());
        assertEquals(1, r.extractedImages().size(), "老 .xls 的图同样要提取");
        assertTrue(r.text().contains("[图片1]"), r.text());
    }

    // ==================== 真实文件（用户测试用例） ====================

    @Test
    void extractsImagesFromRealTestCases() throws Exception {
        Path pptx = Path.of("src/test/resources/office-image/含图片题型的课件.pptx");
        Path xlsx = Path.of("src/test/resources/office-image/含图片的题库表格.xlsx");
        org.junit.jupiter.api.Assumptions.assumeTrue(Files.exists(pptx) && Files.exists(xlsx),
                "跳过：缺少夹具 " + pptx);

        DocumentParserService.ParseResult p = service.parse("含图片题型的课件.pptx", Files.readAllBytes(pptx));
        assertEquals(6, p.extractedImages().size(), "真实课件里有 6 张插图：\n" + p.text());
        assertTrue(p.text().contains("[图片6]"), p.text());
        assertTrue(p.warning() == null, "不该有解码失败告警：" + p.warning());

        DocumentParserService.ParseResult x = service.parse("含图片的题库表格.xlsx", Files.readAllBytes(xlsx));
        assertEquals(10, x.extractedImages().size(), "题库表格里的 10 处图片锚点都要提取：\n" + x.text());
        assertTrue(x.text().contains("[图片10]"), x.text());
        assertTrue(x.text().contains("## 工作表："), "多工作表要有边界标记：\n" + x.text());
    }

    // ==================== 工具 ====================

    private static void writeRow(Sheet sheet, int rowNum, String... values) {
        Row row = sheet.createRow(rowNum);
        for (int i = 0; i < values.length; i++) {
            row.createCell(i).setCellValue(values[i]);
        }
    }

    private static byte[] png(int rgb) throws Exception {
        BufferedImage img = new BufferedImage(60, 40, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = img.createGraphics();
        g.setColor(new Color(rgb));
        g.fillRect(0, 0, 60, 40);
        g.dispose();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(img, "png", out);
        return out.toByteArray();
    }

    private static void assertPng(byte[] data) throws Exception {
        BufferedImage img = ImageIO.read(new java.io.ByteArrayInputStream(data));
        assertTrue(img != null && img.getWidth() > 0, "提取出来的必须是能解码的 PNG");
    }

    private static int firstPixel(byte[] data) throws Exception {
        BufferedImage img = ImageIO.read(new java.io.ByteArrayInputStream(data));
        assertFalse(img == null, "图片必须能解码");
        return img.getRGB(img.getWidth() / 2, img.getHeight() / 2) & 0xFFFFFF;
    }

    private static boolean isReddish(int rgb) {
        return (rgb >> 16 & 0xFF) > 200 && (rgb & 0xFF) < 60 && (rgb >> 8 & 0xFF) < 60;
    }

    private static boolean isGreenish(int rgb) {
        return (rgb >> 8 & 0xFF) > 200 && (rgb >> 16 & 0xFF) < 60 && (rgb & 0xFF) < 60;
    }
}
