package com.tiku.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.tiku.dto.*;
import com.tiku.mapper.MaterialMapper;
import com.tiku.mapper.PracticeSessionMapper;
import com.tiku.mapper.PracticeSessionQuestionMapper;
import com.tiku.mapper.QuestionBankMapper;
import com.tiku.mapper.QuestionMapper;
import com.tiku.mapper.ReviewStateMapper;
import com.tiku.mapper.StudyRecordMapper;
import com.tiku.model.Material;
import com.tiku.model.PracticeSession;
import com.tiku.model.Question;
import com.tiku.model.QuestionBank;
import com.tiku.model.ReviewState;
import com.tiku.model.StudyRecord;
import com.tiku.model.enums.QuestionType;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Set;
import java.util.stream.Collectors;

@Service
public class QuestionBankService {
    private final QuestionBankMapper questionBankMapper;
    private final QuestionMapper questionMapper;
    private final StudyRecordMapper studyRecordMapper;
    private final ReviewStateMapper reviewStateMapper;
    private final PracticeSessionMapper sessionMapper;
    private final PracticeSessionQuestionMapper sessionQuestionMapper;
    private final MaterialMapper materialMapper;

    public QuestionBankService(QuestionBankMapper questionBankMapper,
                               QuestionMapper questionMapper,
                               StudyRecordMapper studyRecordMapper,
                               ReviewStateMapper reviewStateMapper,
                               PracticeSessionMapper sessionMapper,
                               PracticeSessionQuestionMapper sessionQuestionMapper,
                               MaterialMapper materialMapper) {
        this.questionBankMapper = questionBankMapper;
        this.questionMapper = questionMapper;
        this.studyRecordMapper = studyRecordMapper;
        this.reviewStateMapper = reviewStateMapper;
        this.sessionMapper = sessionMapper;
        this.sessionQuestionMapper = sessionQuestionMapper;
        this.materialMapper = materialMapper;
    }

    public Long createQuestionBank(QuestionBankCreateRequest request) {
        QuestionBank questionBank = toEntity(request);
        questionBankMapper.insert(questionBank);
        return questionBank.getId();
    }

    public PageResult<QuestionBankResponse> listQuestionBanks(int page, int size, String keyword, String sort) {
        LambdaQueryWrapper<QuestionBank> wrapper = new LambdaQueryWrapper<>();
        //搜索：名称 / 描述模糊匹配
        if (keyword != null && !keyword.isBlank()) {
            String k = keyword.trim();
            wrapper.and(w -> w.like(QuestionBank::getName, k).or().like(QuestionBank::getDescription, k));
        }
        if ("updated".equals(sort)) {
            wrapper.orderByDesc(QuestionBank::getUpdatedAt).orderByDesc(QuestionBank::getId);
        } else if ("name".equals(sort)) {
            wrapper.orderByAsc(QuestionBank::getName).orderByAsc(QuestionBank::getId);
        } else {
            //默认：新建优先 + 时间倒序（无排序时同秒记录分页会重复/遗漏）
            wrapper.orderByDesc(QuestionBank::getCreatedAt).orderByDesc(QuestionBank::getId);
        }
        IPage<QuestionBank> questionBankPage = questionBankMapper.selectPage(
                new Page<>(com.tiku.util.Paging.page(page), com.tiku.util.Paging.size(size)),
                wrapper);
        return PageResult.from(questionBankPage.convert(QuestionBankResponse::fromEntity));
    }

    public QuestionBankDetailResponse getBankDetail(Long id){
        QuestionBank questionBank = findByIdOrThrow(id);
        return QuestionBankDetailResponse.fromEntity(questionBank);
    }

