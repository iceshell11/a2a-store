package io.a2a.extras.taskstore.jdbc.repository;

import io.a2a.extras.taskstore.jdbc.entity.TaskEntity;
import io.a2a.extras.taskstore.jdbc.entity.TaskState;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface TaskRepository extends JpaRepository<TaskEntity, UUID> {

    List<TaskEntity> findByContextId(String contextId);

    List<TaskEntity> findByStatusState(TaskState statusState);

    List<TaskEntity> findByFinalizedAtIsNull();

    @EntityGraph(attributePaths = {"artifacts", "messages"})
    Optional<TaskEntity> findWithArtifactsAndMessagesById(UUID id);
}
