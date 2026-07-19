from __future__ import annotations

from typing import Any


class TimelineValidator:
    @staticmethod
    def validate(timeline: dict[str, Any]) -> list[str]:
        errors: list[str] = []
        canvas = timeline.get("canvas") or {}
        width = canvas.get("width")
        height = canvas.get("height")
        fps = canvas.get("fps")
        if not isinstance(width, int) or width < 320 or width % 2:
            errors.append("canvas.width must be an even integer of at least 320")
        if not isinstance(height, int) or height < 240 or height % 2:
            errors.append("canvas.height must be an even integer of at least 240")
        if not isinstance(fps, int) or fps < 1 or fps > 120:
            errors.append("canvas.fps must be between 1 and 120")

        tracks = timeline.get("tracks")
        if not isinstance(tracks, list) or len(tracks) != 1 or tracks[0].get("type") != "VIDEO":
            errors.append("Timeline must contain exactly one VIDEO track")
            return errors
        clips = tracks[0].get("clips")
        if not isinstance(clips, list) or not clips:
            errors.append("VIDEO track must contain at least one Clip")
            return errors

        clip_ids: set[str] = set()
        shot_ids: set[str] = set()
        expected_timeline_in = 0
        for index, clip in enumerate(clips):
            prefix = f"clips[{index}]"
            clip_id = clip.get("clipId")
            shot_id = clip.get("shotId")
            if not isinstance(clip_id, str) or not clip_id:
                errors.append(f"{prefix}.clipId is required")
            elif clip_id in clip_ids:
                errors.append(f"{prefix}.clipId is duplicated")
            else:
                clip_ids.add(clip_id)
            if not isinstance(shot_id, str) or not shot_id:
                errors.append(f"{prefix}.shotId is required")
            elif shot_id in shot_ids:
                errors.append(f"{prefix}.shotId is duplicated")
            else:
                shot_ids.add(shot_id)
            for field in ("assetId", "sourceProxyArtifactId"):
                if not isinstance(clip.get(field), str) or not clip[field]:
                    errors.append(f"{prefix}.{field} is required")

            source_in = clip.get("sourceInMs")
            source_out = clip.get("sourceOutMs")
            shot_start = clip.get("sourceShotStartMs")
            shot_end = clip.get("sourceShotEndMs")
            timeline_in = clip.get("timelineInMs")
            timeline_out = clip.get("timelineOutMs")
            if not all(isinstance(value, int) for value in (
                source_in, source_out, shot_start, shot_end, timeline_in, timeline_out
            )):
                errors.append(f"{prefix} time values must be integers")
                continue
            if shot_start < 0 or shot_end <= shot_start:
                errors.append(f"{prefix} has an invalid source Shot range")
            if source_in < shot_start or source_out > shot_end or source_out <= source_in:
                errors.append(f"{prefix} source range exceeds its Shot")
            if timeline_in != expected_timeline_in:
                errors.append(f"{prefix} does not start at the previous Clip boundary")
            if timeline_out <= timeline_in:
                errors.append(f"{prefix} has an invalid Timeline range")
            if timeline_out - timeline_in != source_out - source_in:
                errors.append(f"{prefix} source and Timeline durations differ")
            expected_timeline_in = timeline_out
            if clip.get("playbackRate") != 1.0:
                errors.append(f"{prefix}.playbackRate must be 1.0")
            transition = clip.get("transitionIn") or {}
            if transition.get("type") != "CUT" or transition.get("durationMs") != 0:
                errors.append(f"{prefix}.transitionIn must be a zero-duration CUT")
            if not isinstance(clip.get("selectionRank"), int) or clip["selectionRank"] < 1:
                errors.append(f"{prefix}.selectionRank must be positive")
            if clip.get("storyRole") not in {"HOOK", "INTRO", "JOURNEY", "CLIMAX", "ENDING"}:
                errors.append(f"{prefix}.storyRole is invalid")
            if not isinstance(clip.get("selectionReasons"), list):
                errors.append(f"{prefix}.selectionReasons must be an array")

        if timeline.get("durationMs") != expected_timeline_in:
            errors.append("Timeline duration does not match the VIDEO track")
        return errors
