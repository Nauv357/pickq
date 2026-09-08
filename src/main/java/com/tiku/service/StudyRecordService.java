package com.tiku.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.core.toolkit.support.SFunction;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tiku.dto.*;
import com.tiku.mapper.PracticeSessionMapper;
import com.tiku.mapper.PracticeSessionQuestionMapper;
import com.tiku.mapper.QuestionBankMapper;
import com.tiku.mapper.QuestionMapper;
import com.tiku.mapper.ReviewStateMapper;
import com.tiku.mapper.StudyRecordMapper;
import com.tiku.model.PracticeSession;
import com.tiku.model.Question;
import com.tiku.model.QuestionBank;
import com.tiku.model.ReviewState;
import com.tiku.model.StudyRecord;
import com.tiku.model.StudyRecordFile;
import com.tiku.model.StudyRecordFileItem;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 刷题闭环：刷题记录（纯本地）、错题、学习进度。
 * - 错题 = 每道题最近一次作答为错误的题目集合（最近答对则移出错题）
 * - 进度 = 已答题数 / 总题数 + 正确率
 * 记录文件导入导出按 study-record-spec.md 格式，用于本地备份与换设备迁移。
 */
@Service
public class StudyRecordService {

    private final StudyRecordMapper studyRecordMapper;
    private final QuestionMapper questionMapper;
    private final QuestionBankMapper questionBankMapper;
    private final ReviewStateMapper reviewStateMapper;
    private final PracticeSessionMapper sessionMapper;
    private final PracticeSessionQuestionMapper sessionQuestionMapper;
    private final QuestionService questionService;
    private final QuestionBankService questionBankService;
    private final ObjectMapper objectMapper;

    public StudyRecordService(StudyRecordMapper studyRecordMapper,
                              QuestionMapper questionMapper,
                              QuestionBankMapper questionBankMapper,
                              ReviewStateMapper reviewStateMapper,
                              PracticeSessionMapper sessionMapper,
                              PracticeSessionQuestionMapper sessionQuestionMapper,
                              QuestionService questionService,
                              QuestionBankService questionBankService,
                              ObjectMapper objectMapper) {
        this.studyRecordMapper = studyRecordMapper;
        this.questionMapper = questionMapper;
        this.questionBankMapper = questionBankMapper;
        this.reviewStateMapper = reviewStateMapper;
        this.sessionMapper = sessionMapper;
        this.sessionQuestionMapper = sessionQuestionMapper;
        this.questionService = questionService;
        this.questionBankService = questionBankService;
        this.objectMapper = objectMapper;
    }

    // ==================== 提交作答（判题 + 记录 + 复习状态联动） ====================

    @Transactional
    public StudyRecordSubmitResponse submitAnswer(StudyRecordSubmitRequest request) {
        Question question = questionMapper.selectById(request.questionId());
        if (question == null) {
            throw new NoSuchElementException("题目不存在：" + request.questionId());
        }
        //会话内提交时校验：会话存在、与题目同题库、未交卷、且题目属于该会话抽取列表
        //（防止交卷后追加作答产生游离记录污染统计；主观题交卷后自评走 self-grade 接口，不受此限制）
        if (request.sessionId() != null) {
            PracticeSession session = sessionMapper.selectById(request.sessionId());
            if (session == null) {
                throw new NoSuchElementException("会话不存在：" + request.sessionId());
            }
            if (!session.getBankId().equals(question.getBankId())) {
                throw new IllegalArgumentException("题目不属于该会话的题库");
            }
            if (session.getFinishedAt() != null) {
                throw new IllegalArgumentException("会话已交卷，无法再提交作答（主观题可到回顾页自评赋分）");
            }
            if (sessionQuestionMapper.countBySessionAndQuestion(session.getId(), question.getId()) == 0) {
                throw new IllegalArgumentException("题目不属于该会话，无法提交");
            }
        }

        StudyRecord record = new StudyRecord();
        record.setBankId(question.getBankId());
        record.setQuestionId(question.getId());
        record.setQuestionKey(question.getExternalId());
        record.setSessionId(request.sessionId());
        record.setAnsweredAt(LocalDateTime.now());

        //主观题：不自动判题，记录用户作答等待自评（正确性由 selfGrade 决定，此时不联动复习状态）
        if (question.getQuestionType() == com.tiku.model.enums.QuestionType.SUBJECTIVE) {
            if (request.userAnswer() == null || request.userAnswer().isBlank()) {
                throw new IllegalArgumentException("主观题作答内容不能为空");
            }
            record.setUserAnswer(request.userAnswer());
            record.setCorrect(null);
            record.setSelfGrade(null);
            studyRecordMapper.insert(record);
            return new StudyRecordSubmitResponse(null, List.of(), null, null, record.getId(), null);
        }

        //客观题：判题 + 记录 + 复习状态联动
        if (request.selectedKeys() == null || request.selectedKeys().isEmpty()) {
            throw new IllegalArgumentException("答案不能为空");
        }
        record.setSelectedKeys(String.join(",", request.selectedKeys()));
        //题目尚未配置答案：不判题（correct=null 待定，等同主观未自评——不判对错、不进错题/复习），提示可后补
        if (question.getAnswerKeys() == null || question.getAnswerKeys().isBlank()) {
            record.setCorrect(null);
            studyRecordMapper.insert(record);
            return new StudyRecordSubmitResponse(null, List.of(), null, null, record.getId(),
                    "本题尚未配置答案：作答已记录但无法判定对错，可到题库编辑该题后补答案（补后下次作答自动判题）");
        }
        AnswerResultResponse result = questionService.checkAnswer(request.questionId(), request.selectedKeys());
        record.setCorrect(result.correct());
        studyRecordMapper.insert(record);

        updateReviewState(question, result.correct());

        return new StudyRecordSubmitResponse(
                result.correct(), result.correctKeys(), result.correctText(), result.analysis(), record.getId(), null);
    }

