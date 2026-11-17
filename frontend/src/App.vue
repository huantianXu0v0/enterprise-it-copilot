<script setup lang="ts">
import {ref, onMounted} from 'vue';
import {
  MessageSquare,
  Library,
  Workflow,
  Wrench,
  Plus,
  ArrowUpRight,
  RefreshCw,
  Menu,
  ChevronRight
} from 'lucide-vue-next';
import ChatView from './views/ChatView.vue';
import KnowledgeView from './views/KnowledgeView.vue';
import IntentView from './views/IntentView.vue';
import ToolsView from './views/ToolsView.vue';
import {api, errorText} from './api';
import type {KnowledgeBase, Tool, Session} from './types';

const tab = ref('chat'), bases = ref<KnowledgeBase[]>([]), tools = ref<Tool[]>([]), sessions = ref<Session[]>([]),
    selected = ref<string | null>(null), busy = ref(false), connected = ref(false), connecting = ref(true),
    toolsError = ref(''), error = ref(''), mobile = ref(false), chat = ref<InstanceType<typeof ChatView>>();
const nav = [{key: 'chat', name: '智能问答', icon: MessageSquare}, {
  key: 'knowledge',
  name: '知识空间',
  icon: Library
}, {key: 'intents', name: '意图编排', icon: Workflow}, {key: 'tools', name: '工具服务', icon: Wrench}];

async function refresh() {
  connecting.value = true;
  error.value = '';
  const results = await Promise.allSettled([api<KnowledgeBase[]>('/workspace/knowledge-bases'), api<Tool[]>('/chat/mcp/tools', {}, 20000)]);
  if (results[0].status === 'fulfilled') {
    bases.value = results[0].value;
    connected.value = true;
  } else {
    connected.value = false;
    error.value = errorText(results[0].reason);
  }
  if (results[1].status === 'fulfilled') {
    tools.value = results[1].value;
    toolsError.value = '';
  } else {
    tools.value = [];
    toolsError.value = errorText(results[1].reason);
  }
  connecting.value = false;
}

function navigate(value: string) {
  tab.value = value;
  mobile.value = false;
}

function newChat() {
  if (busy.value) return;
  navigate('chat');
  chat.value?.newChat();
}

function selectSession(id: string) {
  if (busy.value) return;
  navigate('chat');
  chat.value?.select(id);
}

onMounted(refresh);
</script>
<template>
  <div class="app-shell">
    <div v-if="mobile" class="sidebar-scrim" @click="mobile=false"></div>
    <aside class="sidebar" :class="{open:mobile}"><a href="#" class="brand" @click.prevent="navigate('chat')"><span
        class="brand-mark"><span></span><span></span><span></span></span><strong>序知<span>IT WORKSPACE</span></strong></a>
      <button class="new-chat" :disabled="busy" @click="newChat">
        <Plus :size="18"/>
        开启新对话 <span>↵</span></button>
      <div class="nav-label">工作空间</div>
      <nav>
        <button v-for="item in nav" :key="item.key" :class="{active:tab===item.key}" @click="navigate(item.key)">
          <component :is="item.icon" :size="18"/>
          <span>{{ item.name }}</span>
          <ChevronRight v-if="tab===item.key" :size="15"/>
        </button>
      </nav>
      <div class="recent-heading"><span class="nav-label">最近对话</span>
        <MessageSquare :size="13"/>
      </div>
      <div class="session-list">
        <button v-for="s in sessions" :key="s.conversationId" :disabled="busy"
                :class="{selected:selected===s.conversationId&&tab==='chat'}" @click="selectSession(s.conversationId)">
          <span class="session-dot"></span><span>{{ s.title || '新对话' }}</span></button>
        <p v-if="!sessions.length" class="sidebar-empty">第一个问题，开启新的思路。</p></div>
      <div class="sidebar-bottom">
        <div class="workspace-note"><span class="tiny-orbit">✳</span>
          <div><strong>让知识，真正用起来。</strong>
            <p>连接文档，也连接解决方法。</p></div>
        </div>
        <div class="profile"><span class="avatar">我</span>
          <div><strong>我的工作空间</strong><span>本地学习环境</span></div>
          <span class="profile-dot"></span></div>
      </div>
    </aside>
    <main class="main-shell">
      <header class="topbar">
        <div class="breadcrumb">
          <button class="icon-button mobile-menu" aria-label="打开导航" @click="mobile=true">
            <Menu :size="20"/>
          </button>
          <span class="desktop-only">企业 IT 服务台</span><span
            class="breadcrumb-slash desktop-only">/</span><strong>{{ nav.find(n => n.key === tab)?.name }}</strong>
        </div>
        <div class="topbar-right"><span class="connection"
                                        :class="{online:connected}"><i></i>{{ connecting ? '连接中' : connected ? '服务已连接' : '服务未连接' }}</span>
          <button class="icon-button" aria-label="刷新服务状态" @click="refresh">
            <RefreshCw :size="15" :class="{spinning:connecting}"/>
          </button>
          <span class="topbar-divider"></span><span class="workspace-badge">IT COPILOT <ArrowUpRight :size="12"/></span>
        </div>
      </header>
      <div v-if="error" class="connection-banner" role="alert">{{ error }}
        <button @click="refresh">重新连接</button>
      </div>
      <ChatView v-show="tab==='chat'" ref="chat" :connected="connected" @sessions="sessions=$event"
                @selected="selected=$event" @busy="busy=$event" @setup="navigate('intents')"/>
      <KnowledgeView v-if="tab==='knowledge'" :bases="bases" @refresh="refresh"/>
      <IntentView v-if="tab==='intents'" :bases="bases" :tools="tools" :tools-error="toolsError"/>
      <ToolsView v-if="tab==='tools'" :tools="tools" :error="toolsError" @refresh="refresh"/>
    </main>
  </div>
</template>