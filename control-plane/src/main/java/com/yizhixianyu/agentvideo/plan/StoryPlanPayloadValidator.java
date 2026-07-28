package com.yizhixianyu.agentvideo.plan;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

public final class StoryPlanPayloadValidator {

    private static final Set<String> ROLES = Set.of("HOOK", "INTRO", "JOURNEY", "CLIMAX", "ENDING");

    private StoryPlanPayloadValidator() {
    }

    public static void validate(Map<String, Object> plan) {
        var errors = new ArrayList<String>();
        if (plan == null) {
            throw new IllegalArgumentException("Invalid Story Plan: payload is required");
        }
        if (!"1.0".equals(plan.get("schemaVersion"))) {
            errors.add("schemaVersion must be 1.0");
        }
        if (!(plan.get("beats") instanceof List<?> beats) || beats.isEmpty()) {
            errors.add("beats must be a non-empty array");
            failIfInvalid(errors);
            return;
        }
        if (beats.size() != ROLES.size()) {
            errors.add("beats must contain exactly five story roles");
        }

        var seenRoles = new HashSet<String>();
        var seenShotIds = new HashSet<String>();
        long totalDuration = 0;
        int shotCount = 0;

        for (int beatIndex = 0; beatIndex < beats.size(); beatIndex++) {
            var beatValue = beats.get(beatIndex);
            if (!(beatValue instanceof Map<?, ?> beat)) {
                errors.add("beats[" + beatIndex + "] must be an object");
                continue;
            }
            var role = text(beat.get("role"));
            if (!ROLES.contains(role)) {
                errors.add("beats[" + beatIndex + "].role is invalid");
            } else if (!seenRoles.add(role)) {
                errors.add("story role is duplicated: " + role);
            }

            if (!(beat.get("shots") instanceof List<?> shots)) {
                errors.add("beats[" + beatIndex + "].shots must be an array");
                continue;
            }

            long beatDuration = 0;
            for (int shotIndex = 0; shotIndex < shots.size(); shotIndex++) {
                var shotValue = shots.get(shotIndex);
                var prefix = "beats[" + beatIndex + "].shots[" + shotIndex + "]";
                if (!(shotValue instanceof Map<?, ?> shot)) {
                    errors.add(prefix + " must be an object");
                    continue;
                }
                shotCount++;
                var shotId = requiredText(shot, "shotId", prefix, errors);
                requiredText(shot, "sourceAssetId", prefix, errors);
                requiredText(shot, "sourceProxyArtifactId", prefix, errors);
                if (shotId != null && !seenShotIds.add(shotId)) {
                    errors.add("shotId is duplicated across beats: " + shotId);
                }

                var start = integer(shot.get("startMs"));
                var end = integer(shot.get("endMs"));
                var sourceIn = integer(shot.get("sourceInMs"));
                var sourceOut = integer(shot.get("sourceOutMs"));
                var selectedDuration = integer(shot.get("selectedDurationMs"));
                if (start == null || end == null || sourceIn == null || sourceOut == null
                    || selectedDuration == null) {
                    errors.add(prefix + " time values must be integers");
                    continue;
                }
                if (start < 0 || end <= start) {
                    errors.add(prefix + " has an invalid source Shot range");
                }
                if (sourceIn < start || sourceOut > end || sourceOut <= sourceIn) {
                    errors.add(prefix + " selected range exceeds its source Shot");
                }
                if (selectedDuration != sourceOut - sourceIn) {
                    errors.add(prefix + ".selectedDurationMs does not match its selected range");
                }
                if (selectedDuration < 600) {
                    errors.add(prefix + ".selectedDurationMs must be at least 600");
                }
                var rank = integer(shot.get("rank"));
                if (rank == null || rank < 1) {
                    errors.add(prefix + ".rank must be positive");
                }
                if (!role.equals(text(shot.get("storyRole")))) {
                    errors.add(prefix + ".storyRole must match its beat role");
                }
                if (!(shot.get("selectionReasons") instanceof List<?>)) {
                    errors.add(prefix + ".selectionReasons must be an array");
                }
                beatDuration += selectedDuration;
            }

            var actualDuration = integer(beat.get("actualDurationMs"));
            if (actualDuration != null && actualDuration != beatDuration) {
                errors.add("beats[" + beatIndex + "].actualDurationMs does not match its Shots");
            }
            var beatTargetDuration = integer(beat.get("targetDurationMs"));
            if (shots.isEmpty() && beatTargetDuration != null && beatTargetDuration != 0) {
                errors.add("beats[" + beatIndex + "].targetDurationMs must be 0 when the beat is empty");
            }
            totalDuration += beatDuration;
        }

        if (!seenRoles.equals(ROLES)) {
            errors.add("beats must contain HOOK, INTRO, JOURNEY, CLIMAX and ENDING exactly once");
        }
        var targetDuration = integer(plan.get("targetDurationMs"));
        if (targetDuration == null || targetDuration <= 0) {
            errors.add("targetDurationMs must be positive");
        } else if (targetDuration != totalDuration) {
            errors.add("targetDurationMs does not match the selected Shots");
        }
        var maxShots = integer(plan.get("maxShots"));
        if (maxShots == null || maxShots < shotCount || maxShots > 100) {
            errors.add("maxShots must cover all selected Shots and must not exceed 100");
        }
        if (shotCount == 0) {
            errors.add("Story Plan must contain at least one Shot");
        }
        failIfInvalid(errors);
    }

    private static String requiredText(
        Map<?, ?> value, String field, String prefix, List<String> errors
    ) {
        var text = text(value.get(field));
        if (text == null) {
            errors.add(prefix + "." + field + " is required");
        }
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
            throw new IllegalArgumentException("Invalid Story Plan: " + String.join("; ", errors));
        }
    }
}
