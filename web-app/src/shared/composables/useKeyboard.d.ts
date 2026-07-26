/** 快捷键绑定 */
export interface KeyBinding {
    /** 按键名（如 'Space', 'Enter', 'Escape', 'ArrowLeft', 'ArrowRight'） */
    key: string;
    /** 是否按 Ctrl/Cmd */
    ctrl?: boolean;
    /** 回调 */
    handler: () => void;
    /** 描述（文档用） */
    description?: string;
}
/**
 * 创建键盘快捷键管理器。
 * 自动在组件卸载时解绑所有快捷键。
 */
export declare function useKeyboard(bindings: KeyBinding[]): void;
/** 常用快捷键常量（供模板中展示） */
export declare const SHORTCUTS: {
    PLAY_PAUSE: {
        key: string;
        description: string;
    };
    CONFIRM: {
        key: string;
        description: string;
    };
    CLOSE: {
        key: string;
        description: string;
    };
};
