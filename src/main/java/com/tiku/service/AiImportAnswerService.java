package com.tiku.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tiku.config.AiSettings;
import com.tiku.model.ContentPackageMaterial;
import com.tiku.model.ContentPackageQuestion;
import com.tiku.model.OptionItem;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** AI 导入完成后的答案恢复、AI 补充和材料引用收尾。 */
@Slf4j
@Component
public class AiImportAnswerService {

    private static final Pattern IMAGE_REFERENCE = Pattern.compile("\\[图片(\\d+)]");

    private final AiClientService aiClientService;
    private final AiImportPromptFactory promptFactory;
    private final ObjectMapper objectMapper;
    private final AiImportSourceTextService sourceTextService;
    private final AiImportFormulaService formulaService;
    private final java.util.concurrent.ExecutorService aiChunkExecutor;

    public AiImportAnswerService(AiClientService aiClientService,
                                 AiImportPromptFactory promptFactory,
                                 ObjectMapper objectMapper,
                                 AiImportSourceTextService sourceTextService,
                                 AiImportFormulaService formulaService,
                                 @Qualifier("aiChunkExecutor") java.util.concurrent.ExecutorService aiChunkExecutor) {
        this.aiClientService = aiClientService;
        this.promptFactory = promptFactory;
        this.objectMapper = objectMapper;
        this.sourceTextService = sourceTextService;
        this.formulaService = formulaService;
        this.aiChunkExecutor = aiChunkExecutor;
    }

    /**
     * 优先恢复用户原文中的答案证据；仅在用户启用补充时，才用思考模型补全缺失答案。
     */
    public AiImportResult postProcess(AiImportResult result, boolean aiSupplement, String sourceText,
                                      String evidenceSource, AiSettings settings, Long jobId,
                                      List<DocumentParserService.ExtractedImage> extractedImages) {
        List<ContentPackageQuestion> questions = result.questions();
        if (questions.isEmpty()) {
            return result;
        }
        normalizeMislabeledChoiceQuestions(questions);
        clearModelAnswers(questions);
        RecoveryStats recovery = recoverOriginalAnswers(questions, sourceText, evidenceSource);
        if (!aiSupplement) {
            log.info("AI 导入任务 {} 答案处理完成（未勾选 AI 补充）：{} 题原文证据恢复，{} 题主观题原文参考答案恢复，缺失留空",
                    jobId, recovery.objectiveCount(), recovery.subjectiveCount());
            return result;
        }
        List<ContentPackageQuestion> missing = questions.stream()
                .filter(question -> question.getAnswerKeys() == null || question.getAnswerKeys().isEmpty())
                .filter(question -> !isRecoveredSubjective(question))
                .toList();
        if (missing.isEmpty()) {
            log.info("AI 导入任务 {} 答案处理完成：{} 题原文证据恢复，{} 题主观题参考答案恢复，无需 AI 补充",
                    jobId, recovery.objectiveCount(), recovery.subjectiveCount());
            return result;
        }
        supplementAnswers(missing, settings, jobId, extractedImages, result.materials());
        clearDanglingMaterialReferences(questions, result.materials(), jobId);
        log.info("AI 导入任务 {} 答案处理完成：{} 题原文证据恢复，{} 题主观题参考答案恢复，{} 题思考模式补充（含失败留空）",
                jobId, recovery.objectiveCount(), recovery.subjectiveCount(), missing.size());
        formulaService.normalizeResult(result);
        return result;
    }

    private void normalizeMislabeledChoiceQuestions(List<ContentPackageQuestion> questions) {
        for (ContentPackageQuestion question : questions) {
            if (!"SUBJECTIVE".equals(question.getType())) {
                continue;
            }
            String content = question.getContent() == null ? "" : question.getContent();
            boolean subjectivePhrase = content.matches(".*(请简述|请论述|请说明|谈谈|说明理由|简述|论述|撰写|作答|请分析|填正确答案标号|（填|求|计算).*");
            boolean choicePhrase = content.matches(".*(哪个|哪项|以下|下列|多少|正确|错误|属于|能够|不能|选择|填入).*");
            boolean hasOptions = question.getOptions() != null && question.getOptions().size() >= 2
                    && question.getOptions().stream().allMatch(option -> option != null && option.text() != null
                    && !option.text().isBlank());
            if (choicePhrase && !subjectivePhrase && hasOptions) {
                question.setType("SINGLE");
            }
        }
    }

    private void clearModelAnswers(List<ContentPackageQuestion> questions) {
        for (ContentPackageQuestion question : questions) {
            question.setAnswerKeys(List.of());
            question.setAnswerText(null);
            question.setAnalysis(null);
            question.setReferenceAnswer(null);
            question.setAnswerSource(null);
        }
    }

