package com.tiku.service;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * AI 导入链路的通用文本小工具。
 *
 * 这些方法原先散在 {@link AiImportService} 里，被「编排」与「PDF 版面算法」两侧共用；
 * 版面算法搬到 {@link AiImportVisionLayoutService} 后，为避免两边各留一份实现，集中到这里。
 * 只放**纯函数**：不依赖任何注入服务、不产生副作用、可单独测试。
 */
final class AiImportTexts {

    /** 题目文本中的图片标记：[图片N]（N 为会话内临时编号） */
    static final Pattern IMAGE_REF = Pattern.compile("\\[图片(\\d+)]");

    private AiImportTexts() {
    }

    /** 二分页偏移表：字符位置所在页（最后一个 start <= pos 的索引） */
    static int pageIndexOf(int[] pageStarts, int pos) {
        int lo = 0, hi = pageStarts.length - 1;
        while (lo < hi) {
            int mid = (lo + hi + 1) >>> 1;
            if (pageStarts[mid] <= pos) {
                lo = mid;
            } else {
                hi = mid - 1;
            }
        }
        return lo;
    }

    /** 剥离 AI 输出中的图片编号标记（[图片N]）——文本定位/去重时用（标记不在源文文本中） */
    static String stripImageRefs(String text) {
        return text == null ? null : IMAGE_REF.matcher(text).replaceAll(" ");
    }

    static String truncate(String s, int max) {
        if (s == null) return null;
        return s.length() <= max ? s : s.substring(0, max);
    }
}