    /**
     * 题目列表（支持搜索与筛选）：
     * keyword 匹配题干/选项文本；questionType/category/topic 精确筛选；
     * scope: all 全部（默认）/ favorite 收藏 / wrong 答错（刷题记录 correct=false）/
     *        undone 未做过（无刷题记录）。
     * 响应含 answerKeys/analysis/materialContent（列表"解析"弹窗一次到位，无需再调详情接口）。
     */
    public PageResult<QuestionSummaryResponse> getBankQuestions(Long bankId, int page, int size,
                                                                String keyword, QuestionType questionType,
                                                                String category, String topic, String scope) {
        findByIdOrThrow(bankId);
        page = com.tiku.util.Paging.page(page);
        size = com.tiku.util.Paging.size(size);
        LambdaQueryWrapper<Question> wrapper = buildListWrapper(bankId, keyword, questionType, category, topic, scope);
        if (wrapper == null) {
            return PageResult.empty();
        }
        IPage<Question> questionPage = questionMapper.selectPage(new Page<>(page, size), wrapper);
        //材料内容映射（解析弹窗展示材料用）
        Map<Long, String> materialContentById = materialMapper.selectList(
                        new LambdaQueryWrapper<Material>().eq(Material::getBankId, bankId))
                .stream().collect(Collectors.toMap(Material::getId, Material::getContent));
        return PageResult.from(questionPage.convert(q ->
                QuestionSummaryResponse.fromEntity(q,
                        q.getMaterialId() == null ? null : materialContentById.get(q.getMaterialId()))));
    }

    //题号导航（题库详情右侧目录）：与题目列表同过滤同排序，全量轻量返回。
    //null wrapper 表示该范围无题目（如错题集为空）→ 空列表
    public List<QuestionNavItemResponse> listQuestionNavItems(Long bankId, String keyword,
                                                              QuestionType questionType,
                                                              String category, String topic, String scope) {
        findByIdOrThrow(bankId);
        LambdaQueryWrapper<Question> wrapper = buildListWrapper(bankId, keyword, questionType, category, topic, scope);
        if (wrapper == null) {
            return List.of();
        }
        //只取导航需要的列，避免全字段（含大文本 content/analysis）传输
        wrapper.select(Question::getId, Question::getQuestionNumber, Question::getQuestionType);
        return questionMapper.selectList(wrapper).stream().map(QuestionNavItemResponse::fromEntity).toList();
    }

    /** 列表 / 题号导航共用的过滤查询（同排序：题号优先、插入序兜底；wrong 集为空返回 null 表示无结果） */
    private LambdaQueryWrapper<Question> buildListWrapper(Long bankId, String keyword, QuestionType questionType,
                                                          String category, String topic, String scope) {
        LambdaQueryWrapper<Question> wrapper = new LambdaQueryWrapper<Question>()
                .eq(Question::getBankId, bankId)
                .orderByAsc(Question::getQuestionNumber).orderByAsc(Question::getId);
        if (keyword != null && !keyword.isBlank()) {
            wrapper.and(w -> w.like(Question::getContent, keyword)
                    .or().like(Question::getOptions, keyword));
        }
        if (questionType != null) {
            wrapper.eq(Question::getQuestionType, questionType);
        }
        if (category != null && !category.isBlank()) {
            wrapper.eq(Question::getCategory, category);
        }
        if (topic != null && !topic.isBlank()) {
            wrapper.eq(Question::getTopic, topic);
        }
        if ("favorite".equals(scope)) {
            wrapper.eq(Question::getFavorite, true);
        } else if ("wrong".equals(scope) || "undone".equals(scope)) {
            //该题库全部作答记录取回一次（本地数据量小）：answeredIds 供 undone 用；
            //wrong 用统一错题口径（最近一次作答为错，含主观题自评 PARTIAL/WRONG——见 StudyRecordService）
            List<StudyRecord> records = studyRecordMapper.selectList(
                    new LambdaQueryWrapper<StudyRecord>().eq(StudyRecord::getBankId, bankId));
            List<Long> answeredIds = records.stream().map(StudyRecord::getQuestionId).distinct().toList();
            if ("wrong".equals(scope)) {
                Set<Long> wrongIds = StudyRecordService.computeWrongQuestionIds(records);
                if (wrongIds.isEmpty()) {
                    return null;
                }
                wrapper.in(Question::getId, wrongIds);
            } else {
                //undone：未作答 = 全题 - 已作答
                if (!answeredIds.isEmpty()) {
                    wrapper.notIn(Question::getId, answeredIds);
                }
            }
        }
        return wrapper;
    }

