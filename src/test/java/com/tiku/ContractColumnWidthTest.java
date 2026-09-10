package com.tiku;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 内容包契约与本地列宽的**对齐**回归测试（2026-09-11）。
 *
 * 背景：发布侧按契约校验长度，若本地列更窄，就会出现「合法内容包能发布成功、
 * 下载后导入本地却因列超长落库失败」。V15 把本地列放宽到不低于契约上限，
 * 本测试把这条对齐关系锁住：今后谁把列改窄（或把契约上限调大）都会在这里失败。
 *
 * 契约上限来源：docs/package-format.md §5.4 与 web/server/api/packs/upload.post.ts / index.post.ts。
 * 跑在 profile=test（内存 H2 + 临时数据目录），不触碰用户真实数据。
 */
@SpringBootTest
@ActiveProfiles("test")
class ContractColumnWidthTest {

    /** 表.列 → 契约允许的最大长度（本地列必须 ≥ 它） */
    private static final Map<String, Integer> MIN_LENGTHS = new LinkedHashMap<>();

    static {
        // question_bank：title 120（登记）、description 2000、source 500、KEY_RE 1-100、version 40
        MIN_LENGTHS.put("QUESTION_BANK.NAME", 120);
        MIN_LENGTHS.put("QUESTION_BANK.DESCRIPTION", 2000);
        MIN_LENGTHS.put("QUESTION_BANK.SOURCE", 500);
        MIN_LENGTHS.put("QUESTION_BANK.PACKAGE_KEY", 100);
        MIN_LENGTHS.put("QUESTION_BANK.PARENT_KEY", 100);
        MIN_LENGTHS.put("QUESTION_BANK.VERSION", 40);
        // question：questionKey = external_id（KEY_RE 1-100）、source 500
        MIN_LENGTHS.put("QUESTION.EXTERNAL_ID", 100);
        MIN_LENGTHS.put("QUESTION.SOURCE", 500);
        // study_record：冗余 question_key 必须能容纳放宽后的 external_id
        MIN_LENGTHS.put("STUDY_RECORD.QUESTION_KEY", 100);
        // export_records：同样记录包身份
        MIN_LENGTHS.put("EXPORT_RECORDS.PACKAGE_KEY", 100);
        MIN_LENGTHS.put("EXPORT_RECORDS.VERSION", 40);
    }

    @Autowired
    private DataSource dataSource;

    @Test
    void localColumnsAreWideEnoughForTheContract() throws Exception {
        try (Connection conn = dataSource.getConnection()) {
            for (Map.Entry<String, Integer> entry : MIN_LENGTHS.entrySet()) {
                String[] parts = entry.getKey().split("\\.");
                int actual = characterMaximumLength(conn, parts[0], parts[1]);
                assertTrue(actual >= entry.getValue(),
                        entry.getKey() + " 列宽 " + actual + " < 契约上限 " + entry.getValue()
                                + "（会出现「能发布但导入失败」，检查 V15__widen_contract_columns.sql）");
            }
        }
    }

    private static int characterMaximumLength(Connection conn, String table, String column) throws Exception {
        String sql = "SELECT CHARACTER_MAXIMUM_LENGTH FROM INFORMATION_SCHEMA.COLUMNS "
                + "WHERE TABLE_NAME = ? AND COLUMN_NAME = ?";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, table);
            ps.setString(2, column);
            try (ResultSet rs = ps.executeQuery()) {
                assertTrue(rs.next(), "列不存在：" + table + "." + column);
                int len = rs.getInt(1);
                assertNotNull(len);
                return len;
            }
        }
    }
}
