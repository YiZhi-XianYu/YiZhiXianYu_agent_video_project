/**
 * 通用轮询 composable
 *
 * 在组件 mounted 时开始轮询，unmounted 时自动清理。
 * 支持动态调整轮询间隔和手动启停。
 */
import { ref, unref, onUnmounted } from 'vue';
/**
 * 创建轮询实例
 *
 * @param fn - 每次轮询执行的异步函数
 * @param intervalMs - 轮询间隔（毫秒），支持传入普通数值或 Ref
 */
export function usePolling(fn, intervalMs) {
    const isPolling = ref(false);
    let timer = null;
    const getInterval = () => unref(intervalMs);
    const stop = () => {
        if (timer) {
            clearInterval(timer);
            timer = null;
        }
        isPolling.value = false;
    };
    const start = () => {
        stop();
        isPolling.value = true;
        fn().catch(() => { });
        timer = setInterval(() => {
            fn().catch(() => { });
        }, getInterval());
    };
    onUnmounted(stop);
    return { isPolling, start, stop };
}
