package com.tiku.service;

import com.tiku.dto.AiJobResponse;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * AI 任务事件推送（SSE）：
 * - 前端订阅具体任务（GET /api/ai-import/jobs/{id}/stream），收到阶段/完成事件
 * - 与轮询并存：SSE 断开时前端自动回退轮询
 */
@Service
public class AiJobEventService {

    private static final long EMITTER_TIMEOUT = Duration.ofMinutes(15).toMillis();

    private final Map<Long, List<SseEmitter>> emitters = new ConcurrentHashMap<>();

    /** 订阅任务事件流 */
    public SseEmitter subscribe(Long jobId) {
        SseEmitter emitter = new SseEmitter(EMITTER_TIMEOUT);
        emitters.computeIfAbsent(jobId, k -> new CopyOnWriteArrayList<>()).add(emitter);
        emitter.onCompletion(() -> remove(jobId, emitter));
        emitter.onTimeout(() -> remove(jobId, emitter));
        emitter.onError(e -> remove(jobId, emitter));
        return emitter;
    }

    /** 推送任务快照（阶段变化/进度） */
    public void publish(Long jobId, AiJobResponse snapshot) {
        List<SseEmitter> list = emitters.get(jobId);
        if (list == null) {
            return;
        }
        for (SseEmitter emitter : list) {
            try {
                emitter.send(SseEmitter.event().name("update").data(snapshot));
            } catch (Exception e) {
                remove(jobId, emitter);
            }
        }
    }

    /** 推送最终快照并结束事件流（任务完成/失败） */
    public void complete(Long jobId, AiJobResponse snapshot) {
        publish(jobId, snapshot);
        List<SseEmitter> list = emitters.remove(jobId);
        if (list != null) {
            for (SseEmitter emitter : list) {
                try {
                    emitter.complete();
                } catch (Exception ignored) {
                    //已断开
                }
            }
        }
    }

    private void remove(Long jobId, SseEmitter emitter) {
        List<SseEmitter> list = emitters.get(jobId);
        if (list != null) {
            list.remove(emitter);
            if (list.isEmpty()) {
                //空列表键不再滞留（防订阅过取消/超时任务后 map 空 key 泄漏）
                emitters.remove(jobId, list);
            }
        }
    }
}
