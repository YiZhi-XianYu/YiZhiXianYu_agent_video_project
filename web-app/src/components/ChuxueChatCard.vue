<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { X, Send, Loader2, CheckCircle2, Plus, MessageSquare, Workflow } from 'lucide-vue-next'
import WorkflowMonitorPage from '@/features/workflow/WorkflowMonitorPage.vue'
import { useChuxueStore } from '@/stores/chuxue'
import { useProjectStore } from '@/stores/project'
import {
  chatWithChuxue,
  confirmChuxuePlan,
  createChuxueSession,
  getChuxueRuntime,
  getChuxueTurns,
  getChuxueGate,
  listChuxueSessions,
  planWithChuxue,
  type ChuxueRuntime,
  type ChuxueSession,
} from '@/api/chuxue'

const chuxue = useChuxueStore()
const project = useProjectStore()
const input = ref('')
const sending = ref(false)
const loadingSessions = ref(false)
const pendingPlan = ref<string | null>(null)
const currentGate = ref<Awaited<ReturnType<typeof getChuxueGate>> | null>(null)
const embeddedGate = computed(() => Boolean(
  currentGate.value?.gateKey
  && currentGate.value.workflowStatus === 'PAUSED'
  && runtime.value?.workflowRunId
  && runtime.value.runtime?.workflowStatus === 'PAUSED',
))
const sessionList = ref<ChuxueSession[]>([])
const runtime = ref<ChuxueRuntime | null>(null)
const messages = computed(() => chuxue.chatMessages)
const messagesRef = ref<HTMLElement | null>(null)
let runtimeTimer: number | null = null
let sessionsRefreshing = false
let lastWorkflowStatus: string | null = null

const workflowActive = computed(() => {
  const status = runtime.value?.runtime?.workflowStatus
  return Boolean(runtime.value?.workflowRunId && status && !['SUCCEEDED', 'FAILED'].includes(status))
})

const workflowStatusText = computed(() => {
  const current = runtime.value?.runtime
  if (!runtime.value?.workflowRunId || !current?.workflowStatus) return ''
  if (current.workflowStatus === 'SUCCEEDED') return 'Workflow 已完成'
  if (current.workflowStatus === 'FAILED') return 'Workflow 已失败'
  if (current.workflowStatus === 'PAUSED') return current.nextAction || 'Workflow 正在等待你的确认'
  return current.nextAction || 'Workflow 正在执行'
})

function scrollToBottom(): void {
  void nextTick(() => {
    const el = messagesRef.value
    if (el) el.scrollTo({ top: el.scrollHeight, behavior: 'smooth' })
  })
}

function assistant(content: string): void {
  if (!content) return
  chuxue.addChatMessage({ id: crypto.randomUUID(), role: 'assistant', content })
  scrollToBottom()
}

function systemMessage(content: string): void {
  chuxue.addChatMessage({ id: crypto.randomUUID(), role: 'system', content })
  scrollToBottom()
}

async function refreshSessions(showLoading = true): Promise<void> {
  if (!project.currentProjectId) return
  if (sessionsRefreshing) return
  sessionsRefreshing = true
  if (showLoading) loadingSessions.value = true
  try {
    const nextSessions = await listChuxueSessions(project.currentProjectId)
    // Keep the existing array when nothing changed. This prevents the
    // sidebar from needlessly repainting during runtime polling.
    const currentKey = sessionList.value.map(session =>
      `${session.id}:${session.status}:${session.currentWorkflowRunId || ''}:${session.goal}`
    ).join('|')
    const nextKey = nextSessions.map(session =>
      `${session.id}:${session.status}:${session.currentWorkflowRunId || ''}:${session.goal}`
    ).join('|')
    if (currentKey !== nextKey) sessionList.value = nextSessions
  } finally {
    sessionsRefreshing = false
    if (showLoading) loadingSessions.value = false
  }
}

