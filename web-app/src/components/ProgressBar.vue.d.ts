type __VLS_Props = {
    /** 进度百分比（0-100），不传或 -1 时为不确定模式 */
    percent?: number;
    /** 颜色方案 */
    variant?: 'accent' | 'success' | 'warning';
    /** 高度（Tailwind 尺寸类） */
    size?: 'sm' | 'md';
};
declare const _default: import("vue").DefineComponent<__VLS_Props, {}, {}, {}, {}, import("vue").ComponentOptionsMixin, import("vue").ComponentOptionsMixin, {}, string, import("vue").PublicProps, Readonly<__VLS_Props> & Readonly<{}>, {
    size: "sm" | "md";
    percent: number;
    variant: "accent" | "success" | "warning";
}, {}, {}, {}, string, import("vue").ComponentProvideOptions, false, {}, any>;
export default _default;
