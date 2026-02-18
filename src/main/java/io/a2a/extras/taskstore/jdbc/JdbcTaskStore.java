package io.a2a.extras.taskstore.jdbc;

import io.a2a.extras.taskstore.A2aTaskStoreProperties;
import io.a2a.extras.taskstore.cache.CacheConfig;
import io.a2a.server.tasks.TaskStateProvider;
import io.a2a.server.tasks.TaskStore;
import io.a2a.spec.Artifact;
import io.a2a.spec.Message;
import io.a2a.spec.Part;
import io.a2a.spec.Task;
import io.a2a.spec.TaskState;
import io.a2a.spec.TaskStatus;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.transaction.annotation.Transactional;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.BiFunction;
import java.util.stream.IntStream;

public class JdbcTaskStore implements TaskStore, TaskStateProvider {

    private static final Set<TaskState> FINAL_STATES = EnumSet.of(
        TaskState.COMPLETED, TaskState.CANCELED, TaskState.FAILED, TaskState.REJECTED
    );
    private static final String UPDATE_CONVERSATION_SQL = """
        UPDATE a2a_conversations
        SET status_state = ?, status_message = ?, status_timestamp = ?, finalized_at = ?
        WHERE conversation_id = ?
        """;
    private static final String INSERT_CONVERSATION_SQL = """
        INSERT INTO a2a_conversations
        (conversation_id, status_state, status_message, status_timestamp, finalized_at)
        VALUES (?, ?, ?, ?, ?)
        """;

    private final JdbcTemplate jdbcTemplate;
    private final A2aTaskStoreProperties properties;

    public JdbcTaskStore(JdbcTemplate jdbcTemplate, A2aTaskStoreProperties properties) {
        this.jdbcTemplate = jdbcTemplate;
        this.properties = properties;
    }

    @Override
    @Transactional
    @CacheEvict(value = CacheConfig.TASK_CACHE, key = "#task.contextId")
    public void save(Task task) {
        String conversationId = task.getContextId();
        saveConversation(task);
        saveMessages(conversationId, task.getHistory());

        if (properties.isStoreArtifacts()) {
            saveArtifacts(conversationId, task.getArtifacts());
        }
        if (properties.isStoreMetadata() && task.getMetadata() != null) {
            saveMetadata(conversationId, task.getMetadata());
        }
    }

    private void saveConversation(Task task) {
        TaskStatus status = task.getStatus();
        String conversationId = task.getContextId();
        String statusState = status.state().asString();
        String statusMessage = status.message() == null ? null : JsonUtils.toJson(status.message());
        OffsetDateTime statusTimestamp = status.timestamp();
        OffsetDateTime finalizedAt = FINAL_STATES.contains(status.state()) ? OffsetDateTime.now() : null;

        int updatedRows = jdbcTemplate.update(
            UPDATE_CONVERSATION_SQL,
            statusState,
            statusMessage,
            statusTimestamp,
            finalizedAt,
            conversationId
        );

        if (updatedRows > 0) {
            return;
        }

        try {
            jdbcTemplate.update(
                INSERT_CONVERSATION_SQL,
                conversationId,
                statusState,
                statusMessage,
                statusTimestamp,
                finalizedAt
            );
        } catch (DuplicateKeyException ignored) {
            jdbcTemplate.update(
                UPDATE_CONVERSATION_SQL,
                statusState,
                statusMessage,
                statusTimestamp,
                finalizedAt,
                conversationId
            );
        }
    }

    private void saveMessages(String conversationId, List<Message> messages) {
        if (messages.isEmpty()) {
            jdbcTemplate.update("DELETE FROM a2a_messages WHERE conversation_id = ?", conversationId);
            return;
        }

        int existingCount = jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM a2a_messages WHERE conversation_id = ?",
            Integer.class,
            conversationId
        );

