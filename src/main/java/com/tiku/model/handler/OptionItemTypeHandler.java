package com.tiku.model.handler;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tiku.model.OptionItem;
import org.apache.ibatis.type.BaseTypeHandler;
import org.apache.ibatis.type.JdbcType;

import java.sql.CallableStatement;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;

public class OptionItemTypeHandler extends BaseTypeHandler<List<OptionItem>> {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Override
    public void setNonNullParameter(PreparedStatement ps, int i, List<OptionItem> parameter, JdbcType jdbcType) throws SQLException {
        try {
            ps.setString(i, MAPPER.writeValueAsString(parameter));
        } catch (Exception e) {
            throw new RuntimeException("选项序列化失败", e);
        }
    }

    @Override
    public List<OptionItem> getNullableResult(ResultSet rs, String columnName) throws SQLException {
        return parse(rs.getString(columnName));
    }

    @Override
    public List<OptionItem> getNullableResult(ResultSet rs, int columnIndex) throws SQLException {
        return parse(rs.getString(columnIndex));
    }

    @Override
    public List<OptionItem> getNullableResult(CallableStatement cs, int columnIndex) throws SQLException {
        return parse(cs.getString(columnIndex));
    }

    private List<OptionItem> parse(String json){
        if(json == null || json.isBlank()){
            return List.of();
        }
        try {
            return MAPPER.readValue(json, new TypeReference<List<OptionItem>>() {});
        } catch (Exception e) {
            throw new RuntimeException("选项反序列化失败", e);
        }
    }
}
