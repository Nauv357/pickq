package com.tiku.dto;

import java.util.List;

/**
 * 交卷请求（统一判分模型：做题中不逐题提交，交卷时一次性提交全部最终作答）。
 * body 可缺省（幂等重查报告）；answers 为空数组 = 无作答直接交卷。
 * 每题可携带 seconds（该题累计用时，前端本地统计；null = 服务端按作答时间差兜底推算）。
 */
public record SessionFinishRequest(
        List<AnswerEntry> answers
) {

    public record AnswerEntry(
            Long questionId,
            List<String> selectedKeys,
            String userAnswer,
            Long seconds
    ) {
    }
}
