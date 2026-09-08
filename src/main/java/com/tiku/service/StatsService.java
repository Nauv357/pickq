package com.tiku.service;

import com.tiku.dto.StatsDetailResponse;
import com.tiku.dto.StatsSummaryResponse;
import com.tiku.mapper.PracticeSessionMapper;
import com.tiku.mapper.QuestionBankMapper;
import com.tiku.mapper.QuestionMapper;
import com.tiku.mapper.ReviewStateMapper;
import com.tiku.mapper.StudyRecordMapper;
import com.tiku.model.PracticeSession;
import com.tiku.model.Question;
import com.tiku.model.QuestionBank;
import com.tiku.model.ReviewState;
import com.tiku.model.StudyRecord;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;

/**
 * 学习统计聚合（A6）：全量作答/复习状态内存聚合（个人本地量级，单次全表读取）。
 * 口径见 StatsSummaryResponse / StatsDetailResponse 注释。
 */
@Service
public class StatsService {

    private final StudyRecordMapper studyRecordMapper;
    private final ReviewStateMapper reviewStateMapper;
    private final QuestionMapper questionMapper;
    private final QuestionBankMapper questionBankMapper;
    private final PracticeSessionMapper practiceSessionMapper;

    public StatsService(StudyRecordMapper studyRecordMapper,
                        ReviewStateMapper reviewStateMapper,
                        QuestionMapper questionMapper,
                        QuestionBankMapper questionBankMapper,
                        PracticeSessionMapper practiceSessionMapper) {
        this.studyRecordMapper = studyRecordMapper;
        this.reviewStateMapper = reviewStateMapper;
        this.questionMapper = questionMapper;
        this.questionBankMapper = questionBankMapper;
        this.practiceSessionMapper = practiceSessionMapper;
    }

    public StatsSummaryResponse summary() {
        LocalDate today = LocalDate.now();

        //========== 作答记录：按本地日期聚合（count / decided / correct） ==========
        //日期 → [作答数, 有判定数, 判对数]
        Map<LocalDate, int[]> dayAgg = new LinkedHashMap<>();
        int totalAnswered = 0;
        int decidedTotal = 0;
        int correctTotal = 0;
        long totalSeconds = 0;
        TreeSet<LocalDate> activeDays = new TreeSet<>();

        List<StudyRecord> records = studyRecordMapper.selectList(null);
        for (StudyRecord r : records) {
            if (r.getAnsweredAt() == null) {
                continue;
            }
            LocalDate d = r.getAnsweredAt().toLocalDate();
            int[] agg = dayAgg.computeIfAbsent(d, k -> new int[3]);
            agg[0]++;
            totalAnswered++;
            if (r.getSeconds() != null) {
                totalSeconds += r.getSeconds();
            }
            Boolean decided = decide(r);
            if (decided != null) {
                agg[1]++;
                agg[2] += decided ? 1 : 0;
                decidedTotal++;
                if (decided) {
                    correctTotal++;
                }
            }
            activeDays.add(d);
        }

        //========== 连续学习天数（今天有作答则含今天，否则从昨天起算） ==========
        int streakDays = 0;
        LocalDate cursor = activeDays.contains(today) ? today : today.minusDays(1);
        while (activeDays.contains(cursor)) {
            streakDays++;
            cursor = cursor.minusDays(1);
        }
        int longestStreak = 0;
        int run = 0;
        LocalDate prev = null;
        for (LocalDate d : activeDays) {
            if (prev != null && d.equals(prev.plusDays(1))) {
                run++;
            } else {
                run = 1;
            }
            if (run > longestStreak) {
                longestStreak = run;
            }
            prev = d;
        }

        //========== 日粒度输出（最近 365 天补零） ==========
        List<StatsSummaryResponse.DailyStat> daily = new ArrayList<>(365);
        for (int i = 364; i >= 0; i--) {
            LocalDate d = today.minusDays(i);
            int[] agg = dayAgg.get(d);
            if (agg == null) {
                daily.add(new StatsSummaryResponse.DailyStat(d.toString(), 0, 0, 0));
            } else {
                daily.add(new StatsSummaryResponse.DailyStat(d.toString(), agg[0], agg[1], agg[2]));
            }
        }

        //========== 复习状态：到期/逾期/预测/熟练度分布 ==========
        int dueToday = 0;
        int overdue = 0;
        int reviewQuestionCount = 0;
        int[] levelDist = new int[6]; //level 0..5（null 视为 0）
        //日期 → 到期数（今天起 30 天）
        Map<LocalDate, Integer> dueByDate = new HashMap<>();
        List<ReviewState> states = reviewStateMapper.selectList(null);
        for (ReviewState s : states) {
            if (Boolean.TRUE.equals(s.getSuspended()) || s.getDueAt() == null) {
                continue;
            }
            reviewQuestionCount++;
            int level = s.getLevel() == null ? 0 : Math.max(0, Math.min(5, s.getLevel()));
            levelDist[level]++;
            LocalDate due = s.getDueAt().toLocalDate();
            if (!due.isAfter(today)) {
                dueToday++;
                if (due.isBefore(today)) {
                    overdue++;
                }
            } else if (!due.isAfter(today.plusDays(29))) {
                dueByDate.merge(due, 1, Integer::sum);
            }
        }
        List<StatsSummaryResponse.DueDay> dueForecast = new ArrayList<>(30);
        for (int i = 0; i < 30; i++) {
            LocalDate d = today.plusDays(i);
            int c = dueByDate.getOrDefault(d, 0);
            if (i == 0) {
                c += dueToday; //今天柱 = 今日到期 + 逾期（前端红色高亮）
            }
            dueForecast.add(new StatsSummaryResponse.DueDay(d.toString(), c));
        }

        int[] todayAgg = dayAgg.get(today);
        int todayCount = todayAgg == null ? 0 : todayAgg[0];
        int todayDecided = todayAgg == null ? 0 : todayAgg[1];
        int todayCorrect = todayAgg == null ? 0 : todayAgg[2];

        return new StatsSummaryResponse(todayCount, todayDecided, todayCorrect,
                streakDays, longestStreak, totalAnswered, decidedTotal, correctTotal, totalSeconds,
                dueToday, overdue, reviewQuestionCount, daily, dueForecast, levelDist);
    }

