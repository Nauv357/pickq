package com.tiku.service;

import com.tiku.config.AiSettings;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * AI 导入的提示词与模型配置工厂。
 *
 * <p>提示词属于模型交互契约，不应与任务生命周期、文件处理和题目归并逻辑混在同一个服务中。</p>
 */
@Component
public class AiImportPromptFactory {

    /** 多模态模型配置（visionModel 缺省时回退到主模型）。 */
    public AiSettings buildVisionSettings(AiSettings settings) {
        AiSettings vision = new AiSettings();
        vision.setBaseUrl(settings.getBaseUrl());
        vision.setApiKey(settings.getApiKey());
        vision.setModel(settings.getVisionModel() != null && !settings.getVisionModel().isBlank()
                ? settings.getVisionModel() : settings.getModel());
        vision.setThinking(settings.getThinking());
        return vision;
    }

    /** 复制连接配置并覆盖思考模式，供失败重试时切换模型行为。 */
    public AiSettings withThinking(AiSettings base, boolean thinking) {
        AiSettings settings = new AiSettings();
        settings.setBaseUrl(base.getBaseUrl());
        settings.setApiKey(base.getApiKey());
        settings.setModel(base.getModel());
        settings.setVisionModel(base.getVisionModel());
        settings.setThinking(thinking);
        return settings;
    }

    /** 视觉单次路径的系统提示。 */
    public String buildVisionSingleSystemPrompt(boolean aiSupplement) {
        String subjectiveRule = aiSupplement
                ? "主观题（无选项的作答类题目）输出 type=\"SUBJECTIVE\"，referenceAnswer 写参考答案（原文没有时由你生成）。"
                : "主观题（无选项的作答类题目）输出 type=\"SUBJECTIVE\"，referenceAnswer 写原文提供的参考答案；原文没有则留空，不要自行编写。";
        return """
                你是题库整理助手。把试卷整理为标准题目，严格输出一个 JSON 对象：{"questions":[...]}。
                每题字段：type("SINGLE"/"MULTIPLE"/"JUDGE"/"SUBJECTIVE")、content(题干)、
                options([{"key":"A","text":"..."}])、answerKeys(数组)、referenceAnswer(主观题可选)。
                规则：
                1. 题号位置与题目边界以页面截图的视觉排版为准（文本层的行顺序可能与视觉布局不一致，如题号出现在行尾）。
                2. 题干与选项文字以文本层为准，逐字保留，不要改写；图片内容不要转述，图片位置由系统自动处理，你无需标注。
                   例外：图片内容是数学公式/化学式时，直接转写为 LaTeX（$...$，如 $F=ma$）写进对应文本位置，不要跳过公式。
                3. 题干是纯图片（无文字）的题（如"从所给的四个选项中…"图形推理）：content 写文本层中的引导语（如"从所给的四个选项中，选择最合适的一个填入问号处，使之呈现一定的规律性："），
                   选项文字层有内容就写内容（如"A.①②⑥，③④⑤"），没有内容就写 "A."、"B."、"C."、"D."。
                4. 每道题都必须输出，包括图形推理题，禁止遗漏、禁止合并相邻题。
                5. 原文没有答案的题 answerKeys 返回空数组 []，不要编造。
                6. 共享材料题（多题共用大题干，如"材料一/资料分析"）输出顶层 "materials": [{"materialKey":"m1","content":"材料全文"}]，
                   题目加 "materialKey":"m1" 引用，content 只写问题部分；没有共享材料不要输出 materials。
                7. """ + subjectiveRule + """
                8. 只输出 JSON，不要任何其他文字。
                """;
    }