    //主观题自评：自由给分（0 ~ 题目满分），后端派生三档供对错统计（满分=对；非满分按答错参与错题本/复习）
    @Transactional
    public SelfGradeResponse selfGrade(Long recordId, Double earnedScore) {
        StudyRecord record = studyRecordMapper.selectById(recordId);
        if (record == null) {
            throw new NoSuchElementException("刷题记录不存在：" + recordId);
        }
        Question question = questionMapper.selectById(record.getQuestionId());
        if (question == null) {
            throw new NoSuchElementException("题目不存在：" + record.getQuestionId());
        }
        if (question.getQuestionType() != com.tiku.model.enums.QuestionType.SUBJECTIVE) {
            throw new IllegalArgumentException("客观题自动判题，无需自评");
        }
        double max = question.getScore() == null ? 0 : question.getScore();
        if (earnedScore == null || earnedScore < 0 || earnedScore > max + 1e-9) {
            throw new IllegalArgumentException("自评得分需在 0 ~ " + max + " 分之间（满分 " + max + " 分）");
        }
        double earned = Math.min(max, earnedScore);
        //派生档位：对错统计沿用三档（非满分一律按答错计，进错题本/复习）
        String grade = earned >= max - 1e-9 ? "CORRECT" : earned <= 1e-9 ? "WRONG" : "PARTIAL";
        record.setSelfScore(earned);
        record.setSelfGrade(grade);
        studyRecordMapper.updateById(record);
        //非满分按答错处理：level 归零、立即可复习（与设计文档一致）
        updateReviewState(question, "CORRECT".equals(grade));
        return new SelfGradeResponse(record.getId(), grade, earned);
    }

    /** 单题应得分：自由给分优先（selfScore 非空 → 实得分），否则按自评档映射（对=score，部分对=score/2，错/未自评=0） */
    public static double earnedScore(Double score, String selfGrade, Double selfScore) {
        if (selfScore != null && score != null) {
            return Math.max(0, Math.min(score, selfScore));
        }
        return earnedScore(score, selfGrade);
    }

    /** 单题应得分（旧口径：按自评档映射） */
    public static double earnedScore(Double score, String selfGrade) {
        if (score == null) {
            return 0;
        }
        return switch (selfGrade == null ? "" : selfGrade) {
            case "CORRECT" -> score;
            case "PARTIAL" -> score / 2.0;
            default -> 0.0;
        };
    }

    /** 最近一次作答是否算"错"：客观题判错；主观题自评 PARTIAL/WRONG 算错；未自评待判定不算错 */
    private static boolean isWrong(StudyRecord record) {
        if (record.getSelfGrade() != null) {
            return !"CORRECT".equals(record.getSelfGrade());
        }
        if (record.getCorrect() != null) {
            return !Boolean.TRUE.equals(record.getCorrect());
        }
        return false;
    }

