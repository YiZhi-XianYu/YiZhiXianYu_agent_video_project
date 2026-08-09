import { get, post } from '@/api/client'

export interface ChuxueSession {
  id: string
  projectId: string
  goal: string
  targetDurationMs: number | null
  currentWorkflowRunId: string | null
  status: string
}

export interface ChuxueDecision {
  planId: string | null
  sessionId: string
  turnId: string | null
  goal: string
  intent?: { targetDurationMs: number; clarificationQuestion: string | null }
  preview?: { intent: { targetDuration: string; explanation: string }; requiresConfirmation: boolean }
}

export async function createChuxueSession(projectId: string, goal = ''): Promise<ChuxueSession> {
  return post<ChuxueSession>('/api/v1/agent-sessions', { projectId, goal: goal || '视频创作会话' })
}

export async function getChuxueSession(sessionId: string): Promise<ChuxueSession> {
  return get<ChuxueSession>(`/api/v1/agent-sessions/${sessionId}`)
}

export async function planWithChuxue(projectId: string, sessionId: string, goal: string, assetIds: string[]): Promise<ChuxueDecision> {
  return post<ChuxueDecision>(`/api/v1/projects/${projectId}/chuxue/plan`, {
    projectId, sessionId, goal, quality: '1080P', assetIds, autoMode: true,
  })
}

export async function confirmChuxuePlan(projectId: string, planId: string): Promise<{ workflowRunId: string; status: string }> {
  return post<{ workflowRunId: string; status: string }>(`/api/v1/projects/${projectId}/chuxue/plans/${planId}/confirm`)
}