    /** 判定一条作答：客观 correct 非空 → 原值；主观按自评 CORRECT；其余（未判/未自评）→ null */
    private Boolean decide(StudyRecord r) {
        if (r.getCorrect() != null) {
            return r.getCorrect();
        }
        if (r.getSelfGrade() != null) {
            return "CORRECT".equals(r.getSelfGrade());
        }
        return null;
    }

    /** 判错（含主观 PARTIAL/WRONG；未判不算） */
    private boolean isWrongDecide(StudyRecord r) {
        if (Boolean.FALSE.equals(r.getCorrect())) {
            return true;
        }
        return r.getSelfGrade() != null && !"CORRECT".equals(r.getSelfGrade());
    }

    // ==================== 二期 D/E/F（题库掌握度 / 错题治愈 / 最近练习） ====================

    public StatsDetailResponse detail() {
        List<StudyRecord> records = studyRecordMapper.selectList(null);

        //========== D 题库 × 掌握度 ==========
        Map<Long, QuestionBank> bankMap = questionBankMapper.selectList(null).stream()
                .collect(Collectors.toMap(QuestionBank::getId, b -> b));
        Map<Long, Long> bankQuestionTotal = questionMapper.selectList(null).stream()
                .collect(Collectors.groupingBy(Question::getBankId, Collectors.counting()));
        //库内聚合：已做题集合 / [有判定数, 判对数]
        Map<Long, Set<Long>> bankAnswered = new HashMap<>();
        Map<Long, int[]> bankJudge = new HashMap<>();
        for (StudyRecord r : records) {
            if (r.getBankId() == null || r.getQuestionId() == null) {
                continue;
            }
            bankAnswered.computeIfAbsent(r.getBankId(), k -> new java.util.HashSet<>()).add(r.getQuestionId());
            Boolean decided = decide(r);
            if (decided != null) {
                int[] j = bankJudge.computeIfAbsent(r.getBankId(), k -> new int[2]);
                j[0]++;
                if (decided) {
                    j[1]++;
                }
            }
        }
        List<StatsDetailResponse.BankStat> bankStats = new ArrayList<>();
        for (QuestionBank b : bankMap.values()) {
            int total = (int) (long) bankQuestionTotal.getOrDefault(b.getId(), 0L);
            int answered = bankAnswered.containsKey(b.getId()) ? bankAnswered.get(b.getId()).size() : 0;
            int[] j = bankJudge.getOrDefault(b.getId(), new int[2]);
            bankStats.add(new StatsDetailResponse.BankStat(b.getId(), b.getName(), total, answered, j[0], j[1]));
        }
        bankStats.sort(Comparator.comparing(StatsDetailResponse.BankStat::bankId));

        //========== E 错题治愈 ==========
        int currentWrong = StudyRecordService.computeWrongQuestionIds(records).size();
        //近 6 个月每月末错题数（每月末前记录按"最近一次作答"口径重算）
        List<StatsDetailResponse.MonthPoint> wrongTrend = new ArrayList<>();
        YearMonth now = YearMonth.now();
        for (int i = 5; i >= 0; i--) {
            YearMonth ym = now.minusMonths(i);
            LocalDateTime end = ym.atEndOfMonth().atTime(23, 59, 59);
            List<StudyRecord> until = records.stream()
                    .filter(r -> r.getAnsweredAt() != null && !r.getAnsweredAt().isAfter(end))
                    .toList();
            wrongTrend.add(new StatsDetailResponse.MonthPoint(ym.toString(), StudyRecordService.computeWrongQuestionIds(until).size()));
        }
        //错题复现：按题分组按时间升序，首次错误之后的再作答为复现样本
        int reproduceDecided = 0;
        int reproduceCorrect = 0;
        //治愈候选：该题最后一次作答判对，且更早存在错误记录
        List<long[]> healedRaw = new ArrayList<>(); // {questionId, lastAnsweredAtEpoch} 排序用
        Map<Long, StudyRecord> healedLast = new HashMap<>();
        Map<Long, List<StudyRecord>> byQuestion = records.stream()
                .filter(r -> r.getQuestionId() != null && r.getAnsweredAt() != null)
                .collect(Collectors.groupingBy(StudyRecord::getQuestionId,
                        Collectors.collectingAndThen(Collectors.toList(),
                                l -> {
                                    l.sort(Comparator.comparing(StudyRecord::getAnsweredAt)
                                            .thenComparing(StudyRecord::getId));
                                    return l;
                                })));
        for (Map.Entry<Long, List<StudyRecord>> e : byQuestion.entrySet()) {
            boolean everWrong = false;
            StudyRecord last = null;
            for (StudyRecord r : e.getValue()) {
                Boolean decided = decide(r);
                if (everWrong && decided != null) {
                    reproduceDecided++;
                    if (decided) {
                        reproduceCorrect++;
                    }
                }
                if (isWrongDecide(r)) {
                    everWrong = true;
                }
                last = r;
            }
            //治愈：最后一次判对 + 历史曾错
            if (everWrong && last != null && Boolean.TRUE.equals(decide(last))) {
                healedLast.put(e.getKey(), last);
            }
        }
        List<StatsDetailResponse.HealedItem> healed = new ArrayList<>();
        if (!healedLast.isEmpty()) {
            Map<Long, Question> qMap = questionMapper.selectBatchIds(healedLast.keySet()).stream()
                    .collect(Collectors.toMap(Question::getId, q -> q));
            healed = healedLast.entrySet().stream()
                    .sorted(Map.Entry.<Long, StudyRecord>comparingByValue(
                            Comparator.comparing(StudyRecord::getAnsweredAt).reversed()))
                    .limit(5)
                    .map(en -> {
                        StudyRecord r = en.getValue();
                        Question q = qMap.get(en.getKey());
                        QuestionBank b = q == null ? null : bankMap.get(q.getBankId());
                        return new StatsDetailResponse.HealedItem(
                                en.getKey(),
                                b == null ? "（已删除题库）" : b.getName(),
                                q == null ? null : q.getQuestionNumber(),
                                q == null ? "" : q.getContent(),
                                r.getAnsweredAt());
                    })
                    .toList();
        }

        //========== F 最近 10 场完成会话 ===========
        List<StatsDetailResponse.SessionStat> sessionStats = new ArrayList<>();
        List<PracticeSession> sessions = practiceSessionMapper.selectList(
                new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<PracticeSession>()
                        .isNotNull(PracticeSession::getFinishedAt)
                        .orderByDesc(PracticeSession::getFinishedAt)
                        .last("LIMIT 10"));
        if (!sessions.isEmpty()) {
            List<Long> sessionIds = sessions.stream().map(PracticeSession::getId).toList();
            Map<Long, List<StudyRecord>> recBySession = records.stream()
                    .filter(r -> r.getSessionId() != null && sessionIds.contains(r.getSessionId()))
                    .collect(Collectors.groupingBy(StudyRecord::getSessionId));
            for (PracticeSession s : sessions) {
                List<StudyRecord> rs = recBySession.getOrDefault(s.getId(), List.of());
                int answered = rs.size();
                int correct = 0;
                for (StudyRecord r : rs) {
                    if (Boolean.TRUE.equals(decide(r))) {
                        correct++;
                    }
                }
                QuestionBank b = bankMap.get(s.getBankId());
                sessionStats.add(new StatsDetailResponse.SessionStat(s.getId(),
                        b == null ? "（已删除题库）" : b.getName(), s.getMode(),
                        s.getFinishedAt(),
                        s.getQuestionCount() == null ? answered : s.getQuestionCount(),
                        answered, correct));
            }
        }

        return new StatsDetailResponse(bankStats,
                new StatsDetailResponse.WrongHeal(currentWrong, wrongTrend,
                        reproduceDecided, reproduceCorrect, healed),
                sessionStats);
    }
}
