package com.tiku.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.tiku.config.AiSettings;
import com.tiku.dto.*;
import com.tiku.mapper.MaterialMapper;
import com.tiku.mapper.QuestionMapper;
import com.tiku.model.OptionItem;
import com.tiku.model.Question;
import com.tiku.model.enums.QuestionType;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

@Service
public class QuestionService {
    private final QuestionMapper questionMapper;
    private final QuestionBankService questionBankService;
    private final MaterialMapper materialMapper;
    private final AiConfigService aiConfigService;
    private final AiClientService aiClientService;
    private final ImageStorageService imageStorageService;

    public QuestionService(QuestionMapper questionMapper, QuestionBankService questionBankService,
                           MaterialMapper materialMapper, AiConfigService aiConfigService,
                           AiClientService aiClientService, ImageStorageService imageStorageService) {
        this.questionMapper = questionMapper;
        this.questionBankService = questionBankService;
        this.materialMapper = materialMapper;
        this.aiConfigService = aiConfigService;
        this.aiClientService = aiClientService;
        this.imageStorageService = imageStorageService;
    }

    /** 题目 content/选项/材料内的图片引用 [图片:name] */
    private static final Pattern IMAGE_REF_NAMED = Pattern.compile("\\[图片:([^\\]]+)]");

    /** AI 解析单飞锁：同一时间只允许一个生成请求（防用户多处并发点击 → 多路思考调用并发烧 token/拖垮接口） */
    private final java.util.concurrent.locks.ReentrantLock aiAnalysisLock = new java.util.concurrent.locks.ReentrantLock();

    /** 尝试占用 AI 解析通道；占用失败抛业务提示（前端已 busy 防重复，这里兜底多实例/多标签页） */
    private void acquireAiAnalysisSlot() {
        if (!aiAnalysisLock.tryLock()) {
            throw new IllegalStateException("已有 AI 解析正在生成中，请稍候再试");
        }
    }

    /**
     * 单题 AI 辅助解析（按需生成，不落库）：按题目 id 组装（含材料与题图）后调用分析核心。
     */
    public String generateAiAnalysis(Long id) {
        acquireAiAnalysisSlot();
        try {
            Question question = findByIdOrThrow(id);
            String materialContent = null;
            if (question.getMaterialId() != null) {
                com.tiku.model.Material material = materialMapper.selectById(question.getMaterialId());
                materialContent = material == null ? null : material.getContent();
            }
            String typeLabel = question.getQuestionType() == null ? null : question.getQuestionType().getLabel();
            return analyzeQuestion(question.getBankId(), typeLabel, question.getQuestionNumber(),
                    question.getContent(), question.getOptions(), question.getAnswerKeys(),
                    question.getAnswerText(), question.getReferenceAnswer(), materialContent);
        } finally {
            aiAnalysisLock.unlock();
        }
    }

    /**
     * 草稿 AI 解析（编辑/录入面板用：基于表单当前内容，题目可能尚未保存无 id）。
     */
    public String analyzeDraft(Long bankId, String questionTypeLabel, String content,
                               List<OptionItem> options, List<String> answerKeys,
                               String answerText, String referenceAnswer, String materialContent) {
        acquireAiAnalysisSlot();
        try {
            return analyzeQuestion(bankId, questionTypeLabel, null, content, options,
                    answerKeys == null ? null : String.join(",", answerKeys),
                    answerText, referenceAnswer, materialContent);
        } finally {
            aiAnalysisLock.unlock();
        }
    }

