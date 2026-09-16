package com.tiku.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.tiku.mapper.QuestionDifficultyMapper;
import com.tiku.mapper.QuestionMapper;
import com.tiku.mapper.QuestionSkillMapper;
import com.tiku.mapper.StudyRecordMapper;
import com.tiku.model.Question;
import com.tiku.model.QuestionDifficulty;
import com.tiku.model.QuestionSkill;
import com.tiku.model.StudyRecord;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 自适应难度（学习路径引擎阶段 3，设计 §5.5 的 Elo-lite + 85% 规则）。
 *
 * 目的只有一个：**让每天练的题落在"预测成功率 80%–90%"这个区间**——太简单不涨水平，
 * 太难只是挫伤信心（这就是"最优学习区"）。
 *
 * 公式（与设计一致，确定性、可离线）：
 * <pre>
 *   p    = 1 / (1 + 10^((d_q − θ_u) / 400))   预测做对概率
 *   θ_u  = 400 × (2 × mastery − 1)             节点能力分（把掌握度映射到能力分）
 *   d_q ← d_q − K × (实际 − p)                 答对比预期好 → 题目显得更简单
 *   θ_u ← θ_u + K × (实际 − p)                 用户能力分随作答更新（这里由 mastery 现算，不落表）
 * </pre>
 *
 * 为什么 θ 不落表：它就是该节点掌握度的单调映射，落表就又出现"两份真相"。
 * 只有**题目难度**是真正需要跨请求保留的量（它属于题目，不属于用户此刻的状态）。
 */
@Slf4j
@Service
public class AdaptiveService {

    /** Elo 的 K：设计定为 16（本地单用户、数据量小，收敛快一点没关系） */
    private static final double K = 16.0;
    /** 85% 规则的目标成功率 */
    public static final double TARGET_SUCCESS = 0.85;
    /** 可接受的区间（低于/高于都算偏离目标） */
    private static final double LOWER = 0.80;
    private static final double UPPER = 0.90;

    private final QuestionDifficultyMapper difficultyMapper;
    private final QuestionMapper questionMapper;
    private final QuestionSkillMapper questionSkillMapper;
    private final StudyRecordMapper studyRecordMapper;

    public AdaptiveService(QuestionDifficultyMapper difficultyMapper, QuestionMapper questionMapper,
                           QuestionSkillMapper questionSkillMapper, StudyRecordMapper studyRecordMapper) {
        this.difficultyMapper = difficultyMapper;
        this.questionMapper = questionMapper;
        this.questionSkillMapper = questionSkillMapper;
        this.studyRecordMapper = studyRecordMapper;
    }

    /** 由掌握度映射出的能力分（θ = 400 × (2×mastery − 1)） */
    public static double abilityOf(double mastery) {
        return 400.0 * (2.0 * Math.max(0, Math.min(1, mastery)) - 1.0);
    }

    /** 预测做对概率 */
    public static double predict(double difficulty, double ability) {
        return 1.0 / (1.0 + Math.pow(10.0, (difficulty - ability) / 400.0));
    }

    /** 题目难度初值：按题型估计（主观题最难、判断最易），之后由作答更新 */
    static double initialDifficulty(Question q) {
        double d = 400;
        if (q.getQuestionType() != null) {
            d += switch (q.getQuestionType()) {
                case SUBJECTIVE -> 120;
                case MULTIPLE -> 60;
                case JUDGE -> -80;
                default -> 0;
            };
        }
        // 分值越高通常越难（主观题分值大，这里只对客观题做小幅加成，避免把判断也抬高）
        if (q.getScore() != null && q.getQuestionType() != null
                && q.getQuestionType() != com.tiku.model.enums.QuestionType.SUBJECTIVE && q.getScore() > 2) {
            d += 40;
        }
        return d;
    }

    /** 题 → 当前难度（没有记录时按题型初值现算，不写库） */
    public Map<Long, Double> difficultiesOf(List<Question> questions) {
        if (questions.isEmpty()) {
            return Map.of();
        }
        List<Long> ids = questions.stream().map(Question::getId).toList();
        Map<Long, Double> stored = new HashMap<>();
        for (QuestionDifficulty row : difficultyMapper.selectList(new LambdaQueryWrapper<QuestionDifficulty>()
                .in(QuestionDifficulty::getQuestionId, ids))) {
            stored.put(row.getQuestionId(), row.getDifficulty());
        }
        Map<Long, Double> out = new HashMap<>();
        for (Question q : questions) {
            out.put(q.getId(), stored.getOrDefault(q.getId(), initialDifficulty(q)));
        }
        return out;
    }

    /**
     * 按 85% 规则给一批题排序：越接近目标成功率越靠前。
     * 用于"今天做什么"的选点——同一知识点里先练"刚好够得着"的题。
     */
    public List<Question> orderByTargetSuccess(List<Question> questions, double mastery) {
        Map<Long, Double> difficulty = difficultiesOf(questions);
        double ability = abilityOf(mastery);
        List<Question> out = new ArrayList<>(questions);
        out.sort(Comparator.comparingDouble(q -> {
            double p = predict(difficulty.getOrDefault(q.getId(), 400.0), ability);
            // 优先落在 80%–90% 区间内；区间外按偏离目标的距离排
            double penalty = (p >= LOWER && p <= UPPER) ? 0 : Math.abs(p - TARGET_SUCCESS);
            return penalty * 100 + Math.abs(p - TARGET_SUCCESS);
        }));
        return out;
    }

