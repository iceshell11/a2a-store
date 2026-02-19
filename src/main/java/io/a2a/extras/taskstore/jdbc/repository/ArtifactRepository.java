package io.a2a.extras.taskstore.jdbc.repository;

import io.a2a.extras.taskstore.jdbc.entity.ArtifactEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface ArtifactRepository extends JpaRepository<ArtifactEntity, UUID> {

    List<ArtifactEntity> findByTaskIdOrderByCreatedAt(UUID taskId);

    Optional<ArtifactEntity> findByTaskIdAndName(UUID taskId, String name);

    void deleteByTaskId(UUID taskId);
}
