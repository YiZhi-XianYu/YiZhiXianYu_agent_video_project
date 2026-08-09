import { get, post } from '@/api/client'

export interface ChuxueSession {
  id: string
  projectId: string
  goal: string
  targetDurationMs: number | null
  currentWorkflowRunId: string | null
  status: string
}

export interface ChuxueTurn {
  id: string
  sessionId: string
  sequenceNumber: number
  role: 'USER' | 'ASSISTANT'
  content: string
  planId: string | null
  workflowRunId: string | null
  createdAt: string
}

export interface ChuxueChatResponse {
  reply: string
  shouldPlan: boolean
  llmUsed: boolean
  modelRoute?: Record<string, unknown>
}

export interface ChuxueDecision {
  planId: string | null
  sessionId: string
  turnId: string | null
  goal: string
  intent?: { targetDurationMs: number; clarificationQuestion: string | null }
  preview?: {
    intent: {
      targetDuration: string
      explanation: string
      capabilities?: { vlmAnalysis: boolean; sourceTranscription: boolean; subtitles: boolean; bgm: boolean }
    }
    requiresConfirmation: boolean
    llmUsed?: boolean
    governanceWarnings?: string[]
    automationMode?: string
  }
}

export async function createChuxueSession(projectId: string, goal = ''): Promise<ChuxueSession> {
  return post<ChuxueSession>('/api/v1/agent-sessions', { projectId, goal: goal || '视频创作会话' })
}

export async function getChuxueSession(sessionId: string): Promise<ChuxueSession> {
  return get<ChuxueSession>(`/api/v1/agent-sessions/${sessionId}`)
}

export async function listChuxueSessions(projectId: string): Promise<ChuxueSession[]> {
  return get<ChuxueSession[]>('/api/v1/agent-sessions', { params: { projectId } })
}

export async function getChuxueTurns(sessionId: string): Promise<ChuxueTurn[]> {
  return get<ChuxueTurn[]>(`/api/v1/agent-sessions/${sessionId}/turns`)
}

export async function planWithChuxue(projectId: string, sessionId: string, goal: string, assetIds: string[]): Promise<ChuxueDecision> {
  return post<ChuxueDecision>(`/api/v1/projects/${projectId}/chuxue/plan`, {
    projectId, sessionId, goal, quality: '1080P', assetIds, autoMode: true,
  })
}

export async function chatWithChuxue(projectId: string, sessionId: string, message: string,
  history: Array<{ role: string; content: string }> = []): Promise<ChuxueChatResponse> {
  return post<ChuxueChatResponse>(`/api/v1/projects/${projectId}/chuxue/chat`, {
    projectId, sessionId, message,
    history: history.slice(-20).map(item => ({ role: item.role, content: item.content })),
  }, { timeoutMs: 45_000 })
}

export async function confirmChuxuePlan(projectId: string, planId: string): Promise<{ workflowRunId: string; status: string }> {
  return post<{ workflowRunId: string; status: string }>(`/api/v1/projects/${projectId}/chuxue/plans/${planId}/confirm`)
}
