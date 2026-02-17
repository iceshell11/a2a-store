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
import java.util.LinkedHashMap;
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
    private static final TypeReference<Artifact> ARTIFACT_TYPE = new TypeReference<>() {};
    private static final TypeReference<Message> MESSAGE_TYPE = new TypeReference<>() {};
    private static final TypeReference<Map<String, Object>> METADATA_MAP_TYPE = new TypeReference<>() {};
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
        String statusState = status.state().asString();
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
            INSERT INTO a2a_messages (conversation_id, role, content_json, metadata_json, sequence_num)
            VALUES (?, ?, CAST(? AS JSON), CAST(? AS JSON), ?)
            """;

        batchInsert(sql, messages, (msg, index) -> new Object[]{
            conversationId,
            msg.getRole().name(),
            toJson(msg.getParts()),
            toJsonOrNull(msg.getMetadata()),
            index
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
        return loadConversation(taskId)
            .map(conversation -> new Task.Builder()
                .id(taskId)
                .contextId(taskId)
                .status(buildTaskStatus(conversation))
                .history(loadMessages(taskId))
                .artifacts(properties.isStoreArtifacts() ? loadArtifacts(taskId) : List.of())
                .metadata(properties.isStoreMetadata() ? loadMetadata(taskId) : Map.of())
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
        return jdbcTemplate.query(sql, new MessageRowMapper(objectMapper), conversationId);
    }

    private List<Artifact> loadArtifacts(String conversationId) {
        String sql = "SELECT artifact_json FROM a2a_artifacts WHERE conversation_id = ?";
        return jdbcTemplate.query(
            sql,
            (rs, rowNum) -> fromJson(rs.getString("artifact_json"), ARTIFACT_TYPE).orElse(null),
            conversationId);
    }

    private Map<String, Object> loadMetadata(String conversationId) {
        String sql = "SELECT \"key\", value_json FROM a2a_metadata WHERE conversation_id = ?";
        List<Map.Entry<String, Object>> rows = jdbcTemplate.query(
            sql,
            (rs, rowNum) -> new AbstractMap.SimpleEntry<>(
                rs.getString("key"),
                fromJson(rs.getString("value_json"), OBJECT_TYPE).orElse(null)
            ),
            conversationId
        );
        if (rows.isEmpty()) {
            return Map.of();
        }

        Map<String, Object> metadata = new LinkedHashMap<>(rows.size());
        rows.forEach(entry -> metadata.put(entry.getKey(), entry.getValue()));
        return metadata;
    }

    private TaskStatus buildTaskStatus(ConversationRow conversation) {
        return new TaskStatus(
            TaskState.fromString(conversation.statusState()),
            readStatusMessage(conversation.statusMessage()).orElse(null),
            conversation.statusTimestamp()
        );
    }

    private Optional<Message> readStatusMessage(String statusMessage) {
        if (statusMessage == null) {
            return Optional.empty();
        }
        String normalized = statusMessage.trim();
        if (normalized.isBlank() || "null".equalsIgnoreCase(normalized) || "\"null\"".equalsIgnoreCase(normalized)) {
            return Optional.empty();
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

    private String toJson(Object obj) {
        try {
            return objectMapper.writeValueAsString(obj);
        } catch (JsonProcessingException e) {
            throw new RuntimeException("Failed to serialize to JSON", e);
        }
    }

    private String toJsonOrNull(Map<String, Object> metadata) {
        if (metadata == null || metadata.isEmpty()) {
            return null;
        }
        return toJson(metadata);
    }

    private <T> Optional<T> fromJson(String json, TypeReference<T> typeRef) {
        try {
            T value = objectMapper.readValue(json, typeRef);
            if (typeRef == OBJECT_TYPE && value instanceof String stringValue) {
                Optional<String> normalized = unwrapJsonString(stringValue);
                if (normalized.isPresent()) {
                    @SuppressWarnings("unchecked")
                    T unwrapped = (T) normalized.get();
                    return Optional.of(unwrapped);
                }
            }
            return Optional.ofNullable(value);
        } catch (JsonProcessingException e) {
            Optional<String> unwrappedJson = unwrapJsonString(json);
            if (unwrappedJson.isEmpty()) {
                return Optional.empty();
            }
            try {
                return Optional.ofNullable(objectMapper.readValue(unwrappedJson.get(), typeRef));
            } catch (JsonProcessingException nestedException) {
                throw new RuntimeException("Failed to deserialize JSON", e);
            }
        }
    }

    private Optional<String> unwrapJsonString(String json) {
        try {
            String unwrapped = objectMapper.readValue(json, String.class);
            if (unwrapped == null) {
                return Optional.empty();
            }
            String normalized = unwrapped.trim();
            if (normalized.isBlank() || "null".equalsIgnoreCase(normalized)) {
                return Optional.empty();
            }
            return Optional.of(normalized);
        } catch (JsonProcessingException ignored) {
            return Optional.empty();
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
                String conversationId = rs.getString("conversation_id");
                Message.Role role = Message.Role.valueOf(rs.getString("role"));
                String contentJson = rs.getString("content_json");
                String metadataJson = rs.getString("metadata_json");
                return parseMessage(conversationId, contentJson, metadataJson, role);
            } catch (JsonProcessingException e) {
                throw new SQLException("Failed to deserialize message", e);
            }
        }

        private Message parseMessage(
            String conversationId,
            String contentJson,
            String metadataJson,
            Message.Role fallbackRole
        ) throws JsonProcessingException {
            Map<String, Object> metadata = parseMetadata(metadataJson);

            Optional<List<Part<?>>> parts = parsePartsOptional(contentJson);
            if (parts.isPresent()) {
                Message.Builder builder = new Message.Builder()
                    .role(fallbackRole)
                    .parts(parts.get())
                    .contextId(conversationId)
                    .metadata(metadata);
                return builder.build();
            }

            Optional<Message> asMessage = parseAsMessage(contentJson)
                .or(() -> unwrapJsonString(contentJson).flatMap(this::parseAsMessage));
            if (asMessage.isPresent()) {
                Message parsedMessage = asMessage.get();
                Message.Builder builder = new Message.Builder(parsedMessage)
                    .contextId(conversationId);
                if (parsedMessage.getRole() == null) {
                    builder.role(fallbackRole);
                }
                if (parsedMessage.getMetadata() == null || parsedMessage.getMetadata().isEmpty()) {
                    builder.metadata(metadata);
                }
                return builder.build();
            }

            List<Part<?>> fallbackParts = parseParts(contentJson);
            Message.Builder builder = new Message.Builder()
                .role(fallbackRole)
                .parts(fallbackParts)
                .contextId(conversationId)
                .metadata(metadata);
            return builder.build();
        }

        private Map<String, Object> parseMetadata(String metadataJson) throws JsonProcessingException {
            if (metadataJson == null) {
                return Map.of();
            }
            String normalized = metadataJson.trim();
            if (normalized.isBlank() || "null".equalsIgnoreCase(normalized) || "\"null\"".equalsIgnoreCase(normalized)) {
                return Map.of();
            }
            Object parsed = objectMapper.readValue(normalized, Object.class);
            if (parsed instanceof String wrappedJson) {
                if (wrappedJson.isBlank() || "null".equalsIgnoreCase(wrappedJson.trim())) {
                    return Map.of();
                }
                return objectMapper.readValue(wrappedJson, METADATA_MAP_TYPE);
            }
            return objectMapper.convertValue(parsed, METADATA_MAP_TYPE);
        }

        private Optional<Message> parseAsMessage(String json) {
            try {
                return Optional.ofNullable(objectMapper.readValue(json, MESSAGE_TYPE));
            } catch (JsonProcessingException ignored) {
                return Optional.empty();
            }
        }

        private Optional<List<Part<?>>> parsePartsOptional(String contentJson) {
            try {
                return Optional.of(parseParts(contentJson));
            } catch (JsonProcessingException e) {
                return Optional.empty();
            }
        }

        private List<Part<?>> parseParts(String contentJson) throws JsonProcessingException {
            try {
                return objectMapper.readValue(contentJson, PARTS_TYPE);
            } catch (JsonProcessingException e) {
                Optional<String> unwrapped = unwrapJsonString(contentJson);
                if (unwrapped.isEmpty()) {
                    throw e;
                }
                return objectMapper.readValue(unwrapped.get(), PARTS_TYPE);
            }
        }

        private Optional<String> unwrapJsonString(String json) {
            try {
                String unwrapped = objectMapper.readValue(json, String.class);
                if (unwrapped == null) {
                    return Optional.empty();
                }
                String normalized = unwrapped.trim();
                return normalized.isBlank() ? Optional.empty() : Optional.of(normalized);
            } catch (JsonProcessingException ignored) {
                return Optional.empty();
            }
        }
    }
}
