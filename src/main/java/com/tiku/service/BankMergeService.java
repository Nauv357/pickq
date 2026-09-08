package com.tiku.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tiku.mapper.MaterialMapper;
import com.tiku.mapper.QuestionBankMapper;
import com.tiku.mapper.QuestionMapper;
import com.tiku.model.Material;
import com.tiku.model.OptionItem;
import com.tiku.model.Question;
import com.tiku.model.QuestionBank;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 题库合并（多库合一，复制 + 血缘，源库不动）。
 *
 * 语义（与产品决策一致）：
 * - 合并 = 把多个源题库的题目/材料/图片**复制**进一个新题库；
 * - **源题库原样保留**（含各自的刷题记录/错题/复习计划——记录按题库归属，不受影响）；
 * - 新题库从零开始：无刷题记录、无身份（packageKey=null，导出时认领新身份）；
 * - 血缘：新题库 sources 记录各源 packageKey（自建无身份源记录其名称占位，见下文）；
 * - 冲突处理：题目 questionKey（external_id）在新库内唯一——源库间重复时
 *   自动追加 `-{源序号}` 后缀；材料引用（materialId）重新映射到新库材料。
 */
@Service
public class BankMergeService {

    private static final Pattern IMAGE_REF = ImageStorageService.IMAGE_REF;

    private final QuestionBankMapper questionBankMapper;
    private final QuestionMapper questionMapper;
    private final MaterialMapper materialMapper;
    private final ImageStorageService imageStorageService;
    private final ObjectMapper objectMapper;

    public BankMergeService(QuestionBankMapper questionBankMapper,
                            QuestionMapper questionMapper,
                            MaterialMapper materialMapper,
                            ImageStorageService imageStorageService,
                            ObjectMapper objectMapper) {
        this.questionBankMapper = questionBankMapper;
        this.questionMapper = questionMapper;
        this.materialMapper = materialMapper;
        this.imageStorageService = imageStorageService;
        this.objectMapper = objectMapper;
    }

    /** 合并结果 */
    public record MergeResult(Long bankId, String name, int questionsCopied, int materialsCopied) {
    }

    /**
     * 合并多个题库为一个新题库。
     *
     * @param name          新题库名称
     * @param description   描述（可选）
     * @param sourceBankIds 源题库 id 列表（≥2；按给定顺序作为合并顺序）
     */
    @Transactional
    public MergeResult mergeBanks(String name, String description, List<Long> sourceBankIds) {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("新题库名称不能为空");
        }
        if (sourceBankIds == null || sourceBankIds.size() < 2) {
            throw new IllegalArgumentException("请至少选择两个题库进行合并");
        }
        // 去重保序
        List<Long> ids = new ArrayList<>(new LinkedHashSet<>(sourceBankIds));
        if (ids.size() < 2) {
            throw new IllegalArgumentException("请至少选择两个不同的题库进行合并");
        }

        // 校验源库存在
        List<QuestionBank> sources = new ArrayList<>();
        for (Long id : ids) {
            QuestionBank b = questionBankMapper.selectById(id);
            if (b == null) {
                throw new NoSuchElementException("源题库不存在：" + id);
            }
            sources.add(b);
        }

        // 新题库（无 packageKey = 自建身份；导出时认领新内容包身份）
        QuestionBank merged = new QuestionBank();
        merged.setName(name.trim());
        merged.setDescription(description == null || description.isBlank() ? null : description.trim());
        // 血缘：只记录有内容包身份的源（packageKey）；自建源无身份无法溯源，不写入。
        // 合并库自身无 packageKey（自建身份）——将来导出为内容包时认领新身份并携带此血缘。
        List<String> lineage = new ArrayList<>();
        for (QuestionBank s : sources) {
            if (s.getPackageKey() != null && !s.getPackageKey().isBlank()) {
                lineage.add(s.getPackageKey());
            }
        }
        if (!lineage.isEmpty()) {
            merged.setSources(serializeSources(lineage));
        }
        questionBankMapper.insert(merged);
        Long targetId = merged.getId();

