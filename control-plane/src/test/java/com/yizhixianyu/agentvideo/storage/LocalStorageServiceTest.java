package com.yizhixianyu.agentvideo.storage;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class LocalStorageServiceTest {
    @TempDir Path temp;

    @Test
    void storesImmutableObjectAndReadsLegacyFileUri() throws Exception {
        var storage = new LocalStorageService(temp);
        var object = storage.store("project-1", "artifacts/timeline", "timeline.json",
            new ByteArrayInputStream("{}".getBytes()), "application/json");
        assertThat(object.storageUri()).startsWith("file:");
        assertThat(object.sizeBytes()).isEqualTo(2);
        assertThat(object.contentHash()).hasSize(64);
        assertThat(storage.resource(object.storageUri()).isReadable()).isTrue();
        assertThat(Files.exists(Path.of(java.net.URI.create(object.storageUri())))).isTrue();
    }
}
