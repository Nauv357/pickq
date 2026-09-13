package com.tiku.service;

import org.apache.poi.hssf.usermodel.HSSFWorkbook;
import org.apache.poi.hslf.usermodel.HSLFSlideShow;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xslf.usermodel.XMLSlideShow;
import org.apache.poi.xslf.usermodel.XSLFSlide;
import org.apache.poi.xslf.usermodel.XSLFTextBox;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;

import java.awt.Rectangle;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 新增文档格式的解析回归（2026-09-13）：Excel（.xlsx/.xls）、CSV、PPT（.pptx/.ppt）、老 Word（.doc）。
 *
 * 设计口径（与 PDF/docx 一致）：**只抽原文文本交给模型，不在本地做"表头识别/结构化拼装"**——
 * 早先 PDF 先本地解析再交给模型，反而容易丢内容；这里刻意保持"抽取层只做抽取"。
 * 因此断言只看"文本有没有抽到、结构边界（工作表/页码）在不在"，不锁模型侧的组织方式。
 *
 * 全部文档在测试里就地生成（不依赖仓库外的样例文件），干净克隆上也会真正执行。
 */
class DocumentParserFormatTest {

    private final DocumentParserService service = new DocumentParserService();

    // ==================== Excel ====================

    @Test
    void parsesXlsxRowsAsText() throws Exception {
        byte[] bytes;
        try (XSSFWorkbook wb = new XSSFWorkbook(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            Sheet sheet = wb.createSheet("题库");
            Row head = sheet.createRow(0);
            head.createCell(0).setCellValue("题干");
            head.createCell(1).setCellValue("选项A");
            head.createCell(2).setCellValue("答案");
            Row q1 = sheet.createRow(1);
            q1.createCell(0).setCellValue("下列哪个是质数？");
            q1.createCell(1).setCellValue("9");
            q1.createCell(2).setCellValue("B");
            sheet.createRow(2); // 空行：应被跳过
            Row q2 = sheet.createRow(3);
            q2.createCell(0).setCellValue("1+1=?");
            q2.createCell(1).setCellValue("2");
            q2.createCell(2).setCellValue("1");
            wb.write(out);
            bytes = out.toByteArray();
        }

        DocumentParserService.ParseResult r = service.parse("题库.xlsx", bytes);

        assertEquals("XLSX", r.fileType());
        assertTrue(r.text().contains("题干"), r.text());
        assertTrue(r.text().contains("下列哪个是质数？"), r.text());
        assertTrue(r.text().contains("下列哪个是质数？ | 9 | B"), r.text());
        assertTrue(r.text().contains("1+1=? | 2 | 1"), r.text());
        assertTrue(r.text().contains(" | "), "单元格之间要有分隔符，否则列会粘成一坨");
        assertFalse(r.text().contains("\n\n\n"), "空行应被跳过，不留下连续空行");
        assertTrue(r.images().isEmpty());
    }

    @Test
    void parsesMultipleSheetsWithSheetName() throws Exception {
        byte[] bytes;
        try (XSSFWorkbook wb = new XSSFWorkbook(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            Sheet s1 = wb.createSheet("第一章");
            s1.createRow(0).createCell(0).setCellValue("第一题");
            Sheet s2 = wb.createSheet("第二章");
            s2.createRow(0).createCell(0).setCellValue("第二题");
            wb.write(out);
            bytes = out.toByteArray();
        }

        String text = service.parse("多表.xlsx", bytes).text();
        assertTrue(text.contains("## 工作表：第一章"), text);
        assertTrue(text.contains("## 工作表：第二章"), text);
        assertTrue(text.indexOf("第一题") < text.indexOf("第二题"), "工作表顺序应保持");
    }

    @Test
    void trimsNumbersAndDatesInCells() throws Exception {
        byte[] bytes;
        try (XSSFWorkbook wb = new XSSFWorkbook(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            Sheet sheet = wb.createSheet("s");
            Row row = sheet.createRow(0);
            row.createCell(0).setCellValue(1.0); // Excel 里整数常存成 1.0
            row.createCell(1).setCellValue(2.5);
            wb.write(out);
            bytes = out.toByteArray();
        }

        String text = service.parse("数字.xlsx", bytes).text();
        assertTrue(text.contains("1 | 2.5"), text);
    }

    @Test
    void parsesLegacyXls() throws Exception {
        byte[] bytes;
        try (HSSFWorkbook wb = new HSSFWorkbook(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            Sheet sheet = wb.createSheet("旧表");
            sheet.createRow(0).createCell(0).setCellValue("老格式表格里的题干");
            wb.write(out);
            bytes = out.toByteArray();
        }

        DocumentParserService.ParseResult r = service.parse("旧表.xls", bytes);
        assertEquals("XLS", r.fileType());
        assertTrue(r.text().contains("老格式表格里的题干"), r.text());
    }

    // ==================== CSV ====================

    @Test
    void parsesCsvAsPlainText() throws Exception {
        byte[] bytes = "题干,选项A,答案\n下列哪个是质数？,9,B\n".getBytes(StandardCharsets.UTF_8);
        DocumentParserService.ParseResult r = service.parse("题库.csv", bytes);
        assertEquals("CSV", r.fileType());
        assertTrue(r.text().contains("下列哪个是质数？"), r.text());
    }

    @Test
    void parsesGbkCsv() throws Exception {
        byte[] bytes = "题干,答案\n下列哪个是质数？,B\n".getBytes(java.nio.charset.Charset.forName("GBK"));
        String text = service.parse("老编码.csv", bytes).text();
        assertTrue(text.contains("下列哪个是质数？"), text);
    }

    // ==================== PPT ====================

    @Test
    void parsesPptxSlideTextWithPageMarker() throws Exception {
        byte[] bytes;
        try (XMLSlideShow show = new XMLSlideShow(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            XSLFSlide s1 = show.createSlide();
            XSLFTextBox box = s1.createTextBox();
            box.setAnchor(new Rectangle(20, 20, 400, 200));
            box.setText("随堂练习 1. 下列说法正确的是（ ）");
            XSLFSlide s2 = show.createSlide();
            XSLFTextBox box2 = s2.createTextBox();
            box2.setAnchor(new Rectangle(20, 20, 400, 200));
            box2.setText("2. 计算 1+1=（ ）");
            show.write(out);
            bytes = out.toByteArray();
        }

        DocumentParserService.ParseResult r = service.parse("课件.pptx", bytes);
        assertEquals("PPTX", r.fileType());
        assertTrue(r.text().contains("## 第 1 页"), r.text());
        assertTrue(r.text().contains("## 第 2 页"), r.text());
        assertTrue(r.text().contains("随堂练习 1. 下列说法正确的是（ ）"), r.text());
        assertTrue(r.text().contains("2. 计算 1+1=（ ）"), r.text());
    }

    @Test
    void parsesLegacyPpt() throws Exception {
        byte[] bytes;
        try (HSLFSlideShow show = new HSLFSlideShow(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            org.apache.poi.hslf.usermodel.HSLFSlide slide = show.createSlide();
            org.apache.poi.hslf.usermodel.HSLFTextBox box = slide.createTextBox();
            box.setAnchor(new Rectangle(20, 20, 400, 200));
            box.setText("老格式课件里的一道题");
            show.write(out);
            bytes = out.toByteArray();
        }

        DocumentParserService.ParseResult r = service.parse("老课件.ppt", bytes);
        assertEquals("PPT", r.fileType());
        assertTrue(r.text().contains("老格式课件里的一道题"), r.text());
    }

    // ==================== 老 Word（.doc） ====================

    /**
     * .doc 是 OLE 复合文档，POI **不能凭空造出一个**（`new HWPFDocument(new POIFSFileSystem())`
     * 会报 `no such entry: "WordDocument"`），所以这里沿用仓库既有约定：
     * 有本地样例就跑、没有就跳过（与 DocumentParserServiceTest 对 sample-ai-files 的处理一致）。
     * 放入任意一份真实的 Word 97-2003 文件即可让这条断言真正执行：
     *   src/test/resources/legacy-doc/sample.doc
     */
    @Test
    void parsesLegacyDocText() throws Exception {
        java.nio.file.Path fixture = java.nio.file.Path.of("src/test/resources/legacy-doc/sample.doc");
        org.junit.jupiter.api.Assumptions.assumeTrue(java.nio.file.Files.exists(fixture),
                "跳过：缺少本地 .doc 样例 " + fixture.toAbsolutePath());
        byte[] bytes = java.nio.file.Files.readAllBytes(fixture);

        DocumentParserService.ParseResult r = service.parse("老试卷.doc", bytes);

        assertEquals("DOC", r.fileType());
        assertFalse(r.text().isBlank(), "老 Word 文档应能抽出文本");
    }

    @Test
    void legacyDocWithWrongContentGivesActionableMessage() {
        // 把非 Word 内容改名成 .doc（常见于"另存为 .doc"其实是 RTF/HTML）
        byte[] notAWordFile = "{\\rtf1\\ansi not really a doc}".getBytes(StandardCharsets.UTF_8);
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> service.parse("伪装.doc", notAWordFile));
        assertTrue(e.getMessage().contains("另存为 .docx"), e.getMessage());
    }

    // ==================== 不支持格式的提示 ====================

    @Test
    void unsupportedFormatMessageListsNewFormats() {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> service.parse("笔记.pages", "x".getBytes(StandardCharsets.UTF_8)));
        String msg = e.getMessage();
        assertTrue(msg.contains("xlsx") && msg.contains("pptx") && msg.contains("doc"), msg);
    }
}
