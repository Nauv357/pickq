package com.tiku.service;

import org.junit.jupiter.api.Test;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * docx 解析层验收（学科卷泛化）：
 * 1) 物理卷（MathType OLE 公式 84 WMF + 18 PNG）全部图片可解码为 PNG；
 * 2) 图片锚点插入文本流（公式缺失处出现 [图片N] 标记）；
 * 3) 选项同行（tab 分隔）保留空格分隔、表格/段落顺序不丢失。
 */
class DocumentParserServiceTest {

    private static final String PHYSICS_DOCX = "sample-ai-files/2024安徽高考真题物理（教师版）.docx";

    private DocumentParserService.ParseResult parse(String path) throws IOException {
        DocumentParserService service = new DocumentParserService();
        return service.parse(Path.of(path).getFileName().toString(), Files.readAllBytes(Path.of(path)));
    }

    @Test
    void physicsDocxExtractsAllImagesAsDecodablePng() throws IOException {
        Path f = Path.of(PHYSICS_DOCX);
        assertTrue(Files.exists(f), "缺少样例文件：" + f.toAbsolutePath());
        DocumentParserService.ParseResult r = parse(PHYSICS_DOCX);

        String text = r.text();
        assertNotNull(text);
        //题干文字完整（公式缺位处的上下文保留）
        assertTrue(text.contains("大连相干光源"), "题干文字缺失");
        assertTrue(text.contains("参考答案"), "卷末答案区缺失");

        //每张提取的图片都必须能被解码（旧实现把 WMF 原始字节当 PNG 发给模型 → 此处会失败）
        //物理卷：84 张 WMF 公式图（POI 矢量渲染）+ 18 张 PNG 插图，少量渲染失败可跳过
        int total = r.extractedImages().size();
        assertTrue(total >= 90 && total <= 102, "提取图片数量异常：" + total + "（期望 90-102：84 WMF + 18 PNG）");
        for (DocumentParserService.ExtractedImage e : r.extractedImages()) {
            BufferedImage img = ImageIO.read(new ByteArrayInputStream(e.image().data()));
            assertNotNull(img, "图片不可解码（WMF 未渲染为 PNG？）");
            assertTrue(img.getWidth() > 0 && img.getHeight() > 0);
        }
        //个别渲染失败的图片应明确警告而不是坏图
        if (r.warning() != null) {
            assertTrue(r.warning().contains("跳过"), "warning 应说明跳过：" + r.warning());
        }

        //文本流中的 [图片N] 标记数量 == 提取的图片数量（不丢不重）
        Matcher m = Pattern.compile("\\[图片(\\d+)]").matcher(text);
        int marks = 0;
        int maxN = 0;
        while (m.find()) {
            marks++;
            maxN = Math.max(maxN, Integer.parseInt(m.group(1)));
        }
        assertEquals(total, marks, "文本标记数与提取图片数不一致");
        assertEquals(total, maxN, "图片编号不连续（有缺失）");
    }

    @Test
    void physicsDocxAnchorsFormulaImagesInline() throws IOException {
        DocumentParserService.ParseResult r = parse(PHYSICS_DOCX);
        String text = r.text();
        //公式缺位：原文"已知紫外光的光子能量大于（公式）"→ 文本中公式处应有 [图片N]（OLE 预览图锚定）
        assertTrue(text.contains("光子能量大于[图片"), "公式图未锚定在缺失位置：\n" + snippet(text, "光子能量大于"));
        //选项同行（tab 分隔）："A. 1种 B. 2种"（tab→空格，保留拆选项线索）
        assertTrue(text.contains("A. 1种 B. 2种"), "选项同行未按 tab 转空格：\n" + snippet(text, "A. 1种"));
    }

    @Test
    void judgePdfTrailingQuestionNumberSplit() throws IOException {
        Path f = Path.of("sample-ai-files", "专项智能练习（判断推理）(1).pdf");
        assertTrue(Files.exists(f), "缺少样例文件：" + f.toAbsolutePath());
        DocumentParserService service = new DocumentParserService();
        DocumentParserService.ParseResult r = service.parse("judge.pdf", Files.readAllBytes(f));
        String text = r.text();
        //行尾题号归位："下列选项中，属于上图的俯视图的是：2." → 设问行 + 孤立题号行"2."
        assertTrue(text.contains("下列选项中，属于上图的俯视图的是："), "设问行应保留");
        assertFalse(text.contains("俯视图的是：2."), "行尾题号未拆分");
        assertTrue(text.contains("\n2.\n"), "题号 2 应独立成行（当前文本头部：\n" + snippet(text, "俯视图") + "）");
        //题号归位：定义句应从题号行之后开始（"1.\n正向情绪价值…"），消除"定义前置"版式
        assertTrue(text.contains("1.\n正向情绪价值"), "定义句应移到题号行之后：\n" + snippet(text, "1."));
        assertTrue(text.contains("正向情绪价值：指"), "定义句内容应保留");
    }

    @Test
    void physicsDocxAnswerSectionRecognizable() {
        String[] answerLines = {"【1题答案】B", "【9题答案】BD", "【13题答案】（1）；（2）", "1. B", "3:ABD", "5√"};
        for (String line : answerLines) {
            assertTrue(AiAnswerFormat.ANSWER_LINE.matcher(line.trim()).matches(), "答案行未识别：" + line);
        }
        assertTrue(AiAnswerFormat.looksLikeAnswerList(
                "参考答案\n【1题答案】B\n【2题答案】D\n【3题答案】C\n【4题答案】C\n"), "卷末答案列表识别失败");
        assertFalse(AiAnswerFormat.looksLikeAnswerList("一、选择题：本题共8小题\n二、非选择题：共5题"), "非答案文本误判为答案列表");
    }

    private String snippet(String text, String around) {
        int i = text.indexOf(around);
        if (i < 0) {
            return "（未找到 " + around + "）";
        }
        int from = Math.max(0, i - 20);
        return text.substring(from, Math.min(text.length(), i + 60));
    }
}
