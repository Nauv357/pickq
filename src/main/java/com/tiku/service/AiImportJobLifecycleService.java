package com.tiku.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.tiku.dto.AiJobResponse;
import com.tiku.mapper.AiImportJobMapper;
import com.tiku.model.AiImportJob;
import com.tiku.model.ContentPackageMaterial;
import com.tiku.model.ContentPackageQuestion;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.NoSuchElementException;

/**
 * AI 导入任务的**行、临时文件与事件**（任务的"生命周期"，不含导入流程编排）。
 *
 * 从 {@link AiImportService} 迁出（该服务原本把编排、版面算法、任务状态机混在一起）。
 * 这里只负责：
 * - 任务行的读/写（创建由 AiImportService 负责：它还要把任务提交给执行器）；
 * - 状态机与取消语义（PENDING/PROCESSING/SUCCESS/FAILED/CANCELED 的条件更新，取消后不得复活）；
 * - 启动自愈（上次进程遗留的 PENDING/PROCESSING → FAILED）与终态任务目录清理；
 * - 任务快照（轮询 / SSE / active / recent 共用一个 toResponse）与 SSE 订阅。
 *
 * 不涉及 AI 调用、文档解析与题目写入——那些在 AiImportService 与其协作组件里。
 */
@Slf4j
@Service
public class AiImportJobLifecycleService {

    private final AiImportJobMapper jobMapper;
    private final AiImportJobStorageService jobStorageService;
    private final AiJobEventService aiJobEventService;
    private final AiImportResultCodec resultCodec;

    public AiImportJobLifecycleService(AiImportJobMapper jobMapper,
                                       AiImportJobStorageService jobStorageService,
                                       AiJobEventService aiJobEventService,
                                       AiImportResultCodec resultCodec) {
        this.jobMapper = jobMapper;
        this.jobStorageService = jobStorageService;
        this.aiJobEventService = aiJobEventService;
        this.resultCodec = resultCodec;
    }

    /**
     * 应用启动自愈（桌面应用经常直接关闭/重启）：
     * 1) 上一进程中断遗留的 PENDING/PROCESSING 任务 → 标记 FAILED（错误信息可读）并清理其文件，
     *    避免永久"整理中"徽标（getJob 的 10 分钟兜底只覆盖 PENDING，PROCESSING 无兜底）；
     * 2) 兜底清理：终态任务目录超 7 天未清理（含已物理删行但目录残留）→ 删除，防磁盘持续泄漏。
     */
    @jakarta.annotation.PostConstruct
    public void recoverInterruptedJobsOnStartup() {
        try {
            if (jobMapper == null) {
                return;
            }
            LocalDateTime now = LocalDateTime.now();
            List<AiImportJob> interrupted = jobMapper.selectList(new LambdaQueryWrapper<AiImportJob>()
                    .in(AiImportJob::getStatus, "PENDING", "PROCESSING"));
            for (AiImportJob job : interrupted) {
                job.setStatus("FAILED");
                job.setStage("DONE");
                job.setError("应用上次退出中断了该任务，请删除后重新导入");
                job.setErrorCode("APPLICATION_INTERRUPTED");
                job.setFinishedAt(now);
                jobMapper.updateById(job);
                deleteJobFiles(job.getId());
            }
            java.time.LocalDateTime deadline = now.minusDays(7);
            List<AiImportJob> stale = jobMapper.selectList(new LambdaQueryWrapper<AiImportJob>()
                    .in(AiImportJob::getStatus, "SUCCESS", "FAILED", "CANCELED")
                    .isNotNull(AiImportJob::getFinishedAt)
                    .lt(AiImportJob::getFinishedAt, deadline));
            for (AiImportJob job : stale) {
                deleteJobFiles(job.getId());
            }
            //任务行已被物理删除但目录残留（>7 天）也清理
            java.time.Instant deadlineInstant = deadline.atZone(java.time.ZoneId.systemDefault()).toInstant();
            for (Long orphanId : jobStorageService.findDirectoriesOlderThan(deadlineInstant)) {
                if (jobMapper.selectById(orphanId) == null) {
                    deleteJobFiles(orphanId);
                }
            }
            if (!interrupted.isEmpty() || !stale.isEmpty()) {
                log.info("AI 导入启动自愈：中断任务标记失败 {} 个，清理过期终态目录 {} 个", interrupted.size(), stale.size());
            }
        } catch (Exception e) {
            log.warn("AI 导入启动自愈失败：{}", e.getMessage());
        }
    }

