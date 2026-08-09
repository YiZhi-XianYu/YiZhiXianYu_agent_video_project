package com.yizhixianyu.agentvideo.agent;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ChuxueIntentParserTest {
    private final ChuxueIntentParser parser = new ChuxueIntentParser();

    @Test
    void extractsDurationCapabilitiesAndPacing() {
        var intent = parser.parse("两个视频做成45秒快节奏旅行短片，加入字幕和背景音乐", null, false);
        assertThat(intent.targetDurationMs()).isEqualTo(45000);
        assertThat(intent.subtitles()).isTrue();
        assertThat(intent.bgm()).isTrue();
        assertThat(intent.pacing()).isEqualTo(ChuxueIntentParser.Pacing.FAST);
        assertThat(intent.requestedCapabilities()).contains("sourceTranscription", "subtitles", "bgm");
    }

    @Test
    void explicitNegativeRequirementsDisableOptionalCapabilities() {
        var intent = parser.parse("改成30秒，不要字幕，也不要音乐", null, null);
        assertThat(intent.targetDurationMs()).isEqualTo(30000);
        assertThat(intent.subtitles()).isFalse();
        assertThat(intent.bgm()).isFalse();
        assertThat(intent.autoMode()).isFalse();
    }

    @Test
    void usesDefaultDurationAndAsksForGoalWhenEmpty() {
        assertThat(parser.parse("", null, null).needsClarification()).isTrue();
        assertThat(parser.parse("帮我处理一下", null, null).needsClarification()).isTrue();
        assertThat(parser.parse("做一个旅行短片", null, null).targetDurationMs()).isEqualTo(30000);
    }

    @Test
    void recognizesModificationOnlyTurns() {
        assertThat(parser.isModificationOnly("改成45秒")).isTrue();
        assertThat(parser.isModificationOnly("不要字幕")).isTrue();
        assertThat(parser.isModificationOnly("做成旅行短片")).isFalse();
    }

    @Test
    void validatesIntentCapabilities() {
        var intent = parser.parse("不要字幕", null, null);
        assertThat(intent.requestedCapabilities()).containsExactly("vlmAnalysis");
        assertThat(intent.subtitles()).isFalse();
    }
}
