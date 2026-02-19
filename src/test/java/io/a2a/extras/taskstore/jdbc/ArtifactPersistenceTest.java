package io.a2a.extras.taskstore.jdbc;

import io.a2a.spec.Artifact;
import io.a2a.spec.Task;
import io.a2a.spec.TaskState;
import io.a2a.spec.TextPart;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static io.a2a.extras.taskstore.support.TaskTestBuilder.anArtifact;
import static io.a2a.extras.taskstore.support.TaskTestBuilder.aTask;
import static org.assertj.core.api.Assertions.assertThat;

class ArtifactPersistenceTest extends BaseJdbcIntegrationTest {

    @BeforeEach
    void setUp() {
        setUpTaskStore();
    }

    @Test
    void shouldSaveAndRetrieveArtifact() {
        Task task = aTask()
                .withId("art-1")
                .withArtifact("art-001", "Test Artifact", "Content here")
                .build();

        taskStore.save(task);
        Task retrieved = taskStore.get("art-1");

        assertThat(retrieved.getArtifacts()).hasSize(1);
        Artifact artifact = retrieved.getArtifacts().get(0);
        assertThat(artifact.artifactId()).isEqualTo("art-001");
        assertThat(artifact.name()).isEqualTo("Test Artifact");
        assertThat(((TextPart) artifact.parts().get(0)).getText()).isEqualTo("Content here");
    }

    @Test
    void shouldPreserveMultipleArtifacts() {
        Task task = aTask()
                .withId("art-multi")
                .withArtifact("art-1", "First", "Content 1")
                .withArtifact("art-2", "Second", "Content 2")
                .withArtifact("art-3", "Third", "Content 3")
                .build();

        taskStore.save(task);
        Task retrieved = taskStore.get("art-multi");

        assertThat(retrieved.getArtifacts()).hasSize(3);
        assertThat(retrieved.getArtifacts().get(0).artifactId()).isEqualTo("art-1");
        assertThat(retrieved.getArtifacts().get(1).artifactId()).isEqualTo("art-2");
        assertThat(retrieved.getArtifacts().get(2).artifactId()).isEqualTo("art-3");
    }

    @Test
    void shouldPreserveArtifactOrdering() {
        Task task = aTask()
                .withId("art-order")
                .withArtifact("first", "First Artifact", "First content")
                .withArtifact("second", "Second Artifact", "Second content")
                .withArtifact("third", "Third Artifact", "Third content")
                .build();

        taskStore.save(task);
        Task retrieved = taskStore.get("art-order");

        assertThat(retrieved.getArtifacts().get(0).name()).isEqualTo("First Artifact");
        assertThat(retrieved.getArtifacts().get(1).name()).isEqualTo("Second Artifact");
        assertThat(retrieved.getArtifacts().get(2).name()).isEqualTo("Third Artifact");
    }

    @Test
    void shouldPreserveArtifactDescription() {
        Artifact artifact = new Artifact.Builder()
                .artifactId("art-desc")
                .name("With Description")
                .description("This is a detailed description")
                .parts(new TextPart("Content"))
                .build();

        Task task = aTask()
                .withId("art-desc-test")
                .withArtifact(artifact)
                .build();

        taskStore.save(task);
        Task retrieved = taskStore.get("art-desc-test");

        assertThat(retrieved.getArtifacts().get(0).description())
                .isEqualTo("This is a detailed description");
    }

    @Test
    void shouldPreserveArtifactMetadata() {
        Artifact artifact = new Artifact.Builder()
                .artifactId("art-meta")
                .name("With Metadata")
                .parts(new TextPart("Content"))
                .metadata(Map.of("author", "test", "version", 1, "tags", List.of("tag1", "tag2")))
                .build();

        Task task = aTask()
                .withId("art-meta-test")
                .withArtifact(artifact)
                .build();

        taskStore.save(task);
        Task retrieved = taskStore.get("art-meta-test");

        Map<String, Object> metadata = retrieved.getArtifacts().get(0).metadata();
        assertThat(metadata).containsEntry("author", "test");
        assertThat(metadata).containsEntry("version", 1);
    }

