package com.tiku.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tiku.config.AiSettings;
import com.tiku.mapper.QuestionBankMapper;
import com.tiku.mapper.QuestionMapper;
import com.tiku.model.OptionItem;
import com.tiku.model.Question;
import com.tiku.model.QuestionBank;
import com.tiku.model.enums.QuestionType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 库内无答案客观题 AI 批量补答案（"答案后配"的批量兑现）：
 * 串行分批（10 题/批）思考模式判定；有图题（[图片:name]）图片随消息提供；
 * 无法确定/选项不完整/请求失败 → 留空不写库，计数返回由前端提示。
 */
@Service
public class AnswerFillService {

    private static final Logger log = LoggerFactory.getLogger(AnswerFillService.class);
    private static final Pattern IMAGE_REF = Pattern.compile("\\[图片:([^\\]]+)\\]");
    private static final int BATCH_SIZE = 10;

    private final QuestionBankMapper questionBankMapper;
    private final QuestionMapper questionMapper;
    private final AiConfigService aiConfigService;
    private final AiClientService aiClientService;
    private final ImageStorageService imageStorageService;
    private final ObjectMapper objectMapper;

    public AnswerFillService(QuestionBankMapper questionBankMapper,
                             QuestionMapper questionMapper,
                             AiConfigService aiConfigService,
                             AiClientService aiClientService,
                             ImageStorageService imageStorageService,
                             ObjectMapper objectMapper) {
        this.questionBankMapper = questionBankMapper;
        this.questionMapper = questionMapper;
        this.aiConfigService = aiConfigService;
        this.aiClientService = aiClientService;
        this.imageStorageService = imageStorageService;
        this.objectMapper = objectMapper;
    }

    /** 补答案结果 */
    public record FillResult(int total, int filled, int undetermined, int failed) {
    }

    public FillResult fill(Long bankId, QuestionType typeFilter, boolean withAnalysis) {
        QuestionBank bank = questionBankMapper.selectById(bankId);
        if (bank == null) {
            throw new NoSuchElementException("题库不存在：" + bankId);
        }
        AiSettings settings = aiConfigService.load();
        if (settings.getApiKey() == null || settings.getApiKey().isBlank()) {
            throw new IllegalArgumentException("未配置 AI 模型 Key，请先到「设置-AI 模型配置」填写");
        }
        //目标：本库无答案的客观题（可按题型过滤）
        LambdaQueryWrapper<Question> wrapper = new LambdaQueryWrapper<Question>()
                .eq(Question::getBankId, bankId)
                .ne(Question::getQuestionType, QuestionType.SUBJECTIVE)
                .and(w -> w.isNull(Question::getAnswerKeys).or().eq(Question::getAnswerKeys, ""))
                .orderByAsc(Question::getQuestionNumber).orderByAsc(Question::getId);
        if (typeFilter != null) {
            wrapper.eq(Question::getQuestionType, typeFilter);
        }
        List<Question> missing = questionMapper.selectList(wrapper);
        if (missing.isEmpty()) {
            return new FillResult(0, 0, 0, 0);
        }

        //强制思考模式
        AiSettings think = new AiSettings();
        think.setBaseUrl(settings.getBaseUrl());
        think.setApiKey(settings.getApiKey());
        think.setModel(settings.getModel());
        think.setVisionModel(settings.getVisionModel());
        think.setThinking(true);
        String system = "你是题库答案助手。只输出要求的 JSON，不要输出任何其他文字。";

        int filled = 0;
        int undetermined = 0;
        int failed = 0;
        //逐批串行（单用户本地；每批一次思考调用，稳定性优先）
        for (int start = 0; start < missing.size(); start += BATCH_SIZE) {
            List<Question> batch = missing.subList(start, Math.min(missing.size(), start + BATCH_SIZE));
            int[] r = fillBatch(bankId, batch, think, system, withAnalysis);
            filled += r[0];
            undetermined += r[1];
            failed += r[2];
        }
        log.info("AI 补答案：题库 {} 共 {} 题，补 {} 题，留空 {} 题，失败 {} 题", bankId, missing.size(), filled, undetermined, failed);
        return new FillResult(missing.size(), filled, undetermined, failed);
    }

