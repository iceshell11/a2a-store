package io.a2a.extras.taskstore.jdbc.repository;

import io.a2a.extras.taskstore.jdbc.entity.ArtifactEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface ArtifactRepository extends JpaRepository<ArtifactEntity, String> {

    List<ArtifactEntity> findByTaskIdOrderByCreatedAt(String taskId);

    Optional<ArtifactEntity> findByTaskIdAndName(String taskId, String name);

    void deleteByTaskId(String taskId);
}
