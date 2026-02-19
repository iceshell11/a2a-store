package io.a2a.extras.taskstore.repository;

import io.a2a.extras.taskstore.A2aTaskStoreProperties;
import io.a2a.extras.taskstore.jdbc.JsonUtils;
import io.a2a.extras.taskstore.jdbc.JsonbAdapter;
import io.a2a.spec.Message;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.BiFunction;
import java.util.stream.IntStream;

public class HistoryRepository {

    private static final String DELETE_HISTORY_SQL = "DELETE FROM a2a_history WHERE task_id = ?";

    private static final String COUNT_HISTORY_SQL = "SELECT COUNT(*) FROM a2a_history WHERE task_id = ?";

    private static final String INSERT_HISTORY_SQL = """
            INSERT INTO a2a_history (task_id, message_id, role, content_json, metadata_json, sequence_num)
            VALUES (?, ?, ?, ?, ?, ?)
            """;

    private static final String SELECT_HISTORY_SQL = """
            SELECT task_id, message_id, role, content_json, metadata_json
            FROM a2a_history
            WHERE task_id = ?
            ORDER BY sequence_num
            """;

    private final JdbcTemplate jdbcTemplate;
    private final JsonbAdapter jsonbAdapter;
    private final int batchSize;

    public HistoryRepository(JdbcTemplate jdbcTemplate, JsonbAdapter jsonbAdapter, A2aTaskStoreProperties properties) {
        this.jdbcTemplate = jdbcTemplate;
        this.jsonbAdapter = jsonbAdapter;
        this.batchSize = properties.getBatchSize();
    }

    public void saveAll(String taskId, List<Message> messages) {
        if (messages.isEmpty()) {
            jdbcTemplate.update(DELETE_HISTORY_SQL, taskId);
            return;
        }

        int existingCount = jdbcTemplate.queryForObject(COUNT_HISTORY_SQL, Integer.class, taskId);

        if (existingCount == 0) {
            insertAll(taskId, messages);
        } else if (messages.size() > existingCount) {
            List<Message> newMessages = messages.subList(existingCount, messages.size());
            insertStartingFromSequence(taskId, newMessages, existingCount);
        }
    }

    private void insertAll(String taskId, List<Message> messages) {
        batchInsert(messages, (msg, index) -> messageToObjectArray(taskId, msg, index));
    }

    private void insertStartingFromSequence(String taskId, List<Message> messages, int startSequence) {
        batchInsert(messages, (msg, index) -> messageToObjectArray(taskId, msg, startSequence + index));
    }

    private Object[] messageToObjectArray(String taskId, Message msg, Integer index) {
        return new Object[]{
                taskId,
                Optional.ofNullable(msg.getMessageId()).orElseGet(() -> generateMessageId(taskId, index)),
                msg.getRole().name(),
                jsonbAdapter.adapt(JsonUtils.toJson(msg.getParts())),
                jsonbAdapter.adapt(JsonUtils.toJson(msg.getMetadata())),
                index
        };
    }

    private String generateMessageId(String taskId, int index) {
        return taskId + "-msg-" + index;
    }

    private <T> void batchInsert(List<T> items, BiFunction<T, Integer, Object[]> mapper) {
        for (int start = 0; start < items.size(); start += batchSize) {
            int end = Math.min(start + batchSize, items.size());
            List<Object[]> batchArgs = IntStream.range(start, end)
                    .mapToObj(index -> mapper.apply(items.get(index), index))
                    .toList();
            jdbcTemplate.batchUpdate(INSERT_HISTORY_SQL, batchArgs);
        }
    }

    public List<Message> findByTaskId(String taskId) {
        return jdbcTemplate.query(SELECT_HISTORY_SQL, new HistoryRowMapper(taskId), taskId);
    }

    private static class HistoryRowMapper implements RowMapper<Message> {
        private final String taskId;

        HistoryRowMapper(String taskId) {
            this.taskId = taskId;
        }

        @Override
        public Message mapRow(ResultSet rs, int rowNum) throws SQLException {
            try {
                return new Message.Builder()
                        .messageId(rs.getString("message_id"))
                        .contextId(taskId)
                        .taskId(taskId)
                        .role(Message.Role.valueOf(rs.getString("role")))
                        .parts(
                                JsonUtils.fromJson(rs.getString("content_json"), JsonUtils.PARTS_TYPE)
                                        .orElseThrow(() -> new SQLException("History content_json is null"))
                        )
                        .metadata(
                                JsonUtils.fromJson(rs.getString("metadata_json"), JsonUtils.METADATA_MAP_TYPE)
                                        .orElse(Map.of())
                        )
                        .build();
            } catch (RuntimeException e) {
                throw new SQLException("Failed to deserialize history entry", e);
            }
        }
    }
}