async function refreshRuntime(sessionId: string): Promise<void> {
  const snapshot = await getChuxueRuntime(sessionId)
  if (chuxue.chatSessionId !== sessionId) return
  const nextStatus = snapshot.runtime?.workflowStatus || null
  const becameTerminal = nextStatus !== lastWorkflowStatus && ['SUCCEEDED', 'FAILED'].includes(nextStatus || '')
  runtime.value = snapshot
  try {
    currentGate.value = snapshot.currentGateKey && snapshot.workflowRunId
      ? await getChuxueGate(snapshot.workflowRunId)
      : null
  } catch {
    currentGate.value = null
  }
  lastWorkflowStatus = nextStatus
  // A session created before the terminal-workflow detachment fix may still
  // contain the old run ID. PLAN_READY is authoritative for the confirmation
  // card when that run is already terminal.
  pendingPlan.value = snapshot.status === 'PLAN_READY'
    && (!snapshot.workflowRunId || ['SUCCEEDED', 'FAILED'].includes(snapshot.runtime?.workflowStatus || ''))
    ? snapshot.planId : null
  if (becameTerminal) await reloadTurns(sessionId)
}

async function reloadTurns(sessionId: string): Promise<void> {
  const turns = await getChuxueTurns(sessionId)
  if (chuxue.chatSessionId !== sessionId) return
  chuxue.resetChat(sessionId)
  for (const turn of turns) {
    const role = turn.role === 'USER' ? 'user' : turn.role === 'SYSTEM' ? 'system' : 'assistant'
    chuxue.addChatMessage({ id: turn.id, role, content: turn.content, planId: turn.planId })
  }
  scrollToBottom()
}

function stopRuntimePolling(): void {
  if (runtimeTimer !== null) window.clearInterval(runtimeTimer)
  runtimeTimer = null
}

function startRuntimePolling(sessionId: string): void {
  stopRuntimePolling()
  void refreshRuntime(sessionId)
  runtimeTimer = window.setInterval(() => {
    if (chuxue.chatSessionId !== sessionId || !chuxue.chatOpen) {
      stopRuntimePolling()
      return
    }
    void refreshRuntime(sessionId)
    void refreshSessions(false)
  }, 2_000)
}

function newConversation(): void {
  stopRuntimePolling()
  chuxue.resetChat(null)
  pendingPlan.value = null
  currentGate.value = null
  runtime.value = null
  lastWorkflowStatus = null
  input.value = ''
  scrollToBottom()
}

async function selectConversation(session: ChuxueSession): Promise<void> {
  if (session.id === chuxue.chatSessionId) return
  stopRuntimePolling()
  const sessionId = session.id
  const [turns, snapshot] = await Promise.all([getChuxueTurns(sessionId), getChuxueRuntime(sessionId)])
  chuxue.resetChat(sessionId)
  for (const turn of turns) {
    const role = turn.role === 'USER' ? 'user' : turn.role === 'SYSTEM' ? 'system' : 'assistant'
    chuxue.addChatMessage({ id: turn.id, role, content: turn.content, planId: turn.planId })
  }
  runtime.value = snapshot
  lastWorkflowStatus = snapshot.runtime?.workflowStatus || null
  pendingPlan.value = snapshot.status === 'PLAN_READY'
    && (!snapshot.workflowRunId || ['SUCCEEDED', 'FAILED'].includes(snapshot.runtime?.workflowStatus || ''))
    ? snapshot.planId : null
  try {
    currentGate.value = snapshot.currentGateKey && snapshot.workflowRunId
      ? await getChuxueGate(snapshot.workflowRunId) : null
  } catch {
    currentGate.value = null
  }
  startRuntimePolling(sessionId)
  scrollToBottom()
}

