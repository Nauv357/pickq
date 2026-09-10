package com.tiku.controller;

import com.tiku.dto.ApiResponse;
import com.tiku.dto.DeleteExportRecordResult;
import com.tiku.dto.ExportPrefsRequest;
import com.tiku.dto.ExportPrefsResponse;
import com.tiku.dto.ExportRecordResponse;
import com.tiku.dto.ExportToDirRequest;
import com.tiku.dto.MarkPublishedRequest;
import com.tiku.dto.NextVersionResponse;
import com.tiku.service.ExportPrefsService;
import com.tiku.service.ExportRecordService;
import com.tiku.service.LocalExportService;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 本地发布中心「导出」侧接口 —— <b>全部免登录（匿名可用）</b>：
 * <pre>
 *   POST   /api/exports/export                 题库 → .tiku 写到本地目录 + 落导出记录
 *   GET    /api/exports                        导出记录列表（时间倒序，附 fileExists）
 *   DELETE /api/exports/{id}?deleteFile=       移除记录（可选同时删磁盘文件）
 *   POST   /api/exports/{id}/mark-published    标记已发布（上传成功后由前端调用）
 *   GET    /api/exports/prefs                  导出目录偏好（lastDir / defaultDir / dirExists）
 *   PUT    /api/exports/prefs                  更新"上次导出目录"记忆
 *   GET    /api/exports/next-version?bankId=   下一版本号建议（已发布版本补丁号 +1）
 * </pre>
 * <b>为什么不校验登录</b>：这些接口是"本机数据管理"（本地题库 → 本地文件 → 本地导出记录），
 * 与用户有没有登录题库广场、有没有联网毫无关系；产品需求是"离线也能查看本地题库与已导出文件、
 * 离线也能导出文件"。登录只约束真正上云的动作——发布/上传/管理线上作品，见
 * {@link CenterPublishController} 的 /api/center/publish 与 /api/center/publish-from-path。
 * <p>
 * 因此本控制器不注入 {@link com.tiku.service.CenterAuthStore}，也不读 token：
 * 全部操作只有本地文件 IO + 本地 H2 读写，<b>不会向 pickq.cn 等任何远端发起请求</b>
 * （publish-from-path 那条云端路径在 CenterPublishController，仍要求登录）。
 */
@RestController
@RequestMapping("/api/exports")
public class ExportController {

    private final LocalExportService localExportService;
    private final ExportRecordService exportRecordService;
    private final ExportPrefsService exportPrefsService;

    public ExportController(LocalExportService localExportService,
                            ExportRecordService exportRecordService,
                            ExportPrefsService exportPrefsService) {
        this.localExportService = localExportService;
        this.exportRecordService = exportRecordService;
        this.exportPrefsService = exportPrefsService;
    }

    /**
     * POST /api/exports/export — 导出到目录（免登录，纯本地操作）。
     * body：{ bankId, version?, dir? }；返回导出记录（含 filePath/fileName/sizeBytes/packageKey/version）。
     * 目录缺省顺序：dir ＞ 上次记忆 ＞ 文档\拾题（不存在则创建）；本次使用的目录会记为"上次目录"。
     */
    @PostMapping("/export")
    public ApiResponse<ExportRecordResponse> exportToDirectory(@RequestBody(required = false) ExportToDirRequest request) {
        return ApiResponse.success(localExportService.exportToDirectory(request));
    }

    /** GET /api/exports — 导出记录列表（免登录；按导出时间倒序，fileExists = 磁盘文件是否还在） */
    @GetMapping
    public ApiResponse<List<ExportRecordResponse>> listExportRecords() {
        return ApiResponse.success(exportRecordService.list());
    }

    /**
     * DELETE /api/exports/{id}?deleteFile=false|true — 移除记录（免登录）。
     * deleteFile=true 同时删磁盘文件（文件已不存在也算成功）；文件删除失败（占用/无权限）报 500 且不删记录。
     */
    @DeleteMapping("/{id}")
    public ApiResponse<DeleteExportRecordResult> deleteExportRecord(
            @PathVariable Long id,
            @RequestParam(defaultValue = "false") boolean deleteFile) {
        return ApiResponse.success(exportRecordService.delete(id, deleteFile));
    }

    /**
     * POST /api/exports/{id}/mark-published — 标记记录已发布（免登录；纯本地标记，不发起任何远程请求）。
     * body：{ version }（留空则用该记录导出时的版本）；返回更新后的记录。
     */
    @PostMapping("/{id}/mark-published")
    public ApiResponse<ExportRecordResponse> markPublished(
            @PathVariable Long id,
            @RequestBody(required = false) MarkPublishedRequest request) {
        return ApiResponse.success(exportRecordService.markPublished(id, request == null ? null : request.version()));
    }

    /**
     * GET /api/exports/prefs — 导出目录偏好（免登录）。
     * 返回 { lastDir, defaultDir, dirExists:{ lastDir?: bool } }；lastDir 未设置时为 null 且 dirExists 为空。
     */
    @GetMapping("/prefs")
    public ApiResponse<ExportPrefsResponse> getPrefs() {
        return ApiResponse.success(exportPrefsService.current());
    }

    /**
     * PUT /api/exports/prefs — 更新"上次导出目录"记忆（免登录；内存 + {dataDir}/export-prefs.json）。
     * body：{ lastDir }；要求绝对路径且可创建（不存在则创建），留空表示清除记忆。返回最新偏好。
     */
    @PutMapping("/prefs")
    public ApiResponse<ExportPrefsResponse> updatePrefs(@RequestBody(required = false) ExportPrefsRequest request) {
        exportPrefsService.remember(request == null ? null : request.lastDir());
        return ApiResponse.success(exportPrefsService.current());
    }

    /**
     * GET /api/exports/next-version?bankId= — 下一版本号建议（免登录；只看本地题库与本地导出记录）。
     * 返回 { suggested, lastPublished }：有已发布记录时按已发布版本递增补丁号，否则用题库当前版本。
     */
    @GetMapping("/next-version")
    public ApiResponse<NextVersionResponse> nextVersion(@RequestParam Long bankId) {
        return ApiResponse.success(exportRecordService.nextVersion(bankId));
    }
}
