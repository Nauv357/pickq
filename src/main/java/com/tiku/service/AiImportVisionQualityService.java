package com.tiku.service;

import com.tiku.model.ContentPackageQuestion;
import com.tiku.model.OptionItem;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** 视觉模型结果的去重键、残题过滤、图片引用边界与排序规则。 */
@Slf4j
@Component
public class AiImportVisionQualityService {

    private static final Pattern GLUED_QUESTION = Pattern.compile("\\d{1,2}\\s*[.．、]\\s*(?:\\n|$)");
    private static final Pattern TRAILING_QUESTION_NUMBER = Pattern.compile("\\d{1,2}\\s*[.．、]\\s*$");
    private static final Pattern IMAGE_REFERENCE = Pattern.compile("\\[图片(\\d+)]");

    private final AiImportSourceTextService sourceTextService;
    private final AiImportTextStructure textStructure;

    public AiImportVisionQualityService(AiImportSourceTextService sourceTextService,
                                        AiImportTextStructure textStructure) {
        this.sourceTextService = sourceTextService;
        this.textStructure = textStructure;
    }

    public String normalizeQuestion(ContentPackageQuestion question) {
        StringBuilder normalized = new StringBuilder(normalizeForDedup(question.getContent()));
        if (question.getOptions() != null) {
            for (OptionItem option : question.getOptions()) {
                normalized.append('|').append(normalizeForDedup(option.text()));
            }
        }
        return normalized.toString();
    }

    public int quality(ContentPackageQuestion question) {
        int score = question.getOptions() == null ? 0 : question.getOptions().size() * 10;
        if (question.getAnswerKeys() != null && !question.getAnswerKeys().isEmpty()) {
            score += 5;
        }
        return question.getContent() != null && question.getContent().length() > 30 ? score + 3 : score;
    }

    public boolean isJunk(ContentPackageQuestion question) {
        if (hasDuplicatedTextOptions(question)) {
            return true;
        }
        if (!"JUDGE".equals(question.getType()) && !"SUBJECTIVE".equals(question.getType())
                && (question.getOptions() == null || question.getOptions().size() < 3)) {
            log.info("视觉结果过滤（选项不足）：{}", truncate(question.getContent(), 30));
            return true;
        }
        String content = question.getContent() == null ? "" : question.getContent();
        String[] lines = content.split("\\R", -1);
        boolean changed = false;
        for (int index = 0; index < lines.length; index++) {
            if (!TRAILING_QUESTION_NUMBER.matcher(lines[index]).find()) {
                continue;
            }
            if (index < lines.length - 1 && !lines[index + 1].isBlank()) {
                log.info("视觉结果过滤（题干粘连）：{}", truncate(content, 50));
                return true;
            }
            lines[index] = TRAILING_QUESTION_NUMBER.matcher(lines[index]).replaceAll("");
            changed = true;
        }
        if (changed) {
            content = String.join("\n", lines);
            question.setContent(content);
        }
        if (GLUED_QUESTION.matcher(content).find()) {
            log.info("视觉结果过滤（残留粘连）：{}", truncate(content, 50));
            return true;
        }
        return false;
    }

    public void sanitizeImageReferences(List<ContentPackageQuestion> questions, int firstNumber, int lastNumber) {
        if (firstNumber <= 0) {
            return;
        }
        for (ContentPackageQuestion question : questions) {
            question.setContent(stripOutOfRangeReferences(question.getContent(), firstNumber, lastNumber));
            if (question.getOptions() != null) {
                List<OptionItem> options = new ArrayList<>();
                for (OptionItem option : question.getOptions()) {
                    options.add(new OptionItem(option.key(), stripOutOfRangeReferences(option.text(), firstNumber, lastNumber)));
                }
                question.setOptions(options);
            }
            question.setReferenceAnswer(stripOutOfRangeReferences(question.getReferenceAnswer(), firstNumber, lastNumber));
        }
    }

    public List<ContentPackageQuestion> sortBySourceOrder(List<ContentPackageQuestion> questions, String sourceText) {
        if (sourceText == null || sourceText.isBlank()) {
            return questions;
        }
        String[] lines = sourceText.split("\\R", -1);
        List<ContentPackageQuestion> sorted = new ArrayList<>(questions);
        sorted.sort((left, right) -> Integer.compare(positionOf(lines, left), positionOf(lines, right)));
        return sorted;
    }

    private boolean hasDuplicatedTextOptions(ContentPackageQuestion question) {
        if (question.getOptions() == null || question.getOptions().size() < 2) {
            return false;
        }
        String first = question.getOptions().getFirst().text() == null ? "" : question.getOptions().getFirst().text().trim();
        boolean allBlank = first.isEmpty();
        for (OptionItem option : question.getOptions()) {
            String text = option.text() == null ? "" : option.text().trim();
            allBlank &= text.isEmpty();
            if (!text.equals(first)) {
                return false;
            }
        }
        if (!allBlank) {
            log.info("视觉结果过滤（选项全同）：{}", truncate(question.getContent(), 30));
            return true;
        }
        return false;
    }

    private int positionOf(String[] lines, ContentPackageQuestion question) {
        int position = sourceTextService.locateContentLine(lines,
                textStructure.stripImageRefs(question.getContent() == null ? "" : question.getContent()));
        return position < 0 ? Integer.MAX_VALUE : position;
    }

    private String normalizeForDedup(String text) {
        String normalized = text == null ? "" : text.replaceAll("\\[图片(\\d+)\\]", "图$1");
        return normalized.replaceAll("\\s+", "").replace("_", "");
    }

    private String stripOutOfRangeReferences(String text, int firstNumber, int lastNumber) {
        if (text == null) {
            return null;
        }
        Matcher matcher = IMAGE_REFERENCE.matcher(text);
        StringBuffer result = new StringBuffer();
        while (matcher.find()) {
            int number = Integer.parseInt(matcher.group(1));
            matcher.appendReplacement(result, number < firstNumber || number > lastNumber ? "" : matcher.group());
        }
        matcher.appendTail(result);
        return result.toString();
    }

    private String truncate(String value, int maxLength) {
        if (value == null || value.length() <= maxLength) {
            return value;
        }
        return value.substring(0, maxLength) + "…";
    }
}
