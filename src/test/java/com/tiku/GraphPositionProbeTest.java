package com.tiku;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.rendering.ImageType;
import org.apache.pdfbox.rendering.PDFRenderer;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.pdfbox.text.TextPosition;
import org.junit.jupiter.api.Test;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * 临时调研（不入交付）：确认图形推理题的图在题干还是选项。
 * 方法：渲染每页 → 行坐标 → 每题区域（题号行到下一题号行）→ 区域内"题干带/选项带"像素密度分析。
 */
public class GraphPositionProbeTest {

    private static final Path PDF = Path.of("sample-ai-files").toAbsolutePath();
    private static final Path OUT = Path.of("target", "probe");

    private record Line(String text, float x, float y, float h) {
    }

    @Test
    public void probe() throws Exception {
        Files.createDirectories(OUT);
        Path pdfFile = null;
        try (var stream = Files.list(PDF)) {
            for (Path p : stream.toList()) {
                if (p.getFileName().toString().toLowerCase().endsWith(".pdf") && Files.size(p) == 681562) {
                    pdfFile = p;
                    break;
                }
            }
        }
        byte[] bytes = Files.readAllBytes(pdfFile);
        try (PDDocument doc = PDDocument.load(bytes)) {
            float scale = 110f / 72f;
            for (int pageNo = 3; pageNo <= 9; pageNo++) { //页 3-9（含图形题）
                List<Line> lines = collectLines(doc, pageNo);
                BufferedImage img = new PDFRenderer(doc).renderImageWithDPI(pageNo - 1, 110, ImageType.RGB);
                int H = img.getHeight();
                //题号行（孤立或行首）
                List<Integer> numIdx = new ArrayList<>();
                for (int i = 0; i < lines.size(); i++) {
                    String t = lines.get(i).text().trim();
                    if (t.matches("^\\d{1,3}\\s*[.．、)）]\\s*$") || t.matches("^\\d{1,3}\\s*[.．、)）].*")) {
                        numIdx.add(i);
                    }
                }
                System.out.println("===== page " + pageNo + " questions: " + numIdx.size() + " =====");
                for (int qi = 0; qi < numIdx.size(); qi++) {
                    int ni = numIdx.get(qi);
                    int nextNi = qi + 1 < numIdx.size() ? numIdx.get(qi + 1) : lines.size();
                    float top = lines.get(ni).y() + lines.get(ni).h;
                    float bot = nextNi < lines.size() ? lines.get(nextNi).y() : 842;
                    //区域内选项行（孤立 A./B. 或同行选项标记）
                    List<Float> optYs = new ArrayList<>();
                    List<Float> inlineOptX = null;
                    float inlineOptY = -1;
                    for (int i = ni + 1; i < nextNi; i++) {
                        String t = lines.get(i).text().trim();
                        if (t.matches("[A-Da-d][.．、]?")) {
                            optYs.add(lines.get(i).y());
                        } else if (t.matches("^[A-Da-d][.．、].*[A-Da-d][.．、]")) {
                            inlineOptX = new ArrayList<>();
                            inlineOptY = lines.get(i).y();
                        }
                    }
                    //题干带 = [题号行下沿, 首选项行或题尾)
                    float stemTop = top;
                    float stemBot = !optYs.isEmpty() ? optYs.get(0) : (inlineOptY > 0 ? inlineOptY : bot);
                    if (stemBot - stemTop > 4) {
                        float density = density(img, 0, H, stemTop * scale, stemBot * scale, scale);
                        System.out.printf("  Q%s stem[%.0f-%.0f] density=%.1f%%%n",
                                lines.get(ni).text().trim(), stemTop, stemBot, density);
                    }
                    //选项带
                    for (int oi = 0; oi < optYs.size(); oi++) {
                        float oTop = optYs.get(oi) - 20;
                        float oBot = oi + 1 < optYs.size() ? optYs.get(oi + 1) - 20 : optYs.get(oi) + 50;
                        float density = density(img, 0, H, oTop * scale, oBot * scale, scale);
                        System.out.printf("    opt%d y=%.0f density=%.1f%%%n", oi, optYs.get(oi), density);
                    }
                    if (inlineOptX != null) {
                        float density = density(img, 0, H, (inlineOptY - 20) * scale, (inlineOptY + 50) * scale, scale);
                        System.out.printf("    inline-opt y=%.0f density=%.1f%%%n", inlineOptY, density);
                    }
                }
            }
        }
    }

    /** 区域内非白像素密度（%）：文字行约 1-4%，图形块通常 >8% */
    private float density(BufferedImage img, int xFrom, int xTo, float yTopPx, float yBotPx, float scale) {
        int yTop = Math.max(0, (int) yTopPx);
        int yBot = Math.min(img.getHeight(), (int) yBotPx);
        if (yBot <= yTop) {
            return 0;
        }
        int dark = 0, total = 0;
        for (int y = yTop; y < yBot; y += 2) {
            for (int x = 0; x < img.getWidth(); x += 4) {
                int rgb = img.getRGB(x, y);
                int r = (rgb >> 16) & 0xFF, g = (rgb >> 8) & 0xFF, b = rgb & 0xFF;
                if (r < 230 || g < 230 || b < 230) {
                    dark++;
                }
                total++;
            }
        }
        return total == 0 ? 0 : dark * 100f / total;
    }

    private List<Line> collectLines(PDDocument doc, int pageNo) throws Exception {
        List<Line> lines = new ArrayList<>();
        PDFTextStripper stripper = new PDFTextStripper() {
            private final StringBuilder line = new StringBuilder();
            private float x = 0, y = 0, h = 0;

            @Override
            protected void writeString(String text, List<TextPosition> textPositions) {
                for (TextPosition tp : textPositions) {
                    if (line.length() == 0) {
                        x = tp.getXDirAdj();
                        y = tp.getYDirAdj();
                        h = tp.getHeightDir();
                    }
                    line.append(tp.getUnicode());
                }
            }

            @Override
            protected void writeLineSeparator() {
                if (line.length() > 0) {
                    lines.add(new Line(line.toString(), x, y, h));
                    line.setLength(0);
                }
            }

            @Override
            public void endPage(org.apache.pdfbox.pdmodel.PDPage page) {
                if (line.length() > 0) {
                    lines.add(new Line(line.toString(), x, y, h));
                    line.setLength(0);
                }
            }
        };
        stripper.setStartPage(pageNo);
        stripper.setEndPage(pageNo);
        stripper.getText(doc);
        return lines;
    }
}
