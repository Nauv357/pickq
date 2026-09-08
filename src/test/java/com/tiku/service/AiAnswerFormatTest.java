package com.tiku.service;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 答案格式识别（学科卷泛化）：公考样式 + 学科卷样式混合覆盖。
 */
class AiAnswerFormatTest {

    @Test
    void answerLineMatchesAllStyles() {
        String[] yes = {
                "1.B", "12. C", "3:ABD", "5√", "6×", "7对", "8错",
                "【1题答案】B", "【9题答案】BD", "【第10题答案】C",
                "【13题答案】（1）；（2）", "【15题答案】（1）kL2·t，kL2", "【 1题 答案 】 B"
        };
        for (String s : yes) {
            assertTrue(AiAnswerFormat.ANSWER_LINE.matcher(s.trim()).matches(), "应识别为答案行：" + s);
        }
        String[] no = {
                "1. 大连相干光源是我国第一台…", "15.8%", "10.2万吨", "一、选择题：本题共8小题",
                "A. 1种B. 2种", "（1）为测量玻璃的折射率"
        };
        for (String s : no) {
            assertFalse(AiAnswerFormat.ANSWER_LINE.matcher(s.trim()).matches(), "不应识别为答案行：" + s);
        }
    }

    @Test
    void answerForBracketAndDotted() {
        assertEquals("B", AiAnswerFormat.answerFor("【1题答案】B", 1));
        assertEquals("BD", AiAnswerFormat.answerFor("【9题答案】BD", 9));
        assertEquals("C", AiAnswerFormat.answerFor("【第10题答案】C", 10));
        assertNull(AiAnswerFormat.answerFor("【1题答案】B", 2), "题号不符应返回 null");
        assertEquals("ABD", AiAnswerFormat.answerFor("3. ABD", 3));
        assertEquals("C", AiAnswerFormat.answerFor("2. C", 2));
    }

    @Test
    void answerForRange() {
        assertEquals("B", AiAnswerFormat.answerFor("1-8：B D C C B A B D", 1));
        assertEquals("C", AiAnswerFormat.answerFor("1-8：B D C C B A B D", 3));
        assertEquals("D", AiAnswerFormat.answerFor("1-8：B D C C B A B D", 8));
        assertEquals("BD", AiAnswerFormat.answerFor("9~10 BD ABD", 9));
        assertEquals("ABD", AiAnswerFormat.answerFor("9~10 BD ABD", 10));
        assertNull(AiAnswerFormat.answerFor("1-8：B D C C B A B D", 9), "区间外应返回 null");
    }

    @Test
    void bracketAnswerTailForSubjective() {
        assertEquals("（1）B （2）大", AiAnswerFormat.bracketAnswerTail(
                "参考答案\n【11题答案】（1）B    （2）大", 11));
        assertEquals("（1）kL2·t，kL2，从a流向b；（2）", AiAnswerFormat.bracketAnswerTail(
                "【15题答案】（1）kL2·t，kL2，从a流向b；（2）", 15));
        assertNull(AiAnswerFormat.bracketAnswerTail("【11题答案】（1）B", 12));
    }

    @Test
    void looksLikeAnswerList() {
        assertTrue(AiAnswerFormat.looksLikeAnswerList("参考答案\n【1题答案】B\n【2题答案】D\n【3题答案】C"));
        assertTrue(AiAnswerFormat.looksLikeAnswerList("1.B\n2.C\n3.ABD\n4.D"));
        assertTrue(AiAnswerFormat.looksLikeAnswerList("答案：1.A 2.B 3.C 4.D"));
        assertFalse(AiAnswerFormat.looksLikeAnswerList("1. 大连相干光源是我国第一台高增益自由电子激光用户装置\n2. 某同学参加户外拓展活动"));
        assertFalse(AiAnswerFormat.looksLikeAnswerList("一、选择题：本题共8小题\n二、选择题：本题共2小题"));
    }
}