    /** 视觉单次路径的用户提示：整页截图按页序配合文本层。 */
    public String buildVisionSingleUserPrompt(List<String> pageTexts, int pages, String warning) {
        StringBuilder prompt = new StringBuilder();
        if (warning != null && !warning.isBlank()) {
            prompt.append("注意：").append(warning).append('\n');
        }
        prompt.append("以下是试卷的页面截图（按页顺序）与每页文本层。截图用于判断题号位置、题目边界、图形归属与跨页情况；")
                .append("文字一律以文本层为准（不要从截图重新识别文字）。\n\n");
        for (int page = 0; page < pages; page++) {
            prompt.append("【第 ").append(page + 1).append(" 页文本层】\n")
                    .append(stripImageRefs(pageTexts.get(page))).append('\n');
        }
        return prompt.toString();
    }

    /** 构造视觉分页路径的页面文本提示。 */
    public String buildVisionPagePrompt(List<String> pageTexts, int start, int end, int totalPages, String warning) {
        StringBuilder prompt = new StringBuilder();
        if (warning != null && !warning.isBlank()) {
            prompt.append("注意：").append(warning).append('\n');
        }
        prompt.append("以下是试卷第 ").append(start + 1).append('-').append(end)
                .append(" 页（共 ").append(totalPages).append(" 页）的内容。\n\n");
        prompt.append("【文本层】（PDF 精确提取，文字准确，请逐字采用；但行顺序可能与视觉布局不一致：")
                .append("题号有时出现在行尾（如“…规律性：2.”里的 2 其实是第 2 题的题号，排版上位于题干左侧）")
                .append("——请以截图为准归位）\n");
        for (int page = start; page < end; page++) {
            prompt.append("【第 ").append(page + 1).append(" 页】\n")
                    .append(stripImageRefs(pageTexts.get(page))).append('\n');
        }
        prompt.append("\n【页面截图】已随本消息按页顺序提供（第 ").append(start + 1).append('-').append(end)
                .append(" 页截图）：截图仅用于判断题号位置、题目边界、图形/选项的视觉归属与跨页情况；")
                .append("**不要从截图重新识别文字**，题干与选项文字一律以文本层为准（避免 OCR 误差）；")
                .append("插图/图形的位置以截图为准。\n");
        prompt.append("本部分开头/结尾不完整的题目（题干或选项超出本部分范围）跳过不输出，不要补写；")
                .append("完整题目必须全部输出，禁止遗漏。\n");
        return prompt.toString();
    }

    /** 视觉分页路径的插图引用规则。 */
    public String buildVisionImageRefRule(int first, int last) {
        int count = last - first + 1;
        return """

                【图片引用规则】本部分共 %d 张插图，编号为 [图片%d]~[图片%d]（已随本消息按编号顺序提供，编号即图片顺序）。
                插图的视觉位置请对照页面截图判断（截图中的图形即插图所在位置）。
                当题干、选项或共享材料是图片（或含图片）时，必须在对应文本位置写入 [图片N] 标记：
                - 题干有图：content = 完整题干文字 + [图片N]；题干只有图没有文字时，content 只写 [图片N]
                - 选项是图：该选项 text 只写 [图片N]；文字与图混合：文字 + [图片N]
                - 共享材料有图：material.content 中写 [图片N]
                禁止编造编号：只引用实际提供的 [图片%d]~[图片%d]；无法确定图片归属时宁可少引用；
                每个选项通常对应不同的图片，禁止把多个选项写成同一个编号。
                公式图（内容是数学公式/化学式的 [图片N]）禁止引用：题干与选项中的公式一律转写为 $...$ LaTeX 文本，
                禁止把公式图编号写进 content 或选项 text；[图片N] 只用于照片、几何图形、曲线图等非公式内容。
                """.formatted(count, first, last, first, last);
    }

