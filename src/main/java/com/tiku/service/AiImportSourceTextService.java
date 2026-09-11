package com.tiku.service;

import com.tiku.model.ContentPackageQuestion;
import com.tiku.model.OptionItem;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * AI 导入源文本的定位、题干回填与答案证据校验。
 *
 * <p>该组件不调用模型。它只把模型输出与原始文本进行确定性比对，避免模型输出残片、错配选项或在
 * 非补充模式下捏造答案。</p>
 */
@Slf4j
@Component
public class AiImportSourceTextService {

    private static final Set<String> VALID_TYPES = Set.of("SINGLE", "MULTIPLE", "JUDGE", "SUBJECTIVE");
    private static final Pattern ANSWER_LINE = AiAnswerFormat.ANSWER_LINE;
    private static final Pattern ANSWER_MARKER = Pattern.compile(
            "(?:答案|参考答案|正确答案)\\s*[:：]?\\s*[（(]?\\s*([A-Ha-h]{1,6}|[对错√×])\\s*[）)]?"
                    + "|答\\s*[:：]\\s*[（(]?\\s*([A-Ha-h]{1,6}|[对错√×])\\s*[）)]?"
                    + "|[（(]\\s*([A-Ha-h]{1,6}|[对错√×])\\s*[）)]"
                    + "|【\\s*答案\\s*】\\s*[:：]?\\s*([A-Ha-h]{1,6}|[对错√×])"
                    + "|【\\s*(?:第)?\\s*\\d{1,3}\\s*题\\s*(?:的)?\\s*答案\\s*】\\s*[:：]?\\s*([A-Ha-h]{1,6}|[对错√×])");
    private static final Pattern MATERIAL_STOP = Pattern.compile("^\\d{1,3}\\s*[.．、)）]|^[A-Da-d][.．、]");
    private static final Pattern QUESTION_NUMBER_ALONE = Pattern.compile("^\\d{1,3}\\s*[.．、)）]\\s*$");

    private final AiImportTextStructure textStructure;

    public AiImportSourceTextService(AiImportTextStructure textStructure) {
        this.textStructure = textStructure;
    }

    /** 对模型反序列化后的题目执行基本结构规整。 */
    public boolean validateQuestion(ContentPackageQuestion question) {
        if (question.getContent() == null || question.getContent().isBlank()) {
            return false;
        }
        String type = question.getType() == null ? "" : question.getType().toUpperCase();
        if (!VALID_TYPES.contains(type)) {
            return false;
        }
        question.setType(type);
        if (!"SUBJECTIVE".equals(type)
                && (question.getOptions() == null || question.getOptions().isEmpty())) {
            if ("JUDGE".equals(type)) {
                question.setOptions(List.of(new OptionItem("A", "正确"), new OptionItem("B", "错误")));
            } else {
                question.setType("SUBJECTIVE");
                question.setOptions(List.of());
                question.setAnswerKeys(List.of());
                question.setAnswerText(null);
                question.setAnswerSource(null);
                type = "SUBJECTIVE";
            }
        }
        if (question.getAnswerKeys() == null || question.getAnswerKeys().isEmpty()) {
            question.setAnswerKeys(List.of());
        }
        if ("JUDGE".equals(type) && question.getOptions().size() < 2) {
            question.setOptions(List.of(new OptionItem("A", "正确"), new OptionItem("B", "错误")));
        }
        if (question.getQuestionKey() == null || question.getQuestionKey().isBlank()) {
            question.setQuestionKey("AI_" + UUID.randomUUID().toString().substring(0, 8).toUpperCase());
        }
        if (question.getScore() == null) {
            question.setScore("SUBJECTIVE".equals(type) ? 5.0 : 1.0);
        }
        return true;
    }

