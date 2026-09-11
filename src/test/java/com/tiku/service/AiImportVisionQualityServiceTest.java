package com.tiku.service;

import com.tiku.model.ContentPackageQuestion;
import com.tiku.model.OptionItem;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AiImportVisionQualityServiceTest {

    private final AiImportTextStructure textStructure = new AiImportTextStructure();
    private final AiImportVisionQualityService service = new AiImportVisionQualityService(
            new AiImportSourceTextService(textStructure), textStructure);

    @Test
    void keepsImageOptionShellButFiltersRepeatedTextOptions() {
        ContentPackageQuestion imageQuestion = question("图形规律", List.of(
                new OptionItem("A", ""), new OptionItem("B", ""), new OptionItem("C", ""), new OptionItem("D", "")));
        ContentPackageQuestion repeatedOptions = question("文字题", List.of(
                new OptionItem("A", "相同"), new OptionItem("B", "相同"), new OptionItem("C", "相同")));

        assertFalse(service.isJunk(imageQuestion));
        assertTrue(service.isJunk(repeatedOptions));
    }

    @Test
    void removesOnlyOutOfRangeImageReferences() {
        ContentPackageQuestion question = question("题干 [图片1] [图片3]", List.of(new OptionItem("A", "[图片2]")));

        service.sanitizeImageReferences(List.of(question), 2, 2);

        assertEquals("题干  ", question.getContent());
        assertEquals("[图片2]", question.getOptions().getFirst().text());
    }

    private ContentPackageQuestion question(String content, List<OptionItem> options) {
        ContentPackageQuestion question = new ContentPackageQuestion();
        question.setType("SINGLE");
        question.setContent(content);
        question.setOptions(options);
        return question;
    }
}
