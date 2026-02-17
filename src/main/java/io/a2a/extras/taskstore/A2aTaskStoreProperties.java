package io.a2a.extras.taskstore;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

@Data
@ConfigurationProperties(prefix = "a2a.taskstore")
public class A2aTaskStoreProperties {

    private boolean enabled = true;
    private boolean storeArtifacts = true;
    private boolean storeMetadata = true;
    private boolean chatMemoryEnabled = true;
    private int batchSize = 100;
    private String tablePrefix = "a2a_";
    private CacheProperties cache = new CacheProperties();

    @Data
    public static class CacheProperties {
        private boolean enabled = true;
        private int ttlMinutes = 10;
        private int finalizedTtlMinutes = 60;
        private int maxSize = 1000;
        private boolean recordStats = true;
    }
}
