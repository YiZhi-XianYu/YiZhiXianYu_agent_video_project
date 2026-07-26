/**
 * 视频播放控制 composable
 *
 * 提供视频播放/暂停/跳转/音量控制，以及播放进度状态。
 * 组件中使用 ref 获取 <video> 元素后调用各方法。
 */
import { ref } from 'vue';
export function useVideoPlayer() {
    const videoRef = ref(null);
    const isPlaying = ref(false);
    const currentTime = ref(0);
    const duration = ref(0);
    const getVideo = () => videoRef.value;
    const togglePlay = () => {
        const video = getVideo();
        if (!video)
            return;
        if (video.paused) {
            video.play().catch(() => { });
        }
        else {
            video.pause();
        }
        isPlaying.value = !video.paused;
    };
    const seek = (seconds) => {
        const video = getVideo();
        if (video) {
            video.currentTime = Math.max(0, Math.min(seconds, video.duration || 0));
            currentTime.value = video.currentTime;
        }
    };
    const setVolume = (volume) => {
        const video = getVideo();
        if (video)
            video.volume = Math.max(0, Math.min(1, volume));
    };
    const setRate = (rate) => {
        const video = getVideo();
        if (video)
            video.playbackRate = rate;
    };
    return { videoRef, isPlaying, currentTime, duration, togglePlay, seek, setVolume, setRate };
}
