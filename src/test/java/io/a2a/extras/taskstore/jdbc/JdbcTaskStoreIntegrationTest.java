package io.a2a.extras.taskstore.jdbc;

import io.a2a.extras.taskstore.A2aTaskStoreProperties;
import io.a2a.spec.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.jdbc.JdbcTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.jdbc.Sql;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

@JdbcTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.ANY)
@Sql(scripts = "/test-schema-h2.sql", executionPhase = Sql.ExecutionPhase.BEFORE_TEST_METHOD)
@ActiveProfiles("test")
class JdbcTaskStoreIntegrationTest {

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private JdbcTaskStore taskStore;
    private A2aTaskStoreProperties properties;

    @BeforeEach
    void setUp() {
        properties = new A2aTaskStoreProperties();
        taskStore = new JdbcTaskStore(jdbcTemplate, properties);

        // Clean up tables before each test
        jdbcTemplate.execute("DELETE FROM a2a_metadata");
        jdbcTemplate.execute("DELETE FROM a2a_artifacts");
        jdbcTemplate.execute("DELETE FROM a2a_messages");
        jdbcTemplate.execute("DELETE FROM a2a_conversations");
    }

    @Test
    void saveAndGetTask() {
        String conversationId = "conv-123";
        Task task = createSampleTask(conversationId);

        taskStore.save(task);
        Task retrieved = taskStore.get(conversationId);

        assertThat(retrieved).isNotNull();
        assertThat(retrieved.getId()).isEqualTo(conversationId);
        assertThat(retrieved.getContextId()).isEqualTo(conversationId);
        assertThat(retrieved.getStatus().state()).isEqualTo(TaskState.WORKING);
        assertThat(retrieved.getHistory()).hasSize(1);
        assertThat(retrieved.getArtifacts()).hasSize(1);
        assertThat(retrieved.getMetadata()).containsEntry("key1", "value1");
    }

    @Test
    void saveTaskWithoutArtifactsAndMetadata() {
        properties.setStoreArtifacts(false);
        properties.setStoreMetadata(false);
        taskStore = new JdbcTaskStore(jdbcTemplate, properties);

        String conversationId = "conv-456";
        Task task = createSampleTask(conversationId);

        taskStore.save(task);
        Task retrieved = taskStore.get(conversationId);

        assertThat(retrieved).isNotNull();
        assertThat(retrieved.getHistory()).hasSize(1);
        assertThat(retrieved.getArtifacts()).isEmpty();
        assertThat(retrieved.getMetadata()).isEmpty();
    }

    @Test
    void updateExistingTask() {
        String conversationId = "conv-789";
        Task task = createSampleTask(conversationId);

        taskStore.save(task);

        // Update with new status and additional message
        TaskStatus newStatus = new TaskStatus(TaskState.COMPLETED, null, OffsetDateTime.now());
        io.a2a.spec.Message newMessage = new io.a2a.spec.Message.Builder()
                .role(io.a2a.spec.Message.Role.AGENT)
                .parts(new TextPart("Response"))
                .contextId(conversationId)
                .build();

        Task updatedTask = new Task.Builder(task)
                .status(newStatus)
                .history(List.of(task.getHistory().get(0), newMessage))
                .build();

        taskStore.save(updatedTask);

        Task retrieved = taskStore.get(conversationId);
        assertThat(retrieved.getStatus().state()).isEqualTo(TaskState.COMPLETED);
        assertThat(retrieved.getHistory()).hasSize(2);
    }

    @Test
    void deleteTask() {
        String conversationId = "conv-delete";
        Task task = createSampleTask(conversationId);

        taskStore.save(task);
        assertThat(taskStore.get(conversationId)).isNotNull();

        taskStore.delete(conversationId);
        assertThat(taskStore.get(conversationId)).isNull();
    }

    @Test
    void getNonExistentTask() {
        Task retrieved = taskStore.get("non-existent-id");
        assertThat(retrieved).isNull();
    }

    @Test
    void isTaskActive() {
        String workingId = "conv-working";
        String completedId = "conv-completed";

        Task workingTask = new Task.Builder()
                .id(workingId)
                .contextId(workingId)
                .status(new TaskStatus(TaskState.WORKING))
                .build();

        Task completedTask = new Task.Builder()
                .id(completedId)
                .contextId(completedId)
                .status(new TaskStatus(TaskState.COMPLETED))
                .build();

        taskStore.save(workingTask);
        taskStore.save(completedTask);

        assertThat(taskStore.isTaskActive(workingId)).isTrue();
        assertThat(taskStore.isTaskActive(completedId)).isFalse();
    }

    @Test
    void isTaskFinalized() {
        String workingId = "conv-active";
        String completedId = "conv-done";

        Task workingTask = new Task.Builder()
                .id(workingId)
                .contextId(workingId)
                .status(new TaskStatus(TaskState.WORKING))
                .build();

        Task completedTask = new Task.Builder()
                .id(completedId)
                .contextId(completedId)
                .status(new TaskStatus(TaskState.COMPLETED))
                .build();

        taskStore.save(workingTask);
        taskStore.save(completedTask);

        assertThat(taskStore.isTaskFinalized(workingId)).isFalse();
        assertThat(taskStore.isTaskFinalized(completedId)).isTrue();
    }

    @Test
    void saveWithMultipleMessages() {
        String conversationId = "conv-multi";

        List<io.a2a.spec.Message> messages = List.of(
                createMessage(io.a2a.spec.Message.Role.USER, "Message 1"),
                createMessage(io.a2a.spec.Message.Role.AGENT, "Response 1"),
                createMessage(io.a2a.spec.Message.Role.USER, "Message 2"),
                createMessage(io.a2a.spec.Message.Role.AGENT, "Response 2")
        );

        Task task = new Task.Builder()
                .id(conversationId)
                .contextId(conversationId)
                .status(new TaskStatus(TaskState.WORKING))
                .history(messages)
                .build();

        taskStore.save(task);

        Task retrieved = taskStore.get(conversationId);
        assertThat(retrieved.getHistory()).hasSize(4);

        // Verify order is preserved
        assertThat(retrieved.getHistory().get(0).getRole()).isEqualTo(io.a2a.spec.Message.Role.USER);
        assertThat(retrieved.getHistory().get(1).getRole()).isEqualTo(io.a2a.spec.Message.Role.AGENT);
    }

    private Task createSampleTask(String conversationId) {
        io.a2a.spec.Message message = new io.a2a.spec.Message.Builder()
                .role(io.a2a.spec.Message.Role.USER)
                .parts(new TextPart("Hello, agent!"))
                .contextId(conversationId)
                .build();

        Artifact artifact = new Artifact.Builder()
                .artifactId("art-001")
                .name("Test Artifact")
                .parts(new TextPart("Artifact content"))
                .build();

        return new Task.Builder()
                .id(conversationId)
                .contextId(conversationId)
                .status(new TaskStatus(TaskState.WORKING, null, OffsetDateTime.now()))
                .history(List.of(message))
                .artifacts(List.of(artifact))
                .metadata(Map.of("key1", "value1"))
                .build();
    }

    private io.a2a.spec.Message createMessage(io.a2a.spec.Message.Role role, String content) {
        return new io.a2a.spec.Message.Builder()
                .role(role)
                .parts(new TextPart(content))
                .build();
    }
}
