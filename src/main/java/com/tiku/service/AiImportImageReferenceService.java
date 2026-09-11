package com.tiku.service;

import com.tiku.model.ContentPackageMaterial;
import com.tiku.model.ContentPackageQuestion;
import com.tiku.model.OptionItem;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** AI 临时图片编号到正式题库图片引用的落盘与替换。 */
@Slf4j
@Component
public class AiImportImageReferenceService {

    private static final Pattern IMAGE_REFERENCE = Pattern.compile("\\[图片(\\d+)]");

    private final AiImportJobStorageService jobStorageService;
    private final ImageStorageService imageStorageService;

    public AiImportImageReferenceService(AiImportJobStorageService jobStorageService,
                                         ImageStorageService imageStorageService) {
        this.jobStorageService = jobStorageService;
        this.imageStorageService = imageStorageService;
    }

    public Map<String, String> importReferencedImages(Long jobId, Long bankId, AiImportResult result) {
        Set<String> referenced = collectReferences(result);
        Map<String, String> imported = new HashMap<>();
        for (String number : referenced) {
            try {
                byte[] image = jobStorageService.readPngImageIfPresent(jobId, Integer.parseInt(number));
                if (image != null) {
                    imported.put(number, imageStorageService.importImage(bankId, image));
                }
            } catch (IOException exception) {
                log.warn("AI 导入任务 {} 图片 {} 落盘失败：{}", jobId, number, exception.getMessage());
            }
        }
        return imported;
    }

    public String replaceReferences(String text, Map<String, String> importedReferences) {
        if (text == null || importedReferences.isEmpty()) {
            return text;
        }
        Matcher matcher = IMAGE_REFERENCE.matcher(text);
        StringBuffer result = new StringBuffer();
        while (matcher.find()) {
            String imageName = importedReferences.get(matcher.group(1));
            if (imageName != null) {
                matcher.appendReplacement(result, Matcher.quoteReplacement("[图片:" + imageName + "]"));
            }
        }
        matcher.appendTail(result);
        return result.toString();
    }

    private Set<String> collectReferences(AiImportResult result) {
        Set<String> references = new LinkedHashSet<>();
        for (ContentPackageQuestion question : result.questions()) {
            collect(question.getContent(), references);
            collect(question.getReferenceAnswer(), references);
            if (question.getOptions() != null) {
                for (OptionItem option : question.getOptions()) {
                    collect(option.text(), references);
                }
            }
        }
        for (ContentPackageMaterial material : result.materials()) {
            collect(material.getContent(), references);
        }
        return references;
    }

    private void collect(String text, Set<String> references) {
        if (text == null) {
            return;
        }
        Matcher matcher = IMAGE_REFERENCE.matcher(text);
        while (matcher.find()) {
            references.add(matcher.group(1));
        }
    }
}
