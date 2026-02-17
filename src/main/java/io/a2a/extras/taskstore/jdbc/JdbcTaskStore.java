package io.a2a.extras.taskstore.jdbc;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import io.a2a.extras.taskstore.A2aTaskStoreProperties;
import io.a2a.server.tasks.TaskStateProvider;
import io.a2a.server.tasks.TaskStore;
import io.a2a.spec.Artifact;
import io.a2a.spec.Message;
import io.a2a.spec.Part;
import io.a2a.spec.Task;
import io.a2a.spec.TaskState;
import io.a2a.spec.TaskStatus;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.transaction.annotation.Transactional;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.AbstractMap;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.BiFunction;
import java.util.stream.IntStream;

public class JdbcTaskStore implements TaskStore, TaskStateProvider {

    private static final Set<TaskState> FINAL_STATES = EnumSet.of(
        TaskState.COMPLETED, TaskState.CANCELED, TaskState.FAILED, TaskState.REJECTED
    );
    private static final TypeReference<Artifact> ARTIFACT_TYPE = new TypeReference<>() {};
    private static final TypeReference<Message> MESSAGE_TYPE = new TypeReference<>() {};
    private static final TypeReference<List<Part<?>>> PARTS_TYPE = new TypeReference<>() {};
    private static final TypeReference<Object> OBJECT_TYPE = new TypeReference<>() {};
    private static final String UPDATE_CONVERSATION_SQL = """
        UPDATE a2a_conversations
        SET status_state = ?, status_message = CAST(? AS JSON), status_timestamp = ?, finalized_at = ?
        WHERE conversation_id = ?
        """;
    private static final String INSERT_CONVERSATION_SQL = """
        INSERT INTO a2a_conversations
        (conversation_id, status_state, status_message, status_timestamp, finalized_at)
        VALUES (?, ?, CAST(? AS JSON), ?, ?)
        """;

    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;
    private final A2aTaskStoreProperties properties;

    public JdbcTaskStore(JdbcTemplate jdbcTemplate, A2aTaskStoreProperties properties) {
        this.jdbcTemplate = jdbcTemplate;
        this.properties = properties;
        this.objectMapper = new ObjectMapper().registerModule(new JavaTimeModule());
    }

    @Override
    @Transactional
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
        String statusState = status.state().name().toLowerCase();
        String statusMessage = toJson(status.message());
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
        jdbcTemplate.update("DELETE FROM a2a_messages WHERE conversation_id = ?", conversationId);
        if (messages.isEmpty()) {
            return;
        }

        String sql = """
            INSERT INTO a2a_messages (conversation_id, role, content_json, sequence_num)
            VALUES (?, ?, CAST(? AS JSON), ?)
            """;

