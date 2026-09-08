package com.tiku.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tiku.dto.ExportRequest;
import com.tiku.dto.ImportResultResponse;
import com.tiku.mapper.MaterialMapper;
import com.tiku.mapper.QuestionBankMapper;
import com.tiku.mapper.QuestionMapper;
import com.tiku.mapper.StudyRecordMapper;
import com.tiku.model.*;
import com.tiku.model.enums.QuestionType;
import com.tiku.util.PackageContainer;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDateTime;
import java.util.*;
import java.io.IOException;

/**
 * 内容包导入导出（合并模型：题库 = 内容包本地形态）。
 * 导入：内容包 JSON → 题库 + 题目（冲突检测：全新 / 已导入 / 版本并存 / 分支导入）。
 * 导出：题库 + 题目 → 内容包 JSON（自建题库生成新身份；内容被修改默认分支）。
 */
@Service
public class ContentPackageService {

    private final QuestionBankMapper questionBankMapper;
    private final QuestionMapper questionMapper;
    private final MaterialMapper materialMapper;
    private final StudyRecordMapper studyRecordMapper;
    private final ImageStorageService imageStorageService;
    private final ObjectMapper objectMapper;

    public ContentPackageService(QuestionBankMapper questionBankMapper, QuestionMapper questionMapper,
                                 MaterialMapper materialMapper, StudyRecordMapper studyRecordMapper,
                                 ImageStorageService imageStorageService,
                                 ObjectMapper objectMapper) {
        this.questionBankMapper = questionBankMapper;
        this.questionMapper = questionMapper;
        this.materialMapper = materialMapper;
        this.studyRecordMapper = studyRecordMapper;
        this.imageStorageService = imageStorageService;
        this.objectMapper = objectMapper;
    }

    /**
     * 导出题目范围过滤（打印/组卷数据源复用）：
     * category/topic 精确筛选；scope=all（默认）/favorite/wrong/undone。
     * wrong 用统一错题口径（最近一次作答为错，含主观题自评 PARTIAL/WRONG），与错题本/WRONG 模式一致。
     * 保持原顺序。
     */
    private List<Question> filterExportQuestions(Long bankId, List<Question> questions,
                                                 String scope, String category, String topic) {
        List<Question> filtered = questions.stream()
                .filter(q -> category == null || category.isBlank() || category.equals(q.getCategory()))
                .filter(q -> topic == null || topic.isBlank() || topic.equals(q.getTopic()))
                .toList();
        if ("favorite".equals(scope)) {
            filtered = filtered.stream().filter(q -> Boolean.TRUE.equals(q.getFavorite())).toList();
        } else if ("wrong".equals(scope) || "undone".equals(scope)) {
            List<StudyRecord> records = studyRecordMapper.selectList(
                    new LambdaQueryWrapper<StudyRecord>().eq(StudyRecord::getBankId, bankId));
            if ("wrong".equals(scope)) {
                Set<Long> wrongIds = StudyRecordService.computeWrongQuestionIds(records);
                filtered = filtered.stream().filter(q -> wrongIds.contains(q.getId())).toList();
            } else {
                Set<Long> answeredIds = records.stream().map(StudyRecord::getQuestionId)
                        .collect(java.util.stream.Collectors.toSet());
                filtered = filtered.stream().filter(q -> !answeredIds.contains(q.getId())).toList();
            }
        }
        return filtered;
    }

    // ==================== 导入 ====================

    /** v1 纯 JSON 导入（兼容老文件与粘贴文本场景） */
    @Transactional
    public ImportResultResponse importContentPackage(String json) {
        return importContentPackage(parseAndValidate(json));
    }

    /**
     * v2 .tiku 容器导入（zip：package.json + media/）。
     * 图片从 media 二进制重建 base64 map 后走统一导入——checksum 与 v1 同表示（base64），
     * 同内容的 v1/v2 文件指纹一致，存量判断不误判。
     */
    @Transactional
    public ImportResultResponse importTikuPackage(byte[] container) {
        PackageContainer.Unpacked unpacked;
        try {
            unpacked = PackageContainer.unpack(container);
        } catch (IOException e) {
            throw new IllegalArgumentException(".tiku 容器读取失败：" + e.getMessage());
        }
        ContentPackageFile file = parseAndValidate(unpacked.packageText());
        // 包内 package.json 不含 images（图片在 media/）；从 media 重建 base64 表示
        Map<String, String> images = new LinkedHashMap<>();
        if (unpacked.media() != null) {
            for (Map.Entry<String, byte[]> e : unpacked.media().entrySet()) {
                if (e.getValue() != null && e.getValue().length > 0) {
                    images.put(e.getKey(), java.util.Base64.getEncoder().encodeToString(e.getValue()));
                }
            }
        }
        file.setImages(images.isEmpty() ? null : images);
        return importContentPackage(file);
    }

