// 拾题桌面壳：启动时 spawn 捆绑的裁剪 JRE 运行后端 fat jar，
// 解析后端随机端口后就绪后把窗口导航到 http://127.0.0.1:{port}/
// （架构参考 gptme：launcher 不是进程本身 → 退出显式 kill + Java 侧父进程守护兜底）
#![cfg_attr(not(debug_assertions), windows_subsystem = "windows")]

use std::io::{BufRead, BufReader};
use std::os::windows::process::CommandExt;
use std::path::PathBuf;
use std::process::{Child, Command, Stdio};
use std::sync::Mutex;
use std::sync::atomic::{AtomicBool, Ordering};
use std::time::{Duration, Instant};

use tauri::{Emitter, Manager, RunEvent, WindowEvent};

const CREATE_NO_WINDOW: u32 = 0x0800_0000;

/// 更新清单地址（自建更新频道：香港服务器 nginx /updates/ 直链）
const UPDATE_MANIFEST_URL: &str = "https://pickq.cn/updates/latest.json";

// ==================== 单实例（免依赖 Win32） ====================
// 第二次启动时不再报"数据库被占用"：检测到已有实例 → 激活其主窗口 → 本进程静默退出。
mod single_instance {
    use std::os::raw::c_void;

    extern "system" {
        fn CreateMutexW(attrs: *const c_void, initial_owner: i32, name: *const u16) -> *mut c_void;
        fn GetLastError() -> u32;
        fn FindWindowW(class: *const u16, title: *const u16) -> *mut c_void;
        fn ShowWindow(hwnd: *mut c_void, cmd: i32) -> i32;
        fn SetForegroundWindow(hwnd: *mut c_void) -> i32;
    }

    const ERROR_ALREADY_EXISTS: u32 = 183;
    const SW_RESTORE: i32 = 9;

    fn wide(s: &str) -> Vec<u16> {
        s.encode_utf16().chain(std::iter::once(0)).collect()
    }

    /// 首次实例：创建并持有互斥锁，返回 false（继续正常启动）。
    /// 已有实例：激活其主窗口（还原最小化 + 置前），返回 true（调用方应退出）。
    pub fn acquire_or_activate() -> bool {
        let name = wide("cn.shiti.desktop.single.instance");
        let mutex = unsafe { CreateMutexW(std::ptr::null(), 0, name.as_ptr()) };
        if mutex.is_null() {
            return false; // 互斥锁创建失败不阻塞启动（罕见）
        }
        let already = unsafe { GetLastError() } == ERROR_ALREADY_EXISTS;
        if !already {
            // 句柄必须保持到进程结束（drop 会释放锁）；进程退出时 OS 自动回收
            std::mem::forget(mutex);
            return false;
        }
        // 已有实例：按主窗口标题精确查找（错误窗口标题带"启动失败"后缀，不会误中）
        let title = wide("拾题");
        let hwnd = unsafe { FindWindowW(std::ptr::null(), title.as_ptr()) };
        if !hwnd.is_null() {
            unsafe {
                ShowWindow(hwnd, SW_RESTORE);
                SetForegroundWindow(hwnd);
            }
        }
        true
    }
}

static BACKEND: Mutex<Option<Child>> = Mutex::new(None);
/// 更新下载取消标志 + 运行中的 curl 子进程（cancel_update 中止下载；部分文件保留供续传）
static UPDATE_CANCEL: AtomicBool = AtomicBool::new(false);
static UPDATE_CHILD: Mutex<Option<Child>> = Mutex::new(None);
static LOG: std::sync::OnceLock<Mutex<std::fs::File>> = std::sync::OnceLock::new();
/// 后端 stderr 中诊断出的关键失败原因（H2 占用等），供失败提示使用
static ERR_HINT: Mutex<String> = Mutex::new(String::new());

/// Windows 长路径前缀（\\?\）JVM/常规 Win32 不认，统一去除
fn win_path(p: &PathBuf) -> String {
    let s = p.to_string_lossy();
    let s = s.strip_prefix(r"\\?\").unwrap_or(&s);
    s.to_string()
}

/// 壳日志（%APPDATA%/cn.shiti.desktop/desktop.log）——后端 stdout/stderr 与关键步骤落盘便于诊断
fn log_file() -> Option<&'static Mutex<std::fs::File>> {
    LOG.get_or_init(|| {
        let dir = std::env::var("APPDATA")
            .map(std::path::PathBuf::from)
            .unwrap_or_else(|_| std::env::temp_dir())
            .join("cn.shiti.desktop");
        let _ = std::fs::create_dir_all(&dir);
        let f = std::fs::OpenOptions::new()
            .create(true)
            .append(true)
            .open(dir.join("desktop.log"))
            .ok();
        Mutex::new(f.expect("无法创建日志文件"))
    })
    .into()
}

