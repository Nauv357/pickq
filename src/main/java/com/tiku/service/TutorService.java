package com.tiku.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tiku.config.AiSettings;
import com.tiku.mapper.QuestionMapper;
import com.tiku.mapper.StudyRecordMapper;
import com.tiku.mapper.TutorMessageMapper;
import com.tiku.mapper.TutorSessionMapper;
import com.tiku.model.Question;
import com.tiku.model.OptionItem;
import com.tiku.model.StudyRecord;
import com.tiku.model.TutorMessage;
import com.tiku.model.TutorSession;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;

/**
 * AI 私教（学习路径引擎阶段 1）：答错即问、提示楼梯、自由追问、整场复盘。
 * 设计见 docs/learning-path-design.md §7.3、§7.4、§9。
 *
 * 边界（很重要，避免做成"又一个聊天框"）：
 * - **只在用户主动开口时说话**：做题过程中不自动弹窗（设计明确"不做练习中自动弹聊天框"）；
 * - **提示楼梯分级**：L1 指方向 → L2 关键一步 → L3 完整解析 → L4 自由追问，每级都落库；
 * - **掌握度/薄弱点排序是公式算的**（本场错题的节点分布、该节点的历史正确率），模型只负责**讲人话**，
 *   不让模型判断"你会不会"；
 * - 上下文按固定顺序拼（题干 → 选项 → 答案 → 解析 → 我的作答与错因 → 该题历史 → 该知识点表现 → 最近对话），
 *   控制 token 且不把无关内容喂进去。
 */
@Slf4j
@Service
public class TutorService {

    /** 解析/材料进 prompt 时的截断长度（控制 token） */
    private static final int ANALYSIS_CHARS = 600;
    /** 自由追问带上的历史轮数（一问一答算一轮） */
    private static final int HISTORY_ROUNDS = 4;
    /** 复盘清单最多带几道错题（超出只给统计，避免 prompt 爆炸） */
    private static final int REVIEW_MAX_QUESTIONS = 30;

    private final QuestionMapper questionMapper;
    private final StudyRecordMapper studyRecordMapper;
    private final TutorSessionMapper sessionMapper;
    private final TutorMessageMapper messageMapper;
    private final QuestionTaggingService taggingService;
    private final SkillGraphService graphService;
    private final AiClientService aiClientService;
    private final AiConfigService aiConfigService;
    private final ObjectMapper objectMapper;

    public TutorService(QuestionMapper questionMapper, StudyRecordMapper studyRecordMapper,
                        TutorSessionMapper sessionMapper, TutorMessageMapper messageMapper,
                        QuestionTaggingService taggingService, SkillGraphService graphService,
                        AiClientService aiClientService, AiConfigService aiConfigService, ObjectMapper objectMapper) {
        this.questionMapper = questionMapper;
        this.studyRecordMapper = studyRecordMapper;
        this.sessionMapper = sessionMapper;
        this.messageMapper = messageMapper;
        this.taggingService = taggingService;
        this.graphService = graphService;
        this.aiClientService = aiClientService;
        this.aiConfigService = aiConfigService;
        this.objectMapper = objectMapper;
    }

    // ==================== 会话 ====================

    /** 一场复盘里的错题统计 + 按知识点的错题分布（**公式算的**，模型只负责描述） */
    public record ReviewSummary(int total, int correct, int wrong, int unanswered,
                                List<NodeWrong> byNode, List<WrongItem> wrongItems) {
    }

    public record NodeWrong(String nodeId, String name, int wrong, int answered, int correctRate) {
    }

    public record WrongItem(Long questionId, Integer questionNumber, String type, String preview,
                            String yourAnswer, String correctAnswer, String selfReason, List<String> nodeNames) {
    }

    public record SessionView(Long id, Long questionId, Long practiceSessionId, String kind, String selfReason,
                              String selfNote, String status, LocalDateTime createdAt, int messageCount) {
    }

    public record MessageView(Long id, String role, String content, Integer hintLevel, LocalDateTime createdAt) {
    }

    /**
     * 开一场追问会话（同一题 + 同一场练习下复用未关闭的会话，避免每点一次提示就新建一条记录）。
     */
    @Transactional
    public TutorSession openSession(Long bankId, Long questionId, Long practiceSessionId, String kind,
                                    String selfReason, String selfNote) {
        String safeKind = TutorSession.KIND_POST_REVIEW.equals(kind) ? TutorSession.KIND_POST_REVIEW
                : TutorSession.KIND_PER_QUESTION;
        if (TutorSession.KIND_PER_QUESTION.equals(safeKind) && questionId == null) {
            throw new IllegalArgumentException("单题追问必须指定题目");
        }
        if (TutorSession.KIND_POST_REVIEW.equals(safeKind) && practiceSessionId == null) {
            throw new IllegalArgumentException("复盘必须指定练习场次");
        }
        TutorSession existing = findOpenSession(questionId, practiceSessionId, safeKind);
        if (existing != null) {
            // 错因是"第一手数据"：后来选的会覆盖先前（用户可能改主意），但补充说明不会被清空
            if (selfReason != null && !selfReason.isBlank()) {
                existing.setSelfReason(selfReason);
            }
            if (selfNote != null && !selfNote.isBlank()) {
                existing.setSelfNote(truncate(selfNote, 500));
            }
            existing.setUpdatedAt(LocalDateTime.now());
            sessionMapper.updateById(existing);
            return existing;
        }
        TutorSession row = new TutorSession();
        row.setBankId(bankId);
        row.setQuestionId(questionId);
        row.setPracticeSessionId(practiceSessionId);
        row.setKind(safeKind);
        row.setSelfReason(selfReason == null || selfReason.isBlank() ? null : selfReason);
        row.setSelfNote(selfNote == null || selfNote.isBlank() ? null : truncate(selfNote, 500));
        row.setStatus("OPEN");
        row.setCreatedAt(LocalDateTime.now());
        row.setUpdatedAt(LocalDateTime.now());
        sessionMapper.insert(row);
        return row;
    }

