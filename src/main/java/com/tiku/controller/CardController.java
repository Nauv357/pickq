package com.tiku.controller;

import com.tiku.dto.ApiResponse;
import com.tiku.service.CardService;
import org.springframework.web.bind.annotation.*;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 闪卡（学习路径引擎阶段 3，设计 §7.5）：从解析里挖空的记忆卡。
 *
 * 与题目的分工：题目测**再认**，卡片测**回忆**。所以
 * - 卡片答对**不足以**把知识点判为已掌握（只作负向证据：忘了会让已过关的节点掉回 REGRESSED）；
 * - AI 生成的卡默认**未确认**，未确认不参与复习调度（先让人过一眼，别让没校对的卡占满队列）；
 * - 每张卡都带出处题（questionId），界面可点回原题。
 */
@RestController
@RequestMapping("/api/cards")
public class CardController {

    private final CardService cardService;

    public CardController(CardService cardService) {
        this.cardService = cardService;
    }

    /** 生成闪卡（从解析挖空；按批推进，可中断续跑；已有卡的题跳过） */
    @PostMapping("/generate")
    public ApiResponse<CardService.GenerateResult> generate(@RequestParam Long bankId,
                                                            @RequestParam(required = false) String templateId,
                                                            @RequestParam(required = false) String nodeId,
                                                            @RequestParam(required = false) Integer maxAiCalls) {
        return ApiResponse.success(cardService.generate(bankId, templateId, nodeId, maxAiCalls));
    }

    /** 卡片列表：status = all / unconfirmed / confirmed / due */
    @GetMapping
    public ApiResponse<List<CardService.CardView>> list(@RequestParam Long bankId,
                                                        @RequestParam(required = false) String status,
                                                        @RequestParam(required = false) String templateId) {
        return ApiResponse.success(cardService.list(bankId, status, templateId));
    }

    /** 今天要复习的卡（只含已确认的） */
    @GetMapping("/due")
    public ApiResponse<List<CardService.CardView>> due(@RequestParam Long bankId,
                                                       @RequestParam(defaultValue = "50") int limit,
                                                       @RequestParam(required = false) String templateId) {
        return ApiResponse.success(cardService.due(bankId, limit, templateId));
    }

    /** 卡片统计（总数 / 未确认 / 到期 / 已熟练） */
    @GetMapping("/stats")
    public ApiResponse<CardService.CardStats> stats(@RequestParam Long bankId) {
        return ApiResponse.success(cardService.stats(bankId));
    }

    /** 复习一张卡：remembered=true 记得，false 忘了（忘了会 lapses+1、立刻重来） */
    @PostMapping("/{cardId}/review")
    public ApiResponse<CardService.CardView> review(@RequestParam Long bankId, @PathVariable Long cardId,
                                                    @RequestParam boolean remembered) {
        return ApiResponse.success(cardService.review(bankId, cardId, remembered));
    }

    /** 确认 / 取消确认（未确认的卡不进复习队列） */
    @PostMapping("/confirm")
    public ApiResponse<Map<String, Object>> confirm(@RequestParam Long bankId, @RequestBody Map<String, Object> body) {
        boolean confirmed = !Boolean.FALSE.equals(body.get("confirmed"));
        int n = cardService.confirm(bankId, ids(body.get("cardIds")), confirmed);
        return ApiResponse.success(Map.of("affected", n));
    }

    /** 编辑卡片（人工改过的视为人工确认，直接进队列） */
    @PutMapping("/{cardId}")
    public ApiResponse<CardService.CardView> update(@RequestParam Long bankId, @PathVariable Long cardId,
                                                   @RequestBody Map<String, Object> body) {
        String front = body.get("front") == null ? null : String.valueOf(body.get("front"));
        String back = body.get("back") == null ? null : String.valueOf(body.get("back"));
        return ApiResponse.success(cardService.update(bankId, cardId, front, back));
    }

    @DeleteMapping
    public ApiResponse<Map<String, Object>> delete(@RequestParam Long bankId, @RequestBody Map<String, Object> body) {
        return ApiResponse.success(Map.of("affected", cardService.delete(bankId, ids(body.get("cardIds")))));
    }

    private static List<Long> ids(Object raw) {
        List<Long> out = new ArrayList<>();
        if (raw instanceof List<?> list) {
            for (Object o : list) {
                if (o != null) {
                    try {
                        out.add(Long.valueOf(String.valueOf(o)));
                    } catch (NumberFormatException ignored) {
                        // 忽略非数字项
                    }
                }
            }
        }
        return out;
    }
}