    @Test
    void shouldPreserveArtifactExtensions() {
        Artifact artifact = new Artifact.Builder()
                .artifactId("art-ext")
                .name("With Extensions")
                .parts(new TextPart("Content"))
                .extensions(List.of("ext1", "ext2", "ext3"))
                .build();

        Task task = aTask()
                .withId("art-ext-test")
                .withArtifact(artifact)
                .build();

        taskStore.save(task);
        Task retrieved = taskStore.get("art-ext-test");

        assertThat(retrieved.getArtifacts().get(0).extensions())
                .containsExactly("ext1", "ext2", "ext3");
    }

    @Test
    void shouldReplaceArtifactsOnUpdate() {
        Task initialTask = aTask()
                .withId("art-replace")
                .withArtifact("initial", "Initial", "Initial content")
                .build();
        taskStore.save(initialTask);

        Task updatedTask = aTask()
                .withId("art-replace")
                .withStatus(TaskState.COMPLETED)
                .withArtifact("updated", "Updated", "Updated content")
                .build();
        taskStore.save(updatedTask);

        Task retrieved = taskStore.get("art-replace");
        assertThat(retrieved.getArtifacts()).hasSize(1);
        assertThat(retrieved.getArtifacts().get(0).artifactId()).isEqualTo("updated");
    }

    @Test
    void shouldHandleArtifactWithAllFields() {
        Artifact artifact = new Artifact.Builder()
                .artifactId("art-full")
                .name("Full Artifact")
                .description("Complete artifact")
                .parts(new TextPart("Text content"))
                .metadata(Map.of("key", "value"))
                .extensions(List.of("ext1"))
                .build();

        Task task = aTask()
                .withId("art-full-test")
                .withArtifact(artifact)
                .build();

        taskStore.save(task);
        Task retrieved = taskStore.get("art-full-test");

        Artifact retrievedArt = retrieved.getArtifacts().get(0);
        assertThat(retrievedArt.artifactId()).isEqualTo("art-full");
        assertThat(retrievedArt.name()).isEqualTo("Full Artifact");
        assertThat(retrievedArt.description()).isEqualTo("Complete artifact");
        assertThat(retrievedArt.metadata()).containsEntry("key", "value");
        assertThat(retrievedArt.extensions()).containsExactly("ext1");
    }

    @Test
    void shouldHandleNullOptionalFields() {
        Artifact artifact = new Artifact.Builder()
                .artifactId("art-minimal")
                .name(null)
                .description(null)
                .parts(new TextPart("Minimal content"))
                .metadata(null)
                .extensions(null)
                .build();

        Task task = aTask()
                .withId("art-null-test")
                .withArtifact(artifact)
                .build();

        taskStore.save(task);
        Task retrieved = taskStore.get("art-null-test");

        Artifact retrievedArt = retrieved.getArtifacts().get(0);
        assertThat(retrievedArt.artifactId()).isEqualTo("art-minimal");
        assertThat(retrievedArt.name()).isNull();
        assertThat(retrievedArt.description()).isNull();
        assertThat(retrievedArt.metadata()).isEmpty();
        assertThat(retrievedArt.extensions()).isEmpty();
    }

    @Test
    void shouldHandleEmptyArtifactsList() {
        Task task = aTask()
                .withId("art-empty")
                .build();

        taskStore.save(task);
        Task retrieved = taskStore.get("art-empty");

        assertThat(retrieved.getArtifacts()).isEmpty();
    }

    @Test
    void shouldClearArtifactsWhenSavedWithEmptyList() {
        Task initialTask = aTask()
                .withId("art-clear")
                .withArtifact("art-1", "Artifact", "Content")
                .build();
        taskStore.save(initialTask);

        Task emptyTask = new Task.Builder()
                .id("art-clear")
                .contextId("art-clear")
                .status(new io.a2a.spec.TaskStatus(TaskState.WORKING))
                .history(List.of())
                .artifacts(List.of())
                .build();
        taskStore.save(emptyTask);

        Task retrieved = taskStore.get("art-clear");
        assertThat(retrieved.getArtifacts()).isEmpty();
    }
}
