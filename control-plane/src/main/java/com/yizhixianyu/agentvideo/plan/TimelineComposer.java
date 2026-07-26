package com.yizhixianyu.agentvideo.plan;

import com.yizhixianyu.agentvideo.execution.ProxyQuality;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class TimelineComposer {

    private TimelineComposer() {
    }

    public static String compose(Map<String, Object> customPlan, ProxyQuality quality) {
        return compose(customPlan, quality, "CROSS_DISSOLVE");
    }

    public static String compose(Map<String, Object> customPlan, ProxyQuality quality, String transitionStyle) {
        int width = 1920;
        int height = 1080;
        switch (quality) {
            case UHD_4K -> { width = 3840; height = 2160; }
            case QHD_2K -> { width = 2560; height = 1440; }
            case FHD_1080P -> { width = 1920; height = 1080; }
            case HD_720P -> { width = 1280; height = 720; }
        }
        int fps = 30;

        List<Map<String, Object>> clips = new ArrayList<>();
        int timelineIn = 0;
        StringBuilder identitySource = new StringBuilder();

        @SuppressWarnings("unchecked")
        var beats = (List<Map<String, Object>>) customPlan.get("beats");
        if (beats == null || beats.isEmpty()) {
            throw new IllegalArgumentException("Custom plan has no beats");
        }

        int clipIndex = 0;
        String prevStoryRole = null;
        for (var beat : beats) {
            @SuppressWarnings("unchecked")
            var shots = (List<Map<String, Object>>) beat.get("shots");
            if (shots == null) continue;
            String storyRole = (String) beat.get("role");
            for (var shot : shots) {
                int sourceInMs = toInt(shot.get("sourceInMs"));
                int sourceOutMs = toInt(shot.get("sourceOutMs"));
                int startMs = toInt(shot.get("startMs"));
                int endMs = toInt(shot.get("endMs"));
                int duration = sourceOutMs - sourceInMs;

                if (duration <= 0) {
                    throw new IllegalArgumentException("Shot " + shot.get("shotId") + " has non-positive duration");
                }
                if (sourceOutMs > endMs) {
                    throw new IllegalArgumentException("Shot " + shot.get("shotId") + " clip extends beyond shot boundary");
                }

                Map<String, Object> transition = assignTransition(transitionStyle, clipIndex, storyRole, prevStoryRole);
                int transitionDuration = toInt(transition.get("durationMs"));
                if ("CROSS_DISSOLVE".equals(transition.get("type"))) {
                    if (sourceOutMs + transitionDuration <= endMs) {
                        sourceOutMs += transitionDuration;
                        duration += transitionDuration;
                        timelineIn -= transitionDuration;
                    } else {
                        transition = Map.of("type", "CUT", "durationMs", 0);
                    }
                }

                Map<String, Object> clip = new LinkedHashMap<>();
                clip.put("clipId", "clip_" + shot.get("shotId"));
                clip.put("shotId", shot.get("shotId"));
                clip.put("assetId", shot.get("sourceAssetId"));
                clip.put("sourceProxyArtifactId", shot.get("sourceProxyArtifactId"));
                clip.put("sourceInMs", sourceInMs);
                clip.put("sourceOutMs", sourceOutMs);
                clip.put("sourceShotStartMs", startMs);
                clip.put("sourceShotEndMs", endMs);
                clip.put("timelineInMs", timelineIn);
                clip.put("timelineOutMs", timelineIn + duration);
                clip.put("playbackRate", 1.0);
                clip.put("transitionIn", transition);
                clip.put("selectionRank", shot.getOrDefault("rank", 0));
                clip.put("storyRole", storyRole);
                clip.put("selectionReasons", shot.getOrDefault("selectionReasons", List.of()));
                clips.add(clip);

                identitySource.append(shot.get("sourceAssetId")).append(":")
                    .append(sourceInMs).append(":").append(sourceOutMs).append(";");
                timelineIn += duration;
                clipIndex++;
            }
            prevStoryRole = storyRole;
        }
        identitySource.append(width).append(":").append(height).append(":").append(fps);

        String timelineId = "tl_" + sha256Prefix(identitySource.toString(), 16);

        Map<String, Object> timeline = new LinkedHashMap<>();
        timeline.put("timelineId", timelineId);
        timeline.put("version", 1);
        timeline.put("schemaVersion", "1.1");
        timeline.put("sourceHighlightArtifactId", "custom-plan");
        timeline.put("canvas", Map.of("width", width, "height", height, "fps", fps));
        timeline.put("durationMs", timelineIn);
        timeline.put("tracks", List.of(Map.of("type", "VIDEO", "clips", clips)));
        timeline.put("validation", Map.of("valid", true, "errors", List.of()));

        try {
            return new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(timeline);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to serialize timeline JSON", e);
        }
    }

    private static Map<String, Object> assignTransition(String style, int clipIndex,
                                                          String storyRole, String prevStoryRole) {
        if ("CUT".equals(style)) {
            return Map.of("type", "CUT", "durationMs", 0);
        }
        if ("FADE".equals(style)) {
            if (clipIndex == 0) {
                return Map.of("type", "FADE", "durationMs", 300);
            }
            return Map.of("type", "CUT", "durationMs", 0);
        }
        if ("CROSS_DISSOLVE".equals(style)) {
            if (clipIndex == 0) {
                return Map.of("type", "FADE", "durationMs", 300);
            }
            if (prevStoryRole != null && !storyRole.equals(prevStoryRole)) {
                return Map.of("type", "CROSS_DISSOLVE", "durationMs", 500);
            }
            return Map.of("type", "CUT", "durationMs", 0);
        }
        return Map.of("type", "CUT", "durationMs", 0);
    }

    private static int toInt(Object value) {
        if (value instanceof Number n) return n.intValue();
        if (value instanceof String s) return Integer.parseInt(s);
        return 0;
    }

    private static String sha256Prefix(String input, int hexChars) {
        try {
            var digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(input.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash).substring(0, hexChars);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }
}
