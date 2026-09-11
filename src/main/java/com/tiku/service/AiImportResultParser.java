package com.tiku.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tiku.model.ContentPackageMaterial;
import com.tiku.model.ContentPackageQuestion;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/** AI 输出的 Markdown/JSON 兼容解析与确定性安全校验。 */
@Component
public class AiImportResultParser {

    private final ObjectMapper objectMapper;
    private final AiImportSourceTextService sourceTextService;
    private final AiImportTextStructure textStructure;

    public AiImportResultParser(ObjectMapper objectMapper,
                                AiImportSourceTextService sourceTextService,
                                AiImportTextStructure textStructure) {
        this.objectMapper = objectMapper;
        this.sourceTextService = sourceTextService;
        this.textStructure = textStructure;
    }

    public List<ContentPackageQuestion> parseMarkdownQuestions(String markdownOutput) {
        return parseMarkdown(markdownOutput).questions();
    }

    public AiImportResult parseMarkdown(String markdownOutput) {
        String markdown = unwrapJsonOutput(markdownOutput);
        MdQuestionParser.ParseOutcome outcome = MdQuestionParser.parseWithMaterials(markdown);
        List<ContentPackageQuestion> questions = new ArrayList<>();
        for (ContentPackageQuestion question : outcome.questions()) {
            if (sourceTextService.validateQuestion(question)) {
                questions.add(question);
            }
        }
        return new AiImportResult(questions, outcome.materials());
    }

    /**
     * 解析 JSON 格式旧协议结果，并将模型输出与源文进行题干、选项及答案证据比对。
     */
    public AiImportResult parseJson(String aiOutput, boolean aiSupplement, String sourceText,
                                    boolean enableMaterials, boolean skipSourceRepair) {
        JsonNode root;
        try {
            root = objectMapper.readTree(stripJsonFence(aiOutput));
        } catch (IOException exception) {
            throw new IllegalStateException("AI 输出不是合法 JSON，请重试或更换模型");
        }
        List<ContentPackageMaterial> materials = parseMaterials(root, enableMaterials);
        Set<String> materialKeys = materials.stream()
                .map(ContentPackageMaterial::getMaterialKey)
                .collect(Collectors.toSet());
        JsonNode questionNodes = root.isArray() ? root : root.path("questions");
        List<ContentPackageQuestion> questions = new ArrayList<>();
        if (!questionNodes.isArray()) {
            return new AiImportResult(questions, materials);
        }
        for (JsonNode item : questionNodes) {
            parseQuestion(item, aiSupplement, sourceText, skipSourceRepair, materialKeys).ifPresent(questions::add);
        }
        return new AiImportResult(questions, materials);
    }

    public boolean looksLikeJson(String output) {
        String normalized = output == null ? "" : output.trim();
        return (normalized.startsWith("{") || normalized.startsWith("[")) && normalized.contains("question");
    }

    public void assertNotDegraded(List<ContentPackageQuestion> questions, String sourceText) {
        if (sourceText == null || sourceText.isBlank()) {
            return;
        }
        int sourceQuestionCount = 0;
        for (String line : sourceText.split("\\R", -1)) {
            if (textStructure.isQuestionNumberLine(line.trim())) {
                sourceQuestionCount++;
            }
        }
        if (sourceQuestionCount >= 5 && questions.size() < 3) {
            throw new IllegalStateException("模型端点持续返回空白内容（源文约 " + sourceQuestionCount + " 题仅产出 "
                    + questions.size() + " 题），本次导入失败，请稍后重试");
        }
    }

    private List<ContentPackageMaterial> parseMaterials(JsonNode root, boolean enabled) {
        List<ContentPackageMaterial> materials = new ArrayList<>();
        if (!enabled || !root.path("materials").isArray()) {
            return materials;
        }
        Set<String> seenKeys = new HashSet<>();
        for (JsonNode item : root.path("materials")) {
            try {
                ContentPackageMaterial material = objectMapper.treeToValue(item, ContentPackageMaterial.class);
                if (material.getMaterialKey() == null || material.getMaterialKey().isBlank()
                        || material.getContent() == null || material.getContent().isBlank()
                        || !seenKeys.add(material.getMaterialKey())) {
                    continue;
                }
                materials.add(material);
            } catch (Exception ignored) {
                // 单个坏材料不影响其他题目。
            }
        }
        return materials;
    }

    private java.util.Optional<ContentPackageQuestion> parseQuestion(JsonNode item, boolean aiSupplement,
                                                                       String sourceText, boolean skipSourceRepair,
                                                                       Set<String> materialKeys) {
        try {
            ContentPackageQuestion question = objectMapper.treeToValue(item, ContentPackageQuestion.class);
            if (!sourceTextService.validateQuestion(question)) {
                return java.util.Optional.empty();
            }
            if (question.getMaterialKey() != null && !question.getMaterialKey().isBlank()
                    && !materialKeys.contains(question.getMaterialKey())) {
                question.setMaterialKey(null);
            }
            if (!skipSourceRepair && !sourceTextService.completeMaterialFromSource(question, sourceText)) {
                return java.util.Optional.empty();
            }
            normalizeAnswer(question, aiSupplement, sourceText);
            return java.util.Optional.of(question);
        } catch (Exception ignored) {
            return java.util.Optional.empty();
        }
    }

    private void normalizeAnswer(ContentPackageQuestion question, boolean aiSupplement, String sourceText) {
        if (question.getAnswerKeys() != null && !question.getAnswerKeys().isEmpty()) {
            boolean hasEvidence = sourceTextService.hasSourceAnswerEvidence(sourceText, question);
            if (!aiSupplement && !hasEvidence) {
                question.setAnswerKeys(List.of());
                question.setAnswerSource(null);
            } else {
                question.setAnswerSource(hasEvidence ? "ORIGINAL" : "AI_SUPPLEMENT");
            }
        } else {
            question.setAnswerSource(null);
        }
        if (!aiSupplement) {
            question.setAnswerText(null);
            question.setAnalysis(null);
            question.setReferenceAnswer(null);
        }
    }

    private String unwrapJsonOutput(String output) {
        String normalized = output == null ? "" : stripMarkdownFence(output).trim();
        if (!normalized.startsWith("{")) {
            return output;
        }
        try {
            JsonNode root = objectMapper.readTree(normalized);
            JsonNode markdown = root.path("output");
            if (markdown.isTextual() && !markdown.asText().isBlank()) {
                return markdown.asText();
            }
        } catch (Exception ignored) {
            // 非 JSON 的 Markdown 输出由调用方继续解析。
        }
        return output;
    }

    private String stripMarkdownFence(String output) {
        String normalized = output == null ? "" : output.trim();
        if (!normalized.startsWith("```")) {
            return normalized;
        }
        int lineBreak = normalized.indexOf('\n');
        if (lineBreak >= 0) {
            normalized = normalized.substring(lineBreak + 1);
        }
        int closingFence = normalized.lastIndexOf("```");
        return closingFence >= 0 ? normalized.substring(0, closingFence) : normalized;
    }

    private String stripJsonFence(String output) {
        String normalized = output == null ? "" : output.trim();
        int start = normalized.indexOf('{');
        int end = normalized.lastIndexOf('}');
        return start >= 0 && end > start ? normalized.substring(start, end + 1) : normalized;
    }
}
