package com.tiku.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tiku.dto.DeleteExportRecordResult;
import com.tiku.dto.ExportRecordResponse;
import com.tiku.dto.ExportRequest;
import com.tiku.dto.ExportToDirRequest;
import com.tiku.dto.NextVersionResponse;
import com.tiku.mapper.ExportRecordMapper;
import com.tiku.mapper.MaterialMapper;
import com.tiku.mapper.QuestionBankMapper;
import com.tiku.mapper.QuestionMapper;
import com.tiku.mapper.StudyRecordMapper;
import com.tiku.model.ExportRecord;
import com.tiku.model.Question;
import com.tiku.model.QuestionBank;
import com.tiku.model.enums.QuestionType;
import com.tiku.util.PackageContainer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.NoSuchElementException;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 本地发布中心后端单元测试（不加载 Spring 上下文，数据目录一律用 @TempDir，
 * 绝不触碰用户真实的 ~/.tiku）：版本递增规则、导出偏好记忆、导出记录列表/标记/删除、
 * 导出到目录的文件命名与落盘。
 */
class ExportCenterServiceTest {

    @TempDir
    Path tempDir;

    // ==================== 版本递增规则 ====================

    @Test
    void suggestNextVersionIncrementsPatchNumber() {
        assertEquals("1.0.1", ExportRecordService.suggestNextVersion("1.0.0"));
        assertEquals("1.2.1", ExportRecordService.suggestNextVersion("1.2"), "不足 3 段补 0 再递增补丁号");
        assertEquals("1.0.1", ExportRecordService.suggestNextVersion("1"));
        assertEquals("1.0.10", ExportRecordService.suggestNextVersion("1.0.09"));
        assertEquals("1.3.1", ExportRecordService.suggestNextVersion(" 1.3 "));
        assertEquals("2.5.9", ExportRecordService.suggestNextVersion("2.5.8"));
    }

    @Test
    void suggestNextVersionFallsBackWhenUnparsable() {
        assertEquals("1.0.0", ExportRecordService.suggestNextVersion(null));
        assertEquals("1.0.0", ExportRecordService.suggestNextVersion(""));
        assertEquals("1.0.0", ExportRecordService.suggestNextVersion("v1.0"));
        assertEquals("1.0.0", ExportRecordService.suggestNextVersion("1.0.0-beta"));
        assertEquals("1.0.0", ExportRecordService.suggestNextVersion("1.0.0.0.0"));
        assertEquals("1.0.0", ExportRecordService.suggestNextVersion("1.2.3.4"));
    }

    // ==================== 导出偏好（目录记忆） ====================

    @Test
    void prefsRememberResolveAndClear() throws Exception {
        ExportPrefsService prefs = new ExportPrefsService(tempDir.toString(), new ObjectMapper());
        assertNull(prefs.lastDir(), "首次使用无记忆");
        assertTrue(prefs.current().dirExists().isEmpty(), "无记忆时不含 lastDir 键");
        assertNotNull(prefs.current().defaultDir(), "默认目录始终可给出");

        Path dir = tempDir.resolve("导出目录");
        prefs.remember(dir.toString());
        assertEquals(dir.toString(), prefs.lastDir());
        assertEquals(Boolean.TRUE, prefs.current().dirExists().get("lastDir"));
        assertTrue(Files.isDirectory(dir), "记忆时顺带创建目录");

        // 新建服务实例读同一份配置 = 重启后记忆仍在
        assertEquals(dir.toString(), new ExportPrefsService(tempDir.toString(), new ObjectMapper()).lastDir());

        // 目录优先级：显式 dir ＞ 记忆 ＞ 默认
        Path other = tempDir.resolve("另一个目录");
        assertEquals(other, prefs.resolveTargetDir(other.toString()));
        assertEquals(dir, prefs.resolveTargetDir(null));
        assertTrue(Files.isDirectory(other));

        // 记忆目录被手工删掉 → dirExists=false（前端据此提示目录已不存在）
        Files.delete(dir);
        assertEquals(Boolean.FALSE, prefs.current().dirExists().get("lastDir"));
        // 解析目标目录时会被重新创建
        assertEquals(dir, prefs.resolveTargetDir(null));
        assertTrue(Files.isDirectory(dir));

        // 留空 = 清除记忆
        prefs.remember(" ");
        assertNull(prefs.lastDir());

        // 相对路径拒绝（不同启动目录下含义不同，导出位置不可预测）
        assertEquals("导出目录必须是绝对路径：exports",
                assertThrows(IllegalArgumentException.class, () -> prefs.remember("exports")).getMessage());
    }

