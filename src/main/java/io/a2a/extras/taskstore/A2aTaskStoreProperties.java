package io.a2a.extras.taskstore;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "a2a.taskstore")
public class A2aTaskStoreProperties {

    private boolean enabled = true;
    private boolean storeArtifacts = true;
    private boolean storeMetadata = true;
    private boolean chatMemoryEnabled = true;
    private int batchSize = 100;
    private String tablePrefix = "a2a_";
    
    public boolean isEnabled() {
        return enabled;
    }
    
    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }
    
    public boolean isStoreArtifacts() {
        return storeArtifacts;
    }
    
    public void setStoreArtifacts(boolean storeArtifacts) {
        this.storeArtifacts = storeArtifacts;
    }
    
    public boolean isStoreMetadata() {
        return storeMetadata;
    }
    
    public void setStoreMetadata(boolean storeMetadata) {
        this.storeMetadata = storeMetadata;
    }

    public boolean isChatMemoryEnabled() {
        return chatMemoryEnabled;
    }

    public void setChatMemoryEnabled(boolean chatMemoryEnabled) {
        this.chatMemoryEnabled = chatMemoryEnabled;
    }

    public int getBatchSize() {
        return batchSize;
    }
    
    public void setBatchSize(int batchSize) {
        this.batchSize = batchSize;
    }
    
    public String getTablePrefix() {
        return tablePrefix;
    }
    
    public void setTablePrefix(String tablePrefix) {
        this.tablePrefix = tablePrefix;
    }
}