    public AiJobResponse getJob(Long jobId) {
        AiImportJob job = findByIdOrThrow(jobId);
        //兜底：排队超过 10 分钟仍未开始执行（如应用重启/线程异常），标记失败而不是永久 PENDING
        if ("PENDING".equals(job.getStatus()) && job.getCreatedAt() != null
                && job.getCreatedAt().isBefore(LocalDateTime.now().minusMinutes(10))) {
            job.setStatus("FAILED");
            job.setStage("DONE");
            job.setError("任务排队超时（可能因应用中断未能执行），请重新导入");
            job.setErrorCode("QUEUE_TIMEOUT");
            job.setFinishedAt(LocalDateTime.now());
            jobMapper.updateById(job);
        }
        return toResponse(job, true);
    }

    /** 进行中的任务列表（前端全局监控：侧边栏徽标、完成通知） */
    public List<AiJobResponse> listActiveJobs() {
        List<AiImportJob> jobs = jobMapper.selectList(new LambdaQueryWrapper<AiImportJob>()
                .in(AiImportJob::getStatus, "PENDING", "PROCESSING")
                .orderByDesc(AiImportJob::getCreatedAt));
        List<AiJobResponse> result = new ArrayList<>();
        for (AiImportJob job : jobs) {
            result.add(toResponse(job, false));
        }
        return result;
    }

    /** 最近未确认导入的任务（含已取消——补二段删除入口；confirmed=true 不再展示），createdAt 倒序 */
    public List<AiJobResponse> listRecentJobs(int limit) {
        int safeLimit = Math.min(Math.max(limit, 1), 50);
        List<AiImportJob> jobs = jobMapper.selectList(new LambdaQueryWrapper<AiImportJob>()
                .eq(AiImportJob::getConfirmed, false)
                .orderByDesc(AiImportJob::getCreatedAt)
                .last("LIMIT " + safeLimit));
        List<AiJobResponse> result = new ArrayList<>();
        for (AiImportJob job : jobs) {
            //顺带处理排队超时（与 getJob 一致的兜底）
            if ("PENDING".equals(job.getStatus()) && job.getCreatedAt() != null
                    && job.getCreatedAt().isBefore(LocalDateTime.now().minusMinutes(10))) {
                job.setStatus("FAILED");
                job.setStage("DONE");
                job.setError("任务排队超时（可能因应用中断未能执行），请重新导入");
                job.setErrorCode("QUEUE_TIMEOUT");
                job.setFinishedAt(LocalDateTime.now());
                jobMapper.updateById(job);
            }
            result.add(toResponse(job, true));
        }
        return result;
    }

    /**
     * SSE 订阅任务事件流：任务已终态时立即推送最终快照并关闭（含 CANCELED）。
     * 先注册订阅、再重读状态（不能只依赖订阅前快照——worker 可能在注册窗口内完成/取消；
     * 注册后补查终态 + complete 保证任何交错下订阅方都能收到终态并被关闭）。
     */
    public org.springframework.web.servlet.mvc.method.annotation.SseEmitter subscribeJob(Long jobId) {
        org.springframework.web.servlet.mvc.method.annotation.SseEmitter emitter = aiJobEventService.subscribe(jobId);
        AiImportJob job = findByIdOrThrow(jobId);
        if ("SUCCESS".equals(job.getStatus()) || "FAILED".equals(job.getStatus()) || "CANCELED".equals(job.getStatus())) {
            aiJobEventService.complete(jobId, getJob(jobId));
        }
        return emitter;
    }

    /**
     * 取消/删除任务（两阶段）：
     * - 进行中（PENDING/PROCESSING）：仅标记 CANCELED（记录保留供再次删除）。
     *   文件不在这里删——执行线程可能正在读取，删文件会令线程异常并以 FAILED 覆盖 CANCELED（取消竞态）；
     *   由执行线程在取消检查点自行清理（cleanupCanceledJobFiles）；若线程已不存在（应用重启中断），
     *   残留文件在用户再次删除（终态物理删除）时清理。
     * - 终态（SUCCESS/FAILED/CANCELED）：物理删除记录 + 清理文件目录。
     */
    public void deleteJob(Long jobId) {
        AiImportJob job = findByIdOrThrow(jobId);
        String status = job.getStatus();
        if ("PENDING".equals(status) || "PROCESSING".equals(status)) {
            job.setStatus("CANCELED");
            job.setStage("DONE");
            job.setFinishedAt(LocalDateTime.now());
            jobMapper.updateById(job);
            //发布 CANCELED 快照并结束事件流（complete = publish + 关闭订阅方），前端立即感知"已取消"
            aiJobEventService.complete(jobId, getJob(jobId));
            return;
        }
        //终态：物理删除记录 + 清理文件目录
        jobMapper.deleteById(jobId);
        deleteJobFiles(jobId);
    }

