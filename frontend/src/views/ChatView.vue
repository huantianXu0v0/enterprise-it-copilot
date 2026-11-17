<script setup lang="ts">
import {ref, reactive, nextTick, onMounted, onBeforeUnmount, watch} from 'vue';
import {
  ArrowUp,
  Brain,
  Square,
  ChevronDown,
  ArrowUpRight,
  BookOpen,
  Globe,
  Ticket,
  Headphones,
  Copy,
  Check,
  ChevronRight,
  X,
  Layers,
  LoaderCircle,
  RefreshCw,
  SlidersHorizontal
} from 'lucide-vue-next';
import {marked} from 'marked';
import DOMPurify from 'dompurify';
import {api, post, errorText} from '../api';
import {streamAsk} from '../sse';
import type {Session, Message, Answer} from '../types';

const props = defineProps<{ connected: boolean }>(),
    emit = defineEmits<{ sessions: [Session[]]; selected: [string | null]; busy: [boolean]; setup: [] }>();
const id = ref<string | null>(null), messages = ref<Message[]>([]), prompt = ref(''), busy = ref(false),
    loading = ref(false), error = ref(''), copied = ref(''), panel = ref<Answer | null>(null), showPanel = ref(false),
    more = ref(false), cursor = ref(''), timeline = ref<HTMLElement>(), input = ref<HTMLTextAreaElement>();
const deepThinking = ref(false), phase = ref('');
let activeRequest: AbortController | undefined;
let selection = 0, scrollFrame = 0;

// 用户上滚查看旧消息时，不把滚动位置强行拉回底部。
function followStream() {
  const el = timeline.value;
  if (!el || el.scrollHeight - el.scrollTop - el.clientHeight > 140 || scrollFrame) return;
  scrollFrame = requestAnimationFrame(() => {
    scrollFrame = 0;
    nextTick(() => { if (timeline.value) timeline.value.scrollTop = timeline.value.scrollHeight; });
  });
}

function stop() { activeRequest?.abort(); }
onBeforeUnmount(() => { stop(); cancelAnimationFrame(scrollFrame); });
const starters = [{
  title: '排查网络与 VPN',
  description: '从现象开始，找到解决步骤',
  question: 'VPN连接不上，应该怎么排查？',
  icon: Globe,
  color: 'blue'
}, {
  title: '跟进工单进度',
  description: '查询当前状态与处理进展',
  question: '帮我查询 IT-1001 工单的处理进度',
  icon: Ticket,
  color: 'amber'
}, {
  title: '联系 IT 服务台',
  description: '查找联系方式与服务时间',
  question: 'IT服务台怎么联系？',
  icon: Headphones,
  color: 'green'
}];
const render = (text: string) => DOMPurify.sanitize(marked.parse(text, {async: false}) as string);
const statusName: Record<string, string> = {
  success: '已完成',
  empty: '暂无资料',
  needs_input: '待补充',
  failed: '未完成'
}, typeName: Record<string, string> = {RAG: '知识检索', MCP: '工具查询', SYSTEM: '直接交流'};

async function sessions() {
  try {
    emit('sessions', await api<Session[]>('/workspace/conversations'));
  } catch {
  }
}

async function scroll() {
  await nextTick();
  timeline.value?.scrollTo({top: timeline.value.scrollHeight, behavior: 'smooth'});
}

function newChat() {
  if (busy.value) return;
  selection++;
  id.value = null;
  messages.value = [];
  prompt.value = '';
  error.value = '';
  panel.value = null;
  showPanel.value = false;
  more.value = false;
  loading.value = false;
  emit('selected', null);
  nextTick(() => input.value?.focus());
}

async function select(value: string) {
  if (busy.value) return;
  const token = ++selection;
  loading.value = true;
  error.value = '';
  showPanel.value = false;
  panel.value = null;
  id.value = value;
  emit('selected', value);
  try {
    const data = await api<{
      messages: Message[];
      hasMore: boolean;
      nextBeforeId: string
    }>('/workspace/conversations/' + value + '/messages');
    if (token !== selection) return;
    messages.value = data.messages;
    more.value = data.hasMore;
    cursor.value = data.nextBeforeId;
    scroll();
  } catch (e) {
    if (token === selection) {
      messages.value = [];
      error.value = errorText(e);
    }
  } finally {
    if (token === selection) loading.value = false;
  }
}

