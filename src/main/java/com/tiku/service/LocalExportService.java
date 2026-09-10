package com.tiku.service;

import com.tiku.dto.ExportRecordResponse;
import com.tiku.dto.ExportRequest;
import com.tiku.dto.ExportToDirRequest;
import com.tiku.mapper.QuestionBankMapper;
import com.tiku.model.QuestionBank;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.NoSuchElementException;
import java.util.regex.Pattern;

/**
 * 导出到本地目录（本地发布中心的"导出"入口）：题库 → .tiku 容器 → 写目标目录 → 落导出记录。
 * <p>
 * 复用关系：容器打包与身份/版本决策全在 {@link ContentPackageService}（本服务不重复实现导出），
 * 目录默认值与"上次目录"记忆在 {@link ExportPrefsService}，记录在 {@link ExportRecordService}。
 * 导出成"文件在磁盘上"是"从本地路径直接发布"的前提：200MB 级的包不必再经前端中转一次。
 */
@Service
public class LocalExportService {

    private static final Logger log = LoggerFactory.getLogger(LocalExportService.class);

    /**
     * 版本号口径与发布端体检（ContentPackageInspector）一致：
     * 字母/数字/点/下划线/连字符，1-100 字符——导出即校验，避免文件导出后才发现官网拒收。
     */
    private static final Pattern VERSION_RE = Pattern.compile("^[\\p{L}\\p{N}._-]{1,100}$");

    /** Windows 文件名非法字符（\ / : * ? " < > |） */
    private static final Pattern ILLEGAL_FILENAME_CHARS = Pattern.compile("[\\\\/:*?\"<>|]");
    /** 文件名主体的长度上限（题库名很长时截断，避免顶到 Windows 路径长度上限） */
    private static final int MAX_STEM_LENGTH = 80;
    /** Windows 保留设备名：同名文件无法创建 */
    private static final Pattern RESERVED_NAMES =
            Pattern.compile("(?i)^(CON|PRN|AUX|NUL|COM[1-9]|LPT[1-9])(\\..*)?$");

    private final QuestionBankMapper questionBankMapper;
    private final ContentPackageService contentPackageService;
    private final ExportRecordService exportRecordService;
    private final ExportPrefsService exportPrefsService;

    public LocalExportService(QuestionBankMapper questionBankMapper,
                              ContentPackageService contentPackageService,
                              ExportRecordService exportRecordService,
                              ExportPrefsService exportPrefsService) {
        this.questionBankMapper = questionBankMapper;
        this.contentPackageService = contentPackageService;
        this.exportRecordService = exportRecordService;
        this.exportPrefsService = exportPrefsService;
    }

    /**
     * 导出题库到目录：POST /api/exports/export 的实现。
     * 1. 目录：显式 dir ＞ 上次记忆 ＞ 文档\拾题；不存在则创建，不可写即报错（不写半个文件）；
     * 2. 打包：.tiku 容器（与 /api/banks/{id}/export-tiku 同一实现），version 提供时覆盖包内 version；
     * 3. 落盘：{题库名或packageKey}-{version}.tiku（清理 Windows 非法字符），同名覆盖（重复导出同一版本以最新为准）；
     * 4. 记录：写 export_records 并把本次目录记为"上次目录"。
     */
    public ExportRecordResponse exportToDirectory(ExportToDirRequest request) {
        if (request == null || request.bankId() == null) {
            throw new IllegalArgumentException("缺少题库 ID（bankId）");
        }
        Long bankId = request.bankId();
        QuestionBank bank = questionBankMapper.selectById(bankId);
        if (bank == null) {
            throw new NoSuchElementException("题库不存在：" + bankId);
        }
        String versionOverride = normalizeVersion(request.version());
        Path dir = exportPrefsService.resolveTargetDir(request.dir());

        //UPGRADE（作者迭代自己的作品）：沿用当前 packageKey，官网才会把这次上传登记成
        //"同一作品的新版本"；AUTO 在内容被改过时会分支成新 packageKey（那是"派生/防冒用"语义，
        //不适合"我的作品发新版本"）。版本号不交给导出逻辑，而是打包前改写包内 version（见下），
        //这样"内容没改但用户就是想发个新版本号"不会被"内容未变化禁止变更版本号"挡住。
        ExportRequest exportRequest = new ExportRequest(null, null, "UPGRADE", null, null, null);
        ContentPackageService.TikuExport export =
                contentPackageService.exportTikuPackageWithMeta(bankId, exportRequest, versionOverride);

        Path target = dir.resolve(buildFileName(bank, export));
        byte[] bytes = export.bytes();
        try {
            Files.write(target, bytes,
                    StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE);
        } catch (IOException e) {
            throw new IllegalStateException("导出文件写入失败：" + target + "（" + e.getMessage() + "）");
        }
        long size = bytes.length;
        try {
            size = Files.size(target);
        } catch (IOException ignored) {
            /* 取不到实际大小时用内存中的字节数（刚写完，两者一致） */
        }

        ExportRecordResponse record = exportRecordService.create(
                bankId, bank.getName(), export.packageKey(), export.version(), target, size);
        //本次目录记为"上次目录"：下次不传 dir 时直接用它。
        //偏好文件写失败不影响"文件已导出、记录已落库"这个事实（下次退回默认目录即可），故只记日志
        try {
            exportPrefsService.remember(dir.toString());
        } catch (RuntimeException e) {
            log.warn("导出成功但记住导出目录失败：dir={}, {}", dir, e.getMessage());
        }
        return record;
    }

    /** 版本号归一化：空白视为"不覆盖"（沿用导出决策出的版本），非法字符直接拒绝（口径同发布端体检） */
    private static String normalizeVersion(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String version = raw.trim();
        if (!VERSION_RE.matcher(version).matches()) {
            throw new IllegalArgumentException(
                    "版本号不合法（只能是字母、数字、点、下划线、连字符，1-100 个字符）：" + version);
        }
        return version;
    }

    /** 文件名：{题库名或packageKey}-{version}.tiku；题库名（用户可改）与版本都做 Windows 非法字符清理 */
    private static String buildFileName(QuestionBank bank, ContentPackageService.TikuExport export) {
        String base = (bank.getName() == null || bank.getName().isBlank())
                ? export.packageKey()
                : bank.getName();
        String version = (export.version() == null || export.version().isBlank()) ? "1.0.0" : export.version();
        return sanitizeFileName(base) + "-" + sanitizeFileName(version) + ".tiku";
    }

    /**
     * 文件名清理：Windows 非法字符（\ / : * ? " &lt; &gt; |）→ 下划线；
     * 去掉控制字符、折叠空白、截断长度、去掉结尾的点与空格（Windows 不允许），
     * 保留设备名加前缀；全部清空后回退 content-package。
     */
    static String sanitizeFileName(String raw) {
        String name = raw == null ? "" : raw.trim();
        name = ILLEGAL_FILENAME_CHARS.matcher(name).replaceAll("_");
        name = name.replaceAll("\\p{Cntrl}", "");
        name = name.replaceAll("\\s+", " ").trim();
        if (name.length() > MAX_STEM_LENGTH) {
            name = name.substring(0, MAX_STEM_LENGTH);
        }
        name = name.replaceAll("[. ]+$", "").trim();
        if (name.isEmpty()) {
            return "content-package";
        }
        if (RESERVED_NAMES.matcher(name).matches()) {
            return "_" + name;
        }
        return name;
    }
}
