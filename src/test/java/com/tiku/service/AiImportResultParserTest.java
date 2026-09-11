package com.tiku.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tiku.model.ContentPackageQuestion;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AiImportResultParserTest {

    private final AiImportTextStructure textStructure = new AiImportTextStructure();
    private final AiImportSourceTextService sourceTextService = new AiImportSourceTextService(textStructure);
    private final AiImportResultParser parser = new AiImportResultParser(
            new ObjectMapper(), sourceTextService, textStructure);

    @Test
    void preservesOnlySourceBackedAnswersWhenSupplementIsDisabled() {
        String source = """
                1. 下列哪项正确？
                A. 甲
                B. 乙
                答案：B
                """;
        AiImportResult result = parser.parseJson("""
                {"questions":[{
                  "type":"SINGLE","content":"下列哪项正确？",
                  "options":[{"key":"A","text":"甲"},{"key":"B","text":"乙"}],
                  "answerKeys":["B"],"answerText":"乙","analysis":"模型解析"
                }]}
                """, false, source, false, false);

        ContentPackageQuestion question = result.questions().getFirst();
        assertEquals(List.of("B"), question.getAnswerKeys());
        assertEquals("ORIGINAL", question.getAnswerSource());
        assertNull(question.getAnswerText());
        assertNull(question.getAnalysis());
    }

    @Test
    void removesInventedAnswerWhenSourceDoesNotContainEvidence() {
        String source = """
                1. 下列哪项正确？
                A. 甲
                B. 乙
                """;
        AiImportResult result = parser.parseJson("""
                {"questions":[{
                  "type":"SINGLE","content":"下列哪项正确？",
                  "options":[{"key":"A","text":"甲"},{"key":"B","text":"乙"}],
                  "answerKeys":["A"]
                }]}
                """, false, source, false, false);

        assertTrue(result.questions().getFirst().getAnswerKeys().isEmpty());
        assertNull(result.questions().getFirst().getAnswerSource());
    }

    @Test
    void parsesMarkdownWrappedByJsonOutput() {
        AiImportResult result = parser.parseMarkdown("""
                {"output":"## 第1题 · 判断\\n\\n**题干**：\\n判断题"}
                """);

        assertEquals(1, result.questions().size());
        assertEquals("JUDGE", result.questions().getFirst().getType());
        assertEquals("判断题", result.questions().getFirst().getContent());
    }

    @Test
    void collectsCompactAnswerListEvidence() {
        ContentPackageQuestion question = new ContentPackageQuestion();
        question.setQuestionNumber(2);
        question.setContent("第二题题干");
        question.setAnswerKeys(List.of("C"));

        Set<String> evidence = sourceTextService.collectSourceAnswerEvidence(
                "答案：1.A 2.C 3.B", question);

        assertEquals(Set.of("C"), evidence);
        assertTrue(sourceTextService.hasSourceAnswerEvidence("答案：1.A 2.C 3.B", question));
        assertFalse(sourceTextService.hasSourceAnswerEvidence("答案：1.A 2.B", question));
    }
}
