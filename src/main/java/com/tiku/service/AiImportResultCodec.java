package com.tiku.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tiku.model.ContentPackageMaterial;
import com.tiku.model.ContentPackageQuestion;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * AI 导入结果的持久化编解码器。
 *
 * <p>读取时兼容历史的题目数组格式，以及当前的
 * {@code {"materials": [...], "questions": [...]}} 对象格式。</p>
 */
@Component
public class AiImportResultCodec {

    private final ObjectMapper objectMapper;

    public AiImportResultCodec(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public String serialize(AiImportResult result) {
        try {
            if (result.materials().isEmpty()) {
                return objectMapper.writeValueAsString(result.questions());
            }
            var node = objectMapper.createObjectNode();
            node.set("materials", objectMapper.valueToTree(result.materials()));
            node.set("questions", objectMapper.valueToTree(result.questions()));
            return objectMapper.writeValueAsString(node);
        } catch (IOException e) {
            throw new IllegalStateException("AI 导入结果序列化失败", e);
        }
    }

    public AiImportResult parse(String resultJson) throws IOException {
        JsonNode node = objectMapper.readTree(resultJson);
        List<ContentPackageQuestion> questions = new ArrayList<>();
        List<ContentPackageMaterial> materials = new ArrayList<>();
        if (node.isArray()) {
            appendQuestions(node, questions);
        } else {
            appendMaterials(node.path("materials"), materials);
            appendQuestions(node.path("questions"), questions);
        }
        return new AiImportResult(questions, materials);
    }

    private void appendQuestions(JsonNode node, List<ContentPackageQuestion> questions) {
        if (!node.isArray()) {
            return;
        }
        for (JsonNode item : node) {
            try {
                questions.add(objectMapper.treeToValue(item, ContentPackageQuestion.class));
            } catch (Exception ignored) {
                // 单个坏题不应阻断用户读取其余可恢复的预览结果。
            }
        }
    }

    private void appendMaterials(JsonNode node, List<ContentPackageMaterial> materials) {
        if (!node.isArray()) {
            return;
        }
        for (JsonNode item : node) {
            try {
                materials.add(objectMapper.treeToValue(item, ContentPackageMaterial.class));
            } catch (Exception ignored) {
                // 单个坏材料不应阻断用户读取其余可恢复的预览结果。
            }
        }
    }
}
