package com.tiku.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tiku.model.ContentPackageQuestion;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * 固定模型响应夹具：验证导入结果的确定性处理，无需联网或调用真实模型。
 */
class AiImportContractFixtureTest {

    private final AiImportTextStructure structure = new AiImportTextStructure();
    private final AiImportSourceTextService sourceText = new AiImportSourceTextService(structure);
    private final AiImportResultParser parser = new AiImportResultParser(new ObjectMapper(), sourceText, structure);
    private final AiImportVisionQualityService visionQuality = new AiImportVisionQualityService(sourceText, structure);

    @Test
    void textResponseKeepsOnlySourceBackedAnswerAndRemovesSupplementalFields() throws IOException {
        String source = """
                1. 下列关于重构的说法正确的是？
                A. 只改变代码格式
                B. 在保持外部行为的前提下改善内部结构
                答案：B
                2. 单元测试应可离线重复执行。
                答案：A
                """;

        AiImportResult result = parser.parseJson(fixture("text-response.json"), false, source, false, false);

        assertEquals(2, result.questions().size());
        ContentPackageQuestion first = result.questions().getFirst();
        assertEquals(List.of("B"), first.getAnswerKeys());
        assertEquals("ORIGINAL", first.getAnswerSource());
        assertNull(first.getAnswerText());
        assertNull(first.getAnalysis());
    }

    @Test
    void singleVisionResponseRetainsOnlyImagesAssignedToCurrentCall() throws IOException {
        AiImportResult result = parser.parseJson(fixture("vision-single-response.json"), true, null, false, true);

        visionQuality.sanitizeImageReferences(result.questions(), 3, 4);

        ContentPackageQuestion question = result.questions().getFirst();
        assertEquals("观察下图 [图片3]，选择正确的规律。", question.getContent());
        assertEquals("选项乙 [图片4]", question.getOptions().get(1).text());
        assertEquals("选项丁 ", question.getOptions().get(3).text());
    }

    @Test
    void pagedVisionResponseIsReturnedInSourceOrder() throws IOException {
        AiImportResult result = parser.parseJson(fixture("vision-pages-response.json"), true, null, false, true);
        List<ContentPackageQuestion> sorted = visionQuality.sortBySourceOrder(result.questions(), """
                1. 第一题的题干内容。
                A. 甲
                B. 乙
                C. 丙
                2. 第二题的题干内容。
                A. 甲
                B. 乙
                C. 丙
                """);

        assertEquals(List.of(1, 2), sorted.stream().map(ContentPackageQuestion::getQuestionNumber).toList());
    }

    @Test
    void mineruFallbackResponsePreservesMaterialReferenceWithoutRemoteModel() throws IOException {
        AiImportResult result = parser.parseJson(fixture("mineru-fallback-response.json"), true,
                "本地回退后的结构化文本", true, true);

        assertEquals(1, result.materials().size());
        assertEquals("MAT_001", result.questions().getFirst().getMaterialKey());
        assertFalse(result.questions().getFirst().getContent().isBlank());
    }

    private static String fixture(String name) throws IOException {
        ClassPathResource resource = new ClassPathResource("ai-import-contract/" + name);
        try (java.io.InputStream input = resource.getInputStream()) {
            return new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
