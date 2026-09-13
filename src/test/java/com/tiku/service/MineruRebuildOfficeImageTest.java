package com.tiku.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * MinerU 结果重建中的「Office 文档图片」回归（2026-09-13 修）。
 *
 * 背景（真实缺陷）：MinerU 对 **PDF** 的 content_list 条目带 `bbox`（版面坐标），
 * 但对 **PPT/Excel** 只返回 `type` + `content`，**没有 bbox**。
 * 而图链结算代码在 `bbox == null` 时写的是 `continue`（注释本意是"单张输出，不入链合并"），
 * 于是 PPT/Excel 里的图片被整批丢弃——实测一份 8 页课件：MinerU zip 里有 6 张图、
 * content_list_v2 里也有 6 个 image 条目，但重建结果 `extractedImages=0`，预览页素材区全空。
 *
 * 本测试锁两条规则：
 *  1. 无 bbox 的图必须按出现顺序逐张输出（不参与按 bbox 的合并，但绝不能丢）；
 *  2. "无文本页的图片丢弃"只对 PDF 生效——PPT 的一页可能整页就是一道图题。
 */
class MineruRebuildOfficeImageTest {

    private final MineruParseService service = new MineruParseService(new ObjectMapper(), new DocumentParserService());

    @Test
    void keepsImagesWithoutBbox() throws Exception {
        // 一页：文本 + 一张无 bbox 的图（模拟 pptx 的一页）
        String contentList = """
                [
                  [
                    {"type":"paragraph","content":{"text":"1. 看图回答：图中三角形的面积是多少？"}},
                    {"type":"image","content":{"image_source":{"path":"images/a.png"},"image_caption":[]}}
                  ]
                ]
                """;
        byte[] zip = buildZip(contentList, "images/a.png");

        DocumentParserService.ParseResult r = service.rebuild(zip, "课件.pptx", "");

        assertEquals(1, r.extractedImages().size(), "无 bbox 的图不能被丢弃");
        assertTrue(r.text().contains("[图片1]"), "文本流里要留下图片锚点：" + r.text());
        assertEquals(1, r.pageTexts().size());
    }

    @Test
    void keepsImageOnTextlessPageForOfficeDocuments() throws Exception {
        // 第一页有题目（用于确定 firstQuestionPage），第二页整页只有一张图（纯图片题）
        String contentList = """
                [
                  [ {"type":"paragraph","content":{"text":"1. 第一题：太阳从哪边升起？（ ）"}} ],
                  [ {"type":"image","content":{"image_source":{"path":"images/b.png"},"image_caption":[]}} ]
                ]
                """;
        byte[] zip = buildZip(contentList, "images/b.png");

        DocumentParserService.ParseResult office = service.rebuild(zip, "课件.pptx", "");
        assertEquals(1, office.extractedImages().size(), "PPT 的纯图页就是一道题，图不能丢");

        // 同样内容按 PDF 解释时：无文本页的图仍按"封面/广告"丢弃（保持既有行为）
        DocumentParserService.ParseResult pdf = service.rebuild(zip, "试卷.pdf", "");
        assertEquals(0, pdf.extractedImages().size(), "PDF 无文本页图片仍按装饰图丢弃");
    }

    @Test
    void rebuildsRealMineruOfficeOutput() throws Exception {
        // 真实 MinerU 输出（vlm，pptx 含 6 张插图）：只保留 rebuild 需要的最小集合
        Path fixture = Path.of("src/test/resources/mineru-office/mineru-pptx-office-image.zip");
        org.junit.jupiter.api.Assumptions.assumeTrue(Files.exists(fixture), "跳过：缺少夹具 " + fixture);

        DocumentParserService.ParseResult r = service.rebuild(Files.readAllBytes(fixture), "含图片题型的课件.pptx", "");

        assertEquals(6, r.extractedImages().size(),
                "MinerU 对 pptx 返回的 6 张图应全部重建（历史缺陷：全丢）");
        assertEquals(8, r.pageTexts().size(), "8 页幻灯片");
        assertTrue(r.text().contains("[图片1]") && r.text().contains("[图片6]"), "锚点应逐页插入");
    }

