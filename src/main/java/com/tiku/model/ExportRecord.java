package com.tiku.model;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.time.LocalDateTime;

/**
 * 导出记录（本地发布中心）：「我的作品」把题库导出成 .tiku 落到本地目录后落一条，
 * 记录文件位置/大小/内容包身份与版本、是否已发布（见 V14__export_records.sql）。
 * <p>
 * 表字段与本地磁盘文件是一对一快照关系：题库后续被改名/改版本都不回写本表，
 * 记录始终反映"当时导出的那个文件"。本类手写 getter/setter（新代码不用 Lombok）。
 */
@TableName("export_records")
public class ExportRecord {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 来源题库 ID（题库为物理删除，删除后本记录保留：磁盘文件仍在，便于清理） */
    @TableField("bank_id")
    private Long bankId;

    /** 导出时的题库名（快照） */
    @TableField("bank_name")
    private String bankName;

    /** 导出内容包身份（写入包内的 packageKey） */
    @TableField("package_key")
    private String packageKey;

    /** 导出内容包版本（写入包内的 version） */
    private String version;

    /** 落盘绝对路径 */
    @TableField("file_path")
    private String filePath;

    /** 文件名（已清理 Windows 非法字符） */
    @TableField("file_name")
    private String fileName;

    /** 文件字节数 */
    @TableField("size_bytes")
    private Long sizeBytes;

    /** 是否已上传题库广场（0/1） */
    private Boolean published;

    /** 实际上传成功的版本（发布后由前端标记；未发布为 null） */
    @TableField("published_version")
    private String publishedVersion;

    @TableField("created_at")
    private LocalDateTime createdAt;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public Long getBankId() {
        return bankId;
    }

    public void setBankId(Long bankId) {
        this.bankId = bankId;
    }

    public String getBankName() {
        return bankName;
    }

    public void setBankName(String bankName) {
        this.bankName = bankName;
    }

    public String getPackageKey() {
        return packageKey;
    }

    public void setPackageKey(String packageKey) {
        this.packageKey = packageKey;
    }

    public String getVersion() {
        return version;
    }

    public void setVersion(String version) {
        this.version = version;
    }

    public String getFilePath() {
        return filePath;
    }

    public void setFilePath(String filePath) {
        this.filePath = filePath;
    }

    public String getFileName() {
        return fileName;
    }

    public void setFileName(String fileName) {
        this.fileName = fileName;
    }

    public Long getSizeBytes() {
        return sizeBytes;
    }

    public void setSizeBytes(Long sizeBytes) {
        this.sizeBytes = sizeBytes;
    }

    public Boolean getPublished() {
        return published;
    }

    public void setPublished(Boolean published) {
        this.published = published;
    }

    public String getPublishedVersion() {
        return publishedVersion;
    }

    public void setPublishedVersion(String publishedVersion) {
        this.publishedVersion = publishedVersion;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(LocalDateTime createdAt) {
        this.createdAt = createdAt;
    }
}
