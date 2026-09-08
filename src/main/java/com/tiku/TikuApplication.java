package com.tiku;

import org.mybatis.spring.annotation.MapperScan;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
@MapperScan("com.tiku.mapper")
public class TikuApplication {

    private static final Logger log = LoggerFactory.getLogger(TikuApplication.class);

    public static void main(String[] args) {
        //桌面壳守护（可选，--tiku.watch-parent=true 时启用）：
        //后端由 Tauri 壳 spawn，若壳进程异常退出/被强杀，本守护线程检测到父进程
        //消失后主动退出——避免 java.exe 残留占端口/锁 H2（壳正常退出时会先 kill 本进程）
        if (Boolean.parseBoolean(System.getProperty("tiku.watch-parent", "false"))) {
            startParentWatcher();
        }
        SpringApplication.run(TikuApplication.class, args);
    }

    private static void startParentWatcher() {
        Thread watcher = new Thread(() -> {
            try {
                ProcessHandle parent = ProcessHandle.current().parent().orElse(null);
                if (parent == null) {
                    return;
                }
                log.info("桌面壳守护已启用：父进程 {} 退出后本进程自动结束", parent.pid());
                while (parent.isAlive()) {
                    Thread.sleep(2000);
                }
                log.warn("父进程已退出，桌面壳守护触发自动结束");
                System.exit(0);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }, "desktop-parent-watch");
        watcher.setDaemon(true);
        watcher.start();
    }
}
