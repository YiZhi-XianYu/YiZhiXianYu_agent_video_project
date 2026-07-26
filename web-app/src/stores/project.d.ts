import type { Project, Asset } from '@/api/types';
export declare const useProjectStore: import("pinia").StoreDefinition<"project", Pick<{
    projects: import("vue").Ref<{
        id: string;
        name: string;
        createdAt: string;
        updatedAt: string;
    }[], Project[] | {
        id: string;
        name: string;
        createdAt: string;
        updatedAt: string;
    }[]>;
    currentProjectId: import("vue").Ref<string | null, string | null>;
    assets: import("vue").Ref<{
        id: string;
        fileName: string;
        sizeBytes: number;
        status: string;
        createdAt: string;
    }[], Asset[] | {
        id: string;
        fileName: string;
        sizeBytes: number;
        status: string;
        createdAt: string;
    }[]>;
    loading: import("vue").Ref<boolean, boolean>;
    error: import("vue").Ref<string | null, string | null>;
    currentProject: import("vue").ComputedRef<Project | null>;
    assetCount: import("vue").ComputedRef<number>;
    fetchProjects: () => Promise<void>;
    createProject: (name: string) => Promise<Project>;
    fetchAssets: (projectId: string) => Promise<void>;
    setCurrentProject: (projectId: string | null) => void;
    clearError: () => void;
}, "projects" | "currentProjectId" | "assets" | "loading" | "error">, Pick<{
    projects: import("vue").Ref<{
        id: string;
        name: string;
        createdAt: string;
        updatedAt: string;
    }[], Project[] | {
        id: string;
        name: string;
        createdAt: string;
        updatedAt: string;
    }[]>;
    currentProjectId: import("vue").Ref<string | null, string | null>;
    assets: import("vue").Ref<{
        id: string;
        fileName: string;
        sizeBytes: number;
        status: string;
        createdAt: string;
    }[], Asset[] | {
        id: string;
        fileName: string;
        sizeBytes: number;
        status: string;
        createdAt: string;
    }[]>;
    loading: import("vue").Ref<boolean, boolean>;
    error: import("vue").Ref<string | null, string | null>;
    currentProject: import("vue").ComputedRef<Project | null>;
    assetCount: import("vue").ComputedRef<number>;
    fetchProjects: () => Promise<void>;
    createProject: (name: string) => Promise<Project>;
    fetchAssets: (projectId: string) => Promise<void>;
    setCurrentProject: (projectId: string | null) => void;
    clearError: () => void;
}, "currentProject" | "assetCount">, Pick<{
    projects: import("vue").Ref<{
        id: string;
        name: string;
        createdAt: string;
        updatedAt: string;
    }[], Project[] | {
        id: string;
        name: string;
        createdAt: string;
        updatedAt: string;
    }[]>;
    currentProjectId: import("vue").Ref<string | null, string | null>;
    assets: import("vue").Ref<{
        id: string;
        fileName: string;
        sizeBytes: number;
        status: string;
        createdAt: string;
    }[], Asset[] | {
        id: string;
        fileName: string;
        sizeBytes: number;
        status: string;
        createdAt: string;
    }[]>;
    loading: import("vue").Ref<boolean, boolean>;
    error: import("vue").Ref<string | null, string | null>;
    currentProject: import("vue").ComputedRef<Project | null>;
    assetCount: import("vue").ComputedRef<number>;
    fetchProjects: () => Promise<void>;
    createProject: (name: string) => Promise<Project>;
    fetchAssets: (projectId: string) => Promise<void>;
    setCurrentProject: (projectId: string | null) => void;
    clearError: () => void;
}, "fetchProjects" | "createProject" | "fetchAssets" | "setCurrentProject" | "clearError">>;