    /**
     * 终态落库（条件更新）：仅当任务尚未被取消时写入 SUCCESS/FAILED，
     * 返回是否真正落库（false = 任务已被用户取消，调用方应停止并清理）。
     */
    boolean writeTerminal(Long jobId, String status, String error, String errorCode, String resultJson) {
        LambdaUpdateWrapper<AiImportJob> wrapper = new LambdaUpdateWrapper<AiImportJob>()
                .eq(AiImportJob::getId, jobId)
                .ne(AiImportJob::getStatus, "CANCELED")
                .set(AiImportJob::getStatus, status)
                .set(AiImportJob::getStage, "DONE")
                .set(AiImportJob::getProgress, 100)
                .set(AiImportJob::getFinishedAt, LocalDateTime.now());
        if (error != null) {
            wrapper.set(AiImportJob::getError, error);
        }
        if (errorCode != null) {
            wrapper.set(AiImportJob::getErrorCode, errorCode);
        }
        if (resultJson != null) {
            wrapper.set(AiImportJob::getResultJson, resultJson);
        }
        return jobMapper.update(null, wrapper) > 0;
    }

    /** 任务是否已被用户取消（deleteJob 标记 CANCELED） */
    boolean isCanceled(Long jobId) {
        AiImportJob current = jobMapper.selectById(jobId);
        return current != null && "CANCELED".equals(current.getStatus());
    }

    /** 任务取消确认后的文件清理（仅执行线程在"已停不会再读文件"时调用，避免与在途读取竞态） */
    void cleanupCanceledJobFiles(Long jobId) {
        deleteJobFiles(jobId);
    }

    /**
     * 进度/阶段更新（条件更新）：任务被取消（CANCELED）后不再改写行——防止与用户取消竞态时
     * 把 CANCELED 复活成 PROCESSING。更新成功才同步本地实体镜像（供后续代码读取）。
     */
    void updateStage(AiImportJob job, String status, String stage, int progress) {
        int rows = jobMapper.update(null, new LambdaUpdateWrapper<AiImportJob>()
                .eq(AiImportJob::getId, job.getId())
                .ne(AiImportJob::getStatus, "CANCELED")
                .set(AiImportJob::getStatus, status)
                .set(AiImportJob::getStage, stage)
                .set(AiImportJob::getProgress, progress));
        if (rows > 0) {
            job.setStatus(status);
            job.setStage(stage);
            job.setProgress(progress);
        }
    }

    /** 统一构造任务快照（轮询/SSE/active/recent 共用） */
    AiJobResponse toResponse(AiImportJob job, boolean includeQuestions) {
        List<ContentPackageQuestion> questions = null;
        List<ContentPackageMaterial> materials = null;
        if (includeQuestions && job.getResultJson() != null && !job.getResultJson().isBlank()) {
            try {
                AiImportResult parsed = resultCodec.parse(job.getResultJson());
                questions = parsed.questions();
                materials = parsed.materials();
            } catch (IOException ignored) {
                //结果解析失败按空处理
            }
        }
        int fileCount = 1;
        if (job.getFileNames() != null && !job.getFileNames().isBlank()) {
            fileCount = job.getFileNames().split(",").length;
        }
        return new AiJobResponse(job.getId(), job.getStatus(), job.getStage(), job.getProgress(),
                job.getFileName(), job.getFileType(), job.getAiSupplement(), job.getThinking(),
                job.getEngine(), job.getProcessPath(), job.getConfirmed(),
                fileCount, job.getCurrentFileIndex(),
                questions, materials, job.getWarningHint(), job.getErrorCode(), job.getError(),
                job.getCreatedAt(), job.getFinishedAt());
    }

    void deleteJobFiles(Long jobId) {
        try {
            jobStorageService.deleteJobDirectory(jobId);
        } catch (IOException e) {
            log.warn("清理 AI 导入文件失败 job={}: {}", jobId, e.getMessage());
        }
    }

    AiImportJob findByIdOrThrow(Long id) {
        AiImportJob job = jobMapper.selectById(id);
        if (job == null) {
            throw new NoSuchElementException("任务不存在：" + id);
        }
        return job;
    }
}