    /** 分析核心：组装正文 + 题图（[图片:name] → [图片N] 随消息附图），思考模式生成解析文本 */
    private String analyzeQuestion(Long bankId, String typeLabel, Integer questionNumber,
                                   String content, List<OptionItem> options, String answerKeysJoined,
                                   String answerText, String referenceAnswer, String materialContent) {
        List<AiClientService.ImageData> imgs = new ArrayList<>();
        StringBuilder body = new StringBuilder();
        if (questionNumber != null) {
            body.append("第 ").append(questionNumber).append(" 题");
        }
        if (typeLabel != null && !typeLabel.isBlank()) {
            body.append("（").append(typeLabel).append("）");
        }
        body.append("\n\n【题干】\n").append(remapImages(content, bankId, imgs));
        if (options != null && !options.isEmpty()) {
            body.append("\n\n【选项】\n");
            for (OptionItem o : options) {
                body.append(o.key()).append(". ").append(remapImages(o.text(), bankId, imgs)).append('\n');
            }
        }
        if (answerKeysJoined != null && !answerKeysJoined.isBlank()) {
            body.append("\n【正确答案】").append(answerKeysJoined);
        } else if (answerText != null && !answerText.isBlank()) {
            body.append("\n【答案】").append(remapImages(answerText, bankId, imgs));
        } else if (referenceAnswer != null && !referenceAnswer.isBlank()) {
            body.append("\n【参考答案】").append(remapImages(referenceAnswer, bankId, imgs));
        }
        if (materialContent != null && !materialContent.isBlank()) {
            body.append("\n\n【共享材料】\n").append(remapImages(materialContent, bankId, imgs));
        }
        StringBuilder user = new StringBuilder();
        user.append("请为下面这道题撰写一份解析（写给做题者，帮助理解与举一反三）：\n")
                .append("- 先说答案（如已给出答案请围绕它解释），再分步说明解题思路；客观题解释每个选项对/错的关键；主观题给出完整步骤；\n")
                .append("- 若材料/题干/选项含图片引用 [图片N]，请结合对应图片讲解；\n")
                .append("- 公式一律用 $...$ 包裹的 LaTeX（行内公式），不要用图片；\n")
                .append("- 中文作答，结构清晰（可用小标题或编号），篇幅适中（客观题 150-300 字，主观题可更详细）；\n")
                .append("- 不要复述题干与选项全文，直接写解析。\n\n")
                .append(body);
        if (!imgs.isEmpty()) {
            user.append("\n\n（图片已按 [图片1]..[图片").append(imgs.size())
                    .append("] 的顺序随本消息提供，编号即图片顺序）");
        }
        AiSettings settings = aiConfigService.load();
        AiSettings think = new AiSettings();
        think.setBaseUrl(settings.getBaseUrl());
        think.setApiKey(settings.getApiKey());
        think.setModel(settings.getModel());
        think.setVisionModel(settings.getVisionModel());
        think.setThinking(true);
        String out;
        if (imgs.isEmpty()) {
            //无图：走主模型（纯文本，快且省）
            out = aiClientService.chat(think, "你是耐心的题库解析老师。", user.toString(), false);
        } else {
            //有图：走视觉模型（visionModel，未配置时退回主模型——部分端点主模型本身支持图片）
            AiSettings vision = new AiSettings();
            vision.setBaseUrl(settings.getBaseUrl());
            vision.setApiKey(settings.getApiKey());
            vision.setModel(settings.getVisionModel() != null && !settings.getVisionModel().isBlank()
                    ? settings.getVisionModel() : settings.getModel());
            vision.setVisionModel(settings.getVisionModel());
            vision.setThinking(true);
            out = aiClientService.chatWithImages(vision, "你是耐心的题库解析老师。", user.toString(), imgs, false);
        }
        return normalizeLatex(out);
    }

    /** [图片:name] → [图片N] 并按序收集图片（读取失败跳过该图，保留引用原样） */
    private String remapImages(String text, Long bankId, List<AiClientService.ImageData> imgs) {
        if (text == null || text.isEmpty()) {
            return text == null ? "" : text;
        }
        if (bankId == null) {
            return text;
        }
        Matcher m = IMAGE_REF_NAMED.matcher(text);
        StringBuilder sb = new StringBuilder();
        int last = 0;
        int n = 0;
        while (m.find()) {
            String name = m.group(1).trim();
            sb.append(text, last, m.start());
            byte[] data = null;
            try {
                data = imageStorageService.read(bankId, name);
            } catch (Exception ignored) {
                //图片缺失 → 保留引用文本
            }
            if (data != null && data.length > 0) {
                n++;
                sb.append("[图片").append(n).append("]");
                imgs.add(new AiClientService.ImageData("image/png", data));
            } else {
                sb.append(m.group());
            }
            last = m.end();
        }
        sb.append(text.substring(last));
        return sb.toString();
    }

    /** 生成文本的 LaTeX 定界符归一（模型可能输出 \(...\)，统一 $）；[图片N] 引用转可读占位（渲染层只认 [图片:name]） */
    private String normalizeLatex(String s) {
        if (s == null) {
            return "";
        }
        String t = s.trim().replace("\\[", "$$").replace("\\]", "$$")
                .replace("\\(", "$").replace("\\)", "$");
        return t.replaceAll("\\[图片\\d+\\]", "（见题图）");
    }

    /** 保存 AI 解析为题目正式解析（覆盖 analysis 字段） */
    @Transactional
    public void saveAnalysis(Long id, String analysis) {
        findByIdOrThrow(id);
        if (analysis == null || analysis.isBlank()) {
            throw new IllegalArgumentException("解析内容不能为空");
        }
        Question update = new Question();
        update.setAnalysis(analysis.trim());
        update.setUpdatedAt(java.time.LocalDateTime.now());
        questionMapper.update(update, new LambdaUpdateWrapper<Question>().eq(Question::getId, id));
    }