async function earlier() {
  if (!id.value || loading.value) return;
  const token = selection;
  loading.value = true;
  try {
    const data = await api<{
      messages: Message[];
      hasMore: boolean;
      nextBeforeId: string
    }>('/workspace/conversations/' + id.value + '/messages?beforeId=' + encodeURIComponent(cursor.value));
    if (token !== selection) return;
    messages.value = [...data.messages, ...messages.value];
    more.value = data.hasMore;
    cursor.value = data.nextBeforeId;
  } catch (e) {
    error.value = errorText(e);
  } finally {
    if (token === selection) loading.value = false;
  }
}

function fill(value: string) {
  prompt.value = value;
  nextTick(() => input.value?.focus());
}

async function send() {
  const question = prompt.value.trim();
  if (!question || busy.value || loading.value || !props.connected) return;
  busy.value = true;
  emit('busy', true);
  error.value = '';
  phase.value = '正在连接…';
  const controller = new AbortController();
  activeRequest = controller;
  let assistant: Message | undefined;
  try {
    if (!id.value) {
      const r = await post<{ conversationId: string }>('/conversations');
      id.value = r.conversationId;
      emit('selected', id.value);
    }
    if (controller.signal.aborted) throw new DOMException('Aborted', 'AbortError');
    messages.value.push({id: 'u' + Date.now(), role: 'user', content: question});
    assistant = reactive<Message>({
      id: 'a' + Date.now(), role: 'assistant', content: '', reasoning: '',
      thinkingRequested: deepThinking.value, thinkingState: 'waiting',
      reasoningOpen: true, streaming: true
    });
    messages.value.push(assistant);
    const current = assistant;
    prompt.value = '';
    scroll();
    const result = await streamAsk(id.value, {
      question, topK: 5, minScore: 0.3, deepThinking: deepThinking.value
    }, controller.signal, (event, text) => {
      followStream();
      if (event === 'phase') phase.value = text;
      if (event === 'reasoning') {
        current.reasoning += text;
        current.thinkingState = 'thinking';
        phase.value = '正在思考…';
      }
      if (event === 'delta') {
        current.content += text;
        current.thinkingState = current.reasoning ? 'finished' : 'unavailable';
        phase.value = '正在生成回答…';
      }
    });
    // done 才表示服务端已完成保存；以最终正文校准，不能重复追加。
    current.content = result.answer;
    current.result = result;
    current.streaming = false;
    current.thinkingState = current.reasoning ? 'finished' : 'unavailable';
    panel.value = result;
    await sessions();
  } catch (e) {
    if (assistant) { assistant.interrupted = true; assistant.streaming = false; }
    error.value = controller.signal.aborted
      ? '已停止接收。未完成内容仅保留在当前页面；如需确认保存结果，可刷新会话。'
      : errorText(e);
  } finally {
    if (assistant) assistant.streaming = false;
    activeRequest = undefined;
    busy.value = false;
    emit('busy', false);
  }
}

async function copy(m: Message) {
  try {
    await navigator.clipboard.writeText(m.content);
    copied.value = m.id;
    setTimeout(() => copied.value = '', 1800);
  } catch {
    error.value = '复制失败，请手动选择文字复制。';
  }
}

function keydown(e: KeyboardEvent) {
  if (e.key === 'Enter' && !e.shiftKey && !e.isComposing) {
    e.preventDefault();
    send();
  }
}

function inspect(result: Answer) {
  panel.value = result;
  showPanel.value = true;
}