    /** PDF 直传路径的图片引用规则。 */
    public String buildPdfVisionImageRule(int firstPage, int lastPage, int pageImageCount, int first, int last) {
        int count = last - first + 1;
        return """

                【图片引用规则】本部分随消息提供整页截图 %d 张（第 %d~%d 页，位于消息图片最前，用于判断版式与图形归属），
                以及内嵌图 %d 张（编号 [图片%d]~[图片%d]，紧随截图之后、按编号顺序提供，编号即顺序）。
                图形与图片归属（重要，逐题核对，禁止错配）：
                - 每题若有图形/照片/图表：对照整页截图判断该图属于哪道题，在对应位置引用正确的 [图片N]：
                  题干有图 → content = 完整题干文字 + [图片N]（题干末尾）；
                  选项是图 → 该选项 text 写 [图片N]（文字与图混合则文字 + [图片N]）；
                  选项区每个选项通常对应不同图，禁止多个选项引用同一编号。
                - 题干文字几乎相同的相邻图形题（如"从所给的四个选项中…"系列）：以截图中的图形为准区分，
                  每题的图必须引用自己对应的编号，禁止把上一题的图配给下一题。
                - 公式图（内嵌图内容是数学公式/化学式的）禁止引用编号，一律转写为 $...$ LaTeX 文本。
                - 正文文字以文本层为准，不要转写截图中的正文文字；文本层断档处的公式/横线例外（见页面截图说明）。
                - 禁止编造编号：只引用实际提供的 [图片%d]~[图片%d]；无法确定归属时宁可不引用（未配图题预览页会提示，可人工补图）。
                """.formatted(pageImageCount, firstPage, lastPage, count, first, last, first, last);
    }

    /** 普通多模态路径的连续图片引用规则。 */
    public String buildImageRefRule(int first, int last) {
        int count = last - first + 1;
        return """

                【图片引用规则】本部分共 %d 张图片，编号为 [图片%d]~[图片%d]（已随本消息按编号顺序提供，编号即图片顺序）。
                文本中可能已在图片所在位置插入了 [图片N] 标记（有标记则据此判断图片归属）；没有标记时按编号顺序对照文档判断。
                当题干、选项或共享材料是图片（或含图片）时，必须在对应文本位置写入 [图片N] 标记：
                - 题干有图：content = 完整题干文字 + [图片N]；题干只有图没有文字时，content 只写 [图片N]
                - 选项是图：该选项 text 只写 [图片N]；文字与图混合：文字 + [图片N]
                - 共享材料有图：material.content 中写 [图片N]
                禁止编造编号：只引用实际提供的 [图片%d]~[图片%d]；无法确定图片归属时宁可少引用；
                每个选项通常对应不同的图片，禁止把多个选项写成同一个编号。
                公式图（内容是数学公式/化学式的 [图片N]）禁止引用：题干与选项中的公式一律转写为 $...$ LaTeX 文本，
                禁止把公式图编号写进 content 或选项 text；[图片N] 只用于照片、几何图形、曲线图等非公式内容。
                """.formatted(count, first, last, first, last);
    }

    /** 标记路径的非连续图片引用规则。 */
    public String buildImageRefRuleList(List<Integer> numbers) {
        String list = numbers.stream().map(number -> "[图片" + number + "]")
                .collect(java.util.stream.Collectors.joining("、"));
        return """

                【图片引用规则】本部分共 %d 张图片：%s（已随本消息按此顺序提供，编号即图片顺序）。
                文本中已在图片所在位置插入了 [图片N] 标记，请据此判断图片归属。
                当题干、选项或共享材料是图片（或含图片）时，必须在对应文本位置写入 [图片N] 标记：
                - 题干有图：content = 完整题干文字 + [图片N]；题干只有图没有文字时，content 只写 [图片N]
                - 选项是图：该选项 text 只写 [图片N]；文字与图混合：文字 + [图片N]
                - 共享材料有图：material.content 中写 [图片N]
                禁止编造编号：只引用实际提供的编号；无法确定图片归属时宁可少引用；
                每个选项通常对应不同的图片，禁止把多个选项写成同一个编号。
                公式图（内容是数学公式/化学式的 [图片N]）禁止引用：题干与选项中的公式一律转写为 $...$ LaTeX 文本，
                禁止把公式图编号写进 content 或选项 text；[图片N] 只用于照片、几何图形、曲线图等非公式内容。
                """.formatted(numbers.size(), list);
    }

