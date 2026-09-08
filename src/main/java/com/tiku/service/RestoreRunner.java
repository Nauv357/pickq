package com.tiku.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.sql.Connection;
import java.sql.Statement;
import java.util.Comparator;
import java.util.stream.Stream;

/**
 * 一键恢复执行器：应用以 --tiku.restore-stage=<解压目录> 启动时，
 * 在 Spring 完全就绪后执行：清空现有库 → H2 RunScript 导入备份 SQL →
 * 覆盖 images / ai-config.json → 清理 restore 暂存。
 * 完成后打印 TIKU_RESTORE_OK（桌面壳等待该行再导航页面，避免竞态）。
 * 正常启动（无该参数）时不做任何事。
 */
@Component
public class RestoreRunner implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(RestoreRunner.class);
    private static final String OK_MARK = "TIKU_RESTORE_OK";

    private final DataSource dataSource;
    private final String dataDir;

    public RestoreRunner(DataSource dataSource, @Value("${tiku.data-dir}") String dataDir) {
        this.dataSource = dataSource;
        this.dataDir = dataDir;
    }

    @Override
    public void run(ApplicationArguments args) throws Exception {
        if (!args.containsOption("tiku.restore-stage")) {
            return;
        }
        String stageStr = args.getOptionValues("tiku.restore-stage").get(0);
        if (stageStr == null || stageStr.isBlank()) {
            log.warn("restore-stage 参数为空，跳过恢复");
            return;
        }
        Path stageDir = Paths.get(stageStr);
        Path sql = stageDir.resolve("database.sql");
        if (!Files.isRegularFile(sql)) {
            log.warn("恢复暂存缺少 database.sql：{}，跳过恢复", stageDir);
            return;
        }
        log.info("==== 检测到待恢复备份，开始一键恢复（staged={}）====", stageDir);
        long t0 = System.currentTimeMillis();

        // 1. 数据库：清空现有对象 → 导入备份 SQL（同连接执行，H2 保证一致）
        try (Connection conn = dataSource.getConnection()) {
            try (Statement st = conn.createStatement()) {
                st.execute("DROP ALL OBJECTS");
            }
            org.h2.tools.RunScript.execute(conn, Files.newBufferedReader(sql, StandardCharsets.UTF_8));
        }
        log.info("数据库脚本导入完成（{}ms）", System.currentTimeMillis() - t0);

        // 2. images/：删除现有后整体覆盖
        Path imagesStage = stageDir.resolve("images");
        if (Files.isDirectory(imagesStage)) {
            Path imagesData = Paths.get(dataDir, "images");
            deleteRecursively(imagesData);
            copyTree(imagesStage, imagesData);
        }

        // 3. ai-config.json（含 API Key，覆盖）
        Path cfgStage = stageDir.resolve("ai-config.json");
        if (Files.isRegularFile(cfgStage)) {
            Files.copy(cfgStage, Paths.get(dataDir, "ai-config.json"),
                    StandardCopyOption.REPLACE_EXISTING);
        }

        // 4. 清理 restore 暂存（含 staged 与任何旧标记）
        deleteRecursively(Paths.get(dataDir, "restore"));

        log.info("一键恢复完成（总耗时 {}ms）", System.currentTimeMillis() - t0);
        System.out.println(OK_MARK);
    }

    private void deleteRecursively(Path root) {
        if (root == null || !Files.exists(root)) {
            return;
        }
        try (Stream<Path> stream = Files.walk(root)) {
            stream.sorted(Comparator.reverseOrder()).forEach(p -> {
                try {
                    Files.deleteIfExists(p);
                } catch (IOException e) {
                    log.debug("清理失败 {}: {}", p, e.getMessage());
                }
            });
        } catch (IOException e) {
            log.debug("清理目录失败 {}: {}", root, e.getMessage());
        }
    }

    private void copyTree(Path src, Path dst) throws IOException {
        Files.createDirectories(dst);
        try (Stream<Path> stream = Files.walk(src)) {
            for (Path p : stream.sorted().toList()) {
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
}
