package com.tiku.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tiku.dto.ExportPrefsResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 导出偏好（本地发布中心）：记住上次导出目录，存 {dataDir}/export-prefs.json
 * （风格与 {@link AiConfigService}、{@link CenterAuthStore} 的本机 JSON 配置一致：不入库、不进日志）。
 * <p>
 * 目录优先级：请求显式 dir ＞ 上次记忆 ＞ 默认目录（用户"文档"下的 拾题）。
 * 目录校验：必须是绝对路径（相对路径在不同启动工作目录下含义不同，导出位置不可预测）、
 * 不存在则创建、必须是目录且可写；不满足时给可读中文提示（400/500 由全局处理器转换）。
 */
@Service
public class ExportPrefsService {

    /** 默认导出目录名（用户文档目录下的子目录） */
    private static final String DEFAULT_SUBDIR = "拾题";

    /** 存量文件：只存 lastDir，字段缺失/损坏一律当作未设置（宁可回到默认目录，不要报错打断导出） */
    private record Stored(String lastDir) {
    }

    private final Path configPath;
    private final ObjectMapper objectMapper;

    public ExportPrefsService(@Value("${tiku.data-dir}") String dataDir, ObjectMapper objectMapper) {
        this.configPath = Path.of(dataDir, "export-prefs.json");
        this.objectMapper = objectMapper;
    }

    /** 上次导出目录；未设置或配置损坏返回 null */
    public synchronized String lastDir() {
        try {
            if (!Files.exists(configPath)) {
                return null;
            }
            Stored stored = objectMapper.readValue(configPath.toFile(), Stored.class);
            String dir = stored == null ? null : stored.lastDir();
            return (dir == null || dir.isBlank()) ? null : dir;
        } catch (IOException e) {
            return null;
        }
    }

    /**
     * 记住导出目录：校验（绝对路径 + 可创建）通过后写入配置。
     * 目录为空表示清除记忆（下次回到默认目录）。
     */
    public synchronized String remember(String dir) {
        if (dir == null || dir.isBlank()) {
            clear();
            return null;
        }
        Path target = prepareDirectory(dir);
        try {
            Files.createDirectories(configPath.getParent());
            objectMapper.writeValue(configPath.toFile(), new Stored(target.toString()));
        } catch (IOException e) {
            throw new IllegalStateException("保存导出偏好失败：" + e.getMessage());
        }
        return target.toString();
    }

    /** 清除记忆（配置损坏时也可用；删除失败忽略） */
    public synchronized void clear() {
        try {
            Files.deleteIfExists(configPath);
        } catch (IOException ignored) {
            /* 忽略：下次 remember 会覆盖写 */
        }
    }

    /**
     * 默认导出目录 = 用户"文档"目录下的 拾题。
     * Windows 的"文档"可能被重定向（OneDrive）或本地化命名，按候选顺序取第一个真实存在的，
     * 都不存在时回退 用户主目录\Documents\拾题（首次导出时创建）。
     */
    public Path defaultDir() {
        Path home = Path.of(System.getProperty("user.home", "."));
        for (String candidate : new String[]{"Documents", "文档"}) {
            Path dir = home.resolve(candidate);
            if (Files.isDirectory(dir)) {
                return dir.resolve(DEFAULT_SUBDIR);
            }
        }
        Path oneDrive = home.resolve("OneDrive").resolve("Documents");
        if (Files.isDirectory(oneDrive)) {
            return oneDrive.resolve(DEFAULT_SUBDIR);
        }
        return home.resolve("Documents").resolve(DEFAULT_SUBDIR);
    }

    /**
     * 解析本次导出的目标目录：显式 dir ＞ 上次记忆 ＞ 默认目录；并保证目录存在且可写。
     * 返回规范化后的绝对路径（落库与"上次目录"记忆都用它）。
     */
    public Path resolveTargetDir(String dir) {
        String chosen = (dir != null && !dir.isBlank()) ? dir.trim() : lastDir();
        if (chosen == null || chosen.isBlank()) {
            return prepareDirectory(defaultDir().toString());
        }
        return prepareDirectory(chosen);
    }

    /** 当前偏好快照（供 GET /api/exports/prefs 直接返回） */
    public ExportPrefsResponse current() {
        String last = lastDir();
        Map<String, Boolean> dirExists = new LinkedHashMap<>();
        if (last != null) {
            boolean exists = false;
            try {
                exists = Files.isDirectory(Path.of(last));
            } catch (InvalidPathException ignored) {
                /* 配置里的路径已不可解析：按"不存在"处理，前端会退回默认目录 */
            }
            dirExists.put("lastDir", exists);
        }
        return new ExportPrefsResponse(last, defaultDir().toString(), dirExists);
    }

    /**
     * 校验并准备目录：绝对路径 → 不存在则创建 → 必须是目录且可写。
     * 不可写/创建失败抛 IllegalStateException（磁盘/权限问题，非用户输入问题）。
     */
    public static Path prepareDirectory(String raw) {
        if (raw == null || raw.isBlank()) {
            throw new IllegalArgumentException("导出目录不能为空");
        }
        Path path;
        try {
            path = Path.of(raw.trim());
        } catch (InvalidPathException e) {
            throw new IllegalArgumentException("导出目录路径不合法：" + raw);
        }
        if (!path.isAbsolute()) {
            throw new IllegalArgumentException("导出目录必须是绝对路径：" + raw);
        }
        path = path.normalize();
        if (Files.exists(path) && !Files.isDirectory(path)) {
            throw new IllegalArgumentException("导出目录指向的是文件，不是目录：" + path);
        }
        try {
            Files.createDirectories(path);
        } catch (IOException e) {
            throw new IllegalStateException("无法创建导出目录：" + path + "（" + e.getMessage() + "）");
        }
        if (!Files.isWritable(path)) {
            throw new IllegalStateException("导出目录不可写：" + path);
        }
        return path;
    }
}
