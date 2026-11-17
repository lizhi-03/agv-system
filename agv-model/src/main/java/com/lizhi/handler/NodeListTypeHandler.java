package com.lizhi.handler;


import com.alibaba.fastjson.JSON;
import com.lizhi.domain.po.Node;
import org.apache.ibatis.type.BaseTypeHandler;
import org.apache.ibatis.type.JdbcType;
import org.apache.ibatis.type.MappedJdbcTypes;
import org.apache.ibatis.type.MappedTypes;

import java.sql.CallableStatement;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;

/**
 * planned_path 列（JSON 节点数组）的读写。
 * <p>
 * 写：只落 x / y，形如 {@code [{"x":1,"y":2},...]}。运行中的车辆每个 tick 都会回写 planned_path，
 * 不再把 A* 的 cost/g/h/parent 一并写进去，列内容更小、序列化更快。
 * 读：仍用 fastjson 解析，兼容历史数据里带 cost/parent/$ref 的旧格式。
 */
@MappedJdbcTypes(JdbcType.VARCHAR)
@MappedTypes(List.class)
public class NodeListTypeHandler extends BaseTypeHandler<List<Node>> {

    @Override
    public void setNonNullParameter(PreparedStatement ps, int i, List<Node> parameter, JdbcType jdbcType) throws SQLException {
        ps.setString(i, toCompactJson(parameter));
    }

    /** 只序列化坐标 */
    static String toCompactJson(List<Node> nodes) {
        StringBuilder sb = new StringBuilder(nodes.size() * 16 + 2);
        sb.append('[');
        boolean first = true;
        for (Node node : nodes) {
            if (node == null) {
                continue;
            }
            if (!first) {
                sb.append(',');
            }
            first = false;
            sb.append("{\"x\":").append(node.getX()).append(",\"y\":").append(node.getY()).append('}');
        }
        return sb.append(']').toString();
    }

    private static List<Node> parse(String json) {
        if (json == null || json.isEmpty()) {
            return null;
        }
        return JSON.parseArray(json, Node.class);
    }

    @Override
    public List<Node> getNullableResult(ResultSet rs, String columnName) throws SQLException {
        return parse(rs.getString(columnName));
    }

    @Override
    public List<Node> getNullableResult(ResultSet rs, int columnIndex) throws SQLException {
        return parse(rs.getString(columnIndex));
    }

    @Override
    public List<Node> getNullableResult(CallableStatement cs, int columnIndex) throws SQLException {
        return parse(cs.getString(columnIndex));
    }
}
