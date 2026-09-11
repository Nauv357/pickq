package com.tiku.service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Base64;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ImageStorageServiceTest {

    @Test
    void saveBase64KeepsImagesInsideTheirBankDirectory(@TempDir Path tempDir) {
        ImageStorageService service = new ImageStorageService(tempDir.toString());
        byte[] image = "test-image".getBytes(StandardCharsets.UTF_8);

        var saved = service.saveBase64(7L, Map.of(
                "260911/example.png", Base64.getEncoder().encodeToString(image),
                "../outside.png", Base64.getEncoder().encodeToString(image)));

        assertTrue(saved.contains("260911/example.png"));
        assertFalse(saved.contains("../outside.png"));
        assertArrayEquals(image, service.read(7L, "260911/example.png"));
        assertFalse(java.nio.file.Files.exists(tempDir.resolve("images").resolve("outside.png")));
    }

    @Test
    void readRejectsTraversalAndMalformedReferences(@TempDir Path tempDir) {
        ImageStorageService service = new ImageStorageService(tempDir.toString());

        assertThrows(IllegalArgumentException.class, () -> service.read(7L, "../outside.png"));
        assertThrows(IllegalArgumentException.class, () -> service.read(7L, "260911/../outside.png"));
        assertThrows(IllegalArgumentException.class, () -> service.read(7L, "260911\\outside.png"));
        assertThrows(IllegalArgumentException.class, () -> service.read(7L, ".../outside.png"));
        assertThrows(IllegalArgumentException.class, () -> service.read(0L, "260911/example.png"));
    }
}
