package com.tiku.service;

import com.tiku.model.AiImportJob;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.function.BiConsumer;
import java.util.regex.Pattern;

/**
 * AI 导入的文档准备流水线。
 *
 * <p>负责读取任务暂存文件、本地解析、显式 MinerU 回退，以及跨文件的页号与图片编号对齐。
 * 它不负责任务状态持久化或 AI 调用；进度由调用方通过回调映射到具体的任务事件。</p>
 */
@Slf4j
@Service
public class AiImportDocumentPipeline {

    private static final Pattern ANSWER_LINE = AiAnswerFormat.ANSWER_LINE;
    private static final Pattern QUESTION_NUMBER_ALONE = Pattern.compile("^\\d{1,3}\\s*[.．、)）]\\s*$");

    private final AiConfigService aiConfigService;
    private final DocumentParserService documentParserService;
    private final MineruParseService mineruParseService;
    private final AiImportJobStorageService jobStorageService;

    public AiImportDocumentPipeline(AiConfigService aiConfigService,
                                    DocumentParserService documentParserService,
                                    MineruParseService mineruParseService,
                                    AiImportJobStorageService jobStorageService) {
        this.aiConfigService = aiConfigService;
        this.documentParserService = documentParserService;
        this.mineruParseService = mineruParseService;
        this.jobStorageService = jobStorageService;
    }

    /**
     * 准备导入源数据。回调参数依次为当前完成文件数和总文件数。
     */
    public PreparedDocument prepare(AiImportJob job, BiConsumer<Integer, Integer> progressListener) throws IOException {
        if (job == null || job.getId() == null || job.getFileNames() == null || job.getFileNames().isBlank()) {
            throw new IllegalArgumentException("AI 导入任务缺少文件信息");
        }
        String[] fileNames = job.getFileNames().split(",");
        boolean mineruConfigured = aiConfigService.isMineruConfigured();
        String mineruKey = mineruConfigured ? aiConfigService.load().getMineruKey() : null;
        boolean mineruEngine = "MINERU".equals(normalizeEngine(job.getEngine()));
        boolean mineruUsed = false;
        boolean allPlain = true;
        int globalPageOffset = 0;
        int formulaImageCount = 0;

        List<String> texts = new ArrayList<>();
        List<String> localTexts = new ArrayList<>();
        List<AiClientService.ImageData> images = new ArrayList<>();
        List<DocumentParserService.ExtractedImage> extractedImages = new ArrayList<>();
        List<String> pageTexts = new ArrayList<>();
        Set<Integer> formulaImageNumbers = new java.util.HashSet<>();
        StringBuilder warnings = new StringBuilder();

        for (int index = 0; index < fileNames.length; index++) {
            String fileName = fileNames[index];
            byte[] fileBytes = java.nio.file.Files.readAllBytes(jobStorageService.inputFile(job.getId(), index, fileName));
            DocumentParserService.ParseResult localResult = documentParserService.parse(fileName, fileBytes);
            if (localResult.text() != null && !localResult.text().isBlank()) {
                localTexts.add(localResult.text());
            }

            DocumentParserService.ParseResult parsedResult = localResult;
            if (mineruEngine && mineruConfigured && mineruParseService.isMineruFile(fileName)) {
                try {
                    parsedResult = mineruParseService.parse(fileName, fileBytes, mineruKey);
                    mineruUsed = true;
                } catch (Exception e) {
                    log.warn("AI 导入任务 {} 文件 {} 走 MinerU 解析失败，回退本地解析：{}",
                            job.getId(), fileName, e.getMessage());
                    warnings.append("MinerU 解析不可用（").append(e.getMessage())
                            .append("），该文件已回退本地解析 ");
                }
            }

            allPlain &= !mineruEngine && isPlainTextCandidate(localResult);
            appendText(parsedResult, texts);
            appendImages(parsedResult, images, extractedImages, globalPageOffset);
            appendPageTexts(parsedResult, pageTexts);
            globalPageOffset += parsedResult.pageTexts() == null ? 0 : parsedResult.pageTexts().size();
            formulaImageCount += parsedResult.formulaImageCount();
            if (parsedResult.formulaImageNos() != null) {
                formulaImageNumbers.addAll(parsedResult.formulaImageNos());
            }
            if (parsedResult.warning() != null) {
                warnings.append(parsedResult.warning()).append(' ');
            }
            progressListener.accept(index + 1, fileNames.length);
        }

        String localEvidence = mineruUsed && !localTexts.isEmpty()
                ? String.join("\n\n========== 下一份文件 ==========\n\n", localTexts)
                : null;
        return new PreparedDocument(
                List.of(fileNames), List.copyOf(texts), List.copyOf(images), List.copyOf(extractedImages),
                List.copyOf(pageTexts), warnings.toString().trim(), mineruUsed, allPlain,
                localEvidence, formulaImageCount, Set.copyOf(formulaImageNumbers));
    }

