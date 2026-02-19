package io.a2a.extras.taskstore.repository;

import io.a2a.extras.taskstore.A2aTaskStoreProperties;
import io.a2a.extras.taskstore.jdbc.JsonUtils;
import io.a2a.extras.taskstore.jdbc.JsonbAdapter;
import io.a2a.extras.taskstore.jdbc.SqlConstants;
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
            jdbcTemplate.update(SqlConstants.DELETE_HISTORY, taskId);
            return;
        }

        int existingCount = jdbcTemplate.queryForObject(SqlConstants.COUNT_HISTORY, Integer.class, taskId);

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
            jdbcTemplate.batchUpdate(SqlConstants.INSERT_HISTORY, batchArgs);
        }
    }

    public List<Message> findByTaskId(String taskId) {
        return jdbcTemplate.query(SqlConstants.SELECT_HISTORY, new HistoryRowMapper(taskId), taskId);
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
                        .messageId(rs.getString(SqlConstants.COL_MESSAGE_ID))
                        .contextId(taskId)
                        .taskId(taskId)
                        .role(Message.Role.valueOf(rs.getString(SqlConstants.COL_ROLE)))
                        .parts(
                                JsonUtils.fromJson(rs.getString(SqlConstants.COL_CONTENT_JSON), JsonUtils.PARTS_TYPE)
                                        .orElseThrow(() -> new SQLException("History content_json is null"))
                        )
                        .metadata(
                                JsonUtils.fromJson(rs.getString(SqlConstants.COL_METADATA_JSON), JsonUtils.METADATA_MAP_TYPE)
                                        .orElse(Map.of())
                        )
                        .build();
            } catch (RuntimeException e) {
                throw new SQLException("Failed to deserialize history entry", e);
            }
        }
    }
}
