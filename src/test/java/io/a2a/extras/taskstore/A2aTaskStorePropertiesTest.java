package io.a2a.extras.taskstore;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

class A2aTaskStorePropertiesTest {

    @Test
    void defaultValues() {
        A2aTaskStoreProperties properties = new A2aTaskStoreProperties();
        
        assertThat(properties.isEnabled()).isTrue();
        assertThat(properties.isStoreArtifacts()).isTrue();
        assertThat(properties.isStoreMetadata()).isTrue();
        assertThat(properties.isChatMemoryEnabled()).isTrue();
        assertThat(properties.getBatchSize()).isEqualTo(100);
        assertThat(properties.getTablePrefix()).isEqualTo("a2a_");
    }

    @Test
    void customValues() {
        A2aTaskStoreProperties properties = new A2aTaskStoreProperties();
        
        properties.setEnabled(false);
        properties.setStoreArtifacts(false);
        properties.setStoreMetadata(false);
        properties.setChatMemoryEnabled(false);
        properties.setBatchSize(50);
        properties.setTablePrefix("custom_");
        
        assertThat(properties.isEnabled()).isFalse();
        assertThat(properties.isStoreArtifacts()).isFalse();
        assertThat(properties.isStoreMetadata()).isFalse();
        assertThat(properties.isChatMemoryEnabled()).isFalse();
        assertThat(properties.getBatchSize()).isEqualTo(50);
        assertThat(properties.getTablePrefix()).isEqualTo("custom_");
    }
}
