package com.tiku.config;

import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * 本地数据目录初始化：数据库文件、导入的内容包、刷题记录均存放于此。
 * 目录不存在时自动创建（H2 自身也会创建，此处显式兜底并输出日志便于排查）。
 */
@Slf4j
@Configuration
public class DataDirectoryConfig {

    @Value("${tiku.data-dir}")
    private String dataDir;

    @PostConstruct
    public void ensureDataDir() throws IOException {
        Path dir = Paths.get(dataDir);
        Files.createDirectories(dir);
        log.info("本地数据目录: {}", dir.toAbsolutePath());
    }
}
