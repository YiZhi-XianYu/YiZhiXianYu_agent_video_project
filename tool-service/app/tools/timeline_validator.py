from __future__ import annotations

from typing import Any

# Valid transition types and their constraints
_TRANSITION_RULES = {
    "CUT":            {"min": 0, "max": 0},
    "FADE":           {"min": 200, "max": 2000},
    "CROSS_DISSOLVE": {"min": 200, "max": 2000},
}
_ALLOWED_TRANSITION_TYPES = set(_TRANSITION_RULES.keys())
_ALLOWED_STORY_ROLES = {"HOOK", "INTRO", "JOURNEY", "CLIMAX", "ENDING"}
_ALLOWED_SUBTITLE_FORMATS = {"SRT", "VTT"}


class TimelineValidator:
    @staticmethod
    def validate(timeline: dict[str, Any]) -> list[str]:
        errors: list[str] = []

        # ── Canvas ──
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

        # ── Tracks ──
        tracks = timeline.get("tracks")
        if not isinstance(tracks, list) or not tracks:
            errors.append("Timeline must contain at least one track")
            return errors

        video_track = _find_track(tracks, "VIDEO")
        if video_track is None:
            errors.append("Timeline must contain exactly one VIDEO track")
            return errors

        # ── Validate VIDEO track clips ──
        clip_errors, total_clip_duration = _validate_video_clips(video_track)
        errors.extend(clip_errors)

        # ── Total duration consistency ──
        if timeline.get("durationMs") != total_clip_duration:
            errors.append("Timeline duration does not match the VIDEO track")

        # ── Validate AUDIO track ──
        audio_track = _find_track(tracks, "AUDIO")
        if audio_track is not None:
            audio_errors = _validate_audio_track(audio_track)
            errors.extend(audio_errors)

        # ── Validate SUBTITLE track ──
        subtitle_track = _find_track(tracks, "SUBTITLE")
        if subtitle_track is not None:
            subtitle_errors = _validate_subtitle_track(subtitle_track)
            errors.extend(subtitle_errors)

        # ── Unknown track types (forward compat) ──
        for track in tracks:
            track_type = track.get("type", "")
            if track_type not in ("VIDEO", "AUDIO", "SUBTITLE"):
                errors.append(f"Unknown track type: {track_type}")

        return errors


