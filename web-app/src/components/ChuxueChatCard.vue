<script setup lang="ts">
import { computed, nextTick, ref } from 'vue'
import { X, Send, Loader2, CheckCircle2 } from 'lucide-vue-next'
import { useChuxueStore } from '@/stores/chuxue'
import { useProjectStore } from '@/stores/project'
import { createChuxueSession, planWithChuxue, confirmChuxuePlan } from '@/api/chuxue'

const chuxue = useChuxueStore()
const project = useProjectStore()
const input = ref('')
const sending = ref(false)
const pendingPlan = ref<string | null>(null)
const messages = computed(() => chuxue.chatMessages)

function assistant(content: string, planId: string | null = null): void {
  chuxue.addChatMessage({ id: crypto.randomUUID(), role: 'assistant', content, planId })
}

async function send(): Promise<void> {
  const goal = input.value.trim()
  if (!goal || sending.value) return
  input.value = ''
  chuxue.addChatMessage({ id: crypto.randomUUID(), role: 'user', content: goal })
  if (!project.currentProjectId || project.assets.length === 0) {
    assistant('请先选择一个项目并上传至少一个视频素材。')
    return
  }
  sending.value = true
  try {
    let sessionId = chuxue.chatSessionId
    if (!sessionId) {
      const session = await createChuxueSession(project.currentProjectId, goal)
      sessionId = session.id
      chuxue.ensureSession(sessionId)
    }
    const decision = await planWithChuxue(project.currentProjectId, sessionId, goal, project.assets.map(a => a.id))
    if (!decision.planId) {
      assistant(decision.intent?.clarificationQuestion || '请再补充一下你想制作的视频类型。')
    } else {
      pendingPlan.value = decision.planId
      assistant(`我理解为：${decision.preview?.intent.explanation || '制作一支受控旅行短片'}。目标时长约 ${Math.round((decision.intent?.targetDurationMs || 30000) / 1000)} 秒。请确认是否开始执行。`, decision.planId)
    }
  } catch (error) {
    assistant(error instanceof Error ? error.message : '初雪暂时无法处理，请稍后重试。')
  } finally { sending.value = false; await nextTick() }
}

async function confirm(): Promise<void> {
  if (!pendingPlan.value || !project.currentProjectId || sending.value) return
  sending.value = true
  try {
    const result = await confirmChuxuePlan(project.currentProjectId, pendingPlan.value)
    assistant(`Workflow 已启动（${result.status}）。你可以继续在这里查看进度和 Gate。`)
    pendingPlan.value = null
  } catch (error) { assistant(error instanceof Error ? error.message : '启动失败，请重试。') }
  finally { sending.value = false }
}
</script>

<template>
  <Teleport to="body">
  <div class="chuxue-chat-layer" role="presentation">
  <div class="chuxue-chat-backdrop" aria-hidden="true" @click="chuxue.closeChat" />
  <div class="chuxue-chat-card" role="dialog" aria-label="初雪 Agent 聊天窗口">
    <header class="chuxue-chat-header">
      <div><strong>初雪</strong><span>智能创作助手 · Session Runtime</span></div>
      <button type="button" aria-label="关闭" @click="chuxue.closeChat"><X /></button>
    </header>
    <div class="chuxue-chat-messages">
      <div v-if="!messages.length" class="chuxue-chat-empty">告诉我你想把项目素材做成什么视频，例如“两个视频做成30秒旅行短片”。</div>
      <div v-for="message in messages" :key="message.id" :class="['chuxue-chat-message', message.role]">
        {{ message.content }}
        <button v-if="message.planId && pendingPlan === message.planId" class="chuxue-confirm" :disabled="sending" @click="confirm">
          <Loader2 v-if="sending" class="spin" /> <CheckCircle2 v-else /> 确认并开始执行
        </button>
      </div>
    </div>
    <form class="chuxue-chat-input" @submit.prevent="send">
      <input v-model="input" :disabled="sending" placeholder="和初雪说说你的想法…" aria-label="发送给初雪" />
      <button type="submit" :disabled="sending || !input.trim()" aria-label="发送"><Loader2 v-if="sending" class="spin" /><Send v-else /></button>
    </form>
  </div>
  </div>
  </Teleport>
</template>

<style scoped>
.chuxue-chat-layer { position:fixed; inset:0; z-index:80; pointer-events:auto; }
.chuxue-chat-backdrop { position:absolute; inset:0; background:rgba(2,6,23,.68); backdrop-filter:blur(3px); }
.chuxue-chat-card { position:fixed; inset:4vh 4vw; z-index:80; display:flex; flex-direction:column; border:1px solid rgba(96,165,250,.28); border-radius:28px; background:linear-gradient(160deg,rgba(15,23,42,.99),rgba(30,41,59,.99)); box-shadow:0 28px 90px rgba(2,6,23,.72),0 0 42px rgba(96,165,250,.14); overflow:hidden; pointer-events:auto; }
.chuxue-chat-header { display:flex; justify-content:space-between; align-items:center; padding:18px 20px; border-bottom:1px solid rgba(148,163,184,.14); color:#e2e8f0; }
.chuxue-chat-header strong { display:block; font-size:18px; } .chuxue-chat-header span { display:block; margin-top:3px; color:#94a3b8; font-size:11px; }
.chuxue-chat-header button,.chuxue-chat-input button { border:0; background:transparent; color:#94a3b8; cursor:pointer; } .chuxue-chat-header button:hover { color:#fff; }
.chuxue-chat-messages { flex:1; overflow:auto; padding:18px; display:flex; flex-direction:column; gap:12px; }
.chuxue-chat-empty { margin:auto; max-width:360px; text-align:center; color:#94a3b8; font-size:13px; line-height:1.7; }
.chuxue-chat-message { max-width:88%; padding:11px 14px; border-radius:15px; color:#dbeafe; font-size:13px; line-height:1.6; white-space:pre-wrap; }
.chuxue-chat-message.user { align-self:flex-end; background:#2563eb; color:white; } .chuxue-chat-message.assistant { align-self:flex-start; background:rgba(51,65,85,.72); }
.chuxue-confirm { display:flex; align-items:center; gap:5px; margin-top:10px; padding:8px 11px; border:1px solid rgba(147,197,253,.3); border-radius:10px; background:#1d4ed8; color:white; font-size:12px; cursor:pointer; }
.chuxue-chat-input { display:flex; gap:8px; padding:14px; border-top:1px solid rgba(148,163,184,.14); } .chuxue-chat-input input { flex:1; min-width:0; padding:11px 13px; border:1px solid rgba(148,163,184,.2); border-radius:12px; background:rgba(15,23,42,.8); color:#e2e8f0; outline:none; } .chuxue-chat-input button { display:grid; place-items:center; width:40px; border-radius:11px; background:#2563eb; color:white; } .chuxue-chat-input button:disabled { opacity:.45; cursor:not-allowed; }
.spin { animation:spin 1s linear infinite; } @keyframes spin { to { transform:rotate(360deg); } }
@media (max-width: 700px) {
  .chuxue-chat-card { inset:2.5vh 2.5vw; border-radius:22px; }
}
</style>