        // 题目 key 冲突跟踪（新库内唯一）
        Set<String> usedKeys = new HashSet<>();
        int questionsCopied = 0;
        int materialsCopied = 0;
        int sourceIndex = 0;
        //题号重排：新库题号从 1 连续递增（跨源累计；避免各源自带 1..N 造成重复/顺序断裂）
        int nextNumber = 1;

        for (QuestionBank src : sources) {
            sourceIndex++;
            Long srcId = src.getId();

            // 1) 复制材料（含图片引用文本），建立 旧materialId → 新materialId 映射
            Map<Long, Long> materialIdMap = new HashMap<>();
            List<Material> materials = materialMapper.selectList(
                    new LambdaQueryWrapper<Material>().eq(Material::getBankId, srcId)
                            .orderByAsc(Material::getSortOrder).orderByAsc(Material::getId));
            for (Material m : materials) {
                Material copy = new Material();
                copy.setBankId(targetId);
                copy.setContent(m.getContent());
                copy.setSortOrder(m.getSortOrder());
                materialMapper.insert(copy);
                materialIdMap.put(m.getId(), copy.getId());
                materialsCopied++;
            }

            // 2) 复制题目
            List<Question> questions = questionMapper.selectList(
                    new LambdaQueryWrapper<Question>().eq(Question::getBankId, srcId)
                            .orderByAsc(Question::getQuestionNumber).orderByAsc(Question::getId));
            for (Question q : questions) {
                Question copy = new Question();
                copy.setBankId(targetId);
                String ext = q.getExternalId();
                if (usedKeys.contains(ext)) {
                    // 跨源重复 questionKey：追加 -{源序号}；仍冲突（同源内理论不会）再追加 uuid 片段
                    String suffixed = ext + "-" + sourceIndex;
                    if (usedKeys.contains(suffixed)) {
                        suffixed = ext + "-" + UUID.randomUUID().toString().replace("-", "").substring(0, 8);
                    }
                    ext = suffixed;
                }
                usedKeys.add(ext);
                copy.setExternalId(ext);
                copy.setVolume(q.getVolume());
                copy.setQuestionType(q.getQuestionType());
                copy.setContent(q.getContent());
                copy.setOptions(q.getOptions());
                copy.setAnswerKeys(q.getAnswerKeys());
                copy.setAnswerText(q.getAnswerText());
                copy.setAnalysis(q.getAnalysis());
                copy.setTopic(q.getTopic());
                copy.setCategory(q.getCategory());
                copy.setScore(q.getScore());
                copy.setReferenceAnswer(q.getReferenceAnswer());
                copy.setSource(q.getSource());
                copy.setQuestionNumber(nextNumber++); //题号重排：合并库内从 1 连续
                copy.setFavorite(false); // 收藏是个人标记，新库从零
                if (q.getMaterialId() != null) {
                    Long newMaterialId = materialIdMap.get(q.getMaterialId());
                    if (newMaterialId != null) {
                        copy.setMaterialId(newMaterialId);
                    }
                }
                questionMapper.insert(copy);
                questionsCopied++;
            }

            // 3) 复制图片：收集源库题目/材料文本中所有 [图片:name] 引用 → 拷到目标库
            Set<String> imageRefs = new LinkedHashSet<>();
            for (Question q : questions) {
                collectImageRefs(q.getContent(), imageRefs);
                collectImageRefs(q.getAnalysis(), imageRefs);
                collectImageRefs(q.getReferenceAnswer(), imageRefs);
                if (q.getOptions() != null) {
                    for (OptionItem o : q.getOptions()) {
                        if (o != null) {
                            collectImageRefs(o.text(), imageRefs);
                        }
                    }
                }
            }
            for (Material m : materials) {
                collectImageRefs(m.getContent(), imageRefs);
            }
            if (!imageRefs.isEmpty()) {
                imageStorageService.copyToBank(srcId, targetId, imageRefs);
            }
        }