onMounted(sessions);
watch(() => props.connected, v => {
  if (v) sessions();
});
defineExpose({newChat, select});
</script>
<template>
  <section class="chat-workspace">
    <div class="chat-main">
      <div ref="timeline" class="conversation-scroll">
        <div v-if="!messages.length&&!loading" class="welcome">
          <div class="eyebrow"><span></span> YOUR EVERYDAY IT COMPANION</div>
          <div class="welcome-heading">
            <div><h1>少一点摸索，<br>多一点<span class="accent-word">笃定。</span></h1>
              <p class="welcome-description">从文档里找答案，让工具替你跑一步。<br>你的 IT 问题，在这里接着聊。</p></div>
            <div class="knowledge-art" aria-hidden="true">
              <div class="orbit orbit-one"></div>
              <div class="orbit orbit-two"></div>
              <div class="art-center">
                <Layers :size="34" stroke-width="1.3"/>
              </div>
              <div class="art-note art-note-one">
                <BookOpen :size="16"/>
                <span>知识，有出处</span><i></i></div>
              <div class="art-note art-note-two">
                <Check :size="15"/>
                <span>查询，有结果</span></div>
              <span class="art-star">✳</span><span class="art-label">CONNECTED KNOWLEDGE</span></div>
          </div>
          <div class="starter-heading"><span>从一个问题开始</span><span>为你的工作，减一点负担 <ArrowUpRight :size="13"/></span>
          </div>
          <div class="starter-grid">
            <button v-for="s in starters" :key="s.title" class="starter-card" @click="fill(s.question)"><span
                class="starter-icon" :class="s.color"><component :is="s.icon" :size="20"
                                                                 stroke-width="1.6"/></span><strong>{{ s.title }}</strong>
              <p>{{ s.description }}</p>
              <ArrowUpRight class="card-arrow" :size="17"/>
            </button>
          </div>
          <button class="setup-link" @click="emit('setup')">
            <SlidersHorizontal :size="14"/>
            第一次使用？完善意图与知识库绑定
            <ChevronRight :size="14"/>
          </button>
        </div>
        <div v-else class="message-list">
          <button v-if="more" class="subtle-button earlier-button" :disabled="loading" @click="earlier">查看更早的消息
          </button>
          <div v-if="loading" class="loading-row">
            <LoaderCircle class="spinning" :size="18"/>
            正在读取对话
          </div>
          <article v-for="m in messages" :key="m.id" class="message" :class="m.role">
            <div v-if="m.role==='user'" class="user-message">{{ m.content }}</div>
            <template v-else>
              <div class="assistant-identity"><span class="assistant-mark"><Layers
                  :size="15"/></span><strong>序知</strong><span>IT 助手</span></div>
              <div v-if="m.thinkingRequested||m.reasoning" class="reasoning-box" :class="{active:m.streaming&&m.thinkingState==='thinking'}">
                <button class="reasoning-heading" :aria-expanded="m.reasoningOpen"
                        @click="m.reasoningOpen=!m.reasoningOpen">
                  <Brain :size="16"/>
                  <span>{{ m.interrupted ? '思考已中断' : m.thinkingState==='thinking' ? '正在深度思考' : m.thinkingState==='finished' ? '思考过程' : m.thinkingState==='unavailable' ? '本轮未返回思考内容' : '等待模型思考' }}</span>
                  <LoaderCircle v-if="m.streaming&&m.thinkingState==='thinking'" class="spinning" :size="13"/>
                  <ChevronDown :size="15" class="reasoning-chevron" :class="{expanded:m.reasoningOpen}"/>
                </button>
                <div v-if="m.reasoningOpen" class="reasoning-body">
                  <div v-if="m.reasoning" class="reasoning-text">{{ m.reasoning }}</div>
                  <p v-else class="reasoning-placeholder">{{ m.interrupted ? '本轮已停止，尚未收到思考内容。' : m.thinkingState==='unavailable' ? '模型未返回思考片段，或本轮直接返回了处理说明。' : '正在准备本轮资料，收到思考内容后将在这里显示。' }}</p>
                </div>
              </div>
              <div v-if="m.content" class="markdown" v-html="render(m.content)"></div>
              <p v-if="m.interrupted" class="interrupted-note">本轮未确认完成，当前内容可能不完整。</p>
              <div v-if="m.content&&!m.streaming" class="message-actions">
                <button class="icon-button" :aria-label="copied===m.id?'已复制':'复制回答'" @click="copy(m)">
                  <Check v-if="copied===m.id" :size="15"/>
                  <Copy v-else :size="15"/>
                </button>
                <button v-if="m.result" class="result-button" @click="inspect(m.result)">
                  <BookOpen :size="14"/>
                  {{ m.result.sources.length ? m.result.sources.length + ' 条引用' : '查看处理结果' }}<span
                    v-if="m.result.branches.length">· {{ m.result.branches.length }} 个分支</span>
                  <ChevronRight :size="13"/>
                </button>
              </div>
            </template>
          </article>
          <div v-if="busy" class="thinking"><span class="assistant-mark"><Layers :size="15"/></span><span
              class="thinking-dots"><i></i><i></i><i></i></span><span>{{ phase }}</span></div>
        </div>
      </div>
      <div class="composer-area">
        <div v-if="error" class="inline-error" role="alert">{{ error }}
          <button v-if="id&&!busy" @click="select(id)">
            <RefreshCw :size="13"/>
            刷新会话
          </button>
        </div>
        <div class="composer"><textarea ref="input" v-model="prompt" aria-label="输入问题"
                                        placeholder="描述你遇到的问题，我们一起找到答案…" maxlength="1024" rows="2"
                                        :disabled="busy" @keydown="keydown"></textarea>
          <div class="composer-toolbar">
            <div class="composer-options">
              <span class="composer-mode"><span class="mode-dot"></span>智能协作</span>
              <button class="thinking-toggle" :class="{selected:deepThinking}" :aria-pressed="deepThinking"
                      :disabled="busy" title="让模型先思考再回答，可能需要更长时间"
                      @click="deepThinking=!deepThinking"><Brain :size="15"/>深度思考</button>
            </div>
            <button v-if="busy" class="send-button stop-button" aria-label="停止生成" @click="stop"><Square :size="17" fill="currentColor"/></button>
            <button v-else class="send-button" :disabled="!prompt.trim()||loading||!connected" aria-label="发送问题"
                    @click="send"><ArrowUp :size="22"/></button>
          </div>
        </div>
        <div class="composer-footnote"><span>答案有据可查，重要信息请结合原文确认。</span><span class="desktop-only">Enter 发送 · Shift + Enter 换行</span>
        </div>
      </div>
    </div>
    <aside v-if="showPanel&&panel" class="inspector">
      <header>
        <div><span class="eyebrow small">BEHIND THE ANSWER</span>
          <h3>本次回答的依据</h3></div>
        <button class="icon-button" aria-label="关闭依据面板" @click="showPanel=false">
          <X :size="18"/>
        </button>
      </header>
      <div class="inspector-content">
        <div class="inspect-section"><h4>理解你的问题</h4>
          <p>{{ panel.plan.rewrittenQuestion }}</p></div>
        <div class="inspect-section"><h4>处理结果 <span>{{ panel.branches.length }}</span></h4>
          <div v-for="(b,i) in panel.branches" :key="i" class="branch-card">
            <div><strong>{{ typeName[b.task.type] || b.task.type }}</strong><span class="status"
                                                                                  :class="b.status">{{ statusName[b.status] || b.status }}</span>
            </div>
            <p>{{ b.message }}</p></div>
          <p v-if="!panel.branches.length" class="muted">本轮直接交流，没有执行外部查询。</p></div>
        <div class="inspect-section"><h4>引用资料 <span>{{ panel.sources.length }}</span></h4>
          <details v-for="(s,i) in panel.sources" :key="s.chunkId" class="source-card">
            <summary><span class="source-number">{{ i + 1 }}</span><span>{{ s.docName }}</span>
              <ChevronRight :size="14"/>
            </summary>
            <p>{{ s.content }}</p></details>
          <p v-if="!panel.sources.length" class="muted">本轮没有引用知识库文档。</p></div>
      </div>
    </aside>
  </section>
</template>
