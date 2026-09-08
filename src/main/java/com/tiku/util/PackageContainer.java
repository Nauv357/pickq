package com.tiku.util;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.zip.ZipEntry;
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
 */
public final class PackageContainer {

    public static final int SCHEMA_V2 = 2;
    public static final String PKG_ENTRY = "package.json";
    public static final String MEDIA_PREFIX = "media/";

    private static final int MAX_ENTRIES = 4096;
    private static final long MAX_ENTRY_BYTES = 100L * 1024 * 1024;
    private static final long MAX_TOTAL_BYTES = 512L * 1024 * 1024;

    private PackageContainer() {
    }

    /** 魔数识别：PK\x03\x04 开头视为 zip 容器 */
    public static boolean isZipContainer(byte[] data) {
        return data != null && data.length >= 4
                && (data[0] & 0xFF) == 0x50 && (data[1] & 0xFF) == 0x4B
                && (data[2] & 0xFF) == 0x03 && (data[3] & 0xFF) == 0x04;
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
