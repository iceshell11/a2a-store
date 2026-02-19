package io.a2a.extras.taskstore.jdbc;

import io.a2a.extras.taskstore.A2aTaskStoreProperties;
import io.a2a.spec.Task;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static io.a2a.extras.taskstore.support.TaskTestBuilder.aTask;
import static org.assertj.core.api.Assertions.assertThat;

class MetadataAndConfigurationTest extends BaseJdbcIntegrationTest {

    @Test
    void shouldStoreAndRetrieveMetadata() {
        setUpTaskStore();

        Task task = aTask()
                .withId("meta-1")
                .withMetadataEntry("source", "test")
                .withMetadataEntry("priority", 1)
                .withMetadataEntry("active", true)
                .build();

        taskStore.save(task);
        Task retrieved = taskStore.get("meta-1");

        assertThat(retrieved.getMetadata())
                .containsEntry("source", "test")
                .containsEntry("priority", 1)
                .containsEntry("active", true);
    }

    @Test
    void shouldHandleNestedMetadata() {
        setUpTaskStore();

        Map<String, Object> nested = new LinkedHashMap<>();
        nested.put("level1", Map.of("level2", Map.of("value", "deep")));
        nested.put("array", java.util.List.of(1, 2, 3));

        Task task = aTask()
                .withId("meta-nested")
                .withMetadata(nested)
                .build();

        taskStore.save(task);
        Task retrieved = taskStore.get("meta-nested");

        @SuppressWarnings("unchecked")
        Map<String, Object> level1 = (Map<String, Object>) retrieved.getMetadata().get("level1");
        assertThat(level1).containsKey("level2");
    }

    @Test
    void shouldHandleEmptyMetadata() {
        setUpTaskStore();

        Task task = aTask()
                .withId("meta-empty")
                .withMetadata(Map.of())
                .build();

        taskStore.save(task);
        Task retrieved = taskStore.get("meta-empty");

        assertThat(retrieved.getMetadata()).isEmpty();
    }

    @Test
    void shouldNotStoreMetadataWhenDisabled() {
        A2aTaskStoreProperties props = new A2aTaskStoreProperties();
        props.setStoreMetadata(false);
        setUpTaskStoreWithProperties(props);

        Task task = aTask()
                .withId("meta-disabled")
                .withMetadataEntry("key", "value")
                .build();

        taskStore.save(task);
        Task retrieved = taskStore.get("meta-disabled");

        assertThat(retrieved.getMetadata()).isEmpty();
    }

    @Test
    void shouldNotStoreArtifactsWhenDisabled() {
        A2aTaskStoreProperties props = new A2aTaskStoreProperties();
        props.setStoreArtifacts(false);
        setUpTaskStoreWithProperties(props);

        Task task = aTask()
                .withId("art-disabled")
                .withArtifact("art-1", "Artifact", "Content")
                .build();

        taskStore.save(task);
        Task retrieved = taskStore.get("art-disabled");

        assertThat(retrieved.getArtifacts()).isEmpty();
    }

    @Test
    void shouldUseCustomBatchSize() {
        A2aTaskStoreProperties props = new A2aTaskStoreProperties();
        props.setBatchSize(2);
        setUpTaskStoreWithProperties(props);

        Task task = aTask()
                .withId("batch-test")
                .withMessages(5, io.a2a.spec.Message.Role.USER, "Message")
                .build();

        taskStore.save(task);
        Task retrieved = taskStore.get("batch-test");

        assertThat(retrieved.getHistory()).hasSize(5);
    }

    @Test
    void shouldStoreMetadataInDatabase() {
        setUpTaskStore();

        Task task = aTask()
                .withId("meta-db")
                .withMetadataEntry("test-key", "test-value")
                .build();

        taskStore.save(task);

        String metadataJson = jdbcTemplate.queryForObject(
                "SELECT metadata_json FROM a2a_tasks WHERE task_id = ?",
                String.class,
                "meta-db"
        );

        assertThat(metadataJson).contains("test-key");
        assertThat(metadataJson).contains("test-value");
    }
}