def _validate_video_clips(track: dict[str, Any]) -> tuple[list[str], int]:
    errors: list[str] = []
    clips = track.get("clips")
    if not isinstance(clips, list) or not clips:
        errors.append("VIDEO track must contain at least one Clip")
        return errors, 0

    clip_ids: set[str] = set()
    shot_ids: set[str] = set()
    previous_timeline_out = 0

    for index, clip in enumerate(clips):
        prefix = f"clips[{index}]"

        # ── Identity ──
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

        # ── Time bounds ──
        source_in = clip.get("sourceInMs")
        source_out = clip.get("sourceOutMs")
        shot_start = clip.get("sourceShotStartMs")
        shot_end = clip.get("sourceShotEndMs")
        timeline_in = clip.get("timelineInMs")
        timeline_out = clip.get("timelineOutMs")
        if not all(isinstance(v, int) for v in (
            source_in, source_out, shot_start, shot_end, timeline_in, timeline_out
        )):
            errors.append(f"{prefix} time values must be integers")
            continue
        if shot_start < 0 or shot_end <= shot_start:
            errors.append(f"{prefix} has an invalid source Shot range")
        if source_in < shot_start or source_out > shot_end or source_out <= source_in:
            errors.append(f"{prefix} source range exceeds its Shot")
        if timeline_out <= timeline_in:
            errors.append(f"{prefix} has an invalid Timeline range")
        clip_duration = timeline_out - timeline_in
        if clip_duration != source_out - source_in:
            errors.append(f"{prefix} source and Timeline durations differ")
        expected_timeline_in = timeline_out

        # ── Playback ──
        if clip.get("playbackRate") != 1.0:
            errors.append(f"{prefix}.playbackRate must be 1.0")

        # ── Transition ──
        transition = clip.get("transitionIn") or {}
        transition_type = transition.get("type", "")
        transition_dur = transition.get("durationMs", 0)

        if transition_type not in _ALLOWED_TRANSITION_TYPES:
            errors.append(
                f"{prefix}.transitionIn.type must be one of "
                f"{sorted(_ALLOWED_TRANSITION_TYPES)}, got: {transition_type}"
            )
        else:
            rules = _TRANSITION_RULES[transition_type]
            if not isinstance(transition_dur, int) or transition_dur < rules["min"] or transition_dur > rules["max"]:
                errors.append(
                    f"{prefix}.transitionIn.durationMs {transition_dur} is out of range "
                    f"for {transition_type} (allowed: {rules['min']}–{rules['max']})"
                )
            # First clip: CUT or FADE (fade-from-black) allowed; CROSS_DISSOLVE rejected (needs prior clip)
            if index == 0 and transition_type == "CROSS_DISSOLVE":
                errors.append(
                    f"{prefix} is the first clip and must use CUT or FADE transition (CROSS_DISSOLVE requires a preceding clip)"
                )
            # Transition duration must not exceed or equal clip duration
            if transition_dur >= clip_duration:
                errors.append(
                    f"{prefix}.transitionIn duration ({transition_dur}ms) must be less than clip duration ({clip_duration}ms)"
                )

        expected_timeline_in = previous_timeline_out
        if index > 0 and transition_type == "CROSS_DISSOLVE":
            expected_timeline_in -= transition_dur
        if timeline_in != expected_timeline_in:
            errors.append(f"{prefix} has an invalid Timeline position for {transition_type}")
        previous_timeline_out = timeline_out

        # ── Metadata ──
        if not isinstance(clip.get("selectionRank"), int) or clip["selectionRank"] < 1:
            errors.append(f"{prefix}.selectionRank must be positive")
        if clip.get("storyRole") not in _ALLOWED_STORY_ROLES:
            errors.append(f"{prefix}.storyRole is invalid")
        if not isinstance(clip.get("selectionReasons"), list):
            errors.append(f"{prefix}.selectionReasons must be an array")

    # ── Total duration ──
    return errors, previous_timeline_out


def _validate_audio_track(track: dict[str, Any]) -> list[str]:
    errors: list[str] = []
    source = track.get("source") or {}
    uri = source.get("uri", "")
    volume = source.get("volume", 0)

    if not isinstance(uri, str) or not uri:
        errors.append("AUDIO track source.uri is required")
    if not isinstance(volume, (int, float)) or volume < 0 or volume > 1:
        errors.append(f"AUDIO track source.volume must be 0.0–1.0, got: {volume}")

    ducking = source.get("ducking")
    if ducking is not None:
        if not isinstance(ducking, dict):
            errors.append("AUDIO track source.ducking must be an object")
        else:
            duck_vol = ducking.get("duckVolume", 0.1)
            if not isinstance(duck_vol, (int, float)) or duck_vol < 0 or duck_vol > 1:
                errors.append("AUDIO track source.ducking.duckVolume must be 0.0–1.0")

    return errors


def _validate_subtitle_track(track: dict[str, Any]) -> list[str]:
    errors: list[str] = []
    source = track.get("source") or {}
    uri = source.get("uri", "")
    fmt = source.get("format", "")

    if not isinstance(uri, str) or not uri:
        errors.append("SUBTITLE track source.uri is required")
    if fmt not in _ALLOWED_SUBTITLE_FORMATS:
        errors.append(f"SUBTITLE track source.format must be one of {sorted(_ALLOWED_SUBTITLE_FORMATS)}, got: {fmt}")

    return errors


def _find_track(tracks: list[dict[str, Any]], track_type: str) -> dict[str, Any] | None:
    """Return the first track matching the given type, or None."""
    for track in tracks:
        if track.get("type") == track_type:
            return track
    return None