    /** 导入核心：解析后的内容包 → 题库 + 题目（冲突检测：全新 / 已导入 / 版本并存 / 分支导入） */
    @Transactional
    public ImportResultResponse importContentPackage(ContentPackageFile file) {
        if (file.getQuestions() == null) {
            file.setQuestions(List.of());
        }
        String packageKey = file.getPackageKey();
        String fileChecksum = computeChecksum(file);

        //同 package_key 的所有未删除题库（多版本并存）
        List<QuestionBank> existing = questionBankMapper.selectList(
                new LambdaQueryWrapper<QuestionBank>().eq(QuestionBank::getPackageKey, packageKey));

        //同 key + 同 version
        Optional<QuestionBank> sameVersion = existing.stream()
                .filter(b -> Objects.equals(b.getVersion(), file.getVersion()))
                .findFirst();
        if (sameVersion.isPresent()) {
            QuestionBank bank = sameVersion.get();
            if (Objects.equals(bank.getChecksum(), fileChecksum)) {
                return new ImportResultResponse("ALREADY_IMPORTED", bank.getId(), "该内容包已导入过");
            }
            //同版本但内容已被修改：分支导入（新 packageKey + parentKey），不覆盖原题库
            QuestionBank branch = buildBankFromFile(file, generatePackageKey(), packageKey, fileChecksum);
            questionBankMapper.insert(branch);
            persistContent(branch.getId(), file);
            return new ImportResultResponse("BRANCHED", branch.getId(), "内容已被修改，已分支导入为新内容包");
        }

        //全新导入，或同 key 的新版本并存（多版本共存，不覆盖旧版本）
        QuestionBank bank = buildBankFromFile(file, packageKey, null, fileChecksum);
        questionBankMapper.insert(bank);
        persistContent(bank.getId(), file);
        boolean isUpgrade = !existing.isEmpty();
        return new ImportResultResponse(
                isUpgrade ? "VERSION_ADDED" : "CREATED",
                bank.getId(),
                isUpgrade ? "已并存导入新版本 " + file.getVersion() : "导入成功");
    }

    // ==================== 导出 ====================

