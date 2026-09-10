package com.tiku.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.tiku.dto.DeleteExportRecordResult;
import com.tiku.dto.ExportRecordResponse;
import com.tiku.dto.NextVersionResponse;
import com.tiku.mapper.ExportRecordMapper;
import com.tiku.mapper.QuestionBankMapper;
import com.tiku.model.ExportRecord;
import com.tiku.model.QuestionBank;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.List;
import java.util.NoSuchElementException;

/**
 * 导出记录（本地发布中心）：落库、列表、删除、标记已发布、下一版本号建议。
 * <p>
 * 记录只在"导出到目录"时写入（见 {@link LocalExportService}）；发布成功后的标记有两个入口：
 * 前端调 mark-published，或从本地路径发布时带 exportRecordId 由后端顺带标记（见 CenterPublishController）。
 * 表结构见 V14__export_records.sql。
 */
@Service
public class ExportRecordService {

    /** 版本号无法解析时的兜底建议值 */
    private static final String FALLBACK_VERSION = "1.0.0";

    private final ExportRecordMapper exportRecordMapper;
    private final QuestionBankMapper questionBankMapper;

    public ExportRecordService(ExportRecordMapper exportRecordMapper, QuestionBankMapper questionBankMapper) {
        this.exportRecordMapper = exportRecordMapper;
        this.questionBankMapper = questionBankMapper;
    }

    // ==================== 写入 ====================

    /** 新增一条导出记录（文件已写完、size 已确定后调用）；返回带 fileExists 的响应 */
    @Transactional
    public ExportRecordResponse create(Long bankId, String bankName, String packageKey, String version,
                                       Path file, long sizeBytes) {
        ExportRecord record = new ExportRecord();
        record.setBankId(bankId);
        record.setBankName(bankName);
        record.setPackageKey(packageKey);
        record.setVersion(version);
        record.setFilePath(file.toAbsolutePath().toString());
        record.setFileName(file.getFileName().toString());
        record.setSizeBytes(sizeBytes);
        //已发布标记由上传成功后回填，导出时一律为 0
        record.setPublished(false);
        record.setCreatedAt(LocalDateTime.now());
        exportRecordMapper.insert(record);
        return toResponse(record);
    }

    /**
     * 标记记录已发布（上传成功后调用）：
     * version 留空时用导出时的版本；两者都为空则拒绝（不知道发布了什么版本，版本建议会失去依据）。
     */
    @Transactional
    public ExportRecordResponse markPublished(Long id, String version) {
        ExportRecord record = require(id);
        String published = version == null ? "" : version.trim();
        if (published.isEmpty()) {
            published = record.getVersion() == null ? "" : record.getVersion().trim();
        }
        if (published.isEmpty()) {
            throw new IllegalArgumentException("请提供发布版本号（version）");
        }
        record.setPublished(true);
        record.setPublishedVersion(published);
        exportRecordMapper.updateById(record);
        return toResponse(record);
    }

    /**
     * 删除导出记录；deleteFile=true 时同时删磁盘文件（文件已不存在也算成功）。
     * 库里记录先查后删：不存在直接 404（NoSuchElementException），不会静默成功。
     */
    @Transactional
    public DeleteExportRecordResult delete(Long id, boolean deleteFile) {
        ExportRecord record = require(id);
        boolean fileDeleted = false;
        if (deleteFile && record.getFilePath() != null && !record.getFilePath().isBlank()) {
            try {
                Path path = Path.of(record.getFilePath());
                //历史遗留：记录指向目录（异常数据）时只删记录，不动目录
                fileDeleted = !Files.isDirectory(path) && Files.deleteIfExists(path);
            } catch (InvalidPathException e) {
                throw new IllegalArgumentException("导出记录的文件路径不合法：" + record.getFilePath());
            } catch (IOException e) {
                throw new IllegalStateException("删除导出文件失败：" + e.getMessage());
            }
        }
        exportRecordMapper.deleteById(id);
        return new DeleteExportRecordResult(id, fileDeleted);
    }

    // ==================== 查询 ====================

    /** 记录列表：按导出时间倒序（同秒按 ID 倒序，保证顺序稳定） */
    public List<ExportRecordResponse> list() {
        List<ExportRecord> records = exportRecordMapper.selectList(new LambdaQueryWrapper<ExportRecord>()
                .orderByDesc(ExportRecord::getCreatedAt)
                .orderByDesc(ExportRecord::getId));
        return records.stream().map(ExportRecordService::toResponse).toList();
    }