async function send(): Promise<void> {
  const goal = input.value.trim()
  if (!goal || sending.value) return
  input.value = ''
  chuxue.addChatMessage({ id: crypto.randomUUID(), role: 'user', content: goal })
  scrollToBottom()
  if (!project.currentProjectId) {
    systemMessage('请先进入一个项目，再开始与初雪对话。')
    return
  }

  sending.value = true
  let requestSessionId: string | null = null
  try {
    requestSessionId = chuxue.chatSessionId
    if (!requestSessionId) {
      const session = await createChuxueSession(project.currentProjectId, goal)
      requestSessionId = session.id
      chuxue.ensureSession(requestSessionId)
      sessionList.value = [session, ...sessionList.value]
      startRuntimePolling(requestSessionId)
    }
    const history = messages.value.slice(0, -1)
      .filter(item => item.role !== 'system')
      .map(item => ({ role: item.role, content: item.content }))
    const chat = await chatWithChuxue(project.currentProjectId, requestSessionId, goal, history)

    // The response belongs to the session that initiated it. If the user has
    // switched away, MySQL already owns both turns and they will appear when
    // that conversation is opened again.
    if (chuxue.chatSessionId !== requestSessionId) return
    if (!chat.llmUsed || !chat.reply) {
      systemMessage('聊天模型暂时不可用，请稍后重试。')
      return
    }
    assistant(chat.reply)
    if (!chat.shouldPlan) return
    if (project.assets.length === 0) {
      systemMessage('当前项目还没有视频素材，上传素材后即可继续执行这个方案。')
      return
    }
    const decision = await planWithChuxue(
      project.currentProjectId,
      requestSessionId,
      chat.planningGoal || goal,
      project.assets.map(asset => asset.id),
      chat.userTurnId,
      chat.targetDurationMs,
      chat.reviewGateKeys,
    )
    if (chuxue.chatSessionId !== requestSessionId) return
    pendingPlan.value = decision.planId
    await refreshRuntime(requestSessionId)
  } catch (error) {
    if (requestSessionId && chuxue.chatSessionId === requestSessionId) {
      systemMessage(error instanceof Error ? error.message : '聊天请求失败，请稍后重试。')
    }
  } finally {
    sending.value = false
    await refreshSessions()
  }
}

async function confirm(): Promise<void> {
  if (!pendingPlan.value || !project.currentProjectId || !chuxue.chatSessionId || sending.value) return
  const sessionId = chuxue.chatSessionId
  sending.value = true
  try {
    await confirmChuxuePlan(project.currentProjectId, pendingPlan.value)
    pendingPlan.value = null
      const turns = await getChuxueTurns(sessionId)
    if (chuxue.chatSessionId === sessionId) {
      chuxue.resetChat(sessionId)
      for (const turn of turns) {
        const role = turn.role === 'USER' ? 'user' : turn.role === 'SYSTEM' ? 'system' : 'assistant'
        chuxue.addChatMessage({ id: turn.id, role, content: turn.content, planId: turn.planId })
      }
      await refreshRuntime(sessionId)
      startRuntimePolling(sessionId)
      scrollToBottom()
    }
  } catch (error) {
    if (chuxue.chatSessionId === sessionId) {
      systemMessage(error instanceof Error ? error.message : 'Workflow 启动失败，请重试。')
    }
  } finally {
    sending.value = false
    await refreshSessions()
  }
}

async function handleEmbeddedGateResolved(payload: { gateKey: string }): Promise<void> {
  const sessionId = chuxue.chatSessionId
  currentGate.value = null
  if (sessionId) await refreshRuntime(sessionId)
  assistant(`这一阶段（${payload.gateKey}）已经处理完成。你觉得刚才的结果怎么样？如果需要调整，我可以继续根据你的反馈修改。`)
}

async function handleEmbeddedWorkflowCancelled(): Promise<void> {
  currentGate.value = null
  const sessionId = chuxue.chatSessionId
  if (sessionId) await refreshRuntime(sessionId)
  assistant('好的，当前 Workflow 已取消，审核卡片也已关闭。你可以随时告诉我新的创作需求。')
}

