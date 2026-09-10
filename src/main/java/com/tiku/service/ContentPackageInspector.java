package com.tiku.service;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import com.tiku.util.PackageContainer;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Pattern;

/**
 * 内容包「体检」：只解析元数据，不导入、不落库、不转发。
 *
 * 用途：桌面端「发布作品」在把 200MB 内容包跨境上传给题库广场之前，先在本地判定这个文件
 * 能不能发布；同时把解析出的元数据（标题/题数/版本/标识）回显给用户确认。
 *
 * <b>两种内容包格式都支持，且按魔数判定、不看扩展名</b>（用户可能选错 .tiku / .json 后缀）：
 * <pre>
 *   v2 .tiku  = zip 容器（PK\x03\x04 魔数）：manifest 条目 = package.json（media/ 下是图片二进制）
 *   v1 .json  = 纯 JSON 文件（无 PK 魔数）：schemaVersion = 1，图片以 base64 内联在 images 字段
 * </pre>
 * 两种格式的顶层字段名完全一致（v2 的 manifest 就是同一份 JSON 结构装进 zip，见
 * ContentPackageService.exportTikuPackage / exportContentPackage），所以这里用同一套提取与校验逻辑，
 * 计数口径也一致：questionsCount = questions 数组长度、materialsCount = materials 数组长度；
 * schemaVersion 如实回传（.tiku 容器内一般为 2，纯 JSON 一般为 1）。
 *
 * 严格校验口径与官网 <code>web/server/utils/package-meta.ts</code> + <code>upload.post.ts</code> 完全一致，
 * 避免"本地通过、服务端拒绝"：
 * <pre>
 *   1. 容器/编码：PK\x03\x04 = v2 .tiku(zip：package.json + media/)；否则按 v1 纯 JSON 解析
 *   2. .tiku：条目数 ≤ 4096、必须有 manifest（package.json）、manifest ≤ 10MB、manifest 必须是合法 JSON
 *   3. schemaVersion 必须为数字 1 或 2（纯 JSON 必须是 v1 结构：带 schemaVersion）
 *   4. packageKey / version / title 必须为非空字符串，且 packageKey/version 匹配 ^[\p{L}\p{N}._-]{1,100}$
 *   5. parentKey 若存在必须匹配同一正则
 *   6. questions 必须是非空数组（题数为零的空题库无发布意义）
 *   7. description ≤ 2000 / source ≤ 500（与官网落库截断一致，预览值与最终入库值相同）
 * </pre>
 *
 * 内存策略：只读元数据，不物化题目内容与图片。
 * - v1 JSON 用 Jackson 流式解析：只取顶层标量字段，questions/materials 只数元素个数（元素内部整体跳过），
 *   200MB JSON 不进堆；
 * - .tiku 走 {@link PackageContainer#readPackageJson(byte[])} / {@link PackageContainer#readPackageJson(Path)}：
 *   只解出 manifest（≤10MB），media 图片条目完全不参与解压。
 *
 * 校验失败统一抛 IllegalArgumentException，message 对用户可读、可直接展示在前端弹窗。
 */
public final class ContentPackageInspector {

    /** 与官网 package-meta / upload.post.ts 的 KEY_RE 同口径：字母/数字/点/下划线/连字符，1-100 */
    private static final Pattern KEY_RE = Pattern.compile("^[\\p{L}\\p{N}._-]{1,100}$");

    /** 官网 upload.post.ts 对 description / source 的截断长度 */
    private static final int MAX_DESCRIPTION = 2000;
    private static final int MAX_SOURCE = 500;

    private static final JsonFactory JSON_FACTORY = new JsonFactory();

