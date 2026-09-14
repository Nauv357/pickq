package com.tiku.controller;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.hamcrest.Matchers.greaterThan;
import static org.hamcrest.Matchers.greaterThanOrEqualTo;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.is;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 技能图与标签接口的 HTTP 契约（阶段 0）。
 *
 * 用真 DB（profile=test：内存 H2 + Flyway 含 V17）+ 本机假 AI 端点，覆盖：
 * 模板列表/全貌/入库、触发标注、待确认队列、覆盖地图、批量确认、单题用户标注。
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class SkillControllerHttpTest {

    private static final String TEMPLATE = "official.civil-service";
    private static final Long BANK_ID = 990002L;

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private JdbcTemplate jdbc;

    private HttpServer server;

    @BeforeEach
    void setUp() throws Exception {
        jdbc.update("DELETE FROM question_skill");
        jdbc.update("DELETE FROM skill_group_map");
        jdbc.update("DELETE FROM skill_node");
        jdbc.update("DELETE FROM skill_edge");
        jdbc.update("DELETE FROM question WHERE bank_id = ?", BANK_ID);

        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            String reply = body.contains("id=")
                    ? "{\"mappings\":[{\"id\":" + firstId(body) + ",\"nodes\":[{\"nodeId\":\"gk.pd.argue\",\"confidence\":0.9}]}]}"
                    : "{\"mappings\":[{\"group\":\"" + firstGroup(body) + "\",\"nodes\":[{\"nodeId\":\"gk.pd.figure.num\",\"confidence\":0.9}]}]}";
            byte[] out = ("{\"choices\":[{\"message\":{\"content\":" + json(reply) + "}}]}")
                    .getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, out.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(out);
            }
            exchange.close();
        });
        server.start();

        Path dataDir = Path.of(System.getProperty("java.io.tmpdir"), "tiku-test-data");
        Files.createDirectories(dataDir);
        String base = "http://127.0.0.1:" + server.getAddress().getPort() + "/v1";
        Files.writeString(dataDir.resolve("ai-config.json"),
                "{\"baseUrl\":\"" + base + "\",\"model\":\"fake-model\"}", StandardCharsets.UTF_8);

        // 3 道同 topic 的题（一组映射即可覆盖，节点题量 ≥3 → 证据充足）
        for (int i = 0; i < 3; i++) {
            jdbc.update("INSERT INTO question (external_id, question_type, question_number, content, topic, volume, score, bank_id, deleted) "
                            + "VALUES (?, 'SINGLE', ?, ?, '图形推理', 0, 1, ?, 0)",
                    "HTTP_" + System.nanoTime() + "_" + i, i + 1, "第 " + (i + 1) + " 道图形推理题", BANK_ID);
        }
    }

    @AfterEach
    void tearDown() {
        server.stop(0);
    }

    private static String firstId(String body) {
        var m = java.util.regex.Pattern.compile("id=(\\d+)").matcher(body);
        return m.find() ? m.group(1) : "0";
    }

    private static String firstGroup(String body) {
        return body.contains("图形推理") ? "图形推理" : "未知分组";
    }

    private static String json(String raw) {
        try {
            return new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(raw);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private Long firstQuestionId() {
        return jdbc.queryForObject("SELECT id FROM question WHERE bank_id = ? ORDER BY id LIMIT 1", Long.class, BANK_ID);
    }

    @Test
    void graphEndpointsExposeTemplatesAndNodes() throws Exception {
        mockMvc.perform(get("/api/skills/templates"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data", hasSize(greaterThanOrEqualTo(2))))
                .andExpect(jsonPath("$.data[?(@.templateId=='" + TEMPLATE + "')]", hasSize(1)));

        mockMvc.perform(get("/api/skills/templates/{id}", TEMPLATE))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.name", is("公务员行测（通用）")))
                .andExpect(jsonPath("$.data.nodes", hasSize(greaterThan(10))))
                .andExpect(jsonPath("$.data.nodes[?(@.nodeId=='gk.pd.figure.num')].prereq", hasSize(1)));

        mockMvc.perform(post("/api/skills/templates/{id}/sync", TEMPLATE))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.nodesWritten", greaterThan(10)));
        Integer nodes = jdbc.queryForObject("SELECT COUNT(*) FROM skill_node WHERE template_id = ?", Integer.class, TEMPLATE);
        Integer edges = jdbc.queryForObject("SELECT COUNT(*) FROM skill_edge WHERE template_id = ?", Integer.class, TEMPLATE);
        org.junit.jupiter.api.Assertions.assertTrue(nodes != null && nodes > 10, "节点应已入库：" + nodes);
        org.junit.jupiter.api.Assertions.assertTrue(edges != null && edges > 0, "前置边应已入库：" + edges);
    }

    @Test
    void taggingFlowThroughHttp() throws Exception {
        // 未指定模板 → 400（可照做的错误）
        mockMvc.perform(get("/api/skills/templates/{id}", "official.nope"))
                .andExpect(status().isBadRequest());

        // 触发标注
        mockMvc.perform(post("/api/banks/{id}/skills/suggest", BANK_ID).param("templateId", TEMPLATE))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.aiCalls", is(1)))
                .andExpect(jsonPath("$.data.taggedQuestions", is(3)))
                .andExpect(jsonPath("$.data.withoutUsableTag", is(0)));

        // 审阅清单：以**题**为单位，带状态与当前标签（界面就地改标签就靠它）
        mockMvc.perform(get("/api/banks/{id}/skills/questions", BANK_ID)
                        .param("templateId", TEMPLATE).param("status", "pending"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.total", is(3)))
                .andExpect(jsonPath("$.data.counts.pending", is(3)))
                .andExpect(jsonPath("$.data.counts.untagged", is(0)))
                .andExpect(jsonPath("$.data.records[0].status", is("pending")))
                .andExpect(jsonPath("$.data.records[0].questionNumber", greaterThan(0)))
                .andExpect(jsonPath("$.data.records[0].preview", org.hamcrest.Matchers.not(org.hamcrest.Matchers.emptyString())))
                .andExpect(jsonPath("$.data.records[0].tags[0].nodeId", is("gk.pd.figure.num")))
                .andExpect(jsonPath("$.data.records[0].tags[0].name", is("图形推理·数量与属性")))
                .andExpect(jsonPath("$.data.records[0].tags[0].source", is("ai")))
                .andExpect(jsonPath("$.data.records[0].tags[0].confirmed", is(false)));

        // 按知识点筛题（按组批量处理时用）
        mockMvc.perform(get("/api/banks/{id}/skills/questions", BANK_ID)
                        .param("templateId", TEMPLATE).param("status", "all").param("nodeId", "gk.pd.figure.num"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.total", is(3)));
        mockMvc.perform(get("/api/banks/{id}/skills/questions", BANK_ID)
                        .param("templateId", TEMPLATE).param("status", "all").param("nodeId", "gk.zl.growth"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.total", is(0)));

        // 覆盖地图：3 题同节点 → 证据充足；清单条数与概览数字必须一致（用户实测过的口径打架）
        mockMvc.perform(get("/api/banks/{id}/skills/coverage", BANK_ID).param("templateId", TEMPLATE))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.totalQuestions", is(3)))
                .andExpect(jsonPath("$.data.pendingQuestions", is(3)))
                .andExpect(jsonPath("$.data.usableQuestions", is(3)))
                .andExpect(jsonPath("$.data.nodes[?(@.nodeId=='gk.pd.figure.num')].evidenceEnough", hasSize(1)));

        // 就地改标签：把这些题的标签设定为给定节点（source=user），并清掉旧的 AI 建议
        Long someQid = firstQuestionId();
        mockMvc.perform(post("/api/banks/{id}/skills/apply", BANK_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"action\":\"set\",\"newNodes\":[\"gk.pd.analogy\"],\"questionIds\":[" + someQid + "],"
                                + "\"templateId\":\"" + TEMPLATE + "\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.affected", is(1)));
        mockMvc.perform(get("/api/banks/{id}/skills/questions", BANK_ID)
                        .param("templateId", TEMPLATE).param("status", "confirmed"))
                .andExpect(jsonPath("$.data.records[0].tags[0].nodeId", is("gk.pd.analogy")))
                .andExpect(jsonPath("$.data.records[0].tags[0].source", is("user")));

        // 同一题再设一次：不能累积出重复的用户标签（就地改标签会被反复使用）
        mockMvc.perform(post("/api/banks/{id}/skills/apply", BANK_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"action\":\"set\",\"newNodes\":[\"gk.pd.analogy\"],\"questionIds\":[" + someQid + "],"
                                + "\"templateId\":\"" + TEMPLATE + "\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.affected", is(1)));
        mockMvc.perform(get("/api/questions/{id}/skills", someQid))
                .andExpect(jsonPath("$.data", hasSize(1)));

        // set 不给 questionIds → 不做任何事（危险动作必须显式给题）
        mockMvc.perform(post("/api/banks/{id}/skills/apply", BANK_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"action\":\"set\",\"newNodes\":[\"gk.pd.analogy\"],\"templateId\":\"" + TEMPLATE + "\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.affected", is(0)));

        // 批量确认（剩下的 2 题）
        mockMvc.perform(post("/api/banks/{id}/skills/apply", BANK_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"action\":\"confirm\",\"nodeId\":\"gk.pd.figure.num\",\"templateId\":\"" + TEMPLATE + "\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.affected", is(2)));
        mockMvc.perform(get("/api/banks/{id}/skills/coverage", BANK_ID).param("templateId", TEMPLATE))
                .andExpect(jsonPath("$.data.confirmedQuestions", is(3)));

        // 单题用户标注（覆盖 AI）
        Long qid = firstQuestionId();
        mockMvc.perform(put("/api/questions/{id}/skills", qid)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"templateId\":\"" + TEMPLATE + "\",\"nodeIds\":[\"gk.pd.analogy\"]}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.written", is(1)));
        mockMvc.perform(get("/api/questions/{id}/skills", qid))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data", hasSize(1)))
                .andExpect(jsonPath("$.data[0].nodeId", is("gk.pd.analogy")))
                .andExpect(jsonPath("$.data[0].source", is("user")))
                .andExpect(jsonPath("$.data[0].confirmed", is(true)));

        // 非法节点 → 400
        mockMvc.perform(put("/api/questions/{id}/skills", qid)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"templateId\":\"" + TEMPLATE + "\",\"nodeIds\":[\"gk.nope\"]}"))
                .andExpect(status().isBadRequest());
    }

    /**
     * 「分类」下线后的一次性整理：把只有分类、没有归属的题补上归属，不覆盖已有归属。
     */
    @Test
    void mergeCategoryIntoTopicOnlyFillsEmptyTopic() throws Exception {
        Long bankId = 990003L;
        jdbc.update("DELETE FROM question WHERE bank_id = ?", bankId);
        jdbc.update("INSERT INTO question (external_id, question_type, content, topic, category, volume, score, bank_id, deleted) "
                        + "VALUES (?, 'SINGLE', '只有分类', NULL, '基础题', 0, 1, ?, 0)",
                "MERGE_" + System.nanoTime() + "_a", bankId);
        jdbc.update("INSERT INTO question (external_id, question_type, content, topic, category, volume, score, bank_id, deleted) "
                        + "VALUES (?, 'SINGLE', '两者都有', '2024 国考', '基础题', 0, 1, ?, 0)",
                "MERGE_" + System.nanoTime() + "_b", bankId);
        jdbc.update("INSERT INTO question_bank (id, name, created_at, updated_at) VALUES (?, '整理测试', NOW(), NOW())",
                bankId);
        try {
            mockMvc.perform(post("/api/banks/{id}/merge-category-into-topic", bankId))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.affected", is(1)));
            String filled = jdbc.queryForObject(
                    "SELECT topic FROM question WHERE bank_id = ? AND content = '只有分类'", String.class, bankId);
            String untouched = jdbc.queryForObject(
                    "SELECT topic FROM question WHERE bank_id = ? AND content = '两者都有'", String.class, bankId);
            org.junit.jupiter.api.Assertions.assertEquals("基础题", filled, "只有分类的题应把分类并入归属");
            org.junit.jupiter.api.Assertions.assertEquals("2024 国考", untouched, "已有归属的题不能被覆盖");
        } finally {
            jdbc.update("DELETE FROM question WHERE bank_id = ?", bankId);
            jdbc.update("DELETE FROM question_bank WHERE id = ?", bankId);
        }
    }
}
