package io.a2a.extras.taskstore.jdbc.mapper;

import com.fasterxml.jackson.core.type.TypeReference;
import io.a2a.extras.taskstore.jdbc.entity.ArtifactEntity;
import io.a2a.spec.Artifact;
import org.springframework.stereotype.Component;

import java.util.List;
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
        return new ArtifactEntity()
                .setArtifactId(artifact.artifactId())
                .setTaskId(taskId)
                .setName(artifact.name())
                .setDescription(artifact.description())
                .setParts(partConverter.toJsonNode(artifact.parts()))
                .setMetadata(jsonUtils.toJsonNode(artifact.metadata()))
                .setExtensions(jsonUtils.toJsonNode(artifact.extensions()));
    }

    public Artifact fromEntity(ArtifactEntity entity) {
        return new Artifact.Builder()
                .artifactId(entity.getArtifactId())
                .name(entity.getName())
                .description(entity.getDescription())
                .parts(partConverter.fromJsonNode(entity.getParts()))
                .metadata(jsonUtils.fromJsonNode(entity.getMetadata(), new TypeReference<Map<String, Object>>(){}))
                .extensions(jsonUtils.fromJsonNode(entity.getExtensions(), new TypeReference<List<String>>(){}))
                .build();
    }
}