    /**
     * 统一"错题"判定口径（错题本 / 会话 WRONG / 题目列表 scope=wrong / 导出范围 wrong 共用）：
     * 每道题"最近一次作答为错"才进错题集合——历史答错但最近已答对的题不算；
     * 主观题自评 PARTIAL/WRONG 算错（is_correct 为 null，不能只看该列），未自评不算错。
     */
    public static Set<Long> computeWrongQuestionIds(List<StudyRecord> records) {
        Set<Long> wrongIds = new LinkedHashSet<>();
        if (records == null || records.isEmpty()) {
            return wrongIds;
        }
        Map<Long, StudyRecord> latestByQuestion = records.stream().collect(Collectors.toMap(
                StudyRecord::getQuestionId, r -> r,
                (a, b) -> (b.getAnsweredAt() != null
                        && (a.getAnsweredAt() == null || b.getAnsweredAt().isAfter(a.getAnsweredAt()))) ? b : a,
                LinkedHashMap::new));
        latestByQuestion.forEach((questionId, latest) -> {
            if (isWrong(latest)) {
                wrongIds.add(questionId);
            }
        });
        return wrongIds;
    }

    //简化间隔重复：答对 level+1（封顶5）间隔=min(2^level,30)天；答错 level 归0、due=now（立即可复习）
    private void updateReviewState(Question question, boolean correct) {
        ReviewState existing = reviewStateMapper.selectById(question.getId());
        ReviewState state = existing;
        if (state == null) {
            state = new ReviewState();
            state.setQuestionId(question.getId());
            state.setBankId(question.getBankId());
            state.setLevel(0);
            state.setIntervalDays(1);
            state.setSuspended(false);
        }
        if (correct) {
            state.setLevel(Math.min(state.getLevel() + 1, 5));
            state.setIntervalDays(Math.min(1 << state.getLevel(), 30));
            state.setDueAt(LocalDateTime.now().plusDays(state.getIntervalDays()));
        } else {
            state.setLevel(0);
            state.setIntervalDays(1);
            state.setDueAt(LocalDateTime.now());
        }
        if (existing == null) {
            reviewStateMapper.insert(state);
        } else {
            reviewStateMapper.updateById(state);
        }
    }

    // ==================== 记录查询 ====================

    public PageResult<StudyRecordResponse> listBankRecords(Long bankId, int page, int size) {
        questionBankService.findByIdOrThrow(bankId);
        IPage<StudyRecord> recordPage = studyRecordMapper.selectPage(
                new Page<>(com.tiku.util.Paging.page(page), com.tiku.util.Paging.size(size)),
                new LambdaQueryWrapper<StudyRecord>()
                        .eq(StudyRecord::getBankId, bankId)
                        .orderByDesc(StudyRecord::getAnsweredAt)
                        .orderByDesc(StudyRecord::getId));
        return PageResult.from(recordPage.convert(StudyRecordResponse::fromEntity));
    }

    public PageResult<StudyRecordResponse> listQuestionRecords(Long questionId, int page, int size) {
        Question question = questionMapper.selectById(questionId);
        if (question == null) {
            throw new NoSuchElementException("题目不存在：" + questionId);
        }
        IPage<StudyRecord> recordPage = studyRecordMapper.selectPage(
                new Page<>(com.tiku.util.Paging.page(page), com.tiku.util.Paging.size(size)),
                new LambdaQueryWrapper<StudyRecord>()
                        .eq(StudyRecord::getQuestionId, questionId)
                        .orderByDesc(StudyRecord::getAnsweredAt)
                        .orderByDesc(StudyRecord::getId));
        return PageResult.from(recordPage.convert(StudyRecordResponse::fromEntity));
    }

    // ==================== 错题 ====================

