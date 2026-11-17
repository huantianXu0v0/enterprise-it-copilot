<script setup lang="ts">
import {ref, computed, onMounted} from 'vue';
import {
  Workflow,
  Search,
  RefreshCw,
  Plus,
  ArrowRight,
  BookOpen,
  Wrench,
  MessageSquare,
  Pencil,
  CheckCircle2,
  LoaderCircle
} from 'lucide-vue-next';
import Modal from '../components/Modal.vue';
import {api, post, errorText} from '../api';
import type {Intent, KnowledgeBase, Tool} from '../types';

const props = defineProps<{ bases: KnowledgeBase[]; tools: Tool[]; toolsError: string }>();
const tree = ref<Intent[]>([]), search = ref(''), filter = ref('all'), error = ref(''), notice = ref(''),
    loading = ref(false), busy = ref(false), installing = ref(false), kbId = ref(''),
    editing = ref<Intent | null>(null), kind = ref(0), binding = ref(''), description = ref('');

function flatten(items: Intent[]): Intent[] {
  return items.flatMap(n => [n, ...flatten(n.children || [])]);
}

const leaves = computed(() => flatten(tree.value).filter(n => n.executable)),
    rows = computed(() => leaves.value.filter(n => (filter.value === 'all' || String(n.kind) === filter.value) && [n.name, n.intentCode, n.description, ...n.examples || []].join(' ').toLowerCase().includes(search.value.toLowerCase())));
const names: Record<number, string> = {0: '知识检索', 1: '系统交流', 2: '工具查询'};

async function load() {
  loading.value = true;
  error.value = '';
  try {
    tree.value = await api<Intent[]>('/intent/nodes/tree');
  } catch (e) {
    error.value = errorText(e);
  } finally {
    loading.value = false;
  }
}

async function refreshCache() {
  busy.value = true;
  error.value = '';
  try {
    await api('/intent/nodes/tree/cache', {method: 'DELETE'});
    await load();
    notice.value = '缓存已清除，下次识别将使用最新配置。';
  } catch (e) {
    error.value = errorText(e);
  } finally {
    busy.value = false;
  }
}

function openInstall() {
  kbId.value = props.bases.find(b => /it|信息|技术/i.test(b.name))?.id || props.bases[0]?.id || '';
  installing.value = true;
  error.value = '';
}

async function install() {
  if (!kbId.value) return;
  busy.value = true;
  error.value = '';
  try {
    const r = await post<{
      createdCount: number;
      skippedTools: string[]
    }>('/workspace/intent-pack?kbId=' + encodeURIComponent(kbId.value));
    notice.value = '已新增 ' + r.createdCount + ' 个节点，已有节点保留。' + (r.skippedTools.length ? ' 未接入的工具：' + r.skippedTools.join('、') : '');
    installing.value = false;
    await load();
  } catch (e) {
    error.value = errorText(e);
  } finally {
    busy.value = false;
  }
}

function edit(n: Intent) {
  editing.value = n;
  kind.value = n.kind;
  binding.value = n.kbId || n.mcpToolId || '';
  description.value = n.description || '';
  error.value = '';
}

async function save() {
  if (!editing.value) return;
  busy.value = true;
  error.value = '';
  try {
    await api('/intent/nodes/' + encodeURIComponent(editing.value.intentCode) + '/binding', {
      method: 'PUT',
      body: JSON.stringify({
        kind: kind.value,
        kbId: kind.value === 0 ? binding.value : null,
        mcpToolId: kind.value === 2 ? binding.value : null,
        description: description.value
      })
    });
    editing.value = null;
    notice.value = '绑定已更新，意图缓存已清除。';
    await load();
  } catch (e) {
    error.value = errorText(e);
  } finally {
    busy.value = false;
  }
}

function target(n: Intent) {
  if (n.kind === 1) return '直接交流';
  if (n.kind === 0) return props.bases.find(b => b.id === n.kbId)?.name || n.kbId || '未绑定';
  return n.mcpToolId || '未绑定';
}

function missing(n: Intent) {
  return n.kind === 2 && !props.toolsError && !props.tools.some(t => t.name === n.mcpToolId);
}