    public ContentPackageFile exportContentPackage(Long bankId, ExportRequest request) {
        QuestionBank bank = questionBankMapper.selectById(bankId);
        if (bank == null) {
            throw new NoSuchElementException("题库不存在：" + bankId);
        }
        List<Question> questions = questionMapper.selectList(
                new LambdaQueryWrapper<Question>().eq(Question::getBankId, bankId).orderByAsc(Question::getId));
        //题目范围过滤（打印/组卷数据源复用）：scope=all|favorite|wrong|undone + category/topic
        questions = filterExportQuestions(bankId, questions, request == null ? null : request.scope(),
                request == null ? null : request.category(), request == null ? null : request.topic());
        List<Material> materials = materialMapper.selectList(
                new LambdaQueryWrapper<Material>().eq(Material::getBankId, bankId)
                        .orderByAsc(Material::getSortOrder).orderByAsc(Material::getId));

        //材料 → 内容包 materialKey（导出内稳定：material-{本地id}）；
        //范围过滤后只保留被引用题的材料（错题/收藏集导出不携带无关材料）
        Set<Long> referencedMaterialIds = questions.stream()
                .map(Question::getMaterialId).filter(Objects::nonNull).collect(java.util.stream.Collectors.toSet());
        Map<Long, String> materialKeyById = new HashMap<>();
        List<ContentPackageMaterial> fileMaterials = new ArrayList<>();
        for (Material m : materials) {
            if (!referencedMaterialIds.contains(m.getId())) {
                continue;
            }
            String key = "material-" + m.getId();
            materialKeyById.put(m.getId(), key);
            ContentPackageMaterial fm = new ContentPackageMaterial();
            fm.setMaterialKey(key);
            fm.setContent(m.getContent());
            fileMaterials.add(fm);
        }

        List<ContentPackageQuestion> fileQuestions = toFileQuestions(questions, materialKeyById);

        //收集图片 base64：题干 / 参考答案 / 选项 / 材料内容中的 [图片:name] 引用
        List<String> texts = new ArrayList<>();
        for (Question q : questions) {
            texts.add(q.getContent());
            texts.add(q.getReferenceAnswer());
            if (q.getOptions() != null) {
                for (OptionItem o : q.getOptions()) {
                    if (o != null) {
                        texts.add(o.text());
                    }
                }
            }
        }
        for (Material m : materials) {
            texts.add(m.getContent());
        }
        Map<String, String> images = imageStorageService.collectBase64(bankId, texts);

        //checksum 只依赖内容字段，先构建内容探针（身份/元数据字段不影响指纹）
        ContentPackageFile contentProbe = new ContentPackageFile();
        contentProbe.setQuestions(fileQuestions);
        contentProbe.setMaterials(fileMaterials);
        contentProbe.setImages(images);
        String currentChecksum = computeChecksum(contentProbe);
        String mode = request == null ? null : request.mode();
        boolean contentChanged = !Objects.equals(bank.getChecksum(), currentChecksum);

        //packageKey 决策（mode：UPGRADE / BRANCH / null=AUTO）：
        //- 自建题库（无身份）→ 生成新身份
        //- UPGRADE（作者迭代，显式声明）→ 沿用当前身份，内容已修改则登记新版本
        //- BRANCH（显式派生）→ 新身份 + parentKey 指向当前身份
        //- AUTO：内容与导入一致 → 原样沿用身份（版本号不变，内容没变不产生"空升级"）；
        //        内容被修改过 → 默认分支（安全默认防冒用，作者可显式传 UPGRADE）
        String packageKey;
        String parentKey = null;
        if (bank.getPackageKey() == null || bank.getPackageKey().isBlank()) {
            //自建题库（无身份）：完整导出时"认领身份"——把生成的 packageKey/version/指纹回写题库。
            //此后该库的刷题记录可随记录文件跨设备迁移（记录文件以 packageKey+version 定位），
            //后续导出可走 UPGRADE 作者迭代闭环；范围过滤导出（打印/组卷）不认领，避免把子集内容登记成身份指纹
            boolean fullExport = request == null
                    || isBlank(request.scope()) || "all".equalsIgnoreCase(request.scope())
                    && isBlank(request.category()) && isBlank(request.topic());
            String claimedVersion = resolveVersion(request, bank);
            if (fullExport) {
                packageKey = generatePackageKey();
                bank.setPackageKey(packageKey);
                bank.setVersion(claimedVersion);
                bank.setSchemaVersion(1);
                bank.setChecksum(currentChecksum);
                questionBankMapper.updateById(bank);
            } else {
                //非完整导出（打印/组卷）：不认领，临时身份仅用于本次文件
                packageKey = generatePackageKey();
            }
        } else if ("UPGRADE".equalsIgnoreCase(mode)) {
            packageKey = bank.getPackageKey();
        } else if ("BRANCH".equalsIgnoreCase(mode)) {
            packageKey = generatePackageKey();
            parentKey = bank.getPackageKey();
        } else if (contentChanged) {
            packageKey = generatePackageKey();
            parentKey = bank.getPackageKey();
        } else {
            packageKey = bank.getPackageKey();
        }

        String version = resolveVersion(request, bank);

        //内容未变化时禁止变更版本号：版本号的意义是标记内容变化，内容不变产生"空升级"只会污染版本历史
        if (!contentChanged && bank.getVersion() != null && !version.equals(bank.getVersion())) {
            throw new IllegalArgumentException("题目内容未变化，无需变更版本号（保持 " + bank.getVersion() + " 原样导出）");
        }

        //作者以 UPGRADE 迭代且内容确实变化时，把新版本号与当前指纹登记回题库，
        //使后续"内容一致"判断基于最新导出快照（导出 → 再导出未改 → 同 key 同版本）
        if ("UPGRADE".equalsIgnoreCase(mode) && contentChanged) {
            bank.setVersion(version);
            bank.setChecksum(currentChecksum);
            questionBankMapper.updateById(bank);
        }

        ContentPackageFile file = new ContentPackageFile();
        file.setSchemaVersion(1);
        file.setPackageKey(packageKey);
        file.setTitle(bank.getName());
        file.setDescription(bank.getDescription());
        file.setVersion(version);
        file.setAuthorId(bank.getAuthorId());
        file.setAuthorName(resolveAuthorName(request, bank));
        file.setSource(bank.getSource());
        file.setParentKey(parentKey);
        //指纹仅随导出响应返回（前端生成登记清单用；保存内容包文件时剥离——文件本体不含 checksum）
        file.setChecksum(currentChecksum);
        file.setSources(parseSources(bank.getSources()));
        file.setCreatedAt(LocalDateTime.now());
        file.setQuestions(fileQuestions);
        file.setMaterials(fileMaterials);
        file.setImages(images);
        return file;
    }

