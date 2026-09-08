package com.tiku.model.enums;

import com.baomidou.mybatisplus.annotation.EnumValue;

public enum QuestionType {
    SINGLE("单选题"),
    MULTIPLE("多选题"),
    JUDGE("判断题"),
    SUBJECTIVE("主观题");

    private final String label;

    QuestionType(String label) {
        this.label = label;
    }

    public String getLabel() {
        return label;
    }

}