    private TutorSession findOpenSession(Long questionId, Long practiceSessionId, String kind) {
        LambdaQueryWrapper<TutorSession> w = new LambdaQueryWrapper<TutorSession>()
                .eq(TutorSession::getKind, kind)
                .eq(TutorSession::getStatus, "OPEN")
                .orderByDesc(TutorSession::getId)
                .last("LIMIT 1");
        w = questionId != null ? w.eq(TutorSession::getQuestionId, questionId) : w.isNull(TutorSession::getQuestionId);
        w = practiceSessionId != null
                ? w.eq(TutorSession::getPracticeSessionId, practiceSessionId)
                : w.isNull(TutorSession::getPracticeSessionId);
        return sessionMapper.selectOne(w);
    }

    public TutorSession requireSession(Long sessionId) {
        TutorSession s = sessionMapper.selectById(sessionId);
        if (s == null) {
            throw new IllegalArgumentException("追问会话不存在：" + sessionId);
        }
        return s;
    }

    public List<MessageView> messages(Long sessionId) {
        requireSession(sessionId);
        return messageMapper.selectList(new LambdaQueryWrapper<TutorMessage>()
                        .eq(TutorMessage::getSessionId, sessionId)
                        .orderByAsc(TutorMessage::getId))
                .stream()
                .map(m -> new MessageView(m.getId(), m.getRole(), m.getContent(), m.getHintLevel(), m.getCreatedAt()))
                .toList();
    }

    /** 某题的追问历史（错题本/题目详情里"问老师"要能接着上次聊） */
    public List<SessionView> sessionsOfQuestion(Long questionId) {
        return sessionMapper.selectList(new LambdaQueryWrapper<TutorSession>()
                        .eq(TutorSession::getQuestionId, questionId)
                        .orderByDesc(TutorSession::getId))
                .stream()
                .map(s -> new SessionView(s.getId(), s.getQuestionId(), s.getPracticeSessionId(), s.getKind(),
                        s.getSelfReason(), s.getSelfNote(), s.getStatus(), s.getCreatedAt(), countMessages(s.getId())))
                .toList();
    }

    /** 这道题提示到第几级了（界面据此决定下一个按钮是 L2 还是 L3） */
    public int maxHintLevel(Long sessionId) {
        return messageMapper.selectList(new LambdaQueryWrapper<TutorMessage>()
                        .eq(TutorMessage::getSessionId, sessionId)
                        .eq(TutorMessage::getRole, TutorMessage.ROLE_ASSISTANT)
                        .isNotNull(TutorMessage::getHintLevel))
                .stream()
                .map(TutorMessage::getHintLevel)
                .filter(java.util.Objects::nonNull)
                .max(Comparator.naturalOrder())
                .orElse(0);
    }

    private int countMessages(Long sessionId) {
        return Math.toIntExact(messageMapper.selectCount(new LambdaQueryWrapper<TutorMessage>()
                .eq(TutorMessage::getSessionId, sessionId)));
    }

    // ==================== 提示楼梯（L1/L2/L3） ====================

    /**
     * 给一级提示并流式输出（L1 指方向 / L2 关键一步 / L3 完整解析）。
     * 每级都会把**之前的提示**带进上下文，避免 L2 又把 L1 的话重复一遍。
     */
    @Transactional
    public TutorMessage hint(Long sessionId, int level, String templateId, Consumer<String> onDelta) {
        TutorSession session = requireSession(sessionId);
        if (session.getQuestionId() == null) {
            throw new IllegalArgumentException("整场复盘没有提示楼梯（请直接追问）");
        }
        int lv = Math.max(1, Math.min(3, level));
        AiSettings settings = requireSettings();
        Question question = questionMapper.selectById(session.getQuestionId());
        if (question == null) {
            throw new IllegalArgumentException("题目不存在：" + session.getQuestionId());
        }
        String context = buildQuestionContext(session, question, templateId, false);
        List<AiClientService.ChatTurn> turns = new ArrayList<>();
        // 把已给出的提示作为"老师的上几轮发言"，让本级别递进而不是重复
        for (TutorMessage m : previousAssistantMessages(sessionId)) {
            turns.add(AiClientService.ChatTurn.assistant("（我之前给出的第 " + m.getHintLevel() + " 级提示）" + m.getContent()));
        }
        turns.add(AiClientService.ChatTurn.user(context + "\n\n" + hintInstruction(lv)));
        String text = aiClientService.chatStream(settings, HINT_SYSTEM, turns, onDelta);
        return saveAssistant(sessionId, text, lv, settings.getModel());
    }

