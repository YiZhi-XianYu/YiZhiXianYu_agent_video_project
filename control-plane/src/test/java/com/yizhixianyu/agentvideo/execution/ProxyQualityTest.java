package com.yizhixianyu.agentvideo.execution;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ProxyQualityTest {

    @Test
    void parsesAllApiValues() {
        assertThat(ProxyQuality.fromValue("4K")).isEqualTo(ProxyQuality.UHD_4K);
        assertThat(ProxyQuality.fromValue("2k")).isEqualTo(ProxyQuality.QHD_2K);
        assertThat(ProxyQuality.fromValue("1080P")).isEqualTo(ProxyQuality.FHD_1080P);
        assertThat(ProxyQuality.fromValue("720p")).isEqualTo(ProxyQuality.HD_720P);
    }

    @Test
    void rejectsUnsupportedValues() {
        assertThatThrownBy(() -> ProxyQuality.fromValue("8K"))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("Unsupported proxy quality");
    }
}