    /**
     * 下一版本号建议：
     * - 该题库有已发布记录 → 用最近一条的 publishedVersion 递增补丁号（1.0.0 → 1.0.1 / 1.2 → 1.2.1，解析不了回退 1.0.0）；
     * - 没有已发布记录 → 用题库当前版本（自建题库为 null 时 1.0.0）。
     * lastPublished 同时返回，供 UI 提示"上次发布的是哪个版本"。
     */
    public NextVersionResponse nextVersion(Long bankId) {
        if (bankId == null) {
            throw new IllegalArgumentException("缺少题库 ID（bankId）");
        }
        ExportRecord last = exportRecordMapper.selectOne(new LambdaQueryWrapper<ExportRecord>()
                .eq(ExportRecord::getBankId, bankId)
                .eq(ExportRecord::getPublished, true)
                .orderByDesc(ExportRecord::getId)
                .last("LIMIT 1"));
        String lastPublished = null;
        if (last != null) {
            //published_version 是"实际上传版本"；极少数旧记录可能为空，退回导出时的版本
            String published = last.getPublishedVersion();
            if (published == null || published.isBlank()) {
                published = last.getVersion();
            }
            lastPublished = (published == null || published.isBlank()) ? null : published.trim();
        }
        String suggested = lastPublished != null
                ? suggestNextVersion(lastPublished)
                : currentBankVersion(bankId);
        return new NextVersionResponse(suggested, lastPublished);
    }

    // ==================== 工具 ====================

    /**
     * 补丁号递增：补丁号固定是第 3 段，不足 3 段补 0（1.0.0 → 1.0.1、1.2 → 1.2.1、1 → 1.0.1）。
     * 无法解析（空 / 非数字段 / 超过 3 段）回退 1.0.0——宁可给个保守的新版本号，也不要抛错误断掉发布流程。
     */
    public static String suggestNextVersion(String version) {
        if (version == null || version.isBlank()) {
            return FALLBACK_VERSION;
        }
        String[] parts = version.trim().split("\\.");
        //超过 3 段已不是常规"主.次.补丁"版本号，按无法解析处理
        if (parts.length == 0 || parts.length > 3) {
            return FALLBACK_VERSION;
        }
        for (String part : parts) {
            if (!part.matches("\\d{1,9}")) {
                return FALLBACK_VERSION;
            }
        }
        String[] padded = {"0", "0", "0"};
        System.arraycopy(parts, 0, padded, 0, parts.length);
        int patch = Integer.parseInt(padded[2]);
        if (patch >= 999_999_999) {
            return FALLBACK_VERSION;
        }
        padded[2] = String.valueOf(patch + 1);
        return String.join(".", padded);
    }

    /** 记录 → 响应（附 fileExists：磁盘文件是否还在） */
    public static ExportRecordResponse toResponse(ExportRecord record) {
        boolean fileExists = false;
        if (record.getFilePath() != null && !record.getFilePath().isBlank()) {
            try {
                fileExists = Files.isRegularFile(Path.of(record.getFilePath()));
            } catch (RuntimeException ignored) {
                /* 路径不可解析 = 文件不在了 */
            }
        }
        return new ExportRecordResponse(
                record.getId(),
                record.getBankId(),
                record.getBankName(),
                record.getPackageKey(),
                record.getVersion(),
                record.getFilePath(),
                record.getFileName(),
                record.getSizeBytes() == null ? 0L : record.getSizeBytes(),
                Boolean.TRUE.equals(record.getPublished()),
                record.getPublishedVersion(),
                record.getCreatedAt(),
                fileExists);
    }

    private ExportRecord require(Long id) {
        if (id == null) {
            throw new IllegalArgumentException("缺少导出记录 ID");
        }
        ExportRecord record = exportRecordMapper.selectById(id);
        if (record == null) {
            throw new NoSuchElementException("导出记录不存在：" + id);
        }
        return record;
    }

    /** 题库当前版本（自建题库未认领身份时为 null → 1.0.0） */
    private String currentBankVersion(Long bankId) {
        QuestionBank bank = questionBankMapper.selectById(bankId);
        if (bank == null) {
            throw new NoSuchElementException("题库不存在：" + bankId);
        }
        String version = bank.getVersion();
        return (version == null || version.isBlank()) ? FALLBACK_VERSION : version.trim();
    }
}