    private void appendText(DocumentParserService.ParseResult result, List<String> texts) {
        if (result.text() != null && !result.text().isBlank()) {
            texts.add(result.text());
        }
    }

    private void appendImages(DocumentParserService.ParseResult result, List<AiClientService.ImageData> images,
                              List<DocumentParserService.ExtractedImage> extractedImages, int globalPageOffset) {
        if (result.images() != null) {
            images.addAll(result.images());
        }
        if (result.extractedImages() == null) {
            return;
        }
        for (DocumentParserService.ExtractedImage image : result.extractedImages()) {
            extractedImages.add(new DocumentParserService.ExtractedImage(
                    image.pageNo() + globalPageOffset, image.sortX(), image.sortY(), image.pageHeight(), image.image()));
        }
    }

    private void appendPageTexts(DocumentParserService.ParseResult result, List<String> pageTexts) {
        if (result.pageTexts() != null) {
            pageTexts.addAll(result.pageTexts());
        }
    }

    private boolean isPlainTextCandidate(DocumentParserService.ParseResult result) {
        if (result == null || result.text() == null || result.text().isBlank()) {
            return false;
        }
        if (result.extractedImages() != null && !result.extractedImages().isEmpty()) {
            return false;
        }
        int pages = Math.max(1, result.pageTexts() == null ? 1 : result.pageTexts().size());
        return result.text().length() >= pages * 100L && looseQuestionCount(result.text()) >= 3;
    }

    private int looseQuestionCount(String text) {
        String[] lines = text.split("\\R");
        int count = 0;
        for (int index = firstSectionHeaderLine(lines); index < lines.length; index++) {
            String line = lines[index].trim();
            if (line.isEmpty()) {
                continue;
            }
            if (isQuestionNumberLine(line)) {
                count++;
            } else if (!line.matches("^\\d{1,3}\\s*[.．、)）]\\s*$")
                    && line.matches(".*\\d{1,2}\\s*[.．、)]\\s*$")
                    && !ANSWER_LINE.matcher(line).matches()) {
                count++;
            }
        }
        return count;
    }

    private int firstSectionHeaderLine(String[] lines) {
        for (int index = 0; index < lines.length; index++) {
            if (lines[index].trim().matches("^[一二三四五六七八九十]+[、.．]\\s*.*")) {
                return index;
            }
        }
        return 0;
    }

    private boolean isQuestionNumberLine(String line) {
        if (QUESTION_NUMBER_ALONE.matcher(line).matches()) {
            return true;
        }
        String normalized = line.replaceAll("^(\\[图片\\d+])+", "").trim();
        return !normalized.isEmpty()
                && normalized.matches("^\\d{1,3}\\s*[.．、)）].*")
                && !isDecimalLikeLine(normalized)
                && !ANSWER_LINE.matcher(normalized).matches();
    }

    private boolean isDecimalLikeLine(String line) {
        if (line.matches("^\\d{1,3}\\.\\d{4}(\\s*[-—~～]\\s*\\d{4})?\\s*年.*")) {
            return false;
        }
        return line.matches("^\\d{1,3}\\.\\d.*");
    }

    private String normalizeEngine(String engine) {
        return engine == null || engine.isBlank() ? "AUTO" : engine;
    }

    /** 文档准备阶段的不可变输出。 */
    public record PreparedDocument(
            List<String> fileNames,
            List<String> texts,
            List<AiClientService.ImageData> images,
            List<DocumentParserService.ExtractedImage> extractedImages,
            List<String> pageTexts,
            String warning,
            boolean mineruUsed,
            boolean allPlain,
            String localEvidence,
            int formulaImageCount,
            Set<Integer> formulaImageNumbers) {
    }
}
