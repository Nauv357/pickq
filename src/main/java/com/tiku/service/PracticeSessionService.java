package com.tiku.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.tiku.dto.*;
import com.tiku.mapper.MaterialMapper;
import com.tiku.mapper.PracticeSessionMapper;
import com.tiku.mapper.PracticeSessionQuestionMapper;
import com.tiku.mapper.QuestionMapper;
import com.tiku.mapper.ReviewStateMapper;
import com.tiku.mapper.StudyRecordMapper;
import com.tiku.model.Material;
import com.tiku.model.PracticeSession;
import com.tiku.model.Question;
import com.tiku.model.QuestionBank;
import com.tiku.model.ReviewState;
import com.tiku.model.StudyRecord;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 刷题会话（粉笔式：从题库选范围 + 数量，组成单次刷题流程）。
 * 模式：ALL 未做优先+随机补充 / SEQUENCE 按题号顺序 / TOPIC 按分类 / REVIEW 复习队列 / WRONG 错题 / FAVORITE 收藏。
 * 会话成绩不落库，从 study_record 实时聚合（避免不一致）。
 * 交卷（finish）标记完成时间并生成成绩报告（总分、总用时、每题用时）。
 */
@Service
public class PracticeSessionService {

    private static final Set<String> MODES = Set.of("ALL", "SEQUENCE", "TOPIC", "REVIEW", "WRONG", "FAVORITE");

    private final PracticeSessionMapper sessionMapper;
    private final PracticeSessionQuestionMapper sessionQuestionMapper;
    private final QuestionMapper questionMapper;
    private final StudyRecordMapper studyRecordMapper;
    private final ReviewStateMapper reviewStateMapper;
    private final MaterialMapper materialMapper;
    private final QuestionBankService questionBankService;
    private final StudyRecordService studyRecordService;

    public PracticeSessionService(PracticeSessionMapper sessionMapper,
                                  PracticeSessionQuestionMapper sessionQuestionMapper,
                                  QuestionMapper questionMapper,
                                  StudyRecordMapper studyRecordMapper,
                                  ReviewStateMapper reviewStateMapper,
                                  MaterialMapper materialMapper,
                                  QuestionBankService questionBankService,
                                  StudyRecordService studyRecordService) {
        this.sessionMapper = sessionMapper;
        this.sessionQuestionMapper = sessionQuestionMapper;
        this.questionMapper = questionMapper;
        this.studyRecordMapper = studyRecordMapper;
        this.reviewStateMapper = reviewStateMapper;
        this.materialMapper = materialMapper;
        this.questionBankService = questionBankService;
        this.studyRecordService = studyRecordService;
    }

    // ==================== 创建会话（抽题） ====================

