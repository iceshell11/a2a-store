package io.a2a.extras.taskstore.repository;

import io.a2a.extras.taskstore.A2aTaskStoreProperties;
import io.a2a.extras.taskstore.jdbc.JsonUtils;
import io.a2a.extras.taskstore.jdbc.JsonbAdapter;
import io.a2a.spec.Artifact;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Map;
import java.util.function.BiFunction;
import java.util.stream.IntStream;

public class ArtifactRepository {

    private static final String DELETE_ARTIFACTS_SQL = "DELETE FROM a2a_artifacts WHERE task_id = ?";

    private static final String INSERT_ARTIFACT_SQL = """
            INSERT INTO a2a_artifacts
            (task_id, artifact_id, name, description, content_json, metadata_json, extensions_json, sequence_num)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?)
            """;

    private static final String SELECT_ARTIFACTS_SQL = """
            SELECT task_id, artifact_id, name, description, content_json, metadata_json, extensions_json
            FROM a2a_artifacts
            WHERE task_id = ?
            ORDER BY sequence_num
            """;

    private final JdbcTemplate jdbcTemplate;
    private final JsonbAdapter jsonbAdapter;
    private final int batchSize;

    public ArtifactRepository(JdbcTemplate jdbcTemplate, JsonbAdapter jsonbAdapter, A2aTaskStoreProperties properties) {
        this.jdbcTemplate = jdbcTemplate;
        this.jsonbAdapter = jsonbAdapter;
        this.batchSize = properties.getBatchSize();
    }

    public void saveAll(String taskId, List<Artifact> artifacts) {
        jdbcTemplate.update(DELETE_ARTIFACTS_SQL, taskId);
        if (artifacts.isEmpty()) {
            return;
        }

        batchInsert(artifacts, (artifact, idx) -> new Object[]{
                taskId,
                artifact.artifactId(),
                artifact.name(),
                artifact.description(),
                jsonbAdapter.adapt(JsonUtils.toJson(artifact.parts())),
                jsonbAdapter.adapt(JsonUtils.toJson(artifact.metadata())),
                jsonbAdapter.adapt(JsonUtils.toJson(artifact.extensions())),
                idx
        });
    }

    private <T> void batchInsert(List<T> items, BiFunction<T, Integer, Object[]> mapper) {
        for (int start = 0; start < items.size(); start += batchSize) {
            int end = Math.min(start + batchSize, items.size());
            List<Object[]> batchArgs = IntStream.range(start, end)
                    .mapToObj(index -> mapper.apply(items.get(index), index))
                    .toList();
            jdbcTemplate.batchUpdate(INSERT_ARTIFACT_SQL, batchArgs);
        }
    }

    public List<Artifact> findByTaskId(String taskId) {
        return jdbcTemplate.query(SELECT_ARTIFACTS_SQL, new ArtifactRowMapper(), taskId);
    }

    private static class ArtifactRowMapper implements RowMapper<Artifact> {
        @Override
        public Artifact mapRow(ResultSet rs, int rowNum) throws SQLException {
            try {
                return new Artifact.Builder()
                        .artifactId(rs.getString("artifact_id"))
                        .name(rs.getString("name"))
                        .description(rs.getString("description"))
                        .parts(
                                JsonUtils.fromJson(rs.getString("content_json"), JsonUtils.PARTS_TYPE)
                                        .orElseThrow(() -> new SQLException("Artifact content_json is null"))
                        )
                        .metadata(
                                JsonUtils.fromJson(rs.getString("metadata_json"), JsonUtils.METADATA_MAP_TYPE)
                                        .orElse(Map.of())
                        )
                        .extensions(
                                JsonUtils.fromJson(rs.getString("extensions_json"), JsonUtils.EXTENSIONS_TYPE)
                                        .orElse(List.of())
                        )
                        .build();
            } catch (RuntimeException e) {
                throw new SQLException("Failed to deserialize artifact", e);
            }
        }
    }
}
