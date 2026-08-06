package com.yizhixianyu.agentvideo.cache;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class StoredDraftTest {
    @Test
    void reportsRevisionAndPositiveTtl() {
        var draft = StoredDraft.expiringIn(3, "{\"ok\":true}", 60);

        assertThat(draft.revision()).isEqualTo(3);
        assertThat(draft.json()).contains("ok");
        assertThat(draft.ttlSeconds()).isBetween(1L, 60L);
        assertThat(draft.expired()).isFalse();
    }
}
