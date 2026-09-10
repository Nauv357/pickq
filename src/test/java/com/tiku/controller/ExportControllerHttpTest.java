package com.tiku.controller;

import com.tiku.dto.DeleteExportRecordResult;
import com.tiku.dto.ExportPrefsResponse;
import com.tiku.dto.ExportRecordResponse;
import com.tiku.dto.ExportToDirRequest;
import com.tiku.dto.NextVersionResponse;
import com.tiku.service.ExportPrefsService;
import com.tiku.service.ExportRecordService;
import com.tiku.service.LocalExportService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.servlet.View;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 本地发布中心导出侧接口的 HTTP 契约：URI 映射、JSON body 绑定（bankId/version/dir、version、lastDir）、
 * 查询参数（deleteFile/bankId）与响应结构 { code, data, message }。
 * <p>
 * 重点：这些接口属于"本机数据管理"，<b>免登录（匿名可用）</b>——控制器不注入 CenterAuthStore、
 * 不读 token，未登录/离线时导出与记录读写全部照常（登录只约束 /api/center/** 的上云动作）。
 * standalone MockMvc，只装 web 层（不加载 Spring 上下文/数据库）。
 */
class ExportControllerHttpTest {

    private LocalExportService localExportService;
    private ExportRecordService exportRecordService;
    private ExportPrefsService exportPrefsService;
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        localExportService = mock(LocalExportService.class);
        exportRecordService = mock(ExportRecordService.class);
        exportPrefsService = mock(ExportPrefsService.class);
        // 匿名构造：控制器只依赖纯本地服务，没有登录态来源可注入
        mockMvc = mockMvc(new ExportController(localExportService, exportRecordService, exportPrefsService));
    }

    private MockMvc mockMvc(ExportController controller) {
        View errorView = new View() {
            @Override
            public String getContentType() {
                return "application/json";
            }

            @Override
            public void render(Map<String, ?> model, HttpServletRequest req, HttpServletResponse resp) {
                // 测试不渲染错误页
            }
        };
        return MockMvcBuilders.standaloneSetup(controller)
                .setControllerAdvice(new GlobalExceptionHandler(errorView))
                .build();
    }

    private static ExportRecordResponse record(Long id) {
        return new ExportRecordResponse(id, 3L, "我的题库", "key-1", "1.0.1",
                "C:\\Users\\me\\Documents\\拾题\\我的题库-1.0.1.tiku", "我的题库-1.0.1.tiku",
                1024L, false, null, LocalDateTime.of(2026, 9, 10, 12, 0), true);
    }

    @Test
    void exportEndpointBindsBodyAndReturnsRecord() throws Exception {
        when(localExportService.exportToDirectory(any())).thenReturn(record(9L));

        mockMvc.perform(post("/api/exports/export").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"bankId\":3,\"version\":\"1.0.1\",\"dir\":\"C:\\\\out\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.id").value(9))
                .andExpect(jsonPath("$.data.bankId").value(3))
                .andExpect(jsonPath("$.data.bankName").value("我的题库"))
                .andExpect(jsonPath("$.data.fileName").value("我的题库-1.0.1.tiku"))
                .andExpect(jsonPath("$.data.sizeBytes").value(1024))
                .andExpect(jsonPath("$.data.packageKey").value("key-1"))
                .andExpect(jsonPath("$.data.version").value("1.0.1"))
                .andExpect(jsonPath("$.data.fileExists").value(true));

        org.mockito.ArgumentCaptor<ExportToDirRequest> captor =
                org.mockito.ArgumentCaptor.forClass(ExportToDirRequest.class);
        verify(localExportService).exportToDirectory(captor.capture());
        org.junit.jupiter.api.Assertions.assertEquals(3L, captor.getValue().bankId());
        org.junit.jupiter.api.Assertions.assertEquals("1.0.1", captor.getValue().version());
        org.junit.jupiter.api.Assertions.assertEquals("C:\\out", captor.getValue().dir());
    }

    @Test
    void listDeleteAndMarkPublishedRoutes() throws Exception {
        when(exportRecordService.list()).thenReturn(List.of(record(9L)));
        when(exportRecordService.delete(eq(9L), anyBoolean())).thenReturn(new DeleteExportRecordResult(9L, true));
        when(exportRecordService.markPublished(eq(9L), any())).thenReturn(record(9L));

        mockMvc.perform(get("/api/exports"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].id").value(9))
                .andExpect(jsonPath("$.data[0].fileExists").value(true));

        mockMvc.perform(delete("/api/exports/9").param("deleteFile", "true"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.id").value(9))
                .andExpect(jsonPath("$.data.fileDeleted").value(true));
        verify(exportRecordService).delete(9L, true);

        // 不传 deleteFile = 只删记录（默认 false）
        mockMvc.perform(delete("/api/exports/9")).andExpect(status().isOk());
        verify(exportRecordService).delete(9L, false);

        mockMvc.perform(post("/api/exports/9/mark-published").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"version\":\"1.0.1\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.id").value(9));
        verify(exportRecordService).markPublished(9L, "1.0.1");

        // 不带 body 也接受（version 缺省用记录里的版本）
        mockMvc.perform(post("/api/exports/9/mark-published")).andExpect(status().isOk());
        verify(exportRecordService).markPublished(9L, null);
    }

    @Test
    void prefsAndNextVersionRoutes() throws Exception {
        when(exportPrefsService.current())
                .thenReturn(new ExportPrefsResponse("C:\\out", "C:\\Users\\me\\Documents\\拾题", Map.of("lastDir", true)));
        when(exportRecordService.nextVersion(3L)).thenReturn(new NextVersionResponse("1.0.1", "1.0.0"));

        mockMvc.perform(get("/api/exports/prefs"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.lastDir").value("C:\\out"))
                .andExpect(jsonPath("$.data.defaultDir").value("C:\\Users\\me\\Documents\\拾题"))
                .andExpect(jsonPath("$.data.dirExists.lastDir").value(true));

        mockMvc.perform(put("/api/exports/prefs").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"lastDir\":\"D:\\\\tiku\"}"))
                .andExpect(status().isOk());
        verify(exportPrefsService).remember("D:\\tiku");

        mockMvc.perform(get("/api/exports/next-version").param("bankId", "3"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.suggested").value("1.0.1"))
                .andExpect(jsonPath("$.data.lastPublished").value("1.0.0"));
        verify(exportRecordService).nextVersion(3L);
    }

    /**
     * 免登录：不带任何 token / 登录态，全部导出侧接口照常工作（本机数据管理，与广场登录无关）；
     * 且这些接口不依赖任何可发起远程请求的组件——控制器只依赖纯本地服务。
     */
    @Test
    void allEndpointsWorkAnonymousWithoutToken() throws Exception {
        when(localExportService.exportToDirectory(any())).thenReturn(record(9L));
        when(exportRecordService.list()).thenReturn(List.of(record(9L)));
        when(exportRecordService.delete(eq(9L), anyBoolean())).thenReturn(new DeleteExportRecordResult(9L, false));
        when(exportRecordService.markPublished(eq(9L), any())).thenReturn(record(9L));
        when(exportRecordService.nextVersion(3L)).thenReturn(new NextVersionResponse("1.0.1", "1.0.0"));
        when(exportPrefsService.current())
                .thenReturn(new ExportPrefsResponse("C:\\out", "C:\\Users\\me\\Documents\\拾题", Map.of("lastDir", true)));

        mockMvc.perform(get("/api/exports")).andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].id").value(9));
        mockMvc.perform(post("/api/exports/export").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"bankId\":3,\"version\":\"1.0.1\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.filePath").value("C:\\Users\\me\\Documents\\拾题\\我的题库-1.0.1.tiku"));
        mockMvc.perform(get("/api/exports/prefs")).andExpect(status().isOk());
        mockMvc.perform(put("/api/exports/prefs").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"lastDir\":\"D:\\\\tiku\"}"))
                .andExpect(status().isOk());
        mockMvc.perform(get("/api/exports/next-version").param("bankId", "3")).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.suggested").value("1.0.1"));
        mockMvc.perform(delete("/api/exports/9").param("deleteFile", "true")).andExpect(status().isOk());
        mockMvc.perform(post("/api/exports/9/mark-published").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"version\":\"1.0.1\"}"))
                .andExpect(status().isOk());

        verify(localExportService).exportToDirectory(any());
        verify(exportRecordService).list();
        verify(exportPrefsService).remember("D:\\tiku");
        verify(exportRecordService).nextVersion(3L);
        verify(exportRecordService).delete(9L, true);
        verify(exportRecordService).markPublished(9L, "1.0.1");
    }
}
