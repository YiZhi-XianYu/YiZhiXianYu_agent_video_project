import { defineConfig } from 'vite';
import vue from '@vitejs/plugin-vue';
import { resolve } from 'path';
// Vite 构建配置
// 开发时通过 proxy 将 /api 和 /internal 请求转发到 Java 后端 (:8080)
export default defineConfig({
    plugins: [vue()],
    resolve: {
        alias: {
            '@': resolve('./src'),
        },
    },
    server: {
        port: 5173,
        proxy: {
            '/api': {
                target: 'http://localhost:8080',
                changeOrigin: true,
            },
            '/internal': {
                target: 'http://localhost:8080',
                changeOrigin: true,
            },
        },
    },
    // 生产构建输出到 Java 后端的 static 目录
    build: {
        outDir: '../control-plane/src/main/resources/static',
        emptyOutDir: true,
    },
});
