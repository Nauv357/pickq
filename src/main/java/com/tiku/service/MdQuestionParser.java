package com.tiku.service;

import com.tiku.model.ContentPackageQuestion;
import com.tiku.model.OptionItem;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 大模型 Markdown 输出 → 题目结构（确定性解析，替代分块路径的 JSON 解析）。
 *
 * 约定模板（系统提示中强制）：
 * <pre>
 * ## 第N题 · 单选
 * **题干**：
 * （题干全文：可多行，公式/图片标记/子题原样）
 * **选项**：
 * - A. 选项内容
 * - B. 选项内容
 * **答案**：B
 * **解析**：...
 * **参考答案**：...（主观题）
 * </pre>
 *
 * 对模板漂移的宽容处理：
 * - 字段标记可省略 **（"题干：" 亦可）、字段内容可同行；
 * - 选项行接受 "- A."/"A."/"A、"；同行多选项（"A. 1种 B. 2种"）自动拆分；
 * - 标题可省略题型 → 有选项默认单选，无选项默认主观；
 * - 无任何"## 第N题"标题 → 整篇按单题主观题兜底（预览可改）。
 * 题号直接取自标题（免去源文定位回填）；材料块不由模型输出（本地检测负责），遇到则忽略。
 */
final class MdQuestionParser {

    private MdQuestionParser() {
    }

    /** 题块标题：## 第N题 / ## 第N题 · 单选 / ## 第N题 单选 / ### 12. 多选题 */
    private static final Pattern HEADING = Pattern.compile(
            "^\\s*#{1,6}\\s*第?\\s*(\\d{1,3})\\s*题\\s*(?:[·.．、:：\\-—\\s]\\s*(.{1,20}))?\\s*$");

    /** 材料块标题：## 材料 m1 / ## 材料一（材料由模型声明，替代本地启发式检测——本地检测会把题干碎片误判为材料） */
    private static final Pattern MATERIAL_HEADING = Pattern.compile(
            "^\\s*#{1,6}\\s*材料\\s*([mM]?\\d+|[一二三四五六七八九十]+)\\s*[:：]?\\s*$");

    /** 字段标记行：**题干** / 题干： / **选项**：…（组1=字段名，组2=同行剩余内容，可为空） */
    private static final Pattern FIELD = Pattern.compile(
            "^\\s*\\*{0,2}\\s*(题干|选项|答案|解析|参考答案)\\s*\\*{0,2}\\s*[:：]?\\s*(.*)$");

    /** 独立选项行：- A. x / A. x / A、x / A）x */
    private static final Pattern OPTION_LINE = Pattern.compile(
            "^\\s*[-*]?\\s*([A-Ha-h])\\s*[.．、)）]\\s*(.*)$");

    /** 同行多选项拆分点（前瞻保留分隔）："A. 1种 B. 2种 C. 3种" → 按 B./C. 切 */
    private static final Pattern INLINE_OPTION = Pattern.compile("(?=[A-Da-d]\\s*[.．、)）]\\s*)");

    private record HeadingInfo(int line, int number, String typeText) {
    }

    private record Block(int line, Integer number, String typeText, String materialKey) {
    }

    /** 解析结果：题目 + 共享材料（材料块由模型在 MD 中声明） */
    record ParseOutcome(List<ContentPackageQuestion> questions,
                        List<com.tiku.model.ContentPackageMaterial> materials) {
    }

    /** 解析整篇 MD → 题目列表（坏块丢弃，不抛异常）；材料块忽略 */
    static List<ContentPackageQuestion> parse(String md) {
        return parseWithMaterials(md).questions();
    }