    /**
     * 通过题干、选项和题号锚定源文，必要时回填遗漏材料，并拦截明显的跨题错配输出。
     *
     * @return 是否保留该题
     */
    public boolean completeMaterialFromSource(ContentPackageQuestion question, String sourceText) {
        if (sourceText == null || sourceText.isBlank() || question.getContent() == null) {
            return true;
        }
        if (question.getMaterialKey() != null && !question.getMaterialKey().isBlank()) {
            return true;
        }
        String content = textStructure.stripImageRefs(question.getContent()).trim();
        if (content.isEmpty()) {
            return false;
        }
        if (content.matches("^(答案|参考答案|解析)[:：][\\s\\S]*")) {
            log.warn("题干回填丢弃（答案/解析开头残片）：content={}", truncate(content, 50));
            return false;
        }
        String optionProbe = null;
        if (question.getOptions() != null && !question.getOptions().isEmpty()) {
            String optionText = textStructure.stripImageRefs(
                    question.getOptions().get(0).text() == null ? "" : question.getOptions().get(0).text());
            if (optionText != null) {
                optionProbe = optionText.length() > 8 ? optionText.substring(0, 8) : optionText;
            }
        }
        Matcher numberMatcher = Pattern.compile("^(\\d{1,3})\\s*[.．、)）]").matcher(content);
        boolean numberAnchored = numberMatcher.find();
        String[] lines = sourceText.split("\\R", -1);
        int contentLine = locateContentLine(lines, content);
        int anchorLine = -1;
        if (contentLine >= 0) {
            boolean beforeFirstNumber = true;
            for (int lineIndex = 0; lineIndex <= contentLine; lineIndex++) {
                if (textStructure.isQuestionNumberLine(lines[lineIndex].trim())) {
                    beforeFirstNumber = false;
                    break;
                }
            }
            if (beforeFirstNumber && content.length() <= 60) {
                log.warn("题干回填丢弃（标题区残片）：contentLine={} content={}", contentLine, truncate(content, 50));
                return false;
            }
            anchorLine = contentLine;
            if (optionProbe != null && !optionProbe.isBlank()) {
                StringBuilder nearby = new StringBuilder();
                for (int lineIndex = contentLine; lineIndex < Math.min(lines.length, contentLine + 8); lineIndex++) {
                    nearby.append(lines[lineIndex]);
                }
                if (!nearby.toString().contains(optionProbe)) {
                    anchorLine = -1;
                }
            }
        }
        if (anchorLine < 0 && numberAnchored) {
            String numberPattern = "^\\s*" + numberMatcher.group(1) + "\\s*[.．、)）](?:\\s*[A-Da-d]|\\s*$)";
            for (int lineIndex = 0; lineIndex < lines.length; lineIndex++) {
                if (lines[lineIndex].matches(numberPattern)) {
                    anchorLine = lineIndex;
                    break;
                }
            }
        }
        if (anchorLine < 0) {
            if (content.length() <= 30 && !"JUDGE".equals(question.getType())) {
                log.warn("题干回填丢弃（定位失败且题干过短）：content={}", truncate(content, 80));
                return false;
            }
            return true;
        }
        int numberLine = anchorLine;
        if (!QUESTION_NUMBER_ALONE.matcher(lines[anchorLine].trim()).matches()) {
            for (int lineIndex = anchorLine + 1; lineIndex < lines.length; lineIndex++) {
                if (QUESTION_NUMBER_ALONE.matcher(lines[lineIndex].trim()).matches()) {
                    numberLine = lineIndex;
                    break;
                }
            }
        }
        int firstNumberLine = 0;
        for (int lineIndex = 0; lineIndex < lines.length; lineIndex++) {
            if (textStructure.isQuestionNumberLine(lines[lineIndex].trim())) {
                firstNumberLine = lineIndex;
                break;
            }
        }
        boolean shouldCollect = numberAnchored || (contentLine >= 0 && contentLine < numberLine);
        List<String> collected = new ArrayList<>();
        if (shouldCollect) {
            for (int lineIndex = numberLine - 1; lineIndex >= firstNumberLine; lineIndex--) {
                String line = lines[lineIndex].trim();
                if (line.isEmpty()) {
                    continue;
                }
                if (MATERIAL_STOP.matcher(line).find() || line.matches("^(答案|参考答案|解析)[:：].*")) {
                    break;
                }
                collected.add(line);
            }
        }
        if (!collected.isEmpty()) {
            java.util.Collections.reverse(collected);
            String backfill = String.join("\n", collected);
            if (backfill.length() >= 15) {
                question.setContent(backfill);
            }
        }
        return optionsMatchSource(question, lines, contentLine);
    }

