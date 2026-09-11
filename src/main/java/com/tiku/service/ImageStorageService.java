package com.tiku.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * 题目图片存储（资料分析图表、主观题参考答案图等）。
 * 存储：${tiku.data-dir}/images/{bankId}/{yyMMdd}/{uuid}.{ext}
 * 题目 content / reference_answer / material.content 内用标记引用：[图片:文件名]
 * 读取：GET /api/banks/{bankId}/images/{name}
 */
@Service
public class ImageStorageService {

    private static final Pattern SAFE_SEGMENT = Pattern.compile("^[a-zA-Z0-9._-]+$");
    private static final long MAX_IMAGE_BYTES = 10 * 1024 * 1024;

    private final Path imagesDir;

    public ImageStorageService(@Value("${tiku.data-dir}") String dataDir) {
        this.imagesDir = Path.of(dataDir, "images");
    }

    /** 上传图片，返回引用文件名（如 260829/ab12cd34.png） */
    public String upload(Long bankId, MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new IllegalArgumentException("图片文件为空");
        }
        if (file.getSize() > MAX_IMAGE_BYTES) {
            throw new IllegalArgumentException("图片过大（超过 10MB）");
        }
        String name = createGeneratedName(resolveExtension(file.getOriginalFilename()));
        try {
            writeImage(bankId, name, file.getBytes());
        } catch (IOException e) {
            throw new IllegalStateException("图片保存失败", e);
        }
        return name;
    }

    /** AI 导入：把已提取的图片字节落盘，返回正式引用文件名（扩展名按字节头判断，缺省 png） */
    public String importImage(Long bankId, byte[] bytes) {
        if (bytes == null || bytes.length == 0) {
            throw new IllegalArgumentException("图片数据为空");
        }
        if (bytes.length > MAX_IMAGE_BYTES) {
            throw new IllegalArgumentException("图片过大（超过 10MB）");
        }
        String name = createGeneratedName(detectExtension(bytes));
        try {
            writeImage(bankId, name, bytes);
        } catch (IOException e) {
            throw new IllegalStateException("图片保存失败", e);
        }
        return name;
    }

    /** 按文件头判断扩展名：PNG/JPEG/GIF/WEBP/BMP，缺省 png */
    private String detectExtension(byte[] b) {
        if (b.length >= 8 && (b[0] & 0xFF) == 0x89 && b[1] == 'P' && b[2] == 'N' && b[3] == 'G') return "png";
        if (b.length >= 3 && (b[0] & 0xFF) == 0xFF && (b[1] & 0xFF) == 0xD8 && (b[2] & 0xFF) == 0xFF) return "jpg";
        if (b.length >= 3 && b[0] == 'G' && b[1] == 'I' && b[2] == 'F') return "gif";
        if (b.length >= 12 && b[0] == 'R' && b[1] == 'I' && b[2] == 'F' && b[3] == 'F'
                && b[8] == 'W' && b[9] == 'E' && b[10] == 'B' && b[11] == 'P') return "webp";
        if (b.length >= 2 && b[0] == 'B' && b[1] == 'M') return "bmp";
        return "png";
    }

    /** 读取图片字节（校验文件名安全，防路径穿越） */
    public byte[] read(Long bankId, String name) {
        Path path = resolveImagePath(bankId, name);
        if (!Files.exists(path)) {
            throw new java.util.NoSuchElementException("图片不存在：" + name);
        }
        try {
            return Files.readAllBytes(path);
        } catch (IOException e) {
            throw new IllegalStateException("图片读取失败", e);
        }
    }

    public String contentType(String name) {
        if (name == null || !name.contains(".")) {
            return "application/octet-stream";
        }
        return switch (name.substring(name.lastIndexOf('.') + 1).toLowerCase(Locale.ROOT)) {
            case "png" -> "image/png";
            case "jpg", "jpeg" -> "image/jpeg";
            case "gif" -> "image/gif";
            case "webp" -> "image/webp";
            case "bmp" -> "image/bmp";
            default -> "application/octet-stream";
        };
    }

    /** 跨库复制图片（题库合并用）：读源库 → 写目标库同名（目标已存在则跳过）。
     *  返回实际复制成功的名字集合（缺失/失败的名字忽略，与内容包导入语义一致）。 */
    public java.util.Set<String> copyToBank(Long fromBankId, Long toBankId, Collection<String> names) {
        java.util.Set<String> copied = new java.util.HashSet<>();
        if (names == null) {
            return copied;
        }
        for (String name : names) {
            if (!isSafeImageName(name)) {
                continue;
            }
            Path target = resolveImagePath(toBankId, name);
            if (Files.exists(target)) {
                continue; // 目标已有（同内容引用去重），跳过
            }
            try {
                byte[] bytes = read(fromBankId, name);
                writeImage(toBankId, name, bytes);
                copied.add(name);
            } catch (Exception ignored) {
                // 源缺失/读取失败：跳过
            }
        }
        return copied;
    }

    // ==================== 内容包导出/导入辅助 ====================

    /** 从文本（content/referenceAnswer/material.content）提取 [图片:name] 引用 */
    public static final java.util.regex.Pattern IMAGE_REF =
            java.util.regex.Pattern.compile("\\[图片:([a-zA-Z0-9._/-]+)]");

    /** 收集文本中引用的图片 → base64（内容包导出用） */
    public Map<String, String> collectBase64(Long bankId, List<String> texts) {
        Map<String, String> images = new java.util.LinkedHashMap<>();
        if (texts == null) {
            return images;
        }
        java.util.regex.Matcher m = IMAGE_REF.matcher(
                texts.stream().filter(Objects::nonNull).collect(java.util.stream.Collectors.joining("\n")));
        while (m.find()) {
            String name = m.group(1);
            if (!images.containsKey(name)) {
                try {
                    images.put(name, java.util.Base64.getEncoder().encodeToString(read(bankId, name)));
                } catch (Exception ignored) {
                    //引用但文件缺失：跳过（导入端会忽略缺失图片）
                }
            }
        }
        return images;
    }

    /** 落盘内容包中的 base64 图片（导入用）；返回成功落盘的名字集合 */
    public java.util.Set<String> saveBase64(Long bankId, Map<String, String> images) {
        java.util.Set<String> saved = new java.util.HashSet<>();
        if (images == null) {
            return saved;
        }
        for (Map.Entry<String, String> e : images.entrySet()) {
            String name = e.getKey();
            if (!isSafeImageName(name)) {
                continue;
            }
            try {
                byte[] bytes = java.util.Base64.getDecoder().decode(e.getValue());
                if (bytes.length == 0 || bytes.length > MAX_IMAGE_BYTES) {
                    continue;
                }
                writeImage(bankId, name, bytes);
                saved.add(name);
            } catch (Exception ignored) {
                //单张失败不影响其余
            }
        }
        return saved;
    }

    private String resolveExtension(String filename) {
        if (filename != null && filename.contains(".")) {
            String ext = filename.substring(filename.lastIndexOf('.') + 1).toLowerCase(Locale.ROOT);
            if (ext.matches("png|jpg|jpeg|gif|webp|bmp")) {
                return ext;
            }
        }
        return "png";
    }

    private String createGeneratedName(String extension) {
        String date = LocalDate.now().format(DateTimeFormatter.ofPattern("yyMMdd"));
        String fileName = UUID.randomUUID().toString().replace("-", "").substring(0, 12) + "." + extension;
        return date + "/" + fileName;
    }

    /**
     * 只接受旧版本兼容的单文件名或“日期目录/文件名”两种引用形式。
     * 每个路径段单独校验，明确拒绝 . 与 ..，避免正则允许字符却被文件系统解释为路径跳转。
     */
    private boolean isSafeImageName(String name) {
        if (name == null || name.isBlank() || name.indexOf('\\') >= 0 || name.startsWith("/")) {
            return false;
        }
        String[] segments = name.split("/", -1);
        if (segments.length < 1 || segments.length > 2) {
            return false;
        }
        for (String segment : segments) {
            if (segment.isBlank() || segment.startsWith(".") || segment.endsWith(".")
                    || !SAFE_SEGMENT.matcher(segment).matches()) {
                return false;
            }
        }
        return true;
    }

    /** 在对应题库目录中解析图片引用；即使以后放宽命名规则，仍由 startsWith 作为最终边界。 */
    private Path resolveImagePath(Long bankId, String name) {
        if (bankId == null || bankId <= 0) {
            throw new IllegalArgumentException("题库 ID 不合法");
        }
        if (!isSafeImageName(name)) {
            throw new IllegalArgumentException("非法图片名");
        }
        Path bankDir = imagesDir.resolve(String.valueOf(bankId)).toAbsolutePath().normalize();
        Path target = bankDir.resolve(name).normalize();
        if (!target.startsWith(bankDir)) {
            throw new IllegalArgumentException("图片路径越界");
        }
        return target;
    }

    private void writeImage(Long bankId, String name, byte[] bytes) throws IOException {
        Path target = resolveImagePath(bankId, name);
        Files.createDirectories(target.getParent());
        Files.write(target, bytes);
    }
}
