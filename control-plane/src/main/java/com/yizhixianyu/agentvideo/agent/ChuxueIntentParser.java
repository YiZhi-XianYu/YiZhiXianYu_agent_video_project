package com.yizhixianyu.agentvideo.agent;

import org.springframework.stereotype.Service;

import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Deterministic, side-effect-free intent extraction used before model planning. */
@Service
public class ChuxueIntentParser {
    private static final Pattern SECONDS = Pattern.compile("(\\d+(?:\\.\\d+)?)\\s*(?:秒|秒钟|seconds?|secs?)", Pattern.CASE_INSENSITIVE);
    private static final Pattern MINUTES = Pattern.compile("(\\d+(?:\\.\\d+)?)\\s*(?:分钟|分|minutes?|mins?)", Pattern.CASE_INSENSITIVE);

    public Intent parse(String goal, Integer explicitDurationMs, Boolean explicitAutoMode) {
        var text = goal == null ? "" : goal.trim();
        int duration = explicitDurationMs != null ? clamp(explicitDurationMs) : durationFromText(text);
        var lower = text.toLowerCase(Locale.ROOT);
        boolean subtitlesExplicit = containsAny(text, "字幕", "subtitles", "caption");
        boolean bgmExplicit = containsAny(text, "音乐", "配乐", "背景音乐", "bgm", "music");
        boolean subtitles = subtitlesExplicit;
        boolean bgm = bgmExplicit;
        if (containsAny(text, "不要字幕", "无需字幕", "不加字幕", "without subtitles")) subtitles = false;
        if (containsAny(text, "不要音乐", "不要配乐", "无音乐", "拒绝bgm", "without music")) bgm = false;
        Pacing pacing = containsAny(text, "快节奏", "紧凑", "快速", "fast") ? Pacing.FAST
            : containsAny(text, "慢节奏", "舒缓", "安静", "slow") ? Pacing.SLOW : Pacing.BALANCED;
        var capabilities = new LinkedHashSet<String>();
        capabilities.add("vlmAnalysis");
        if (subtitles) { capabilities.add("sourceTranscription"); capabilities.add("subtitles"); }
        if (bgm) capabilities.add("bgm");
        boolean auto = explicitAutoMode != null ? explicitAutoMode : containsAny(text, "自动", "全自动", "auto");
        String clarification = text.isBlank() || containsAny(text, "帮我处理一下", "帮我做一下", "随便做", "处理视频")
            ? "请告诉我想制作什么视频，例如旅行短片、产品介绍或访谈剪辑。" : null;
        return new Intent(duration, subtitles, bgm, subtitlesExplicit, bgmExplicit, pacing, auto, capabilities, clarification);
    }

    public boolean isModificationOnly(String goal) {
        var text = goal == null ? "" : goal.trim();
        if (text.isBlank()) return false;
        return containsAny(text, "改成", "调整为", "换成", "不要", "无需", "不加", "快节奏", "慢节奏", "舒缓", "安静", "seconds", "secs", "分钟", "秒")
            && !containsAny(text, "视频", "短片", "旅行", "产品", "访谈", "素材", "vlog", "video");
    }

    public boolean hasDuration(String goal) {
        var text = goal == null ? "" : goal;
        return SECONDS.matcher(text).find() || MINUTES.matcher(text).find();
    }

    private int durationFromText(String text) {
        Matcher seconds = SECONDS.matcher(text);
        if (seconds.find()) return clamp((int) (Double.parseDouble(seconds.group(1)) * 1000));
        Matcher minutes = MINUTES.matcher(text);
        if (minutes.find()) return clamp((int) (Double.parseDouble(minutes.group(1)) * 60000));
        return 30000;
    }

    private int clamp(int value) { return Math.max(5000, Math.min(300000, value)); }
    private boolean containsAny(String text, String... values) {
        var lower = text.toLowerCase(Locale.ROOT);
        for (var value : values) if (lower.contains(value.toLowerCase(Locale.ROOT))) return true;
        return false;
    }

    public enum Pacing { FAST, BALANCED, SLOW }
    public record Intent(int targetDurationMs, boolean subtitles, boolean bgm, boolean subtitlesExplicit,
                         boolean bgmExplicit, Pacing pacing,
                         boolean autoMode, Set<String> requestedCapabilities, String clarificationQuestion) {
        public Intent {
            if (targetDurationMs < 5000 || targetDurationMs > 300000) {
                throw new IllegalArgumentException("targetDurationMs must be between 5000 and 300000");
            }
            if (pacing == null) pacing = Pacing.BALANCED;
            requestedCapabilities = requestedCapabilities == null ? Set.of() : Set.copyOf(requestedCapabilities);
            var allowed = Set.of("vlmAnalysis", "sourceTranscription", "subtitles", "bgm");
            if (!allowed.containsAll(requestedCapabilities)) {
                throw new IllegalArgumentException("Intent contains an unsupported capability");
            }
            if (!subtitles && requestedCapabilities.contains("subtitles")) {
                throw new IllegalArgumentException("Disabled subtitles cannot be requested");
            }
            if (!subtitles && requestedCapabilities.contains("sourceTranscription")) {
                throw new IllegalArgumentException("Disabled subtitles cannot require transcription");
            }
        }
        public boolean needsClarification() { return clarificationQuestion != null; }
    }
}
