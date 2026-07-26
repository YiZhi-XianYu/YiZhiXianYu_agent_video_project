/**
 * 视频播放控制 composable
 *
 * 提供视频播放/暂停/跳转/音量控制，以及播放进度状态。
 * 组件中使用 ref 获取 <video> 元素后调用各方法。
 */
import { type Ref } from 'vue';
export interface UseVideoPlayerReturn {
    videoRef: Ref<HTMLVideoElement | null>;
    isPlaying: Ref<boolean>;
    currentTime: Ref<number>;
    duration: Ref<number>;
    togglePlay: () => void;
    seek: (seconds: number) => void;
    setVolume: (volume: number) => void;
    setRate: (rate: number) => void;
}
export declare function useVideoPlayer(): UseVideoPlayerReturn;
