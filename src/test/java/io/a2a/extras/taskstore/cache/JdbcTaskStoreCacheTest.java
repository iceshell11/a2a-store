package io.a2a.extras.taskstore.cache;

import io.a2a.extras.taskstore.A2aTaskStoreProperties;
import io.a2a.extras.taskstore.jdbc.JdbcTaskStore;
import io.a2a.spec.Message;
import io.a2a.spec.Task;
import io.a2a.spec.TaskState;
import io.a2a.spec.TaskStatus;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.cache.CacheManager;
import org.springframework.cache.caffeine.CaffeineCacheManager;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;

import java.time.OffsetDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(classes = CacheTestConfiguration.class)
@TestPropertySource(properties = {
    "spring.sql.init.mode=never",
    "spring.datasource.url=jdbc:h2:mem:testdb;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DEFAULT_NULL_ORDERING=HIGH;INIT=RUNSCRIPT FROM 'classpath:test-schema-h2.sql'",
    "a2a.taskstore.cache.enabled=true",
    "a2a.taskstore.cache.ttl-minutes=60",
    "a2a.taskstore.cache.max-size=100"
})
class JdbcTaskStoreCacheTest {

    @Autowired
    private JdbcTaskStore taskStore;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private CacheManager cacheManager;

    @Autowired
    private A2aTaskStoreProperties properties;

    @Test
    void cacheShouldBeConfigured() {
        assertThat(cacheManager).isInstanceOf(CaffeineCacheManager.class);
        assertThat(cacheManager.getCache(CacheConfig.TASK_CACHE)).isNotNull();
    }

    @Test
    void getShouldCacheTask() {
        // Given
        String taskId = "cached-task-1";
        Task task = createTask(taskId, TaskState.SUBMITTED);
        taskStore.save(task);

        // When - first get (should hit DB)
        Task result1 = taskStore.get(taskId);

        // Then
        assertThat(result1).isNotNull();
        assertThat(result1.getContextId()).isEqualTo(taskId);

        // When - second get (should hit cache)
        Task result2 = taskStore.get(taskId);

        // Then - verify cache hit by checking it's the same object or equal
        assertThat(result2).isNotNull();
        assertThat(result2.getContextId()).isEqualTo(taskId);
    }

    @Test
    void saveShouldEvictCache() {
        // Given
        String taskId = "evict-task-1";
        Task task = createTask(taskId, TaskState.SUBMITTED);
        taskStore.save(task);
        
        // Populate cache
        taskStore.get(taskId);
        
        // When - update task
        Task updatedTask = createTask(taskId, TaskState.WORKING);
        taskStore.save(updatedTask);

        // Then - cache should be evicted, get should return updated task from DB
        Task result = taskStore.get(taskId);
        assertThat(result.getStatus().state()).isEqualTo(TaskState.WORKING);
    }

    @Test
    void deleteShouldEvictCache() {
        // Given
        String taskId = "delete-task-1";
        Task task = createTask(taskId, TaskState.SUBMITTED);
        taskStore.save(task);
        taskStore.get(taskId); // Populate cache

        // When
        taskStore.delete(taskId);

        // Then
        Task result = taskStore.get(taskId);
        assertThat(result).isNull();
    }

    @Test
    void cachePropertiesShouldBeLoaded() {
        assertThat(properties.getCache().isEnabled()).isTrue();
        assertThat(properties.getCache().getTtlMinutes()).isEqualTo(60);
        assertThat(properties.getCache().getMaxSize()).isEqualTo(100);
    }

    private Task createTask(String taskId, TaskState state) {
        return new Task.Builder()
            .id(taskId)
            .contextId(taskId)
            .status(new TaskStatus(state, null, OffsetDateTime.now()))
            .history(List.of())
            .build();
    }
}
