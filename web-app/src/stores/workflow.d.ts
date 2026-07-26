import type { WorkflowRunDetail, TaskRun, GateInfo, RunStatus } from '@/api/types';
export declare const useWorkflowStore: import("pinia").StoreDefinition<"workflow", Pick<{
    run: import("vue").Ref<{
        tasks: {
            id: string;
            nodeKey: string;
            toolName: string;
            status: import("@/api/types").TaskStatus;
            attempt: number;
            maxAttempts: number;
            errorMessage: string | null;
            createdAt: string;
            updatedAt: string;
        }[];
        id: string;
        projectId: string;
        definitionKey: string;
        status: RunStatus;
        autoMode: boolean;
        currentGateKey: string | null;
        gates: {
            gateKey: string;
            label: string;
            description: string;
        }[];
        createdAt: string;
        updatedAt: string;
    } | null, WorkflowRunDetail | {
        tasks: {
            id: string;
            nodeKey: string;
            toolName: string;
            status: import("@/api/types").TaskStatus;
            attempt: number;
            maxAttempts: number;
            errorMessage: string | null;
            createdAt: string;
            updatedAt: string;
        }[];
        id: string;
        projectId: string;
        definitionKey: string;
        status: RunStatus;
        autoMode: boolean;
        currentGateKey: string | null;
        gates: {
            gateKey: string;
            label: string;
            description: string;
        }[];
        createdAt: string;
        updatedAt: string;
    } | null>;
    loading: import("vue").Ref<boolean, boolean>;
    error: import("vue").Ref<string | null, string | null>;
    status: import("vue").ComputedRef<RunStatus | null>;
    isPaused: import("vue").ComputedRef<boolean>;
    isRunning: import("vue").ComputedRef<boolean>;
    isTerminal: import("vue").ComputedRef<boolean>;
    currentGate: import("vue").ComputedRef<GateInfo | null>;
    tasks: import("vue").ComputedRef<TaskRun[]>;
    completedTaskCount: import("vue").ComputedRef<number>;
    totalTaskCount: import("vue").ComputedRef<number>;
    progressPercent: import("vue").ComputedRef<number>;
    fetchRun: (workflowRunId: string) => Promise<void>;
    continueWorkflow: (workflowRunId: string) => Promise<void>;
    clear: () => void;
}, "loading" | "error" | "run">, Pick<{
    run: import("vue").Ref<{
        tasks: {
            id: string;
            nodeKey: string;
            toolName: string;
            status: import("@/api/types").TaskStatus;
            attempt: number;
            maxAttempts: number;
            errorMessage: string | null;
            createdAt: string;
            updatedAt: string;
        }[];
        id: string;
        projectId: string;
        definitionKey: string;
        status: RunStatus;
        autoMode: boolean;
        currentGateKey: string | null;
        gates: {
            gateKey: string;
            label: string;
            description: string;
        }[];
        createdAt: string;
        updatedAt: string;
    } | null, WorkflowRunDetail | {
        tasks: {
            id: string;
            nodeKey: string;
            toolName: string;
            status: import("@/api/types").TaskStatus;
            attempt: number;
            maxAttempts: number;
            errorMessage: string | null;
            createdAt: string;
            updatedAt: string;
        }[];
        id: string;
        projectId: string;
        definitionKey: string;
        status: RunStatus;
        autoMode: boolean;
        currentGateKey: string | null;
        gates: {
            gateKey: string;
            label: string;
            description: string;
        }[];
        createdAt: string;
        updatedAt: string;
    } | null>;
    loading: import("vue").Ref<boolean, boolean>;
    error: import("vue").Ref<string | null, string | null>;
    status: import("vue").ComputedRef<RunStatus | null>;
    isPaused: import("vue").ComputedRef<boolean>;
    isRunning: import("vue").ComputedRef<boolean>;
    isTerminal: import("vue").ComputedRef<boolean>;
    currentGate: import("vue").ComputedRef<GateInfo | null>;
    tasks: import("vue").ComputedRef<TaskRun[]>;
    completedTaskCount: import("vue").ComputedRef<number>;
    totalTaskCount: import("vue").ComputedRef<number>;
    progressPercent: import("vue").ComputedRef<number>;
    fetchRun: (workflowRunId: string) => Promise<void>;
    continueWorkflow: (workflowRunId: string) => Promise<void>;
    clear: () => void;
}, "status" | "tasks" | "isPaused" | "isRunning" | "isTerminal" | "currentGate" | "completedTaskCount" | "totalTaskCount" | "progressPercent">, Pick<{
    run: import("vue").Ref<{
        tasks: {
            id: string;
            nodeKey: string;
            toolName: string;
            status: import("@/api/types").TaskStatus;
            attempt: number;
            maxAttempts: number;
            errorMessage: string | null;
            createdAt: string;
            updatedAt: string;
        }[];
        id: string;
        projectId: string;
        definitionKey: string;
        status: RunStatus;
        autoMode: boolean;
        currentGateKey: string | null;
        gates: {
            gateKey: string;
            label: string;
            description: string;
        }[];
        createdAt: string;
        updatedAt: string;
    } | null, WorkflowRunDetail | {
        tasks: {
            id: string;
            nodeKey: string;
            toolName: string;
            status: import("@/api/types").TaskStatus;
            attempt: number;
            maxAttempts: number;
            errorMessage: string | null;
            createdAt: string;
            updatedAt: string;
        }[];
        id: string;
        projectId: string;
        definitionKey: string;
        status: RunStatus;
        autoMode: boolean;
        currentGateKey: string | null;
        gates: {
            gateKey: string;
            label: string;
            description: string;
        }[];
        createdAt: string;
        updatedAt: string;
    } | null>;
    loading: import("vue").Ref<boolean, boolean>;
    error: import("vue").Ref<string | null, string | null>;
    status: import("vue").ComputedRef<RunStatus | null>;
    isPaused: import("vue").ComputedRef<boolean>;
    isRunning: import("vue").ComputedRef<boolean>;
    isTerminal: import("vue").ComputedRef<boolean>;
    currentGate: import("vue").ComputedRef<GateInfo | null>;
    tasks: import("vue").ComputedRef<TaskRun[]>;
    completedTaskCount: import("vue").ComputedRef<number>;
    totalTaskCount: import("vue").ComputedRef<number>;
    progressPercent: import("vue").ComputedRef<number>;
    fetchRun: (workflowRunId: string) => Promise<void>;
    continueWorkflow: (workflowRunId: string) => Promise<void>;
    clear: () => void;
}, "fetchRun" | "continueWorkflow" | "clear">>;
