package com.tiku.service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AiImportJobStorageServiceTest {

    @Test
    void storesSanitizedInputInsideTheTaskDirectory(@TempDir Path tempDir) throws Exception {
        AiImportJobStorageService service = new AiImportJobStorageService(tempDir.toString());
        String safeName = AiImportJobStorageService.sanitizeFileName("..\\题目,一.pdf");
        byte[] content = "pdf-content".getBytes(StandardCharsets.UTF_8);

        service.storeInputFiles(42L, List.of(safeName), List.of(content));

        Path input = service.inputFile(42L, 0, safeName);
        assertTrue(input.startsWith(tempDir.resolve("imports").resolve("42").toAbsolutePath().normalize()));
        assertArrayEquals(content, Files.readAllBytes(input));
        assertFalse(Files.exists(tempDir.resolve("题目_一.pdf")));
        assertThrows(IllegalArgumentException.class,
                () -> service.inputFile(42L, 0, "../escape.pdf"));
    }

    @Test
    void listsReadsAndDeletesTemporaryImages(@TempDir Path tempDir) throws Exception {
        AiImportJobStorageService service = new AiImportJobStorageService(tempDir.toString());
        Path images = service.jobDirectory(7L).resolve("images");
        Files.createDirectories(images);
        Files.writeString(images.resolve("2.jpg"), "second");
        Files.writeString(images.resolve("1.png"), "first");

        List<AiImportJobStorageService.JobImageInfo> imageInfos = service.listImages(7L);

        assertEquals(List.of(1, 2), imageInfos.stream().map(AiImportJobStorageService.JobImageInfo::num).toList());
        assertEquals("png", imageInfos.getFirst().ext());
        assertArrayEquals("second".getBytes(StandardCharsets.UTF_8), service.readImage(7L, 2));
        assertArrayEquals("first".getBytes(StandardCharsets.UTF_8), service.readPngImageIfPresent(7L, 1));
        assertThrows(IllegalArgumentException.class, () -> service.readImage(7L, 0));

        service.deleteJobDirectory(7L);

        assertFalse(Files.exists(service.jobDirectory(7L)));
    }

    @Test
    void findsOnlyExpiredNumericTaskDirectories(@TempDir Path tempDir) throws Exception {
        AiImportJobStorageService service = new AiImportJobStorageService(tempDir.toString());
        Path expired = service.jobDirectory(11L);
        Path current = service.jobDirectory(12L);
        Path invalid = tempDir.resolve("imports").resolve("not-a-job");
        Files.createDirectories(expired);
        Files.createDirectories(current);
        Files.createDirectories(invalid);
        Files.setLastModifiedTime(expired, java.nio.file.attribute.FileTime.from(Instant.parse("2020-01-01T00:00:00Z")));

        assertEquals(List.of(11L), service.findDirectoriesOlderThan(Instant.parse("2021-01-01T00:00:00Z")));
    }

    @Test
    void rejectsInvalidTaskIdentifiers(@TempDir Path tempDir) {
        AiImportJobStorageService service = new AiImportJobStorageService(tempDir.toString());

        assertThrows(IllegalArgumentException.class, () -> service.jobDirectory(0L));
        assertThrows(IllegalArgumentException.class, () -> service.jobDirectory(null));
    }
}
