/**
 * 视频播放控制 composable
 *
 * 提供视频播放/暂停/跳转/音量控制，以及播放进度状态。
 * 组件中使用 ref 获取 <video> 元素后调用各方法。
 */
import { ref, type Ref } from 'vue'

export interface UseVideoPlayerReturn {
  videoRef: Ref<HTMLVideoElement | null>
  isPlaying: Ref<boolean>
  currentTime: Ref<number>
  duration: Ref<number>
  togglePlay: () => void
  seek: (seconds: number) => void
  setVolume: (volume: number) => void
  setRate: (rate: number) => void
}

export function useVideoPlayer(): UseVideoPlayerReturn {
  const videoRef = ref<HTMLVideoElement | null>(null)
  const isPlaying = ref(false)
  const currentTime = ref(0)
  const duration = ref(0)

  const getVideo = (): HTMLVideoElement | null => videoRef.value

  const togglePlay = (): void => {
    const video = getVideo()
    if (!video) return
    if (video.paused) {
      video.play().catch(() => { /* 自动播放限制 */ })
    } else {
      video.pause()
    }
    isPlaying.value = !video.paused
  }

  const seek = (seconds: number): void => {
    const video = getVideo()
    if (video) {
      video.currentTime = Math.max(0, Math.min(seconds, video.duration || 0))
      currentTime.value = video.currentTime
    }
  }

  const setVolume = (volume: number): void => {
    const video = getVideo()
    if (video) video.volume = Math.max(0, Math.min(1, volume))
  }

  const setRate = (rate: number): void => {
    const video = getVideo()
    if (video) video.playbackRate = rate
  }

  return { videoRef, isPlaying, currentTime, duration, togglePlay, seek, setVolume, setRate }
}