fn logln(msg: &str) {
    if let Some(m) = log_file() {
        if let Ok(mut f) = m.lock() {
            use std::io::Write;
            let _ = writeln!(f, "[{}] {}", chrono_now(), msg);
        }
    }
}

fn chrono_now() -> String {
    std::time::SystemTime::now()
        .duration_since(std::time::UNIX_EPOCH)
        .map(|d| d.as_secs().to_string())
        .unwrap_or_default()
}

/// 在打包资源（bundle.resources 复制后位于 resource_dir）中定位裁剪 JRE 与后端 jar
fn locate_resources(resource_dir: &PathBuf) -> Result<(PathBuf, PathBuf), String> {
    //候选布局：resource_dir/jre/bin/java.exe + resource_dir/app.jar
    //（tauri dev 时 resource_dir = target/debug，资源按 conf 复制到其下）
    let mut candidates = vec![
        resource_dir.join("jre").join("bin").join("java.exe"),
        resource_dir.join("resources").join("jre").join("bin").join("java.exe"),
        //开发兜底：直接指向仓库构建产物
        std::env::current_dir()
            .ok()
            .map(|c| c.join("resources").join("jre").join("bin").join("java.exe"))
            .unwrap_or_default(),
    ];
    candidates.retain(|p| p.exists());
    let java = candidates
        .into_iter()
        .next()
        .ok_or_else(|| "未找到捆绑的 JRE（resources/jre/bin/java.exe）".to_string())?;

    let mut jars = vec![
        resource_dir.join("app.jar"),
        resource_dir.join("resources").join("app.jar"),
        std::env::current_dir()
            .ok()
            .map(|c| c.join("resources").join("app.jar"))
            .unwrap_or_default(),
    ];
    jars.retain(|p| p.exists());
    let jar = jars
        .into_iter()
        .next()
        .ok_or_else(|| "未找到后端 app.jar（resources/app.jar）".to_string())?;
    Ok((java, jar))
}

