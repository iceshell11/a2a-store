package io.a2a.extras.taskstore.jdbc;

import io.a2a.extras.taskstore.A2aTaskStoreProperties;
import io.a2a.extras.taskstore.repository.ArtifactRepository;
import io.a2a.extras.taskstore.repository.HistoryRepository;
import io.a2a.extras.taskstore.repository.TaskRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.jdbc.JdbcTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.jdbc.Sql;

@JdbcTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.ANY)
@Sql(scripts = "/test-schema-h2.sql", executionPhase = Sql.ExecutionPhase.BEFORE_TEST_METHOD)
@ActiveProfiles("test")
abstract class BaseJdbcIntegrationTest {

    @Autowired
    protected JdbcTemplate jdbcTemplate;

    protected JdbcTaskStore taskStore;
    protected A2aTaskStoreProperties properties;
    protected TaskRepository taskRepository;
    protected HistoryRepository historyRepository;
    protected ArtifactRepository artifactRepository;
    protected JsonbAdapter jsonbAdapter;

    protected void setUpTaskStore() {
        properties = new A2aTaskStoreProperties();
        jsonbAdapter = JsonbAdapterFactory.create(jdbcTemplate);
        taskRepository = new TaskRepository(jdbcTemplate, jsonbAdapter);
        historyRepository = new HistoryRepository(jdbcTemplate, jsonbAdapter, properties);
        artifactRepository = new ArtifactRepository(jdbcTemplate, jsonbAdapter, properties);
        taskStore = new JdbcTaskStore(taskRepository, historyRepository, artifactRepository, properties);

        cleanupTables();
    }

    protected void setUpTaskStoreWithProperties(A2aTaskStoreProperties customProperties) {
        properties = customProperties;
        jsonbAdapter = JsonbAdapterFactory.create(jdbcTemplate);
        taskRepository = new TaskRepository(jdbcTemplate, jsonbAdapter);
        historyRepository = new HistoryRepository(jdbcTemplate, jsonbAdapter, properties);
        artifactRepository = new ArtifactRepository(jdbcTemplate, jsonbAdapter, properties);
        taskStore = new JdbcTaskStore(taskRepository, historyRepository, artifactRepository, properties);

        cleanupTables();
    }

    private void cleanupTables() {
        jdbcTemplate.execute("DELETE FROM a2a_artifacts");
        jdbcTemplate.execute("DELETE FROM a2a_history");
        jdbcTemplate.execute("DELETE FROM a2a_tasks");
    }
}