    /** 预测成功率（界面用来说明"为什么先练这几题"） */
    public int predictedSuccess(Question q, double mastery, Map<Long, Double> difficulty) {
        double p = predict(difficulty.getOrDefault(q.getId(), initialDifficulty(q)), abilityOf(mastery));
        return (int) Math.round(p * 100);
    }

    /**
     * 作答后的入口（StudyRecordService 调它）：自己算能力分，再更新题目难度。
     * 能力分 = 该题所属知识点上的历史正确率（做了贝叶斯收缩，样本少时向 0.5 靠），
     * 只查两次库、不加载提示/卡片，所以可以安全地放在**每次作答**的路径上。
     */
    @Transactional
    public void recordAnswer(Question question, boolean correct) {
        double ability = abilityOnQuestionNodes(question);
        recordAnswer(question.getId(), correct, ability);
    }

    /** 该题所在知识点上的能力分（θ = 400×(2×掌握度代理−1)） */
    public double abilityOnQuestionNodes(Question question) {
        try {
            List<String> nodes = questionSkillMapper.selectList(new LambdaQueryWrapper<QuestionSkill>()
                            .eq(QuestionSkill::getQuestionId, question.getId())
                            .eq(QuestionSkill::getShadowed, false))
                    .stream().map(QuestionSkill::getNodeId).distinct().toList();
            if (nodes.isEmpty()) {
                return abilityOf(0.5);
            }
            List<Long> qids = questionSkillMapper.selectList(new LambdaQueryWrapper<QuestionSkill>()
                            .eq(QuestionSkill::getBankId, question.getBankId())
                            .eq(QuestionSkill::getShadowed, false)
                            .in(QuestionSkill::getNodeId, nodes))
                    .stream().map(QuestionSkill::getQuestionId).distinct().toList();
            if (qids.isEmpty()) {
                return abilityOf(0.5);
            }
            int answered = 0;
            int ok = 0;
            for (StudyRecord r : studyRecordMapper.selectList(new LambdaQueryWrapper<StudyRecord>()
                    .eq(StudyRecord::getBankId, question.getBankId())
                    .in(StudyRecord::getQuestionId, qids))) {
                //统一口径：主观题看自评（PARTIAL/WRONG 算错），未判定既不算对也不算错
                //（此前只看 correct 列，主观题的自评结果完全不参与能力分）
                if (StudyRecordService.isCorrect(r)) {
                    answered++;
                    ok++;
                } else if (StudyRecordService.isWrong(r)) {
                    answered++;
                }
            }
            // 贝叶斯收缩：样本少时向 0.5 靠（3 次先验），避免"做对一题就当成高手"
            double masteryProxy = (ok + 1.5) / (answered + 3.0);
            return abilityOf(masteryProxy);
        } catch (Exception e) {
            return abilityOf(0.5);
        }
    }

    /**
     * 记录一次作答：更新该题难度（Elo-lite）。
     * 能力分用**该题所属节点的掌握度**近似（取最高掌握度那个节点；没有标签时用 0.5 中性值）。
     */
    @Transactional
    public void recordAnswer(Long questionId, boolean correct, double ability) {
        Question q = questionMapper.selectById(questionId);
        if (q == null) {
            return;
        }
        QuestionDifficulty row = difficultyMapper.selectById(questionId);
        boolean fresh = row == null;
        if (fresh) {
            row = new QuestionDifficulty();
            row.setQuestionId(questionId);
            row.setDifficulty(initialDifficulty(q));
            row.setAttempts(0);
        }
        double d = row.getDifficulty() == null ? 400 : row.getDifficulty();
        double actual = correct ? 1.0 : 0.0;
        double p = predict(d, ability);
        double updated = d - K * (actual - p);
        // 夹取到合理范围，避免个别异常作答把难度推到极端
        row.setDifficulty(Math.max(-400, Math.min(1200, updated)));
        row.setAttempts((row.getAttempts() == null ? 0 : row.getAttempts()) + 1);
        row.setUpdatedAt(LocalDateTime.now());
        if (fresh) {
            difficultyMapper.insert(row);
        } else {
            difficultyMapper.updateById(row);
        }
    }

    /**
     * 交错混练比例：设计 §5.5 写的是"低掌握度集中练、掌握后与相邻节点交错"。
     *
     * **当前未接入**（2026-09-16 审计：全库零调用）。改配题顺序要动"薄弱知识点"那一桶的取题逻辑，
     * 而它现在只按"掌握度最低的节点"取——在没有真实数据证明交错更有用之前不引入第二个变量。
     * 保留这个方法是为了让设计稿与代码对得上；**别把它写进文档当已生效**（features.md 已同步）。
     */
    public static double interleaveRatio(double mastery) {
        return mastery >= 0.85 ? 0.35 : mastery >= 0.6 ? 0.15 : 0.0;
    }
}
