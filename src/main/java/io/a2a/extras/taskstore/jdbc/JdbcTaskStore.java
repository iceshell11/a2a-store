package io.a2a.extras.taskstore.jdbc;

import io.a2a.extras.taskstore.A2aTaskStoreProperties;
import io.a2a.extras.taskstore.cache.CacheConfig;
import io.a2a.extras.taskstore.repository.ArtifactRepository;
import io.a2a.extras.taskstore.repository.HistoryRepository;
import io.a2a.extras.taskstore.repository.TaskRepository;
import io.a2a.server.tasks.TaskStateProvider;
import io.a2a.server.tasks.TaskStore;
import io.a2a.spec.Task;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;

public class JdbcTaskStore implements TaskStore, TaskStateProvider {

    private final TaskRepository taskRepository;
    private final HistoryRepository historyRepository;
    private final ArtifactRepository artifactRepository;
    private final A2aTaskStoreProperties properties;

    public JdbcTaskStore(
            TaskRepository taskRepository,
            HistoryRepository historyRepository,
            ArtifactRepository artifactRepository,
            A2aTaskStoreProperties properties) {
        this.taskRepository = taskRepository;
        this.historyRepository = historyRepository;
        this.artifactRepository = artifactRepository;
        this.properties = properties;
    }

    @Override
    @Transactional
    @CacheEvict(value = CacheConfig.TASK_CACHE, key = "#task.id")
    public void save(Task task) {
        String taskId = task.getId();
        taskRepository.save(task);
        historyRepository.saveAll(taskId, task.getHistory());

        if (properties.isStoreArtifacts()) {
            artifactRepository.saveAll(taskId, task.getArtifacts());
        }
        if (properties.isStoreMetadata() && task.getMetadata() != null) {
            taskRepository.updateMetadata(taskId, task.getMetadata());
        }
    }

    @Override
    @Transactional(readOnly = true)
    @Cacheable(value = CacheConfig.TASK_CACHE, key = "#taskId", unless = "#result == null")
    public Task get(String taskId) {
        return taskRepository.findById(taskId)
                .map(taskRow -> new Task.Builder()
                        .id(taskId)
                        .contextId(taskRow.contextId() != null ? taskRow.contextId() : taskId)
                        .status(taskRepository.buildTaskStatus(taskRow))
                        .history(historyRepository.findByTaskId(taskId))
                        .artifacts(properties.isStoreArtifacts() ? artifactRepository.findByTaskId(taskId) : List.of())
                        .metadata(properties.isStoreMetadata() ? taskRepository.loadMetadata(taskRow) : Map.of())
                        .build())
                .orElse(null);
    }

    @Override
    @Transactional
    @CacheEvict(value = CacheConfig.TASK_CACHE, key = "#taskId")
    public void delete(String taskId) {
        taskRepository.delete(taskId);
    }

    @Override
    @Transactional(readOnly = true)
    public boolean isTaskActive(String taskId) {
        return taskRepository.isTaskActive(taskId);
    }

    @Override
    @Transactional(readOnly = true)
    public boolean isTaskFinalized(String taskId) {
        return taskRepository.isTaskFinalized(taskId);
    }
}
