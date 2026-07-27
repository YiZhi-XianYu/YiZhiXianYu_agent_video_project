from __future__ import annotations

import gc
import logging
import ctypes

logger = logging.getLogger(__name__)


def release_models() -> None:
    """Release process-local model references without deleting disk caches."""
    from app.core.vision_models import release_clip_model
    from app.tools.audio_transcribe import release_whisper_model

    released = release_clip_model() | release_whisper_model()
    if released:
        gc.collect()
        _trim_native_heap()
        logger.info("Released idle ML models from process memory")


def _trim_native_heap() -> None:
    """Return free glibc arenas to the container after native ML runtimes exit."""
    try:
        libc = ctypes.CDLL("libc.so.6")
        malloc_trim = libc.malloc_trim
        malloc_trim.argtypes = [ctypes.c_size_t]
        malloc_trim.restype = ctypes.c_int
        malloc_trim(0)
    except (AttributeError, OSError):
        return
