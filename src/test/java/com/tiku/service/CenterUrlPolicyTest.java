package com.tiku.service;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class CenterUrlPolicyTest {

    private final CenterUrlPolicy policy = new CenterUrlPolicy();

    @Test
    void normalizesCenterBaseAndPreservesConfiguredPath() {
        assertEquals("https://example.test/center", policy.centerBase(" https://example.test/center/// "));
        assertEquals("https://pickq.cn", policy.centerBase(null));
    }

    @Test
    void rejectsUnsafeOrAmbiguousCenterBase() {
        assertThrows(IllegalArgumentException.class, () -> policy.centerBase("ftp://example.test"));
        assertThrows(IllegalArgumentException.class, () -> policy.centerBase("https://token@example.test"));
        assertThrows(IllegalArgumentException.class, () -> policy.centerBase("https://example.test/?page=1"));
        assertThrows(IllegalArgumentException.class, () -> policy.centerBase("https:example.test"));
    }

    @Test
    void permitsSignedExternalDownloadUrlButRejectsCredentials() {
        assertEquals("https://download.example.test/package.tiku?signature=abc",
                policy.externalDownloadUrl("https://download.example.test/package.tiku?signature=abc"));
        assertEquals("", policy.externalDownloadUrl(" "));
        assertThrows(IllegalArgumentException.class,
                () -> policy.externalDownloadUrl("https://token@download.example.test/package.tiku"));
    }
}
