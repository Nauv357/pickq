package com.tiku.dto;

import java.util.List;

public record AnswerResultResponse(
        boolean correct,
        List<String> correctKeys,
        String correctText,
        String analysis
){
}