    private String hintInstruction(int level) {
        return switch (level) {
            case 1 -> """
                    请给出**第 1 级提示：只指方向**。
                    - 一句话点出这道题考的是什么、该往哪个方向想（考点/方法名），最多两句；
                    - 绝对不要给解题步骤，也不要透露答案或选项的对错；
                    - 不要复述题干。""";
            case 2 -> """
                    请给出**第 2 级提示：关键一步**。
                    - 只讲最关键的那一步（用哪个公式/从哪入手/怎么排除），让做题的人自己能往下走；
                    - 仍然不要给出最终答案；
                    - 不要重复第 1 级已经说过的话。""";
            default -> """
                    请给出**第 3 级提示：完整解析**。
                    - 完整讲清解题过程，明确说出正确答案，并解释其他选项为什么不对（客观题）；
                    - 若题目自带解析，请把它讲透、补上"为什么"，而不是照抄；
                    - 最后用一句话点出这类题的通用做法。""";
        };
    }

    // ==================== 自由追问（L4）与答错即问 ====================

    /**
     * 用户追问一句（含"答错即问"的错因说明）。带最近 4 轮对话与固定上下文。
     */
    @Transactional
    public TutorMessage ask(Long sessionId, String text, String templateId, Consumer<String> onDelta) {
        TutorSession session = requireSession(sessionId);
        String question = text == null ? "" : text.trim();
        if (question.isEmpty() && session.getSelfReason() == null) {
            throw new IllegalArgumentException("请先写一句想问什么");
        }
        AiSettings settings = requireSettings();
        Question q = session.getQuestionId() == null ? null : questionMapper.selectById(session.getQuestionId());
        String context = q == null
                ? buildReviewContext(session)
                : buildQuestionContext(session, q, templateId, true);
        List<AiClientService.ChatTurn> turns = new ArrayList<>();
        for (TutorMessage m : recentMessages(sessionId)) {
            turns.add(new AiClientService.ChatTurn(m.getRole(), m.getContent()));
        }
        String askText = question.isEmpty()
                ? context + "\n\n（我没有额外的问题，请针对上面的错因给我一句最关键的提醒。）"
                : context + "\n\n【我的追问】" + question;
        turns.add(AiClientService.ChatTurn.user(askText));
        if (!question.isEmpty()) {
            saveUser(sessionId, question);
        }
        String reply = aiClientService.chatStream(settings, TUTOR_SYSTEM, turns, onDelta);
        return saveAssistant(sessionId, reply, null, settings.getModel());
    }

    // ==================== 讲解（结构化三段；做题后的唯一解析入口） ====================

    /**
     * 讲解三段的小标题：模型原样输出，前端按这三个标记切块渲染（流式时逐块长出来）。
     * **两种口吻共用同一套结构**，只有第一、三段的标题不同：
     * - {@link #MODE_WRONG}（有作答）：错在哪 / 这类题怎么做 / 下次防错；
     * - {@link #MODE_NEUTRAL}（没作答）：这道题怎么做 / 这类题怎么做 / 易错点。
     */
    public static final String EXPLAIN_HEAD_WHERE = "【错在哪】";
    public static final String EXPLAIN_HEAD_HOW = "【这类题怎么做】";
    public static final String EXPLAIN_HEAD_GUARD = "【下次防错】";
    public static final String EXPLAIN_HEAD_HOWTO = "【这道题怎么做】";
    public static final String EXPLAIN_HEAD_PITFALL = "【易错点】";

    /** 讲解口吻：有作答（针对你的错）/ 没作答（中性讲题） */
    public static final String MODE_WRONG = "WRONG";
    public static final String MODE_NEUTRAL = "NEUTRAL";

    /**
     * 讲解（2026-09-16 起是**做题后唯一的解析入口**）：一次讲清三段。
     *
     * 为什么只有一种解析（用户拍板）：过去"答错即问"和题库里的"AI 解析"是两套 prompt、两种风格，
     * 同一道错题会出现两段说法不同的话——现在统一成这一个引擎，**两个去处**：
     * ① 就地看（可继续追问）② 「存为解析」写回题库。
     *
     * 与提示楼梯的分工：楼梯是"做题中还不想要答案"时的分级给；讲解是"做完/做错之后要弄明白"时的一次性交付。
     * 因此讲解**不占提示级别**（hintLevel 留空，不进楼梯状态），但会作为对话历史参与后续追问。
     *
     * @param mode WRONG / NEUTRAL；传空则按"这道题有没有作答记录"自动判定
     */
    @Transactional
    public TutorMessage explain(Long sessionId, String templateId, String mode, Consumer<String> onDelta) {
        TutorSession session = requireSession(sessionId);
        if (session.getQuestionId() == null) {
            throw new IllegalArgumentException("讲解需要指定题目");
        }
        Question question = questionMapper.selectById(session.getQuestionId());
        if (question == null) {
            throw new IllegalArgumentException("题目不存在：" + session.getQuestionId());
        }
        String effective = resolveExplainMode(session, question.getId(), mode);
        AiSettings settings = requireSettings();
        String context = buildQuestionContext(session, question, templateId, false);
        List<AiClientService.ChatTurn> turns = new ArrayList<>();
        //已给过提示的话，把最后一级带进来，避免讲解与提示重复或互相矛盾
        List<TutorMessage> hints = previousAssistantMessages(sessionId);
        if (!hints.isEmpty()) {
            TutorMessage last = hints.get(hints.size() - 1);
            turns.add(AiClientService.ChatTurn.assistant(
                    "（我之前给出的第 " + last.getHintLevel() + " 级提示）" + last.getContent()));
        }
        turns.add(AiClientService.ChatTurn.user(context + "\n\n" + explainInstruction(effective)));
        String text = aiClientService.chatStream(settings, EXPLAIN_SYSTEM, turns, onDelta);
        return saveAssistant(sessionId, text, null, settings.getModel());
    }

