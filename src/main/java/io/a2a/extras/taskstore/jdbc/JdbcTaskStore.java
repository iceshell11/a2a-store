package io.a2a.extras.taskstore.jdbc;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import io.a2a.extras.taskstore.A2aTaskStoreProperties;
import io.a2a.server.tasks.TaskStateProvider;
import io.a2a.server.tasks.TaskStore;
import io.a2a.spec.*;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowCallbackHandler;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.transaction.annotation.Transactional;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.*;
import java.util.function.BiFunction;

public class JdbcTaskStore implements TaskStore, TaskStateProvider {

    private static final Set<TaskState> FINAL_STATES = EnumSet.of(
        TaskState.COMPLETED, TaskState.CANCELED, TaskState.FAILED, TaskState.REJECTED
    );

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
        String sql = """
            INSERT INTO a2a_conversations
            (conversation_id, status_state, status_message, status_timestamp, finalized_at)
            VALUES (?, ?, CAST(? AS JSON), ?, ?)
            ON CONFLICT (conversation_id) DO UPDATE SET
            status_state = EXCLUDED.status_state,
            status_message = EXCLUDED.status_message,
            status_timestamp = EXCLUDED.status_timestamp,
            finalized_at = EXCLUDED.finalized_at
            """;

        jdbcTemplate.update(sql,
            task.getContextId(),
            status.state().name().toLowerCase(),
            toJson(status.message()),
            status.timestamp(),
            FINAL_STATES.contains(status.state()) ? OffsetDateTime.now() : null
        );
    }

    private void saveMessages(String conversationId, List<Message> messages) {
        jdbcTemplate.update("DELETE FROM a2a_messages WHERE conversation_id = ?", conversationId);
        if (messages.isEmpty()) return;

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
        List<Object[]> batchArgs = new ArrayList<>();

        for (int i = 0; i < items.size(); i++) {
            batchArgs.add(mapper.apply(items.get(i), i));
            if (batchArgs.size() >= batchSize) {
                jdbcTemplate.batchUpdate(sql, batchArgs);
                batchArgs.clear();
            }
        }
        if (!batchArgs.isEmpty()) {
            jdbcTemplate.batchUpdate(sql, batchArgs);
        }
    }

    private void saveArtifacts(String conversationId, List<Artifact> artifacts) {
        jdbcTemplate.update("DELETE FROM a2a_artifacts WHERE conversation_id = ?", conversationId);
        if (artifacts.isEmpty()) return;

        String sql = "INSERT INTO a2a_artifacts (conversation_id, artifact_json) VALUES (?, CAST(? AS JSON))";
        batchInsert(sql, artifacts, (artifact, idx) -> new Object[]{conversationId, toJson(artifact)});
    }

    private void saveMetadata(String conversationId, Map<String, Object> metadata) {
        jdbcTemplate.update("DELETE FROM a2a_metadata WHERE conversation_id = ?", conversationId);
        if (metadata.isEmpty()) return;

        String sql = """
            INSERT INTO a2a_metadata (conversation_id, "key", value_json)
            VALUES (?, ?, CAST(? AS JSON))
            ON CONFLICT (conversation_id, "key") DO UPDATE SET value_json = EXCLUDED.value_json
            """;

        List<Map.Entry<String, Object>> entries = new ArrayList<>(metadata.entrySet());
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
            .artifacts(properties.isStoreArtifacts() ? loadArtifacts(taskId) : null)
            .metadata(properties.isStoreMetadata() ? loadMetadata(taskId) : null)
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
        List<Artifact> artifacts = jdbcTemplate.query(sql,
            (rs, rowNum) -> fromJson(rs.getString("artifact_json"), new TypeReference<Artifact>() {}),
            conversationId);
        return artifacts.isEmpty() ? null : artifacts;
    }

    private Map<String, Object> loadMetadata(String conversationId) {
        String sql = "SELECT \"key\", value_json FROM a2a_metadata WHERE conversation_id = ?";
        Map<String, Object> metadata = new HashMap<>();
        jdbcTemplate.query(sql, (RowCallbackHandler) rs ->
            metadata.put(rs.getString("key"), fromJson(rs.getString("value_json"), new TypeReference<>() {})),
            conversationId
        );
        return metadata.isEmpty() ? null : metadata;
    }

    private TaskStatus buildTaskStatus(ConversationRow conversation) {
        return new TaskStatus(
            TaskState.fromString(conversation.statusState()),
            conversation.statusMessage() != null
                ? fromJson(conversation.statusMessage(), new TypeReference<Message>() {})
                : null,
            conversation.statusTimestamp()
        );
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
        try {
            String status = jdbcTemplate.queryForObject(sql, String.class, taskId);
            return status != null && !FINAL_STATES.contains(TaskState.fromString(status));
        } catch (EmptyResultDataAccessException e) {
            return false;
        }
    }

    @Override
    @Transactional(readOnly = true)
    public boolean isTaskFinalized(String taskId) {
        String sql = "SELECT finalized_at FROM a2a_conversations WHERE conversation_id = ?";
        try {
            return jdbcTemplate.queryForObject(sql, OffsetDateTime.class, taskId) != null;
        } catch (EmptyResultDataAccessException e) {
            return false;
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
            return objectMapper.readValue(json, typeRef);
        } catch (JsonProcessingException e) {
            throw new RuntimeException("Failed to deserialize JSON", e);
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
                
                List<Part<?>> parts = objectMapper.readValue(contentJson, new TypeReference<>() {});
                
                return new Message.Builder()
                    .role(Message.Role.valueOf(role))
                    .parts(parts)
                    .build();
            } catch (JsonProcessingException e) {
                throw new SQLException("Failed to deserialize message", e);
            }
        }
    }
}
