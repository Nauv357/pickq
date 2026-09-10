package com.tiku.util;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipException;
import java.util.zip.ZipFile;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

/**
 * 内容包容器（.tiku = zip）读写。
 *
 * v2 容器结构（schemaVersion = 2）：
 * <pre>
 *   xxx-1.0.0.tiku            ← zip
 *   ├── package.json          ← ContentPackageFile 序列化（images 字段不写入；图片在 media/）
 *   └── media/                ← 图片二进制，路径与题干 [图片:name] 引用一致（如 260829/ab12.png）
 * </pre>
 *
 * - 魔数识别：PK\x03\x04 开头 = zip 容器；否则视为 v1 纯 JSON。
 * - 安全解包：条目数 ≤ 4096、单条 ≤ 100MB、总解压 ≤ 512MB（防 zip 炸弹）；
 *   只接受 package.json 与 media/ 前缀条目；路径穿越条目直接拒绝。
 * - 图片参与 checksum 的表示与 v1 相同（base64 map），保证 v1/v2 同内容指纹可比。
 * - {@link #readPackageJson(byte[])} / {@link #readPackageJson(Path)}：发布前"体检"用的只读元数据读取，
 *   只取 package.json、不解压 media（大文件走 ZipFile 随机读），避免为校验元数据而把图片读进内存。
 */
public final class PackageContainer {

    public static final int SCHEMA_V2 = 2;
    public static final String PKG_ENTRY = "package.json";
    public static final String MEDIA_PREFIX = "media/";

    /** 体检（只读元数据）路径的 manifest 相关文案：入口名固定 package.json（官网 package-meta.ts 只认这个名字） */
    private static final String MISSING_MANIFEST = ".tiku 容器内缺少 manifest（package.json）";
    private static final String MANIFEST_TOO_LARGE = ".tiku 容器内 manifest（package.json）过大";

    private static final int MAX_ENTRIES = 4096;
    private static final long MAX_ENTRY_BYTES = 100L * 1024 * 1024;
    private static final long MAX_TOTAL_BYTES = 512L * 1024 * 1024;
    /** package.json 条目上限（与官网 package-meta.ts 的 10MB 口径一致） */
    private static final long MAX_PKG_ENTRY_BYTES = 10L * 1024 * 1024;

    private PackageContainer() {
    }

    /** 魔数识别：PK\x03\x04 开头视为 zip 容器 */
    public static boolean isZipContainer(byte[] data) {
        return data != null && data.length >= 4
                && (data[0] & 0xFF) == 0x50 && (data[1] & 0xFF) == 0x4B
                && (data[2] & 0xFF) == 0x03 && (data[3] & 0xFF) == 0x04;
    }

    /** 魔数识别（文件态，只读前 4 字节：200MB 级 .tiku 判定 zip 不读全文件） */
    public static boolean isZipContainer(Path file) throws IOException {
        try (InputStream in = Files.newInputStream(file)) {
            byte[] head = new byte[4];
            return in.readNBytes(head, 0, 4) == 4 && isZipContainer(head);
        }
    }

    /** 打包 .tiku：package.json 字节 + media 条目（name 形如 260829/ab12.png → media/260829/ab12.png） */
    public static byte[] pack(byte[] packageJson, Map<String, byte[]> media) throws IOException {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        try (ZipOutputStream zos = new ZipOutputStream(bos)) {
            zos.putNextEntry(new ZipEntry(PKG_ENTRY));
            zos.write(packageJson);
            zos.closeEntry();
            if (media != null) {
                // 排序保证打包确定性（同内容同字节 → 同 fileSha256）
                for (Map.Entry<String, byte[]> e : new java.util.TreeMap<>(media).entrySet()) {
                    if (e.getValue() == null || e.getValue().length == 0) {
                        continue;
                    }
                    zos.putNextEntry(new ZipEntry(MEDIA_PREFIX + e.getKey()));
                    zos.write(e.getValue());
                    zos.closeEntry();
                }
            }
        }
        return bos.toByteArray();
    }

    /** 解包 .tiku：返回 package.json 字节与 media map（name → 字节）；非法/超限抛 IllegalArgumentException */
    public static Unpacked unpack(byte[] zipData) throws IOException {
        if (!isZipContainer(zipData)) {
            throw new IllegalArgumentException("文件不是 .tiku 容器（zip 格式）");
        }
        byte[] pkg = null;
        Map<String, byte[]> media = new LinkedHashMap<>();
        long total = 0;
        int count = 0;
        try (ZipInputStream zis = new ZipInputStream(new java.io.ByteArrayInputStream(zipData))) {
            ZipEntry entry;
            while ((entry = zis.getNextEntry()) != null) {
                count++;
                if (count > MAX_ENTRIES) {
                    throw new IllegalArgumentException(".tiku 容器条目过多");
                }
                String name = entry.getName();
                if (entry.isDirectory()) {
                    continue;
                }
                if (name.contains("..") || name.startsWith("/")) {
                    throw new IllegalArgumentException(".tiku 容器含非法路径：" + name);
                }
                if (entry.getSize() > MAX_ENTRY_BYTES) {
                    throw new IllegalArgumentException(".tiku 容器条目过大：" + name);
                }
                byte[] content = readAll(zis, entry.getSize());
                total += content.length;
                if (total > MAX_TOTAL_BYTES) {
                    throw new IllegalArgumentException(".tiku 容器解压后过大");
                }
                if (PKG_ENTRY.equals(name)) {
                    pkg = content;
                } else if (name.startsWith(MEDIA_PREFIX) && name.length() > MEDIA_PREFIX.length()) {
                    media.put(name.substring(MEDIA_PREFIX.length()), content);
                }
                // 其余条目（如 __MACOSX、目录元数据）忽略
            }
        }
        if (pkg == null) {
            throw new IllegalArgumentException(".tiku 容器缺少 package.json");
        }
        return new Unpacked(pkg, media);
    }