    /**
     * 定这次讲哪种口吻：调用方指定就听调用方的；没指定就看这道题**最近一次作答对不对**
     * （答错 → 讲"你错在哪"；答对或没作答 → 中性讲"这道题怎么做"）。
     * 注意：题库列表里点「讲解」时没有练习场次，取的是这道题的最近一次作答——"你上次选的 B" 也是有用的信息，
     * 但**答对过就不该再讲"你错在哪"**。
     */
    private String resolveExplainMode(TutorSession session, Long questionId, String requested) {
        if (MODE_WRONG.equalsIgnoreCase(requested)) {
            return MODE_WRONG;
        }
        if (MODE_NEUTRAL.equalsIgnoreCase(requested)) {
            return MODE_NEUTRAL;
        }
        StudyRecord last = latestRecord(session.getPracticeSessionId(), questionId);
        return last != null && StudyRecordService.isWrong(last) ? MODE_WRONG : MODE_NEUTRAL;
    }

    private String explainInstruction(String mode) {
        return MODE_NEUTRAL.equals(mode) ? EXPLAIN_INSTRUCTION_NEUTRAL : EXPLAIN_INSTRUCTION;
    }

    // ==================== 整场复盘 ====================

    /**
     * 复盘诊断：先给**公式算的**统计（本场成绩、按知识点的错题分布），再让模型写一段人话诊断。
     * 模型不允许判定"你掌握了没有"，也不允许编造清单以外的题。
     */
    @Transactional
    public TutorMessage diagnose(Long sessionId, String templateId, Consumer<String> onDelta) {
        TutorSession session = requireSession(sessionId);
        if (session.getPracticeSessionId() == null) {
            throw new IllegalArgumentException("复盘会话缺少练习场次");
        }
        ReviewSummary summary = reviewSummary(session.getBankId(), session.getPracticeSessionId(), templateId);
        AiSettings settings = requireSettings();
        List<AiClientService.ChatTurn> turns = new ArrayList<>();
        turns.add(AiClientService.ChatTurn.user(buildReviewPrompt(summary)));
        String text = aiClientService.chatStream(settings, REVIEW_SYSTEM, turns, onDelta);
        return saveAssistant(sessionId, text, null, settings.getModel());
    }

    /**
     * 本场练习的复盘统计（纯公式）：
     * 成绩来自 study_record；错题的知识点来自当前技能图的可用标签；同一知识点的历史正确率来自全部作答记录。
     */
    public ReviewSummary reviewSummary(Long bankId, Long practiceSessionId, String templateId) {
        List<StudyRecord> records = studyRecordMapper.selectList(new LambdaQueryWrapper<StudyRecord>()
                .eq(StudyRecord::getSessionId, practiceSessionId));
        // 该题库的全部作答记录一次取回（本地数据量小）：算"某知识点历史正确率"时不再逐题查库
        Map<Long, List<StudyRecord>> allByQuestion = new HashMap<>();
        for (StudyRecord r : studyRecordMapper.selectList(new LambdaQueryWrapper<StudyRecord>()
                .eq(StudyRecord::getBankId, bankId))) {
            allByQuestion.computeIfAbsent(r.getQuestionId(), k -> new ArrayList<>()).add(r);
        }
        int correct = 0;
        int wrong = 0;
        List<StudyRecord> wrongRecords = new ArrayList<>();
        for (StudyRecord r : records) {
            //统一口径（含主观题自评 PARTIAL/WRONG 算错、未判定不算对也不算错）
            if (StudyRecordService.isCorrect(r)) {
                correct++;
            } else if (StudyRecordService.isWrong(r)) {
                wrong++;
                wrongRecords.add(r);
            }
        }
        Map<String, int[]> nodeStats = new LinkedHashMap<>(); // nodeId → [本场错题数]
        List<WrongItem> items = new ArrayList<>();
        for (StudyRecord r : wrongRecords) {
            Question q = questionMapper.selectById(r.getQuestionId());
            if (q == null) {
                continue;
            }
            List<QuestionTaggingService.QuestionTag> tags = tagsOf(q.getId(), templateId);
            List<String> names = tags.stream().map(QuestionTaggingService.QuestionTag::name).toList();
            for (QuestionTaggingService.QuestionTag tag : tags) {
                nodeStats.computeIfAbsent(tag.nodeId(), k -> new int[1])[0]++;
            }
            if (items.size() < REVIEW_MAX_QUESTIONS) {
                items.add(new WrongItem(q.getId(), q.getQuestionNumber(),
                        q.getQuestionType() == null ? "" : q.getQuestionType().name(),
                        truncate(oneLine(q.getContent()), 120),
                        answerOfRecord(r), answerOfQuestion(q), selfReasonOf(q.getId(), practiceSessionId), names));
            }
        }
        // 每个知识点的历史正确率（公式：该节点下所有做过的题；数据不足就如实说"还没有历史数据"）
        List<NodeWrong> byNode = new ArrayList<>();
        Map<String, int[]> rateCache = new HashMap<>();
        for (String nodeId : nodeStats.keySet()) {
            int[] rc = rateCache.computeIfAbsent(nodeId,
                    k -> performanceCounts(bankId, templateId, k, allByQuestion));
            byNode.add(new NodeWrong(nodeId, nameOfNode(nodeId, templateId), nodeStats.get(nodeId)[0],
                    rc[0], rc[0] == 0 ? -1 : Math.round(rc[1] * 100f / rc[0])));
        }
        byNode.sort(Comparator.comparingInt(NodeWrong::wrong).reversed());
        return new ReviewSummary(records.size(), correct, wrong, records.size() - correct - wrong, byNode, items);
    }