/// 启动后端（--server.port=0 随机端口），从 stdout 解析就绪端口。
/// extra：附加 JVM/应用参数（如 --tiku.data-dir、--tiku.restore-stage）；
/// require_line：Some(标记) 时，Tomcat 就绪后继续等待该行出现才返回（恢复模式用，
/// 避免页面导航撞上恢复执行）。
fn spawn_backend(
    java: &PathBuf,
    jar: &PathBuf,
    extra: &[String],
    require_line: Option<&str>,
) -> Result<u16, String> {
    let java_path = win_path(java);
    let jar_path = win_path(jar);
    let mut cmd_args: Vec<String> = vec![
        "-Dtiku.watch-parent=true".to_string(),
        "-jar".to_string(),
        jar_path.clone(),
    ];
    for a in extra {
        cmd_args.push(a.clone());
    }
    cmd_args.push("--server.address=127.0.0.1".to_string());
    cmd_args.push("--server.port=0".to_string());
    logln(&format!(
        "spawn: {java_path} -jar {jar_path} {}",
        extra.join(" ")
    ));
    let mut child = Command::new(&java_path)
        .args(&cmd_args)
        .creation_flags(CREATE_NO_WINDOW) //Java 是控制台程序：禁止新建黑窗口
        .stdout(Stdio::piped())
        .stderr(Stdio::piped())
        .spawn()
        .map_err(|e| format!("启动后端失败：{e}"))?;

    //保存句柄供退出清理
    *BACKEND.lock().unwrap() = Some(child);

    //stderr 需消费防阻塞——逐行写壳日志并抓关键失败原因（H2 被占用等）
    if let Some(err) = BACKEND.lock().unwrap().as_mut().and_then(|c| c.stderr.take()) {
        std::thread::spawn(move || {
            for line in BufReader::new(err).lines().map_while(Result::ok) {
                logln(&format!("[java-err] {line}"));
                let l = line.to_lowercase();
                if l.contains("already in use") || l.contains("locked by another process")
                    || l.contains("database may be already in use")
                {
                    *ERR_HINT.lock().unwrap() =
                        "数据文件正被另一个拾题实例占用（浏览器版拾题或另一个桌面窗口正在运行？请先关闭它再启动）。"
                            .to_string();
                } else if l.contains("exception") || l.contains("error") {
                    if ERR_HINT.lock().unwrap().is_empty() {
                        let s: String = line.chars().take(160).collect();
                        *ERR_HINT.lock().unwrap() = format!("后端异常：{s}");
                    }
                }
            }
        });
    }
    let out = BACKEND
        .lock()
        .unwrap()
        .as_mut()
        .and_then(|c| c.stdout.take())
        .ok_or("无法读取后端输出")?;

    let deadline = Instant::now()
        + Duration::from_secs(if require_line.is_some() { 150 } else { 45 });
    let mut port: Option<u16> = None;
    for line in BufReader::new(out).lines() {
        let l = line.map_err(|e| format!("读取后端输出失败：{e}"))?;
        logln(&format!("[java] {l}"));
        //Spring Boot 就绪行：Tomcat started on port 12345 (http) with context path '/'
        if let Some(idx) = l.find("Tomcat started on port") {
            let tail = &l[idx + "Tomcat started on port".len()..];
            let p: u16 = tail
                .split_whitespace()
                .next()
                .and_then(|s| s.trim_matches(|c: char| !c.is_ascii_digit()).parse().ok())
                .ok_or_else(|| format!("解析后端端口失败：{l}"))?;
            logln(&format!("backend ready on port {p}"));
            if require_line.is_none() {
                return Ok(p);
            }
            port = Some(p);
        }
        if let Some(req) = require_line {
            if port.is_some() && l.contains(req) {
                let p = port.unwrap();
                logln(&format!("restore complete, backend ready on port {p}"));
                return Ok(p);
            }
        }
        if l.contains("APPLICATION FAILED TO START") || l.contains("Application run failed") {
            kill_backend();
            return Err(fail_reason("后端启动失败"));
        }
        if Instant::now() > deadline {
            kill_backend();
            return Err(fail_reason("后端启动超时"));
        }
    }
    kill_backend();
    Err(fail_reason("后端进程提前退出"))
}

/// 附加 stderr 诊断出的具体原因（H2 占用等），让错误页可读可行动
fn fail_reason(base: &str) -> String {
    let hint = ERR_HINT.lock().unwrap().clone();
    if hint.is_empty() {
        base.to_string()
    } else {
        format!("{base}。{hint}")
    }
}

/// 「另存为」系统对话框并写入文件（桌面版导出统一出口；浏览器版仍走浏览器下载）
/// 前端传：filename（建议文件名）+ data_base64（文件内容）；返回：保存路径（取消=null）。
/// 记忆上次选择的目录，下次默认打开。
static LAST_DIR: Mutex<Option<std::path::PathBuf>> = Mutex::new(None);

#[tauri::command]
fn save_dialog_file(filename: String, data_base64: String) -> Result<Option<String>, String> {
    let bytes = base64::Engine::decode(&base64::engine::general_purpose::STANDARD, data_base64)
        .map_err(|e| format!("数据解码失败：{e}"))?;
    //默认目录：上次选择 > 系统下载目录 > 临时目录
    let last = LAST_DIR.lock().unwrap().clone();
    let default_dir = last.unwrap_or_else(|| {
        std::env::var("USERPROFILE")
            .ok()
            .map(|p| std::path::PathBuf::from(p).join("Downloads"))
            .filter(|p| p.exists())
            .unwrap_or_else(std::env::temp_dir)
    });
    let mut dialog = rfd::FileDialog::new().set_file_name(&filename);
    if let Some(d) = default_dir.to_str() {
        dialog = dialog.set_directory(d);
    }
    let picked = dialog.save_file();
    let Some(path) = picked else {
        return Ok(None); //用户取消
    };
    std::fs::write(&path, &bytes).map_err(|e| format!("写入文件失败：{e}"))?;
    if let Some(parent) = path.parent() {
        *LAST_DIR.lock().unwrap() = Some(parent.to_path_buf());
    }
    Ok(Some(path.to_string_lossy().to_string()))
}

