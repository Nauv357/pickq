package com.tiku.service;

import com.tiku.model.ContentPackageMaterial;
import com.tiku.model.ContentPackageQuestion;
import com.tiku.model.OptionItem;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class AiImportFormulaServiceTest {

    @Test
    void normalizesNativeLatexDelimitersAcrossQuestionsAndMaterials() {
        AiImportFormulaService service = new AiImportFormulaService(null, null);
        ContentPackageQuestion question = new ContentPackageQuestion();
        question.setContent("计算\\(x+1\\)");
        question.setOptions(List.of(new OptionItem("A", "\\[x^2\\]")));
        question.setAnalysis("\\(分析\\)");
        ContentPackageMaterial material = new ContentPackageMaterial();
        material.setContent("材料：\\[a/b\\]");
        AiImportResult result = new AiImportResult(List.of(question), List.of(material));

        service.normalizeResult(result);

        assertEquals("计算$x+1$", question.getContent());
        assertEquals("$$x^2$$", question.getOptions().getFirst().text());
        assertEquals("$分析$", question.getAnalysis());
        assertEquals("材料：$$a/b$$", material.getContent());
    }
}