    @Transactional
    public SessionCreateResponse createSession(Long bankId, SessionCreateRequest request) {
        questionBankService.findByIdOrThrow(bankId);
        if (request == null) {
            //空请求体（前端可能不带 body）：等价于"全部随机刷全库"
            request = new SessionCreateRequest(null, null, null, null, null, null, null, null);
        }
        String mode = normalizeMode(request.mode());

        List<Question> pool = selectPool(bankId, mode, request);
        if (pool.isEmpty()) {
            //空抽题池（题库为空 / WRONG 无错题 / REVIEW 无到期题 / FAVORITE 无收藏 / TOPIC 无匹配）：
            //拒绝创建 0 题僵尸会话，而不是把它写进练习历史
            throw new IllegalArgumentException("没有符合条件的题目，请调整练习范围");
        }

        //抽取单位：ALL/SEQUENCE 按材料整组抽取（组 = 同一 material_id 的所有题，资料分析大题干共用）；
        //其余模式（TOPIC/REVIEW/WRONG/FAVORITE）每题独立
        List<List<Question>> units = buildUnits(pool, mode);

        int startUnit = 0;
        if (!"SEQUENCE".equals(mode)) {
            if (request.startQuestionId() != null) {
                throw new IllegalArgumentException("startQuestionId 仅支持 mode=SEQUENCE（从指定题按题号顺序往后做）");
            }
            if ("ALL".equals(mode) || "TOPIC".equals(mode)) {
                //未做优先 + 随机补充（粉笔风格）：先抽没做过的，不够再随机补做过的
                //组内所有题都做过才算"做过"（部分做过的组仍整体优先抽取）
                Set<Long> doneIds = loadDoneQuestionIds(bankId);
                List<List<Question>> undoneUnits = new ArrayList<>();
                List<List<Question>> doneUnits = new ArrayList<>();
                for (List<Question> unit : units) {
                    boolean done = unit.stream().allMatch(q -> doneIds.contains(q.getId()));
                    (done ? doneUnits : undoneUnits).add(unit);
                }
                Collections.shuffle(undoneUnits);
                Collections.shuffle(doneUnits);
                units = new ArrayList<>(undoneUnits);
                units.addAll(doneUnits);
            } else {
                Collections.shuffle(units);
            }
        } else if (request.startQuestionId() != null) {
            //SEQUENCE + 起点题：从该题所在组起按顺序往后做
            startUnit = indexOfUnit(units, request.startQuestionId());
        }

        int totalRemaining = units.subList(startUnit, units.size()).stream().mapToInt(List::size).sum();
        //count 缺省：SEQUENCE 带起点时 = 剩余数量，否则 = 范围内全部
        int requested;
        if (request.count() == null || request.count() <= 0) {
            requested = totalRemaining;
        } else {
            requested = Math.min(request.count(), totalRemaining);
        }

        //按抽取单位取题：整组不拆分（累计到 requested 为止，跨越边界的组整体纳入，实际数量可能略超）
        List<Question> picked = new ArrayList<>();
        for (int i = startUnit; i < units.size() && picked.size() < requested; i++) {
            picked.addAll(units.get(i));
        }
        int count = picked.size();

        PracticeSession session = new PracticeSession();
        session.setBankId(bankId);
        session.setMode(mode);
        //多选条件以逗号拼接存储（会话记录的展示性字段）
        session.setScopeTopic(request.topic() == null ? null : String.join(",", request.topic()));
        session.setScopeCategory(request.category() == null ? null : String.join(",", request.category()));
        session.setQuestionCount(count);
        session.setCreatedAt(LocalDateTime.now());
        sessionMapper.insert(session);

        //保存抽取的题目（回顾页展示完整题目列表）
        for (int i = 0; i < picked.size(); i++) {
            sessionQuestionMapper.insert(session.getId(), picked.get(i).getId(), i);
        }

        Map<Long, String> materialContentById = loadMaterialContents(bankId);
        List<QuestionPracticeResponse> questions = picked.stream()
                .map(q -> QuestionPracticeResponse.fromEntity(q,
                        q.getMaterialId() == null ? null : materialContentById.get(q.getMaterialId())))
                .toList();
        return new SessionCreateResponse(session.getId(), count, questions);
    }

    private String normalizeMode(String mode) {
        String m = mode == null || mode.isBlank() ? "ALL" : mode.toUpperCase();
        if (!MODES.contains(m)) {
            throw new IllegalArgumentException("不支持的会话模式：" + mode + "（ALL/SEQUENCE/TOPIC/REVIEW/WRONG/FAVORITE）");
        }
        return m;
    }

