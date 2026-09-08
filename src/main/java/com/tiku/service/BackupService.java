package com.tiku.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import javax.sql.DataSource;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.sql.Connection;
import java.sql.Statement;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Iterator;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * 一键完整备份：H2 一致性快照（SCRIPT 导出全库 SQL）+ 题目图片目录 + AI 配置 → zip。
 * <p>
 * - database.sql：在应用运行中通过同一数据源连接执行 H2 `SCRIPT TO` 导出，
 *   快照一致性由 H2 保证（Flyway 版本表一并导出，恢复后无需重跑迁移）；
 * - ai-config.json：含 API Key，zip 内含《恢复说明.txt》提醒妥善保管；
 * - images/：按 {bankId}/{yyMMdd}/{uuid}.{ext} 原结构复制。
 * <p>
 * 恢复（停机操作，见 zip 内《恢复说明.txt》）：
 * H2 RunScript 执行 database.sql（会 DROP 重建所有表），再覆盖 images 与 ai-config.json。
 */
@Service
public class BackupService {

    private static final Logger log = LoggerFactory.getLogger(BackupService.class);
    private static final DateTimeFormatter TS = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");

    private final DataSource dataSource;
    private final String dataDir;

    public BackupService(DataSource dataSource, @Value("${tiku.data-dir}") String dataDir) {
        this.dataSource = dataSource;
        this.dataDir = dataDir;
    }

    /** 生成备份包写入输出流；临时目录用完即删（异常也会清理）。 */
    public void writeBackupZip(OutputStream out) throws IOException {
        Path tmp = Files.createTempDirectory("tiku-backup-");
        try {
            Path sql = tmp.resolve("database.sql");
            exportDatabaseScript(sql);
            copyIfExists(Paths.get(dataDir, "ai-config.json"), tmp.resolve("ai-config.json"));
            Path imagesSrc = Paths.get(dataDir, "images");
            if (Files.isDirectory(imagesSrc)) {
                copyTree(imagesSrc, tmp.resolve("images"));
            }
            writeRestoreReadme(tmp.resolve("恢复说明.txt"));
            zipTree(tmp, out);
        } finally {
            deleteRecursively(tmp);
        }
    }

    /**
     * 一键恢复·准备：校验备份 zip 并解压到 {dataDir}/restore/staged（安全解压：
     * 路径穿越拒绝、条目与大小上限）。应用重启后由 RestoreRunner 执行真正的恢复。
     * 返回数据目录（壳重启时拼 staged 路径用）。
     */
    public String prepareRestore(byte[] zipBytes) throws IOException {
        Path restoreDir = Paths.get(dataDir, "restore");
        Path staged = restoreDir.resolve("staged");
        deleteRecursively(restoreDir);
        Files.createDirectories(staged);
        long total = 0;
        int count = 0;
        try (java.util.zip.ZipInputStream zis = new java.util.zip.ZipInputStream(
                new java.io.ByteArrayInputStream(zipBytes))) {
            java.util.zip.ZipEntry e;
            while ((e = zis.getNextEntry()) != null) {
                count++;
                if (count > 100_000) {
                    throw new IllegalArgumentException("备份包条目过多");
                }
                String name = e.getName();
                if (name.contains("..") || name.startsWith("/") || name.contains("\\")) {
                    throw new IllegalArgumentException("备份包含非法路径：" + name);
                }
                Path target = staged.resolve(name).normalize();
                if (!target.startsWith(staged)) {
                    throw new IllegalArgumentException("备份包路径越界：" + name);
                }
                if (e.isDirectory()) {
                    Files.createDirectories(target);
                    continue;
                }
                Files.createDirectories(target.getParent());
                Files.copy(zis, target, StandardCopyOption.REPLACE_EXISTING);
                total += Files.size(target);
                if (total > 5L * 1024 * 1024 * 1024) {
                    throw new IllegalArgumentException("备份包解压后过大");
                }
            }
        } catch (IOException e) {
            deleteRecursively(restoreDir);
            throw e;
        }
        if (!Files.exists(staged.resolve("database.sql"))) {
            deleteRecursively(restoreDir);
            throw new IllegalArgumentException("备份包缺少 database.sql，不是有效的拾题备份文件");
        }
        return dataDir;
    }

    /** H2 SCRIPT：当前连接的数据库一致性快照 → 单个 SQL 文件（含建表与全部数据）。 */
    private void exportDatabaseScript(Path sql) throws IOException {
        String target = sql.toAbsolutePath().toString().replace('\\', '/');
        log.info("备份：导出 H2 全量脚本 {}", target);
        try (Connection conn = dataSource.getConnection();
             Statement st = conn.createStatement()) {
            st.execute("SCRIPT TO '" + target + "'");
        } catch (Exception e) {
            throw new IOException("H2 SCRIPT 导出失败：" + e.getMessage(), e);
        }
    }