    /** 解析整篇 MD → 题目 + 材料（材料块之后、下一个材料块之前的题目自动关联该材料） */
    static ParseOutcome parseWithMaterials(String md) {
        List<ContentPackageQuestion> questions = new ArrayList<>();
        List<com.tiku.model.ContentPackageMaterial> materials = new ArrayList<>();
        if (md == null || md.isBlank()) {
            return new ParseOutcome(questions, materials);
        }
        String[] lines = md.split("\\R", -1);
        List<Block> blocks = new ArrayList<>();
        int materialIdx = 0;
        for (int i = 0; i < lines.length; i++) {
            String t = lines[i].trim();
            Matcher qm = HEADING.matcher(t);
            if (qm.matches()) {
                blocks.add(new Block(i, Integer.parseInt(qm.group(1)),
                        qm.group(2) == null ? "" : qm.group(2).trim(), null));
                continue;
            }
            Matcher mm = MATERIAL_HEADING.matcher(t);
            if (mm.matches()) {
                materialIdx++;
                blocks.add(new Block(i, null, null, "m" + materialIdx));
            }
        }
        if (blocks.isEmpty()) {
            //无标题兜底：整篇按单题主观题（题干去掉残留字段标记），预览人工修正
            String content = md
                    .replaceAll("(?m)^\\s*\\*{0,2}\\s*(题干|选项|答案|解析|参考答案)\\s*\\*{0,2}\\s*[:：]?\\s*", "")
                    .trim();
            if (!content.isBlank()) {
                ContentPackageQuestion q = new ContentPackageQuestion();
                q.setType("SUBJECTIVE");
                q.setContent(content);
                questions.add(q);
            }
            return new ParseOutcome(questions, materials);
        }
        String currentMaterial = null;
        //标题前的前导内容兜底：模型偶发省略首个题块标题（实测判断推理 Q1/Q2 输出无标题被丢弃）——
        //按无题号块解析，题号由后端 content 定位回填
        if (blocks.get(0).line > 0) {
            ContentPackageQuestion lead = parseBlock(lines, 0, blocks.get(0).line, new HeadingInfo(-1, -1, ""));
            if (lead != null && lead.getContent() != null && !lead.getContent().isBlank()) {
                questions.add(lead);
            }
        }
        for (int b = 0; b < blocks.size(); b++) {
            Block blk = blocks.get(b);
            int start = blk.line + 1;
            int end = b + 1 < blocks.size() ? blocks.get(b + 1).line : lines.length;
            if (blk.materialKey != null) {
                StringBuilder sb = new StringBuilder();
                for (int i = start; i < end; i++) {
                    sb.append(lines[i]).append('\n');
                }
                String content = sb.toString().trim();
                if (!content.isBlank()) {
                    com.tiku.model.ContentPackageMaterial m = new com.tiku.model.ContentPackageMaterial();
                    m.setMaterialKey(blk.materialKey);
                    m.setContent(content);
                    materials.add(m);
                    currentMaterial = blk.materialKey;
                }
            } else {
                ContentPackageQuestion q = parseBlock(lines, start, end,
                        new HeadingInfo(blk.line, blk.number, blk.typeText));
                if (q != null && q.getContent() != null && !q.getContent().isBlank()) {
                    if (currentMaterial != null && (q.getMaterialKey() == null || q.getMaterialKey().isBlank())) {
                        q.setMaterialKey(currentMaterial);
                    }
                    questions.add(q);
                }
            }
        }
        return new ParseOutcome(questions, materials);
    }

    private static ContentPackageQuestion parseBlock(String[] lines, int start, int end, HeadingInfo hi) {
        StringBuilder stem = new StringBuilder();
        //标题漂移防护：题型位置被写成题干首行（"## 第1题 大连相干光源…"）时，把该文本还给题干
        if (hi.typeText != null && !hi.typeText.isBlank()
                && inferType(hi.typeText, List.of()) == null && hi.typeText.length() > 12) {
            stem.append(hi.typeText).append('\n');
        }
        List<OptionItem> options = new ArrayList<>();
        StringBuilder answer = new StringBuilder();
        StringBuilder analysis = new StringBuilder();
        StringBuilder refAnswer = new StringBuilder();
        String section = "stem";
        for (int i = start; i < end; i++) {
            String t = lines[i].trim();
            if (t.isEmpty()) {
                if ("stem".equals(section)) {
                    stem.append('\n');
                }
                continue;
            }
            Matcher fm = FIELD.matcher(t);
            if (fm.matches() && fm.group(1) != null) {
                String name = fm.group(1);
                String inline = fm.group(2) == null ? "" : fm.group(2).trim();
                switch (name) {
                    case "题干" -> {
                        section = "stem";
                        if (!inline.isEmpty()) {
                            stem.append(inline);
                        }
                        stem.append('\n');
                    }
                    case "选项" -> {
                        section = "options";
                        if (!inline.isEmpty()) {
                            addOptionLine(options, inline);
                        }
                    }
                    case "答案" -> {
                        section = "answer";
                        if (!inline.isEmpty()) {
                            answer.append(inline);
                        }
                    }
                    case "解析" -> {
                        section = "analysis";
                        if (!inline.isEmpty()) {
                            analysis.append(inline);
                        }
                    }
                    case "参考答案" -> {
                        section = "ref";
                        if (!inline.isEmpty()) {
                            refAnswer.append(inline);
                        }
                    }
                    default -> {
                        //理论不可达
                    }
                }
                continue;
            }
            switch (section) {
                case "stem" -> {
                    //无"选项："标记时的宽容兜底：出现 ≥2 个选项行且题干已非空 → 视为选项区开始。
                    //实验题防护：题干含填空指示（"填正确答案标号"/填空线）时 A/B/C 是填空标号，不切选项
                    boolean fillIndicators = stem.toString().matches(
                            "(?s).*(填正确答案标号|_{3,}|（填).*");
                    if (!fillIndicators && options.isEmpty() && stem.toString().trim().length() > 0
                            && looksLikeOptionLine(t) && hasMultipleOptionsAhead(lines, i, end)) {
                        section = "options";
                        addOptionLine(options, t);
                    } else {
                        stem.append(t).append('\n');
                    }
                }
                case "options" -> {
                    if (looksLikeOptionLine(t)) {
                        addOptionLine(options, t);
                    } else {
                        //选项换行续行：追加到最后一个选项
                        if (!options.isEmpty()) {
                            int last = options.size() - 1;
                            options.set(last, new OptionItem(options.get(last).key(),
                                    (options.get(last).text() == null ? "" : options.get(last).text()) + t));
                        }
                    }
                }
                case "answer" -> answer.append(t);
                case "analysis" -> analysis.append(t).append('\n');
                case "ref" -> refAnswer.append(t).append('\n');
                default -> {
                }
            }
        }
        String stemText = stem.toString().trim();
        if (stemText.isEmpty()) {
            return null;
        }
        ContentPackageQuestion q = new ContentPackageQuestion();
        if (hi.number > 0) {
            q.setQuestionNumber(hi.number);
        }
        q.setType(inferType(hi.typeText, options));
        q.setContent(stemText);
        q.setOptions(options);
        q.setAnswerKeys(extractAnswerKeys(answer.toString()));
        String an = analysis.toString().trim();
        if (!an.isEmpty()) {
            q.setAnalysis(an);
        }
        String ra = refAnswer.toString().trim();
        if (!ra.isEmpty()) {
            q.setReferenceAnswer(ra);
        }
        return q;
    }