    //错题 = 每道题最近一次作答错误的题目（最近一次答对则移出错题集合）
    //本地数据量小，全量取回后在内存中分组取最近记录并分页
    public PageResult<WrongQuestionResponse> listWrongQuestions(Long bankId, int page, int size) {
        questionBankService.findByIdOrThrow(bankId);
        page = com.tiku.util.Paging.page(page);
        size = com.tiku.util.Paging.size(size);
        Map<Long, StudyRecord> latestByQuestion = buildLatestByQuestion(bankId);
        //每题错误总次数（主观题 PARTIAL 也算错）
        List<StudyRecord> records = studyRecordMapper.selectList(
                new LambdaQueryWrapper<StudyRecord>().eq(StudyRecord::getBankId, bankId));
        Map<Long, Long> wrongCountByQuestion = records.stream()
                .filter(StudyRecordService::isWrong)
                .collect(Collectors.groupingBy(StudyRecord::getQuestionId, Collectors.counting()));

        List<WrongQuestionResponse> wrongs = new ArrayList<>();
        for (Map.Entry<Long, StudyRecord> entry : latestByQuestion.entrySet()) {
            StudyRecord latest = entry.getValue();
            if (!isWrong(latest)) {
                continue; //最近一次已答对（或主观题未自评待判定），移出错题
            }
            Question question = questionMapper.selectById(entry.getKey());
            if (question == null) {
                continue; //题目已被删除，错题项跳过（历史记录仍保留）
            }
            wrongs.add(WrongQuestionResponse.fromEntity(
                    question,
                    parseKeys(latest.getSelectedKeys()),
                    latest.getUserAnswer(),
                    latest.getSelfGrade(),
                    latest.getAnsweredAt(),
                    wrongCountByQuestion.getOrDefault(entry.getKey(), 0L).intValue()));
        }

        //按"最近一次答错时间"倒序（最新错的最先），再内存分页
        wrongs.sort(java.util.Comparator.comparing(
                WrongQuestionResponse::lastAnsweredAt,
                java.util.Comparator.nullsLast(java.util.Comparator.reverseOrder())));
        int total = wrongs.size();
        int from = Math.min((page - 1) * size, total);
        int to = Math.min(from + size, total);
        List<WrongQuestionResponse> pageRecords = from >= total ? List.of() : wrongs.subList(from, to);
        long pages = total == 0 ? 0 : (long) Math.ceil(total * 1.0 / size);
        return new PageResult<>(pageRecords, (long) total, (long) page, (long) size, pages);
    }

    //每题最近一次作答记录（供错题计算与会话 WRONG 模式复用）
    private Map<Long, StudyRecord> buildLatestByQuestion(Long bankId) {
        List<StudyRecord> records = studyRecordMapper.selectList(
                new LambdaQueryWrapper<StudyRecord>()
                        .eq(StudyRecord::getBankId, bankId)
                        .orderByAsc(StudyRecord::getQuestionId)
                        .orderByDesc(StudyRecord::getAnsweredAt));
        Map<Long, StudyRecord> latestByQuestion = new LinkedHashMap<>();
        for (StudyRecord r : records) {
            latestByQuestion.putIfAbsent(r.getQuestionId(), r);
        }
        return latestByQuestion;
    }

    //最近一次作答错误的题目 id 集合（会话 WRONG 模式抽题用；主观题 PARTIAL 也算错）
    public Set<Long> listWrongQuestionIds(Long bankId) {
        List<StudyRecord> records = studyRecordMapper.selectList(
                new LambdaQueryWrapper<StudyRecord>().eq(StudyRecord::getBankId, bankId));
        return computeWrongQuestionIds(records);
    }

    // ==================== 复习队列 ====================

    //待复习 = due_at <= now 且未暂停的题（答错 due=now 立即可复习；到期题答对也复习防遗忘）
    //复习开关语义（与产品确认）：关闭 = 队列"暂停"——不展示、不提醒，但内部调度（review_state）
    //仍由作答照常推进（到期日不会消失）；重新开启后到期项自然回到队列（含积压，逾期天数可区分）。
    public PageResult<ReviewDueItemResponse> listReviewDue(Long bankId, int page, int size) {
        QuestionBank bank = questionBankService.findByIdOrThrow(bankId);
        if (!Boolean.TRUE.equals(bank.getReviewEnabled())) {
            return new PageResult<>(List.of(), 0L, (long) page, (long) size, 0L);
        }
        LocalDateTime now = LocalDateTime.now();
        IPage<ReviewState> statePage = reviewStateMapper.selectPage(
                new Page<>(com.tiku.util.Paging.page(page), com.tiku.util.Paging.size(size)),
                new LambdaQueryWrapper<ReviewState>()
                        .eq(ReviewState::getBankId, bankId)
                        .eq(ReviewState::getSuspended, false)
                        .le(ReviewState::getDueAt, now)
                        .orderByAsc(ReviewState::getDueAt));
        List<ReviewDueItemResponse> items = new ArrayList<>();
        for (ReviewState s : statePage.getRecords()) {
            Question question = questionMapper.selectById(s.getQuestionId());
            if (question == null) {
                continue; //题目已删（逻辑删除自动过滤），跳过
            }
            items.add(ReviewDueItemResponse.fromEntity(question, s.getLevel(), s.getIntervalDays(), s.getDueAt(),
                    overdueDays(s.getDueAt())));
        }
        return new PageResult<>(items, statePage.getTotal(), statePage.getCurrent(), statePage.getSize(), statePage.getPages());
    }