    /** Markdown 整理阶段的模板提示。 */
    public String buildMdExtractPrompt() {
        return """
                你是题库整理助手。把用户提供的文档内容整理成题目清单，严格按以下 Markdown 模板输出（只输出 Markdown 模板内容，不要输出其他格式或任何说明文字）：

                ## 第N题 · 题型
                **题干**：
                （题干全文，逐字保留原文，可多行；公式以 LaTeX（$...$）输出；含 (1)(2)(3) 子问的题目把全部子问合并在一题内，按原文换行保留）
                **选项**：
                - A. 选项内容
                - B. 选项内容
                **答案**：留空（本阶段不要写答案）
                **解析**：留空

                规则（重要）：
                1. 题号必须与文档一致、按文档顺序输出；**每个题块必须以"## 第N题 · 题型"标题开头，禁止省略标题**；
                   禁止合并相邻题、禁止遗漏任何一题（包括图形题、表格题、题号在行尾的题）。
                2. 题型写：单选 / 多选 / 判断 / 主观。填空题、实验题、作图题、计算题等非选择题 → 主观，且不写"**选项**"行。
                3. 题干含"填正确答案标号"等字样时，其中出现的 A/B/C/D 是填空标号不是选择题选项 → 题型为主观，不写选项。
                4. 选项挤在同一行（"A. …B. …C. …D. …"）时拆成独立选项行。
                5. 公式必须转写为 LaTeX：图片内容是数学公式/化学式时，直接输出 $...$ 公式文本（如 $F=ma$、$\\frac{1}{2}mv^2$、$kL^2$），
                   不要引用图片、不要描述图片、不要跳过公式。
                6. [图片N] 标记表示该位置存在一张图片（本块内图片已随消息提供）。图片就地保留：题干的图写在题干中原位置（通常在题干末尾），
                   只有选项本身是图片（如图线选项、几何图形选项）时该选项才写 [图片N]；禁止把题干图移到选项里、禁止重排图片位置；
                   公式图（内容是数学公式/化学式的 [图片N]）例外：一律按规则 5 转写为 $...$ LaTeX 文本，禁止引用公式图编号；
                   图形推理题的图在题干、选项是文字（如"①②⑥，③④⑤"）时照写文字。禁止编造编号，禁止转述图片内容。
                   若消息附带页面截图：截图仅用于判断版式（题号位置、题目边界、图形与选项的视觉归属），文字一律以文本层为准，图片引用仍用 [图片N]。
                7. 卷末"参考答案"区的答案行（"1.B"、"【1题答案】B"、"1-8：B D C…"）不是题目，不要输出；试卷开头的注意事项/答题说明也不是题目，跳过。
                   封面/宣传页的图片与广告内容（logo、二维码、课程推广等）不是题目内容：禁止引用其图片、禁止输出为题目。
                8. 题干与选项逐字保留原文：下划线、填空线、括号、引号、公式、特殊符号一律原样，禁止改写、删除或规范化。
                9. 题号紧跟在定义/说明文字之后（如"正向情绪价值：指……能力。1.下列属于……"）时，题号前的定义句属于该题题干，并入题干输出，禁止跳过。
                10. 共享材料：文档存在多题共用的大题干/表格/图表（如"材料一"、资料分析材料）时，在该组题之前输出一个"## 材料 m1"块（后续材料依次 m2、m3…），
                    材料全文写在块内（可含公式与 [图片N]）；材料后的题目 content 只写问题部分，不重复材料文字。没有共享材料时禁止输出材料块。
                11. 每个题块之间空一行。
                """;
    }