    // ==================== 导出记录 ====================

    @Test
    void listMarksFileExistsPerRecord() throws Exception {
        ExportRecordMapper mapper = mock(ExportRecordMapper.class);
        ExportRecordService service = new ExportRecordService(mapper, mock(QuestionBankMapper.class));

        Path alive = Files.writeString(tempDir.resolve("a-1.0.0.tiku"), "x");
        ExportRecord existing = record(1L, alive, 2L, "1.0.0");
        ExportRecord missing = record(2L, tempDir.resolve("gone.tiku"), 2L, "1.0.1");
        when(mapper.selectList(any())).thenReturn(List.of(existing, missing));

        List<ExportRecordResponse> list = service.list();
        assertEquals(2, list.size());
        assertTrue(list.get(0).fileExists());
        assertFalse(list.get(1).fileExists(), "磁盘文件已丢失 → fileExists=false");
        assertFalse(list.get(0).published());
    }

    @Test
    void markPublishedFallsBackToExportedVersion() {
        ExportRecordMapper mapper = mock(ExportRecordMapper.class);
        ExportRecordService service = new ExportRecordService(mapper, mock(QuestionBankMapper.class));
        ExportRecord draft = record(1L, tempDir.resolve("a.tiku"), 2L, "1.0.2");
        when(mapper.selectById(1L)).thenReturn(draft);

        ExportRecordResponse marked = service.markPublished(1L, null);
        assertTrue(marked.published());
        assertEquals("1.0.2", marked.publishedVersion(), "version 留空用导出时的版本");
        verify(mapper).updateById(draft);

        assertEquals("2.0.0", service.markPublished(1L, " 2.0.0 ").publishedVersion());

        // 记录里没有版本可用 → 明确报错（否则版本建议失去依据）
        when(mapper.selectById(3L)).thenReturn(record(3L, tempDir.resolve("b.tiku"), 2L, null));
        assertEquals("请提供发布版本号（version）",
                assertThrows(IllegalArgumentException.class, () -> service.markPublished(3L, " ")).getMessage());

        assertThrows(NoSuchElementException.class, () -> service.markPublished(99L, "1.0.0"));
    }

    @Test
    void deleteRemovesRecordAndOptionallyTheFile() throws Exception {
        ExportRecordMapper mapper = mock(ExportRecordMapper.class);
        ExportRecordService service = new ExportRecordService(mapper, mock(QuestionBankMapper.class));
        Path file = Files.writeString(tempDir.resolve("a.tiku"), "x");
        when(mapper.selectById(1L)).thenReturn(record(1L, file, 2L, "1.0.0"));
        when(mapper.selectById(2L)).thenReturn(record(2L, tempDir.resolve("gone.tiku"), 2L, "1.0.0"));

        DeleteExportRecordResult result = service.delete(1L, true);
        assertEquals(1L, result.id());
        assertTrue(result.fileDeleted());
        assertFalse(Files.exists(file));
        verify(mapper).deleteById(1L);

        // 文件已不存在也算删除成功
        DeleteExportRecordResult again = service.delete(2L, true);
        assertFalse(again.fileDeleted());
        verify(mapper).deleteById(2L);

        // deleteFile=false 时磁盘文件保持不动
        Path keep = Files.writeString(tempDir.resolve("keep.tiku"), "x");
        when(mapper.selectById(4L)).thenReturn(record(4L, keep, 2L, "1.0.0"));
        assertFalse(service.delete(4L, false).fileDeleted());
        assertTrue(Files.exists(keep));

        assertThrows(NoSuchElementException.class, () -> service.delete(99L, false));
        assertEquals("缺少导出记录 ID",
                assertThrows(IllegalArgumentException.class, () -> service.delete(null, false)).getMessage());
    }

