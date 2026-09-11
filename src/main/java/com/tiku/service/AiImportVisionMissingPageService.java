package com.tiku.service;

import com.tiku.config.AiSettings;
import com.tiku.model.ContentPackageMaterial;
import com.tiku.model.ContentPackageQuestion;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.BooleanSupplier;
import java.util.stream.Collectors;

/** 视觉分页导入的按页差异检测与补漏调用。 */
@Slf4j
@Component
public class AiImportVisionMissingPageService {

    private static final int MAX_RETRY_PAGES = 4;

    private final AiClientService aiClientService;
    private final AiImportPromptFactory promptFactory;
    private final AiImportResultParser resultParser;
    private final AiImportTextStructure textStructure;
    private final AiImportSourceTextService sourceTextService;
    private final AiImportVisionQualityService visionQualityService;

    public AiImportVisionMissingPageService(AiClientService aiClientService,
                                            AiImportPromptFactory promptFactory,
                                            AiImportResultParser resultParser,
                                            AiImportTextStructure textStructure,
                                            AiImportSourceTextService sourceTextService,
                                            AiImportVisionQualityService visionQualityService) {
        this.aiClientService = aiClientService;
        this.promptFactory = promptFactory;
        this.resultParser = resultParser;
        this.textStructure = textStructure;
        this.sourceTextService = sourceTextService;
        this.visionQualityService = visionQualityService;
    }

    /**
     * 对“参考题数大于已识别题数”的页面重新调用视觉模型，最多补漏四页。
     * 材料列表会按 materialKey 原地补充，问题列表以新列表形式返回。
     */
    public List<ContentPackageQuestion> fillMissingPages(List<ContentPackageQuestion> questions,
                                                          List<ContentPackageMaterial> materials,
                                                          Set<String> materialKeys,
                                                          List<String> pageTexts,
                                                          List<AiClientService.ImageData> pageImages,
                                                          List<DocumentParserService.ExtractedImage> extractedImages,
                                                          String sourceText, String warning, AiSettings visionSettings,
                                                          String systemPrompt, boolean aiSupplement, Long jobId,
                                                          BooleanSupplier cancelled) {
        List<Integer> missingPages = findMissingPages(questions, pageTexts, sourceText);
        if (missingPages.isEmpty()) {
            return questions;
        }
        log.info("AI 导入任务 {} 视觉补漏页面：{}", jobId,
                missingPages.stream().map(page -> String.valueOf(page + 1)).collect(Collectors.joining(",")));
        List<ContentPackageQuestion> recovered = new ArrayList<>();
        int processed = 0;
        for (int page : missingPages) {
            if (processed >= MAX_RETRY_PAGES || cancelled.getAsBoolean()) {
                break;
            }
            recoverPage(recovered, materials, materialKeys, pageTexts, pageImages, extractedImages, sourceText,
                    warning, visionSettings, systemPrompt, aiSupplement, jobId, page);
            processed++;
        }
        return mergeByQuality(questions, recovered);
    }

    private List<Integer> findMissingPages(List<ContentPackageQuestion> questions, List<String> pageTexts,
                                           String sourceText) {
        String[] sourceLines = sourceText.split("\\R", -1);
        int[] lineStarts = lineStarts(sourceLines);
        int[] pageStarts = pageStarts(pageTexts);
        int[] referenceCounts = new int[pageTexts.size()];
        int[] actualCounts = new int[pageTexts.size()];
        for (int page = 0; page < pageTexts.size(); page++) {
            referenceCounts[page] = textStructure.looseQuestionCount(pageTexts.get(page));
        }
        for (ContentPackageQuestion question : questions) {
            int line = sourceTextService.locateContentLine(sourceLines,
                    textStructure.stripImageRefs(question.getContent() == null ? "" : question.getContent()));
            if (line >= 0) {
                int page = textStructure.pageIndexOf(pageStarts, lineStarts[line]);
                if (page >= 0 && page < actualCounts.length) {
                    actualCounts[page]++;
                }
            }
        }
        List<Integer> missing = new ArrayList<>();
        for (int page = 0; page < pageTexts.size(); page++) {
            if (referenceCounts[page] >= 2 && actualCounts[page] < referenceCounts[page]) {
                missing.add(page);
            }
        }
        return missing;
    }