    /**
     * v2 .tiku 容器导出（zip：package.json + media/ 图片二进制）。
     * 身份/版本/指纹决策全部复用 v1 导出（exportContentPackage 内部完成）；
     * 图片从 base64 解码为二进制写入 media/，package.json 不含 images 与 checksum。
     */
    public byte[] exportTikuPackage(Long bankId, ExportRequest request) {
        ContentPackageFile file = exportContentPackage(bankId, request);
        Map<String, byte[]> media = new LinkedHashMap<>();
        if (file.getImages() != null) {
            for (Map.Entry<String, String> e : file.getImages().entrySet()) {
                try {
                    media.put(e.getKey(), java.util.Base64.getDecoder().decode(e.getValue()));
                } catch (IllegalArgumentException ignored) {
                    //单张解码失败跳过（导出端图片刚读出，正常不会发生）
                }
            }
        }
        //容器内 package.json 不含图片数据与指纹（图片在 media/；checksum 由导入端重算）
        file.setImages(null);
        file.setChecksum(null);
        file.setSchemaVersion(PackageContainer.SCHEMA_V2);
        try {
            byte[] pkgJson = objectMapper.writeValueAsBytes(file);
            return PackageContainer.pack(pkgJson, media);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("内容包序列化失败", e);
        } catch (IOException e) {
            throw new IllegalStateException(".tiku 容器打包失败", e);
        }
    }

    // ==================== 解析与校验 ====================

    private ContentPackageFile parseAndValidate(String json) {
        ContentPackageFile file;
        try {
            file = objectMapper.readValue(json, ContentPackageFile.class);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("内容包文件格式错误：" + e.getOriginalMessage());
        }
        if (file.getSchemaVersion() == null
                || (file.getSchemaVersion() != 1 && file.getSchemaVersion() != PackageContainer.SCHEMA_V2)) {
            throw new IllegalArgumentException("不支持的内容包格式版本：" + file.getSchemaVersion());
        }
        if (file.getPackageKey() == null || file.getPackageKey().isBlank()) {
            throw new IllegalArgumentException("内容包缺少 packageKey");
        }
        if (file.getTitle() == null || file.getTitle().isBlank()) {
            throw new IllegalArgumentException("内容包缺少标题");
        }
        if (file.getVersion() == null || file.getVersion().isBlank()) {
            throw new IllegalArgumentException("内容包缺少版本号");
        }
        if (file.getQuestions() != null) {
            for (ContentPackageQuestion q : file.getQuestions()) {
                if (q.getQuestionKey() == null || q.getQuestionKey().isBlank()) {
                    throw new IllegalArgumentException("题目缺少 questionKey");
                }
                if (q.getContent() == null || q.getContent().isBlank()) {
                    throw new IllegalArgumentException("题目题干为空：" + q.getQuestionKey());
                }
                try {
                    QuestionType.valueOf(q.getType());
                } catch (Exception e) {
                    throw new IllegalArgumentException("未知题型：" + q.getType());
                }
            }
        }
        return file;
    }

    // ==================== 转换 ====================

    private QuestionBank buildBankFromFile(ContentPackageFile file, String packageKey, String parentKey, String checksum) {
        QuestionBank bank = new QuestionBank();
        bank.setName(file.getTitle());
        bank.setDescription(file.getDescription());
        bank.setPackageKey(packageKey);
        bank.setVersion(file.getVersion());
        bank.setSchemaVersion(file.getSchemaVersion());
        bank.setChecksum(checksum);
        bank.setAuthorId(file.getAuthorId());
        bank.setAuthorName(file.getAuthorName());
        bank.setSource(file.getSource());
        bank.setSources(serializeSources(file.getSources()));
        bank.setParentKey(parentKey);
        return bank;
    }

