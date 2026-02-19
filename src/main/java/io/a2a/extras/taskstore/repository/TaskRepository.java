package io.a2a.extras.taskstore.repository;

import io.a2a.extras.taskstore.jdbc.JsonUtils;
import io.a2a.extras.taskstore.jdbc.JsonbAdapter;
import io.a2a.extras.taskstore.jdbc.SqlConstants;
import io.a2a.spec.Task;
import io.a2a.spec.TaskState;
import io.a2a.spec.TaskStatus;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.EnumSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

public class TaskRepository {

    private static final Set<TaskState> FINAL_STATES = EnumSet.of(
            TaskState.COMPLETED, TaskState.CANCELED, TaskState.FAILED, TaskState.REJECTED
    );

    private final JdbcTemplate jdbcTemplate;
    private final JsonbAdapter jsonbAdapter;

    public TaskRepository(JdbcTemplate jdbcTemplate, JsonbAdapter jsonbAdapter) {
        this.jdbcTemplate = jdbcTemplate;
        this.jsonbAdapter = jsonbAdapter;
    }

    public void save(Task task) {
        String taskId = task.getId();
        String contextId = task.getContextId();
        TaskStatus status = task.getStatus();
        String statusState = status.state().asString();
        String statusMessage = status.message() == null ? null : JsonUtils.toJson(status.message());
        OffsetDateTime statusTimestamp = status.timestamp();
        OffsetDateTime finalizedAt = FINAL_STATES.contains(status.state()) ? OffsetDateTime.now() : null;

        int updatedRows = jdbcTemplate.update(
                SqlConstants.UPDATE_TASK,
                contextId,
                statusState,
                jsonbAdapter.adapt(statusMessage),
                statusTimestamp,
                finalizedAt,
                taskId
        );

        if (updatedRows > 0) {
            return;
        }

        try {
            jdbcTemplate.update(
                    SqlConstants.INSERT_TASK,
                    taskId,
                    contextId,
                    statusState,
                    jsonbAdapter.adapt(statusMessage),
                    statusTimestamp,
                    finalizedAt
            );
        } catch (DuplicateKeyException ignored) {
            jdbcTemplate.update(
                    SqlConstants.UPDATE_TASK,
                    contextId,
                    statusState,
                    jsonbAdapter.adapt(statusMessage),
                    statusTimestamp,
                    finalizedAt,
                    taskId
            );
        }
    }

    public Optional<TaskRow> findById(String taskId) {
        try {
            return Optional.ofNullable(jdbcTemplate.queryForObject(
                    SqlConstants.SELECT_TASK_BY_ID,
                    new TaskRowMapper(),
                    taskId
            ));
        } catch (EmptyResultDataAccessException e) {
            return Optional.empty();
        }
    }

    public void updateMetadata(String taskId, Map<String, Object> metadata) {
        String metadataJson = metadata.isEmpty() ? null : JsonUtils.toJson(metadata);
        jdbcTemplate.update(SqlConstants.UPDATE_TASK_METADATA, jsonbAdapter.adapt(metadataJson), taskId);
    }

    public void delete(String taskId) {
        jdbcTemplate.update(SqlConstants.DELETE_TASK, taskId);
    }

    public boolean isTaskActive(String taskId) {
        return queryForOptional(SqlConstants.SELECT_STATUS_STATE, String.class, taskId)
                .map(status -> !FINAL_STATES.contains(TaskState.fromString(status)))
                .orElse(false);
    }

    public boolean isTaskFinalized(String taskId) {
        return queryForOptional(SqlConstants.SELECT_FINALIZED_AT, OffsetDateTime.class, taskId).isPresent();
    }

    private <T> Optional<T> queryForOptional(String sql, Class<T> requiredType, Object... args) {
        try {
            return Optional.ofNullable(jdbcTemplate.queryForObject(sql, requiredType, args));
        } catch (EmptyResultDataAccessException e) {
            return Optional.empty();
        }
    }

    public TaskStatus buildTaskStatus(TaskRow taskRow) {
        return new TaskStatus(
                TaskState.fromString(taskRow.statusState()),
                JsonUtils.fromJson(taskRow.statusMessageJson(), JsonUtils.MESSAGE_TYPE).orElse(null),
                taskRow.statusTimestamp()
        );
    }

    public Map<String, Object> loadMetadata(TaskRow taskRow) {
        return JsonUtils.fromJson(taskRow.metadataJson(), JsonUtils.METADATA_MAP_TYPE)
                .orElse(Map.of());
    }

    public record TaskRow(
            String taskId,
            String contextId,
            String statusState,
            String statusMessageJson,
            OffsetDateTime statusTimestamp,
            OffsetDateTime finalizedAt,
            String metadataJson
    ) {
    }

    private static class TaskRowMapper implements RowMapper<TaskRow> {
        @Override
        public TaskRow mapRow(ResultSet rs, int rowNum) throws SQLException {
            return new TaskRow(
                    rs.getString(SqlConstants.COL_TASK_ID),
                    rs.getString(SqlConstants.COL_CONTEXT_ID),
                    rs.getString(SqlConstants.COL_STATUS_STATE),
                    rs.getString(SqlConstants.COL_STATUS_MESSAGE_JSON),
                    rs.getObject(SqlConstants.COL_STATUS_TIMESTAMP, OffsetDateTime.class),
                    rs.getObject(SqlConstants.COL_FINALIZED_AT, OffsetDateTime.class),
                    rs.getString(SqlConstants.COL_METADATA_JSON)
            );
        }
    }
}
