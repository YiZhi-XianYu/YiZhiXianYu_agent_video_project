<script setup lang="ts">
import { computed, nextTick, onMounted, ref, watch } from 'vue'
import { X, Send, Loader2, CheckCircle2, Plus, MessageSquare } from 'lucide-vue-next'
import { useChuxueStore } from '@/stores/chuxue'
import { useProjectStore } from '@/stores/project'
import { createChuxueSession, listChuxueSessions, getChuxueTurns, planWithChuxue, confirmChuxuePlan, chatWithChuxue, type ChuxueSession } from '@/api/chuxue'

const chuxue = useChuxueStore()
const project = useProjectStore()
const input = ref('')
const sending = ref(false)
const loadingSessions = ref(false)
const pendingPlan = ref<string | null>(null)
const sessionList = ref<ChuxueSession[]>([])
const messages = computed(() => chuxue.chatMessages)
const messagesRef = ref<HTMLElement | null>(null)

function scrollToBottom(): void { void nextTick(() => { const el = messagesRef.value; if (el) el.scrollTo({ top: el.scrollHeight, behavior: 'smooth' }) }) }
function assistant(content: string, planId: string | null = null): void { if (!content) return; chuxue.addChatMessage({ id: crypto.randomUUID(), role: 'assistant', content, planId }); scrollToBottom() }
function systemMessage(content: string): void { chuxue.addChatMessage({ id: crypto.randomUUID(), role: 'system', content }); scrollToBottom() }

async function refreshSessions(): Promise<void> {
  if (!project.currentProjectId) return
  loadingSessions.value = true
  try { sessionList.value = await listChuxueSessions(project.currentProjectId) } finally { loadingSessions.value = false }
}

function newConversation(): void { chuxue.resetChat(null); pendingPlan.value = null; scrollToBottom() }

async function selectConversation(session: ChuxueSession): Promise<void> {
  if (session.id === chuxue.chatSessionId) return
  const turns = await getChuxueTurns(session.id)
  chuxue.resetChat(session.id)
  for (const turn of turns) chuxue.addChatMessage({ id: turn.id, role: turn.role === 'USER' ? 'user' : 'assistant', content: turn.content, planId: turn.planId })
  pendingPlan.value = turns.find(turn => turn.planId)?.planId || null
  scrollToBottom()
}

async function send(): Promise<void> {
  const goal = input.value.trim()
  if (!goal || sending.value) return
  input.value = ''
  chuxue.addChatMessage({ id: crypto.randomUUID(), role: 'user', content: goal })
  scrollToBottom()
  if (!project.currentProjectId) { systemMessage('请先进入一个项目，再开始与初雪对话。'); return }
  sending.value = true
  try {
    let sessionId = chuxue.chatSessionId
    if (!sessionId) {
      const session = await createChuxueSession(project.currentProjectId, goal)
      sessionId = session.id
      chuxue.ensureSession(sessionId)
      sessionList.value = [session, ...sessionList.value]
    }
    const history = messages.value.slice(0, -1).filter(item => item.role !== 'system').map(item => ({ role: item.role, content: item.content }))
    const chat = await chatWithChuxue(project.currentProjectId, sessionId, goal, history)
    if (!chat.llmUsed || !chat.reply) { systemMessage('聊天模型暂时不可用，请稍后重试。'); return }
    if (!chat.shouldPlan) { assistant(chat.reply); return }
    if (project.assets.length === 0) { assistant(chat.reply); systemMessage('当前项目还没有视频素材，上传素材后即可继续执行这个方案。'); return }
    const decision = await planWithChuxue(project.currentProjectId, sessionId, goal, project.assets.map(a => a.id))
    if (!decision.planId) { assistant(chat.reply); return }
    pendingPlan.value = decision.planId
    assistant(chat.reply, decision.planId)
  } catch (error) { systemMessage(error instanceof Error ? error.message : '聊天请求失败，请稍后重试。') }
  finally { sending.value = false; await refreshSessions() }
}

async function confirm(): Promise<void> {
  if (!pendingPlan.value || !project.currentProjectId || sending.value) return
  sending.value = true
  try { await confirmChuxuePlan(project.currentProjectId, pendingPlan.value); systemMessage('Workflow 已启动，进度会持续同步到当前会话。'); pendingPlan.value = null }
  catch (error) { systemMessage(error instanceof Error ? error.message : 'Workflow 启动失败，请重试。') }
  finally { sending.value = false; await refreshSessions() }
}