    /** 复习队列摘要（徽标计数/开启提示）：dueTotal 含积压；overdueTotal = 到期日早于今天的历史欠账 */
    public ReviewSummaryResponse getReviewSummary(Long bankId) {
        QuestionBank bank = questionBankService.findByIdOrThrow(bankId);
        if (!Boolean.TRUE.equals(bank.getReviewEnabled())) {
            return new ReviewSummaryResponse(0, 0);
        }
        long dueTotal = reviewStateMapper.selectCount(new LambdaQueryWrapper<ReviewState>()
                .eq(ReviewState::getBankId, bankId)
                .eq(ReviewState::getSuspended, false)
                .le(ReviewState::getDueAt, LocalDateTime.now()));
        long overdueTotal = reviewStateMapper.selectCount(new LambdaQueryWrapper<ReviewState>()
                .eq(ReviewState::getBankId, bankId)
                .eq(ReviewState::getSuspended, false)
                .lt(ReviewState::getDueAt, LocalDate.now().atStartOfDay()));
        return new ReviewSummaryResponse(dueTotal, overdueTotal);
    }

    /** 重置复习计划：清空该题库全部复习状态（含暂停标记），作答记录与错题本不受影响；返回清除条数 */
    public int resetReviewStates(Long bankId) {
        questionBankService.findByIdOrThrow(bankId);
        return reviewStateMapper.delete(new LambdaQueryWrapper<ReviewState>()
                .eq(ReviewState::getBankId, bankId));
    }

    /** 逾期天数：0 = 今天到期/未到期；N = 到期日距今 N 天前（按自然日，非 24 小时） */
    private static long overdueDays(LocalDateTime dueAt) {
        if (dueAt == null) {
            return 0;
        }
        return Math.max(0, java.time.temporal.ChronoUnit.DAYS.between(dueAt.toLocalDate(), LocalDate.now()));
    }

    //暂停/恢复复习（suspended 的题不进待复习队列，可随时恢复）
    public void setReviewSuspended(Long questionId, boolean suspended) {
        Question question = questionMapper.selectById(questionId);
        if (question == null) {
            throw new NoSuchElementException("题目不存在：" + questionId);
        }
        ReviewState existing = reviewStateMapper.selectById(questionId);
        ReviewState state = existing;
        if (state == null) {
            //从未作答的题也可以直接暂停（防止将来作答后进入队列）
            state = new ReviewState();
            state.setQuestionId(questionId);
            state.setBankId(question.getBankId());
            state.setLevel(0);
            state.setIntervalDays(1);
            state.setDueAt(LocalDateTime.now());
        }
        state.setSuspended(suspended);
        if (existing == null) {
            reviewStateMapper.insert(state);
        } else {
            reviewStateMapper.updateById(state);
        }
    }

    // ==================== 学习进度 ====================

    public BankProgressResponse getBankProgress(Long bankId) {
        questionBankService.findByIdOrThrow(bankId);
        long totalQuestions = questionMapper.selectCount(
                new LambdaQueryWrapper<Question>().eq(Question::getBankId, bankId));
        long recordsCount = studyRecordMapper.selectCount(
                new LambdaQueryWrapper<StudyRecord>().eq(StudyRecord::getBankId, bankId));
        //答对 = 客观题判对，或主观题自评 CORRECT（部分对/错/未自评不计入）
        long correctCount = studyRecordMapper.selectCount(
                new LambdaQueryWrapper<StudyRecord>()
                        .eq(StudyRecord::getBankId, bankId)
                        .and(w -> w.eq(StudyRecord::getCorrect, true).or().eq(StudyRecord::getSelfGrade, "CORRECT")));
        List<Object> answeredIds = studyRecordMapper.selectObjs(
                new QueryWrapper<StudyRecord>().select("DISTINCT question_id").eq("bank_id", bankId));
        //只统计仍存在的题目：记录含已删题的作答（单题删除不删记录），
        //若把已删题计入"已做"，删除题目后完成度会超过 100%
        long answeredQuestions = answeredIds.isEmpty() ? 0
                : questionMapper.selectCount(new LambdaQueryWrapper<Question>().in(Question::getId, answeredIds));

        double accuracy = recordsCount == 0 ? 0 : (double) correctCount / recordsCount;
        int progressPercent = totalQuestions == 0 ? 0 : (int) Math.round(answeredQuestions * 100.0 / totalQuestions);
        return new BankProgressResponse(bankId, totalQuestions, answeredQuestions, recordsCount,
                correctCount, accuracy, progressPercent);
    }