onMounted(load);
</script>
<template>
  <section class="management-page">
    <div class="page-heading">
      <div>
        <div class="eyebrow">A CLEAR PATH FOR EVERY QUESTION</div>
        <h1>让问题，找到正确的去向。</h1>
        <p>用业务分类组织意图，用真实资源决定能做什么。</p></div>
      <button class="primary-button" :disabled="busy" @click="openInstall">
        <Plus :size="16"/>
        完善 IT 意图
      </button>
    </div>
    <div class="intent-overview">
      <div><span class="overview-icon"><Workflow :size="24"/></span>
        <div><strong>{{ leaves.length }} 个可执行意图</strong>
          <p>知识检索、工具查询与日常交流，按需协作。</p></div>
      </div>
      <span class="overview-note">未命中时，先理解，再回应。</span></div>
    <div v-if="error" class="inline-error" role="alert">{{ error }}</div>
    <div v-if="notice" class="notice" role="status">{{ notice }}</div>
    <div class="section-toolbar">
      <div class="filter-tabs">
        <button
            v-for="f in [{v:'all',label:'全部意图'},{v:'0',label:'知识检索'},{v:'2',label:'工具查询'},{v:'1',label:'系统交流'}]"
            :key="f.v" :class="{active:filter===f.v}" @click="filter=f.v">{{ f.label }}
        </button>
      </div>
      <div class="button-group"><label class="search-field">
        <Search :size="16"/>
        <input v-model="search" aria-label="搜索意图" placeholder="搜索意图或示例"/></label>
        <button class="secondary-button" :disabled="busy" @click="refreshCache">
          <RefreshCw :size="15"/>
          刷新缓存
        </button>
      </div>
    </div>
    <div class="intent-list">
      <article v-for="node in rows" :key="node.intentCode" class="intent-row"><span class="intent-kind"
                                                                                    :class="'kind-'+node.kind"><BookOpen
          v-if="node.kind===0" :size="18"/><Wrench v-else-if="node.kind===2" :size="18"/><MessageSquare v-else
                                                                                                        :size="18"/></span>
        <div class="intent-description">
          <div><h3>{{ node.name }}</h3><span class="tag">{{ names[node.kind] }}</span></div>
          <p>{{ node.description }}</p><span class="intent-example"
                                             v-if="node.examples?.length">“{{ node.examples[0] }}”</span></div>
        <div class="intent-binding"><span
            :class="{invalid:missing(node)}">{{ target(node) }}</span><small>{{ missing(node) ? '工具未注册，请修改绑定' : node.intentCode }}</small>
        </div>
        <button class="icon-button" :aria-label="'编辑'+node.name+'绑定'" @click="edit(node)">
          <Pencil :size="16"/>
        </button>
      </article>
      <div v-if="!rows.length" class="empty-state">
        <Workflow :size="32"/>
        <h3>{{ loading ? '正在读取意图…' : '还没有符合条件的意图' }}</h3>
        <p>使用“完善 IT 意图”创建常用分类，或调整搜索条件。</p></div>
    </div>
    <Modal v-if="installing" title="完善常用 IT 意图" @close="!busy&&(installing=false)">
      <form @submit.prevent="install"><p class="form-help">
        建立网络、账号、软件、设备、安全和流程分类，连接已注册的三个工具，并补充交流意图。已有节点不会被覆盖。</p><label>绑定 IT
        知识库<select v-model="kbId" required>
          <option value="" disabled>请选择知识库</option>
          <option v-for="kb in bases" :key="kb.id" :value="kb.id">{{ kb.name }}</option>
        </select></label>
        <p v-if="!bases.length" class="inline-error">请先在知识空间创建知识库。</p>
        <p v-if="error" class="inline-error">{{ error }}</p>
        <footer>
          <button type="button" class="secondary-button" :disabled="busy" @click="installing=false">取消</button>
          <button class="primary-button" :disabled="busy||!kbId">
            <LoaderCircle v-if="busy" class="spinning" :size="16"/>
            {{ busy ? '配置中…' : '创建并绑定' }}
          </button>
        </footer>
      </form>
    </Modal>
    <Modal v-if="editing" :title="'编辑绑定 · '+editing.name" @close="!busy&&(editing=null)">
      <form @submit.prevent="save"><label>执行类型<select v-model.number="kind" @change="binding=''">
        <option :value="0">知识库检索</option>
        <option :value="2">工具查询</option>
        <option :value="1">系统交流</option>
      </select></label><label v-if="kind===0">知识库<select v-model="binding" required>
        <option value="" disabled>选择知识库</option>
        <option v-for="kb in bases" :key="kb.id" :value="kb.id">{{ kb.name }}</option>
      </select></label><label v-if="kind===2">已注册工具<select v-model="binding" required>
        <option value="" disabled>选择工具</option>
        <option v-for="tool in tools" :key="tool.name" :value="tool.name">{{ tool.name }}</option>
      </select></label><label>匹配范围<textarea v-model="description" rows="4" required
                                                maxlength="512"></textarea></label>
        <p v-if="error" class="inline-error">{{ error }}</p>
        <footer>
          <button type="button" class="secondary-button" :disabled="busy" @click="editing=null">取消</button>
          <button class="primary-button" :disabled="busy">{{ busy ? '保存中…' : '保存并刷新缓存' }}</button>
        </footer>
      </form>
    </Modal>
  </section>
</template>