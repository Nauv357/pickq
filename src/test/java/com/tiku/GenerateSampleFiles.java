package com.tiku;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.font.PDFont;
import org.apache.pdfbox.pdmodel.font.PDType0Font;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.XWPFParagraph;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * 生成 AI 导入实验用样例文件（txt / docx / pdf）。
 * 运行：mvn -Dtest=GenerateSampleFiles test
 * 输出：项目根 sample-ai-files/
 * 内容为原创模拟文档（网盘题库 / 笔记 / 试卷 / 复习手册 / 打印版 PDF），用于测试 AI 辅助导入。
 */
public class GenerateSampleFiles {

    private static final Path OUT = Path.of("sample-ai-files");

    @Test
    public void generateAll() throws Exception {
        Files.createDirectories(OUT);
        writeTxtBank();
        writeTxtNotes();
        writeDocxExam();
        writeDocxReview();
        writePdfExam();
        writePdfBank();
        System.out.println("样例文件已生成到 " + OUT.toAbsolutePath());
    }

    // ==================== TXT ====================

    /** 网盘下载风格：题号 + 选项 + 答案 + 解析 混排 */
    private void writeTxtBank() throws IOException {
        String content = """
                计算机基础题库（整理版）
                共 15 题，答案在每题下方

                1. 在计算机中，1KB 等于多少字节？
                A. 1000    B. 1024    C. 512    D. 2048
                答案：B
                解析：1KB = 1024B

                2. 下列属于输出设备的是（ ）。
                A. 键盘   B. 鼠标   C. 显示器   D. 扫描仪
                答案：C

                3. CPU 主要由哪两部分组成？
                A. 运算器和控制器   B. 存储器和输入设备   C. 控制器和存储器   D. 运算器和输出设备
                答案：A

                4. 下列哪些软件属于操作系统？
                A. Windows  B. Linux  C. Office  D. macOS
                答案：ABD

                5. 世界上第一台电子计算机诞生于哪一年？
                A. 1936  B. 1946  C. 1958  D. 1971
                答案：B

                6. 在计算机内部，数据是以什么形式存储和处理的？
                A. 十进制  B. 八进制  C. 二进制  D. 十六进制
                答案：C

                7. 下列关于内存的说法正确的是？
                A. 断电后数据丢失  B. 断电后数据保留  C. 速度比硬盘慢  D. 容量比硬盘大
                答案：A

                8. 下列哪些属于输入设备？
                A. 键盘  B. 鼠标  C. 打印机  D. 麦克风
                答案：ABD

                9. 1 字节（Byte）等于多少位（bit）？
                A. 4  B. 8  C. 16  D. 32
                答案：B

                10. 网页浏览器缓存的数据主要存储在（ ）。
                A. CPU  B. 硬盘  C. 显示器  D. 网卡
                答案：B

                11. 判断题：TCP 协议是面向连接的传输协议。
                答案：正确

                12. 判断题：计算机病毒只能通过网络传播。
                答案：错误
                解析：还可以通过移动存储设备等途径传播

                13. 判断题：RAM 中的数据在断电后会丢失。
                答案：正确

                14. 下列哪些是编程语言？
                A. Python  B. HTML  C. Java  D. CSS
                答案：AC
                解析：HTML、CSS 是标记/样式语言，不是编程语言

                15. 防火墙的主要功能是？
                A. 防止计算机病毒  B. 保护网络安全  C. 提高网速  D. 存储数据
                答案：B
                """;
        Files.writeString(OUT.resolve("bank-computer-basics.txt"), content, StandardCharsets.UTF_8);
    }