    // ==================== 记录文件导出 / 导入 ====================

    public StudyRecordFile exportRecords() {
        List<StudyRecord> records = studyRecordMapper.selectList(
                new LambdaQueryWrapper<StudyRecord>().orderByAsc(StudyRecord::getAnsweredAt));
        List<StudyRecordFileItem> items = new ArrayList<>();
        for (StudyRecord r : records) {
            QuestionBank bank = questionBankMapper.selectById(r.getBankId());
            StudyRecordFileItem item = new StudyRecordFileItem();
            //自建题库在首次"完整导出内容包"前没有内容包身份，导出为空串（导入时无法定位，会提示）；
            //完整导出一次后导出流程会"认领身份"回写题库（见 ContentPackageService），此后记录即可跨设备迁移
            item.setPackageKey(bank == null || bank.getPackageKey() == null ? "" : bank.getPackageKey());
            item.setPackageVersion(bank == null || bank.getVersion() == null ? "" : bank.getVersion());
            item.setQuestionKey(r.getQuestionKey());
            item.setSelectedKeys(parseKeys(r.getSelectedKeys()));
            item.setIsCorrect(r.getCorrect());
            item.setUserAnswer(r.getUserAnswer());
            item.setSelfGrade(r.getSelfGrade());
            item.setAnsweredAt(r.getAnsweredAt());
            items.add(item);
        }
        StudyRecordFile file = new StudyRecordFile();
        file.setSchemaVersion(1);
        file.setExportedAt(LocalDateTime.now());
        file.setRecords(items);
        return file;
    }

