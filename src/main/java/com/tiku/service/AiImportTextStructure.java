package com.tiku.service;

import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** AI 导入文本的题号识别、分块与图片标记分析。 */
@Component
public class AiImportTextStructure {

    private static final int MAX_CHUNKS = 6;
    private static final int MAX_CHUNK_IMAGES = 10;
    private static final Pattern QUESTION_START = Pattern.compile("(?m)^\\s*(\\d{1,3})\\s*[.．、)）]");
    private static final Pattern QUESTION_NUMBER_ALONE = Pattern.compile("^\\d{1,3}\\s*[.．、)）]\\s*$");
    private static final Pattern IMAGE_REFERENCE = Pattern.compile("\\[图片(\\d+)]");

    public int looseQuestionCount(String text) {
        if (text == null || text.isBlank()) {
            return 0;
        }
        Pattern trailingNumber = Pattern.compile("\\d{1,2}\\s*[.．、)]\\s*$");
        String[] lines = text.split("\\R");
        int count = 0;
        for (int index = findFirstSectionHeaderLine(lines); index < lines.length; index++) {
            String line = lines[index].trim();
            if (line.isEmpty()) {
                continue;
            }
            if (isQuestionNumberLine(line)) {
                count++;
            } else if (!line.matches("^\\d{1,3}\\s*[.．、)）]\\s*$")
                    && trailingNumber.matcher(line).find()
                    && !AiAnswerFormat.ANSWER_LINE.matcher(line).matches()) {
                count++;
            }
        }
        return count;
    }

    public int referenceQuestionCount(String text) {
        return Math.max(looseQuestionCount(text), text == null || text.isBlank() ? 0 : detectQuestionBoundaries(text).size());
    }

    public boolean isQuestionNumberLine(String line) {
        if (QUESTION_NUMBER_ALONE.matcher(line).matches()) {
            return true;
        }
        String normalized = line.replaceAll("^(\\[图片\\d+])+", "").trim();
        return !normalized.isEmpty()
                && normalized.matches("^\\d{1,3}\\s*[.．、)）].*")
                && !isDecimalLikeLine(normalized)
                && !AiAnswerFormat.ANSWER_LINE.matcher(normalized).matches();
    }

    public boolean isDecimalLikeLine(String line) {
        if (line.matches("^\\d{1,3}\\.\\d{4}(\\s*[-—~～]\\s*\\d{4})?\\s*年.*")) {
            return false;
        }
        return line.matches("^\\d{1,3}\\.\\d.*");
    }

    public List<Integer> detectQuestionBoundaries(String text) {
        List<int[]> candidates = new ArrayList<>();
        Matcher matcher = QUESTION_START.matcher(text);
        while (matcher.find()) {
            int lineEnd = text.indexOf('\n', matcher.start());
            String line = text.substring(matcher.start(), lineEnd < 0 ? text.length() : lineEnd).trim();
            if (!AiAnswerFormat.ANSWER_LINE.matcher(line).matches()) {
                candidates.add(new int[]{Integer.parseInt(matcher.group(1)), matcher.start()});
            }
        }
        if (candidates.size() < 3) {
            return List.of();
        }
        List<int[]> longestRun = List.of();
        for (int start = 0; start < candidates.size(); start++) {
            List<int[]> run = new ArrayList<>();
            int expected = candidates.get(start)[0];
            for (int index = start; index < candidates.size(); index++) {
                int number = candidates.get(index)[0];
                if (number == expected) {
                    run.add(candidates.get(index));
                    expected++;
                } else if (number > expected) {
                    expected = number + 1;
                    run.add(candidates.get(index));
                }
            }
            if (run.size() > longestRun.size()) {
                longestRun = run;
            }
        }
        if (longestRun.size() < 3) {
            return List.of();
        }
        return longestRun.stream().map(candidate -> candidate[1]).toList();
    }

    public List<int[]> splitChunks(String text, int chunkTarget) {
        List<Integer> boundaries = detectQuestionBoundaries(text);
        if (boundaries.size() < 3) {
            return List.of(new int[]{0, text.length()});
        }
        int chunkCount = Math.min(MAX_CHUNKS, Math.max(1, (boundaries.size() + chunkTarget - 1) / chunkTarget));
        int perChunk = (boundaries.size() + chunkCount - 1) / chunkCount;
        List<int[]> ranges = new ArrayList<>();
        for (int chunk = 0; chunk < chunkCount; chunk++) {
            int startIndex = chunk * perChunk;
            int endIndex = Math.min(boundaries.size(), (chunk + 1) * perChunk);
            int start = startIndex == 0 ? 0 : boundaries.get(startIndex - 1);
            int end = endIndex >= boundaries.size() ? text.length() : boundaries.get(endIndex);
            ranges.add(new int[]{start, end});
        }
        return ranges;
    }

