package com.tiku.controller;

import com.tiku.service.CenterAuthStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.servlet.View;

import java.nio.charset.StandardCharsets;
import java.util.Map;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 发布前体检接口的 HTTP 契约测试：URI 映射、multipart 字段 file 绑定、
 * 响应 JSON 结构（{ code, data:{...}, message }）与 400 错误文案。
 *
 * 用 standalone MockMvc + GlobalExceptionHandler，只装 web 层，不加载 Spring 上下文/数据库：
 * 既快，也不依赖本机数据目录（测试库见 src/test/resources/application-test.yml）。
 */
class CenterPublishInspectHttpTest {

    private MockMvc mockMvc;
    private CenterAuthStore authStore;

    @BeforeEach
    void setUp() {
        authStore = mock(CenterAuthStore.class);
        when(authStore.isLoggedIn()).thenReturn(true);
        View errorView = new View() {
            @Override
            public String getContentType() {
                return "application/json";
            }

            @Override
            public void render(Map<String, ?> model, HttpServletRequest request, HttpServletResponse response) {
                // 测试不渲染错误页
            }
        };
        mockMvc = MockMvcBuilders.standaloneSetup(new CenterPublishController(authStore))
                .setControllerAdvice(new GlobalExceptionHandler(errorView))
                .build();
    }

    @Test
    void inspectEndpointReturnsMetadataJson() throws Exception {
        String json = "{\"schemaVersion\":1,\"packageKey\":\"k\",\"version\":\"1.0\",\"title\":\"标题\","
                + "\"description\":\"d\",\"source\":\"s\",\"questions\":[{},{}],\"materials\":[{}]}";
        MockMultipartFile file = new MockMultipartFile("file", "a.json", "application/json",
                json.getBytes(StandardCharsets.UTF_8));
        mockMvc.perform(multipart("/api/center/publish/inspect").file(file))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.packageKey").value("k"))
                .andExpect(jsonPath("$.data.version").value("1.0"))
                .andExpect(jsonPath("$.data.title").value("标题"))
                .andExpect(jsonPath("$.data.description").value("d"))
                .andExpect(jsonPath("$.data.source").value("s"))
                .andExpect(jsonPath("$.data.schemaVersion").value(1))
                .andExpect(jsonPath("$.data.questionsCount").value(2))
                .andExpect(jsonPath("$.data.materialsCount").value(1));
    }

    @Test
    void invalidPackageReturns400WithReadableMessage() throws Exception {
        MockMultipartFile file = new MockMultipartFile("file", "a.json", "application/json",
                "junk".getBytes(StandardCharsets.UTF_8));
        mockMvc.perform(multipart("/api/center/publish/inspect").file(file))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(400))
                .andExpect(jsonPath("$.message").value(
                        "题库文件解析失败：不是有效的题库文件（既不是 .tiku 也不是 v1 JSON）"));
    }

    /** v2 .tiku 容器经 HTTP 也要如实回传 schemaVersion=2 */
    @Test
    void inspectEndpointAcceptsTikuContainer() throws Exception {
        java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream();
        try (java.util.zip.ZipOutputStream zos = new java.util.zip.ZipOutputStream(bos)) {
            zos.putNextEntry(new java.util.zip.ZipEntry("package.json"));
            zos.write(("{\"schemaVersion\":2,\"packageKey\":\"kc\",\"version\":\"2.0\",\"title\":\"容器\","
                    + "\"questions\":[{},{}]}").getBytes(StandardCharsets.UTF_8));
            zos.closeEntry();
            zos.putNextEntry(new java.util.zip.ZipEntry("media/a.png"));
            zos.write(new byte[]{1, 2, 3});
            zos.closeEntry();
        }
        MockMultipartFile file = new MockMultipartFile("file", "a.tiku", "application/zip", bos.toByteArray());
        mockMvc.perform(multipart("/api/center/publish/inspect").file(file))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.schemaVersion").value(2))
                .andExpect(jsonPath("$.data.packageKey").value("kc"))
                .andExpect(jsonPath("$.data.questionsCount").value(2));
    }

    @Test
    void inspectWithoutLoginIsRejected() throws Exception {
        when(authStore.isLoggedIn()).thenReturn(false);
        MockMultipartFile file = new MockMultipartFile("file", "a.json", "application/json",
                "{}".getBytes(StandardCharsets.UTF_8));
        mockMvc.perform(multipart("/api/center/publish/inspect").file(file))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.message").value("请先登录题库广场账号"));
    }

    @Test
    void publishRejectsInvalidPackageWith400BeforeForwarding() throws Exception {
        MockMultipartFile file = new MockMultipartFile("file", "a.json", "application/json",
                "junk".getBytes(StandardCharsets.UTF_8));
        mockMvc.perform(multipart("/api/center/publish").file(file).param("center", "http://127.0.0.1:1"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(
                        "题库文件解析失败：不是有效的题库文件（既不是 .tiku 也不是 v1 JSON）"));
    }

    @Test
    void inspectMissingFilePartReturns400() throws Exception {
        mockMvc.perform(multipart("/api/center/publish/inspect"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(400));
    }

    /** 补传托管文件（PUT）同样先本地体检：不合法 → 400 + 文案，不发公网请求 */
    @Test
    void uploadFileRejectsInvalidPackageWith400() throws Exception {
        MockMultipartFile file = new MockMultipartFile("file", "x.tiku", "application/zip",
                "junk".getBytes(StandardCharsets.UTF_8));
        mockMvc.perform(multipart("/api/center/packs/mykey/1.0/file").file(file).with((request) -> {
                    request.setMethod("PUT");
                    return request;
                }))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(
                        "题库文件解析失败：不是有效的题库文件（既不是 .tiku 也不是 v1 JSON）"));
    }

    /** 补传：格式合法但 packageKey/version 与登记不一致 → 本地 400（官网 file.put.ts 同样会拒绝） */
    @Test
    void uploadFileRejectsMismatchedIdentityWith400() throws Exception {
        java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream();
        try (java.util.zip.ZipOutputStream zos = new java.util.zip.ZipOutputStream(bos)) {
            zos.putNextEntry(new java.util.zip.ZipEntry("package.json"));
            zos.write(("{\"schemaVersion\":2,\"packageKey\":\"otherkey\",\"version\":\"9.9\",\"title\":\"t\","
                    + "\"questions\":[{}]}").getBytes(StandardCharsets.UTF_8));
            zos.closeEntry();
        }
        MockMultipartFile file = new MockMultipartFile("file", "x.tiku", "application/zip", bos.toByteArray());
        mockMvc.perform(multipart("/api/center/packs/mykey/1.0/file").file(file).with((request) -> {
                    request.setMethod("PUT");
                    return request;
                }))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("文件内 packageKey 与登记不一致（文件 otherkey，登记 mykey）"));
    }
}
