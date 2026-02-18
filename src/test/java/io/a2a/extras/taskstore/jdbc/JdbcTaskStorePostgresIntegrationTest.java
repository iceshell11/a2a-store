package io.a2a.extras.taskstore.jdbc;

import io.a2a.extras.taskstore.A2aTaskStoreProperties;
import io.a2a.spec.*;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import javax.sql.DataSource;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration test that uses the existing PostgreSQL container on port 5432.
 * This test reproduces the JSONB type error when inserting String values into JSONB columns.
 */
class JdbcTaskStorePostgresIntegrationTest {

    private JdbcTemplate jdbcTemplate;
    private JdbcTaskStore taskStore;
    private A2aTaskStoreProperties properties;

    @BeforeEach
    void setUp() {
        // Connect to the existing PostgreSQL container
        DataSource dataSource = createPostgresDataSource();
        jdbcTemplate = new JdbcTemplate(dataSource);
        properties = new A2aTaskStoreProperties();
        taskStore = new JdbcTaskStore(jdbcTemplate, properties);

        // Initialize schema
        initializeSchema();
    }

    @AfterEach
    void tearDown() {
        // Clean up test data
        if (jdbcTemplate != null) {
            jdbcTemplate.execute("DROP TABLE IF EXISTS a2a_artifacts");
            jdbcTemplate.execute("DROP TABLE IF EXISTS a2a_messages");
            jdbcTemplate.execute("DROP TABLE IF EXISTS a2a_conversations");
        }
    }

    private DataSource createPostgresDataSource() {
        DriverManagerDataSource dataSource = new DriverManagerDataSource();
        dataSource.setDriverClassName("org.postgresql.Driver");
        dataSource.setUrl("jdbc:postgresql://localhost:5432/sd_agent");
        dataSource.setUsername("postgres");
        dataSource.setPassword("postgres");
        return dataSource;
    }

    private void initializeSchema() {
        // Create schema using the same SQL as production
        jdbcTemplate.execute("""
            CREATE TABLE IF NOT EXISTS a2a_conversations (
                conversation_id VARCHAR(255) PRIMARY KEY,
                status_state VARCHAR(50) NOT NULL DEFAULT 'submitted',
                status_message JSONB,
                status_timestamp TIMESTAMPTZ DEFAULT CURRENT_TIMESTAMP,
                metadata_json JSONB,
                created_at TIMESTAMPTZ DEFAULT CURRENT_TIMESTAMP,
                updated_at TIMESTAMPTZ DEFAULT CURRENT_TIMESTAMP,
                finalized_at TIMESTAMPTZ
            )
            """);

        jdbcTemplate.execute("""
            CREATE TABLE IF NOT EXISTS a2a_messages (
                message_id SERIAL PRIMARY KEY,
                conversation_id VARCHAR(255) NOT NULL REFERENCES a2a_conversations(conversation_id) ON DELETE CASCADE,
                role VARCHAR(20) NOT NULL,
                content_json JSONB NOT NULL,
                metadata_json JSONB,
                created_at TIMESTAMPTZ DEFAULT CURRENT_TIMESTAMP,
                sequence_num INTEGER NOT NULL
            )
            """);

        jdbcTemplate.execute("""
            CREATE TABLE IF NOT EXISTS a2a_artifacts (
                artifact_id SERIAL PRIMARY KEY,
                conversation_id VARCHAR(255) NOT NULL REFERENCES a2a_conversations(conversation_id) ON DELETE CASCADE,
                artifact_json JSONB NOT NULL,
                created_at TIMESTAMPTZ DEFAULT CURRENT_TIMESTAMP
            )
            """);
    }