    /** 落盘内容包内容：图片 → 材料 → 题目（材料 id 映射给题目引用） */
    private void persistContent(Long bankId, ContentPackageFile file) {
        imageStorageService.saveBase64(bankId, file.getImages());
        Map<String, Long> materialIdByKey = insertMaterials(bankId, file.getMaterials());
        insertQuestions(bankId, file.getQuestions(), materialIdByKey);
    }

    /** 材料导入：返回 materialKey → 本地 material_id 映射 */
    private Map<String, Long> insertMaterials(Long bankId, List<ContentPackageMaterial> fileMaterials) {
        Map<String, Long> idByKey = new HashMap<>();
        if (fileMaterials == null) {
            return idByKey;
        }
        int order = 0;
        for (ContentPackageMaterial fm : fileMaterials) {
            if (fm.getMaterialKey() == null || fm.getMaterialKey().isBlank()) {
                throw new IllegalArgumentException("内容包材料缺少 materialKey");
            }
            Material material = new Material();
            material.setBankId(bankId);
            material.setContent(fm.getContent());
            material.setSortOrder(order++);
            LocalDateTime now = LocalDateTime.now();
            material.setCreatedAt(now);
            material.setUpdatedAt(now);
            materialMapper.insert(material);
            idByKey.put(fm.getMaterialKey(), material.getId());
        }
        return idByKey;
    }

    /** 公共入口：把题目列表插入指定题库（AI 导入确认等场景复用），返回插入数量 */
    public int importQuestionsToBank(Long bankId, List<ContentPackageQuestion> questions) {
        return importQuestionsToBank(bankId, questions, null);
    }

    public int importQuestionsToBank(Long bankId, List<ContentPackageQuestion> questions, Map<String, Long> materialIdByKey) {
        int count = 0;
        //题号兜底：AI 导入的题目通常没有题号（null），按插入顺序从当前最大题号+1 递增，
        //保证列表/做题/跳转定位顺序统一（SEQUENCE 按 questionNumber,id 排序，null 会排最前导致顺序错乱）
        Integer nextNumber = null;
        Question maxQ = questionMapper.selectOne(
                new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<Question>()
                        .eq(Question::getBankId, bankId)
                        .orderByDesc(Question::getQuestionNumber).last("LIMIT 1"));
        if (maxQ != null && maxQ.getQuestionNumber() != null) {
            nextNumber = maxQ.getQuestionNumber() + 1;
        }
        for (ContentPackageQuestion q : questions) {
            Question question = new Question();
            //external_id NOT NULL：预览编辑后提交的题目可能丢 questionKey（前端对象重建）→ 自动生成兜底
            if (q.getQuestionKey() == null || q.getQuestionKey().isBlank()) {
                question.setExternalId("AI_" + java.util.UUID.randomUUID().toString()
                        .replace("-", "").substring(0, 12).toUpperCase());
            } else {
                question.setExternalId(q.getQuestionKey());
            }
            question.setVolume(q.getVolume() == null ? 0 : q.getVolume());
            question.setQuestionType(QuestionType.valueOf(q.getType()));
            question.setContent(q.getContent());
            question.setOptions(q.getOptions());
            String trimmedKeys = q.getAnswerKeys() == null ? null
                    : q.getAnswerKeys().stream().filter(Objects::nonNull).map(String::trim)
                            .filter(s -> !s.isBlank()).collect(java.util.stream.Collectors.joining(","));
            question.setAnswerKeys(trimmedKeys == null || trimmedKeys.isEmpty() ? null : trimmedKeys);
            question.setAnswerText(q.getAnswerText());
            question.setAnalysis(q.getAnalysis());
            question.setTopic(q.getTopic());
            question.setCategory(q.getCategory());
            question.setScore(q.getScore() == null ? 1.0 : q.getScore());
            question.setReferenceAnswer(q.getReferenceAnswer());
            if (q.getQuestionNumber() != null) {
                question.setQuestionNumber(q.getQuestionNumber());
            } else {
                question.setQuestionNumber(nextNumber == null ? 1 : nextNumber);
                nextNumber = (nextNumber == null ? 1 : nextNumber) + 1;
            }
            if (q.getMaterialKey() != null && !q.getMaterialKey().isBlank()) {
                Long materialId = materialIdByKey == null ? null : materialIdByKey.get(q.getMaterialKey());
                if (materialId == null) {
                    throw new IllegalArgumentException("题目引用的材料不存在：" + q.getMaterialKey());
                }
                question.setMaterialId(materialId);
            }
            question.setBankId(bankId);
            questionMapper.insert(question);
            count++;
        }
        return count;
    }