    public QuestionDetailResponse getQuestionDetail(Long id){
        Question question = findByIdOrThrow(id);
        String materialContent = null;
        if (question.getMaterialId() != null) {
            com.tiku.model.Material material = materialMapper.selectById(question.getMaterialId());
            materialContent = material == null ? null : material.getContent();
        }
        return QuestionDetailResponse.fromEntity(question, materialContent);
    }

    public AnswerResultResponse checkAnswer(Long id, List<String> selectKeys){
        Question question = findByIdOrThrow(id);
        if (question.getQuestionType() == QuestionType.SUBJECTIVE) {
            throw new IllegalArgumentException("主观题不支持自动判题，请提交作答后自行评分");
        }
        String rawKeys = question.getAnswerKeys();
        if(rawKeys == null || rawKeys.isBlank()){
            throw new IllegalStateException("题目未配置正确答案：" + id);
        }

        //两侧都做去空白归一（存量数据/外部导入的 key 可能带空格），防止"看答案是对的、判题永远错"
        Set<String> correctKeys = Arrays.stream(rawKeys.split(","))
                .map(String::trim).filter(s -> !s.isBlank()).collect(Collectors.toSet());
        Set<String> userKeys = (selectKeys == null ? List.<String>of() : selectKeys).stream()
                .filter(Objects::nonNull).map(String::trim).filter(s -> !s.isBlank()).collect(Collectors.toSet());
        boolean isCorrect = !userKeys.isEmpty() && correctKeys.equals(userKeys);

        List<String> sortedCorrectKeys = correctKeys.stream().sorted().toList();
        return new AnswerResultResponse(isCorrect, sortedCorrectKeys, question.getAnswerText(), question.getAnalysis());
    }

    public Long createQuestion(QuestionCreateRequest request){
        //题目必须属于一个已存在的题库（无题目总库设计，防止孤儿题目）
        questionBankService.findByIdOrThrow(request.bankId());
        validateObjectiveFields(request.questionType(), request.options(), request.answerKeys());
        Question question = toEntity(request);
        questionMapper.insert(question);
        return question.getId();
    }

    //批量建题（AI 整理数据 / 粘贴导入场景），一个事务内完成，返回插入数量
    @Transactional
    public int batchCreateQuestions(Long bankId, QuestionBatchCreateRequest request){
        questionBankService.findByIdOrThrow(bankId);
        for (QuestionBatchItemRequest item : request.questions()) {
            validateObjectiveFields(item.questionType(), item.options(), item.answerKeys());
            Question question = new Question();
            question.setExternalId(generateExternalId(item.questionType()));
            question.setVolume(item.volume());
            question.setQuestionType(item.questionType());
            question.setQuestionNumber(item.questionNumber());
            question.setContent(item.content());
            question.setOptions(item.options());
            question.setTopic(item.topic());
            question.setCategory(item.category());
            question.setScore(item.score() == null ? 1.0 : item.score());
            question.setAnswerKeys(joinTrimmedKeys(item.answerKeys()));
            question.setAnswerText(item.answerText());
            question.setAnalysis(item.analysis());
            question.setMaterialId(item.materialId());
            question.setReferenceAnswer(item.referenceAnswer());
            question.setBankId(bankId);
            questionMapper.insert(question);
        }
        return request.questions().size();
    }

    /** 客观题（SINGLE/MULTIPLE/JUDGE）必须有选项；答案允许为空（可先录入题干选项、后补答案——
     *  无答案题作答不判题、不进错题本，见 StudyRecordService.submitAnswer） */
    private void validateObjectiveFields(QuestionType type, List<OptionItem> options, List<String> answerKeys) {
        if (type == QuestionType.SUBJECTIVE) {
            return;
        }
        if (options == null || options.isEmpty()) {
            throw new IllegalArgumentException("选项不能为空");
        }
        //答案可空；非空时校验 key 都存在于选项（防永远判错）在调用方按需执行
    }