    //做题用题目列表：含选项，不含答案与解析（供做题页面渲染，避免剧透）；组内题附共享材料内容
    public PageResult<QuestionPracticeResponse> getBankPracticeQuestions(Long bankId, int page, int size){
        findByIdOrThrow(bankId);
        IPage<Question> questionPage = questionMapper.selectPage(
                new Page<>(com.tiku.util.Paging.page(page), com.tiku.util.Paging.size(size)),
                //与做题顺序一致（SEQUENCE：题号优先、插入序兜底）
                new LambdaQueryWrapper<Question>().eq(Question::getBankId, bankId)
                        .orderByAsc(Question::getQuestionNumber).orderByAsc(Question::getId)
        );
        Map<Long, String> materialContentById = materialMapper.selectList(
                        new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<Material>()
                                .eq(Material::getBankId, bankId))
                .stream().collect(Collectors.toMap(Material::getId, Material::getContent));
        return PageResult.from(questionPage.convert(q ->
                QuestionPracticeResponse.fromEntity(q, q.getMaterialId() == null ? null : materialContentById.get(q.getMaterialId()))));
    }

    //更新题库：仅允许修改 name / description / source / authorName
    //（package_key、version、checksum、parent_key 为系统管理字段，不可手动修改）
    public void updateQuestionBank(Long id, QuestionBankUpdateRequest request){
        QuestionBank questionBank = findByIdOrThrow(id);
        if (request.name() != null) {
            if (request.name().isBlank()) {
                throw new IllegalArgumentException("题库名不能为空");
            }
            questionBank.setName(request.name());
        }
        if (request.description() != null) {
            questionBank.setDescription(request.description());
        }
        if (request.source() != null) {
            questionBank.setSource(request.source());
        }
        if (request.authorName() != null) {
            questionBank.setAuthorName(request.authorName());
        }
        questionBankMapper.updateById(questionBank);
    }

    //删除题库：物理删除题库 + 级联逻辑删除题目 + 物理删除刷题记录/复习状态/会话/材料（一个事务，原子完成）
    //返回删除的题目数与受影响的记录数，前端据此提示（产品原则第 9 条）
    @Transactional
    public DeleteBankResult deleteQuestionBank(Long id){
        findByIdOrThrow(id);
        sessionQuestionMapper.deleteByBankId(id);
        sessionMapper.delete(new LambdaQueryWrapper<PracticeSession>().eq(PracticeSession::getBankId, id));
        reviewStateMapper.delete(new LambdaQueryWrapper<ReviewState>().eq(ReviewState::getBankId, id));
        questionBankMapper.deleteById(id);
        // MyBatis-Plus @TableLogic 自动转为 UPDATE question SET deleted=1 WHERE bank_id=? AND deleted=0
        long deletedQuestions = questionMapper.delete(new LambdaQueryWrapper<Question>().eq(Question::getBankId, id));
        long affectedRecords = studyRecordMapper.delete(new LambdaQueryWrapper<StudyRecord>().eq(StudyRecord::getBankId, id));
        //共享材料随题库级联清理（物理删除）
        materialMapper.delete(new LambdaQueryWrapper<Material>().eq(Material::getBankId, id));
        return new DeleteBankResult(deletedQuestions, affectedRecords);
    }

    public QuestionBank findByIdOrThrow(Long id){
        QuestionBank questionBank = questionBankMapper.selectById(id);
        if(questionBank == null){
            throw new NoSuchElementException("题库不存在");
        }
        return questionBank;
    }

