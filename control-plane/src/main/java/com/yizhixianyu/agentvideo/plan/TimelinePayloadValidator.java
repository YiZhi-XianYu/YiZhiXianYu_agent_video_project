package com.yizhixianyu.agentvideo.plan;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

public final class TimelinePayloadValidator {

    private static final Set<String> TRACK_TYPES = Set.of("VIDEO", "AUDIO", "SUBTITLE");
    private static final Set<String> TRANSITIONS = Set.of("CUT", "FADE", "CROSS_DISSOLVE");
    private static final Set<String> STORY_ROLES = Set.of("HOOK", "INTRO", "JOURNEY", "CLIMAX", "ENDING");

    private TimelinePayloadValidator() {
    }

    public static void validate(Map<String, Object> timeline) {
        var errors = new ArrayList<String>();
        if (timeline == null) {
            throw new IllegalArgumentException("Invalid Timeline: payload is required");
        }
        if (!"1.1".equals(timeline.get("schemaVersion"))) errors.add("schemaVersion must be 1.1");
        if (text(timeline.get("timelineId")) == null) errors.add("timelineId is required");
        if (text(timeline.get("sourceHighlightArtifactId")) == null) {
            errors.add("sourceHighlightArtifactId is required");
        }
        var version = integer(timeline.get("version"));
        if (version == null || version < 1) errors.add("version must be positive");
        validateCanvas(timeline.get("canvas"), errors);

        if (!(timeline.get("tracks") instanceof List<?> tracks) || tracks.isEmpty()) {
            errors.add("tracks must be a non-empty array");
            failIfInvalid(errors);
            return;
        }
        if (tracks.size() > 3) errors.add("tracks must not contain more than three entries");

        var seenTrackTypes = new HashSet<String>();
        Map<?, ?> videoTrack = null;
        for (int index = 0; index < tracks.size(); index++) {
            if (!(tracks.get(index) instanceof Map<?, ?> track)) {
                errors.add("tracks[" + index + "] must be an object");
                continue;
            }
            var type = text(track.get("type"));
            if (!TRACK_TYPES.contains(type)) {
                errors.add("tracks[" + index + "].type is invalid");
                continue;
            }
            if (!seenTrackTypes.add(type)) errors.add("track type is duplicated: " + type);
            if ("VIDEO".equals(type)) videoTrack = track;
            if ("AUDIO".equals(type)) validateAudioTrack(track, errors);
            if ("SUBTITLE".equals(type)) validateSubtitleTrack(track, errors);
        }
        if (videoTrack == null) {
            errors.add("Timeline must contain exactly one VIDEO track");
            failIfInvalid(errors);
            return;
        }

        long videoDuration = validateVideoTrack(videoTrack, errors);
        var duration = integer(timeline.get("durationMs"));
        if (duration == null || duration != videoDuration) {
            errors.add("durationMs does not match the VIDEO track");
        }
        var validation = timeline.get("validation");
        if (!(validation instanceof Map<?, ?> result)
            || !Boolean.TRUE.equals(result.get("valid"))
            || !(result.get("errors") instanceof List<?> validationErrors)
            || !validationErrors.isEmpty()) {
            errors.add("validation must declare a valid Timeline with no errors");
        }
        failIfInvalid(errors);
    }

    private static void validateCanvas(Object value, List<String> errors) {
        if (!(value instanceof Map<?, ?> canvas)) {
            errors.add("canvas is required");
            return;
        }
        var width = integer(canvas.get("width"));
        var height = integer(canvas.get("height"));
        var fps = integer(canvas.get("fps"));
        if (width == null || width < 320 || width % 2 != 0) {
            errors.add("canvas.width must be an even integer of at least 320");
        }
        if (height == null || height < 240 || height % 2 != 0) {
            errors.add("canvas.height must be an even integer of at least 240");
        }
        if (fps == null || fps < 1 || fps > 120) errors.add("canvas.fps must be between 1 and 120");
    }

