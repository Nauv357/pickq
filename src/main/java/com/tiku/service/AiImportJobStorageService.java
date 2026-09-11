package com.tiku.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * AI 导入任务的临时文件存储。
 *
 * <p>该类是任务目录的唯一所有者，统一约束目录结构为
 * {@code {data-dir}/imports/{jobId}/}，避免业务编排代码散落路径拼接、文件名净化和递归删除逻辑。</p>
 */
@Service
public class AiImportJobStorageService {

    private static final Pattern IMAGE_FILE_NAME = Pattern.compile("^(\\d+)\\.([a-zA-Z0-9]+)$");
    private static final int MAX_FILE_NAME_LENGTH = 150;

    private final Path importsDir;

    public AiImportJobStorageService(@Value("${tiku.data-dir}") String dataDir) {
        this.importsDir = Path.of(dataDir, "imports").toAbsolutePath().normalize();
    }

    /** 取得任务目录；任务编号必须为正数，返回路径始终位于 imports 根目录内。 */
    public Path jobDirectory(Long jobId) {
        if (jobId == null || jobId <= 0) {
            throw new IllegalArgumentException("导入任务编号无效");
        }
        Path directory = importsDir.resolve(String.valueOf(jobId)).normalize();
        if (!directory.startsWith(importsDir)) {
            throw new IllegalArgumentException("导入任务路径无效");
        }
        return directory;
    }

    /** 将上传文件写入任务目录；文件名须先通过 {@link #sanitizeFileName(String)} 净化。 */
    public void storeInputFiles(Long jobId, List<String> fileNames, List<byte[]> files) throws IOException {
        if (fileNames == null || files == null || fileNames.size() != files.size() || fileNames.isEmpty()) {
            throw new IllegalArgumentException("导入文件与文件名不匹配");
        }
        Path directory = jobDirectory(jobId);
        Files.createDirectories(directory);
        for (int index = 0; index < fileNames.size(); index++) {
            String fileName = fileNames.get(index);
            byte[] content = files.get(index);
            if (!sanitizeFileName(fileName).equals(fileName)) {
                throw new IllegalArgumentException("导入文件名未净化");
            }
            if (content == null) {
                throw new IllegalArgumentException("导入文件内容不能为空");
            }
            Files.write(inputFile(jobId, index, fileName), content,
                    StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE);
        }
    }

    /** 取得任务中的原始输入文件路径。 */
    public Path inputFile(Long jobId, int index, String sanitizedFileName) {
        if (index < 0 || !sanitizeFileName(sanitizedFileName).equals(sanitizedFileName)) {
            throw new IllegalArgumentException("导入文件路径无效");
        }
        Path input = jobDirectory(jobId).resolve(index + "-" + sanitizedFileName).normalize();
        if (!input.startsWith(jobDirectory(jobId))) {
            throw new IllegalArgumentException("导入文件路径无效");
        }
        return input;
    }

    /** 将文档解析出的图片按全局编号写入 {@code images/{N}.png}。 */
    public void storeExtractedImages(Long jobId, List<DocumentParserService.ExtractedImage> images) throws IOException {
        if (images == null || images.isEmpty()) {
            return;
        }
        Path imagesDir = imagesDirectory(jobId);
        Files.createDirectories(imagesDir);
        for (int index = 0; index < images.size(); index++) {
            DocumentParserService.ExtractedImage image = images.get(index);
            if (image == null || image.image() == null || image.image().data() == null) {
                throw new IllegalArgumentException("提取图片内容不能为空");
            }
            Files.write(imagesDir.resolve((index + 1) + ".png"), image.image().data(),
                    StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE);
        }
    }

    /** 列出素材区可预览的临时图片。 */
    public List<JobImageInfo> listImages(Long jobId) {
        Path imagesDir = imagesDirectory(jobId);
        if (!Files.isDirectory(imagesDir)) {
            return List.of();
        }
        List<JobImageInfo> result = new ArrayList<>();
        try (var files = Files.list(imagesDir)) {
            files.filter(Files::isRegularFile).forEach(file -> {
                Matcher matcher = IMAGE_FILE_NAME.matcher(file.getFileName().toString());
                if (matcher.matches()) {
                    try {
                        result.add(new JobImageInfo(
                                Integer.parseInt(matcher.group(1)),
                                file.getFileName().toString(),
                                matcher.group(2).toLowerCase(Locale.ROOT)));
                    } catch (NumberFormatException ignored) {
                        // 极大编号不是可用的图片素材，忽略即可。
                    }
                }
            });
        } catch (IOException e) {
            throw new IllegalStateException("读取任务图片列表失败", e);
        }
        result.sort(Comparator.comparingInt(JobImageInfo::num));
        return List.copyOf(result);
    }