    private RecoveryStats recoverOriginalAnswers(List<ContentPackageQuestion> questions, String sourceText,
                                                 String evidenceSource) {
        int objectiveCount = 0;
        int subjectiveCount = 0;
        for (ContentPackageQuestion question : questions) {
            if ("SUBJECTIVE".equals(question.getType())) {
                Integer questionNumber = question.getQuestionNumber();
                if (questionNumber == null) {
                    questionNumber = locateNumberByContent(sourceText, question);
                }
                if (questionNumber != null) {
                    String referenceAnswer = AiAnswerFormat.bracketAnswerTail(evidenceSource, questionNumber);
                    if (referenceAnswer != null && !referenceAnswer.isBlank()) {
                        question.setReferenceAnswer(referenceAnswer);
                        question.setAnswerSource("ORIGINAL");
                        subjectiveCount++;
                    }
                }
                continue;
            }
            if (question.getOptions() == null || question.getOptions().isEmpty()) {
                continue;
            }
            Set<String> evidence = sourceTextService.collectSourceAnswerEvidence(evidenceSource, question);
            if (evidence.isEmpty()) {
                continue;
            }
            List<String> optionKeys = question.getOptions().stream().map(OptionItem::key)
                    .map(String::toUpperCase).toList();
            List<String> answerKeys = evidence.stream().filter(optionKeys::contains).distinct().sorted().toList();
            if (!answerKeys.isEmpty()) {
                question.setAnswerKeys(answerKeys);
                question.setAnswerSource("ORIGINAL");
                objectiveCount++;
            }
        }
        return new RecoveryStats(objectiveCount, subjectiveCount);
    }

    private boolean isRecoveredSubjective(ContentPackageQuestion question) {
        return "SUBJECTIVE".equals(question.getType())
                && question.getReferenceAnswer() != null && !question.getReferenceAnswer().isBlank();
    }

    private void clearDanglingMaterialReferences(List<ContentPackageQuestion> questions,
                                                 List<ContentPackageMaterial> materials, Long jobId) {
        if (materials == null || materials.isEmpty()) {
            return;
        }
        Set<String> materialKeys = new HashSet<>();
        for (ContentPackageMaterial material : materials) {
            if (material.getMaterialKey() != null) {
                materialKeys.add(material.getMaterialKey());
            }
        }
        for (ContentPackageQuestion question : questions) {
            if (question.getMaterialKey() != null && !materialKeys.contains(question.getMaterialKey())) {
                log.warn("AI 导入任务 {} 题目引用材料 {} 但最终材料不存在，清空引用（预览可手动关联）",
                        jobId, question.getMaterialKey());
                question.setMaterialKey(null);
            }
        }
    }

    private Integer locateNumberByContent(String sourceText, ContentPackageQuestion question) {
        if (sourceText == null || sourceText.isBlank() || question.getContent() == null || question.getContent().isBlank()) {
            return null;
        }
        String[] lines = sourceText.split("\\R", -1);
        int line = sourceTextService.locateContentLine(lines, stripImageReferences(question.getContent()));
        if (line < 0) {
            return null;
        }
        int number = sourceTextService.findQuestionNumber(lines, line, true);
        return number > 0 ? number : null;
    }

    private void supplementAnswers(List<ContentPackageQuestion> missing, AiSettings settings, Long jobId,
                                   List<DocumentParserService.ExtractedImage> extractedImages,
                                   List<ContentPackageMaterial> materials) {
        AiSettings thinkingSettings = promptFactory.withThinking(settings, true);
        List<Future<Void>> futures = new ArrayList<>();
        for (int start = 0; start < missing.size(); start += 10) {
            List<ContentPackageQuestion> batch = missing.subList(start, Math.min(missing.size(), start + 10));
            futures.add(aiChunkExecutor.submit(() -> {
                supplementBatch(batch, thinkingSettings, jobId, extractedImages, materials);
                return null;
            }));
        }
        for (Future<Void> future : futures) {
            try {
                future.get(5, TimeUnit.MINUTES);
            } catch (Exception exception) {
                log.warn("AI 导入任务 {} 答案补充批次失败：{}", jobId, exception.getMessage());
            }
        }
    }