    /** 既不是 .tiku 容器也不是合法 v1 JSON */
    private static final String NOT_A_PACKAGE = "题库文件解析失败：不是有效的题库文件（既不是 .tiku 也不是 v1 JSON）";
    /** .tiku 容器内的 manifest 不是合法 JSON（或不是对象结构） */
    private static final String BROKEN_MANIFEST = "题库文件解析失败：.tiku 容器内 manifest（package.json）不是合法 JSON";
    /** 纯 JSON 文件缺少 v1 结构的必备字段 */
    private static final String NOT_V1_SCHEMA = "该 .json 不是 v1 题库文件（缺少 schemaVersion=1）";
    private static final String NOT_V1_QUESTIONS = "该 .json 不是 v1 题库文件（缺少 questions）";

    /** 内容包格式（按魔数判定，不看扩展名） */
    private enum Format {
        /** v2：.tiku zip 容器（package.json manifest + media/ 图片） */
        TIKU_CONTAINER,
        /** v1：纯 JSON 文件 */
        PLAIN_JSON
    }

    private ContentPackageInspector() {
    }

    /**
     * 体检结果（.tiku 与 .json 两种格式字段完全一致，能取到的字段都给、取不到的为 null）：
     * packageKey / version / title / description / source / schemaVersion（1 或 2，如实反映）/
     * questionsCount（questions 数组长度）/ materialsCount（materials 数组长度）。
     */
    public record Inspection(String packageKey, String version, String title, String description,
                             String source, Integer schemaVersion, int questionsCount, int materialsCount) {
    }

    /**
     * 体检内存态内容包（≤ 暂存阈值的小文件），.tiku 与 .json 都支持（按魔数判定）。
     * 失败抛 IllegalArgumentException（message 可直接展示）。
     */
    public static Inspection inspect(byte[] data) {
        if (data == null || data.length == 0) {
            throw new IllegalArgumentException("请选择要上传的内容包文件（.tiku 或 .json）");
        }
        if (PackageContainer.isZipContainer(data)) {
            return validate(readContainerMeta(() -> PackageContainer.readPackageJson(data)), Format.TIKU_CONTAINER);
        }
        return validate(parseMeta(new ByteArrayInputStream(data), NOT_A_PACKAGE), Format.PLAIN_JSON);
    }

    /**
     * 体检文件态内容包（大文件，已被调用方落到临时文件），.tiku 与 .json 都支持（按魔数判定）。
     * zip 走随机读中央目录，只解压 manifest；v1 JSON 走流式解析。
     */
    public static Inspection inspect(Path file) {
        try {
            if (PackageContainer.isZipContainer(file)) {
                return validate(readContainerMeta(() -> PackageContainer.readPackageJson(file)), Format.TIKU_CONTAINER);
            }
            try (InputStream in = Files.newInputStream(file)) {
                return validate(parseMeta(in, NOT_A_PACKAGE), Format.PLAIN_JSON);
            }
        } catch (IOException e) {
            throw new IllegalArgumentException("题库文件解析失败：" + e.getMessage());
        }
    }

    // ==================== 元数据提取 ====================

    /** 顶层元数据（只含校验所需字段；题目/材料/detail 内容一律不物化） */
    private record Meta(Integer schemaVersion, String packageKey, String version, String title,
                        String description, String source, String parentKey,
                        int questionsCount, int materialsCount) {
    }

    @FunctionalInterface
    private interface PackageJsonSupplier {
        String get();
    }

    /**
     * 元数据结构问题（顶层不是对象 / JSON 截断 / 末尾多余内容）。
     * 用独立异常承载，由入口按"是不是 .tiku 容器"翻译成对应的用户可读文案
     * （官网 package-meta.ts 对 v1 JSON 与容器内 package.json 分别给不同 message）。
     */
    private static final class MetaFormatException extends RuntimeException {
        MetaFormatException() {
            super(null, null, false, false);
        }
    }

    /** 解出 .tiku 容器内 manifest 并解析（容器错误消息加统一前缀，便于前端归类展示） */
    private static Meta readContainerMeta(PackageJsonSupplier supplier) {
        String json;
        try {
            json = supplier.get();
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("题库文件解析失败：" + e.getMessage());
        }
        return parseMeta(json, BROKEN_MANIFEST);
    }