watch(() => chuxue.chatOpen, open => {
  if (open) {
    void refreshSessions()
    if (chuxue.chatSessionId) startRuntimePolling(chuxue.chatSessionId)
    scrollToBottom()
  } else {
    stopRuntimePolling()
  }
})
watch(() => messages.value.length, scrollToBottom)
onMounted(() => {
  if (chuxue.chatOpen) {
    void refreshSessions()
    if (chuxue.chatSessionId) startRuntimePolling(chuxue.chatSessionId)
  }
})
onBeforeUnmount(stopRuntimePolling)
</script>

<template>
  <Teleport to="body">
    <div class="chuxue-chat-layer" role="presentation">
      <div class="chuxue-chat-backdrop" aria-hidden="true" @click="chuxue.closeChat" />
      <div class="chuxue-chat-card" role="dialog" aria-modal="true" aria-label="初雪 Agent 聊天窗口" @click.stop>
        <aside class="chuxue-session-sidebar">
          <div class="chuxue-session-heading">
            <div><strong>初雪</strong><span>创作会话</span></div>
            <button type="button" aria-label="关闭" @click="chuxue.closeChat"><X /></button>
          </div>
          <button class="chuxue-new-chat" type="button" @click="newConversation"><Plus /> 新对话</button>
          <div class="chuxue-session-list">
            <div v-if="loadingSessions" class="chuxue-session-empty">正在加载会话…</div>
            <button
              v-for="session in sessionList"
              :key="session.id"
              type="button"
              class="chuxue-session-item"
              :class="{ active: session.id === chuxue.chatSessionId }"
              @click="selectConversation(session)"
            >
              <MessageSquare />
              <span>{{ session.goal || '未命名创作会话' }}</span>
              <i v-if="session.currentWorkflowRunId && ['EXECUTING', 'WAITING_GATE'].includes(session.status)" />
            </button>
            <div v-if="!loadingSessions && !sessionList.length" class="chuxue-session-empty">还没有历史会话</div>
          </div>
        </aside>

        <section class="chuxue-chat-main">
          <header class="chuxue-chat-header">
            <div><strong>和初雪聊聊你的创作</strong><span>自然对话 · 受控规划 · 可追溯执行</span></div>
          </header>

          <div ref="messagesRef" class="chuxue-chat-messages">
            <div v-if="!messages.length" class="chuxue-chat-empty">告诉我你的想法，我们可以先聊风格、节奏和成片目标。</div>
            <div v-for="message in messages" :key="message.id" :class="['chuxue-chat-message', message.role]">
              {{ message.content }}
            </div>

            <div v-if="pendingPlan" class="chuxue-plan-card">
              <div><CheckCircle2 /><span><strong>计划等待确认</strong>确认后才会创建并启动 Workflow。</span></div>
              <button :disabled="sending" @click="confirm">
                <Loader2 v-if="sending" class="spin" /><CheckCircle2 v-else /> 确认计划并执行
              </button>
            </div>

            <div v-if="runtime?.workflowRunId && workflowActive" class="chuxue-workflow-card" :class="runtime.runtime?.workflowStatus?.toLowerCase()">
              <div class="chuxue-workflow-heading">
                <Workflow />
                <div><strong>{{ workflowStatusText }}</strong><span>{{ runtime.workflowRunId }}</span></div>
                <b>{{ runtime.runtime?.progress || 0 }}%</b>
              </div>
              <div class="chuxue-workflow-progress"><i :style="{ width: `${runtime.runtime?.progress || 0}%` }" /></div>
              <p>该 Workflow 完成或失败前，当前会话不会开启新的 Workflow。</p>
            </div>
            <WorkflowMonitorPage
              v-if="embeddedGate"
              :project-id="project.currentProjectId || ''"
              :run-id="runtime?.workflowRunId || ''"
              embedded
              @gate-resolved="handleEmbeddedGateResolved"
              @workflow-cancelled="handleEmbeddedWorkflowCancelled"
            />
          </div>

          <form class="chuxue-chat-input" @submit.prevent="send">
            <input v-model="input" :disabled="sending" placeholder="和初雪说说你的想法…" aria-label="发送给初雪" />
            <button type="submit" :disabled="sending || !input.trim()" aria-label="发送">
              <Loader2 v-if="sending" class="spin" /><Send v-else />
            </button>
          </form>
        </section>
      </div>
    </div>
  </Teleport>
