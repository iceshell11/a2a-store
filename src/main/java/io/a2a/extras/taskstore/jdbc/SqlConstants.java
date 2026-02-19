package io.a2a.extras.taskstore.jdbc;

/**
 * Centralized SQL statements for all database operations.
 * All table and column names are defined as constants to ensure consistency.
 */
public final class SqlConstants {

    private SqlConstants() {
    }

    // Table names
    public static final String TABLE_TASKS = "a2a_tasks";
    public static final String TABLE_HISTORY = "a2a_history";
    public static final String TABLE_ARTIFACTS = "a2a_artifacts";

    // Column names - Tasks
    public static final String COL_TASK_ID = "task_id";
    public static final String COL_CONTEXT_ID = "context_id";
    public static final String COL_STATUS_STATE = "status_state";
    public static final String COL_STATUS_MESSAGE_JSON = "status_message_json";
    public static final String COL_STATUS_TIMESTAMP = "status_timestamp";
    public static final String COL_METADATA_JSON = "metadata_json";
    public static final String COL_FINALIZED_AT = "finalized_at";
    public static final String COL_CREATED_AT = "created_at";
    public static final String COL_UPDATED_AT = "updated_at";

    // Column names - History
    public static final String COL_MESSAGE_ID = "message_id";
    public static final String COL_ROLE = "role";
    public static final String COL_CONTENT_JSON = "content_json";
    public static final String COL_SEQUENCE_NUM = "sequence_num";

    // Column names - Artifacts
    public static final String COL_ARTIFACT_ID = "artifact_id";
    public static final String COL_NAME = "name";
    public static final String COL_DESCRIPTION = "description";
    public static final String COL_EXTENSIONS_JSON = "extensions_json";

    // Task SQL
    public static final String UPDATE_TASK = String.format("""
            UPDATE %s
            SET %s = ?, %s = ?, %s = ?, %s = ?, %s = ?
            WHERE %s = ?
            """,
            TABLE_TASKS, COL_CONTEXT_ID, COL_STATUS_STATE, COL_STATUS_MESSAGE_JSON,
            COL_STATUS_TIMESTAMP, COL_FINALIZED_AT, COL_TASK_ID);

    public static final String INSERT_TASK = String.format("""
            INSERT INTO %s (%s, %s, %s, %s, %s, %s)
            VALUES (?, ?, ?, ?, ?, ?)
            """,
            TABLE_TASKS, COL_TASK_ID, COL_CONTEXT_ID, COL_STATUS_STATE,
            COL_STATUS_MESSAGE_JSON, COL_STATUS_TIMESTAMP, COL_FINALIZED_AT);

    public static final String SELECT_TASK_BY_ID = String.format("""
            SELECT * FROM %s WHERE %s = ?
            """, TABLE_TASKS, COL_TASK_ID);

    public static final String UPDATE_TASK_METADATA = String.format("""
            UPDATE %s SET %s = ? WHERE %s = ?
            """, TABLE_TASKS, COL_METADATA_JSON, COL_TASK_ID);

    public static final String DELETE_TASK = String.format("""
            DELETE FROM %s WHERE %s = ?
            """, TABLE_TASKS, COL_TASK_ID);

    public static final String SELECT_STATUS_STATE = String.format("""
            SELECT %s FROM %s WHERE %s = ?
            """, COL_STATUS_STATE, TABLE_TASKS, COL_TASK_ID);

    public static final String SELECT_FINALIZED_AT = String.format("""
            SELECT %s FROM %s WHERE %s = ?
            """, COL_FINALIZED_AT, TABLE_TASKS, COL_TASK_ID);

    // History SQL
    public static final String DELETE_HISTORY = String.format("""
            DELETE FROM %s WHERE %s = ?
            """, TABLE_HISTORY, COL_TASK_ID);

    public static final String COUNT_HISTORY = String.format("""
            SELECT COUNT(*) FROM %s WHERE %s = ?
            """, TABLE_HISTORY, COL_TASK_ID);

    public static final String INSERT_HISTORY = String.format("""
            INSERT INTO %s (%s, %s, %s, %s, %s, %s)
            VALUES (?, ?, ?, ?, ?, ?)
            """,
            TABLE_HISTORY, COL_TASK_ID, COL_MESSAGE_ID, COL_ROLE,
            COL_CONTENT_JSON, COL_METADATA_JSON, COL_SEQUENCE_NUM);

    public static final String SELECT_HISTORY = String.format("""
            SELECT %s, %s, %s, %s, %s
            FROM %s
            WHERE %s = ?
            ORDER BY %s
            """,
            COL_TASK_ID, COL_MESSAGE_ID, COL_ROLE, COL_CONTENT_JSON, COL_METADATA_JSON,
            TABLE_HISTORY, COL_TASK_ID, COL_SEQUENCE_NUM);

    // Artifact SQL
    public static final String DELETE_ARTIFACTS = String.format("""
            DELETE FROM %s WHERE %s = ?
            """, TABLE_ARTIFACTS, COL_TASK_ID);

    public static final String INSERT_ARTIFACT = String.format("""
            INSERT INTO %s (%s, %s, %s, %s, %s, %s, %s, %s)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?)
            """,
            TABLE_ARTIFACTS, COL_TASK_ID, COL_ARTIFACT_ID, COL_NAME, COL_DESCRIPTION,
            COL_CONTENT_JSON, COL_METADATA_JSON, COL_EXTENSIONS_JSON, COL_SEQUENCE_NUM);

    public static final String SELECT_ARTIFACTS = String.format("""
            SELECT %s, %s, %s, %s, %s, %s, %s
            FROM %s
            WHERE %s = ?
            ORDER BY %s
            """,
            COL_TASK_ID, COL_ARTIFACT_ID, COL_NAME, COL_DESCRIPTION, COL_CONTENT_JSON,
            COL_METADATA_JSON, COL_EXTENSIONS_JSON, TABLE_ARTIFACTS, COL_TASK_ID, COL_SEQUENCE_NUM);
}
