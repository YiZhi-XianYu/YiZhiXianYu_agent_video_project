from __future__ import annotations

import hashlib
import json
import logging
import shutil
import urllib.parse
import urllib.request
from dataclasses import dataclass
from pathlib import Path
from typing import Protocol

from app.core.config import settings

logger = logging.getLogger(__name__)


@dataclass(frozen=True)
class MusicSearchProfile:
    primary_mood: str
    tags: tuple[str, ...]
    batch_index: int = 0
    seed: str = "default"


@dataclass(frozen=True)
class MusicCandidate:
    provider: str
    track_id: str
    title: str
    artist: str
    duration_ms: int
    mood: str
    source_url: str
    audio_url: str
    license_name: str = ""
    license_url: str = ""
    attribution: str = ""
    score: float = 0.0
    local_path: Path | None = None
    matched_tag: str = ""
    instrumental: bool = False


class MusicProvider(Protocol):
    name: str

    def search(
        self, profile: MusicSearchProfile, target_duration_ms: int, limit: int
    ) -> list[MusicCandidate]: ...

    def cache(self, candidate: MusicCandidate) -> Path: ...


class JamendoMusicProvider:
    name = "jamendo"
    api_url = "https://api.jamendo.com/v3.0/tracks/"

    def __init__(self, client_id: str, cache_root: Path) -> None:
        self.client_id = client_id.strip()
        self.cache_root = cache_root / self.name

    def search(
        self, profile: MusicSearchProfile, target_duration_ms: int, limit: int
    ) -> list[MusicCandidate]:
        if not self.client_id:
            return []
        per_tag_limit = max(8, min(25, limit // max(len(profile.tags), 1) + 4))
        seed_page = int(hashlib.sha256(profile.seed.encode("utf-8")).hexdigest()[:4], 16) % 3
        offset = min(180, (profile.batch_index + seed_page) * per_tag_limit)
        candidates: list[MusicCandidate] = []
        seen: set[str] = set()
        for tag in profile.tags:
            params = {
                "client_id": self.client_id,
                "format": "json",
                "limit": per_tag_limit,
                "offset": offset,
                "tags": tag,
                "include": "musicinfo+licenses",
                "audioformat": "mp32",
                "order": "relevance",
            }
            request = urllib.request.Request(
                f"{self.api_url}?{urllib.parse.urlencode(params)}",
                headers={"Accept": "application/json", "User-Agent": "AgentVideoPipeline/1.0"},
            )
            try:
                with urllib.request.urlopen(
                    request, timeout=settings.music_request_timeout_seconds
                ) as response:
                    payload = json.load(response)
            except Exception as exc:
                logger.warning("Jamendo search unavailable for tag '%s': %s", tag, exc)
                continue

            for index, track in enumerate(payload.get("results", [])):
                track_id = str(track.get("id") or "")
                if not track_id or track_id in seen:
                    continue
                audio_url = str(track.get("audiodownload") or track.get("audio") or "").strip()
                if not audio_url:
                    continue
                seen.add(track_id)
                duration_ms = int(float(track.get("duration") or 0) * 1000)
                duration_distance = abs(duration_ms - target_duration_ms) / max(target_duration_ms, 1)
                music_info = track.get("musicinfo") or {}
                tags = music_info.get("tags") or {}
                tag_values = [
                    str(item).lower() for values in tags.values()
                    if isinstance(values, list) for item in values
                ]
                mood_bonus = 20 if tag.lower() in tag_values else 8
                instrumental = music_info.get("vocalinstrumental") == "instrumental"
                instrumental_bonus = 10 if instrumental else 0
                score = max(
                    0.0,
                    100 - index * 2 - min(45, duration_distance * 22)
                    + mood_bonus + instrumental_bonus,
                )
                license_url = str(track.get("license_ccurl") or "")
                candidates.append(MusicCandidate(
                    provider=self.name,
                    track_id=track_id,
                    title=str(track.get("name") or "未命名音乐"),
                    artist=str(track.get("artist_name") or "未知作者"),
                    duration_ms=duration_ms,
                    mood=profile.primary_mood,
                    source_url=str(track.get("shareurl") or track.get("shorturl") or ""),
                    audio_url=audio_url,
                    license_name="Creative Commons" if license_url else "Jamendo",
                    license_url=license_url,
                    attribution=f"{track.get('name') or 'Untitled'} - {track.get('artist_name') or 'Unknown'}",
                    score=round(score, 2),
                    matched_tag=tag,
                    instrumental=instrumental,
                ))
                if len(candidates) >= limit:
                    return candidates
        return candidates

    def cache(self, candidate: MusicCandidate) -> Path:
        self.cache_root.mkdir(parents=True, exist_ok=True)
        destination = self.cache_root / f"{candidate.track_id}.mp3"
        if destination.is_file() and destination.stat().st_size > 0:
            return destination
        temporary = destination.with_suffix(".download")
        request = urllib.request.Request(candidate.audio_url, headers={"User-Agent": "AgentVideoPipeline/1.0"})
        try:
            with urllib.request.urlopen(request, timeout=settings.music_request_timeout_seconds) as response:
                with temporary.open("wb") as output:
                    shutil.copyfileobj(response, output)
            temporary.replace(destination)
            return destination
        finally:
            temporary.unlink(missing_ok=True)


class LocalMusicProvider:
    name = "local"

    def __init__(self, library_root: Path) -> None:
        self.library_root = library_root

    def search(
        self, profile: MusicSearchProfile, target_duration_ms: int, limit: int
    ) -> list[MusicCandidate]:
        if not self.library_root.exists():
            return []
        candidates = []
        for path in sorted(self.library_root.glob("*.mp3")):
            matched_tag = next(
                (tag for tag in profile.tags if tag.lower() in path.stem.lower()),
                profile.primary_mood,
            )
            candidates.append(MusicCandidate(
                provider=self.name,
                track_id=path.stem,
                title=path.stem.replace("_", " "),
                artist="本地曲库",
                duration_ms=0,
                mood=profile.primary_mood,
                source_url="",
                audio_url=path.resolve().as_uri(),
                license_name="本地素材",
                attribution=path.name,
                score=100 if matched_tag.lower() in path.stem.lower() else 50,
                local_path=path,
                matched_tag=matched_tag,
                instrumental=True,
            ))
        return sorted(candidates, key=lambda item: (-item.score, item.title))[:limit]

    def cache(self, candidate: MusicCandidate) -> Path:
        if candidate.local_path is None or not candidate.local_path.is_file():
            raise ValueError("Local music candidate is unavailable")
        return candidate.local_path


def configured_music_providers() -> list[MusicProvider]:
    providers: list[MusicProvider] = []
    if settings.music_provider.strip().lower() in {"jamendo", "auto"} and settings.jamendo_client_id.strip():
        providers.append(JamendoMusicProvider(settings.jamendo_client_id, settings.music_cache_root))
    providers.append(LocalMusicProvider(settings.bgm_library_root))
    return providers
