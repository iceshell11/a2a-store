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
import org.springframework.util.Assert;

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
        Assert.notNull(task, "Task cannot be null");
        
        replaceExistingTask(task.getId(), task);
    }

    @Override
    @Transactional(readOnly = true)
    public Task get(String taskId) {
        Assert.hasText(taskId, "Task ID cannot be null or blank");
        
        TaskEntity entity = taskRepository.findById(taskId).orElse(null);
        
        if (entity == null) {
            return null;
        }
        
        loadRelatedEntities(entity, taskId);
        return taskMapper.fromEntity(entity);
    }

    @Override
    @Transactional
    public void delete(String taskId) {
        Assert.hasText(taskId, "Task ID cannot be null or blank");
        taskRepository.deleteById(taskId);
    }

    private void replaceExistingTask(String taskId, Task task) {
        taskRepository.findById(taskId).ifPresent(existing -> {
            taskRepository.delete(existing);
            taskRepository.flush();
        });
        
        TaskEntity entity = taskMapper.toEntity(task);
        taskRepository.save(entity);
    }

    private void loadRelatedEntities(TaskEntity entity, String taskId) {
        entity.setArtifacts(artifactRepository.findByTaskIdOrderByCreatedAt(taskId));
        entity.setMessages(messageRepository.findByTaskIdOrderByCreatedAtAsc(taskId));
    }
}