    private List<Question> selectPool(Long bankId, String mode, SessionCreateRequest request) {
        LambdaQueryWrapper<Question> wrapper = new LambdaQueryWrapper<Question>().eq(Question::getBankId, bankId);
        //通用筛选（题库列表筛选联动：行内 ▶ 顺序刷题把当前列表筛选带过来，其余入口不传则不限）
        if (request.keyword() != null && !request.keyword().isBlank()) {
            String k = request.keyword().trim();
            wrapper.and(w -> w.like(Question::getContent, k).or().like(Question::getOptions, k));
        }
        if (request.questionType() != null) {
            wrapper.eq(Question::getQuestionType, request.questionType());
        }
        if (request.topic() != null && !request.topic().isEmpty()) {
            wrapper.in(Question::getTopic, request.topic());
        }
        if (request.category() != null && !request.category().isEmpty()) {
            wrapper.in(Question::getCategory, request.category());
        }
        String scope = request.scope();
        if ("favorite".equals(scope)) {
            wrapper.eq(Question::getFavorite, true);
        } else if ("wrong".equals(scope)) {
            Set<Long> wrongIds = studyRecordService.listWrongQuestionIds(bankId);
            if (wrongIds.isEmpty()) {
                return List.of();
            }
            wrapper.in(Question::getId, wrongIds);
        } else if ("undone".equals(scope)) {
            Set<Long> doneIds = loadDoneQuestionIds(bankId);
            if (!doneIds.isEmpty()) {
                wrapper.notIn(Question::getId, doneIds);
            }
        }
        switch (mode) {
            case "SEQUENCE" -> wrapper.orderByAsc(Question::getQuestionNumber).orderByAsc(Question::getId);
            case "FAVORITE" -> wrapper.eq(Question::getFavorite, true);
            case "WRONG" -> {
                Set<Long> wrongIds = studyRecordService.listWrongQuestionIds(bankId);
                if (wrongIds.isEmpty()) {
                    return List.of();
                }
                wrapper.in(Question::getId, wrongIds);
            }
            case "REVIEW" -> {
                //复习开关语义（2026-* 定稿）：关闭 = 队列暂停（不展示、不提醒），
                //内部调度（review_state）仍由作答照常推进——重新开启后到期项自然回到队列，不会"消失"；
                //因此 REVIEW 刷题入口只在开启时可用
                QuestionBank bank = questionBankService.findByIdOrThrow(bankId);
                if (!Boolean.TRUE.equals(bank.getReviewEnabled())) {
                    throw new IllegalArgumentException("复习计划未开启，请先在题库详情中开启「复习计划」");
                }
                List<Long> dueIds = reviewStateMapper.selectList(new LambdaQueryWrapper<ReviewState>()
                                .eq(ReviewState::getBankId, bankId)
                                .eq(ReviewState::getSuspended, false)
                                .le(ReviewState::getDueAt, LocalDateTime.now()))
                        .stream().map(ReviewState::getQuestionId).toList();
                if (dueIds.isEmpty()) {
                    return List.of();
                }
                wrapper.in(Question::getId, dueIds);
            }
            //TOPIC：topic/category 已按通用筛选应用（多选 in）；ALL：无额外条件
        }
        return questionMapper.selectList(wrapper);
    }

    //题库中已作答过的题目 id（"未做优先"判断依据）
    private Set<Long> loadDoneQuestionIds(Long bankId) {
        List<Object> ids = studyRecordMapper.selectObjs(
                new QueryWrapper<StudyRecord>().select("DISTINCT question_id").eq("bank_id", bankId));
        Set<Long> result = new HashSet<>();
        for (Object o : ids) {
            if (o instanceof Number n) {
                result.add(n.longValue());
            }
        }
        return result;
    }

    /**
     * ALL/SEQUENCE：材料组整组抽取（同一 material_id 的题为一组，共读大题干，组内不拆散）；
     * 无材料题（material_id=null）每题独立成组——不能把所有 null 并入同一个 Map 键，
     * 否则整库题会变成"一个大组"：count 截断失效（选 20 得全库）、shuffle 空转（顺序不随机）、
     * SEQUENCE 起点题定位恒为 0（总从第 1 题开始）。
     */
    private List<List<Question>> buildUnits(List<Question> pool, String mode) {
        if (!"ALL".equals(mode) && !"SEQUENCE".equals(mode)) {
            return pool.stream().map(List::of).collect(Collectors.toList());
        }
        List<List<Question>> units = new ArrayList<>();
        Map<Long, List<Question>> groups = new LinkedHashMap<>();
        for (Question q : pool) {
            Long materialId = q.getMaterialId();
            if (materialId == null) {
                units.add(List.of(q));
            } else {
                groups.computeIfAbsent(materialId, k -> new ArrayList<>()).add(q);
            }
        }
        units.addAll(groups.values());
        return units;
    }

    //按抽取单位顺序定位起点题（校验属于该题库）
    private int indexOfUnit(List<List<Question>> units, Long questionId) {
        for (int i = 0; i < units.size(); i++) {
            for (Question q : units.get(i)) {
                if (q.getId().equals(questionId)) {
                    return i;
                }
            }
        }
        throw new IllegalArgumentException("起点题目不属于该题库：" + questionId);
    }

    //题库材料内容映射（组内题做题/回顾页展示大题干）
    private Map<Long, String> loadMaterialContents(Long bankId) {
        return materialMapper.selectList(new LambdaQueryWrapper<Material>().eq(Material::getBankId, bankId))
                .stream().collect(Collectors.toMap(Material::getId, Material::getContent));
    }

    // ==================== 交卷（统一判分：一次性提交 + 生成成绩报告） ====================