    private void insertQuestions(Long bankId, List<ContentPackageQuestion> fileQuestions, Map<String, Long> materialIdByKey) {
        importQuestionsToBank(bankId, fileQuestions, materialIdByKey);
    }

    private List<ContentPackageQuestion> toFileQuestions(List<Question> questions, Map<Long, String> materialKeyById) {
        List<ContentPackageQuestion> result = new ArrayList<>();
        for (Question q : questions) {
            ContentPackageQuestion fq = new ContentPackageQuestion();
            fq.setQuestionKey(q.getExternalId());
            fq.setVolume(q.getVolume());
            fq.setType(q.getQuestionType().name());
            fq.setContent(q.getContent());
            fq.setOptions(q.getOptions());
            fq.setAnswerKeys(q.getAnswerKeys() == null || q.getAnswerKeys().isBlank()
                    ? List.of()
                    : Arrays.stream(q.getAnswerKeys().split(",")).map(String::trim).toList());
            fq.setAnswerText(q.getAnswerText());
            fq.setAnalysis(q.getAnalysis());
            fq.setTopic(q.getTopic());
            fq.setCategory(q.getCategory());
            fq.setScore(q.getScore());
            fq.setReferenceAnswer(q.getReferenceAnswer());
            if (q.getMaterialId() != null && materialKeyById != null) {
                fq.setMaterialKey(materialKeyById.get(q.getMaterialId()));
            }
            result.add(fq);
        }
        return result;
    }

    private String serializeSources(List<String> sources) {
        if (sources == null) {
            return null;
        }
        try {
            return objectMapper.writeValueAsString(sources);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("来源数组序列化失败", e);
        }
    }

    private List<String> parseSources(String sourcesJson) {
        if (sourcesJson == null || sourcesJson.isBlank()) {
            return null;
        }
        try {
            return objectMapper.readValue(sourcesJson, new TypeReference<List<String>>() {});
        } catch (JsonProcessingException e) {
            return null;
        }
    }

    // ==================== 工具 ====================

    //对内容部分（题目 + 材料 + 图片）按固定字段顺序序列化后计算 SHA-256（规范化：导入/导出同一逻辑，保证可比对）。
    //无材料且无图片时退化为"仅题目数组"序列化，与旧版本 checksum 完全兼容（存量题库不因升级误判内容已变）。
    private String computeChecksum(ContentPackageFile file) {
        try {
            boolean hasExtension = (file.getMaterials() != null && !file.getMaterials().isEmpty())
                    || (file.getImages() != null && !file.getImages().isEmpty());
            Object content;
            if (hasExtension) {
                Map<String, Object> fields = new LinkedHashMap<>();
                fields.put("questions", file.getQuestions() == null ? List.of() : file.getQuestions());
                fields.put("materials", file.getMaterials() == null ? List.of() : file.getMaterials());
                fields.put("images", file.getImages() == null ? Map.of() : new TreeMap<>(file.getImages()));
                content = fields;
            } else {
                content = file.getQuestions() == null ? List.of() : file.getQuestions();
            }
            String json = objectMapper.writeValueAsString(content);
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(json.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("内容序列化失败", e);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 不可用", e);
        }
    }

    private String resolveVersion(ExportRequest request, QuestionBank bank) {
        if (request != null && request.version() != null && !request.version().isBlank()) {
            return request.version();
        }
        if (bank.getVersion() != null && !bank.getVersion().isBlank()) {
            return bank.getVersion();
        }
        return "1.0.0";
    }

    private String resolveAuthorName(ExportRequest request, QuestionBank bank) {
        if (request != null && request.authorName() != null && !request.authorName().isBlank()) {
            return request.authorName();
        }
        return bank.getAuthorName();
    }

    private String generatePackageKey() {
        return UUID.randomUUID().toString();
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }
}