    private void recoverPage(List<ContentPackageQuestion> recovered, List<ContentPackageMaterial> materials,
                             Set<String> materialKeys, List<String> pageTexts,
                             List<AiClientService.ImageData> pageImages,
                             List<DocumentParserService.ExtractedImage> extractedImages, String sourceText,
                             String warning, AiSettings visionSettings, String systemPrompt, boolean aiSupplement,
                             Long jobId, int page) {
        String prompt = promptFactory.buildVisionPagePrompt(pageTexts, page, page + 1, pageTexts.size(), warning)
                + "\n该页在上一轮整理中有题目缺失（可能因题目内容全在图片中），请重新识别本页【全部】题目："
                + "包括图形推理题（题干/选项是图片的题，在对应位置写 [图片N] 标记），一题不漏。";
        List<AiClientService.ImageData> images = new ArrayList<>(pageImages.subList(page, page + 1));
        int firstImageNumber = 0;
        int lastImageNumber = 0;
        for (int index = 0; index < extractedImages.size(); index++) {
            DocumentParserService.ExtractedImage image = extractedImages.get(index);
            if (image.pageNo() == page) {
                if (firstImageNumber == 0) {
                    firstImageNumber = index + 1;
                }
                lastImageNumber = index + 1;
                images.add(image.image());
            }
        }
        if (firstImageNumber > 0) {
            prompt += promptFactory.buildVisionImageRefRule(firstImageNumber, lastImageNumber);
        }
        try {
            String output = aiClientService.chatWithImages(visionSettings, systemPrompt, prompt, images, true);
            AiImportResult result = resultParser.parseJson(output, aiSupplement, sourceText, true, true);
            visionQualityService.sanitizeImageReferences(result.questions(), firstImageNumber, lastImageNumber);
            for (ContentPackageQuestion question : result.questions()) {
                if (!visionQualityService.isJunk(question)) {
                    recovered.add(question);
                }
            }
            for (ContentPackageMaterial material : result.materials()) {
                if (material.getMaterialKey() != null && !material.getMaterialKey().isBlank()
                        && materialKeys.add(material.getMaterialKey())) {
                    materials.add(material);
                }
            }
            log.info("AI 导入任务 {} 视觉补漏第 {} 页：解析 {} 题", jobId, page + 1, result.questions().size());
        } catch (Exception exception) {
            log.warn("AI 导入任务 {} 视觉补漏第 {} 页失败：{}", jobId, page + 1, exception.getMessage());
        }
    }

    private List<ContentPackageQuestion> mergeByQuality(List<ContentPackageQuestion> existing,
                                                         List<ContentPackageQuestion> recovered) {
        Map<String, ContentPackageQuestion> byNormalizedQuestion = new LinkedHashMap<>();
        for (ContentPackageQuestion question : existing) {
            byNormalizedQuestion.put(visionQualityService.normalizeQuestion(question), question);
        }
        for (ContentPackageQuestion question : recovered) {
            String normalized = visionQualityService.normalizeQuestion(question);
            ContentPackageQuestion previous = byNormalizedQuestion.get(normalized);
            if (previous == null || visionQualityService.quality(question) > visionQualityService.quality(previous)) {
                byNormalizedQuestion.put(normalized, question);
            }
        }
        return new ArrayList<>(byNormalizedQuestion.values());
    }

    private int[] lineStarts(String[] lines) {
        int[] starts = new int[lines.length];
        int offset = 0;
        for (int index = 0; index < lines.length; index++) {
            starts[index] = offset;
            offset += lines[index].length() + 1;
        }
        return starts;
    }

    private int[] pageStarts(List<String> pageTexts) {
        int[] starts = new int[pageTexts.size() + 1];
        int offset = 0;
        for (int index = 0; index < pageTexts.size(); index++) {
            starts[index] = offset;
            offset += pageTexts.get(index).length();
        }
        starts[pageTexts.size()] = offset;
        return starts;
    }
}