        if (existingCount == 0) {
            insertAllMessages(conversationId, messages);
        } else if (messages.size() > existingCount) {
            List<Message> newMessages = messages.subList(existingCount, messages.size());
            insertMessagesStartingFromSequence(conversationId, newMessages, existingCount);
        }
    }

    private void insertAllMessages(String conversationId, List<Message> messages) {
        String sql = """
            INSERT INTO a2a_messages (conversation_id, role, content_json, metadata_json, sequence_num)
            VALUES (?, ?, ?, ?, ?)
            """;
        batchInsert(sql, messages, (msg, index) -> new Object[]{
            conversationId,
            msg.getRole().name(),
            JsonUtils.toJson(msg.getParts()),
            JsonUtils.toJson(msg.getMetadata()),
            index
        });
    }

    private void insertMessagesStartingFromSequence(String conversationId, List<Message> messages, int startSequence) {
        String sql = """
            INSERT INTO a2a_messages (conversation_id, role, content_json, metadata_json, sequence_num)
            VALUES (?, ?, ?, ?, ?)
            """;
        batchInsert(sql, messages, (msg, index) -> new Object[]{
            conversationId,
            msg.getRole().name(),
            JsonUtils.toJson(msg.getParts()),
            JsonUtils.toJson(msg.getMetadata()),
            startSequence + index
        });
    }

    private <T> void batchInsert(String sql, List<T> items, BiFunction<T, Integer, Object[]> mapper) {
        int batchSize = properties.getBatchSize();
        for (int start = 0; start < items.size(); start += batchSize) {
            int end = Math.min(start + batchSize, items.size());
            List<Object[]> batchArgs = IntStream.range(start, end)
                .mapToObj(index -> mapper.apply(items.get(index), index))
                .toList();
            jdbcTemplate.batchUpdate(sql, batchArgs);
        }
    }

    private void saveArtifacts(String conversationId, List<Artifact> artifacts) {
        jdbcTemplate.update("DELETE FROM a2a_artifacts WHERE conversation_id = ?", conversationId);
        if (artifacts.isEmpty()) {
            return;
        }

        String sql = "INSERT INTO a2a_artifacts (conversation_id, artifact_json) VALUES (?, ?)";
        batchInsert(sql, artifacts, (artifact, idx) -> new Object[]{conversationId, JsonUtils.toJson(artifact)});
    }

    private void saveMetadata(String conversationId, Map<String, Object> metadata) {
        String metadataJson = metadata.isEmpty() ? null : JsonUtils.toJson(metadata);
        jdbcTemplate.update(
            "UPDATE a2a_conversations SET metadata_json = ? WHERE conversation_id = ?",
            metadataJson,
            conversationId
        );
    }

    @Override
    @Transactional(readOnly = true)
    @Cacheable(value = CacheConfig.TASK_CACHE, key = "#taskId", unless = "#result == null")
    public Task get(String taskId) {
        return loadConversation(taskId)
            .map(conversation -> new Task.Builder()
                .id(taskId)
                .contextId(taskId)
                .status(buildTaskStatus(conversation))
                .history(loadMessages(taskId))
                .artifacts(properties.isStoreArtifacts() ? loadArtifacts(taskId) : List.of())
                .metadata(properties.isStoreMetadata() ? loadMetadata(conversation) : Map.of())
                .build())
            .orElse(null);
    }

    private Optional<ConversationRow> loadConversation(String conversationId) {
        try {
            return Optional.ofNullable(jdbcTemplate.queryForObject(
                "SELECT * FROM a2a_conversations WHERE conversation_id = ?",
                new ConversationRowMapper(),
                conversationId
            ));
        } catch (EmptyResultDataAccessException e) {
            return Optional.empty();
        }
    }

    private List<Message> loadMessages(String conversationId) {
        String sql = """
            SELECT conversation_id, role, content_json, metadata_json
            FROM a2a_messages
            WHERE conversation_id = ?
            ORDER BY sequence_num
            """;
        return jdbcTemplate.query(sql, new MessageRowMapper(), conversationId);
    }

    private List<Artifact> loadArtifacts(String conversationId) {
        String sql = "SELECT artifact_json FROM a2a_artifacts WHERE conversation_id = ?";
        return jdbcTemplate.query(
            sql,
            (rs, rowNum) -> JsonUtils.fromJson(rs.getString("artifact_json"), JsonUtils.ARTIFACT_TYPE).orElse(null),
            conversationId);
    }

    private Map<String, Object> loadMetadata(ConversationRow conversation) {
        return JsonUtils.fromJson(conversation.metadataJson(), JsonUtils.METADATA_MAP_TYPE)
            .orElse(Map.of());
    }

    private TaskStatus buildTaskStatus(ConversationRow conversation) {
        return new TaskStatus(
            TaskState.fromString(conversation.statusState()),
            JsonUtils.fromJson(conversation.statusMessage(), JsonUtils.MESSAGE_TYPE).orElse(null),
            conversation.statusTimestamp()
        );
    }

    @Override
    @Transactional
    @CacheEvict(value = CacheConfig.TASK_CACHE, key = "#taskId")
    public void delete(String taskId) {
        jdbcTemplate.update("DELETE FROM a2a_conversations WHERE conversation_id = ?", taskId);
    }

    @Override
    @Transactional(readOnly = true)
    public boolean isTaskActive(String taskId) {
        String sql = "SELECT status_state FROM a2a_conversations WHERE conversation_id = ?";
        return queryForOptional(sql, String.class, taskId)
            .map(status -> !FINAL_STATES.contains(TaskState.fromString(status)))
            .orElse(false);
    }

    @Override
    @Transactional(readOnly = true)
    public boolean isTaskFinalized(String taskId) {
        String sql = "SELECT finalized_at FROM a2a_conversations WHERE conversation_id = ?";
        return queryForOptional(sql, OffsetDateTime.class, taskId).isPresent();
    }

    private <T> Optional<T> queryForOptional(String sql, Class<T> requiredType, Object... args) {
        try {
            return Optional.ofNullable(jdbcTemplate.queryForObject(sql, requiredType, args));
        } catch (EmptyResultDataAccessException e) {
            return Optional.empty();
        }
    }

    private record ConversationRow(
        String conversationId,
        String statusState,
        String statusMessage,
        OffsetDateTime statusTimestamp,
        OffsetDateTime finalizedAt,
        String metadataJson
    ) {}

    private static class ConversationRowMapper implements RowMapper<ConversationRow> {
        @Override
        public ConversationRow mapRow(ResultSet rs, int rowNum) throws SQLException {
            return new ConversationRow(
                rs.getString("conversation_id"),
                rs.getString("status_state"),
                rs.getString("status_message"),
                rs.getObject("status_timestamp", OffsetDateTime.class),
                rs.getObject("finalized_at", OffsetDateTime.class),
                rs.getString("metadata_json")
            );
        }
    }

    private class MessageRowMapper implements RowMapper<Message> {
        @Override
        public Message mapRow(ResultSet rs, int rowNum) throws SQLException {
            try {
                return new Message.Builder()
                    .contextId(rs.getString("conversation_id"))
                    .role(Message.Role.valueOf(rs.getString("role")))
                    .parts(
                        JsonUtils.fromJson(rs.getString("content_json"), JsonUtils.PARTS_TYPE)
                            .orElseThrow(() -> new SQLException("Message content_json is null"))
                    )
                    .metadata(
                        JsonUtils.fromJson(rs.getString("metadata_json"), JsonUtils.METADATA_MAP_TYPE)
                            .orElse(Map.of())
                    )
                    .build();
            } catch (RuntimeException e) {
                throw new SQLException("Failed to deserialize message", e);
            }
        }
    }
}