    // ==================== 只读元数据（发布前体检：不导入、不解压图片） ====================

    /**
     * 只读 manifest（内存态 .tiku）：顺序扫描 zip，只解出 manifest 条目（package.json），
     * 其余条目（media 图片）读完即弃、不持有字节。
     * 与 {@link #unpack} 的区别：体检/校验只需要元数据，200MB 级内容包不该把图片全部读进内存；
     * 与官网 package-meta.ts 的口径一致：条目数 ≤ 4096、manifest ≤ 10MB、缺条目即拒绝。
     * 注意：这里的条目只被丢弃读取（累计上限防 zip 炸弹），不落盘，故不校验路径穿越。
     * 非法/超限抛 IllegalArgumentException（message 对用户可读）。
     */
    public static String readPackageJson(byte[] zipData) {
        if (!isZipContainer(zipData)) {
            throw new IllegalArgumentException(".tiku 容器不是 zip 格式");
        }
        long discarded = 0;
        int count = 0;
        try (ZipInputStream zis = new ZipInputStream(new ByteArrayInputStream(zipData))) {
            ZipEntry entry;
            while ((entry = zis.getNextEntry()) != null) {
                count++;
                if (count > MAX_ENTRIES) {
                    throw new IllegalArgumentException(".tiku 容器条目过多");
                }
                if (entry.isDirectory()) {
                    continue;
                }
                if (PKG_ENTRY.equals(entry.getName())) {
                    return pkgText(readLimited(zis, MAX_PKG_ENTRY_BYTES));
                }
                discarded += drain(zis, MAX_TOTAL_BYTES - discarded);
            }
        } catch (IOException e) {
            throw new IllegalArgumentException(".tiku 容器解压失败（zip 格式损坏）");
        }
        if (count == 0) {
            // 有 PK 魔数但一个条目都读不出来 = zip 结构损坏（ZipInputStream 对坏头会直接返回 null）
            throw new IllegalArgumentException(".tiku 容器解压失败（zip 格式损坏）");
        }
        throw new IllegalArgumentException(MISSING_MANIFEST);
    }

    /**
     * 只读 manifest（文件态 .tiku，大文件路径）：用 {@link ZipFile} 随机读中央目录，
     * 只解压 manifest（package.json）这一个条目——media 图片完全不参与解压，内存峰值只有 manifest 大小。
     * 非法/超限抛 IllegalArgumentException（message 对用户可读）。
     */
    public static String readPackageJson(Path zipFile) {
        try (ZipFile zf = new ZipFile(zipFile.toFile(), StandardCharsets.UTF_8)) {
            if (zf.size() > MAX_ENTRIES) {
                throw new IllegalArgumentException(".tiku 容器条目过多");
            }
            ZipEntry entry = zf.getEntry(PKG_ENTRY);
            if (entry == null) {
                throw new IllegalArgumentException(MISSING_MANIFEST);
            }
            if (entry.getSize() > MAX_PKG_ENTRY_BYTES) {
                throw new IllegalArgumentException(MANIFEST_TOO_LARGE);
            }
            try (InputStream in = zf.getInputStream(entry)) {
                return pkgText(readLimited(in, MAX_PKG_ENTRY_BYTES));
            }
        } catch (ZipException e) {
            throw new IllegalArgumentException(".tiku 容器解压失败（zip 格式损坏）");
        } catch (IOException e) {
            throw new IllegalArgumentException(".tiku 容器读取失败：" + e.getMessage());
        }
    }

    /** 限长读取 manifest（超过 limit 抛 IllegalArgumentException，口径同官网：manifest 过大） */
    private static byte[] readLimited(InputStream in, long limit) throws IOException {
        ByteArrayOutputStream bos = new ByteArrayOutputStream(8192);
        byte[] buf = new byte[8192];
        int n;
        while ((n = in.read(buf)) != -1) {
            if (bos.size() + n > limit) {
                throw new IllegalArgumentException(MANIFEST_TOO_LARGE);
            }
            bos.write(buf, 0, n);
        }
        return bos.toByteArray();
    }

    /** 丢弃读取一个条目并返回读到的字节数（超过 limit 抛异常，防小体积 zip 解压炸弹） */
    private static long drain(InputStream in, long limit) throws IOException {
        byte[] buf = new byte[8192];
        long read = 0;
        int n;
        while ((n = in.read(buf)) != -1) {
            read += n;
            if (read > limit) {
                throw new IllegalArgumentException(".tiku 容器解压后过大");
            }
        }
        return read;
    }

    private static byte[] readAll(ZipInputStream zis, long declaredSize) throws IOException {
        ByteArrayOutputStream bos = new ByteArrayOutputStream(
                declaredSize > 0 && declaredSize < Integer.MAX_VALUE ? (int) declaredSize : 8192);
        byte[] buf = new byte[8192];
        int n;
        while ((n = zis.read(buf)) != -1) {
            bos.write(buf, 0, n);
        }
        return bos.toByteArray();
    }

    /** package.json 字符串 */
    public static String pkgText(byte[] pkg) {
        return new String(pkg, StandardCharsets.UTF_8);
    }

    public record Unpacked(byte[] packageJson, Map<String, byte[]> media) {
        public String packageText() {
            return pkgText(packageJson);
        }
    }
}