    private List<Long> questionIdsOfNode(Long bankId, String nodeId, String templateId) {
        return taggingService.idsMatching(bankId, templateId, "all", nodeId);
    }

    /** 某节点上的历史作答统计：[answered, correct] */
    private int[] performanceCounts(Long bankId, String templateId, String nodeId,
                                    Map<Long, List<StudyRecord>> allByQuestion) {
        int answered = 0;
        int ok = 0;
        for (Long qid : questionIdsOfNode(bankId, nodeId, templateId)) {
            for (StudyRecord r : allByQuestion.getOrDefault(qid, List.of())) {
                if (r.getCorrect() != null) {
                    answered++;
                    if (Boolean.TRUE.equals(r.getCorrect())) {
                        ok++;
                    }
                }
            }
        }
        return new int[]{answered, ok};
    }

    private String selfReasonOf(Long questionId, Long practiceSessionId) {
        TutorSession s = sessionMapper.selectOne(new LambdaQueryWrapper<TutorSession>()
                .eq(TutorSession::getQuestionId, questionId)
                .eq(TutorSession::getPracticeSessionId, practiceSessionId)
                .orderByDesc(TutorSession::getId)
                .last("LIMIT 1"));
        return s == null ? null : s.getSelfReason();
    }

    // ==================== 上下文拼装（固定顺序，见设计 §7.4） ====================

    private String buildQuestionContext(TutorSession session, Question q, String templateId, boolean withHistory) {
        StringBuilder sb = new StringBuilder();
        sb.append("【题目】");
        if (q.getQuestionNumber() != null) {
            sb.append("第 ").append(q.getQuestionNumber()).append(" 题");
        }
        sb.append("（").append(typeLabel(q.getQuestionType() == null ? null : q.getQuestionType().name())).append("）\n");
        sb.append(oneLine(q.getContent())).append('\n');
        List<OptionItem> options = q.getOptions() == null ? List.of() : q.getOptions();
        if (!options.isEmpty()) {
            sb.append("\n【选项】\n");
            for (OptionItem o : options) {
                sb.append(o.key()).append(". ").append(oneLine(o.text())).append('\n');
            }
        }
        sb.append("\n【正确答案】").append(answerOfQuestion(q)).append('\n');
        if (q.getAnalysis() != null && !q.getAnalysis().isBlank()) {
            sb.append("【官方解析】").append(truncate(oneLine(q.getAnalysis()), ANALYSIS_CHARS)).append('\n');
        }
        if (q.getMaterialId() != null) {
            sb.append("（此题关联了共享材料，若题干信息不足请如实说明）\n");
        }
        // 我的作答与错因
        StudyRecord last = latestRecord(session.getPracticeSessionId(), q.getId());
        if (last != null) {
            String mine = answerOfRecord(last);
            sb.append("\n【我的作答】").append(mine.isEmpty() ? "（未作答）" : mine);
            sb.append(Boolean.TRUE.equals(last.getCorrect()) ? "（正确）" : "（错误）");
            if (last.getSeconds() != null) {
                sb.append("，用时 ").append(last.getSeconds()).append(" 秒");
            }
            sb.append('\n');
        }
        if (session.getSelfReason() != null || session.getSelfNote() != null) {
            sb.append("【我的错因】").append(reasonLabel(session.getSelfReason()));
            if (session.getSelfNote() != null && !session.getSelfNote().isBlank()) {
                sb.append("——").append(oneLine(session.getSelfNote()));
            }
            sb.append('\n');
        }
        // 该题历史（错过几次、上次何时）
        List<StudyRecord> history = studyRecordMapper.selectList(new LambdaQueryWrapper<StudyRecord>()
                .eq(StudyRecord::getQuestionId, q.getId())
                .orderByDesc(StudyRecord::getAnsweredAt));
        long wrongTimes = history.stream().filter(StudyRecordService::isWrong).count();
        if (wrongTimes > 0) {
            LocalDateTime lastAt = history.stream().map(StudyRecord::getAnsweredAt)
                    .filter(java.util.Objects::nonNull).max(Comparator.naturalOrder()).orElse(null);
            sb.append("【这道题的历史】错过 ").append(wrongTimes).append(" 次");
            if (lastAt != null) {
                sb.append("，最近一次 ").append(humanAgo(lastAt));
            }
            sb.append('\n');
        }
        // 该知识点上的表现（公式算的，用"较弱/一般/较好"描述，不给数值）
        List<QuestionTaggingService.QuestionTag> tags = tagsOf(q.getId(), templateId);
        if (!tags.isEmpty()) {
            sb.append("【这道题的知识点】").append(String.join("、", tags.stream()
                    .map(QuestionTaggingService.QuestionTag::name).toList())).append('\n');
            Map<Long, List<StudyRecord>> byQuestion = recordsOfBank(session.getBankId());
            Map<String, int[]> cache = new HashMap<>();
            for (QuestionTaggingService.QuestionTag tag : tags) {
                int[] rc = cache.computeIfAbsent(tag.nodeId(),
                        k -> performanceCounts(session.getBankId(), templateId, k, byQuestion));
                String phrase = phraseOf(rc);
                if (phrase != null) {
                    sb.append("（这名做题者在「").append(tag.name()).append("」这个知识点上，整体表现：")
                            .append(phrase).append("）\n");
                }
            }
        } else {
            sb.append("【这道题的知识点】还没有标注（不要臆测具体知识点名称）\n");
        }
        if (withHistory) {
            String recent = recentDialogue(session.getId());
            if (!recent.isEmpty()) {
                sb.append("\n【最近的对话】\n").append(recent);
            }
        }
        return sb.toString();
    }