    @Test
    void extractsXlsxTableImagesAtCellPosition() throws Exception {
        // xlsx 的真实形态：MinerU 不产出 image 条目，只在 table 的单元格 HTML 里写 <img src="images/…">
        // （实测 2 个工作表 6 张图全靠这个引用）→ 必须按单元格位置提出来，且标记要落在所在行
        String contentList = """
                [
                  [
                    {"type":"title","content":{"text":"题库"}},
                    {"type":"table","content":{"html":"<table><tr><td>1. 看图选答案</td><td><img src=\\"images/red.png\\"/></td></tr><tr><td>2. 再看一题</td><td><img src=\\"images/green.png\\"/></td></tr></table>"}}
                  ]
                ]
                """;
        byte[] zip = buildZip(contentList, new String[]{"images/red.png", "images/green.png"},
                new byte[][]{png(0xFF0000), png(0x00FF00)});

        DocumentParserService.ParseResult r = service.rebuild(zip, "含图片的题库表格.xlsx", "");

        assertEquals(2, r.extractedImages().size(), "表格里引用的 2 张图都要提取（历史缺陷：0 张）");
        String text = r.text();
        int q1 = text.indexOf("1. 看图选答案");
        int m1 = text.indexOf("[图片1]");
        int q2 = text.indexOf("2. 再看一题");
        int m2 = text.indexOf("[图片2]");
        assertTrue(q1 >= 0 && m1 > q1, "第 1 张图的标记要落在它所在行：\n" + text);
        assertTrue(q2 > m1 && m2 > q2, "第 2 张图的标记要落在它所在行：\n" + text);
        assertTrue(text.indexOf("images/") < 0, "图片引用的路径不能漏进正文：\n" + text);
        // 路径→图片不能错位：第 1 张是红图、第 2 张是绿图（jpg 有损，判色相不判精确值）
        int p0 = firstPixel(r.extractedImages().get(0).image().data());
        int p1 = firstPixel(r.extractedImages().get(1).image().data());
        assertTrue((p0 >> 16 & 0xFF) > 200 && (p0 & 0xFF) < 80, "第 1 位应是红图，实际 #" + Integer.toHexString(p0));
        assertTrue((p1 >> 8 & 0xFF) > 200 && (p1 >> 16 & 0xFF) < 80, "第 2 位应是绿图，实际 #" + Integer.toHexString(p1));
    }

    @Test
    void resolvesImagePathWrappedByNewline() throws Exception {
        // MinerU 会把超长 hash 折行写进 HTML：JSON 里是 \n 转义 → 解析后路径中间真的带一个换行
        // （实测 xlsx 的 10 个引用里有 3 个中招：查不到文件 → 图丢）。折行的路径必须照样命中。
        String contentList = """
                [
                  [
                    {"type":"table","content":{"html":"<table><tr><td>1. 看图</td><td><img src=\\"images/red\\n.png\\"/></td></tr></table>"}}
                  ]
                ]
                """;
        byte[] zip = buildZip(contentList, new String[]{"images/red.png"}, new byte[][]{png(0xFF0000)});

        DocumentParserService.ParseResult r = service.rebuild(zip, "题库表格.xlsx", "");

        assertEquals(1, r.extractedImages().size(), "路径里的换行不能导致图片查不到");
        assertTrue(r.text().contains("[图片1]"), "正文里要有锚点：\n" + r.text());
    }

    @Test
    void rebuildsRealMineruXlsxOutput() throws Exception {
        // 真实 MinerU 输出（vlm，含图片的题库表格.xlsx）：图片只在 table HTML 里被引用
        Path fixture = Path.of("src/test/resources/mineru-office/mineru-xlsx-office-image.zip");
        org.junit.jupiter.api.Assumptions.assumeTrue(Files.exists(fixture), "跳过：缺少夹具 " + fixture);

        DocumentParserService.ParseResult r = service.rebuild(Files.readAllBytes(fixture), "含图片的题库表格.xlsx", "");

        // 两个工作表共 10 处 <img> 引用（6 个不同 hash，其中 3 处路径被折行）→ 全部要提取
        assertEquals(10, r.extractedImages().size(),
                "表格内嵌图应全部重建，实际 " + r.extractedImages().size() + " 张");
        assertTrue(r.text().contains("[图片1]") && r.text().contains("[图片10]"), "正文里要有锚点：\n" + r.text());
        assertTrue(r.text().indexOf("images/") < 0, "图片路径不能漏进正文：\n" + r.text());
    }

    // ==================== 工具 ====================

    /** 造一个最小 MinerU 结果 zip：一份 content_list_v2.json + 若干图片 */
    private static byte[] buildZip(String contentListJson, String... imagePaths) throws Exception {
        String[] paths = imagePaths;
        byte[][] datas = new byte[paths.length][];
        for (int i = 0; i < paths.length; i++) {
            datas[i] = png(0x0000FF);
        }
        return buildZip(contentListJson, paths, datas);
    }

    private static byte[] buildZip(String contentListJson, String[] imagePaths, byte[][] imageDatas) throws Exception {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        try (ZipOutputStream zos = new ZipOutputStream(bos)) {
            zos.putNextEntry(new ZipEntry("task-1_content_list_v2.json"));
            zos.write(contentListJson.getBytes(StandardCharsets.UTF_8));
            zos.closeEntry();
            for (int i = 0; i < imagePaths.length; i++) {
                zos.putNextEntry(new ZipEntry(imagePaths[i]));
                zos.write(imageDatas[i]);
                zos.closeEntry();
            }
        }
        return bos.toByteArray();
    }

    private static byte[] png() throws Exception {
        return png(0x0000FF);
    }

    /** 纯色 jpg（MinerU 的图是 jpg，正好顺带验证"jpg → PNG"的转换契约） */
    private static byte[] png(int rgb) throws Exception {
        BufferedImage img = new BufferedImage(40, 30, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = img.createGraphics();
        g.setColor(new Color(rgb));
        g.fillRect(0, 0, 40, 30);
        g.dispose();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(img, "jpg", out);
        return out.toByteArray();
    }

    /** 解出图片左上角像素的 RGB（用于验证"路径 → 图片"的对应顺序） */
    private static int firstPixel(byte[] data) throws Exception {
        BufferedImage img = ImageIO.read(new java.io.ByteArrayInputStream(data));
        return img.getRGB(0, 0) & 0xFFFFFF;
    }
}
