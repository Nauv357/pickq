package com.tiku.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * 题库广场桌面会话：登录 token 存本机 {dataDir}/center-auth.json。
 * 风险面与浏览器里的官网会话 cookie 相同（本机可读）；不放 localStorage，
 * 前端不接触 token，代理转发时由后端附加 Authorization 头。
 */
@Service
public class CenterAuthStore {

    private record Stored(String token, String username) {
    }

    private final Path configPath;
    private final ObjectMapper objectMapper;

    public CenterAuthStore(@Value("${tiku.data-dir}") String dataDir, ObjectMapper objectMapper) {
        this.configPath = Path.of(dataDir, "center-auth.json");
        this.objectMapper = objectMapper;
    }

    /** 当前 token；未登录或文件损坏返回 null */
    public synchronized String token() {
        try {
            if (!Files.exists(configPath)) {
                return null;
            }
            Stored s = objectMapper.readValue(configPath.toFile(), Stored.class);
            String token = s == null ? null : s.token();
            return (token == null || token.isBlank()) ? null : token;
        } catch (IOException e) {
            return null;
        }
    }

    public synchronized boolean isLoggedIn() {
        return token() != null;
    }

    public synchronized void save(String token, String username) {
        try {
            Files.createDirectories(configPath.getParent());
            objectMapper.writeValue(configPath.toFile(), new Stored(token, username));
        } catch (IOException e) {
            throw new IllegalStateException("无法保存广场登录状态：" + e.getMessage());
        }
    }

    public synchronized void clear() {
        try {
            Files.deleteIfExists(configPath);
        } catch (IOException e) {
            /* 忽略 */
        }
    }
}