    /**
     * 交卷（2026-09 统一判分模型）：做题中不逐题提交，交卷时一次性提交全部最终作答（answers 可选）。
     * - 行锁串行化，事务内原子完成：先清该会话该题旧记录（遗留/重试场景），再判题入库 + 复习状态联动；
     *   任一步失败整体回滚，重试不会产生半提交/重复记录
     * - 已交卷会话：携带 answers → 400 拒绝（防止交卷后追加作答）；不带 → 幂等返回同一报告
     * - 每题可携带 seconds（前端本地累计用时）落库，报告/回顾优先用它；null 时按作答时间差兜底
     */
    @Transactional
    public SessionFinishResponse finishSession(Long sessionId, SessionFinishRequest request) {
        PracticeSession session = sessionMapper.selectByIdForUpdate(sessionId);
        if (session == null) {
            throw new NoSuchElementException("会话不存在：" + sessionId);
        }
        List<SessionFinishRequest.AnswerEntry> answers = request == null ? null : request.answers();
        boolean hasAnswers = answers != null && !answers.isEmpty();
        if (session.getFinishedAt() != null) {
            if (hasAnswers) {
                throw new IllegalArgumentException("会话已交卷，无法再提交作答");
            }
            return buildFinishReport(session);
        }
        if (hasAnswers) {
            for (SessionFinishRequest.AnswerEntry answer : answers) {
                if (answer.questionId() == null) {
                    throw new IllegalArgumentException("答案缺少题目");
                }
                Question question = questionMapper.selectById(answer.questionId());
                if (question == null || !Objects.equals(question.getBankId(), session.getBankId())) {
                    throw new IllegalArgumentException("题目不属于该会话的题库");
                }
                if (sessionQuestionMapper.countBySessionAndQuestion(session.getId(), question.getId()) == 0) {
                    throw new IllegalArgumentException("题目不属于该会话，无法提交");
                }
                //清旧记录（交卷批提交 = 最终答案；防遗留/异常重试产生重复记录）
                studyRecordMapper.delete(new LambdaQueryWrapper<StudyRecord>()
                        .eq(StudyRecord::getSessionId, session.getId())
                        .eq(StudyRecord::getQuestionId, question.getId()));
                StudyRecordSubmitResponse submitted = studyRecordService.submitAnswer(
                        new StudyRecordSubmitRequest(question.getId(), answer.selectedKeys(), answer.userAnswer(),
                                session.getId()));
                if (answer.seconds() != null) {
                    StudyRecord patch = new StudyRecord();
                    patch.setId(submitted.recordId());
                    patch.setSeconds(answer.seconds());
                    studyRecordMapper.updateById(patch);
                }
            }
        }
        session.setFinishedAt(LocalDateTime.now());
        sessionMapper.updateById(session);
        return buildFinishReport(session);
    }

    private SessionFinishResponse buildFinishReport(PracticeSession session) {
        List<Question> questions = sessionQuestionMapper.selectQuestions(session.getId());
        List<StudyRecord> records = studyRecordMapper.selectList(
                new LambdaQueryWrapper<StudyRecord>()
                        .eq(StudyRecord::getSessionId, session.getId())
                        .orderByAsc(StudyRecord::getAnsweredAt));
        Map<Long, StudyRecord> recordByQuestion = records.stream()
                .collect(Collectors.toMap(StudyRecord::getQuestionId, r -> r, (a, b) -> b));
        Map<Long, Long> secondsByQuestion = computeSecondsByQuestion(session, records);

        int answered = 0;
        int correctCount = 0;
        double totalScore = 0;
        double maxScore = 0;
        List<SessionQuestionReportItem> items = new ArrayList<>();
        for (Question q : questions) {
            maxScore += q.getScore() == null ? 0 : q.getScore();
            StudyRecord rec = recordByQuestion.get(q.getId());
            if (rec != null) {
                answered++;
                //有效判定：主观题看自评（部分对按答错计）；客观题看判题结果；
                //题目未配置答案/主观未自评（correct 与 selfGrade 均 null）→ 不判对错（不计答对、得 0 分、不进错题）
                String effectiveGrade = rec.getSelfGrade() != null ? rec.getSelfGrade()
                        : rec.getCorrect() == null ? null
                        : (Boolean.TRUE.equals(rec.getCorrect()) ? "CORRECT" : "WRONG");
                double earned = StudyRecordService.earnedScore(q.getScore(), effectiveGrade, rec.getSelfScore());
                if ("CORRECT".equals(effectiveGrade)) {
                    correctCount++;
                }
                totalScore += earned;
                items.add(new SessionQuestionReportItem(q.getId(), rec.getCorrect(), q.getScore(),
                        secondsByQuestion.get(q.getId()), earned, rec.getSelfGrade()));
            } else {
                items.add(new SessionQuestionReportItem(q.getId(), null, q.getScore(), null, 0.0, null));
            }
        }
        long totalSeconds = Duration.between(session.getCreatedAt(), session.getFinishedAt()).getSeconds();
        return new SessionFinishResponse(session.getId(), questions.size(), answered, correctCount,
                totalScore, maxScore, Math.max(0, totalSeconds), items);
    }