    private boolean optionsMatchSource(ContentPackageQuestion question, String[] lines, int contentLine) {
        if ("JUDGE".equals(question.getType()) || question.getOptions() == null || question.getOptions().isEmpty()) {
            return true;
        }
        List<OptionItem> textOptions = new ArrayList<>();
        for (OptionItem option : question.getOptions()) {
            if (option.text() != null && !option.text().isBlank()
                    && !textStructure.stripImageRefs(option.text()).isBlank()) {
                textOptions.add(option);
            }
        }
        if (textOptions.isEmpty()) {
            return true;
        }
        int contentNumber = contentLine >= 0 ? findQuestionNumber(lines, contentLine, true) : -1;
        if (contentNumber > 0) {
            int optionLine = findLineOf(lines, contentNumber);
            if (optionLine >= 0) {
                StringBuilder optionArea = new StringBuilder();
                for (int lineIndex = optionLine; lineIndex < Math.min(lines.length, optionLine + 5); lineIndex++) {
                    optionArea.append(lines[lineIndex]).append('\n');
                }
                String optionText = optionArea.toString();
                for (OptionItem option : textOptions) {
                    String probe = option.text().length() > 6 ? option.text().substring(0, 6) : option.text();
                    if (!optionText.contains(probe)) {
                        log.warn("题干回填丢弃（选项不在本题选项区）：contentNum={} opt={} content={}",
                                contentNumber, probe, truncate(question.getContent(), 60));
                        return false;
                    }
                }
            }
            return true;
        }
        int optionNumber = -1;
        for (OptionItem option : textOptions) {
            String probe = option.text().length() > 6 ? option.text().substring(0, 6) : option.text();
            Pattern optionPattern = Pattern.compile("[A-Da-d][.．、]\\s*" + Pattern.quote(probe));
            int optionLine = -1;
            for (int lineIndex = 0; lineIndex < lines.length; lineIndex++) {
                if (optionPattern.matcher(lines[lineIndex]).find()) {
                    optionLine = lineIndex;
                    break;
                }
            }
            if (optionLine < 0) {
                return false;
            }
            int currentNumber = findQuestionNumber(lines, optionLine, false);
            if (currentNumber < 0) {
                continue;
            }
            if (optionNumber < 0) {
                optionNumber = currentNumber;
            } else if (optionNumber != currentNumber) {
                log.warn("题干回填丢弃（选项跨题）：content={}", truncate(question.getContent(), 80));
                return false;
            }
        }
        return true;
    }

    public int locateContentLine(String[] lines, String content) {
        String firstLine = content.split("\\R", 2)[0].trim();
        if (!firstLine.isEmpty()) {
            for (int lineIndex = 0; lineIndex < lines.length; lineIndex++) {
                if (lines[lineIndex].contains(firstLine)) {
                    return lineIndex;
                }
            }
        }
        StringBuilder normalized = new StringBuilder();
        List<Integer> starts = new ArrayList<>();
        for (String line : lines) {
            starts.add(normalized.length());
            normalized.append(line.replaceAll("\\s+", ""));
        }
        String probe = content.replaceAll("\\s+", "");
        probe = probe.substring(0, Math.min(30, probe.length()));
        if (probe.isEmpty()) {
            return -1;
        }
        int position = normalized.indexOf(probe);
        if (position >= 0) {
            for (int index = starts.size() - 1; index >= 0; index--) {
                if (starts.get(index) <= position) {
                    return index;
                }
            }
        }
        return -1;
    }

    public Set<String> collectSourceAnswerEvidence(String sourceText, ContentPackageQuestion question) {
        Set<String> evidence = new HashSet<>();
        if (sourceText == null || sourceText.isBlank()) {
            return evidence;
        }
        String[] lines = sourceText.split("\\R", -1);
        int questionNumber = question.getQuestionNumber() == null ? -1 : question.getQuestionNumber();
        int contentLine = locateContentLine(lines, textStructure.stripImageRefs(
                question.getContent() == null ? "" : question.getContent()));
        int numberLine = -1;
        if (contentLine >= 0) {
            for (int lineIndex = contentLine; lineIndex < lines.length; lineIndex++) {
                String line = lines[lineIndex].trim();
                if (QUESTION_NUMBER_ALONE.matcher(line).matches()
                        || (line.matches("^\\d{1,3}\\s*[.．、)）].*") && !textStructure.isDecimalLikeLine(line)
                        && !ANSWER_LINE.matcher(line).matches())) {
                    numberLine = lineIndex;
                    break;
                }
            }
            if (numberLine >= 0) {
                int windowEnd = Math.min(lines.length, numberLine + 10);
                for (int lineIndex = numberLine + 1; lineIndex < windowEnd; lineIndex++) {
                    if (textStructure.isQuestionNumberLine(lines[lineIndex].trim())) {
                        windowEnd = lineIndex;
                        break;
                    }
                }
                StringBuilder window = new StringBuilder();
                for (int lineIndex = numberLine; lineIndex < windowEnd; lineIndex++) {
                    window.append(lines[lineIndex]).append('\n');
                }
                collectEvidenceKeys(window.toString(), evidence);
            }
            if (questionNumber <= 0 && numberLine >= 0) {
                Matcher matcher = Pattern.compile("^(\\d{1,3})").matcher(lines[numberLine].trim());
                if (matcher.find()) {
                    questionNumber = Integer.parseInt(matcher.group(1));
                }
            }
        }
        if (questionNumber > 0) {
            for (String line : lines) {
                String letters = AiAnswerFormat.answerFor(line, questionNumber);
                if (letters != null) {
                    addAnswerChars(letters, evidence);
                }
                Map<Integer, String> compactAnswers = AiAnswerFormat.compactAnswerMap(line);
                if (compactAnswers.containsKey(questionNumber)) {
                    addAnswerChars(compactAnswers.get(questionNumber), evidence);
                }
            }
        }
        return evidence;
    }

