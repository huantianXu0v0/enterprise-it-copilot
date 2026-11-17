<script setup lang="ts">
import {ref} from 'vue';
import {Wrench, Play, RefreshCw, Clock3, Ticket, Headphones, CheckCircle2, LoaderCircle} from 'lucide-vue-next';
import Modal from '../components/Modal.vue';
import {post, errorText} from '../api';
import type {Tool} from '../types';

defineProps<{ tools: Tool[]; error: string }>();
const emit = defineEmits<{ refresh: [] }>();
const selected = ref<Tool | null>(null), args = ref<Record<string, string>>({}), busy = ref(false), failure = ref(''),
    result = ref<{ content?: { type: string; text?: string }[]; isError?: boolean } | null>(null);
const labels: Record<string, string> = {
  current_time: '日期与时间',
  ticket_status: '工单进度',
  it_contact: '服务台联系'
};

function open(tool: Tool) {
  selected.value = tool;
  args.value = {};
  result.value = null;
  failure.value = '';
}

async function call() {
  if (!selected.value || busy.value) return;
  busy.value = true;
  failure.value = '';
  result.value = null;
  try {
    const values = Object.fromEntries(Object.entries(args.value).filter(([, v]) => v.trim()).map(([k, v]) => [k, v.trim()]));
    result.value = await post('/chat/mcp/call', {toolName: selected.value.name, arguments: values});
  } catch (e) {
    failure.value = errorText(e);
  } finally {
    busy.value = false;
  }
}
</script>
<template>
  <section class="management-page">
    <div class="page-heading">
      <div>
        <div class="eyebrow">LESS CLICKING, MORE DOING</div>
        <h1>把查询，交给合适的工具。</h1>
        <p>这里展示服务端实际提供的能力，工具名称与意图绑定一一对应。</p></div>
      <button class="secondary-button" @click="emit('refresh')">
        <RefreshCw :size="16"/>
        刷新工具
      </button>
    </div>
    <div v-if="error" class="inline-error" role="alert">{{ error }}</div>
    <div class="tool-grid">
      <article v-for="tool in tools" :key="tool.name" class="tool-card">
        <div class="tool-top"><span class="starter-icon green"><Clock3 v-if="tool.name==='current_time'" :size="23"/><Ticket
            v-else-if="tool.name==='ticket_status'" :size="23"/><Headphones v-else-if="tool.name==='it_contact'"
                                                                            :size="23"/><Wrench v-else
                                                                                                :size="23"/></span><span
            class="tag">{{ tool.annotations?.readOnlyHint ? '只读工具' : '需单独接入' }}</span></div>
        <h2>{{ labels[tool.name] || tool.name }}</h2><code>{{ tool.name }}</code>
        <p>{{ tool.description }}</p>
        <div class="tool-parameters"><span v-for="(prop,key) in tool.inputSchema.properties" :key="key"
                                           class="parameter">{{ key }}<i>{{ tool.inputSchema.required?.includes(String(key)) ? '必填' : '可选' }}</i></span><span
            v-if="!Object.keys(tool.inputSchema.properties||{}).length" class="muted">无需参数</span></div>
        <button class="secondary-button" :disabled="!tool.annotations?.readOnlyHint" @click="open(tool)">
          <Play :size="14"/>
          测试工具
        </button>
      </article>
    </div>
    <div v-if="!tools.length&&!error" class="empty-state">
      <Wrench :size="32"/>
      <h3>还没有发现工具</h3>
      <p>启动工具服务后，点击刷新。</p></div>
    <Modal v-if="selected" :title="'测试 · '+(labels[selected.name]||selected.name)" @close="!busy&&(selected=null)">
      <form @submit.prevent="call"><p class="form-help">{{ selected.description }}</p><label
          v-for="(prop,key) in selected.inputSchema.properties" :key="key">{{ key }} <span
          class="muted">{{ selected.inputSchema.required?.includes(String(key)) ? '必填' : '可选' }}</span><input
          v-model="args[String(key)]" :required="selected.inputSchema.required?.includes(String(key))"
          :placeholder="prop.description||String(key)"/></label>
        <p v-if="failure" class="inline-error">{{ failure }}</p>
        <div v-if="result" class="tool-result" :class="{failure:result.isError}">
          <strong>{{ result.isError ? '工具未完成执行' : '执行完成' }}</strong>
          <pre v-for="(item,i) in result.content" :key="i">{{ item.text || '当前页面仅预览文本结果' }}</pre>
        </div>
        <footer>
          <button type="button" class="secondary-button" :disabled="busy" @click="selected=null">关闭</button>
          <button class="primary-button" :disabled="busy">
            <LoaderCircle v-if="busy" class="spinning" :size="16"/>
            {{ busy ? '执行中…' : '执行查询' }}
          </button>
        </footer>
      </form>
    </Modal>
  </section>
</template>