    /** JSON 输出路径的系统提示；整理阶段会禁用答案补充。 */
    public String buildSystemPrompt(boolean aiSupplement, boolean extractOnly) {
        String answerRule;
        if (extractOnly) {
            answerRule = "2. 本阶段只整理题目结构：所有题目的 answerKeys 一律返回空数组 []，不要输出 answerText 和 analysis，"
                    + "不要自行计算或猜测答案（答案与解析由后续阶段单独补充）；原文中的答案信息（题后\"答案：X\"、卷末答案列表如 \"1.B\"、\"【1题答案】B\"）照常保留在文档里即可，不要写进题目字段。\n"
                    + "   图片标记规则（重要）：文档文本中的 [图片N] 标记表示该位置存在一张图片（公式图/插图，本块内图片已随消息提供）。"
                    + "题干/选项是图片（或公式图）的题：紧跟题干文字之后的标记通常是题干图，保留在 content 末尾；选项区的标记按出现顺序写入对应选项的 text（\"[图片N]\"）；"
                    + "同一位置的多个连续标记按顺序对应；无法确定归属的标记保留在 content 末尾，不要丢弃也不要编造编号，禁止转述图片内容。";
        } else if (aiSupplement) {
            answerRule = "2. 只有原文完全没有答案的题目，才由你补充答案（基于内容判断正确），并将 answerSource 标记为 \"AI_SUPPLEMENT\"；使用原文答案的题目标记为 \"ORIGINAL\"。";
        } else {
            answerRule = "2. 原文没有答案的题目，保留 answerKeys 为空数组 []，不要自行补充答案，也不要生成解析；使用原文答案的题目标记 answerSource 为 \"ORIGINAL\"。";
        }
        String subjectiveRule;
        if (extractOnly) {
            subjectiveRule = "8. 主观题（应用题/简答/论述/计算题/实验题/填空题，无选项的作答类题目）：输出 type=\"SUBJECTIVE\"，不输出 options/answerKeys（空数组），"
                    + "referenceAnswer 留空（参考答案由后续阶段补充）。";
        } else if (aiSupplement) {
            subjectiveRule = "8. 主观题（应用题/简答/论述/计算题/实验题/填空题，无选项的作答类题目）：输出 type=\"SUBJECTIVE\"，不输出 options/answerKeys（空数组），"
                    + "referenceAnswer 字段写参考答案（可含 [图片N] 标记；原文的分段答案如（1）…（2）…原样保留）；原文没有参考答案时由你生成参考作答。";
        } else {
            subjectiveRule = "8. 主观题（应用题/简答/论述/计算题/实验题/填空题，无选项的作答类题目）：输出 type=\"SUBJECTIVE\"，不输出 options/answerKeys（空数组），"
                    + "referenceAnswer 写原文提供的参考答案（原文的分段答案如（1）…（2）…原样保留）；原文没有参考答案时 referenceAnswer 留空，不要自行编写。";
        }
        String materialRule = extractOnly
                ? "（材料题（多题共用大题干，如资料分析/阅读材料）处理：材料文字不需要你转写或输出，"
                + "也不要输出顶层 materials 字段、不要在题目上添加 materialKey 引用——"
                + "共享材料已由本地检测，预览页会作为素材块提供，用户可拖入题目材料区；"
                + "这类题 content 照常写题干原文（问题部分）即可）"
                : "（材料规则见上：材料单独输出到顶层 materials，题目用 materialKey 引用）";
        return """
                你是题库整理助手。把用户提供的文档内容整理为考试题目，严格输出一个 JSON 对象：{"questions":[...]}。
                每道题字段：
                type: "SINGLE"单选 / "MULTIPLE"多选 / "JUDGE"判断 / "SUBJECTIVE"主观题（无选项无答案）
                content: 题干（忠实原文，不做改写）
                options: [{"key":"A","text":"..."}]（顺序与原文一致；判断题固定 [{"key":"A","text":"正确"},{"key":"B","text":"错误"}]；主观题空数组）
                answerKeys: 正确答案 key 数组（单选/判断一个，多选多个；主观题空数组）
                answerText: 答案文字（可选）  analysis: 解析（可选）
                referenceAnswer: 主观题参考答案（仅 SUBJECTIVE，可选）
                topic: 主题（可选）  category: 分类（可选）  score: 分值（默认1，主观题默认5）
                answerSource: "ORIGINAL" 或 "AI_SUPPLEMENT"（见下方答案规则）

                共享材料规则（资料分析/阅读材料题）：
                若文档存在"多题共用的大题干"（如材料一/材料二、一段阅读材料带多道问题），
                把材料单独输出到顶层 "materials": [{"materialKey":"m1","content":"材料全文"}]，
                这些题的 content 只写问题部分，并在题目上加 "materialKey":"m1" 引用；
                禁止把材料文字重复写进每题 content；没有共享材料的文档不要输出 materials。
                """ + materialRule + """
                答案规则（最重要）：
                1. 原文提供答案的题目，必须使用原文答案，禁止自行计算或修改。原文答案可能出现在：
                   题后（"答案：B"、"答：C"）、括号标注（"（对）"、"（√）"）、
                   文档末尾的"参考答案/答案列表"（如 "1.B 2.C 3.ABD"、"【1题答案】B"、
                   "第1~8题答案 1-8：B D C…" 区间式，按题号对应到各题）；
                   若多个文件一并提供，其中一份可能是答案文件，其答案列表同样按题号对应，不要单独出题。
                """ + answerRule + """
                3. 主观题按下方"主观题规则"输出（见第 8 条），不要跳过。
                4. 原文明显笔误（如选项缺字母）可做最小修正并保持语义不变。
                5. 材料题（阅读材料+问题）按"共享材料规则"处理：材料进 materials、content 只写问题、题目带 materialKey。
                6. 题干与选项必须逐字保留原文：下划线 _、填空线、括号、引号、公式、特殊符号一律原样保留，
                   禁止删除、替换或规范化（如把 "___" 改成空格、把（ ）改成空白）。
                   公式（包括以图片形式出现的数学/化学公式）必须转写为 LaTeX（$...$，如 $F=ma$、$\\frac{1}{2}mv^2$），
                   不要用图片引用、不要截图式描述、不要跳过公式。
                   图片就地保留：题干的图写在题干中原位置（通常在题干末尾），只有选项本身是图片（如图线选项）时
                   该选项才写 [图片N]；禁止把题干图移到选项里、禁止重排图片位置。
                7. 文档中的每一道题都必须输出，禁止遗漏；确实无法确定答案时 answerKeys 返回空数组 []，
                   不要因此省略整道题。
                """ + subjectiveRule + """
                9. 学科卷（数理化生等）规则：填空题、实验题、作图题、计算题等非选择题 → type="SUBJECTIVE"；
                   题目含 (1)(2)(3) 等子问时合并为一题（子问文字按原文保留在题干中，换行分隔），禁止拆成多题；
                   题干含"填正确答案标号"等字样时，其中出现的 A/B/C/D 是填空标号不是选择题选项 → 不输出 options；
                   选项挤在同一行（"A. …B. …C. …D. …"）时拆成独立选项；
                   选项是图片（如图线选项）时，该选项 text 只写 [图片N]。
                10. 卷末"参考答案"区的答案行（"1.B"、"【1题答案】B"、"1-8：B D C…"）不是题目，不要输出；
                   试卷开头的注意事项/答题说明（"答题前…""注意事项…"开头段落）也不是题目，跳过。
                11. 禁止合并相邻题：每题独立输出；相邻题目之间内容不交叉。

                要求：题干完整、答案以原文为准、解析简明；只输出 JSON，不要任何其他文字。
                """;
    }

    /** 普通文本路径的用户提示。 */
    public String buildUserPrompt(String text, String warning) {
        StringBuilder prompt = new StringBuilder();
        if (warning != null) {
            prompt.append("注意：").append(warning).append('\n');
        }
        prompt.append("以下是文档内容，请整理为题目：\n\n");
        if (text != null && !text.isBlank()) {
            prompt.append(text);
        } else {
            prompt.append("（文档以图片形式提供，请识别图片中的内容出题）");
        }
        return prompt.toString();
    }

    private String stripImageRefs(String text) {
        if (text == null) {
            return null;
        }
        return text.replaceAll("\\[图片\\d+\\]", " ");
    }
}
