package io.a2a.extras.taskstore.jdbc;

import org.postgresql.util.PGobject;

import java.sql.SQLException;

/**
 * Converts JSON strings to database-compatible parameter objects.
 * Handles PostgreSQL JSONB columns by wrapping values in PGobject.
 */
@FunctionalInterface
public interface JsonbAdapter {

    /**
     * Converts a JSON string to a database-compatible parameter.
     *
     * @param json the JSON string, may be null
     * @return PGobject for PostgreSQL, the original string for other databases, or null if input was null
     */
    Object adapt(String json);

    /**
     * Factory method that detects database type from JDBC URL.
     *
     * @param jdbcUrl the JDBC connection URL
     * @return PostgresJsonbAdapter for PostgreSQL URLs, StandardJsonbAdapter otherwise
     */
    static JsonbAdapter forDatabase(String jdbcUrl) {
        return jdbcUrl != null && jdbcUrl.startsWith("jdbc:postgresql:")
                ? new PostgresJsonbAdapter()
                : new StandardJsonbAdapter();
    }

    /**
     * Standard adapter that returns JSON strings as-is.
     * Compatible with H2 and other databases that accept string values for JSON columns.
     */
    final class StandardJsonbAdapter implements JsonbAdapter {
        @Override
        public Object adapt(String json) {
            return json;
        }
    }

    /**
     * PostgreSQL adapter that wraps JSON strings in PGobject with type "jsonb".
     * Required for proper handling of PostgreSQL JSONB columns.
     */
    final class PostgresJsonbAdapter implements JsonbAdapter {
        @Override
        public Object adapt(String json) {
            if (json == null) {
                return null;
            }
            try {
                PGobject pgObject = new PGobject();
                pgObject.setType("jsonb");
                pgObject.setValue(json);
                return pgObject;
            } catch (SQLException e) {
                throw new IllegalArgumentException("Failed to create PostgreSQL JSONB parameter", e);
            }
        }
    }
}