// ==================== 自动更新（自建频道） ====================
// 设计：不用 tauri-plugin-updater（其 NSIS 安装回默认目录，会破坏自定义安装位置
// 如 D:\拾题）。改为壳内自实现：curl 拉清单/下载 → certutil 校验 SHA256 →
// 等待自身进程退出 → 静默安装回【当前 exe 所在目录】→ 自动重启。
// 清单 JSON：{ "version": "0.1.2", "url": "https://pickq.cn/updates/...exe",
//              "sha256": "<64 hex>", "size": 123456, "notes": "更新说明" }

/// 前端展示用：当前版本
#[tauri::command]
fn app_version() -> String {
    env!("CARGO_PKG_VERSION").to_string()
}

/// 用系统默认浏览器打开外链（WebView2 会拦截 window.open / target=_blank 新窗口，
/// 桌面版一律经此命令跳系统浏览器）。仅允许 http(s)，防注入。
/// 注意：
/// - 不能用 `cmd /c start "" url`（Rust 参数转义与 cmd 引号剥离叠加会把引号吞掉，
///   实测弹 "Windows 找不到 '\' 文件" 错误框）；
/// - 不能用 explorer.exe 打开带 query 的 URL（如 ...?apiKey=1，实测会打开本地文件夹）；
/// - rundll32 url.dll,FileProtocolHandler 是 URL 协议的标准转交，无上述问题。
#[tauri::command]
fn open_url(url: String) -> Result<(), String> {
    let url = url.trim();
    if !url.starts_with("http://") && !url.starts_with("https://") {
        return Err("仅支持 http(s) 链接".to_string());
    }
    if url.contains('"') || url.contains('\n') || url.contains('\r') {
        return Err("链接不合法".to_string());
    }
    let spawned = Command::new("rundll32.exe")
        .args(["url.dll,FileProtocolHandler", url])
        .spawn();
    match spawned {
        Ok(_) => Ok(()),
        Err(_) => {
            // 兜底：explorer 打开 URL（不带 query 的简单链接可用）
            Command::new("explorer.exe")
                .arg(url)
                .spawn()
                .map_err(|e| format!("无法打开浏览器：{e}"))?;
            Ok(())
        }
    }
}

#[derive(serde::Deserialize)]
struct UpdateManifest {
    version: String,
    url: String,
    sha256: String,
    #[serde(default)]
    size: u64,
    #[serde(default)]
    notes: Option<String>,
}

/// 更新信息（返回前端展示 + 下载参数）
#[derive(serde::Serialize, Clone)]
pub struct UpdateInfo {
    version: String,
    url: String,
    sha256: String,
    size: u64,
    notes: Option<String>,
}

fn parse_version(v: &str) -> Vec<u32> {
    v.split(|c: char| c == '.' || c == '-')
        .filter_map(|s| s.parse().ok())
        .collect()
}

/// a > b ？
fn is_newer(a: &str, b: &str) -> bool {
    let (va, vb) = (parse_version(a), parse_version(b));
    for (x, y) in va.iter().zip(vb.iter()) {
        if x != y {
            return x > y;
        }
    }
    va.len() > vb.len()
}

/// curl.exe 静默 GET（Windows 10+ 系统自带）
fn curl_text(url: &str, max_secs: u32) -> Result<String, String> {
    let out = Command::new("curl.exe")
        .args(["-s", "--fail", "-L", "--max-time", &max_secs.to_string(), url])
        .creation_flags(CREATE_NO_WINDOW)
        .output()
        .map_err(|e| format!("无法启动 curl：{e}"))?;
    if !out.status.success() {
        return Err("连接更新服务器失败".to_string());
    }
    String::from_utf8(out.stdout).map_err(|_| "更新服务器返回了非文本内容".to_string())
}

/// 检查更新：拉取清单并与当前版本比较。Ok(None)=已最新；Err=网络/清单问题（静默提示）
#[tauri::command]
async fn check_update() -> Result<Option<UpdateInfo>, String> {
    let text = curl_text(UPDATE_MANIFEST_URL, 10)?;
    let m: UpdateManifest =
        serde_json::from_str(&text).map_err(|e| format!("更新清单格式错误：{e}"))?;
    let cur = env!("CARGO_PKG_VERSION");
    if !is_newer(&m.version, cur) {
        return Ok(None);
    }
    Ok(Some(UpdateInfo {
        version: m.version,
        url: m.url,
        sha256: m.sha256.trim().to_lowercase(),
        size: m.size,
        notes: m.notes,
    }))
}