    /** 复盘会话在没有单题时（例如整场复盘后直接追问）用的上下文 */
    private String buildReviewContext(TutorSession session) {
        if (session.getPracticeSessionId() == null) {
            return "";
        }
        ReviewSummary summary = reviewSummary(session.getBankId(), session.getPracticeSessionId(), null);
        return buildReviewPrompt(summary);
    }

    private String buildReviewPrompt(ReviewSummary summary) {
        StringBuilder sb = new StringBuilder();
        sb.append("【本场练习】共 ").append(summary.total()).append(" 题，答对 ").append(summary.correct())
                .append("，答错 ").append(summary.wrong());
        if (summary.unanswered() > 0) {
            sb.append("，未作答 ").append(summary.unanswered());
        }
        sb.append('\n');
        if (!summary.byNode().isEmpty()) {
            sb.append("\n【错题按知识点分布】（已按错题数排序，数字是公式统计出来的）\n");
            for (NodeWrong n : summary.byNode()) {
                sb.append("- ").append(n.name()).append("：本场错 ").append(n.wrong()).append(" 题");
                if (n.correctRate() >= 0) {
                    sb.append("；该知识点历史正确率 ").append(n.correctRate()).append("%");
                } else {
                    sb.append("；该知识点还没有历史数据");
                }
                sb.append('\n');
            }
        }
        if (!summary.wrongItems().isEmpty()) {
            sb.append("\n【错题清单】\n");
            int idx = 1;
            for (WrongItem w : summary.wrongItems()) {
                sb.append(idx++).append(". 第 ").append(w.questionNumber() == null ? "?" : w.questionNumber())
                        .append(" 题（").append(typeLabel(w.type())).append("）");
                if (!w.nodeNames().isEmpty()) {
                    sb.append("｜知识点：").append(String.join("、", w.nodeNames()));
                }
                if (w.selfReason() != null) {
                    sb.append("｜自述错因：").append(reasonLabel(w.selfReason()));
                }
                sb.append('\n');
                sb.append("   题干：").append(w.preview()).append('\n');
                sb.append("   正确答案：").append(w.correctAnswer()).append("｜我的作答：")
                        .append(w.yourAnswer().isEmpty() ? "（未作答）" : w.yourAnswer()).append('\n');
            }
        }
        sb.append("\n请写一段复盘（中文，分四小段，不要用表格）：\n")
                .append("① 一句话总结这场表现（就事论事，不打分、不夸张）；\n")
                .append("② 指出 2–3 个最该补的知识点，并说明为什么是它们（依据上面的分布）；\n")
                .append("③ 每道错题一句话，点出错在哪（**只写上面清单里的题**，不要编造）；\n")
                .append("④ 接下来做什么：具体到知识点与动作（例如「先把增长类的公式默一遍，再做 10 题」）。\n");
        return sb.toString();
    }

    private List<TutorMessage> previousAssistantMessages(Long sessionId) {
        return messageMapper.selectList(new LambdaQueryWrapper<TutorMessage>()
                .eq(TutorMessage::getSessionId, sessionId)
                .eq(TutorMessage::getRole, TutorMessage.ROLE_ASSISTANT)
                .isNotNull(TutorMessage::getHintLevel)
                .orderByAsc(TutorMessage::getId));
    }

    /** 最近 N 轮（一问一答）对话，供追问带上下文 */
    private List<TutorMessage> recentMessages(Long sessionId) {
        List<TutorMessage> all = messageMapper.selectList(new LambdaQueryWrapper<TutorMessage>()
                .eq(TutorMessage::getSessionId, sessionId)
                .orderByAsc(TutorMessage::getId));
        int keep = HISTORY_ROUNDS * 2;
        return all.size() <= keep ? all : all.subList(all.size() - keep, all.size());
    }

    private String recentDialogue(Long sessionId) {
        StringBuilder sb = new StringBuilder();
        for (TutorMessage m : recentMessages(sessionId)) {
            sb.append(TutorMessage.ROLE_USER.equals(m.getRole()) ? "我：" : "老师：")
                    .append(truncate(oneLine(m.getContent()), 300)).append('\n');
        }
        return sb.toString();
    }

    // ==================== 落库与工具 ====================

