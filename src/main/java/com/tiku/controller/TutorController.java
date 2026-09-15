package com.tiku.controller;

import com.tiku.dto.ApiResponse;
import com.tiku.dto.TutorAskRequest;
import com.tiku.model.TutorSession;
import com.tiku.service.TutorService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * AI 私教（学习路径引擎阶段 1）：提示楼梯 / 答错即问 / 自由追问 / 整场复盘。
 * 设计见 docs/learning-path-design.md §7.3、§7.4。
 *
 * 三个生成类接口都是 **SSE 流式**（`text/event-stream`，事件名 session/delta/done/error）：
 * 首字很快就能显示，长回答不用干等；模型网关不支持流式时后端自动降级为一次性返回，
 * 前端收到一个 delta 就渲染（体验退化但不失败）。
 *
 * 会话由后端复用（同题 + 同场练习的未关闭会话），所以前端每次只要带
 * bankId/questionId/practiceSessionId，不必自己管理 sessionId——`session` 事件会把 id 带回来。
 */
@Slf4j
@RestController
@RequestMapping("/api/tutor")
public class TutorController {

    private static final long STREAM_TIMEOUT_MS = 10 * 60 * 1000L;

    private final TutorService tutorService;
    /** 流式生成的线程池：AI 调用是阻塞 IO，与请求线程分开；守护线程保证应用能退出 */
    private final ExecutorService streamPool = Executors.newFixedThreadPool(4, r -> {
        Thread t = new Thread(r, "tutor-stream");
        t.setDaemon(true);
        return t;
    });

    public TutorController(TutorService tutorService) {
        this.tutorService = tutorService;
    }

    /** 三级提示（1 指方向 / 2 关键一步 / 3 完整解析） */
    @PostMapping(value = "/hint", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter hint(@RequestBody TutorAskRequest request) {
        return stream(request, (sessionId, delta) ->
                tutorService.hint(sessionId, levelOf(request), request.templateId(), delta));
    }

    /** 答错即问 / 自由追问（同一入口：带 selfReason 就是"答错即问"） */
    @PostMapping(value = "/ask", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter ask(@RequestBody TutorAskRequest request) {
        return stream(request, (sessionId, delta) -> tutorService.ask(sessionId, request.text(), request.templateId(), delta));
    }

    /** 单题讲解（做题后唯一的解析入口）：一次给出三段——有作答讲「错在哪」，没作答讲「这道题怎么做」 */
    @PostMapping(value = "/explain", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter explain(@RequestBody TutorAskRequest request) {
        if (request.questionId() == null) {
            throw new IllegalArgumentException("讲解需要指定题目");
        }
        return stream(request, (sessionId, delta) ->
                tutorService.explain(sessionId, request.templateId(), request.mode(), delta));
    }

    /** 整场复盘诊断（这是"答错即问一句"的批量版本：一次看完这场的问题） */
    @PostMapping(value = "/review", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter review(@RequestBody TutorAskRequest request) {
        return stream(request, (sessionId, delta) -> tutorService.diagnose(sessionId, request.templateId(), delta));
    }

    /** 本场复盘的**公式统计**（成绩、按知识点的错题分布、错题清单）——模型只负责把人话说出来 */
    @GetMapping("/review/{practiceSessionId}/summary")
    public ApiResponse<TutorService.ReviewSummary> summary(@PathVariable Long practiceSessionId,
                                                           @RequestParam Long bankId,
                                                           @RequestParam(required = false) String templateId) {
        return ApiResponse.success(tutorService.reviewSummary(bankId, practiceSessionId, templateId));
    }

    /** 某题的追问历史（错题本/题目详情"问老师"要能接着上次聊） */
    @GetMapping("/sessions")
    public ApiResponse<List<TutorService.SessionView>> sessions(@RequestParam Long questionId) {
        return ApiResponse.success(tutorService.sessionsOfQuestion(questionId));
    }

    /** 一场会话的全部消息 */
    @GetMapping("/sessions/{sessionId}/messages")
    public ApiResponse<Map<String, Object>> messages(@PathVariable Long sessionId) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("messages", tutorService.messages(sessionId));
        out.put("maxHintLevel", tutorService.maxHintLevel(sessionId));
        return ApiResponse.success(out);
    }

    // ==================== 内部 ====================

    private Long sessionOf(TutorAskRequest request) {
        if (request.bankId() == null) {
            throw new IllegalArgumentException("缺少 bankId");
        }
        boolean review = TutorSession.KIND_POST_REVIEW.equals(request.kind());
        TutorSession session = tutorService.openSession(request.bankId(), request.questionId(),
                request.practiceSessionId(), review ? TutorSession.KIND_POST_REVIEW : TutorSession.KIND_PER_QUESTION,
                request.selfReason(), request.selfNote());
        return session.getId();
    }

    private static int levelOf(TutorAskRequest request) {
        return request.level() == null ? 1 : request.level();
    }

    /** 一次流式生成的工作单元：拿到会话 id 与"每段文本"回调，去调用 TutorService */
    private interface StreamWork {
        void run(Long sessionId, java.util.function.Consumer<String> onDelta);
    }

    /** 把一次生成过程包成 SSE：先回 session 事件，再逐段 delta，最后 done（失败则 error） */
    private SseEmitter stream(TutorAskRequest request, StreamWork work) {
        SseEmitter emitter = new SseEmitter(STREAM_TIMEOUT_MS);
        emitter.onTimeout(emitter::complete);
        streamPool.execute(() -> {
            try {
                Long sessionId = sessionOf(request);
                send(emitter, "session", Map.of("sessionId", sessionId, "maxHintLevel", tutorService.maxHintLevel(sessionId)));
                work.run(sessionId, delta -> send(emitter, "delta", Map.of("text", delta)));
                send(emitter, "done", Map.of("sessionId", sessionId, "maxHintLevel", tutorService.maxHintLevel(sessionId)));
                emitter.complete();
            } catch (Exception e) {
                // 出错也要让前端知道原因（可照做的提示：未配置 AI / 模型报错 / 题干信息不足等）
                String message = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
                log.warn("AI 私教生成失败：{}", message);
                send(emitter, "error", Map.of("message", message));
                emitter.complete();
            }
        });
        return emitter;
    }

    private void send(SseEmitter emitter, String event, Object data) {
        try {
            emitter.send(SseEmitter.event().name(event).data(data));
        } catch (Exception e) {
            // 客户端断开（用户关了面板/切页）→ 结束这次推送，不当作错误
            log.debug("SSE 推送中断（客户端可能已断开）：{}", e.getMessage());
            emitter.complete();
        }
    }
}
