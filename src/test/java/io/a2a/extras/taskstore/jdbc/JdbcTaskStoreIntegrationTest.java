package io.a2a.extras.taskstore.jdbc;

import io.a2a.extras.taskstore.jdbc.store.JdbcTaskStore;
import io.a2a.spec.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;

import java.time.OffsetDateTime;
import java.util.*;

import static org.assertj.core.api.Assertions.*;

@SpringBootTest
@TestPropertySource(properties = {
    "spring.datasource.url=jdbc:postgresql://localhost:5432/postgres",
    "spring.datasource.username=postgres",
    "spring.datasource.password=postgres",
    "spring.jpa.hibernate.ddl-auto=create-drop",
    "spring.flyway.enabled=false"
})
class JdbcTaskStoreIntegrationTest {

    @Autowired
    private JdbcTaskStore taskStore;

    @BeforeEach
    void setUp() {
        // Clean up before each test - get all tasks and delete them
        // Note: This is a simplified approach, in production you'd use a cleaner
    }

    @Test
    void shouldSaveAndRetrieveTask() {
        // Given
        Task task = createSampleTask();

        // When
        taskStore.save(task);
        Task retrieved = taskStore.get(task.getId());

        // Then
        assertThat(retrieved).isNotNull();
        assertThat(retrieved.getId()).isEqualTo(task.getId());
        assertThat(retrieved.getContextId()).isEqualTo(task.getContextId());
        assertThat(retrieved.getStatus().state()).isEqualTo(task.getStatus().state());
        assertThat(retrieved.getArtifacts()).hasSize(1);
        assertThat(retrieved.getHistory()).hasSize(1);
    }

    @Test
    void shouldUpdateExistingTask() {
        // Given
        Task task = createSampleTask();
        taskStore.save(task);

        // When - update status
        Task updatedTask = new Task.Builder(task)
                .status(new TaskStatus(TaskState.COMPLETED, null, OffsetDateTime.now()))
                .build();
        taskStore.save(updatedTask);

        Task retrieved = taskStore.get(task.getId());

        // Then
        assertThat(retrieved).isNotNull();
        assertThat(retrieved.getStatus().state()).isEqualTo(TaskState.COMPLETED);
    }

    @Test
    void shouldDeleteTask() {
        // Given
        Task task = createSampleTask();
        taskStore.save(task);

        // When
        taskStore.delete(task.getId());
        Task retrieved = taskStore.get(task.getId());

        // Then
        assertThat(retrieved).isNull();
    }

    @Test
    void shouldHandleTaskWithEmptyCollections() {
        // Given
        Task task = new Task.Builder()
                .id(UUID.randomUUID().toString())
                .contextId("test-context")
                .status(new TaskStatus(TaskState.SUBMITTED, null, OffsetDateTime.now()))
                .build();

        // When
        taskStore.save(task);
        Task retrieved = taskStore.get(task.getId());

        // Then
        assertThat(retrieved).isNotNull();
        assertThat(retrieved.getArtifacts()).isEmpty();
        assertThat(retrieved.getHistory()).isEmpty();
    }

    @Test
    void shouldHandleComplexParts() {
        // Given
        Task task = createTaskWithComplexParts();

        // When
        taskStore.save(task);
        Task retrieved = taskStore.get(task.getId());

        // Then
        assertThat(retrieved).isNotNull();
        assertThat(retrieved.getArtifacts()).hasSize(1);
        
        Artifact artifact = retrieved.getArtifacts().get(0);
        assertThat(artifact.parts()).hasSize(3); // text, file, data
    }

    private Task createSampleTask() {
        TextPart textPart = new TextPart("Hello, this is a test message");

        Artifact artifact = new Artifact.Builder()
                .artifactId(UUID.randomUUID().toString())
                .name("test-artifact")
                .description("A test artifact")
                .parts(List.of(textPart))
                .build();

        Message message = new Message.Builder()
                .role(Message.Role.USER)
                .parts(List.of(textPart))
                .build();

        return new Task.Builder()
                .id(UUID.randomUUID().toString())
                .contextId("test-context")
                .status(new TaskStatus(TaskState.WORKING, null, OffsetDateTime.now()))
                .artifacts(List.of(artifact))
                .history(List.of(message))
                .build();
    }

    private Task createTaskWithComplexParts() {
        // Text part
        TextPart textPart = new TextPart("Sample text");

        // File part with bytes
        FileWithBytes fileContent = new FileWithBytes("image/png", "image.png", "base64encodeddata");
        FilePart filePart = new FilePart(fileContent);

        // Data part
        DataPart dataPart = new DataPart(Map.of("key", "value", "number", 42));

        Artifact artifact = new Artifact.Builder()
                .artifactId(UUID.randomUUID().toString())
                .name("complex-artifact")
                .parts(List.of(textPart, filePart, dataPart))
                .build();

        return new Task.Builder()
                .id(UUID.randomUUID().toString())
                .contextId("test-context")
                .status(new TaskStatus(TaskState.SUBMITTED, null, OffsetDateTime.now()))
                .artifacts(List.of(artifact))
                .build();
    }
}