    /** 手打笔记风格：更随意，混合简答与客观题 */
    private void writeTxtNotes() throws IOException {
        String content = """
                【中国古代史·自己整理的练习题】
                来源：课本+网课笔记，可能有错，欢迎纠正

                1. 中国历史上第一个统一的中央集权王朝是？
                A. 夏  B. 商  C. 秦  D. 汉
                答：C

                2. 甲骨文最早出现在哪个朝代？
                A. 夏  B. 商  C. 西周  D. 春秋
                答：B

                3. 丝绸之路的开辟与哪位人物有关？
                A. 张骞  B. 班超  C. 苏武  D. 郑和
                答：A

                4. 唐朝的开国皇帝是？
                A. 李世民  B. 李渊  C. 李隆基  D. 李治
                答：B

                5. 下列哪些属于我国古代"四大发明"？
                A. 造纸术  B. 印刷术  C. 指南针  D. 地动仪
                答：ABC

                6. 北宋时期出现的世界上最早的纸币是？
                A. 交子  B. 会子  C. 银票  D. 铜钱
                答：A

                7. 郑和下西洋发生在哪个朝代？
                A. 宋  B. 元  C. 明  D. 清
                答：C

                8. 判断：科举制创立于隋朝。
                答：对

                9. 判断：长城是秦始皇时期开始大规模修筑的。
                答：对

                10. 判断：《本草纲目》的作者是李时珍。
                答：对

                11. 思考题：为什么说秦朝是"百代皆行秦政法"？（简答，可不做）
                """;
        Files.writeString(OUT.resolve("notes-chinese-history.txt"), content, StandardCharsets.UTF_8);
    }

    // ==================== DOCX ====================

    /** 正式试卷样式：分题型 + 卷末参考答案 */
    private void writeDocxExam() throws IOException {
        try (XWPFDocument doc = new XWPFDocument()) {
            title(doc, "《计算机应用基础》期末试卷（模拟）");
            sub(doc, "满分 100 分  考试时间 60 分钟");

            section(doc, "一、单项选择题（每题 2 分）");
            question(doc, "1. 在 Word 中，保存文档的快捷键是？", "A. Ctrl+S", "B. Ctrl+C", "C. Ctrl+V", "D. Ctrl+Z");
            question(doc, "2. 下列哪个不是操作系统？", "A. Windows", "B. Android", "C. Photoshop", "D. Linux");
            question(doc, "3. Excel 中，对一组数值求和的函数是？", "A. AVERAGE", "B. SUM", "C. COUNT", "D. MAX");
            question(doc, "4. 电子邮件地址中“@”后面的部分是？", "A. 用户名", "B. 域名", "C. 密码", "D. 端口号");

            section(doc, "二、多项选择题（每题 3 分）");
            question(doc, "5. 下列哪些属于计算机硬件？", "A. CPU", "B. 内存", "C. 操作系统", "D. 硬盘");
            question(doc, "6. 下列哪些属于应用软件？", "A. 微信", "B. 浏览器", "C. 操作系统", "D. 杀毒软件");

            section(doc, "三、判断题（每题 1 分，正确打√，错误打×）");
            question(doc, "7. 计算机网络按覆盖范围可分为局域网、城域网和广域网。（  ）", "");
            question(doc, "8. 防火墙可以完全阻止所有网络攻击。（  ）", "");

            section(doc, "四、参考答案");
            answer(doc, "1.B  2.C  3.B  4.B  5.ABD  6.ABD  7.√  8.×");

            save(doc, "exam-computer-paper.docx");
        }
    }