    //启用/关闭复习计划（默认关闭，用户显式开启；控制"今日待复习"队列）
    public void setReviewEnabled(Long id, boolean enabled) {
        QuestionBank questionBank = findByIdOrThrow(id);
        questionBank.setReviewEnabled(enabled);
        questionBankMapper.updateById(questionBank);
    }

    /** 主页概览（学习首页卡带）：跨库轻量聚合——题库数/总题数/今日待复习/错题数/最近一场练习 */
    public HomeOverviewResponse getHomeOverview() {
        long bankCount = questionBankMapper.selectCount(null);
        long questionCount = questionMapper.selectCount(null);
        //今日待复习：仅统计"复习计划已启用"的题库
        List<QuestionBank> enabledBanks = questionBankMapper.selectList(
                new LambdaQueryWrapper<QuestionBank>().eq(QuestionBank::getReviewEnabled, true));
        long dueTotal = 0;
        if (!enabledBanks.isEmpty()) {
            Set<Long> enabledIds = enabledBanks.stream().map(QuestionBank::getId).collect(Collectors.toSet());
            dueTotal = reviewStateMapper.selectCount(new LambdaQueryWrapper<ReviewState>()
                    .in(ReviewState::getBankId, enabledIds)
                    .isNotNull(ReviewState::getDueAt)
                    .le(ReviewState::getDueAt, LocalDateTime.now())
                    .and(w -> w.isNull(ReviewState::getSuspended)
                            .or().eq(ReviewState::getSuspended, false)));
        }
        //错题数（跨库）：最近一次作答为错（与错题本同口径）
        List<StudyRecord> allRecords = studyRecordMapper.selectList(null);
        int wrongTotal = StudyRecordService.computeWrongQuestionIds(allRecords).size();
        //最近一场完成的练习（含进行中会话不计；成绩实时聚合）
        HomeOverviewResponse.LastSession last = null;
        List<PracticeSession> finished = sessionMapper.selectList(
                new LambdaQueryWrapper<PracticeSession>()
                        .isNotNull(PracticeSession::getFinishedAt)
                        .orderByDesc(PracticeSession::getFinishedAt)
                        .last("LIMIT 1"));
        if (!finished.isEmpty()) {
            PracticeSession s = finished.get(0);
            QuestionBank b = questionBankMapper.selectById(s.getBankId());
            List<StudyRecord> recs = studyRecordMapper.selectList(
                    new LambdaQueryWrapper<StudyRecord>().eq(StudyRecord::getSessionId, s.getId()));
            int answered = recs.size();
            int correct = 0;
            for (StudyRecord r : recs) {
                String grade = r.getSelfGrade() != null ? r.getSelfGrade()
                        : r.getCorrect() == null ? null
                        : (Boolean.TRUE.equals(r.getCorrect()) ? "CORRECT" : "WRONG");
                if ("CORRECT".equals(grade)) {
                    correct++;
                }
            }
            last = new HomeOverviewResponse.LastSession(s.getId(), s.getBankId(),
                    b == null ? null : b.getName(), s.getMode(), modeLabel(s.getMode()),
                    correct, answered, s.getFinishedAt());
        }
        return new HomeOverviewResponse(bankCount, questionCount, dueTotal, wrongTotal, last);
    }

    private String modeLabel(String mode) {
        if (mode == null) {
            return "练习";
        }
        return switch (mode) {
            case "SEQUENCE" -> "顺序刷题";
            case "TOPIC" -> "按分类";
            case "REVIEW" -> "复习队列";
            case "WRONG" -> "错题重做";
            case "FAVORITE" -> "收藏练习";
            default -> "随机练习";
        };
    }

    private QuestionBank toEntity(QuestionBankCreateRequest request) {
        QuestionBank questionBank = new QuestionBank();
        questionBank.setName(request.name());
        questionBank.setDescription(request.description());
        return questionBank;
    }
}
