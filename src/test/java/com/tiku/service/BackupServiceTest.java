package com.tiku.service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BackupServiceTest {

    @Test
    void prepareRestoreExtractsValidRelativeEntries(@TempDir Path tempDir) throws IOException {
        BackupService service = new BackupService(null, tempDir.toString(), 1024);

        String dataDir = service.prepareRestore(zipOf(
                new ArchiveEntry("database.sql", "CREATE TABLE test(id INT);"),
                new ArchiveEntry("images/1/example.png", "image")));

        assertEquals(tempDir.toString(), dataDir);
        assertTrue(Files.exists(tempDir.resolve("restore/staged/database.sql")));
        assertEquals("image", Files.readString(tempDir.resolve("restore/staged/images/1/example.png")));
    }

    @Test
    void prepareRestoreRejectsTraversalAndCleansStagingDirectory(@TempDir Path tempDir) throws IOException {
        BackupService service = new BackupService(null, tempDir.toString(), 1024);

        assertThrows(IllegalArgumentException.class,
                () -> service.prepareRestore(zipOf(new ArchiveEntry("../outside.txt", "blocked"))));

        assertFalse(Files.exists(tempDir.resolve("restore")));
    }

    @Test
    void prepareRestoreStopsBeforeWritingPastConfiguredLimit(@TempDir Path tempDir) throws IOException {
        BackupService service = new BackupService(null, tempDir.toString(), 16);

        assertThrows(IllegalArgumentException.class,
                () -> service.prepareRestore(zipOf(new ArchiveEntry("database.sql", "0123456789abcdefg"))));

        assertFalse(Files.exists(tempDir.resolve("restore")));
    }

    private static byte[] zipOf(ArchiveEntry... entries) throws IOException {
        try (ByteArrayOutputStream bytes = new ByteArrayOutputStream();
             ZipOutputStream zip = new ZipOutputStream(bytes)) {
            for (ArchiveEntry entry : entries) {
                zip.putNextEntry(new ZipEntry(entry.name()));
                zip.write(entry.content().getBytes(StandardCharsets.UTF_8));
                zip.closeEntry();
            }
            zip.finish();
            return bytes.toByteArray();
        }
    }

    private record ArchiveEntry(String name, String content) {
    }
}
