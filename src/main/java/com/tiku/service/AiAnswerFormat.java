package com.tiku.service;

import java.util.HashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * AI 导入的答案格式识别（共享工具，docx/学科卷泛化）。
 * 兼容公考样式（"1.B"、"答案：B"、"答案：1.A 2.B"）与学科卷样式
 * （"【1题答案】B"、"【第9题答案】BD"、"1-8：B D C C B A B D" 区间式、
 * 主观题分段 "【13题答案】（1）…（2）…"）。
 * AiImportService / MineruParseService 的答案行常量统一引用此处 ANSWER_LINE，
 * 保证"卷末答案剥离 / 题号排除 / 材料检测排除"三处一致。
 */
final class AiAnswerFormat {

    private AiAnswerFormat() {
    }

    /**
     * 答案列表行判定：N.X / N:ABD / N√ / 【N题答案】X / 【第N题答案】任意内容（主观题分段答案）。
     * 注意：【N题答案】行永远不是题目（无论内容是什么），因此括号形式接受任意尾部——
     * 教师版试卷的主观题答案（"【13题答案】（1）…"）也要从材料检测/题号统计中排除。
     */
    static final Pattern ANSWER_LINE = Pattern.compile(
            "^\\s*(?:\\d{1,3}\\s*[.．、:：)）]?\\s*[A-Ha-h√×对错]{1,6}"
                    + "|【\\s*(?:第)?\\s*\\d{1,3}\\s*题\\s*(?:的)?\\s*答案\\s*】\\s*[:：]?.*)"
                    + "\\s*$");

    /** 紧凑答案行："答案：1.B 2.C 3.ABD" / "key: 1.A 2.B" */
    static final Pattern ANSWER_COMPACT = Pattern.compile(
            "^(答案|参考答案|正确答案|key|keys)?\\s*[:：]?\\s*(\\d{1,3}\\s*[.．、:：)）]?\\s*[A-Ha-h√×对错]{1,6}\\s*[,，;；、\\s]+){2,}\\d{1,3}\\s*[.．、:：)）]?\\s*[A-Ha-h√×对错]{1,6}$");

    /** 【N题答案】X（X 为 1-6 位选项字母/对错/√×） */
    static final Pattern BRACKET_ANSWER = Pattern.compile(
            "^\\s*【\\s*(?:第)?\\s*(\\d{1,3})\\s*题\\s*(?:的)?\\s*答案\\s*】\\s*[:：]?\\s*([A-Ha-h√×对错]{1,6})\\s*$");

    /** N.X 点号式答案行 */
    static final Pattern DOTTED_ANSWER = Pattern.compile(
            "^\\s*(\\d{1,3})\\s*[.．、:：)）]\\s*([A-Ha-h√×对错]{1,6})\\s*$");

    /** 区间式答案行："1-8：B D C C B A B D" / "9~10 BD ABD"（字母按题号顺序排列） */
    static final Pattern RANGE_ANSWER = Pattern.compile(
            "^\\s*(\\d{1,3})\\s*[-~—～]\\s*(\\d{1,3})\\s*[:：]?\\s*([A-Ha-h√×对错][A-Ha-h√×对错\\s,，、;；]*)\\s*$");

    /**
     * 从一行答案文本中取出"第 qNum 题"的答案码（可能为多字母，如 "BD"）。
     * 支持：【N题答案】X、N.X、区间式（按位置取字母）。不是答案行或不含该题 → null。
     */
    static String answerFor(String line, int qNum) {
        if (line == null || qNum <= 0) {
            return null;
        }
        String t = line.trim();
        Matcher b = BRACKET_ANSWER.matcher(t);
        if (b.matches() && Integer.parseInt(b.group(1)) == qNum) {
            return b.group(2);
        }
        Matcher d = DOTTED_ANSWER.matcher(t);
        if (d.matches() && Integer.parseInt(d.group(1)) == qNum) {
            return d.group(2);
        }
        Matcher r = RANGE_ANSWER.matcher(t);
        if (r.matches()) {
            int start = Integer.parseInt(r.group(1));
            int end = Integer.parseInt(r.group(2));
            if (qNum >= start && qNum <= end) {
                String[] tokens = r.group(3).trim().split("[\\s,，、;；]+");
                int idx = qNum - start;
                if (idx >= 0 && idx < tokens.length) {
                    return tokens[idx];
                }
            }
        }
        return null;
    }