        return new MergeResult(targetId, merged.getName(), questionsCopied, materialsCopied);
    }

    /**
     * 选题另存 / 并入：从题库选出若干题，复制到新题库（targetBankId=null）或并入现有题库。
     * 与 mergeBanks 同语义：源库不动、复制图片与关联材料（仅被选中题引用的材料）、
     * 新题库无 packageKey（自建身份）、血缘记源 packageKey；并入时题目 questionKey 与目标库冲突自动加后缀。
     *
     * @param sourceBankId 源题库
     * @param questionIds  选中题目（必须都属于源题库）
     * @param name         新建时的题库名（并入时忽略）
     * @param description  新建时的描述（可选）
     * @param targetBankId 并入目标（不能等于源库）；null = 新建
     */
    @Transactional
    public MergeResult copySelection(Long sourceBankId, List<Long> questionIds, String name,
                                     String description, Long targetBankId) {
        QuestionBank source = questionBankMapper.selectById(sourceBankId);
        if (source == null) {
            throw new NoSuchElementException("源题库不存在：" + sourceBankId);
        }
        if (questionIds == null || questionIds.isEmpty()) {
            throw new IllegalArgumentException("请先勾选要复制的题目");
        }
        List<Long> ids = new ArrayList<>(new LinkedHashSet<>(questionIds));
        if (targetBankId != null && targetBankId.equals(sourceBankId)) {
            throw new IllegalArgumentException("不能并入源题库自身");
        }

        //目标：新建或校验既有库
        QuestionBank target;
        if (targetBankId == null) {
            if (name == null || name.isBlank()) {
                throw new IllegalArgumentException("新题库名称不能为空");
            }
            target = new QuestionBank();
            target.setName(name.trim());
            target.setDescription(description == null || description.isBlank() ? null : description.trim());
            //血缘：源有内容包身份才记录（同合并语义）
            if (source.getPackageKey() != null && !source.getPackageKey().isBlank()) {
                target.setSources(serializeSources(List.of(source.getPackageKey())));
            }
            questionBankMapper.insert(target);
        } else {
            target = questionBankMapper.selectById(targetBankId);
            if (target == null) {
                throw new NoSuchElementException("目标题库不存在：" + targetBankId);
            }
        }
        Long targetId = target.getId();

        //题号重排：并入目标库时从现有最大题号 +1 续号（目标库原题号不动，不产生重复）；
        //新建目标库从 1 开始
        int nextNumber = 1;
        if (targetBankId != null) {
            Object mx = questionMapper.selectObjs(new com.baomidou.mybatisplus.core.conditions.query.QueryWrapper<Question>()
                    .select("MAX(question_number)").eq("bank_id", targetId)).stream().findFirst().orElse(null);
            if (mx != null) {
                nextNumber = ((Number) mx).intValue() + 1;
            }
        }

        //选中题目（保持题号顺序）
        List<Question> selected = questionMapper.selectList(
                new LambdaQueryWrapper<Question>()
                        .eq(Question::getBankId, sourceBankId)
                        .in(Question::getId, ids)
                        .orderByAsc(Question::getQuestionNumber).orderByAsc(Question::getId));
        if (selected.isEmpty()) {
            throw new IllegalArgumentException("所选题目不存在或不属于该题库");
        }

        //题目 key 冲突跟踪：并入时以目标库现有 key 为种子
        Set<String> usedKeys = new HashSet<>();
        if (targetBankId != null) {
            for (Question q : questionMapper.selectList(
                    new LambdaQueryWrapper<Question>().eq(Question::getBankId, targetId))) {
                if (q.getExternalId() != null) {
                    usedKeys.add(q.getExternalId());
                }
            }
        }

        //1) 复制被引用材料（仅选中题引用的；避免整库材料冗余）
        Set<Long> materialIds = new LinkedHashSet<>();
        for (Question q : selected) {
            if (q.getMaterialId() != null) {
                materialIds.add(q.getMaterialId());
            }
        }
        Map<Long, Long> materialIdMap = new HashMap<>();
        List<Material> copiedMaterials = new ArrayList<>();
        if (!materialIds.isEmpty()) {
            List<Material> materials = materialMapper.selectList(
                    new LambdaQueryWrapper<Material>()
                            .eq(Material::getBankId, sourceBankId)
                            .in(Material::getId, materialIds)
                            .orderByAsc(Material::getSortOrder).orderByAsc(Material::getId));
            for (Material m : materials) {
                Material copy = new Material();
                copy.setBankId(targetId);
                copy.setContent(m.getContent());
                copy.setSortOrder(m.getSortOrder());
                materialMapper.insert(copy);
                materialIdMap.put(m.getId(), copy.getId());
                copiedMaterials.add(copy);
            }
        }

        //2) 复制题目
        int questionsCopied = 0;
        for (Question q : selected) {
            Question copy = new Question();
            copy.setBankId(targetId);
            String ext = q.getExternalId();
            if (ext != null && usedKeys.contains(ext)) {
                String suffixed = ext + "-" + targetId;
                if (usedKeys.contains(suffixed)) {
                    suffixed = ext + "-" + UUID.randomUUID().toString().replace("-", "").substring(0, 8);
                }
                ext = suffixed;
            }
            if (ext != null) {
                usedKeys.add(ext);
                copy.setExternalId(ext);
            }
            copy.setVolume(q.getVolume());
            copy.setQuestionType(q.getQuestionType());
            copy.setContent(q.getContent());
            copy.setOptions(q.getOptions());
            copy.setAnswerKeys(q.getAnswerKeys());
            copy.setAnswerText(q.getAnswerText());
            copy.setAnalysis(q.getAnalysis());
            copy.setTopic(q.getTopic());
            copy.setCategory(q.getCategory());
            copy.setScore(q.getScore());
            copy.setReferenceAnswer(q.getReferenceAnswer());
            copy.setSource(q.getSource());
            copy.setQuestionNumber(nextNumber++); //题号重排：新建从 1、并入从目标库最大号+1 续
            copy.setFavorite(false); //收藏是个人标记，复制从零
            if (q.getMaterialId() != null) {
                Long newMaterialId = materialIdMap.get(q.getMaterialId());
                if (newMaterialId != null) {
                    copy.setMaterialId(newMaterialId);
                }
            }
            questionMapper.insert(copy);
            questionsCopied++;
        }

        //3) 复制图片（选中题 + 被复制材料的 [图片:name] 引用）
        Set<String> imageRefs = new LinkedHashSet<>();
        for (Question q : selected) {
            collectImageRefs(q.getContent(), imageRefs);
            collectImageRefs(q.getAnalysis(), imageRefs);
            collectImageRefs(q.getReferenceAnswer(), imageRefs);
            if (q.getOptions() != null) {
                for (OptionItem o : q.getOptions()) {
                    if (o != null) {
                        collectImageRefs(o.text(), imageRefs);
                    }
                }
            }
        }
        for (Material m : copiedMaterials) {
            collectImageRefs(m.getContent(), imageRefs);
        }
        if (!imageRefs.isEmpty()) {
            imageStorageService.copyToBank(sourceBankId, targetId, imageRefs);
        }

        return new MergeResult(targetId, target.getName(), questionsCopied, copiedMaterials.size());
    }

    private void collectImageRefs(String text, Set<String> out) {
        if (text == null || text.isBlank()) {
            return;
        }
        Matcher m = IMAGE_REF.matcher(text);
        while (m.find()) {
            out.add(m.group(1));
        }
    }

    private String serializeSources(List<String> sources) {
        if (sources == null || sources.isEmpty()) {
            return null;
        }
        try {
            return objectMapper.writeValueAsString(sources);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("血缘来源序列化失败", e);
        }
    }
}