    public boolean hasSourceAnswerEvidence(String sourceText, ContentPackageQuestion question) {
        if (sourceText == null || sourceText.isBlank()) {
            return true;
        }
        Set<String> evidence = collectSourceAnswerEvidence(sourceText, question);
        if (question.getAnswerKeys() == null || question.getAnswerKeys().isEmpty()) {
            return true;
        }
        if (evidence.isEmpty()) {
            log.warn("答案证据校验：无证据 content={} ans={}", truncate(question.getContent(), 60), question.getAnswerKeys());
            return false;
        }
        for (String answerKey : question.getAnswerKeys()) {
            if (!evidence.contains(answerKey.toUpperCase())) {
                log.warn("答案证据校验：字母不符 evidence={} ans={} content={}", evidence, question.getAnswerKeys(),
                        truncate(question.getContent(), 60));
                return false;
            }
        }
        return true;
    }

    public int findLineOf(String[] lines, int questionNumber) {
        for (int lineIndex = 0; lineIndex < lines.length; lineIndex++) {
            String line = lines[lineIndex].trim();
            if (textStructure.isQuestionNumberLine(line)) {
                Matcher matcher = Pattern.compile("^(\\d{1,3})").matcher(line);
                if (matcher.find() && Integer.parseInt(matcher.group(1)) == questionNumber) {
                    return lineIndex;
                }
            }
        }
        return 0;
    }

    public int findQuestionNumber(String[] lines, int startLine, boolean forward) {
        int step = forward ? 1 : -1;
        int end = forward ? lines.length : -1;
        for (int lineIndex = startLine; lineIndex != end; lineIndex += step) {
            String line = lines[lineIndex].trim();
            String normalized = line.replaceAll("^(\\[图片\\d+])+", "").trim();
            if (normalized.matches("^\\d{1,3}\\s*[.．、)）].*") && !textStructure.isDecimalLikeLine(normalized)
                    && !ANSWER_LINE.matcher(normalized).matches()) {
                Matcher matcher = Pattern.compile("^(\\d{1,3})").matcher(normalized);
                if (matcher.find()) {
                    return Integer.parseInt(matcher.group(1));
                }
            }
        }
        return -1;
    }

    private void collectEvidenceKeys(String window, Set<String> evidence) {
        Matcher multi = Pattern.compile("(?:答案|参考答案|正确答案)\\s*[:：]?\\s*[（(]?\\s*([A-Ha-h]{1,6}|正确|错误)").matcher(window);
        while (multi.find()) {
            addAnswerChars(multi.group(1), evidence);
        }
        Matcher matcher = ANSWER_MARKER.matcher(window);
        while (matcher.find()) {
            for (int group = 1; group <= matcher.groupCount(); group++) {
                if (matcher.group(group) != null) {
                    addAnswerChars(matcher.group(group), evidence);
                }
            }
        }
    }

    private void addAnswerChars(String value, Set<String> evidence) {
        if (value == null) {
            return;
        }
        if ("正确".equals(value) || "对".equals(value) || "√".equals(value)) {
            evidence.add("A");
            return;
        }
        if ("错误".equals(value) || "错".equals(value) || "×".equals(value)) {
            evidence.add("B");
            return;
        }
        for (char character : value.toUpperCase().toCharArray()) {
            String answerKey = String.valueOf(character);
            if (answerKey.matches("[A-H]")) {
                evidence.add(answerKey);
            }
        }
    }

    private String truncate(String value, int maxLength) {
        if (value == null || value.length() <= maxLength) {
            return value;
        }
        return value.substring(0, maxLength) + "…";
    }
}