    private TutorMessage saveUser(Long sessionId, String content) {
        TutorMessage m = new TutorMessage();
        m.setSessionId(sessionId);
        m.setRole(TutorMessage.ROLE_USER);
        m.setContent(content);
        m.setCreatedAt(LocalDateTime.now());
        messageMapper.insert(m);
        return m;
    }

    private TutorMessage saveAssistant(Long sessionId, String content, Integer hintLevel, String model) {
        TutorMessage m = new TutorMessage();
        m.setSessionId(sessionId);
        m.setRole(TutorMessage.ROLE_ASSISTANT);
        m.setContent(content == null || content.isBlank() ? "（模型没有返回内容，可再试一次）" : content);
        m.setHintLevel(hintLevel);
        m.setModel(model);
        m.setCreatedAt(LocalDateTime.now());
        messageMapper.insert(m);
        TutorSession s = sessionMapper.selectById(sessionId);
        if (s != null) {
            s.setUpdatedAt(LocalDateTime.now());
            sessionMapper.updateById(s);
        }
        return m;
    }

    private List<QuestionTaggingService.QuestionTag> tagsOf(Long questionId, String templateId) {
        if (templateId == null || templateId.isBlank() || !graphService.has(templateId)) {
            return List.of();
        }
        try {
            return taggingService.tagsOf(questionId, templateId);
        } catch (Exception e) {
            return List.of();
        }
    }

    private String nameOfNode(String nodeId, String templateId) {
        if (templateId == null || !graphService.has(templateId)) {
            return nodeId;
        }
        for (SkillGraphService.NodeView n : graphService.template(templateId).nodes()) {
            if (n.nodeId().equals(nodeId)) {
                return n.name();
            }
        }
        return nodeId;
    }

    /**
     * 该做题者在一个知识点上的整体表现（公式：历史正确率），用"较弱/一般/较好"描述。
     * 设计上**不把数值暴露给模型**，避免它拿数值编造"你的掌握度是 62%"。
     */
    private String phraseOf(int[] rc) {
        int answered = rc[0];
        if (answered < 3) {
            return "数据还太少";
        }
        double rate = rc[1] * 1.0 / answered;
        return rate >= 0.8 ? "较好" : rate >= 0.5 ? "一般" : "较弱";
    }

    /** 某题库的全部作答记录按题分组（追问上下文里算知识点表现用） */
    private Map<Long, List<StudyRecord>> recordsOfBank(Long bankId) {
        Map<Long, List<StudyRecord>> byQuestion = new HashMap<>();
        for (StudyRecord r : studyRecordMapper.selectList(new LambdaQueryWrapper<StudyRecord>()
                .eq(StudyRecord::getBankId, bankId))) {
            byQuestion.computeIfAbsent(r.getQuestionId(), k -> new ArrayList<>()).add(r);
        }
        return byQuestion;
    }

    private StudyRecord latestRecord(Long practiceSessionId, Long questionId) {
        if (practiceSessionId == null) {
            return studyRecordMapper.selectOne(new LambdaQueryWrapper<StudyRecord>()
                    .eq(StudyRecord::getQuestionId, questionId)
                    .orderByDesc(StudyRecord::getAnsweredAt)
                    .last("LIMIT 1"));
        }
        return studyRecordMapper.selectOne(new LambdaQueryWrapper<StudyRecord>()
                .eq(StudyRecord::getQuestionId, questionId)
                .eq(StudyRecord::getSessionId, practiceSessionId)
                .orderByDesc(StudyRecord::getAnsweredAt)
                .last("LIMIT 1"));
    }

    private String answerOfRecord(StudyRecord r) {
        if (r.getSelectedKeys() != null && !r.getSelectedKeys().isBlank()) {
            return r.getSelectedKeys();
        }
        return r.getUserAnswer() == null ? "" : oneLine(r.getUserAnswer());
    }

    private String answerOfQuestion(Question q) {
        if (q.getAnswerKeys() != null && !q.getAnswerKeys().isBlank()) {
            return q.getAnswerKeys();
        }
        if (q.getAnswerText() != null && !q.getAnswerText().isBlank()) {
            return oneLine(q.getAnswerText());
        }
        if (q.getReferenceAnswer() != null && !q.getReferenceAnswer().isBlank()) {
            return truncate(oneLine(q.getReferenceAnswer()), 300);
        }
        return "（题目未录入答案）";
    }

    private static String reasonLabel(String reason) {
        if (reason == null) {
            return "";
        }
        return switch (reason) {
            case TutorSession.REASON_CARELESS -> "看错/蒙的";
            case TutorSession.REASON_NO_KNOWLEDGE -> "这个知识点不会";
            case TutorSession.REASON_NEVER_SEEN -> "完全没见过";
            default -> reason;
        };
    }

    private static String typeLabel(String type) {
        if (type == null) {
            return "题目";
        }
        return switch (type) {
            case "SINGLE" -> "单选";
            case "MULTIPLE" -> "多选";
            case "JUDGE" -> "判断";
            case "SUBJECTIVE" -> "主观题";
            default -> type;
        };
    }

    private static String humanAgo(LocalDateTime at) {
        Duration d = Duration.between(at, LocalDateTime.now());
        long minutes = Math.max(1, d.toMinutes());
        if (minutes < 60) {
            return minutes + " 分钟前";
        }
        long hours = minutes / 60;
        if (hours < 24) {
            return hours + " 小时前";
        }
        return (hours / 24) + " 天前";
    }

