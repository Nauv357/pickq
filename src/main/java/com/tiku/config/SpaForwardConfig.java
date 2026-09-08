package com.tiku.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.Resource;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;
import org.springframework.web.servlet.resource.PathResourceResolver;

import java.io.IOException;

/**
 * 前端 SPA 回退（生产打包形态：frontend/dist 由后端同源托管）。
 *
 * 桌面前端使用 history 路由（createWebHistory），直接刷新 /banks/xxx 等深链时
 * 静态资源层找不到对应文件——这里把「无扩展名且非 /api」的路径回退到 index.html，
 * 交给前端路由接管。
 *
 * 约束（勿破坏）：
 * - /api/** 一律不回退：controller 未命中时走默认 404 → GlobalExceptionHandler 的
 *   NoResourceFoundException → JSON 错误体（前端 axios 依赖此语义）；
 * - 带文件扩展名的真实资源（/assets/*.js 等）照常解析，不回退。
 */
@Configuration
public class SpaForwardConfig implements WebMvcConfigurer {

    @Override
    public void addResourceHandlers(ResourceHandlerRegistry registry) {
        registry.addResourceHandler("/**")
                .addResourceLocations(
                        "classpath:/META-INF/resources/",
                        "classpath:/resources/",
                        "classpath:/static/",
                        "classpath:/public/")
                .resourceChain(true)
                .addResolver(new PathResourceResolver() {
                    @Override
                    protected Resource getResource(String resourcePath, Resource location) throws IOException {
                        // 常规资源命中即返回（含目录默认页 index.html）
                        Resource requested = location.createRelative(resourcePath);
                        if (requested.exists() && requested.isReadable()) {
                            return requested;
                        }
                        // SPA 回退：非 /api、无扩展名（是路由而非文件）→ index.html
                        boolean apiPath = resourcePath.startsWith("api/") || resourcePath.equals("api");
                        boolean hasExtension = resourcePath.contains(".");
                        if (!apiPath && !hasExtension) {
                            Resource index = location.createRelative("index.html");
                            if (index.exists() && index.isReadable()) {
                                return index;
                            }
                        }
                        return null;
                    }
                });
    }
}