#[derive(Clone, serde::Serialize)]
struct UpdateProgress {
    downloaded: u64,
    total: u64,
}

/// 下载安装包到 %TEMP%/shiti-setup-{version}-x64-setup.exe，校验 SHA256；
/// 进度经事件 "shiti://update-progress" 推送前端。
/// 断点续传：curl -C -，不删除未完成的部分文件（重试/重启后接着下载）；
/// 取消下载：cancel_update 命令置标志并结束 curl，已下部分同样保留供续传。
#[tauri::command]
async fn download_update(
    app: tauri::AppHandle,
    url: String,
    sha256: String,
    size: u64,
    version: String,
) -> Result<(), String> {
    let exe_path = std::env::temp_dir().join(format!("shiti-setup-{version}-x64-setup.exe"));
    UPDATE_CANCEL.store(false, Ordering::SeqCst);

    // 已有完整文件则跳过下载直接校验（重复下载/已下载完成场景）
    let existing = std::fs::metadata(&exe_path).map(|m| m.len()).unwrap_or(0);
    if existing < size {
        let child = Command::new("curl.exe")
            .args(["-L", "--fail", "-s", "-C", "-", "-o"])
            .arg(&exe_path)
            .arg(&url)
            .creation_flags(CREATE_NO_WINDOW)
            .spawn()
            .map_err(|e| format!("无法启动下载：{e}"))?;
        *UPDATE_CHILD.lock().unwrap() = Some(child);

        loop {
            // 收到取消请求：终止 curl（部分文件保留，下次自动续传）
            if UPDATE_CANCEL.load(Ordering::SeqCst) {
                if let Some(mut c) = UPDATE_CHILD.lock().unwrap().take() {
                    let _ = c.kill();
                    // 不阻塞等待：kill 后进程可能已被 cancel_update 回收
                    let _ = c.try_wait();
                }
                return Err("下载已取消".to_string());
            }
            let mut finished: Option<bool> = None;
            if let Some(c) = UPDATE_CHILD.lock().unwrap().as_mut() {
                match c.try_wait() {
                    Ok(Some(status)) => finished = Some(status.success()),
                    Ok(None) => {}
                    Err(_) => finished = Some(false),
                }
            }
            if let Some(ok) = finished {
                UPDATE_CHILD.lock().unwrap().take();
                if !ok {
                    return Err("下载失败，请检查网络后重试（已下载部分已保留，可直接重试续传）".to_string());
                }
                break;
            }
            let downloaded = std::fs::metadata(&exe_path).map(|m| m.len()).unwrap_or(0);
            let _ = app.emit(
                "shiti://update-progress",
                UpdateProgress {
                    downloaded,
                    total: size,
                },
            );
            std::thread::sleep(Duration::from_millis(250));
        }
    }
    // 下载结束（或已有完整文件）后仍可能收到取消请求：直接返回，不进入校验/安装阶段
    if UPDATE_CANCEL.load(Ordering::SeqCst) {
        return Err("下载已取消".to_string());
    }
    let _ = app.emit(
        "shiti://update-progress",
        UpdateProgress {
            downloaded: size,
            total: size,
        },
    );

    // SHA256 校验（certutil 系统自带）
    let out = Command::new("certutil")
        .args(["-hashfile"])
        .arg(&exe_path)
        .arg("SHA256")
        .creation_flags(CREATE_NO_WINDOW)
        .output()
        .map_err(|e| format!("无法启动校验：{e}"))?;
    let text = String::from_utf8_lossy(&out.stdout);
    let got = text
        .split_whitespace()
        .find(|t| t.len() == 64 && t.chars().all(|c| c.is_ascii_hexdigit()))
        .map(|s| s.to_lowercase())
        .unwrap_or_default();
    if got != sha256.trim().to_lowercase() {
        let _ = std::fs::remove_file(&exe_path); // 内容损坏：删除避免下次误用
        return Err("下载文件校验失败（SHA256 不匹配），请重试".to_string());
    }
    Ok(())
}