    /** 从答案区提取答案码（B / BD / 对 → A 判断映射由后续 validate/postProcess 处理） */
    private static List<String> extractAnswerKeys(String answerText) {
        List<String> keys = new ArrayList<>();
        Matcher m = Pattern.compile("[A-Ha-h]").matcher(answerText);
        while (m.find()) {
            keys.add(m.group().toUpperCase());
        }
        return keys;
    }

    private static boolean looksLikeOptionLine(String t) {
        return OPTION_LINE.matcher(t).matches();
    }

    /** 其后（到块尾或字段标记）是否还有至少一个选项行——避免把题干中"（1）A…"式文本误判为选项 */
    private static boolean hasMultipleOptionsAhead(String[] lines, int from, int end) {
        int count = 0;
        for (int i = from; i < end; i++) {
            String t = lines[i].trim();
            if (t.isEmpty()) {
                continue;
            }
            if (FIELD.matcher(t).matches()) {
                break;
            }
            if (looksLikeOptionLine(t)) {
                count++;
                if (count >= 2) {
                    return true;
                }
            }
        }
        return false;
    }

    /** 添加一行选项；同行多选项（"A. 1种 B. 2种"）自动拆分 */
    private static void addOptionLine(List<OptionItem> options, String line) {
        Matcher om = OPTION_LINE.matcher(line.trim());
        if (!om.matches()) {
            return;
        }
        String key = om.group(1).toUpperCase();
        String rest = om.group(2) == null ? "" : om.group(2);
        String[] parts = INLINE_OPTION.split(rest);
        options.add(new OptionItem(key, parts.length > 0 ? parts[0].trim() : ""));
        for (int i = 1; i < parts.length; i++) {
            Matcher sm = OPTION_LINE.matcher(parts[i]);
            if (sm.matches()) {
                options.add(new OptionItem(sm.group(1).toUpperCase(), sm.group(2) == null ? "" : sm.group(2).trim()));
            }
        }
    }

    /** 标题题型文案 → 枚举；无法识别返回 null（调用方按选项数兜底） */
    private static String inferType(String typeText, List<OptionItem> options) {
        String t = typeText == null ? "" : typeText;
        if (t.contains("单")) {
            return "SINGLE";
        }
        if (t.contains("多")) {
            return "MULTIPLE";
        }
        if (t.contains("判断") || t.contains("对错")) {
            return "JUDGE";
        }
        if (t.contains("主观") || t.contains("填空") || t.contains("简答") || t.contains("解答")
                || t.contains("计算") || t.contains("实验") || t.contains("问答")) {
            return "SUBJECTIVE";
        }
        return options.size() >= 2 ? "SINGLE" : "SUBJECTIVE";
    }
}