    /**
     * 取"第 qNum 题"在【N题答案】行中"】"之后的全部内容（主观题分段参考答案，如"（1）B （2）大"）。
     * 空白归一为单空格；找不到返回 null。
     * 多行匹配（如 MinerU 文本 + 本地解析文本拼接）时取第一个有效行；
     * 垃圾防护：纯数字/纯标点（如 "654645"，源于解析层把公式误读成编号）视为无效跳过——
     * 真实参考答案至少含汉字/字母/括号内容。
     */
    static String bracketAnswerTail(String text, int qNum) {
        if (text == null || text.isBlank() || qNum <= 0) {
            return null;
        }
        Pattern p = Pattern.compile("(?m)^\\s*【\\s*(?:第)?\\s*" + qNum
                + "\\s*题\\s*(?:的)?\\s*答案\\s*】\\s*[:：]?\\s*(.*?)\\s*$");
        Matcher m = p.matcher(text);
        while (m.find()) {
            String tail = m.group(1);
            if (tail == null) {
                continue;
            }
            String cleaned = tail.replaceAll("\\s+", " ").trim();
            if (cleaned.isEmpty()) {
                continue;
            }
            //垃圾防护：纯数字/纯标点（公式被误读成编号等解析垃圾）→ 无效，试下一行；
            //含 [图片N] 引用（答案本身是公式/图形图）→ 有效，保留标记（预览可渲染图答案）
            boolean hasImg = cleaned.contains("[图片");
            String probe = cleaned.replaceAll("\\[图片\\d+\\]", "")
                    .replaceAll("[\\d\\s.．、,，;；:：()（）\\-_=+*/<>×÷~～^]+", "");
            if (probe.isEmpty() && !hasImg) {
                continue;
            }
            return cleaned;
        }
        return null;
    }

    /** 判断文本是否像"卷末答案列表"（多文件时识别答案文件；单文件时剥离卷末答案区） */
    static boolean looksLikeAnswerList(String s) {
        if (s == null || s.isBlank() || s.length() > 5000) {
            return false;
        }
        String t = s.trim();
        if (ANSWER_COMPACT.matcher(t).matches()) {
            return true;
        }
        String[] lines = t.split("\\R");
        int nonBlank = 0;
        int matched = 0;
        for (String line : lines) {
            String l = line.trim();
            if (l.isEmpty()) {
                continue;
            }
            nonBlank++;
            if (ANSWER_LINE.matcher(l).matches()
                    || RANGE_ANSWER.matcher(l).matches()
                    || l.matches("^(答案|参考答案|正确答案)[:：]?$")) {
                matched++;
            }
        }
        return nonBlank >= 2 && matched >= 2 && matched * 2 >= nonBlank;
    }

    /**
     * 紧凑答案行内题号映射（"答案：1.A 2.C 3.B" / "参考答案 1.A 2.C" 单行多题）→ {题号: 答案码}。
     * 仅对 ANSWER_COMPACT 匹配的行调用（安全：题干行"2. 光在…"不含连续 "N.X 码" 结构不会匹配）；
     * 子问答案（【12题答案】（1）M…（4）A）所在行含中文不匹配 ANSWER_COMPACT → 不会误入。
     */
    static Map<Integer, String> compactAnswerMap(String line) {
        Map<Integer, String> map = new HashMap<>();
        if (line == null || !ANSWER_COMPACT.matcher(line.trim()).matches()) {
            return map;
        }
        //"1.A" / "2:BD" / "3）C" 码对（前位非数字/分隔符——防止 "13.A" 中的 "3.A" 二次命中）
        Matcher m = Pattern.compile("(?<![\\d.．、:：)）])(\\d{1,3})\\s*[.．、:：)）]?\\s*([A-Ha-h√×对错]{1,6})")
                .matcher(line);
        while (m.find()) {
            map.put(Integer.parseInt(m.group(1)), m.group(2));
        }
        return map;
    }
}