    private void copyIfExists(Path src, Path dst) throws IOException {
        if (Files.exists(src)) {
            Files.copy(src, dst, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private void copyTree(Path src, Path dst) throws IOException {
        Files.createDirectories(dst);
        try (Stream<Path> stream = Files.walk(src)) {
            Iterator<Path> it = stream.iterator();
            while (it.hasNext()) {
                Path p = it.next();
                Path rel = src.relativize(p);
                Path target = dst.resolve(rel);
                if (Files.isDirectory(p)) {
                    Files.createDirectories(target);
                } else {
                    Files.createDirectories(target.getParent());
                    Files.copy(p, target, StandardCopyOption.REPLACE_EXISTING);
                }
            }
        }
    }

    private void writeRestoreReadme(Path readme) throws IOException {
        String h2Jar = findH2JarHint();
        //Windows 展示用反斜杠；jdbc url 用正斜杠更稳
        String dirWin = dataDir.replace('/', '\\');
        String dirUrl = dataDir.replace('\\', '/');
        String content = """
                【拾题 · 一键备份包】
                生成时间：%s
                数据目录：%s

                ── 包含内容 ──────────────────────────────
                database.sql  全部数据的一致性 SQL 快照：题库、题目、刷题记录、
                              复习进度/错题、AI 导入任务状态（含 Flyway 版本表，
                              恢复后无需重跑数据库升级）。
                images/       题目图片（按 题库ID/日期/文件名 原结构）。
                ai-config.json AI 模型配置（含 API Key —— 本包等同钥匙，
                              请妥善保管，不要外传）。

                ── 恢复步骤（先停止应用！）──────────────────
                应用运行时独占数据库，恢复前必须先完全关闭拾题应用。

                1. 关闭应用（结束正在运行的程序窗口/进程）。
                2. 备份当前数据目录以防万一：把 %s 整个文件夹复制一份存到别处。
                3. 把本 zip 解压到任意目录，然后执行 H2 RunScript 恢复数据库：
                   java -cp "%s" org.h2.tools.RunScript ^
                        -url "jdbc:h2:file:%s/tiku" -user sa ^
                        -script "<解压目录>/database.sql"
                   （命令行窗口内执行；路径含空格时注意引号）
                4. 恢复图片：把解压出的 images 文件夹复制进 %s（与现有
                   images 目录合并/覆盖）。
                5. 恢复 AI 配置：把 ai-config.json 复制到 %s（覆盖）。
                6. 重新启动拾题应用，检查题库与刷题记录是否完整。

                ── 换新电脑 / 全新安装 ────────────────────
                全新安装后先启动一次应用（生成空的数据库文件），退出，再按上面
                3~5 步操作即可；第 2 步可跳过。

                ── 只备份刷题记录（不换电脑）───────────────
                设置页「刷题记录」的导出/导入只含做题记录，用于换库/分享场景；
                需要完整迁移请使用本备份包。
                """.formatted(
                LocalDateTime.now().format(TS),
                dirWin,
                dirWin,
                h2Jar,
                dirUrl,
                dirWin,
                dirWin
        );
        Files.writeString(readme, content, StandardCharsets.UTF_8);
    }

    /** 尽力定位本机 maven 仓库里的 H2 jar（只用于写文档提示，找不到也不影响备份）。 */
    private String findH2JarHint() {
        try {
            //从 classpath 定位 h2 jar：URL 形如 jar:file:/C:/.../h2-2.x.jar!/org/h2/Driver.class
            java.net.URL u = BackupService.class.getClassLoader().getResource("org/h2/Driver.class");
            if (u != null) {
                String spec = u.toExternalForm();
                if (spec.startsWith("jar:file:")) {
                    String inner = spec.substring("jar:file:".length());
                    int bang = inner.indexOf('!');
                    if (bang > 0) {
                        String p = java.net.URLDecoder.decode(inner.substring(0, bang), StandardCharsets.UTF_8)
                                .replace('/', '\\');
                        //去掉盘符前可能残留的 '\'（如 \C:\...）
                        if (p.length() > 2 && p.charAt(0) == '\\'
                                && Character.isLetter(p.charAt(1)) && p.charAt(2) == ':') {
                            p = p.substring(1);
                        }
                        return p;
                    }
                }
            }
        } catch (Exception ignored) {
            // 忽略
        }
        return "C:\\Users\\<你的用户名>\\.m2\\repository\\com\\h2database\\h2\\<版本>\\h2-<版本>.jar";
    }

    private void zipTree(Path dir, OutputStream out) throws IOException {
        try (ZipOutputStream zos = new ZipOutputStream(out)) {
            zos.setLevel(java.util.zip.Deflater.DEFAULT_COMPRESSION);
            try (Stream<Path> stream = Files.walk(dir)) {
                Iterator<Path> it = stream.iterator();
                while (it.hasNext()) {
                    Path p = it.next();
                    if (Files.isDirectory(p)) {
                        continue;
                    }
                    String rel = dir.relativize(p).toString().replace('\\', '/');
                    ZipEntry entry = new ZipEntry(rel);
                    zos.putNextEntry(entry);
                    Files.copy(p, zos);
                    zos.closeEntry();
                }
            }
        }
    }

    private void deleteRecursively(Path root) {
        if (root == null || !Files.exists(root)) {
            return;
        }
        try (Stream<Path> stream = Files.walk(root)) {
            stream.sorted(java.util.Comparator.reverseOrder()).forEach(p -> {
                try {
                    Files.deleteIfExists(p);
                } catch (IOException e) {
                    log.debug("备份临时文件清理失败 {}: {}", p, e.getMessage());
                }
            });
        } catch (IOException e) {
            log.debug("备份临时目录清理失败 {}: {}", root, e.getMessage());
        }
    }
}
