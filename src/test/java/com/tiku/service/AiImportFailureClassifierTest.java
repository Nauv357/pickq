package com.tiku.service;

import org.junit.jupiter.api.Test;

import java.net.ConnectException;
import java.net.http.HttpTimeoutException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

class AiImportFailureClassifierTest {

    private final AiImportFailureClassifier classifier = new AiImportFailureClassifier();

    @Test
    void classifiesTimeoutAndRateLimitAsStableCodes() {
        assertEquals("MODEL_TIMEOUT", classifier.classify(new HttpTimeoutException("request timed out")).code());
        assertEquals("MODEL_RATE_LIMITED",
                classifier.classify(new IllegalStateException("模型调用失败 HTTP 429：too many requests")).code());
    }

    @Test
    void classifiesConnectionAndDocumentFailures() {
        assertEquals("MODEL_CONNECTION_FAILED", classifier.classify(new ConnectException("Connection refused")).code());
        assertEquals("DOCUMENT_PARSE_FAILED",
                classifier.classify(new IllegalStateException("PDF 文档解析失败")).code());
    }

    @Test
    void diagnosticSummaryRedactsApiKeyAndKeepsUserMessageSafe() {
        AiImportFailureClassifier.Failure failure = classifier.classify(
                new IllegalStateException("模型调用失败 sk-live-secret-token apiKey=another-secret"));

        assertEquals("IMPORT_PROCESSING_FAILED", failure.code());
        assertEquals("AI 导入处理失败，请重试；若持续失败请检查导入设置", failure.userMessage());
        assertFalse(failure.diagnosticSummary().contains("secret"));
    }
}