    @Test
    void nextVersionUsesPublishedRecordThenBankVersion() {
        ExportRecordMapper mapper = mock(ExportRecordMapper.class);
        QuestionBankMapper bankMapper = mock(QuestionBankMapper.class);
        ExportRecordService service = new ExportRecordService(mapper, bankMapper);

        ExportRecord published = record(5L, tempDir.resolve("p.tiku"), 2L, "1.0.0");
        published.setPublished(true);
        published.setPublishedVersion("1.0.0");
        when(mapper.selectOne(any())).thenReturn(published);
        NextVersionResponse fromRecord = service.nextVersion(2L);
        assertEquals("1.0.1", fromRecord.suggested());
        assertEquals("1.0.0", fromRecord.lastPublished());

        // 没有已发布记录 → 题库当前版本（自建题库为 null → 1.0.0）
        when(mapper.selectOne(any())).thenReturn(null);
        QuestionBank bank = new QuestionBank();
        bank.setVersion("0.9");
        when(bankMapper.selectById(2L)).thenReturn(bank);
        NextVersionResponse fromBank = service.nextVersion(2L);
        assertEquals("0.9", fromBank.suggested());
        assertNull(fromBank.lastPublished());

        when(bankMapper.selectById(8L)).thenReturn(null);
        assertThrows(NoSuchElementException.class, () -> service.nextVersion(8L));
        assertEquals("缺少题库 ID（bankId）",
                assertThrows(IllegalArgumentException.class, () -> service.nextVersion(null)).getMessage());
    }

    // ==================== 导出到目录 ====================

    @Test
    void exportToDirectoryWritesContainerAndRecordsIt() throws Exception {
        QuestionBankMapper bankMapper = mock(QuestionBankMapper.class);
        ContentPackageService contentPackageService = mock(ContentPackageService.class);
        ExportRecordMapper mapper = mock(ExportRecordMapper.class);
        ExportRecordService recordService = new ExportRecordService(mapper, mock(QuestionBankMapper.class));
        ExportPrefsService prefs = new ExportPrefsService(tempDir.toString(), new ObjectMapper());
        LocalExportService service = new LocalExportService(bankMapper, contentPackageService, recordService, prefs);

        QuestionBank bank = new QuestionBank();
        bank.setId(9L);
        bank.setName("我的题库:第一套?");
        when(bankMapper.selectById(9L)).thenReturn(bank);

        byte[] tiku = PackageContainer.pack(
                "{\"schemaVersion\":2,\"packageKey\":\"k\",\"version\":\"1.0.1\",\"title\":\"t\",\"questions\":[{}]}"
                        .getBytes(StandardCharsets.UTF_8), null);
        when(contentPackageService.exportTikuPackageWithMeta(eq(9L), any(), eq("1.0.1")))
                .thenReturn(new ContentPackageService.TikuExport(tiku, "k", "1.0.1", "t", 1));
        // 模拟自增主键回填
        when(mapper.insert(any(ExportRecord.class))).thenAnswer(invocation -> {
            invocation.getArgument(0, ExportRecord.class).setId(77L);
            return 1;
        });

        Path out = tempDir.resolve("out");
        ExportRecordResponse response = service.exportToDirectory(new ExportToDirRequest(9L, "1.0.1", out.toString()));

        // 打包走 UPGRADE（作者迭代自己的作品，packageKey 保持稳定），版本号以请求的版本覆盖
        verify(contentPackageService).exportTikuPackageWithMeta(eq(9L),
                argThat(request -> "UPGRADE".equals(request.mode())), eq("1.0.1"));

        assertEquals(77L, response.id());
        assertEquals("我的题库_第一套_-1.0.1.tiku", response.fileName(), "Windows 非法字符已清理");
        assertEquals(out.resolve("我的题库_第一套_-1.0.1.tiku").toString(), response.filePath());
        assertArrayEquals(tiku, Files.readAllBytes(Path.of(response.filePath())));
        assertEquals(tiku.length, response.sizeBytes());
        assertEquals("1.0.1", response.version());
        assertEquals("k", response.packageKey());
        assertEquals("我的题库:第一套?", response.bankName());
        assertTrue(response.fileExists());
        // 本次目录记为"上次目录"（下次不传 dir 直接用它）
        assertEquals(out.toString(), prefs.lastDir());
        // 同名重导出 = 覆盖，不报错
        service.exportToDirectory(new ExportToDirRequest(9L, "1.0.1", out.toString()));
        try (var files = Files.list(out)) {
            assertEquals(1, files.count());
        }
    }

