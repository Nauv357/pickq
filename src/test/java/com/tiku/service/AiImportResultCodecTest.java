package com.tiku.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tiku.model.ContentPackageMaterial;
import com.tiku.model.ContentPackageQuestion;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AiImportResultCodecTest {

    private final AiImportResultCodec codec = new AiImportResultCodec(new ObjectMapper());

    @Test
    void serializesMaterialsWithQuestionsAndRoundTrips() throws Exception {
        ContentPackageQuestion question = new ContentPackageQuestion();
        question.setType("JUDGE");
        question.setContent("判断题题干");
        ContentPackageMaterial material = new ContentPackageMaterial();
        material.setMaterialKey("m1");
        material.setContent("共享材料");

        String json = codec.serialize(new AiImportResult(List.of(question), List.of(material)));
        AiImportResult restored = codec.parse(json);

        assertTrue(json.startsWith("{"));
        assertEquals("判断题题干", restored.questions().getFirst().getContent());
        assertEquals("m1", restored.materials().getFirst().getMaterialKey());
    }

    @Test
    void parsesHistoricalQuestionArrayAndSkipsMalformedEntries() throws Exception {
        AiImportResult restored = codec.parse("""
                [
                  {"type":"JUDGE","content":"可恢复题目"},
                  "坏题"
                ]
                """);

        assertEquals(1, restored.questions().size());
        assertTrue(restored.materials().isEmpty());
    }
}
