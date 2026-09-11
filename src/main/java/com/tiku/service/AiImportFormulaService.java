package com.tiku.service;

import com.tiku.config.AiSettings;
import com.tiku.model.ContentPackageMaterial;
import com.tiku.model.ContentPackageQuestion;
import com.tiku.model.OptionItem;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.Consumer;
import java.util.function.Supplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** AI 导入结果的 LaTeX 规范化与公式图片转写兜底。 */
@Slf4j
@Component
public class AiImportFormulaService {

    private static final Pattern IMAGE_REFERENCE = Pattern.compile("\\[图片(\\d+)]");

    private final AiClientService aiClientService;
    private final AiImportPromptFactory promptFactory;

    public AiImportFormulaService(AiClientService aiClientService, AiImportPromptFactory promptFactory) {
        this.aiClientService = aiClientService;
        this.promptFactory = promptFactory;
    }

    public void normalizeResult(AiImportResult result) {
        for (ContentPackageQuestion question : result.questions()) {
            question.setContent(normalizeDelimiters(question.getContent()));
            if (question.getOptions() != null) {
                question.setOptions(question.getOptions().stream()
                        .map(option -> new OptionItem(option.key(), normalizeDelimiters(option.text())))
                        .toList());
            }
            question.setAnalysis(normalizeDelimiters(question.getAnalysis()));
            question.setReferenceAnswer(normalizeDelimiters(question.getReferenceAnswer()));
            question.setAnswerText(normalizeDelimiters(question.getAnswerText()));
        }
        if (result.materials() != null) {
            for (ContentPackageMaterial material : result.materials()) {
                material.setContent(normalizeDelimiters(material.getContent()));
            }
        }
    }

    public AiImportResult replaceResidualFormulaImages(AiImportResult result, Set<Integer> formulaNumbers,
                                                        List<DocumentParserService.ExtractedImage> extractedImages,
                                                        AiSettings settings, Long jobId) {
        try {
            Set<Integer> residualReferences = new TreeSet<>();
            Map<Integer, String> contextByReference = new HashMap<>();
            collectQuestionReferences(result.questions(), formulaNumbers, residualReferences, contextByReference);
            if (result.materials() != null) {
                for (ContentPackageMaterial material : result.materials()) {
                    String context = questionContext(material.getContent());
                    collectReferences(material.getContent(), formulaNumbers, residualReferences, context, contextByReference);
                }
            }
            if (residualReferences.isEmpty()) {
                return result;
            }
            log.info("AI 导入任务 {} 残留公式图 {} 个（{}），逐张带上下文 LaTeX 转写兜底", jobId,
                    residualReferences.size(), residualReferences.stream().map(number -> "[图片" + number + "]")
                            .reduce("", (left, right) -> left + right));
            Map<Integer, String> replacements = transcribeFormulas(residualReferences, contextByReference,
                    extractedImages, settings, jobId);
            if (replacements.isEmpty()) {
                return result;
            }
            int replaced = applyReplacements(result, replacements);
            log.info("AI 导入任务 {} 公式转写回填完成：{} 处", jobId, replaced);
        } catch (Exception exception) {
            log.warn("AI 导入任务 {} 残留公式转写兜底失败（保留原图标记）：{}", jobId, exception.getMessage());
        }
        return result;
    }

    private void collectQuestionReferences(List<ContentPackageQuestion> questions, Set<Integer> formulaNumbers,
                                           Set<Integer> references, Map<Integer, String> contextByReference) {
        for (ContentPackageQuestion question : questions) {
            String context = questionContext(question.getContent());
            collectReferences(question.getContent(), formulaNumbers, references, context, contextByReference);
            if (question.getOptions() != null) {
                for (OptionItem option : question.getOptions()) {
                    collectReferences(option.text(), formulaNumbers, references, context, contextByReference);
                }
            }
            collectReferences(question.getReferenceAnswer(), formulaNumbers, references, context, contextByReference);
            collectReferences(question.getAnswerText(), formulaNumbers, references, context, contextByReference);
        }
    }

    private Map<Integer, String> transcribeFormulas(Set<Integer> references, Map<Integer, String> contextByReference,
                                                     List<DocumentParserService.ExtractedImage> extractedImages,
                                                     AiSettings settings, Long jobId) {
        AiSettings thinkingSettings = promptFactory.withThinking(settings, true);
        AiSettings visionSettings = promptFactory.buildVisionSettings(thinkingSettings);
        Map<Integer, String> replacements = new HashMap<>();
        for (int number : references) {
            if (number < 1 || number > extractedImages.size()) {
                continue;
            }
            try {
                String context = contextByReference.getOrDefault(number, "");
                String userPrompt = "题目背景：" + (context.isEmpty() ? "（该公式是某道题的官方答案）" : context)
                        + "\n下图中是该处对应的数学公式/数值答案，请逐字符完整转写为 LaTeX 文本"
                        + "（行内公式用 $...$ 包裹；图中每个数字、字母、单位、上下标都必须保留，禁止省略或简化，务必照图准确）；"
                        + "只输出 LaTeX，不要解释。";
                byte[] image = upscale2x(extractedImages.get(number - 1).image().data());
                String output = aiClientService.chatWithImages(visionSettings, "你是公式转写助手。", userPrompt,
                        List.of(new AiClientService.ImageData("image/png", image)), false);
                String latex = normalizeFormulaOutput(output);
                if (latex.length() > 2 && latex.startsWith("$") && latex.endsWith("$")) {
                    replacements.put(number, latex);
                    log.info("AI 导入任务 {} 残留公式 [图片{}] 转写：{}", jobId, number, truncate(latex, 200));
                } else {
                    log.warn("AI 导入任务 {} 残留公式 [图片{}] 转写无效（保留原图）：{}", jobId, number,
                            truncate(output, 200));
                }
            } catch (Exception exception) {
                log.warn("AI 导入任务 {} 残留公式 [图片{}] 转写失败（保留原图）：{}", jobId, number,
                        exception.getMessage());
            }
        }
        return replacements;
    }

