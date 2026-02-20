package io.a2a.extras.taskstore.jdbc.repository;

import io.a2a.extras.taskstore.jdbc.entity.TaskEntity;
import io.a2a.extras.taskstore.jdbc.entity.TaskState;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface TaskRepository extends JpaRepository<TaskEntity, String> {

    List<TaskEntity> findByContextId(String contextId);

    List<TaskEntity> findByStatusState(TaskState statusState);

    List<TaskEntity> findByFinalizedAtIsNull();

    @Query("SELECT t FROM TaskEntity t LEFT JOIN FETCH t.artifacts WHERE t.id = :id")
    Optional<TaskEntity> findWithArtifactsById(@Param("id") String id);
}