    private static Meta parseMeta(String json, String failureMessage) {
        try (JsonParser parser = JSON_FACTORY.createParser(json)) {
            return parseObject(parser);
        } catch (IOException | MetaFormatException e) {
            throw new IllegalArgumentException(failureMessage);
        }
    }

    private static Meta parseMeta(InputStream in, String failureMessage) {
        try (JsonParser parser = JSON_FACTORY.createParser(in)) {
            return parseObject(parser);
        } catch (IOException | MetaFormatException e) {
            throw new IllegalArgumentException(failureMessage);
        }
    }

    /**
     * 流式读取顶层对象：标量字段按 JSON 类型取值（与官网 typeof 判定一致），
     * questions/materials 只数元素个数。
     */
    private static Meta parseObject(JsonParser p) throws IOException {
        if (p.nextToken() != JsonToken.START_OBJECT) {
            throw new MetaFormatException();
        }
        Integer schemaVersion = null;
        String packageKey = null;
        String version = null;
        String title = null;
        String description = null;
        String source = null;
        String parentKey = null;
        int questionsCount = 0;
        int materialsCount = 0;
        while (p.nextToken() == JsonToken.FIELD_NAME) {
            String name = p.currentName();
            JsonToken value = p.nextToken();
            if (value == null) {
                throw new MetaFormatException();
            }
            switch (name) {
                // 官网口径：schemaVersion 必须是数字 1 或 2（字符串 "1" 会被服务端拒绝，本地同样判非法）
                case "schemaVersion" -> schemaVersion = scalarInt(value, p);
                case "packageKey" -> packageKey = scalarString(value, p);
                case "version" -> version = scalarString(value, p);
                case "title" -> title = scalarString(value, p);
                case "description" -> description = scalarString(value, p);
                case "source" -> source = scalarString(value, p);
                case "parentKey" -> parentKey = scalarString(value, p);
                // 非数组（null / 字符串 / 对象）与官网 Array.isArray 判定一致 → 计 0 → 后续按"没有题目"拒绝
                case "questions" -> questionsCount = value == JsonToken.START_ARRAY ? countArray(p) : skipValue(p);
                case "materials" -> materialsCount = value == JsonToken.START_ARRAY ? countArray(p) : skipValue(p);
                default -> p.skipChildren();
            }
        }
        // 根对象之后不允许再有内容（官网走 JSON.parse 全量解析，末尾多余内容一律报错）
        if (p.nextToken() != null) {
            throw new MetaFormatException();
        }
        return new Meta(schemaVersion, packageKey, version, title, description, source, parentKey,
                questionsCount, materialsCount);
    }

    /**
     * 数字 token → schemaVersion（只认数字类型的 1 / 2）：
     * - 返回 null = 该字段根本没提供 / 不是数字（字符串 "1" 官网也会拒绝）
     * - 返回 0 = 提供了数字但取值不是 1/2（含超 int 范围、1.0/2.0 以外的浮点）→ 走"需为 1 或 2"文案
     * 用 token 文本做转换，避免超大数字直取 int 抛异常。
     */
    private static Integer scalarInt(JsonToken token, JsonParser p) throws IOException {
        if (token == JsonToken.VALUE_NUMBER_INT) {
            try {
                return Integer.valueOf(p.getText());
            } catch (NumberFormatException e) {
                return 0;
            }
        }
        if (token == JsonToken.VALUE_NUMBER_FLOAT) {
            // 官网 JS 里 1.0 === 1，故 1.0/2.0 同样视为合法；其余浮点按非法值处理
            double value = p.getDoubleValue();
            if (value == 1.0d) {
                return 1;
            }
            return value == 2.0d ? 2 : 0;
        }
        p.skipChildren();
        return null;
    }