    public String trimOrphans(String chunk, boolean trimHead, boolean trimTail) {
        String[] lines = chunk.split("\\R", -1);
        int start = 0;
        int end = lines.length;
        if (trimHead) {
            boolean seenNumber = false;
            while (start < end) {
                String line = lines[start].trim();
                if (line.isEmpty()) {
                    start++;
                } else if (isQuestionNumberLine(line) && !seenNumber) {
                    seenNumber = true;
                    start++;
                } else if (seenNumber && (line.matches("^[A-Da-d][.．、].*")
                        || line.matches("^(答案|参考答案|解析)[:：]?.*"))) {
                    start++;
                } else {
                    break;
                }
            }
        }
        if (trimTail) {
            while (end > start) {
                String line = lines[end - 1].trim();
                if (line.isEmpty()) {
                    end--;
                } else if (line.matches("^[A-Da-d][.．、].*")
                        || QUESTION_NUMBER_ALONE.matcher(line).matches()
                        || line.matches("^\\d{1,3}\\s*[.．、)）].*")
                        || line.matches("^(答案|参考答案|解析)[:：]?.*")
                        || line.contains("[图片") && line.endsWith("]")) {
                    break;
                } else {
                    end--;
                }
            }
        }
        return String.join("\n", java.util.Arrays.copyOfRange(lines, start, end));
    }

    public int answerSectionStartOffset(String tailCandidate) {
        if (tailCandidate == null || tailCandidate.isBlank()) {
            return -1;
        }
        Matcher matcher = Pattern.compile("(?m)^.*$").matcher(tailCandidate);
        while (matcher.find()) {
            String line = matcher.group().trim();
            if (AiAnswerFormat.ANSWER_LINE.matcher(line).matches()
                    || AiAnswerFormat.RANGE_ANSWER.matcher(line).matches()
                    || line.matches("^(答案|参考答案|正确答案)[:：]?$")) {
                return matcher.start();
            }
        }
        return -1;
    }

    public List<int[]> splitRangesByImageQuota(String text, List<int[]> ranges) {
        List<int[]> result = new ArrayList<>();
        int budget = MAX_CHUNKS - ranges.size();
        for (int[] range : ranges) {
            if (budget <= 0 || imageChunkNumbers(text.substring(range[0], range[1])).size() <= MAX_CHUNK_IMAGES) {
                result.add(range);
                continue;
            }
            String segment = text.substring(range[0], range[1]);
            List<Integer> innerBoundaries = detectQuestionBoundaries(segment).stream().filter(boundary -> boundary > 0).toList();
            if (innerBoundaries.isEmpty()) {
                result.add(range);
                continue;
            }
            List<Integer> cuts = new ArrayList<>();
            int lastCut = 0;
            Set<Integer> accumulatedImages = new HashSet<>();
            for (int boundary : innerBoundaries) {
                accumulatedImages.addAll(imageChunkNumbers(segment.substring(lastCut, boundary)));
                if (accumulatedImages.size() > MAX_CHUNK_IMAGES && boundary > lastCut) {
                    cuts.add(boundary);
                    lastCut = boundary;
                    accumulatedImages.clear();
                }
            }
            if (cuts.isEmpty()) {
                result.add(range);
                continue;
            }
            int previous = 0;
            for (int cut : cuts) {
                if (budget-- <= 0) {
                    break;
                }
                result.add(new int[]{range[0] + previous, range[0] + cut});
                previous = cut;
            }
            result.add(new int[]{range[0] + previous, range[1]});
        }
        return result;
    }

    public List<Integer> imageChunkNumbers(String text) {
        if (text == null) {
            return List.of();
        }
        List<Integer> numbers = new ArrayList<>();
        Matcher matcher = IMAGE_REFERENCE.matcher(text);
        while (matcher.find()) {
            int number = Integer.parseInt(matcher.group(1));
            if (!numbers.contains(number)) {
                numbers.add(number);
            }
        }
        return numbers;
    }

    public String stripImageRefs(String text) {
        //实现集中在 AiImportTexts（主服务编排与版面算法两侧共用同一份，避免两处实现漂移）
        return AiImportTexts.stripImageRefs(text);
    }

    public int pageIndexOf(int[] pageStarts, int position) {
        if (pageStarts == null || pageStarts.length < 2) {
            return 0;
        }
        for (int index = 0; index < pageStarts.length - 1; index++) {
            if (position < pageStarts[index + 1]) {
                return index;
            }
        }
        return pageStarts.length - 2;
    }

    /** 返回第一个“ 一、选择题 ”等章节标题所在行；找不到时返回首行。 */
    public int firstSectionHeaderLine(String[] lines) {
        return findFirstSectionHeaderLine(lines);
    }

    private int findFirstSectionHeaderLine(String[] lines) {
        for (int index = 0; index < lines.length; index++) {
            if (lines[index].trim().matches("^[一二三四五六七八九十]+[、.．]\\s*.*")) {
                return index;
            }
        }
        return 0;
    }
}
