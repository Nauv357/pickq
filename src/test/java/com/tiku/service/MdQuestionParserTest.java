package com.tiku.service;

import com.tiku.model.ContentPackageQuestion;
import com.tiku.model.OptionItem;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * MdQuestionParser 验收：标准模板 + 模板漂移宽容（无**标记、同行选项、无标题兜底、实验题标号不误判）。
 */
class MdQuestionParserTest {

    @Test
    void parsesStandardTemplate() {
        String md = """
                ## 第1题 · 单选
                **题干**：
                大连相干光源是……辐射不同频率的紫外光有（　　）[图片3]

                **选项**：
                - A. 1种
                - B. 2种
                - C. 3种
                - D. 4种

                **答案**：留空

                ## 第9题 · 多选
                **题干**：
                一倾角为足够大的光滑斜面固定于水平地面上……

                **选项**：
                - A. 物块始终做匀变速曲线运动
                - B. 时，物块的y坐标值为2.5m
                - C. 时，物块的加速度大小为
                - D. 时，物块的速度大小为
                """;
        List<ContentPackageQuestion> qs = MdQuestionParser.parse(md);
        assertEquals(2, qs.size());
        ContentPackageQuestion q1 = qs.get(0);
        assertEquals(1, q1.getQuestionNumber());
        assertEquals("SINGLE", q1.getType());
        assertTrue(q1.getContent().contains("大连相干光源"));
        assertTrue(q1.getContent().contains("[图片3]"), "题干图片标记应保留");
        assertEquals(4, q1.getOptions().size());
        assertEquals("1种", q1.getOptions().get(0).text());
        assertEquals("MULTIPLE", qs.get(1).getType());
        assertEquals(9, qs.get(1).getQuestionNumber());
    }

    @Test
    void tolerantToMissingBoldAndInlineOptions() {
        String md = """
                ## 第1题 单选
                题干：
                某同学参加户外拓展活动……克服摩擦力做的功为（ ）

                选项：
                - A. [图片1]
                - B. [图片2]
                - C. [图片3]
                - D. [图片4]
                """;
        List<ContentPackageQuestion> qs = MdQuestionParser.parse(md);
        assertEquals(1, qs.size());
        ContentPackageQuestion q = qs.get(0);
        assertEquals("SINGLE", q.getType());
        assertEquals(4, q.getOptions().size());
        assertEquals("[图片1]", q.getOptions().get(0).text());

        //同行多选项拆分
        String inline = """
                ## 第2题 · 单选
                **题干**：
                辐射不同频率的紫外光有（ ）
                **选项**：
                A. 1种 B. 2种 C. 3种 D. 4种
                """;
        List<ContentPackageQuestion> qs2 = MdQuestionParser.parse(inline);
        assertEquals(1, qs2.size());
        assertEquals(4, qs2.get(0).getOptions().size());
        assertEquals("1种", qs2.get(0).getOptions().get(0).text());
        assertEquals("4种", qs2.get(0).getOptions().get(3).text());
    }

    @Test
    void experimentalQuestionNotMistakenAsChoice() {
        String md = """
                ## 第11题 · 主观
                **题干**：
                某实验小组做"测量玻璃的折射率"及拓展探究实验．
                （1）为测量玻璃的折射率，按如图所示进行实验，以下表述正确的一项是________。（填正确答案标号）
                A. 用笔在白纸上沿着玻璃砖上边和下边分别画出直线
                B. 在玻璃砖一侧插上大头针
                C. 实验时入射角应尽量小一些
                （2）……频率大，折射率________（填"大"或"小"）
                （3）……折射率大小关系为________

                **参考答案**：（1）B （2）大 （3）n玻>n介
                """;
        List<ContentPackageQuestion> qs = MdQuestionParser.parse(md);
        assertEquals(1, qs.size());
        ContentPackageQuestion q = qs.get(0);
        assertEquals("SUBJECTIVE", q.getType());
        assertEquals(0, q.getOptions().size(), "填空标号不应成为选项（无'选项：'标记）");
        assertTrue(q.getContent().contains("（1）"), "子问应合并保留在题干中");
        assertTrue(q.getContent().contains("A. 用笔在白纸上"), "填空标号行属于题干内容");
        assertEquals("（1）B （2）大 （3）n玻>n介", q.getReferenceAnswer());
    }

    @Test
    void infersTypeWhenMissingAndFallsBackToSingleSubjective() {
        String md = """
                ## 第3题
                **题干**：
                某仪器发射甲、乙两列横波……则这两列横波（　　）
                **选项**：
                - A. 在处开始相遇
                - B. 在处开始相遇
                - C. 波峰在处相遇
                - D. 波峰在处相遇
                """;
        List<ContentPackageQuestion> qs = MdQuestionParser.parse(md);
        assertEquals(1, qs.size());
        assertEquals("SINGLE", qs.get(0).getType(), "无题型但有选项 → 默认单选");

        //无标题整篇兜底 → 单题主观
        List<ContentPackageQuestion> qs2 = MdQuestionParser.parse("这是一段没有题号标题的杂文内容。");
        assertEquals(1, qs2.size());
        assertEquals("SUBJECTIVE", qs2.get(0).getType());
        assertEquals(0, MdQuestionParser.parse("").size());
        assertEquals(0, MdQuestionParser.parse(null).size());
    }

    @Test
    void parsesModelDeclaredMaterials() {
        String md = """
                ## 材料 m1
                2019年全国居民人均可支配收入30733元，比上年增长8.9%。

                ## 第1题 · 单选
                **题干**：
                2019年，全国居民人均可支配收入比上年增长：

                **选项**：
                - A. 8.0%
                - B. 8.9%

                ## 材料 二
                某地区2010-2020年粮食产量如下表。

                ## 第2题 · 单选
                **题干**：
                2020年该地区粮食产量比2010年增长：

                **选项**：
                - A. 20%
                - B. 30%
                """;
        MdQuestionParser.ParseOutcome out = MdQuestionParser.parseWithMaterials(md);
        assertEquals(2, out.questions().size());
        assertEquals(2, out.materials().size());
        assertEquals("m1", out.materials().get(0).getMaterialKey());
        assertEquals("m2", out.materials().get(1).getMaterialKey());
        assertTrue(out.materials().get(0).getContent().contains("30733"));
        //材料按位置自动关联其后的题目
        assertEquals("m1", out.questions().get(0).getMaterialKey());
        assertEquals("m2", out.questions().get(1).getMaterialKey());
        //无材料块的文档 → 空材料列表（判断推理等无材料卷不再产生假材料）
        assertEquals(0, MdQuestionParser.parseWithMaterials(
                "## 第1题 · 单选\n**题干**：xxx\n**选项**：\n- A. a\n- B. b").materials().size());
    }

    @Test
    void extractsAnswerAnalysisAndQuestionNumber() {
        String md = """
                ## 第5题 · 单选
                **题干**：
                2024年3月20日，我国探月工程四期鹊桥二号中继星成功发射升空。

                **选项**：
                - A. 周期约为144h
                - B. 近月点的速度大于远月点的速度

                **答案**：B

                **解析**：
                根据开普勒第三定律……
                """;
        List<ContentPackageQuestion> qs = MdQuestionParser.parse(md);
        assertEquals(1, qs.size());
        ContentPackageQuestion q = qs.get(0);
        assertEquals(5, q.getQuestionNumber());
        assertEquals(List.of("B"), q.getAnswerKeys());
        assertTrue(q.getAnalysis().contains("开普勒"));
        assertEquals(2, q.getOptions().size());
    }
}