        batchInsert(sql, messages, (msg, index) -> new Object[]{
            conversationId, msg.getRole().name(), toJson(msg.getParts()), index
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

        String sql = "INSERT INTO a2a_artifacts (conversation_id, artifact_json) VALUES (?, CAST(? AS JSON))";
        batchInsert(sql, artifacts, (artifact, idx) -> new Object[]{conversationId, toJson(artifact)});
    }

    private void saveMetadata(String conversationId, Map<String, Object> metadata) {
        jdbcTemplate.update("DELETE FROM a2a_metadata WHERE conversation_id = ?", conversationId);
        if (metadata.isEmpty()) {
            return;
        }

        String sql = """
            INSERT INTO a2a_metadata (conversation_id, "key", value_json)
            VALUES (?, ?, CAST(? AS JSON))
            """;

        List<Map.Entry<String, Object>> entries = metadata.entrySet().stream().toList();
        batchInsert(sql, entries, (entry, idx) -> new Object[]{conversationId, entry.getKey(), toJson(entry.getValue())});
    }

    @Override
    @Transactional(readOnly = true)
    public Task get(String taskId) {
        ConversationRow conversation = loadConversation(taskId);
        if (conversation == null) return null;

        return new Task.Builder()
            .id(taskId)
            .contextId(taskId)
            .status(buildTaskStatus(conversation))
            .history(loadMessages(taskId))
            .artifacts(properties.isStoreArtifacts() ? loadArtifacts(taskId) : List.of())
            .metadata(properties.isStoreMetadata() ? loadMetadata(taskId) : Map.of())
            .build();
    }

    private ConversationRow loadConversation(String conversationId) {
        try {
            return jdbcTemplate.queryForObject(
                "SELECT * FROM a2a_conversations WHERE conversation_id = ?",
                new ConversationRowMapper(),
                conversationId
            );
        } catch (EmptyResultDataAccessException e) {
            return null;
        }
    }

    private List<Message> loadMessages(String conversationId) {
        String sql = "SELECT role, content_json FROM a2a_messages WHERE conversation_id = ? ORDER BY sequence_num";
        return jdbcTemplate.query(sql, new MessageRowMapper(objectMapper), conversationId);
    }

    private List<Artifact> loadArtifacts(String conversationId) {
        String sql = "SELECT artifact_json FROM a2a_artifacts WHERE conversation_id = ?";
        List<Artifact> artifacts = jdbcTemplate.query(
            sql,
            (rs, rowNum) -> fromJson(rs.getString("artifact_json"), ARTIFACT_TYPE),
            conversationId);
        return artifacts.isEmpty() ? null : artifacts;
    }

    private Map<String, Object> loadMetadata(String conversationId) {
        String sql = "SELECT \"key\", value_json FROM a2a_metadata WHERE conversation_id = ?";
        List<Map.Entry<String, Object>> rows = jdbcTemplate.query(
            sql,
            (rs, rowNum) -> new AbstractMap.SimpleEntry<>(
                rs.getString("key"),
                fromJson(rs.getString("value_json"), OBJECT_TYPE)
            ),
            conversationId
        );
        if (rows.isEmpty()) {
            return null;
        }

        Map<String, Object> metadata = new HashMap<>();
        rows.forEach(entry -> metadata.put(entry.getKey(), entry.getValue()));
        return metadata;
    }

    private TaskStatus buildTaskStatus(ConversationRow conversation) {
        return new TaskStatus(
            TaskState.fromString(conversation.statusState()),
            readStatusMessage(conversation.statusMessage()),
            conversation.statusTimestamp()
        );
    }

    private Message readStatusMessage(String statusMessage) {
        if (statusMessage == null) {
            return null;
        }
        String normalized = statusMessage.trim();
        if (normalized.isBlank() || "null".equalsIgnoreCase(normalized) || "\"null\"".equalsIgnoreCase(normalized)) {
            return null;
        }
        return fromJson(normalized, MESSAGE_TYPE);
    }

    @Override
    @Transactional
    public void delete(String taskId) {
        jdbcTemplate.update("DELETE FROM a2a_conversations WHERE conversation_id = ?", taskId);
    }

    @Override
    @Transactional(readOnly = true)
    public boolean isTaskActive(String taskId) {
        String sql = "SELECT status_state FROM a2a_conversations WHERE conversation_id = ?";
        String status = queryForObjectOrNull(sql, String.class, taskId);
        if (status == null) {
            return false;
        }
        return !FINAL_STATES.contains(TaskState.fromString(status));
    }

    @Override
    @Transactional(readOnly = true)
    public boolean isTaskFinalized(String taskId) {
        String sql = "SELECT finalized_at FROM a2a_conversations WHERE conversation_id = ?";
        return queryForObjectOrNull(sql, OffsetDateTime.class, taskId) != null;
    }

    private <T> T queryForObjectOrNull(String sql, Class<T> requiredType, Object... args) {
        try {
            return jdbcTemplate.queryForObject(sql, requiredType, args);
        } catch (EmptyResultDataAccessException e) {
            return null;
        }
    }

    private String toJson(Object obj) {
        try {
            return objectMapper.writeValueAsString(obj);
        } catch (JsonProcessingException e) {
            throw new RuntimeException("Failed to serialize to JSON", e);
        }
    }

    private <T> T fromJson(String json, TypeReference<T> typeRef) {
        try {
            T value = objectMapper.readValue(json, typeRef);
            if (typeRef == OBJECT_TYPE && value instanceof String stringValue) {
                String normalized = unwrapJsonString(stringValue);
                if (normalized != null) {
                    @SuppressWarnings("unchecked")
                    T unwrapped = (T) normalized;
                    return unwrapped;
                }
            }
            return value;
        } catch (JsonProcessingException e) {
            String unwrappedJson = unwrapJsonString(json);
            if (unwrappedJson == null) {
                return null;
            }
            try {
                return objectMapper.readValue(unwrappedJson, typeRef);
            } catch (JsonProcessingException nestedException) {
                throw new RuntimeException("Failed to deserialize JSON", e);
            }
        }
    }

    private String unwrapJsonString(String json) {
        try {
            String unwrapped = objectMapper.readValue(json, String.class);
            if (unwrapped == null) {
                return null;
            }
            String normalized = unwrapped.trim();
            if (normalized.isBlank() || "null".equalsIgnoreCase(normalized)) {
                return null;
            }
            return normalized;
        } catch (JsonProcessingException ignored) {
            return null;
        }
    }

    // Row mappers and data classes
    private record ConversationRow(
        String conversationId,
        String statusState,
        String statusMessage,
        OffsetDateTime statusTimestamp,
        OffsetDateTime finalizedAt
    ) {}

    private static class ConversationRowMapper implements RowMapper<ConversationRow> {
        @Override
        public ConversationRow mapRow(ResultSet rs, int rowNum) throws SQLException {
            return new ConversationRow(
                rs.getString("conversation_id"),
                rs.getString("status_state"),
                rs.getString("status_message"),
                rs.getObject("status_timestamp", OffsetDateTime.class),
                rs.getObject("finalized_at", OffsetDateTime.class)
            );
        }
    }

    private static class MessageRowMapper implements RowMapper<Message> {
        private final ObjectMapper objectMapper;
        
        MessageRowMapper(ObjectMapper objectMapper) {
            this.objectMapper = objectMapper;
        }
        
        @Override
        public Message mapRow(ResultSet rs, int rowNum) throws SQLException {
            try {
                String role = rs.getString("role");
                String contentJson = rs.getString("content_json");

                List<Part<?>> parts = parseParts(contentJson);

                return new Message.Builder()
                    .role(Message.Role.valueOf(role))
                    .parts(parts)
                    .build();
            } catch (JsonProcessingException e) {
                throw new SQLException("Failed to deserialize message", e);
            }
        }

        private List<Part<?>> parseParts(String contentJson) throws JsonProcessingException {
            try {
                return objectMapper.readValue(contentJson, PARTS_TYPE);
            } catch (JsonProcessingException e) {
                String unwrapped = unwrapJsonString(contentJson);
                if (unwrapped == null) {
                    throw e;
                }
                return objectMapper.readValue(unwrapped, PARTS_TYPE);
            }
        }

        private String unwrapJsonString(String json) {
            try {
                String unwrapped = objectMapper.readValue(json, String.class);
                if (unwrapped == null) {
                    return null;
                }
                String normalized = unwrapped.trim();
                return normalized.isBlank() ? null : normalized;
            } catch (JsonProcessingException ignored) {
                return null;
            }
        }
    }
}