    //每题用时：优先用交卷时前端统计并落库的 seconds；null（旧数据/单题提交）按
    //"本题作答时间 - 上一题作答时间"（首题 = 作答时间 - 会话开始时间）兜底推算
    private Map<Long, Long> computeSecondsByQuestion(PracticeSession session, List<StudyRecord> records) {
        Map<Long, Long> secondsByQuestion = new HashMap<>();
        LocalDateTime prev = session.getCreatedAt();
        for (StudyRecord r : records) {
            secondsByQuestion.put(r.getQuestionId(),
                    r.getSeconds() != null ? r.getSeconds()
                            : Duration.between(prev, r.getAnsweredAt()).getSeconds());
            prev = r.getAnsweredAt();
        }
        return secondsByQuestion;
    }

    // ==================== 会话查询 ====================

    public PageResult<SessionResponse> listSessions(Long bankId, int page, int size) {
        questionBankService.findByIdOrThrow(bankId);
        IPage<PracticeSession> sessionPage = sessionMapper.selectPage(
                new Page<>(com.tiku.util.Paging.page(page), com.tiku.util.Paging.size(size)),
                new LambdaQueryWrapper<PracticeSession>()
                        .eq(PracticeSession::getBankId, bankId)
                        .orderByDesc(PracticeSession::getCreatedAt)
                        .orderByDesc(PracticeSession::getId));
        List<SessionResponse> list = new ArrayList<>();
        for (PracticeSession s : sessionPage.getRecords()) {
            list.add(toSessionResponse(s));
        }
        return new PageResult<>(list, sessionPage.getTotal(), sessionPage.getCurrent(), sessionPage.getSize(), sessionPage.getPages());
    }

    public SessionDetailResponse getSessionDetail(Long sessionId) {
        PracticeSession session = sessionMapper.selectById(sessionId);
        if (session == null) {
            throw new NoSuchElementException("会话不存在：" + sessionId);
        }
        List<Question> questions = sessionQuestionMapper.selectQuestions(sessionId);
        List<StudyRecord> records = studyRecordMapper.selectList(
                new LambdaQueryWrapper<StudyRecord>()
                        .eq(StudyRecord::getSessionId, sessionId)
                        .orderByAsc(StudyRecord::getAnsweredAt));
        Map<Long, StudyRecord> recordByQuestion = records.stream()
                .collect(Collectors.toMap(StudyRecord::getQuestionId, r -> r, (a, b) -> b));
        Map<Long, Long> secondsByQuestion = computeSecondsByQuestion(session, records);
        boolean finished = session.getFinishedAt() != null;
        Map<Long, String> materialContentById = loadMaterialContents(session.getBankId());

        int answered = 0;
        int correctCount = 0;
        double totalScore = 0;
        double maxScore = 0;
        List<SessionQuestionItem> items = new ArrayList<>();
        for (Question q : questions) {
            maxScore += q.getScore() == null ? 0 : q.getScore();
            StudyRecord rec = recordByQuestion.get(q.getId());
            String materialContent = q.getMaterialId() == null ? null : materialContentById.get(q.getMaterialId());
            SessionQuestionItem item;
            if (rec != null) {
                answered++;
                String effectiveGrade = rec.getSelfGrade() != null ? rec.getSelfGrade()
                        : Boolean.TRUE.equals(rec.getCorrect()) ? "CORRECT" : "WRONG";
                if ("CORRECT".equals(effectiveGrade)) {
                    correctCount++;
                }
                totalScore += StudyRecordService.earnedScore(q.getScore(), effectiveGrade, rec.getSelfScore());
                //答案与解析仅在交卷后返回（避免做题中剧透）
                item = new SessionQuestionItem(
                        q.getId(), q.getQuestionType(), q.getQuestionType().getLabel(), q.getQuestionNumber(),
                        q.getContent(), q.getOptions(), q.getScore(), q.getCategory(), q.getTopic(),
                        q.getFavorite(),
                        parseKeys(rec.getSelectedKeys()), rec.getCorrect(), rec.getId(),
                        secondsByQuestion.get(q.getId()),
                        finished ? parseKeys(q.getAnswerKeys()) : null,
                        finished ? q.getAnswerText() : null,
                        finished ? q.getAnalysis() : null,
                        rec.getUserAnswer(),
                        rec.getSelfGrade(),
                        rec.getSelfScore(),
                        finished ? q.getReferenceAnswer() : null,
                        materialContent);
            } else {
                item = new SessionQuestionItem(
                        q.getId(), q.getQuestionType(), q.getQuestionType().getLabel(), q.getQuestionNumber(),
                        q.getContent(), q.getOptions(), q.getScore(), q.getCategory(), q.getTopic(),
                        q.getFavorite(),
                        null, null, null, null, null, null, null, null, null, null,
                        finished ? q.getReferenceAnswer() : null,
                        materialContent);
            }
            items.add(item);
        }
        long totalSeconds = session.getFinishedAt() != null
                ? Duration.between(session.getCreatedAt(), session.getFinishedAt()).getSeconds()
                : 0;
        return new SessionDetailResponse(session.getId(), session.getBankId(), session.getMode(),
                session.getQuestionCount(), answered, correctCount, totalScore, maxScore,
                Math.max(0, totalSeconds),
                session.getFinishedAt() == null ? "IN_PROGRESS" : "COMPLETED",
                session.getCreatedAt(), session.getFinishedAt(), items);
    }

