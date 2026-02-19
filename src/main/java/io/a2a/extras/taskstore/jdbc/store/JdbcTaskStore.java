package io.a2a.extras.taskstore.jdbc.store;

import io.a2a.extras.taskstore.jdbc.entity.TaskEntity;
import io.a2a.extras.taskstore.jdbc.mapper.TaskMapper;
import io.a2a.extras.taskstore.jdbc.repository.ArtifactRepository;
import io.a2a.extras.taskstore.jdbc.repository.MessageRepository;
import io.a2a.extras.taskstore.jdbc.repository.TaskRepository;
import io.a2a.server.tasks.TaskStore;
import io.a2a.spec.Task;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.util.UUID;

@Component
public class JdbcTaskStore implements TaskStore {

    private final TaskRepository taskRepository;
    private final ArtifactRepository artifactRepository;
    private final MessageRepository messageRepository;
    private final TaskMapper taskMapper;

    public JdbcTaskStore(TaskRepository taskRepository,
                         ArtifactRepository artifactRepository,
                         MessageRepository messageRepository,
                         TaskMapper taskMapper) {
        this.taskRepository = taskRepository;
        this.artifactRepository = artifactRepository;
        this.messageRepository = messageRepository;
        this.taskMapper = taskMapper;
    }

    @Override
    @Transactional
    public void save(Task task) {
        requireNonNull(task, "Task cannot be null");
        
        UUID taskId = UUID.fromString(task.getId());
        replaceExistingTask(taskId, task);
    }

    @Override
    @Transactional(readOnly = true)
    public Task get(String taskId) {
        requireNonBlank(taskId, "Task ID cannot be null or blank");
        
        UUID id = parseUuid(taskId);
        TaskEntity entity = taskRepository.findById(id).orElse(null);
        
        if (entity == null) {
            return null;
        }
        
        loadRelatedEntities(entity, id);
        return taskMapper.fromEntity(entity);
    }

    @Override
    @Transactional
    public void delete(String taskId) {
        requireNonBlank(taskId, "Task ID cannot be null or blank");
        taskRepository.deleteById(parseUuid(taskId));
    }

    private void replaceExistingTask(UUID taskId, Task task) {
        taskRepository.findById(taskId).ifPresent(existing -> {
            taskRepository.delete(existing);
            taskRepository.flush();
        });
        
        TaskEntity entity = taskMapper.toEntity(task);
        taskRepository.save(entity);
    }

    private void loadRelatedEntities(TaskEntity entity, UUID taskId) {
        entity.setArtifacts(artifactRepository.findByTaskIdOrderByCreatedAt(taskId));
        entity.setMessages(messageRepository.findByTaskIdOrderByCreatedAtAsc(taskId));
    }

    private UUID parseUuid(String taskId) {
        return UUID.fromString(taskId);
    }

    private void requireNonNull(Object value, String message) {
        if (value == null) {
            throw new IllegalArgumentException(message);
        }
    }

    private void requireNonBlank(String value, String message) {
        if (!StringUtils.hasText(value)) {
            throw new IllegalArgumentException(message);
        }
    }
}