/// 取消进行中的下载（保留已下载部分，下次下载自动续传）
#[tauri::command]
fn cancel_update() {
    // 置标志 + 立即结束 curl：download_update 最迟在下一个轮询周期（250ms）返回「下载已取消」；
    // 前端另有"立即关闭对话框"的乐观路径，二者互补，保证取消永远能退出。
    UPDATE_CANCEL.store(true, Ordering::SeqCst);
    if let Some(mut c) = UPDATE_CHILD.lock().unwrap().take() {
        let _ = c.kill();
        let _ = c.try_wait();
    }
}

/// 安装更新：写等待脚本（等自身退出 → NSIS /S 装回当前 exe 目录 → 重启）并退出进程。
/// 安装目录经环境变量传给 cmd 脚本，规避中文路径在批处理里的编码问题。
#[tauri::command]
fn install_update(version: String) -> Result<(), String> {
    let exe_name = format!("shiti-setup-{version}-x64-setup.exe");
    let exe_path = std::env::temp_dir().join(&exe_name);
    if !exe_path.exists() {
        return Err("安装包不存在，请重新下载".to_string());
    }
    let install_dir = std::env::current_exe()
        .ok()
        .and_then(|p| p.parent().map(|d| d.to_path_buf()))
        .ok_or_else(|| "无法确定安装目录".to_string())?;
    let self_name = std::env::current_exe()
        .ok()
        .and_then(|p| p.file_name().map(|n| n.to_string_lossy().to_string()))
        .unwrap_or_else(|| "tiku-desktop.exe".to_string());

    let runner = std::env::temp_dir().join("shiti-update-runner.cmd");
    let script = format!(
        "@echo off\r\n\
         setlocal enabledelayedexpansion\r\n\
         set /a tries=0\r\n\
         :wait\r\n\
         tasklist /FI \"IMAGENAME eq {self_name}\" 2>NUL | find /I \"{self_name}\" >NUL\r\n\
         if not errorlevel 1 (\r\n\
         \x20 set /a tries+=1\r\n\
         \x20 if !tries! GEQ 90 goto :run\r\n\
         \x20 ping -n 2 127.0.0.1 >NUL\r\n\
         \x20 goto :wait\r\n\
         )\r\n\
         :run\r\n\
         \"%TEMP%\\{exe_name}\" /S /D=%SHITI_INSTALL_DIR%\r\n\
         set RC=%ERRORLEVEL%\r\n\
         del \"%TEMP%\\{exe_name}\" >NUL 2>&1\r\n\
         if exist \"%SHITI_INSTALL_DIR%\\{self_name}\" start \"\" \"%SHITI_INSTALL_DIR%\\{self_name}\"\r\n\
         del \"%~f0\" >NUL 2>&1\r\n\
         exit /b %RC%\r\n"
    );
    std::fs::write(&runner, script).map_err(|e| format!("无法写入更新脚本：{e}"))?;

    let _ = Command::new("cmd")
        .arg("/c")
        .arg(&runner)
        .env("SHITI_INSTALL_DIR", &install_dir)
        .creation_flags(CREATE_NO_WINDOW)
        .spawn()
        .map_err(|e| format!("无法启动更新程序：{e}"))?;

    logln(&format!("install_update: spawning runner, install_dir={install_dir:?}"));
    // 让 runner 先进入等待循环，再退出自身进程（退出后 exe 解锁，NSIS 才能覆盖）
    std::thread::sleep(Duration::from_millis(1200));
    std::process::exit(0);
}

fn kill_backend() {
    logln("kill_backend called");
    if let Some(mut child) = BACKEND.lock().unwrap().take() {
        let _ = child.kill();
        let _ = child.wait();
        logln("backend killed");
    }
}

/// 简单错误页（打包内静态 ui/error.html——通用文案；具体原因见窗口标题与 desktop.log）

