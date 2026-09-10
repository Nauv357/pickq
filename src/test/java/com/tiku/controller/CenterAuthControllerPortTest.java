package com.tiku.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tiku.dto.ApiResponse;
import com.tiku.service.CenterAuthStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.web.context.WebServerApplicationContext;
import org.springframework.boot.web.server.WebServer;

import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 回归测试：CenterAuthController 对本地端口上下文（WebServerApplicationContext）改为延迟获取后，
 * - 拿不到上下文（@SpringBootTest MOCK 环境）也能构造控制器 → 应用上下文可以加载；
 * - 只在真正发起 GitHub 登录时才需要端口，拿不到时给出原来的可读错误。
 */
class CenterAuthControllerPortTest {

    private static CenterAuthStore store(Path tempDir) {
        return new CenterAuthStore(tempDir.toString(), new ObjectMapper());
    }

    @SuppressWarnings("unchecked")
    private static ObjectProvider<WebServerApplicationContext> providerOf(WebServerApplicationContext ctx) {
        ObjectProvider<WebServerApplicationContext> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(ctx);
        return provider;
    }

    @Test
    void githubStartWithoutWebContextFailsReadably(@TempDir Path tempDir) {
        CenterAuthController controller = new CenterAuthController(store(tempDir), new ObjectMapper(),
                providerOf(null));
        IllegalStateException e = assertThrows(IllegalStateException.class, () -> controller.githubStart(null));
        assertEquals("无法获取本地服务端口，请重启拾题后重试", e.getMessage());
    }

    @Test
    void githubStartUsesLazilyResolvedLocalPort(@TempDir Path tempDir) {
        WebServer webServer = mock(WebServer.class);
        when(webServer.getPort()).thenReturn(54321);
        WebServerApplicationContext webContext = mock(WebServerApplicationContext.class);
        when(webContext.getWebServer()).thenReturn(webServer);

        CenterAuthController controller = new CenterAuthController(store(tempDir), new ObjectMapper(),
                providerOf(webContext));
        ApiResponse<Map<String, Object>> resp = controller.githubStart(null);

        String url = String.valueOf(resp.data().get("url"));
        assertTrue(url.startsWith("https://pickq.cn/api/auth/github/start?desktop=1"), url);
        assertTrue(url.contains("127.0.0.1%3A54321%2Fapi%2Fcenter%2Fauth%2Fgithub%2Fcallback"),
                "回调地址应使用延迟解析出的本地端口：" + url);
    }
}
