/** Toast 通知数据结构（导出以便其他模块引用类型） */
export interface Toast {
    id: number;
    message: string;
    type: 'info' | 'success' | 'warning' | 'error';
}
export declare const useUiStore: import("pinia").StoreDefinition<"ui", Pick<{
    autoMode: import("vue").Ref<boolean, boolean>;
    globalLoading: import("vue").Ref<boolean, boolean>;
    globalLoadingText: import("vue").Ref<string, string>;
    toasts: import("vue").Ref<{
        id: number;
        message: string;
        type: "info" | "success" | "warning" | "error";
    }[], Toast[] | {
        id: number;
        message: string;
        type: "info" | "success" | "warning" | "error";
    }[]>;
    showToast: (message: string, type?: Toast["type"]) => void;
    setGlobalLoading: (loading: boolean, text?: string) => void;
    toggleAutoMode: () => void;
}, "autoMode" | "globalLoading" | "globalLoadingText" | "toasts">, Pick<{
    autoMode: import("vue").Ref<boolean, boolean>;
    globalLoading: import("vue").Ref<boolean, boolean>;
    globalLoadingText: import("vue").Ref<string, string>;
    toasts: import("vue").Ref<{
        id: number;
        message: string;
        type: "info" | "success" | "warning" | "error";
    }[], Toast[] | {
        id: number;
        message: string;
        type: "info" | "success" | "warning" | "error";
    }[]>;
    showToast: (message: string, type?: Toast["type"]) => void;
    setGlobalLoading: (loading: boolean, text?: string) => void;
    toggleAutoMode: () => void;
}, never>, Pick<{
    autoMode: import("vue").Ref<boolean, boolean>;
    globalLoading: import("vue").Ref<boolean, boolean>;
    globalLoadingText: import("vue").Ref<string, string>;
    toasts: import("vue").Ref<{
        id: number;
        message: string;
        type: "info" | "success" | "warning" | "error";
    }[], Toast[] | {
        id: number;
        message: string;
        type: "info" | "success" | "warning" | "error";
    }[]>;
    showToast: (message: string, type?: Toast["type"]) => void;
    setGlobalLoading: (loading: boolean, text?: string) => void;
    toggleAutoMode: () => void;
}, "showToast" | "setGlobalLoading" | "toggleAutoMode">>;