    private SessionResponse toSessionResponse(PracticeSession session) {
        List<Question> questions = sessionQuestionMapper.selectQuestions(session.getId());
        List<StudyRecord> records = studyRecordMapper.selectList(
                new LambdaQueryWrapper<StudyRecord>().eq(StudyRecord::getSessionId, session.getId()));
        Map<Long, StudyRecord> recordByQuestion = records.stream()
                .collect(Collectors.toMap(StudyRecord::getQuestionId, r -> r, (a, b) -> b));
        int answered = 0;
        int correctCount = 0;
        double totalScore = 0;
        double maxScore = 0;
        for (Question q : questions) {
            maxScore += q.getScore() == null ? 0 : q.getScore();
            StudyRecord rec = recordByQuestion.get(q.getId());
            if (rec != null) {
                answered++;
                String effectiveGrade = rec.getSelfGrade() != null ? rec.getSelfGrade()
                        : Boolean.TRUE.equals(rec.getCorrect()) ? "CORRECT" : "WRONG";
                if ("CORRECT".equals(effectiveGrade)) {
                    correctCount++;
                }
                totalScore += StudyRecordService.earnedScore(q.getScore(), effectiveGrade, rec.getSelfScore());
            }
        }
        long totalSeconds = session.getFinishedAt() != null
                ? Duration.between(session.getCreatedAt(), session.getFinishedAt()).getSeconds()
                : 0;
        return new SessionResponse(session.getId(), session.getMode(), session.getQuestionCount(),
                answered, correctCount, totalScore, maxScore, Math.max(0, totalSeconds),
                session.getFinishedAt() == null ? "IN_PROGRESS" : "COMPLETED",
                session.getCreatedAt(), session.getFinishedAt());
    }

    // ==================== 分类聚合（会话筛选选项） ====================

    public BankCategoriesResponse getBankCategories(Long bankId) {
        questionBankService.findByIdOrThrow(bankId);
        List<String> topics = distinctColumn(bankId, "topic");
        List<String> categories = distinctColumn(bankId, "category");
        return new BankCategoriesResponse(topics, categories);
    }

    private List<String> distinctColumn(Long bankId, String column) {
        List<Object> values = questionMapper.selectObjs(new QueryWrapper<Question>()
                .select("DISTINCT " + column).eq("bank_id", bankId).isNotNull(column));
        return values.stream()
                .filter(Objects::nonNull)
                .map(Object::toString)
                .filter(s -> !s.isBlank())
                .sorted()
                .toList();
    }

    // ==================== 工具 ====================

    private List<String> parseKeys(String keys) {
        if (keys == null || keys.isBlank()) {
            return List.of();
        }
        return Arrays.stream(keys.split(",")).map(String::trim).toList();
    }
}
