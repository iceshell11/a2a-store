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

import java.util.Optional;
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
        if (task == null) {
            throw new IllegalArgumentException("Task cannot be null");
        }

        UUID taskId = UUID.fromString(task.getId());
        
        // Delete existing task and all related entities if present
        Optional<TaskEntity> existing = taskRepository.findById(taskId);
        if (existing.isPresent()) {
            taskRepository.delete(existing.get());
            taskRepository.flush();
        }

        // Create and save new entity
        TaskEntity entity = taskMapper.toEntity(task);
        taskRepository.save(entity);
    }

    @Override
    @Transactional(readOnly = true)
    public Task get(String taskId) {
        if (taskId == null || taskId.isBlank()) {
            throw new IllegalArgumentException("Task ID cannot be null or blank");
        }

        UUID id = UUID.fromString(taskId);
        TaskEntity taskEntity = taskRepository.findById(id).orElse(null);
        if (taskEntity == null) {
            return null;
        }
        
        // Load artifacts and messages separately to avoid MultipleBagFetchException
        taskEntity.setArtifacts(artifactRepository.findByTaskIdOrderByCreatedAt(id));
        taskEntity.setMessages(messageRepository.findByTaskIdOrderByCreatedAtAsc(id));
        
        return taskMapper.fromEntity(taskEntity);
    }

    @Override
    @Transactional
    public void delete(String taskId) {
        if (taskId == null || taskId.isBlank()) {
            throw new IllegalArgumentException("Task ID cannot be null or blank");
        }

        UUID id = UUID.fromString(taskId);
        taskRepository.deleteById(id);
    }
}