</template>

<style scoped>
.chuxue-chat-layer{position:fixed;inset:0;z-index:80}.chuxue-chat-backdrop{position:absolute;inset:0;background:rgba(2,6,23,.68);backdrop-filter:blur(3px)}.chuxue-chat-card{position:fixed;inset:4vh 4vw;z-index:1;display:grid;grid-template-columns:260px minmax(0,1fr);grid-template-rows:minmax(0,1fr);overflow:hidden;border:1px solid rgba(96,165,250,.28);border-radius:28px;background:linear-gradient(160deg,rgba(15,23,42,.99),rgba(30,41,59,.99));box-shadow:0 28px 90px rgba(2,6,23,.72),0 0 42px rgba(96,165,250,.14)}
.chuxue-session-sidebar{display:flex;flex-direction:column;min-width:0;padding:20px 14px;border-right:1px solid rgba(148,163,184,.14);background:rgba(15,23,42,.72)}.chuxue-session-heading{display:flex;justify-content:space-between;align-items:flex-start;padding:0 6px 18px;color:#e2e8f0}.chuxue-session-heading strong{display:block;font-size:19px}.chuxue-session-heading span{display:block;margin-top:3px;color:#94a3b8;font-size:11px}.chuxue-session-heading button{border:0;background:transparent;color:#94a3b8;cursor:pointer}.chuxue-new-chat{display:flex;align-items:center;justify-content:center;gap:7px;padding:10px;border:1px solid rgba(96,165,250,.3);border-radius:11px;background:rgba(37,99,235,.8);color:#fff;font-size:12px;cursor:pointer}.chuxue-session-list{flex:1;overflow:auto;margin-top:16px}.chuxue-session-item{display:flex;align-items:center;gap:8px;width:100%;margin-bottom:5px;padding:10px 8px;border:0;border-radius:10px;background:transparent;color:#cbd5e1;text-align:left;font-size:12px;cursor:pointer}.chuxue-session-item:hover,.chuxue-session-item.active{background:rgba(59,130,246,.18);color:#dbeafe}.chuxue-session-item span{flex:1;overflow:hidden;text-overflow:ellipsis;white-space:nowrap}.chuxue-session-item i{width:7px;height:7px;border-radius:50%;background:#60a5fa;box-shadow:0 0 9px #3b82f6}.chuxue-session-empty{padding:16px 8px;color:#64748b;font-size:11px;line-height:1.6}
.chuxue-chat-main{display:flex;min-width:0;min-height:0;overflow:hidden;flex-direction:column}.chuxue-chat-header{flex:0 0 auto;display:flex;align-items:center;padding:22px 26px;border-bottom:1px solid rgba(148,163,184,.14);color:#e2e8f0}.chuxue-chat-header strong{display:block;font-size:18px}.chuxue-chat-header span{display:block;margin-top:4px;color:#94a3b8;font-size:11px}.chuxue-chat-messages{flex:1 1 auto;min-height:0;overflow-x:hidden;overflow-y:auto;overscroll-behavior:contain;padding:28px clamp(20px,6vw,90px);display:flex;flex-direction:column;gap:14px}.chuxue-chat-empty{margin:auto;max-width:460px;text-align:center;color:#94a3b8;font-size:14px;line-height:1.8}.chuxue-chat-message{max-width:min(760px,88%);padding:12px 15px;border-radius:16px;color:#dbeafe;font-size:14px;line-height:1.7;white-space:pre-wrap}.chuxue-chat-message.user{align-self:flex-end;background:#2563eb;color:#fff}.chuxue-chat-message.assistant{align-self:flex-start;background:rgba(51,65,85,.72)}.chuxue-chat-message.system{align-self:center;max-width:80%;background:rgba(100,116,139,.18);color:#94a3b8;font-size:12px}
.chuxue-plan-card,.chuxue-workflow-card,.chuxue-gate-card{align-self:stretch;padding:14px 16px;border:1px solid rgba(96,165,250,.25);border-radius:15px;background:rgba(30,41,59,.82);color:#dbeafe}.chuxue-plan-card{display:flex;align-items:center;justify-content:space-between;gap:18px}.chuxue-plan-card>div,.chuxue-gate-card>div:first-child{display:flex;flex-direction:column;gap:5px}.chuxue-plan-card span,.chuxue-workflow-card span,.chuxue-gate-card span{display:block;color:#94a3b8;font-size:11px}.chuxue-plan-card button,.chuxue-gate-actions button{display:flex;align-items:center;gap:6px;flex:0 0 auto;padding:9px 12px;border:0;border-radius:10px;background:#2563eb;color:white;cursor:pointer}.chuxue-gate-card{border-color:rgba(251,191,36,.35);background:rgba(120,53,15,.18)}.chuxue-gate-actions{display:flex;flex-wrap:wrap;gap:8px;margin-top:12px}.chuxue-gate-actions button{background:#d97706}.chuxue-gate-actions button.danger{background:#7f1d1d}.chuxue-workflow-heading{display:flex;align-items:center;gap:11px}.chuxue-workflow-heading>div{flex:1;min-width:0}.chuxue-workflow-heading span{overflow:hidden;text-overflow:ellipsis;white-space:nowrap}.chuxue-workflow-heading b{font-size:13px;color:#93c5fd}.chuxue-workflow-progress{height:5px;margin-top:12px;overflow:hidden;border-radius:99px;background:rgba(148,163,184,.16)}.chuxue-workflow-progress i{display:block;height:100%;border-radius:inherit;background:linear-gradient(90deg,#2563eb,#60a5fa);transition:width .35s ease}.chuxue-workflow-card p{margin:9px 0 0;color:#94a3b8;font-size:11px}.chuxue-workflow-card.succeeded{border-color:rgba(52,211,153,.25)}.chuxue-workflow-card.failed{border-color:rgba(248,113,113,.3)}
.chuxue-chat-input{flex:0 0 auto;display:flex;gap:10px;margin:0 clamp(20px,6vw,90px) 24px;padding:12px 14px;border:1px solid rgba(148,163,184,.22);border-radius:16px;background:rgba(15,23,42,.88)}.chuxue-chat-input input{flex:1;min-width:0;border:0;background:transparent;color:#e2e8f0;outline:0;font-size:14px}.chuxue-chat-input button{display:grid;place-items:center;width:38px;border:0;border-radius:11px;background:#2563eb;color:#fff;cursor:pointer}.chuxue-chat-input button:disabled,.chuxue-plan-card button:disabled{opacity:.45;cursor:not-allowed}.spin{animation:spin 1s linear infinite}@keyframes spin{to{transform:rotate(360deg)}}
@media(max-width:700px){.chuxue-chat-card{inset:2vh 2vw;grid-template-columns:1fr;grid-template-rows:170px minmax(0,1fr)}.chuxue-session-sidebar{min-height:0;max-height:none;border-right:0;border-bottom:1px solid rgba(148,163,184,.14)}.chuxue-session-list{display:flex;gap:5px;overflow-x:auto;overflow-y:hidden}.chuxue-session-item{min-width:160px}.chuxue-chat-messages{padding:20px 14px}.chuxue-chat-input{margin:0 14px 14px}.chuxue-plan-card{align-items:stretch;flex-direction:column}}
</style>