    private static String oneLine(String s) {
        return s == null ? "" : s.replaceAll("\\s+", " ").trim();
    }

    private static String truncate(String s, int max) {
        return s == null ? "" : (s.length() <= max ? s : s.substring(0, max) + "…");
    }

    private AiSettings requireSettings() {
        if (!aiConfigService.isConfigured()) {
            throw new IllegalStateException("尚未配置 AI 模型，无法使用讲解与追问（可在设置页配置后再试）");
        }
        return aiConfigService.load();
    }

    private static final String HINT_SYSTEM = """
            你是题库应用里的讲解老师。你面对的是**正在做题的考生**，只在他主动要提示时才说话。
            原则：
            - 诚实：题干信息不足就说"这题信息不足，我需要看到配图/材料"，绝不编造；
            - 分级：严格按用户要求的那一级提示作答，不要越级给出答案；
            - 简洁：中文，不用寒暄、不重复题干、不写"首先/其次"这类填充词；
            - 数学公式用 $...$ 包裹的 LaTeX。""";

    private static final String TUTOR_SYSTEM = """
            你是题库应用里的辅导老师，正在和一名考生讨论他刚做错（或正在做）的一道题。
            原则：
            - 先回答问题本身，再补一句这类题的通用提醒；
            - 针对性：结合他说的错因（看错/不会/没见过）给不同的建议——"看错"谈审题习惯，"不会"补方法，"没见过"补概念；
            - 诚实：不确定就说不确定，不要编造题目里没有的条件；
            - 中文，简洁（一般 100–250 字），数学公式用 $...$ 包裹的 LaTeX。""";

    private static final String REVIEW_SYSTEM = """
            你是题库应用里的复盘助教。用户刚做完一场练习，你要把这场错题变成收获。
            原则：
            - 只依据用户提供的统计与错题清单，**不要编造清单以外的题目或知识点**；
            - 不打分、不夸张、不评判人格，只谈题目与知识点；
            - 结论可执行：指出最该补的知识点，并给出一个具体动作；
            - 中文，四小段，每段短句，不要用表格、不要用一级标题，数学公式用 $...$ 包裹的 LaTeX。""";

    private static final String EXPLAIN_INSTRUCTION = """
            请把这道题给我讲清楚。**严格按下面三段输出**，每段用原样的方括号小标题开头（界面要按标题分块显示）：

            【错在哪】
            针对我选的答案讲清我错在哪：我选的那个选项/我的写法为什么不对（客观题就逐项对照），
            以及我为什么会这么选（概念混了、公式用错了、还是漏看了条件）。2–4 句。

            【这类题怎么做】
            给一个**可复用的做法**：这类题的识别特征 + 固定步骤（分 2–4 点）。
            不要只讲这一道题，要让我下次遇到同类题能照着做。

            【下次防错】
            一个具体的检查动作：我下次做题时到底看什么、算什么。一句话，必须能立刻执行。

            规则：
            - 中文，不寒暄、不复述题干、不写"总的来说"这类空话；
            - 不要出现"掌握度""你还需要多练""建议你加强"这类评判与空建议；
            - 题目自带的官方解析可以参考，但必须讲透为什么，不要照抄；
            - 题干信息不足（缺材料/配图）就在【错在哪】里如实说明，绝不编造条件；
            - 数学公式用 $...$ 包裹的 LaTeX。""";

    private static final String EXPLAIN_SYSTEM = """
            你是题库应用里的讲解老师。用户点开讲解就是要一次弄明白这道题。
            原则：
            - 只讲这道题和这一类题，不讲空泛的学习方法、不打分、不评判人；
            - 结构必须严格是用户要求的那三段，标题原样保留，不增不减；
            - 诚实：题干信息不足就说明，绝不编造条件、选项或知识点名称；
            - 具体、短句，不用"首先/其次"这类填充词，数学公式用 $...$ 包裹的 LaTeX。""";

    /** 没作答过的题（题库列表里点「讲解」）：不讲"你错在哪"，讲这道题本身 */
    private static final String EXPLAIN_INSTRUCTION_NEUTRAL = """
            请把下面这道题给我讲清楚。**严格按下面三段输出**，每段用原样的方括号小标题开头（界面要按标题分块显示）：

            【这道题怎么做】
            讲清这道题的解法：关键条件是什么、走哪条思路（用哪条公式/哪个概念）、怎么一步步得到答案。2–4 句。

            【这类题怎么做】
            给一个**可复用的做法**：这类题的识别特征 + 固定步骤（分 2–4 点）。
            不要只讲这一道题，要让我下次遇到同类题能照着做。

            【易错点】
            最容易踩的坑 1–2 句：指出具体错法（比如把哪个量当成了哪个量），不要写"注意审题"这种空话。

            规则：
            - 中文，不寒暄、不复述题干、不写"总的来说"这类空话；
            - 不要出现"掌握度""你还需要多练""建议你加强"这类评判与空建议；
            - **我没有作答记录，所以不要写"你选错/你错在"**；
            - 题目自带的官方解析可以参考，但必须讲透为什么，不要照抄；
            - 题干信息不足（缺材料/配图）就在【这道题怎么做】里如实说明，绝不编造条件；
            - 数学公式用 $...$ 包裹的 LaTeX。""";
}