fn main() {
    // 单实例：已有实例在运行 → 激活其窗口后静默退出（不再弹"数据库被占用"错误）
    if single_instance::acquire_or_activate() {
        logln("已有拾题实例在运行，激活既有窗口后本实例退出");
        std::process::exit(0);
    }
    let app = tauri::Builder::default()
        .invoke_handler(tauri::generate_handler![
            save_dialog_file,
            app_version,
            open_url,
            restart_with_restore,
            check_update,
            download_update,
            cancel_update,
            install_update
        ])
        .setup(|app| {
            let handle = app.handle().clone();
            //先让窗口显示本地 loading 页
            logln(&format!("resource_dir: {}", handle.path().resource_dir().unwrap_or_default().display()));
            // 环境变量 TIKU_DATA_DIR 可覆盖数据目录（测试实例/便携数据随行场景）
            let mut extra: Vec<String> = Vec::new();
            if let Ok(dir) = std::env::var("TIKU_DATA_DIR") {
                let dir = dir.trim().to_string();
                if !dir.is_empty() {
                    extra.push(format!("--tiku.data-dir={dir}"));
                    logln(&format!("TIKU_DATA_DIR={dir}"));
                }
            }
            launch_backend(&handle, extra, None);
            Ok(())
        })
        .on_window_event(|window, event| {
            //窗口关闭：先收拾后端，再显式退出（Tauri 2 不再默认"最后窗口关闭即退出"）
            match event {
                WindowEvent::CloseRequested { .. } => {
                    logln("window close requested");
                    kill_backend();
                    window.app_handle().exit(0);
                }
                WindowEvent::Destroyed => {
                    logln("window destroyed");
                }
                _ => {}
            }
        })
        .build(tauri::generate_context!())
        .expect("构建 Tauri 应用失败");

    app.run(|app_handle, event| {
        match event {
            RunEvent::ExitRequested { .. } => {
                logln("run: ExitRequested");
            }
            RunEvent::Exit => {
                logln("run: Exit");
                kill_backend();
            }
            _ => {}
        }
    });
}

/// 定位资源并异步启动后端，就绪后导航主窗口；失败弹错误窗口（正常启动与恢复重启共用）。
fn launch_backend(handle: &tauri::AppHandle, extra: Vec<String>, require_line: Option<String>) {
    let handle = handle.clone();
    std::thread::spawn(move || {
        let resource_dir = handle.path().resource_dir().unwrap_or_default();
        let result = (|| -> Result<u16, String> {
            let (java, jar) = locate_resources(&resource_dir)?;
            spawn_backend(&java, &jar, &extra, require_line.as_deref())
        })();
        match result {
            Ok(port) => {
                let url = format!("http://127.0.0.1:{port}/");
                logln(&format!("navigate to {url}"));
                if let Some(w) = handle.get_webview_window("main") {
                    let _ = w.navigate(url.parse().unwrap());
                }
            }
            Err(msg) => {
                logln(&format!("启动失败：{msg}"));
                //错误呈现：不经 navigate（后台线程 navigate 本地 asset URL 会异常退出）；
                //loading 主窗口保留在后面，额外以指定 URL 直接新建错误窗口（置前）。
                //注意：不要 hide/destroy 主窗口（hide 会竞态触发 CloseRequested 导致
                //错误窗口来不及展示应用即退出）
                let short: String = msg.chars().take(80).collect();
                let result = tauri::WebviewWindowBuilder::new(
                    &handle,
                    "error",
                    tauri::WebviewUrl::App("error.html".into()),
                )
                .title(format!("拾题启动失败：{short}"))
                .inner_size(660.0, 480.0)
                .resizable(false)
                .center()
                .build();
                if let Err(e) = result {
                    logln(&format!("error window 创建失败：{e}"));
                }
                //让错误窗口置顶显示
                if let Some(w) = handle.get_webview_window("error") {
                    let _ = w.set_focus();
                }
            }
        }
    });
}

/// 一键恢复：前端已把备份 zip 解压到 {dataDir}/restore/staged。
/// 杀掉当前后端 → 带 --tiku.restore-stage 重启 → 后端执行恢复并打印
/// TIKU_RESTORE_OK → 导航页面（应用窗口全程不关闭）。
#[tauri::command]
fn restart_with_restore(app: tauri::AppHandle, data_dir: String) -> Result<(), String> {
    let stage = std::path::Path::new(data_dir.trim()).join("restore").join("staged");
    if !stage.is_dir() {
        return Err("未找到待恢复数据（请先在「完整备份」中上传备份文件）".to_string());
    }
    logln(&format!(
        "restart_with_restore: stage={}",
        stage.to_string_lossy()
    ));
    kill_backend();
    // 清掉旧错误提示，避免误报
    ERR_HINT.lock().unwrap().clear();
    let extra = vec![format!(
        "--tiku.restore-stage={}",
        win_path(&stage).replace('\\', "/")
    )];
    launch_backend(&app, extra, Some("TIKU_RESTORE_OK".to_string()));
    Ok(())
}
