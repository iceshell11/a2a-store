package io.a2a.extras.taskstore.jdbc.mapper;

import io.a2a.extras.taskstore.jdbc.entity.ArtifactEntity;
import io.a2a.spec.Artifact;
import org.springframework.stereotype.Component;

import java.util.Map;

@Component
public class ArtifactMapper {

    private final PartConverter partConverter;
    private final JsonUtils jsonUtils;

    public ArtifactMapper(PartConverter partConverter, JsonUtils jsonUtils) {
        this.partConverter = partConverter;
        this.jsonUtils = jsonUtils;
    }

    public ArtifactEntity toEntity(Artifact artifact, String taskId) {
        ArtifactEntity entity = new ArtifactEntity();
        entity.setArtifactId(artifact.artifactId());
        entity.setTaskId(taskId);
        entity.setName(artifact.name());
        entity.setDescription(artifact.description());
        entity.setParts(partConverter.toJsonNode(artifact.parts()));
        entity.setMetadata(jsonUtils.toJsonNode(artifact.metadata()));
        entity.setExtensions(jsonUtils.toJsonNode(artifact.extensions()));
        return entity;
    }

    public Artifact fromEntity(ArtifactEntity entity) {
        return new Artifact.Builder()
                .artifactId(entity.getArtifactId())
                .name(entity.getName())
                .description(entity.getDescription())
                .parts(partConverter.fromJsonNode(entity.getParts()))
                .metadata(jsonUtils.fromJsonNode(entity.getMetadata(), Map.class))
                .extensions(jsonUtils.fromJsonNode(entity.getExtensions(), java.util.List.class))
                .build();
    }
}