    @Transactional
    public RecordImportResultResponse importRecords(String json) {
        StudyRecordFile file;
        try {
            file = objectMapper.readValue(json, StudyRecordFile.class);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("刷题记录文件格式错误：" + e.getOriginalMessage());
        }
        if (file.getSchemaVersion() == null || file.getSchemaVersion() != 1) {
            throw new IllegalArgumentException("不支持的记录文件格式版本：" + file.getSchemaVersion());
        }
        int imported = 0;
        int skippedDuplicates = 0;
        Set<String> missingBanks = new LinkedHashSet<>();
        Set<String> missingQuestions = new LinkedHashSet<>();
        //导入过程中遇到"无复习状态"的题目 → 导入完成后按其全部作答记录时间序重放，
        //重建复习调度（换机迁移后"今日待复习"不再为空；已在本机作答过的题保留现有状态，不重放）
        Set<Long> rebuildQuestionIds = new LinkedHashSet<>();
        if (file.getRecords() != null) {
            for (StudyRecordFileItem item : file.getRecords()) {
                //自建题库或无身份记录无法按内容包身份定位
                if (item.getPackageKey() == null || item.getPackageKey().isBlank()) {
                    missingBanks.add("(无内容包身份的题库记录)");
                    continue;
                }
                QuestionBank bank = questionBankMapper.selectOne(new LambdaQueryWrapper<QuestionBank>()
                        .eq(QuestionBank::getPackageKey, item.getPackageKey())
                        .eq(QuestionBank::getVersion, item.getPackageVersion()));
                if (bank == null) {
                    missingBanks.add(item.getPackageKey() + " v" + item.getPackageVersion());
                    continue;
                }
                Question question = questionMapper.selectOne(new LambdaQueryWrapper<Question>()
                        .eq(Question::getBankId, bank.getId())
                        .eq(Question::getExternalId, item.getQuestionKey()));
                if (question == null) {
                    missingQuestions.add(item.getQuestionKey());
                    continue;
                }
                StudyRecord record = new StudyRecord();
                record.setBankId(bank.getId());
                record.setQuestionId(question.getId());
                record.setQuestionKey(question.getExternalId());
                record.setSelectedKeys(item.getSelectedKeys() == null ? null : String.join(",", item.getSelectedKeys()));
                record.setCorrect(item.getIsCorrect());
                record.setUserAnswer(item.getUserAnswer());
                record.setSelfGrade(item.getSelfGrade());
                record.setAnsweredAt(item.getAnsweredAt() == null ? LocalDateTime.now() : item.getAnsweredAt());
                //幂等：与已存在记录一致（同题/同时刻窗口/同答案/同判定）则跳过——防止同一记录文件重复导入导致统计翻倍。
                //answeredAt 用"秒级窗口"比较：H2 TIMESTAMP 微秒精度与文件/JSON 精度往返可能截断，
                //精确 eq 会因表示差异漏判（实测同一文件二次导入全部重复插入）；
                //可空字段（selectedKeys/correct/userAnswer/selfGrade）不能用 eq(col, null)（会生成 = NULL 永假），
                //须显式 isNull/eq 分支
                LocalDateTime answeredAt = record.getAnsweredAt() == null ? LocalDateTime.now() : record.getAnsweredAt();
                record.setAnsweredAt(answeredAt);
                LocalDateTime atFloor = answeredAt.withNano(0);
                LambdaQueryWrapper<StudyRecord> dupWrapper = new LambdaQueryWrapper<StudyRecord>()
                        .eq(StudyRecord::getBankId, record.getBankId())
                        .eq(StudyRecord::getQuestionId, record.getQuestionId())
                        .ge(StudyRecord::getAnsweredAt, atFloor)
                        .lt(StudyRecord::getAnsweredAt, atFloor.plusSeconds(1));
                appendNullableEqual(dupWrapper, StudyRecord::getSelectedKeys, record.getSelectedKeys());
                appendNullableEqual(dupWrapper, StudyRecord::getCorrect, record.getCorrect());
                appendNullableEqual(dupWrapper, StudyRecord::getUserAnswer, record.getUserAnswer());
                appendNullableEqual(dupWrapper, StudyRecord::getSelfGrade, record.getSelfGrade());
                if (studyRecordMapper.selectCount(dupWrapper) > 0) {
                    skippedDuplicates++;
                    continue;
                }
                studyRecordMapper.insert(record);
                imported++;
                //该题此前没有复习状态（本机从未作答/从未导入）→ 标记为需要重放重建
                if (!rebuildQuestionIds.contains(question.getId())
                        && reviewStateMapper.selectById(question.getId()) == null) {
                    rebuildQuestionIds.add(question.getId());
                }
            }
        }
        //按作答时间序重放"无状态题"的记录，重建复习调度（与线上作答同一状态机；主观题未自评不参与）
        for (Long questionId : rebuildQuestionIds) {
            Question question = questionMapper.selectById(questionId);
            if (question == null) {
                continue;
            }
            List<StudyRecord> questionRecords = studyRecordMapper.selectList(
                    new LambdaQueryWrapper<StudyRecord>()
                            .eq(StudyRecord::getQuestionId, questionId)
                            .orderByAsc(StudyRecord::getAnsweredAt)
                            .orderByAsc(StudyRecord::getId));
            for (StudyRecord r : questionRecords) {
                if (r.getCorrect() != null) {
                    updateReviewState(question, r.getCorrect());
                } else if (r.getSelfGrade() != null) {
                    updateReviewState(question, "CORRECT".equals(r.getSelfGrade()));
                }
            }
        }
        return new RecordImportResultResponse(imported, skippedDuplicates,
                new ArrayList<>(missingBanks), new ArrayList<>(missingQuestions));
    }

    // ==================== 工具 ====================

    /** 可空字段相等条件：null → IS NULL；非 null → 等值（eq(col, null) 会生成 = NULL 永假，不可用） */
    private static <T> void appendNullableEqual(LambdaQueryWrapper<StudyRecord> wrapper,
                                                SFunction<StudyRecord, T> column, T value) {
        if (value == null) {
            wrapper.isNull(column);
        } else {
            wrapper.eq(column, value);
        }
    }

    private List<String> parseKeys(String keys) {
        if (keys == null || keys.isBlank()) {
            return List.of();
        }
        return Arrays.stream(keys.split(",")).map(String::trim).toList();
    }
}