    @Test
    void exportToDirectoryValidatesInput() {
        QuestionBankMapper bankMapper = mock(QuestionBankMapper.class);
        ContentPackageService contentPackageService = mock(ContentPackageService.class);
        ExportRecordMapper mapper = mock(ExportRecordMapper.class);
        ExportRecordService recordService = new ExportRecordService(mapper, mock(QuestionBankMapper.class));
        ExportPrefsService prefs = new ExportPrefsService(tempDir.toString(), new ObjectMapper());
        LocalExportService service = new LocalExportService(bankMapper, contentPackageService, recordService, prefs);

        assertEquals("缺少题库 ID（bankId）",
                assertThrows(IllegalArgumentException.class, () -> service.exportToDirectory(null)).getMessage());
        assertEquals("缺少题库 ID（bankId）", assertThrows(IllegalArgumentException.class,
                () -> service.exportToDirectory(new ExportToDirRequest(null, null, null))).getMessage());

        when(bankMapper.selectById(9L)).thenReturn(null);
        assertEquals("题库不存在：9", assertThrows(NoSuchElementException.class,
                () -> service.exportToDirectory(new ExportToDirRequest(9L, null, tempDir.toString()))).getMessage());

        // 版本号口径与发布端体检一致：非法字符在导出时就拒绝（否则文件写完才被官网拒收）
        QuestionBank bank = new QuestionBank();
        bank.setId(9L);
        bank.setName("库");
        when(bankMapper.selectById(9L)).thenReturn(bank);
        assertEquals("版本号不合法（只能是字母、数字、点、下划线、连字符，1-100 个字符）：1.0 正式",
                assertThrows(IllegalArgumentException.class, () -> service.exportToDirectory(
                        new ExportToDirRequest(9L, "1.0 正式", tempDir.toString()))).getMessage());
    }

    // ==================== 容器内 version 覆盖（打包前改写，不破坏其它字段） ====================

    @Test
    void tikuExportOverridesVersionAndKeepsIdentity() {
        QuestionBankMapper bankMapper = mock(QuestionBankMapper.class);
        QuestionMapper questionMapper = mock(QuestionMapper.class);
        // 注册 JSR-310 模块（生产环境的 Spring ObjectMapper 自带；此处用于序列化 createdAt）
        ContentPackageService service = new ContentPackageService(bankMapper, questionMapper,
                mock(MaterialMapper.class), mock(StudyRecordMapper.class),
                mock(ImageStorageService.class), new ObjectMapper().findAndRegisterModules());

        QuestionBank bank = new QuestionBank();
        bank.setId(1L);
        bank.setName("库");
        bank.setPackageKey("key-1");
        bank.setVersion("1.0.0");
        // 与当前内容指纹不一致 → 内容已修改（UPGRADE 迭代）
        bank.setChecksum("stale-checksum");
        when(bankMapper.selectById(1L)).thenReturn(bank);

        Question question = new Question();
        question.setId(10L);
        question.setBankId(1L);
        question.setExternalId("q1");
        question.setQuestionType(QuestionType.SINGLE);
        question.setContent("题干");
        question.setScore(1.0);
        when(questionMapper.selectList(any())).thenReturn(List.of(question));

        ContentPackageService.TikuExport export = service.exportTikuPackageWithMeta(1L,
                new ExportRequest(null, null, "UPGRADE", null, null, null, null), "1.0.1");

        assertEquals("1.0.1", export.version());
        assertEquals("key-1", export.packageKey(), "UPGRADE 沿用身份：改写版本号不影响 packageKey");
        assertEquals(1, export.questionsCount());

        // 用发布端同一套体检解析导出结果：改写过版本号的文件依然是可发布的 v2 容器
        ContentPackageInspector.Inspection inspection = ContentPackageInspector.inspect(export.bytes());
        assertEquals("key-1", inspection.packageKey());
        assertEquals("1.0.1", inspection.version());
        assertEquals(2, inspection.schemaVersion());
        assertEquals(1, inspection.questionsCount());

        // 不传 override = 保持导出决策出的版本（既有 /api/banks/{id}/export-tiku 行为不变）
        ContentPackageService.TikuExport keep = service.exportTikuPackageWithMeta(1L,
                new ExportRequest(null, null, "UPGRADE", null, null, null, null), null);
        assertEquals("1.0.0", ContentPackageInspector.inspect(keep.bytes()).version());
    }

