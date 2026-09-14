package com.tiku.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * 开发期的跨源放行：只放开**本机**的开发端口。
 *
 * 为什么需要：开发时前端跑在 Vite（3000 / 5173 / 5199），后端在 8080，属跨源；
 * 带 Origin 的写请求（POST/PUT）会被 CORS 拦成 403——表现为"点按钮没反应、提示 403"。
 * 5199 是项目工具链的约定端口（截图脚本 shot-routes、各 smoke:*.mjs 默认都用它），
 * 之前没放行，导致按文档起 dev server 后写接口全部 403（踩过一次）。
 *
 * 生产不受影响：桌面端是后端同源托管前端（没有跨源请求），且只监听 127.0.0.1。
 * localhost 与 127.0.0.1 在浏览器眼里是**两个不同的源**，所以两种写法都要列。
 */
@Configuration
public class WebConfig {

    @Bean
    public WebMvcConfigurer corsConfigurer() {
        return new WebMvcConfigurer() {
            public void addCorsMappings(CorsRegistry registry) {
                registry.addMapping("/api/**")
                        .allowedOrigins(
                                "http://localhost:3000", "http://127.0.0.1:3000",   // 官网（Nuxt）
                                "http://localhost:5173", "http://127.0.0.1:5173",   // Vite 默认端口
                                "http://localhost:5199", "http://127.0.0.1:5199")   // 项目工具链约定端口（截图/冒烟）
                        .allowedMethods("GET", "POST", "PUT", "DELETE", "OPTIONS")
                        .allowedHeaders("*");
            }
        };
    }
}