    /** 处理一批；返回 [filled, undetermined, failed] */
    private int[] fillBatch(Long bankId, List<Question> batch, AiSettings think, String system, boolean withAnalysis) {
        int filled = 0;
        int undetermined = 0;
        int failed = 0;
        List<Question> workable = new ArrayList<>();
        for (Question q : batch) {
            if (q.getContent() == null || q.getContent().isBlank()) {
                undetermined++;
                continue;
            }
            boolean judge = q.getQuestionType() == QuestionType.JUDGE;
            if (!judge) {
                List<OptionItem> opts = q.getOptions();
                if (opts == null || opts.size() < 2
                        || opts.stream().anyMatch(o -> o == null || o.text() == null || o.text().isBlank())) {
                    undetermined++; //选项不完整：无法可靠判定，留空
                    continue;
                }
            }
            workable.add(q);
        }
        if (workable.isEmpty()) {
            return new int[]{0, undetermined, 0};
        }

        StringBuilder sb = new StringBuilder();
        sb.append("你是题库答案助手。下面是 ").append(workable.size())
                .append(" 道客观题（题干+选项），请为每道题给出正确答案")
                .append(withAnalysis ? "和简要解析" : "").append("。\n")
                .append("严格输出一个 JSON 对象：{\"answers\":[{\"index\":1,\"answerKeys\":[\"B\"]")
                .append(withAnalysis ? ",\"analysis\":\"...\"" : "")
                .append("}]}\n")
                .append("规则：index 从 1 开始对应题目顺序；answerKeys 必须是选项 key（单选/判断一个，多选多个，大写字母数组）；")
                .append("判断题选项固定 A=正确/B=错误；题干或选项中的 [图片:文件名] 表示该处有图（图片已随消息提供），请结合图片判断；")
                .append("无法确定答案时 answerKeys 返回空数组 []，不要编造。")
                .append(withAnalysis ? "解析简明（一两句即可）。" : "").append("\n\n");
        List<AiClientService.ImageData> imgs = new ArrayList<>();
        Set<String> seenImgs = new HashSet<>();
        for (int i = 0; i < workable.size(); i++) {
            Question q = workable.get(i);
            sb.append(i + 1).append(". ").append(q.getContent()).append('\n');
            if (q.getQuestionType() == QuestionType.JUDGE) {
                sb.append("   A. 正确   B. 错误\n");
            } else if (q.getOptions() != null) {
                for (OptionItem o : q.getOptions()) {
                    sb.append("   ").append(o.key()).append(". ").append(o.text()).append('\n');
                }
            }
            sb.append('\n');
            collectImages(bankId, q.getContent(), imgs, seenImgs);
            if (q.getOptions() != null) {
                for (OptionItem o : q.getOptions()) {
                    if (o != null) {
                        collectImages(bankId, o.text(), imgs, seenImgs);
                    }
                }
            }
        }

        try {
            String out = imgs.isEmpty()
                    ? aiClientService.chat(think, system, sb.toString(), true)
                    : aiClientService.chatWithImages(think, system, sb.toString(), imgs, true);
            JsonNode root = objectMapper.readTree(stripCodeFence(out));
            JsonNode answers = root.path("answers");
            if (!answers.isArray()) {
                log.warn("AI 补答案输出格式异常（无 answers 数组），批次留空");
                undetermined += workable.size();
                return new int[]{0, undetermined, 0};
            }
            for (JsonNode item : answers) {
                int index = item.path("index").asInt() - 1;
                if (index < 0 || index >= workable.size()) {
                    continue;
                }
                Question q = workable.get(index);
                List<String> keys = validateKeys(q, parseKeys(item.path("answerKeys")));
                if (keys.isEmpty()) {
                    undetermined++;
                    continue;
                }
                LambdaUpdateWrapper<Question> upd = new LambdaUpdateWrapper<Question>()
                        .eq(Question::getId, q.getId())
                        .set(Question::getAnswerKeys, String.join(",", keys))
                        .set(Question::getUpdatedAt, LocalDateTime.now());
                if (withAnalysis && (q.getAnalysis() == null || q.getAnalysis().isBlank())) {
                    String analysis = item.path("analysis").asText(null);
                    if (analysis != null && !analysis.isBlank()) {
                        upd.set(Question::getAnalysis,
                                analysis.length() > 20000 ? analysis.substring(0, 20000) : analysis);
                    }
                }
                questionMapper.update(null, upd);
                filled++;
            }
        } catch (Exception e) {
            log.warn("AI 补答案批次失败（{} 题）：{}", workable.size(), e.getMessage());
            failed += workable.size();
        }
        return new int[]{filled, undetermined, failed};
    }

    private void collectImages(Long bankId, String text, List<AiClientService.ImageData> out, Set<String> seen) {
        if (text == null) {
            return;
        }
        Matcher m = IMAGE_REF.matcher(text);
        while (m.find()) {
            String name = m.group(1);
            if (!seen.add(name)) {
                continue;
            }
            try {
                byte[] bytes = imageStorageService.read(bankId, name);
                if (bytes != null && bytes.length > 0) {
                    out.add(new AiClientService.ImageData(mimeOf(name), bytes));
                }
            } catch (Exception e) {
                log.debug("AI 补答案读图失败 {}: {}", name, e.getMessage());
            }
        }
    }

    private List<String> parseKeys(JsonNode node) {
        List<String> keys = new ArrayList<>();
        if (node.isArray()) {
            for (JsonNode k : node) {
                String s = k.asText().trim().toUpperCase();
                if (s.matches("[A-H]")) {
                    keys.add(s);
                }
            }
        } else if (node.isTextual() && !node.asText().isBlank()) {
            for (char c : node.asText().toUpperCase().toCharArray()) {
                String v = String.valueOf(c);
                if (v.matches("[A-H]")) {
                    keys.add(v);
                }
            }
        }
        return keys;
    }

    private List<String> validateKeys(Question q, List<String> keys) {
        if (keys.isEmpty()) {
            return keys;
        }
        Set<String> valid = new HashSet<>();
        if (q.getQuestionType() == QuestionType.JUDGE) {
            valid.add("A");
            valid.add("B");
        } else if (q.getOptions() != null) {
            for (OptionItem o : q.getOptions()) {
                if (o != null && o.key() != null) {
                    valid.add(o.key().trim().toUpperCase());
                }
            }
        }
        if (valid.isEmpty()) {
            return List.of();
        }
        Set<String> dedup = new HashSet<>();
        List<String> filtered = new ArrayList<>();
        for (String k : keys) {
            if (valid.contains(k) && dedup.add(k)) {
                filtered.add(k);
            }
        }
        return filtered;
    }

    private String stripCodeFence(String s) {
        if (s == null) {
            return "{}";
        }
        String t = s.trim();
        int start = t.indexOf('{');
        int end = t.lastIndexOf('}');
        if (start >= 0 && end > start) {
            return t.substring(start, end + 1);
        }
        return t;
    }

    private String mimeOf(String name) {
        String lower = name == null ? "" : name.toLowerCase();
        if (lower.endsWith(".jpg") || lower.endsWith(".jpeg")) {
            return "image/jpeg";
        }
        if (lower.endsWith(".webp")) {
            return "image/webp";
        }
        return "image/png";
    }
}