    /** 读取素材区图片；优先使用 PNG，并兼容旧任务的其他扩展名。 */
    public byte[] readImage(Long jobId, int number) throws IOException {
        if (number <= 0) {
            throw new IllegalArgumentException("图片编号无效：" + number);
        }
        Path image = findImage(jobId, number);
        if (image == null) {
            throw new IllegalArgumentException("图片不存在：" + number);
        }
        return Files.readAllBytes(image);
    }

    /** 读取约定 PNG 图片；不存在时返回 null，供确认导入阶段保留原图片标记。 */
    public byte[] readPngImageIfPresent(Long jobId, int number) throws IOException {
        if (number <= 0) {
            return null;
        }
        Path image = imagesDirectory(jobId).resolve(number + ".png").normalize();
        if (!image.startsWith(imagesDirectory(jobId)) || !Files.isRegularFile(image)) {
            return null;
        }
        return Files.readAllBytes(image);
    }

    /** 返回超过给定时间且目录名为任务编号的临时目录编号。 */
    public List<Long> findDirectoriesOlderThan(Instant deadline) throws IOException {
        if (!Files.isDirectory(importsDir)) {
            return List.of();
        }
        List<Long> jobIds = new ArrayList<>();
        try (var directories = Files.list(importsDir)) {
            directories.filter(Files::isDirectory).forEach(directory -> {
                String directoryName = directory.getFileName().toString();
                if (!directoryName.matches("\\d+")) {
                    return;
                }
                try {
                    if (Files.getLastModifiedTime(directory).toInstant().isBefore(deadline)) {
                        jobIds.add(Long.parseLong(directoryName));
                    }
                } catch (IOException | NumberFormatException ignored) {
                    // 单个目录不可读不影响其他目录的启动清理。
                }
            });
        }
        return List.copyOf(jobIds);
    }

    /** 删除整个任务目录；任务目录不存在时视为已清理。 */
    public void deleteJobDirectory(Long jobId) throws IOException {
        Path directory = jobDirectory(jobId);
        if (!Files.exists(directory)) {
            return;
        }
        try (var paths = Files.walk(directory)) {
            for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(path);
            }
        }
    }

    /**
     * 上传文件名净化：路径分隔符、Windows 非法字符和逗号替换为下划线，控制字符剔除，
     * 保留文件扩展名所在的末尾内容。
     */
    public static String sanitizeFileName(String raw) {
        if (raw == null || raw.isBlank()) {
            return "file";
        }
        String name = raw.replace('\\', '_').replace('/', '_')
                .replace(',', '_')
                .replaceAll("[\\\\/:*?\"<>|]", "_")
                .replaceAll("[\\p{Cntrl}]", "")
                .trim();
        if (name.isEmpty() || ".".equals(name) || "..".equals(name)) {
            name = "file";
        }
        if (name.length() > MAX_FILE_NAME_LENGTH) {
            return name.substring(name.length() - MAX_FILE_NAME_LENGTH);
        }
        return name;
    }

    private Path imagesDirectory(Long jobId) {
        Path directory = jobDirectory(jobId).resolve("images").normalize();
        if (!directory.startsWith(jobDirectory(jobId))) {
            throw new IllegalArgumentException("导入图片路径无效");
        }
        return directory;
    }

    private Path findImage(Long jobId, int number) throws IOException {
        Path imagesDir = imagesDirectory(jobId);
        Path png = imagesDir.resolve(number + ".png").normalize();
        if (png.startsWith(imagesDir) && Files.isRegularFile(png)) {
            return png;
        }
        if (!Files.isDirectory(imagesDir)) {
            return null;
        }
        try (var files = Files.list(imagesDir)) {
            return files.filter(Files::isRegularFile)
                    .filter(file -> file.getFileName().toString().matches("^" + number + "\\.[a-zA-Z0-9]+$"))
                    .findFirst()
                    .orElse(null);
        }
    }

    /** 素材区图片信息。 */
    public record JobImageInfo(int num, String fileName, String ext) {
    }
}