    private static long validateVideoTrack(Map<?, ?> track, List<String> errors) {
        if (!(track.get("clips") instanceof List<?> clips) || clips.isEmpty()) {
            errors.add("VIDEO track must contain at least one Clip");
            return 0;
        }
        var clipIds = new HashSet<String>();
        var shotIds = new HashSet<String>();
        long previousTimelineOut = 0;

        for (int index = 0; index < clips.size(); index++) {
            var prefix = "clips[" + index + "]";
            if (!(clips.get(index) instanceof Map<?, ?> clip)) {
                errors.add(prefix + " must be an object");
                continue;
            }
            var clipId = requiredText(clip, "clipId", prefix, errors);
            var shotId = requiredText(clip, "shotId", prefix, errors);
            requiredText(clip, "assetId", prefix, errors);
            requiredText(clip, "sourceProxyArtifactId", prefix, errors);
            if (clipId != null && !clipIds.add(clipId)) errors.add(prefix + ".clipId is duplicated");
            if (shotId != null && !shotIds.add(shotId)) errors.add(prefix + ".shotId is duplicated");

            var sourceIn = integer(clip.get("sourceInMs"));
            var sourceOut = integer(clip.get("sourceOutMs"));
            var shotStart = integer(clip.get("sourceShotStartMs"));
            var shotEnd = integer(clip.get("sourceShotEndMs"));
            var timelineIn = integer(clip.get("timelineInMs"));
            var timelineOut = integer(clip.get("timelineOutMs"));
            if (sourceIn == null || sourceOut == null || shotStart == null || shotEnd == null
                || timelineIn == null || timelineOut == null) {
                errors.add(prefix + " time values must be integers");
                continue;
            }
            if (shotStart < 0 || shotEnd <= shotStart) errors.add(prefix + " has an invalid source Shot range");
            if (sourceIn < shotStart || sourceOut > shotEnd || sourceOut <= sourceIn) {
                errors.add(prefix + " source range exceeds its Shot");
            }
            var clipDuration = timelineOut - timelineIn;
            if (timelineOut <= timelineIn) errors.add(prefix + " has an invalid Timeline range");
            if (clipDuration != sourceOut - sourceIn) errors.add(prefix + " source and Timeline durations differ");
            if (!(clip.get("playbackRate") instanceof Number rate) || rate.doubleValue() != 1.0) {
                errors.add(prefix + ".playbackRate must be 1.0");
            }

            var transitionType = "";
            long transitionDuration = -1;
            if (clip.get("transitionIn") instanceof Map<?, ?> transition) {
                var parsedType = text(transition.get("type"));
                transitionType = parsedType == null ? "" : parsedType;
                var parsedDuration = integer(transition.get("durationMs"));
                transitionDuration = parsedDuration == null ? -1 : parsedDuration;
            }
            if (!TRANSITIONS.contains(transitionType)) {
                errors.add(prefix + ".transitionIn.type is invalid");
            } else {
                var min = "CUT".equals(transitionType) ? 0 : 200;
                var max = "CUT".equals(transitionType) ? 0 : 2000;
                if (transitionDuration < min || transitionDuration > max) {
                    errors.add(prefix + ".transitionIn.durationMs is invalid for " + transitionType);
                }
                if (index == 0 && "CROSS_DISSOLVE".equals(transitionType)) {
                    errors.add(prefix + " cannot CROSS_DISSOLVE without a preceding Clip");
                }
                if (transitionDuration >= clipDuration) {
                    errors.add(prefix + ".transitionIn duration must be shorter than its Clip");
                }
            }
            var expectedTimelineIn = previousTimelineOut;
            if (index > 0 && "CROSS_DISSOLVE".equals(transitionType)) {
                expectedTimelineIn -= transitionDuration;
            }
            if (timelineIn != expectedTimelineIn) {
                errors.add(prefix + " has an invalid Timeline position for " + transitionType);
            }
            previousTimelineOut = timelineOut;

            var rank = integer(clip.get("selectionRank"));
            if (rank == null || rank < 1) errors.add(prefix + ".selectionRank must be positive");
            if (!STORY_ROLES.contains(text(clip.get("storyRole")))) errors.add(prefix + ".storyRole is invalid");
            if (!(clip.get("selectionReasons") instanceof List<?>)) {
                errors.add(prefix + ".selectionReasons must be an array");
            }
        }
        return previousTimelineOut;
    }

    private static void validateAudioTrack(Map<?, ?> track, List<String> errors) {
        if (!(track.get("source") instanceof Map<?, ?> source)) {
            errors.add("AUDIO track source is required");
            return;
        }
        if (text(source.get("uri")) == null) errors.add("AUDIO track source.uri is required");
        if (!(source.get("volume") instanceof Number volume)
            || volume.doubleValue() < 0 || volume.doubleValue() > 1) {
            errors.add("AUDIO track source.volume must be between 0 and 1");
        }
    }

    private static void validateSubtitleTrack(Map<?, ?> track, List<String> errors) {
        if (!(track.get("source") instanceof Map<?, ?> source)) {
            errors.add("SUBTITLE track source is required");
            return;
        }
        if (text(source.get("uri")) == null) errors.add("SUBTITLE track source.uri is required");
        if (!Set.of("SRT", "VTT").contains(text(source.get("format")))) {
            errors.add("SUBTITLE track source.format must be SRT or VTT");
        }
    }

    private static String requiredText(
        Map<?, ?> value, String field, String prefix, List<String> errors
    ) {
        var text = text(value.get(field));
        if (text == null) errors.add(prefix + "." + field + " is required");
        return text;
    }

    private static String text(Object value) {
        return value instanceof String text && !text.isBlank() ? text : null;
    }

    private static Long integer(Object value) {
        if (!(value instanceof Number number)) return null;
        var doubleValue = number.doubleValue();
        if (!Double.isFinite(doubleValue) || doubleValue != Math.rint(doubleValue)) return null;
        return number.longValue();
    }

    private static void failIfInvalid(List<String> errors) {
        if (!errors.isEmpty()) {
            throw new IllegalArgumentException("Invalid Timeline: " + String.join("; ", errors));
        }
    }
}