watch(() => chuxue.chatOpen, open => { if (open) { void refreshSessions(); scrollToBottom() } })
watch(() => messages.value.length, scrollToBottom)
onMounted(() => { if (chuxue.chatOpen) void refreshSessions() })
</script>

<template>
  <Teleport to="body">
    <div class="chuxue-chat-layer" role="presentation">
      <div class="chuxue-chat-backdrop" aria-hidden="true" @click="chuxue.closeChat" />
      <div class="chuxue-chat-card" role="dialog" aria-modal="true" aria-label="初雪 Agent 聊天窗口" @click.stop>
        <aside class="chuxue-session-sidebar">
          <div class="chuxue-session-heading"><div><strong>初雪</strong><span>创作会话</span></div><button type="button" aria-label="关闭" @click="chuxue.closeChat"><X /></button></div>
          <button class="chuxue-new-chat" type="button" @click="newConversation"><Plus /> 新对话</button>
          <div class="chuxue-session-list"><div v-if="loadingSessions" class="chuxue-session-empty">正在加载会话…</div><button v-for="session in sessionList" :key="session.id" type="button" class="chuxue-session-item" :class="{ active: session.id === chuxue.chatSessionId }" @click="selectConversation(session)"><MessageSquare /><span>{{ session.goal || '未命名创作会话' }}</span></button><div v-if="!loadingSessions && !sessionList.length" class="chuxue-session-empty">还没有历史会话</div></div>
        </aside>
        <section class="chuxue-chat-main">
          <header class="chuxue-chat-header"><div><strong>和初雪聊聊你的创作</strong><span>自然对话 · 受控规划 · 可追溯执行</span></div></header>
          <div ref="messagesRef" class="chuxue-chat-messages"><div v-if="!messages.length" class="chuxue-chat-empty">告诉我你的想法，我们可以先聊风格、节奏和成片目标。</div><div v-for="message in messages" :key="message.id" :class="['chuxue-chat-message', message.role]">{{ message.content }}<button v-if="message.planId && pendingPlan === message.planId" class="chuxue-confirm" :disabled="sending" @click="confirm"><Loader2 v-if="sending" class="spin" /><CheckCircle2 v-else /> 确认并开始执行</button></div></div>
          <form class="chuxue-chat-input" @submit.prevent="send"><input v-model="input" :disabled="sending" placeholder="和初雪说说你的想法…" aria-label="发送给初雪" /><button type="submit" :disabled="sending || !input.trim()" aria-label="发送"><Loader2 v-if="sending" class="spin" /><Send v-else /></button></form>
        </section>
      </div>
    </div>
  </Teleport>
</template>