    /** 复习手册样式：题干后紧跟答案与解析 */
    private void writeDocxReview() throws IOException {
        try (XWPFDocument doc = new XWPFDocument()) {
            title(doc, "计算机基础知识·复习手册");
            sub(doc, "每道题后直接附答案与解析，适合快速过一遍");

            question(doc, "1. 1 兆（MB）等于多少 KB？", "");
            answer(doc, "答案：1024 KB    解析：1MB = 1024KB");

            question(doc, "2. 冯·诺依曼体系结构的核心思想是？", "A. 存储程序", "B. 并行计算", "C. 人工智能", "D. 分布式计算");
            answer(doc, "答案：A    解析：“存储程序控制”是冯·诺依曼体系的核心");

            question(doc, "3. 下列哪个是数据库管理系统？", "A. Excel", "B. MySQL", "C. Photoshop", "D. PowerPoint");
            answer(doc, "答案：B    解析：MySQL 是开源关系型数据库");

            question(doc, "4. 判断题：云计算是一种按需付费的计算服务模式。", "");
            answer(doc, "答案：正确    解析：资源池化 + 按需服务是云计算的本质");

            question(doc, "5. 下列哪些属于即时通信软件？", "A. 微信", "B. QQ", "C. 钉钉", "D. Oracle");
            answer(doc, "答案：ABC    解析：Oracle 是数据库");

            question(doc, "6. URL 的全称是？", "A. 统一资源定位符", "B. 域名系统", "C. 传输控制协议", "D. 互联网协议");
            answer(doc, "答案：A");

            question(doc, "7. 判断题：5G 网络的传输速度一定比 4G 快。", "");
            answer(doc, "答案：错误    解析：5G 理论峰值更高，但实际速度受覆盖和设备影响");

            question(doc, "8. 以下哪个是开源操作系统？", "A. Linux", "B. Windows", "C. macOS", "D. iOS");
            answer(doc, "答案：A");

            save(doc, "review-computer-basics.docx");
        }
    }

    // ==================== PDF ====================

    /** 打印版试卷（中文，嵌入系统字体） */
    private void writePdfExam() throws Exception {
        List<String> lines = List.of(
                "《公共基础知识》模拟试卷（部分）",
                "一、单项选择题",
                "1. 我国现行宪法是哪一年颁布的？",
                "   A. 1954   B. 1978   C. 1982   D. 1999",
                "2. 我国的根本政治制度是？",
                "   A. 人民代表大会制度   B. 多党合作制度",
                "   C. 民族区域自治制度   D. 基层群众自治制度",
                "3. 行政处罚的种类不包括？",
                "   A. 警告   B. 罚款   C. 拘役   D. 没收违法所得",
                "二、多项选择题",
                "4. 下列哪些属于我国公民的基本权利？",
                "   A. 选举权   B. 被选举权   C. 受教育权   D. 依法纳税",
                "5. 下列哪些属于行政强制措施？",
                "   A. 限制人身自由   B. 查封   C. 扣押   D. 行政拘留",
                "三、判断题",
                "6. 法律面前人人平等。（  ）",
                "7. 我国的最高国家权力机关是全国人民代表大会。（  ）",
                "",
                "参考答案：1.C  2.A  3.C  4.ABC  5.ABC  6.对  7.对");
        writePdf("exam-public-affairs.pdf", lines, 16);
    }

    /** 打印版题库 PDF */
    private void writePdfBank() throws Exception {
        List<String> lines = List.of(
                "初中地理知识问答（打印版）",
                "1. 地球仪上，连接南北两极的线叫？",
                "   A. 纬线   B. 经线   C. 赤道   D. 回归线",
                "2. 世界上面积最大的大洋是？",
                "   A. 太平洋   B. 大西洋   C. 印度洋   D. 北冰洋",
                "3. 下列哪些属于可再生资源？",
                "   A. 太阳能   B. 煤炭   C. 风能   D. 森林",
                "4. 我国的首都是？",
                "   A. 上海   B. 北京   C. 广州   D. 深圳",
                "5. 七大洲中面积最大的是？",
                "   A. 亚洲   B. 非洲   C. 欧洲   D. 南美洲",
                "6. 判断：长江是我国最长的河流。（  ）",
                "7. 判断：地球自转产生了昼夜交替。（  ）",
                "",
                "参考答案：1.B  2.A  3.ACD  4.B  5.A  6.对  7.对");
        writePdf("quiz-geography.pdf", lines, 14);
    }

