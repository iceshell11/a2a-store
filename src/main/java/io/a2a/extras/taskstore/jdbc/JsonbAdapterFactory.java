package io.a2a.extras.taskstore.jdbc;

import org.springframework.jdbc.core.JdbcTemplate;

import java.sql.SQLException;

public final class JsonbAdapterFactory {

    private JsonbAdapterFactory() {
    }

    public static JsonbAdapter create(JdbcTemplate jdbcTemplate) {
        try {
            String url = jdbcTemplate.getDataSource().getConnection().getMetaData().getURL();
            return JsonbAdapter.forDatabase(url);
        } catch (SQLException e) {
            return new JsonbAdapter.StandardJsonbAdapter();
        }
    }
}