    /**
     * 更新题目（部分更新：null 字段不修改）。
     * 与创建一致的一致性校验：客观题必须有选项与答案（防"改坏题"→ 该题作答永久 500、永远进错题本）；
     * 答案 key 统一去首尾空白。
     * 题型切换语义：切换到客观题时必须同请求内提供选项与正确答案（缺则 400 提示）；
     * 切换到主观题时自动清空客观题残留字段（options/answerKeys/answerText）。
     */
    @Transactional
    public void updateQuestion(Long id, QuestionUpdateRequest request){
        Question question = findByIdOrThrow(id);

        //逐个字段更新：仅当 request 中的字段不为 null 时才覆盖
        if (request.volume() != null) {
            question.setVolume(request.volume());
        }
        if (request.questionType() != null) {
            question.setQuestionType(request.questionType());
        }
        if (request.questionNumber() != null) {
            question.setQuestionNumber(request.questionNumber());
        }
        if (request.content() != null) {
            question.setContent(request.content());
        }
        if (request.options() != null) {
            question.setOptions(request.options());
        }
        if (request.topic() != null) {
            question.setTopic(request.topic());
        }
        if (request.category() != null) {
            question.setCategory(request.category());
        }
        if (request.score() != null) {
            question.setScore(request.score());
        }
        if (request.answerKeys() != null) {
            question.setAnswerKeys(joinTrimmedKeys(request.answerKeys()));
        }
        if (request.answerText() != null) {
            question.setAnswerText(request.answerText());
        }
        if (request.analysis() != null) {
            question.setAnalysis(request.analysis());
        }
        if (request.materialId() != null) {
            question.setMaterialId(request.materialId());
        }
        if (request.referenceAnswer() != null) {
            question.setReferenceAnswer(request.referenceAnswer());
        }

        QuestionType finalType = question.getQuestionType();
        if (finalType != QuestionType.SUBJECTIVE) {
            //客观题：答案 key 规范化 + 一致性校验（存量数据可能带空白/非法，一并归一）
            List<String> keys = question.getAnswerKeys() == null || question.getAnswerKeys().isBlank()
                    ? List.of()
                    : Arrays.stream(question.getAnswerKeys().split(","))
                            .map(String::trim).filter(s -> !s.isBlank()).toList();
            validateObjectiveFields(finalType, question.getOptions(), keys);
            question.setAnswerKeys(keys.isEmpty() ? null : String.join(",", keys));
        }

        questionMapper.updateById(question);

        if (finalType == QuestionType.SUBJECTIVE) {
            //切换到（或保持）主观题：客观题字段对主观题无意义，落库清空（MyBatis-Plus 实体 null 不更新，需显式 wrapper）
            questionMapper.update(null, new LambdaUpdateWrapper<Question>()
                    .eq(Question::getId, id)
                    .set(Question::getOptions, null)
                    .set(Question::getAnswerKeys, null)
                    .set(Question::getAnswerText, null));
        }
    }

    public void deleteQuestion(Long id){
        Question question = findByIdOrThrow(id);
        questionMapper.deleteById(id);
    }

    //收藏/取消收藏（做对也想二刷的题）
    public void setFavorite(Long id, boolean favorite) {
        Question question = findByIdOrThrow(id);
        question.setFavorite(favorite);
        questionMapper.updateById(question);
    }

    private boolean existsByExternalId(String externalId){
        return questionMapper.selectCount(
                new LambdaQueryWrapper<Question>()
                        .eq(Question::getExternalId, externalId)) > 0;
    }

    private Question findByIdOrThrow(Long id){
        Question question = questionMapper.selectById(id);
        if(question == null){
            throw new NoSuchElementException("题目不存在：" + id);
        }
        return question;
    }

    private Question toEntity(QuestionCreateRequest request){
        Question question = new Question();
        question.setExternalId(generateExternalId(request.questionType()));
        question.setVolume(request.volume());
        question.setQuestionType(request.questionType());
        question.setQuestionNumber(request.questionNumber());
        question.setContent(request.content());
        question.setOptions(request.options());
        question.setTopic(request.topic());
        question.setCategory(request.category());
        question.setScore(request.score() == null ? 1.0 : request.score());
        question.setAnswerKeys(joinTrimmedKeys(request.answerKeys()));
        question.setAnswerText(request.answerText());
        question.setAnalysis(request.analysis());
        question.setMaterialId(request.materialId());
        question.setReferenceAnswer(request.referenceAnswer());
        question.setBankId(request.bankId());
        return question;
    }

    private String generateExternalId(QuestionType questionType){
        return questionType.name() + "_" + UUID.randomUUID().toString().substring(0, 8).toUpperCase();
    }

    /** 答案 key 规范化：去首尾空白、去空项；无有效项返回 null（入库为 NULL 而非空串） */
    private static String joinTrimmedKeys(List<String> keys) {
        if (keys == null) {
            return null;
        }
        String joined = keys.stream()
                .filter(Objects::nonNull)
                .map(String::trim)
                .filter(s -> !s.isBlank())
                .collect(Collectors.joining(","));
        return joined.isEmpty() ? null : joined;
    }
}