    /** 字符串 token → 值与官网 typeof === 'string' 一致；其余类型（数字/null/对象）视为未提供 */
    private static String scalarString(JsonToken token, JsonParser p) throws IOException {
        if (token != JsonToken.VALUE_STRING) {
            p.skipChildren();
            return null;
        }
        return p.getText();
    }

    /** 跳过非数组值并返回 0 */
    private static int skipValue(JsonParser p) throws IOException {
        p.skipChildren();
        return 0;
    }

    /** 数数组元素个数（元素内部整体跳过，不物化题干/图片） */
    private static int countArray(JsonParser p) throws IOException {
        int count = 0;
        while (true) {
            JsonToken token = p.nextToken();
            if (token == null) {
                throw new MetaFormatException();
            }
            if (token == JsonToken.END_ARRAY) {
                return count;
            }
            count++;
            p.skipChildren();
        }
    }

    // ==================== 严格校验（与官网 package-meta.ts / upload.post.ts 同口径） ====================

    /**
     * 校验并组装体检结果；两种格式的字段与计数口径一致，只有"格式相关"的报错文案不同：
     * - .tiku 容器：沿用官网 message（schemaVersion 需为 1 或 2 / 题库文件中没有题目，无法发布）
     * - 纯 JSON：v1 结构必备字段缺失时给 .json 专属文案，便于用户分辨自己选错了文件
     */
    private static Inspection validate(Meta meta, Format format) {
        boolean container = format == Format.TIKU_CONTAINER;
        Integer schemaVersion = meta.schemaVersion();
        if (schemaVersion == null) {
            throw new IllegalArgumentException(container
                    ? "题库文件中 schemaVersion 需为 1 或 2"
                    : NOT_V1_SCHEMA);
        }
        if (schemaVersion != 1 && schemaVersion != PackageContainer.SCHEMA_V2) {
            throw new IllegalArgumentException("题库文件中 schemaVersion 需为 1 或 2");
        }
        String packageKey = trim(meta.packageKey());
        if (packageKey.isEmpty()) {
            throw new IllegalArgumentException("题库文件缺少 packageKey");
        }
        if (!KEY_RE.matcher(packageKey).matches()) {
            throw new IllegalArgumentException("题库文件 packageKey 不合法（只能是字母、数字、点、下划线、连字符，1-100 个字符）");
        }
        String version = trim(meta.version());
        if (version.isEmpty()) {
            throw new IllegalArgumentException("题库文件缺少版本号（version）");
        }
        if (!KEY_RE.matcher(version).matches()) {
            throw new IllegalArgumentException("题库文件 version 不合法（只能是字母、数字、点、下划线、连字符，1-100 个字符）");
        }
        String title = trim(meta.title());
        if (title.isEmpty()) {
            throw new IllegalArgumentException("题库文件缺少标题");
        }
        String parentKey = trim(meta.parentKey());
        if (!parentKey.isEmpty() && !KEY_RE.matcher(parentKey).matches()) {
            throw new IllegalArgumentException("题库文件 parentKey 不合法（只能是字母、数字、点、下划线、连字符，1-100 个字符）");
        }
        if (meta.questionsCount() == 0) {
            throw new IllegalArgumentException(container
                    ? "题库文件中没有题目，无法发布"
                    : NOT_V1_QUESTIONS);
        }
        String description = trim(meta.description());
        String source = trim(meta.source());
        return new Inspection(packageKey, version, title,
                description.isEmpty() ? null : slice(description, MAX_DESCRIPTION),
                source.isEmpty() ? null : slice(source, MAX_SOURCE),
                schemaVersion, meta.questionsCount(), meta.materialsCount());
    }

    private static String trim(String s) {
        return s == null ? "" : s.trim();
    }

    private static String slice(String s, int max) {
        return s.length() > max ? s.substring(0, max) : s;
    }
}
