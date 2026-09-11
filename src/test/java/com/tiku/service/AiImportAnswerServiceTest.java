package com.tiku.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tiku.config.AiSettings;
import com.tiku.model.ContentPackageQuestion;
import com.tiku.model.OptionItem;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class AiImportAnswerServiceTest {

    @Test
    void restoresOriginalAnswerWithoutCallingAiWhenSupplementIsDisabled() {
        AiImportTextStructure structure = new AiImportTextStructure();
        AiImportSourceTextService sourceText = new AiImportSourceTextService(structure);
        AiImportAnswerService service = new AiImportAnswerService(null, null, new ObjectMapper(), sourceText, null, null);
        ContentPackageQuestion question = new ContentPackageQuestion();
        question.setQuestionNumber(1);
        question.setType("SINGLE");
        question.setContent("下列哪项正确？");
        question.setOptions(List.of(new OptionItem("A", "甲"), new OptionItem("B", "乙")));
        question.setAnswerKeys(List.of("A"));
        question.setAnalysis("模型内容");
        AiImportResult result = new AiImportResult(List.of(question), List.of());
        String source = """
                1. 下列哪项正确？
                A. 甲
                B. 乙
                答案：B
                """;

        service.postProcess(result, false, source, source, new AiSettings(), 1L, List.of());

        assertEquals(List.of("B"), question.getAnswerKeys());
        assertEquals("ORIGINAL", question.getAnswerSource());
        assertNull(question.getAnalysis());
    }
}