    private void supplementBatch(List<ContentPackageQuestion> batch, AiSettings settings, Long jobId,
                                 List<DocumentParserService.ExtractedImage> extractedImages,
                                 List<ContentPackageMaterial> materials) {
        StringBuilder prompt = new StringBuilder("你是题库答案助手。下面是 ").append(batch.size())
                .append(" 道题目（题干+选项），请为每道题给出正确答案和简要解析。\n")
                .append("严格输出一个 JSON 对象：{\"answers\":[{\"index\":1,\"answerKeys\":[\"B\"],\"analysis\":\"...\"}]}\n")
                .append("规则：index 从 1 开始对应题目顺序；answerKeys 必须是选项 key（单选/判断一个，多选多个）；")
                .append("判断题选项固定 A=正确/B=错误；题目是图片时用 [图片N] 标记引用；")
                .append("无法确定答案时 answerKeys 返回空数组 []，不要编造；解析简明即可。\n\n");
        List<AiClientService.ImageData> images = new ArrayList<>();
        appendMaterials(prompt, materials, extractedImages, images);
        for (int index = 0; index < batch.size(); index++) {
            ContentPackageQuestion question = batch.get(index);
            prompt.append(index + 1).append(". ").append(question.getContent() == null ? "" : question.getContent()).append('\n');
            if (question.getOptions() != null) {
                for (OptionItem option : question.getOptions()) {
                    prompt.append("   ").append(option.key()).append(". ").append(option.text()).append('\n');
                }
            }
            prompt.append('\n');
            appendReferencedImages(question.getContent(), extractedImages, images);
        }
        try {
            String output = images.isEmpty()
                    ? aiClientService.chat(settings, promptFactory.buildSystemPrompt(true, false), prompt.toString(), true)
                    : aiClientService.chatWithImages(promptFactory.buildVisionSettings(settings),
                    promptFactory.buildSystemPrompt(true, false), prompt.toString(), images, true);
            applySupplementOutput(objectMapper.readTree(stripCodeFence(output)).path("answers"), batch, jobId);
        } catch (Exception exception) {
            log.warn("AI 导入任务 {} 答案补充批次失败：{}", jobId, exception.getMessage());
        }
    }

    private void appendMaterials(StringBuilder prompt, List<ContentPackageMaterial> materials,
                                 List<DocumentParserService.ExtractedImage> extractedImages,
                                 List<AiClientService.ImageData> images) {
        if (materials == null || materials.isEmpty()) {
            return;
        }
        prompt.append("【共享材料（供作答参考，本文档部分题目依赖以下材料才能作答；仅用于判断答案，不要输出材料文字）】\n");
        for (ContentPackageMaterial material : materials) {
            prompt.append(material.getContent()).append('\n');
            appendReferencedImages(material.getContent(), extractedImages, images);
        }
        prompt.append("\n\n");
    }

    private void appendReferencedImages(String text, List<DocumentParserService.ExtractedImage> extractedImages,
                                        List<AiClientService.ImageData> images) {
        if (text == null || extractedImages == null) {
            return;
        }
        Matcher matcher = IMAGE_REFERENCE.matcher(text);
        while (matcher.find()) {
            int number = Integer.parseInt(matcher.group(1));
            if (number >= 1 && number <= extractedImages.size()) {
                AiClientService.ImageData image = extractedImages.get(number - 1).image();
                if (!images.contains(image)) {
                    images.add(image);
                }
            }
        }
    }

    private void applySupplementOutput(JsonNode answers, List<ContentPackageQuestion> batch, Long jobId) {
        if (!answers.isArray()) {
            log.warn("AI 导入任务 {} 答案补充输出格式异常（无 answers 数组），批次留空", jobId);
            return;
        }
        for (JsonNode item : answers) {
            int index = item.path("index").asInt() - 1;
            if (index < 0 || index >= batch.size()) {
                continue;
            }
            ContentPackageQuestion question = batch.get(index);
            List<String> answerKeys = parseAnswerKeys(item.path("answerKeys"));
            if (question.getOptions() != null && !question.getOptions().isEmpty()) {
                List<String> optionKeys = question.getOptions().stream().map(OptionItem::key)
                        .map(String::toUpperCase).toList();
                answerKeys.removeIf(key -> !optionKeys.contains(key));
            }
            if (!answerKeys.isEmpty()) {
                question.setAnswerKeys(answerKeys);
                question.setAnswerSource("AI_SUPPLEMENT");
            }
            if (item.hasNonNull("analysis")) {
                question.setAnalysis(item.path("analysis").asText(null));
            }
            if (item.hasNonNull("answerText")) {
                question.setAnswerText(item.path("answerText").asText(null));
            }
            if (item.hasNonNull("referenceAnswer")
                    && (question.getReferenceAnswer() == null || question.getReferenceAnswer().isBlank())) {
                question.setReferenceAnswer(item.path("referenceAnswer").asText(null));
            }
        }
    }

    private List<String> parseAnswerKeys(JsonNode keysNode) {
        List<String> keys = new ArrayList<>();
        if (keysNode.isArray()) {
            for (JsonNode key : keysNode) {
                keys.add(key.asText().trim().toUpperCase());
            }
        } else if (keysNode.isTextual() && !keysNode.asText().isBlank()) {
            for (char character : keysNode.asText().toUpperCase().toCharArray()) {
                String key = String.valueOf(character);
                if (key.matches("[A-H]")) {
                    keys.add(key);
                }
            }
        }
        return keys;
    }

    private String stripImageReferences(String text) {
        return text.replaceAll("\\[图片\\d+\\]", " ");
    }

    private String stripCodeFence(String output) {
        String normalized = output == null ? "" : output.trim();
        int start = normalized.indexOf('{');
        int end = normalized.lastIndexOf('}');
        return start >= 0 && end > start ? normalized.substring(start, end + 1) : normalized;
    }

    private record RecoveryStats(int objectiveCount, int subjectiveCount) {
    }
}