<style scoped>
.chuxue-chat-layer{position:fixed;inset:0;z-index:80}.chuxue-chat-backdrop{position:absolute;inset:0;background:rgba(2,6,23,.68);backdrop-filter:blur(3px)}.chuxue-chat-card{position:fixed;inset:4vh 4vw;z-index:1;display:grid;grid-template-columns:260px minmax(0,1fr);overflow:hidden;border:1px solid rgba(96,165,250,.28);border-radius:28px;background:linear-gradient(160deg,rgba(15,23,42,.99),rgba(30,41,59,.99));box-shadow:0 28px 90px rgba(2,6,23,.72),0 0 42px rgba(96,165,250,.14)}
.chuxue-session-sidebar{display:flex;flex-direction:column;min-width:0;padding:20px 14px;border-right:1px solid rgba(148,163,184,.14);background:rgba(15,23,42,.72)}.chuxue-session-heading{display:flex;justify-content:space-between;align-items:flex-start;padding:0 6px 18px;color:#e2e8f0}.chuxue-session-heading strong{display:block;font-size:19px}.chuxue-session-heading span{display:block;margin-top:3px;color:#94a3b8;font-size:11px}.chuxue-session-heading button{border:0;background:transparent;color:#94a3b8;cursor:pointer}.chuxue-new-chat{display:flex;align-items:center;justify-content:center;gap:7px;padding:10px;border:1px solid rgba(96,165,250,.3);border-radius:11px;background:rgba(37,99,235,.8);color:#fff;font-size:12px;cursor:pointer}.chuxue-session-list{flex:1;overflow:auto;margin-top:16px}.chuxue-session-item{display:flex;align-items:center;gap:8px;width:100%;margin-bottom:5px;padding:10px 8px;border:0;border-radius:10px;background:transparent;color:#cbd5e1;text-align:left;font-size:12px;cursor:pointer}.chuxue-session-item:hover,.chuxue-session-item.active{background:rgba(59,130,246,.18);color:#dbeafe}.chuxue-session-item span{overflow:hidden;text-overflow:ellipsis;white-space:nowrap}.chuxue-session-empty{padding:16px 8px;color:#64748b;font-size:11px;line-height:1.6}
.chuxue-chat-main{display:flex;min-width:0;flex-direction:column}.chuxue-chat-header{display:flex;align-items:center;padding:22px 26px;border-bottom:1px solid rgba(148,163,184,.14);color:#e2e8f0}.chuxue-chat-header strong{display:block;font-size:18px}.chuxue-chat-header span{display:block;margin-top:4px;color:#94a3b8;font-size:11px}.chuxue-chat-messages{flex:1;min-height:0;overflow:auto;padding:28px clamp(20px,6vw,90px);display:flex;flex-direction:column;gap:14px}.chuxue-chat-empty{margin:auto;max-width:460px;text-align:center;color:#94a3b8;font-size:14px;line-height:1.8}.chuxue-chat-message{max-width:min(760px,88%);padding:12px 15px;border-radius:16px;color:#dbeafe;font-size:14px;line-height:1.7;white-space:pre-wrap}.chuxue-chat-message.user{align-self:flex-end;background:#2563eb;color:#fff}.chuxue-chat-message.assistant{align-self:flex-start;background:rgba(51,65,85,.72)}.chuxue-chat-message.system{align-self:center;max-width:80%;background:rgba(100,116,139,.18);color:#94a3b8;font-size:12px}.chuxue-confirm{display:flex;align-items:center;gap:5px;margin-top:10px;padding:8px 11px;border:1px solid rgba(147,197,253,.3);border-radius:10px;background:#1d4ed8;color:#fff;font-size:12px;cursor:pointer}.chuxue-chat-input{display:flex;gap:10px;margin:0 clamp(20px,6vw,90px) 24px;padding:12px 14px;border:1px solid rgba(148,163,184,.22);border-radius:16px;background:rgba(15,23,42,.88)}.chuxue-chat-input input{flex:1;min-width:0;border:0;background:transparent;color:#e2e8f0;outline:0;font-size:14px}.chuxue-chat-input button{display:grid;place-items:center;width:38px;border:0;border-radius:11px;background:#2563eb;color:#fff;cursor:pointer}.chuxue-chat-input button:disabled{opacity:.45;cursor:not-allowed}.spin{animation:spin 1s linear infinite}@keyframes spin{to{transform:rotate(360deg)}}
@media(max-width:700px){.chuxue-chat-card{inset:2vh 2vw;grid-template-columns:1fr}.chuxue-session-sidebar{max-height:190px;border-right:0;border-bottom:1px solid rgba(148,163,184,.14)}.chuxue-session-list{display:flex;gap:5px;overflow:auto}.chuxue-session-item{min-width:160px}.chuxue-chat-messages{padding:20px 14px}.chuxue-chat-input{margin:0 14px 14px}}
</style>
<style scoped>
/* Keep the composer pinned inside the dialog; only the transcript scrolls. */
.chuxue-chat-card {
  grid-template-rows: minmax(0, 1fr);
}
.chuxue-chat-main {
  min-height: 0;
  overflow: hidden;
}
.chuxue-chat-header,
.chuxue-chat-input {
  flex: 0 0 auto;
}
.chuxue-chat-messages {
  flex: 1 1 auto;
  min-height: 0;
  overflow-x: hidden;
  overflow-y: auto;
  overscroll-behavior: contain;
}
@media (max-width: 700px) {
  .chuxue-chat-card {
    grid-template-rows: 170px minmax(0, 1fr);
  }
  .chuxue-session-sidebar {
    min-height: 0;
    max-height: none;
  }
  .chuxue-session-list {
    overflow-x: auto;
    overflow-y: hidden;
  }
}
</style>
