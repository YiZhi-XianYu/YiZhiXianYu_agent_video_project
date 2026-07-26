import { createRouter, createWebHistory } from 'vue-router';
// 路由懒加载 —— 按 feature 拆分 chunk，减少首屏体积
const ProjectListPage = () => import('@/features/projects/ProjectListPage.vue');
const ProjectDetailPage = () => import('@/features/projects/ProjectDetailPage.vue');
const WorkflowMonitorPage = () => import('@/features/workflow/WorkflowMonitorPage.vue');
const VersionListPage = () => import('@/features/versions/VersionListPage.vue');
const LlmAuditPage = () => import('@/features/audit/LlmAuditPanel.vue');
/** 路由配置 —— 与方案设计中的路由表一致 */
const routes = [
    {
        path: '/',
        name: 'home',
        component: ProjectListPage,
        meta: { title: '项目列表' },
    },
    {
        path: '/projects/:id',
        name: 'project-detail',
        component: ProjectDetailPage,
        meta: { title: '项目详情' },
        props: true,
    },
    {
        path: '/projects/:projectId/runs/:runId',
        name: 'workflow-monitor',
        component: WorkflowMonitorPage,
        meta: { title: 'Workflow 监控' },
        props: true,
    },
    {
        path: '/projects/:projectId/runs/:runId/versions',
        name: 'versions',
        component: VersionListPage,
        meta: { title: '版本管理' },
        props: true,
    },
    {
        path: '/projects/:projectId/audit',
        name: 'audit',
        component: LlmAuditPage,
        meta: { title: 'LLM 审计' },
        props: true,
    },
    // 404 兜底
    {
        path: '/:pathMatch(.*)*',
        name: 'not-found',
        redirect: '/',
    },
];
const router = createRouter({
    history: createWebHistory(import.meta.env.BASE_URL),
    routes,
    scrollBehavior() {
        return { top: 0 };
    },
});
router.afterEach((to) => {
    const title = to.meta.title || 'Agent Video Pipeline';
    document.title = `${title} — Agent Video Pipeline`;
});
export default router;