    /**
     * 免登录（离线）路径：导出 + 读记录全过程只用到本地文件与本地库，
     * 无需任何登录态——测试里根本没有 CenterAuthStore/token，数据目录里也不会出现 center-auth.json。
     */
    @Test
    void exportAndListWorkWithoutAnyLoginToken() throws Exception {
        QuestionBankMapper bankMapper = mock(QuestionBankMapper.class);
        ContentPackageService contentPackageService = mock(ContentPackageService.class);
        ExportRecordMapper mapper = mock(ExportRecordMapper.class);
        ExportPrefsService prefs = new ExportPrefsService(tempDir.toString(), new ObjectMapper());
        ExportRecordService recordService = new ExportRecordService(mapper, mock(QuestionBankMapper.class));
        LocalExportService service = new LocalExportService(bankMapper, contentPackageService, recordService, prefs);

        QuestionBank bank = new QuestionBank();
        bank.setId(9L);
        bank.setName("离线题库");
        when(bankMapper.selectById(9L)).thenReturn(bank);

        byte[] tiku = PackageContainer.pack(
                "{\"schemaVersion\":2,\"packageKey\":\"k\",\"version\":\"1.0.0\",\"title\":\"t\",\"questions\":[{}]}"
                        .getBytes(StandardCharsets.UTF_8), null);
        when(contentPackageService.exportTikuPackageWithMeta(eq(9L), any(), eq(null)))
                .thenReturn(new ContentPackageService.TikuExport(tiku, "k", "1.0.0", "t", 1));

        // 内存替身 DB：insert 回填自增 id 并留存，list 返回存量
        List<ExportRecord> stored = new ArrayList<>();
        when(mapper.insert(any(ExportRecord.class))).thenAnswer(invocation -> {
            ExportRecord row = invocation.getArgument(0, ExportRecord.class);
            row.setId((long) stored.size() + 1);
            stored.add(row);
            return 1;
        });
        when(mapper.selectList(any())).thenAnswer(invocation -> List.copyOf(stored));

        // 导出（不传版本 = 沿用题库版本；不传 dir = 后端默认目录/记忆）
        Path out = tempDir.resolve("offline-out");
        ExportRecordResponse exported = service.exportToDirectory(new ExportToDirRequest(9L, null, out.toString()));
        assertTrue(Files.exists(Path.of(exported.filePath())), "离线导出照样写盘");
        assertArrayEquals(tiku, Files.readAllBytes(Path.of(exported.filePath())));

        // 读记录（离线列表）
        List<ExportRecordResponse> list = recordService.list();
        assertEquals(1, list.size());
        assertEquals("离线题库-1.0.0.tiku", list.get(0).fileName());
        assertTrue(list.get(0).fileExists());
        assertFalse(list.get(0).published(), "未发布 = 本地记录里的正常状态");

        // 全程没有任何登录态参与：数据目录里不该出现广场会话文件
        assertFalse(Files.exists(tempDir.resolve("center-auth.json")), "本地导出路径不读也不写登录 token");
    }

    @Test
    void sanitizeFileNameCleansWindowsIllegalChars() {        assertEquals("a_b_c_d_e_f_g_h_i_j", LocalExportService.sanitizeFileName("a\\b/c:d*e?f\"g<h>i|j"));
        assertEquals("题库 第一套", LocalExportService.sanitizeFileName("  题库   第一套  "));
        assertEquals("content-package", LocalExportService.sanitizeFileName("..."));
        assertEquals("content-package", LocalExportService.sanitizeFileName(null));
        assertEquals("_CON", LocalExportService.sanitizeFileName("CON"), "Windows 设备名加前缀");
        assertEquals(80, LocalExportService.sanitizeFileName("a".repeat(200)).length(), "超长名截断");
    }

    private static ExportRecord record(Long id, Path file, Long bankId, String version) {
        ExportRecord record = new ExportRecord();
        record.setId(id);
        record.setBankId(bankId);
        record.setBankName("测试库");
        record.setPackageKey("k");
        record.setVersion(version);
        record.setFilePath(file.toAbsolutePath().toString());
        record.setFileName(file.getFileName().toString());
        record.setSizeBytes(1L);
        record.setPublished(false);
        return record;
    }
}
