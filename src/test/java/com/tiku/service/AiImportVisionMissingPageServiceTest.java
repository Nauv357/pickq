package com.tiku.service;

import com.tiku.model.ContentPackageQuestion;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertSame;

class AiImportVisionMissingPageServiceTest {

    @Test
    void skipsAiCallWhenEveryPageHasExpectedQuestionCount() {
        AiImportTextStructure structure = new AiImportTextStructure();
        AiImportSourceTextService sourceText = new AiImportSourceTextService(structure);
        AiImportVisionQualityService quality = new AiImportVisionQualityService(sourceText, structure);
        AiImportVisionMissingPageService service = new AiImportVisionMissingPageService(
                null, null, null, structure, sourceText, quality);
        String source = """
                一、选择题
                1. 第一题题干
                2. 第二题题干
                """;
        List<ContentPackageQuestion> questions = List.of(question("第一题题干"), question("第二题题干"));

        List<ContentPackageQuestion> result = service.fillMissingPages(questions, List.of(), Set.of(), List.of(source),
                List.of(), List.of(), source, "", null, "", false, 1L, () -> false);

        assertSame(questions, result);
    }

    private ContentPackageQuestion question(String content) {
        ContentPackageQuestion question = new ContentPackageQuestion();
        question.setType("SINGLE");
        question.setContent(content);
        return question;
    }
}