    @Test
    void saveAndGetTaskWithJsonbColumns() {
        String conversationId = "conv-postgres-123";
        
        io.a2a.spec.Message message = new io.a2a.spec.Message.Builder()
                .role(io.a2a.spec.Message.Role.USER)
                .parts(new TextPart("Hello from PostgreSQL!"))
                .contextId(conversationId)
                .metadata(Map.of("source", "postgres-test", "priority", 1))
                .build();

        Artifact artifact = new Artifact.Builder()
                .artifactId("art-postgres-001")
                .name("PostgreSQL Test Artifact")
                .parts(new TextPart("Artifact content for PostgreSQL"))
                .build();

        Task task = new Task.Builder()
                .id(conversationId)
                .contextId(conversationId)
                .status(new TaskStatus(TaskState.WORKING, null, OffsetDateTime.now()))
                .history(List.of(message))
                .artifacts(List.of(artifact))
                .metadata(Map.of("test-type", "postgres-jsonb", "runId", 42))
                .build();

        // This should trigger the JSONB error if not handled properly
        // ERROR: column content_json is of type jsonb but expression is of type character varying
        taskStore.save(task);
        
        Task retrieved = taskStore.get(conversationId);

        assertThat(retrieved).isNotNull();
        assertThat(retrieved.getId()).isEqualTo(conversationId);
        assertThat(retrieved.getContextId()).isEqualTo(conversationId);
        assertThat(retrieved.getStatus().state()).isEqualTo(TaskState.WORKING);
        assertThat(retrieved.getHistory()).hasSize(1);
        assertThat(retrieved.getArtifacts()).hasSize(1);
        assertThat(retrieved.getMetadata()).containsEntry("test-type", "postgres-jsonb");
        
        // Verify message metadata was stored correctly
        assertThat(retrieved.getHistory().get(0).getMetadata())
                .containsEntry("source", "postgres-test");
    }

    @Test
    void saveTaskWithComplexJsonbData() {
        String conversationId = "conv-complex-jsonb";
        
        // Create message with nested metadata
        io.a2a.spec.Message message = new io.a2a.spec.Message.Builder()
                .role(io.a2a.spec.Message.Role.AGENT)
                .parts(new TextPart("Complex response"))
                .contextId(conversationId)
                .metadata(Map.of(
                        "nested", Map.of("deep", "value"),
                        "array", List.of(1, 2, 3),
                        "boolean", true,
                        "number", 42.5
                ))
                .build();

        Task task = new Task.Builder()
                .id(conversationId)
                .contextId(conversationId)
                .status(new TaskStatus(TaskState.COMPLETED, null, OffsetDateTime.now()))
                .history(List.of(message))
                .build();

        taskStore.save(task);
        
        Task retrieved = taskStore.get(conversationId);

        assertThat(retrieved).isNotNull();
        assertThat(retrieved.getHistory()).hasSize(1);
        assertThat(retrieved.getStatus().state()).isEqualTo(TaskState.COMPLETED);
        
        Map<String, Object> retrievedMetadata = retrieved.getHistory().get(0).getMetadata();
        assertThat(retrievedMetadata).containsKey("nested");
        assertThat(retrievedMetadata).containsKey("array");
        assertThat(retrievedMetadata).containsKey("boolean");
        assertThat(retrievedMetadata).containsKey("number");
    }

    @Test
    void saveMultipleMessagesWithBatching() {
        properties.setBatchSize(2); // Force multiple batches
        taskStore = new JdbcTaskStore(jdbcTemplate, properties);

        String conversationId = "conv-batch-jsonb";
        
        List<io.a2a.spec.Message> messages = List.of(
                createMessage(io.a2a.spec.Message.Role.USER, "Message 1"),
                createMessage(io.a2a.spec.Message.Role.AGENT, "Response 1"),
                createMessage(io.a2a.spec.Message.Role.USER, "Message 2"),
                createMessage(io.a2a.spec.Message.Role.AGENT, "Response 2"),
                createMessage(io.a2a.spec.Message.Role.USER, "Message 3")
        );

        Task task = new Task.Builder()
                .id(conversationId)
                .contextId(conversationId)
                .status(new TaskStatus(TaskState.WORKING))
                .history(messages)
                .build();

        // Batch insert with JSONB should work correctly
        taskStore.save(task);
        
        Task retrieved = taskStore.get(conversationId);

        assertThat(retrieved.getHistory()).hasSize(5);
        assertThat(retrieved.getHistory().get(0).getRole()).isEqualTo(io.a2a.spec.Message.Role.USER);
        assertThat(retrieved.getHistory().get(4).getRole()).isEqualTo(io.a2a.spec.Message.Role.USER);
    }

    private io.a2a.spec.Message createMessage(io.a2a.spec.Message.Role role, String content) {
        return new io.a2a.spec.Message.Builder()
                .role(role)
                .parts(new TextPart(content))
                .build();
    }
}
