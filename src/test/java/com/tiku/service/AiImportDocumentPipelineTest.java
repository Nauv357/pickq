package com.tiku.service;

import com.tiku.model.AiImportJob;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class AiImportDocumentPipelineTest {

    @Test
    void preparesMultipleFilesWithContinuousPageOffsets(@TempDir Path tempDir) throws Exception {
        AiConfigService aiConfigService = mock(AiConfigService.class);
        DocumentParserService parserService = mock(DocumentParserService.class);
        MineruParseService mineruParseService = mock(MineruParseService.class);
        AiImportJobStorageService storageService = new AiImportJobStorageService(tempDir.toString());
        when(aiConfigService.isMineruConfigured()).thenReturn(false);

        AiClientService.ImageData firstImage = new AiClientService.ImageData("image/png", new byte[]{1});
        AiClientService.ImageData secondImage = new AiClientService.ImageData("image/png", new byte[]{2});
        when(parserService.parse(eq("first.pdf"), any(byte[].class))).thenReturn(new DocumentParserService.ParseResult(
                "PDF", plainQuestionText(), List.of(), List.of("第一页"),
                List.of(new DocumentParserService.ExtractedImage(0, 1, 2, 100, firstImage)), null, 1, Set.of(1)));
        when(parserService.parse(eq("second.pdf"), any(byte[].class))).thenReturn(new DocumentParserService.ParseResult(
                "PDF", "第二份题目", List.of(), List.of("第二页"),
                List.of(new DocumentParserService.ExtractedImage(0, 3, 4, 100, secondImage)), "第二份警告", 0, Set.of()));

        AiImportJob job = job(8L, "first.pdf,second.pdf", "AUTO");
        storageService.storeInputFiles(8L, List.of("first.pdf", "second.pdf"), List.of(
                "first".getBytes(StandardCharsets.UTF_8), "second".getBytes(StandardCharsets.UTF_8)));
        List<String> progress = new ArrayList<>();

        AiImportDocumentPipeline pipeline = new AiImportDocumentPipeline(
                aiConfigService, parserService, mineruParseService, storageService);
        AiImportDocumentPipeline.PreparedDocument prepared = pipeline.prepare(job,
                (current, total) -> progress.add(current + "/" + total));

        assertEquals(List.of("first.pdf", "second.pdf"), prepared.fileNames());
        assertEquals(2, prepared.texts().size());
        assertEquals(List.of("1/2", "2/2"), progress);
        assertEquals(List.of(0, 1), prepared.extractedImages().stream()
                .map(DocumentParserService.ExtractedImage::pageNo).toList());
        assertEquals(1, prepared.formulaImageCount());
        assertTrue(prepared.formulaImageNumbers().contains(1));
        assertTrue(prepared.warning().contains("第二份警告"));
        assertFalse(prepared.allPlain());
        assertFalse(prepared.mineruUsed());
    }

    @Test
    void fallsBackToLocalParsingWhenMineruFails(@TempDir Path tempDir) throws Exception {
        AiConfigService aiConfigService = mock(AiConfigService.class);
        DocumentParserService parserService = mock(DocumentParserService.class);
        MineruParseService mineruParseService = mock(MineruParseService.class);
        AiImportJobStorageService storageService = new AiImportJobStorageService(tempDir.toString());
        com.tiku.config.AiSettings settings = new com.tiku.config.AiSettings();
        settings.setMineruKey("mineru-key");
        when(aiConfigService.isMineruConfigured()).thenReturn(true);
        when(aiConfigService.load()).thenReturn(settings);
        when(parserService.parse(eq("scan.pdf"), any(byte[].class))).thenReturn(new DocumentParserService.ParseResult(
                "PDF", "本地回退文本", List.of(), List.of(), List.of(), null, 0, Set.of()));
        when(mineruParseService.isMineruFile("scan.pdf")).thenReturn(true);
        when(mineruParseService.parse(eq("scan.pdf"), any(byte[].class), eq("mineru-key")))
                .thenThrow(new IllegalStateException("服务暂不可用"));

        AiImportJob job = job(9L, "scan.pdf", "MINERU");
        storageService.storeInputFiles(9L, List.of("scan.pdf"), List.of("scan".getBytes(StandardCharsets.UTF_8)));
        AiImportDocumentPipeline pipeline = new AiImportDocumentPipeline(
                aiConfigService, parserService, mineruParseService, storageService);

        AiImportDocumentPipeline.PreparedDocument prepared = pipeline.prepare(job, (current, total) -> { });

        assertEquals(List.of("本地回退文本"), prepared.texts());
        assertTrue(prepared.warning().contains("MinerU 解析不可用"));
        assertFalse(prepared.mineruUsed());
        assertFalse(prepared.allPlain());
    }

    private AiImportJob job(Long id, String fileNames, String engine) {
        AiImportJob job = new AiImportJob();
        job.setId(id);
        job.setFileNames(fileNames);
        job.setEngine(engine);
        return job;
    }

    private String plainQuestionText() {
        return "一、判断题\n1. " + "甲".repeat(40) + "\n2. " + "乙".repeat(40) + "\n3. " + "丙".repeat(40);
    }
}