    private int applyReplacements(AiImportResult result, Map<Integer, String> replacements) {
        int replaced = 0;
        for (ContentPackageQuestion question : result.questions()) {
            replaced += replaceIn(question::getContent, question::setContent, replacements);
            if (question.getOptions() != null && !question.getOptions().isEmpty()) {
                List<OptionItem> options = new ArrayList<>();
                boolean changed = false;
                for (OptionItem option : question.getOptions()) {
                    String text = applyReferences(option.text(), replacements);
                    changed |= !text.equals(option.text());
                    options.add(new OptionItem(option.key(), text));
                }
                if (changed) {
                    question.setOptions(options);
                    replaced += replacements.size();
                }
            }
            replaced += replaceIn(question::getReferenceAnswer, question::setReferenceAnswer, replacements);
            replaced += replaceIn(question::getAnswerText, question::setAnswerText, replacements);
        }
        if (result.materials() != null) {
            for (ContentPackageMaterial material : result.materials()) {
                replaced += replaceIn(material::getContent, material::setContent, replacements);
            }
        }
        return replaced;
    }

    private void collectReferences(String text, Set<Integer> formulaNumbers, Set<Integer> references,
                                   String context, Map<Integer, String> contextByReference) {
        if (text == null || text.isEmpty()) {
            return;
        }
        Matcher matcher = IMAGE_REFERENCE.matcher(text);
        while (matcher.find()) {
            int number = Integer.parseInt(matcher.group(1));
            if (formulaNumbers.contains(number)) {
                references.add(number);
                if (context != null && !context.isBlank()) {
                    contextByReference.putIfAbsent(number, context);
                }
            }
        }
    }

    private int replaceIn(Supplier<String> getter, Consumer<String> setter, Map<Integer, String> replacements) {
        String text = getter.get();
        if (text == null || text.isEmpty() || replacements.isEmpty()) {
            return 0;
        }
        String replacement = applyReferences(text, replacements);
        if (!replacement.equals(text)) {
            setter.accept(replacement);
            return 1;
        }
        return 0;
    }

    private String applyReferences(String text, Map<Integer, String> replacements) {
        if (text == null || text.isEmpty() || replacements.isEmpty()) {
            return text;
        }
        String result = text;
        for (Map.Entry<Integer, String> entry : replacements.entrySet()) {
            String marker = "[图片" + entry.getKey() + "]";
            if (result.contains(marker)) {
                result = result.replaceAll("\\[图片" + entry.getKey() + "]", Matcher.quoteReplacement(entry.getValue()));
            }
        }
        return result;
    }

    private String normalizeFormulaOutput(String output) {
        String latex = stripJsonFence(output == null ? "" : output.trim());
        if (latex.startsWith("\\(") && latex.endsWith("\\)")) {
            latex = "$" + latex.substring(2, latex.length() - 2).trim() + "$";
        }
        int firstDollar = latex.indexOf('$');
        if (firstDollar >= 0) {
            latex = latex.substring(firstDollar);
            int lastDollar = latex.lastIndexOf('$');
            if (lastDollar > firstDollar) {
                latex = latex.substring(0, lastDollar + 1);
            }
        } else if (!latex.isEmpty()) {
            latex = "$" + latex + "$";
        }
        return latex;
    }

    private String normalizeDelimiters(String text) {
        if (text == null || text.isEmpty()) {
            return text;
        }
        return text.replace("\\[", "$$").replace("\\]", "$$")
                .replace("\\(", "$").replace("\\)", "$");
    }

    private String questionContext(String content) {
        if (content == null || content.isBlank()) {
            return "";
        }
        String flattened = content.replaceAll("\\s+", " ").replaceAll("\\[图片\\d+]", " ").trim();
        return flattened.length() > 120 ? flattened.substring(0, 120) : flattened;
    }

    private byte[] upscale2x(byte[] imageBytes) {
        try {
            BufferedImage image = ImageIO.read(new ByteArrayInputStream(imageBytes));
            if (image == null) {
                return imageBytes;
            }
            BufferedImage enlarged = new BufferedImage(image.getWidth() * 2, image.getHeight() * 2,
                    BufferedImage.TYPE_INT_RGB);
            Graphics2D graphics = enlarged.createGraphics();
            graphics.setColor(Color.WHITE);
            graphics.fillRect(0, 0, enlarged.getWidth(), enlarged.getHeight());
            graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
            graphics.drawImage(image, 0, 0, enlarged.getWidth(), enlarged.getHeight(), null);
            graphics.dispose();
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            return ImageIO.write(enlarged, "png", output) ? output.toByteArray() : imageBytes;
        } catch (Exception ignored) {
            return imageBytes;
        }
    }

    private String stripJsonFence(String output) {
        String normalized = output.trim();
        int start = normalized.indexOf('{');
        int end = normalized.lastIndexOf('}');
        return start >= 0 && end > start ? normalized.substring(start, end + 1) : normalized;
    }

    private String truncate(String value, int maxLength) {
        if (value == null || value.length() <= maxLength) {
            return value;
        }
        return value.substring(0, maxLength) + "…";
    }
}
