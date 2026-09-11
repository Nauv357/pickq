package com.tiku.service;

import com.tiku.config.AiSettings;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AiImportPromptFactoryTest {

    private final AiImportPromptFactory promptFactory = new AiImportPromptFactory();

    @Test
    void buildsVisionSettingsAndPreservesTheThinkingChoice() {
        AiSettings source = new AiSettings();
        source.setBaseUrl("https://example.test");
        source.setApiKey("key");
        source.setModel("text-model");
        source.setVisionModel("vision-model");
        source.setThinking(true);

        AiSettings vision = promptFactory.buildVisionSettings(source);
        AiSettings withoutThinking = promptFactory.withThinking(source, false);

        assertEquals("vision-model", vision.getModel());
        assertTrue(vision.getThinking());
        assertEquals("text-model", withoutThinking.getModel());
        assertFalse(withoutThinking.getThinking());
    }

    @Test
    void visionPromptKeepsTextLayerAndHidesInternalImageMarkers() {
        String prompt = promptFactory.buildVisionPagePrompt(
                List.of("1. 判断题[图片1]", "2. 第二题[图片2]"), 0, 2, 2, "解析提示");

        assertTrue(prompt.contains("解析提示"));
        assertTrue(prompt.contains("1. 判断题"));
        assertTrue(prompt.contains("2. 第二题"));
        assertFalse(prompt.contains("[图片1]"));
        assertTrue(promptFactory.buildImageRefRuleList(List.of(2, 5)).contains("[图片2]、[图片5]"));
    }

    @Test
    void extractionPromptSeparatesStructureFromAnswerSupplement() {
        String markdown = promptFactory.buildMdExtractPrompt();
        String extractOnly = promptFactory.buildSystemPrompt(false, true);
        String supplemented = promptFactory.buildSystemPrompt(true, false);

        assertTrue(markdown.contains("## 第N题 · 题型"));
        assertTrue(extractOnly.contains("本阶段只整理题目结构"));
        assertTrue(supplemented.contains("AI_SUPPLEMENT"));
    }
}
