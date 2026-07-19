package com.yizhixianyu.agentvideo.execution;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

import java.util.Arrays;

public enum ProxyQuality {
    UHD_4K("4K"),
    QHD_2K("2K"),
    FHD_1080P("1080P"),
    HD_720P("720P");

    private final String value;

    ProxyQuality(String value) {
        this.value = value;
    }

    @JsonValue
    public String value() {
        return value;
    }

    @JsonCreator
    public static ProxyQuality fromValue(String value) {
        return Arrays.stream(values())
            .filter(quality -> quality.value.equalsIgnoreCase(value))
            .findFirst()
            .orElseThrow(() -> new IllegalArgumentException("Unsupported proxy quality: " + value));
    }
}