    private void writePdf(String fileName, List<String> lines, float fontSize) throws Exception {
        try (PDDocument doc = new PDDocument()) {
            PDFont font = loadChineseFont(doc);
            PDPage page = new PDPage();
            doc.addPage(page);
            PDPageContentStream cs = new PDPageContentStream(doc, page);
            cs.beginText();
            cs.setFont(font, fontSize);
            cs.newLineAtOffset(50, 780);
            float lineHeight = fontSize + 8;
            int lineOnPage = 0;
            for (String line : lines) {
                //每页最多 38 行，超出分页
                if (lineOnPage >= 38) {
                    cs.endText();
                    cs.close();
                    page = new PDPage();
                    doc.addPage(page);
                    cs = new PDPageContentStream(doc, page);
                    cs.beginText();
                    cs.setFont(font, fontSize);
                    cs.newLineAtOffset(50, 780);
                    lineOnPage = 0;
                }
                cs.showText(line);
                cs.newLineAtOffset(0, -lineHeight);
                lineOnPage++;
            }
            cs.endText();
            cs.close();
            doc.save(OUT.resolve(fileName).toFile());
        }
    }

    /** 探测系统中文字体（仅 TTF 单字体，PDFBox 2.x 不支持 ttc 集合） */
    private PDFont loadChineseFont(PDDocument doc) throws IOException {
        String[] candidates = {
                "C:/Windows/Fonts/simhei.ttf",   //黑体
                "C:/Windows/Fonts/Deng.ttf",     //等线
                "C:/Windows/Fonts/simfang.ttf",  //仿宋
                "C:/Windows/Fonts/simkai.ttf"    //楷体
        };
        for (String path : candidates) {
            File f = new File(path);
            if (f.exists()) {
                try (var in = new java.io.FileInputStream(f)) {
                    return PDType0Font.load(doc, in, true); //嵌入子集
                }
            }
        }
        throw new IllegalStateException("未找到系统中文字体 TTF（simhei/Deng/simfang/simkai），无法生成中文 PDF");
    }

    // ==================== POI 工具 ====================

    private void title(XWPFDocument doc, String text) {
        XWPFParagraph p = doc.createParagraph();
        p.setAlignment(org.apache.poi.xwpf.usermodel.ParagraphAlignment.CENTER);
        p.createRun().setText(text);
        p.getRuns().get(0).setBold(true);
        p.getRuns().get(0).setFontSize(16);
    }

    private void sub(XWPFDocument doc, String text) {
        XWPFParagraph p = doc.createParagraph();
        p.setAlignment(org.apache.poi.xwpf.usermodel.ParagraphAlignment.CENTER);
        p.createRun().setText(text);
        p.getRuns().get(0).setFontSize(11);
        p.getRuns().get(0).setColor("808080");
    }

    private void section(XWPFDocument doc, String text) {
        XWPFParagraph p = doc.createParagraph();
        p.createRun().setText(text);
        p.getRuns().get(0).setBold(true);
        p.getRuns().get(0).setFontSize(13);
        p.setSpacingBefore(240);
    }

    private void question(XWPFDocument doc, String stem, String... options) {
        XWPFParagraph p = doc.createParagraph();
        p.createRun().setText(stem);
        p.getRuns().get(0).setFontSize(11);
        for (String opt : options) {
            if (!opt.isBlank()) {
                XWPFParagraph op = doc.createParagraph();
                op.setIndentationLeft(360);
                op.createRun().setText(opt);
                op.getRuns().get(0).setFontSize(11);
            }
        }
    }

    private void answer(XWPFDocument doc, String text) {
        XWPFParagraph p = doc.createParagraph();
        p.setIndentationLeft(360);
        p.createRun().setText(text);
        p.getRuns().get(0).setFontSize(11);
        p.getRuns().get(0).setColor("1a7f37");
    }

    private void save(XWPFDocument doc, String fileName) throws IOException {
        try (FileOutputStream out = new FileOutputStream(OUT.resolve(fileName).toFile())) {
            doc.write(out);
        }
    }
}
