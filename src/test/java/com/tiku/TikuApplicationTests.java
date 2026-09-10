package com.tiku;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

/**
 * 应用上下文冒烟测试。
 *
 * MOCK web 环境即可加载：CenterAuthController 对本地端口上下文（WebServerApplicationContext）
 * 改为延迟获取（ObjectProvider），不再在构造期强依赖真实内嵌服务器。
 *
 * profile=test（见 src/test/resources/application-test.yml）：数据目录指到系统临时目录、
 * 数据库用内存 H2，避免与正在运行的桌面端争抢 ~/.tiku/tiku.mv.db 的文件锁，
 * 也避免测试读写用户真实数据。
 */
@SpringBootTest
@ActiveProfiles("test")
class TikuApplicationTests {

    @Test
    void contextLoads() {
    }

}
