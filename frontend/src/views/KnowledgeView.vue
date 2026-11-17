<script setup lang="ts">
import {ref, watch} from 'vue';
import {Library, Plus, Upload, FileText, ArrowUpRight, LoaderCircle, Search, Layers, RefreshCw} from 'lucide-vue-next';
import Modal from '../components/Modal.vue';
import {api, post, errorText} from '../api';
import type {KnowledgeBase, DocumentItem} from '../types';

const props = defineProps<{ bases: KnowledgeBase[] }>(), emit = defineEmits<{ refresh: [] }>();
const selected = ref(''), docs = ref<DocumentItem[]>([]), loading = ref(false), busy = ref(false), error = ref(''),
    notice = ref(''), creating = ref(false), name = ref(''), collection = ref(''), file = ref<HTMLInputElement>(),
    preview = ref<unknown[] | null>(null);
let version = 0;
const statusLabel: Record<string, string> = {
  pending: '待建立索引',
  running: '处理中',
  success: '可检索',
  failed: '处理失败'
};
const bytes = (size: number) => !size ? '—' : size > 1048576 ? (size / 1048576).toFixed(1) + ' MB' : (size / 1024).toFixed(1) + ' KB';

async function load() {
  const token = ++version;
  docs.value = [];
  if (!selected.value) return;
  loading.value = true;
  error.value = '';
  try {
    const result = await api<DocumentItem[]>('/workspace/knowledge-bases/' + selected.value + '/documents');
    if (token === version) docs.value = result;
  } catch (e) {
    error.value = errorText(e);
  } finally {
    if (token === version) loading.value = false;
  }
}

watch(() => props.bases, list => {
  if (list.length && !list.some(b => b.id === selected.value)) selected.value = list[0].id;
}, {immediate: true});
watch(selected, load, {immediate: true});

async function create() {
  if (busy.value) return;
  busy.value = true;
  error.value = '';
  try {
    const params = new URLSearchParams({
      kb_name: name.value,
      collection_name: collection.value,
      embedding_model: props.bases[0]?.embeddingModel || 'qwen-emb-8b'
    });
    const result = await post<{ kbId: string }>('/kb?' + params);
    creating.value = false;
    name.value = '';
    collection.value = '';
    selected.value = result.kbId;
    emit('refresh');
    notice.value = '知识库已创建，可以开始上传文档。';
  } catch (e) {
    error.value = errorText(e);
  } finally {
    busy.value = false;
  }
}

async function upload(event: Event) {
  const input = event.target as HTMLInputElement;
  const f = input.files?.[0];
  if (!f || !selected.value) return;
  busy.value = true;
  error.value = '';
  notice.value = '正在上传并建立索引…';
  const kb = selected.value;
  try {
    const form = new FormData();
    form.append('file', f);
    const result = await api<{ docId: string }>('/kb/' + kb + '/docs/upload', {method: 'POST', body: form});
    await post('/kb/' + result.docId + '/docs/chunk?chunkSize=256&overlap=32');
    notice.value = '文档已完成解析、分块与向量化，现在可以检索。';
    await load();
  } catch (e) {
    const message = errorText(e);
    notice.value = '';
    await load();
    error.value = message;
  } finally {
    busy.value = false;
    input.value = '';
  }
}

async function ingest(doc: DocumentItem) {
  busy.value = true;
  error.value = '';
  try {
    await post('/kb/' + doc.id + '/docs/chunk?chunkSize=256&overlap=32');
    await load();
    notice.value = '索引已更新。';
  } catch (e) {
    error.value = errorText(e);
  } finally {
    busy.value = false;
  }
}

async function showPreview(doc: DocumentItem) {
  busy.value = true;
  error.value = '';
  try {
    preview.value = await post<unknown[]>('/kb/' + doc.id + '/docs/chunk-preview?chunkSize=256&overlap=32');
  } catch (e) {
    error.value = errorText(e);
  } finally {
    busy.value = false;
  }
}
</script>
<template>
  <section class="management-page">
    <div class="page-heading">
      <div>
        <div class="eyebrow">KNOWLEDGE, CONNECTED</div>
        <h1>让每一份文档，都能被用到。</h1>
        <p>集中管理知识，保留回答的来源与依据。</p></div>
      <button class="primary-button" @click="creating=true">
        <Plus :size="16"/>
        新建知识库
      </button>
    </div>
    <div v-if="error" class="inline-error" role="alert">{{ error }}</div>
    <div v-if="notice" class="notice" role="status">
      <LoaderCircle v-if="busy" :size="16" class="spinning"/>
      {{ notice }}
    </div>
    <div class="kb-grid">
      <button v-for="kb in bases" :key="kb.id" class="kb-card" :class="{selected:selected===kb.id}" :disabled="busy"
              @click="selected=kb.id"><span class="starter-icon blue"><Library
          :size="22"/></span><strong>{{ kb.name }}</strong><span class="muted">{{ kb.collectionName }}</span><span
          class="kb-card-foot">查看文档 <ArrowUpRight :size="15"/></span></button>
      <button v-if="!bases.length" class="empty-card" @click="creating=true">
        <Plus :size="28"/>
        <strong>建立第一座知识库</strong><span>从一份文档开始积累。</span></button>
    </div>
    <div class="section-toolbar">
      <div><h2>文档列表 <span class="count">{{ docs.length }}</span></h2>
        <p>支持解析与分块的文档，由后端统一处理。</p></div>
      <div class="button-group">
        <button class="secondary-button" :disabled="busy||loading" @click="load">
          <RefreshCw :size="15"/>
          刷新
        </button>
        <button class="primary-button" :disabled="!selected||busy" @click="file?.click()">
          <LoaderCircle v-if="busy" :size="16" class="spinning"/>
          <Upload v-else :size="16"/>
          {{ busy ? '处理中' : '上传并建立索引' }}
        </button>
        <input ref="file" type="file" hidden @change="upload"/></div>
    </div>
    <div class="table-wrap">
      <table>
        <thead>
        <tr>
          <th>文档名称</th>
          <th>状态</th>
          <th>大小</th>
          <th>分块</th>
          <th>操作</th>
        </tr>
        </thead>
        <tbody>
        <tr v-for="doc in docs" :key="doc.id">
          <td><span class="document-name"><FileText :size="18"/>{{ doc.docName }}</span></td>
          <td><span class="status" :class="doc.status">{{ statusLabel[doc.status] || doc.status }}</span></td>
          <td>{{ bytes(doc.fileSize) }}</td>
          <td>{{ doc.chunkCount || 0 }}</td>
          <td>
            <div class="button-group">
              <button class="text-button" :disabled="busy" @click="showPreview(doc)">查看分块</button>
              <button class="text-button" :disabled="busy" @click="ingest(doc)">
                {{ doc.status === 'success' ? '更新索引' : '建立索引' }}
              </button>
            </div>
          </td>
        </tr>
        <tr v-if="!docs.length">
          <td colspan="5" class="table-empty">
            {{ loading ? '正在读取文档…' : '这里还没有文档，上传后即可建立知识索引。' }}
          </td>
        </tr>
        </tbody>
      </table>
    </div>
    <Modal v-if="creating" title="创建知识库" @close="!busy&&(creating=false)">
      <form @submit.prevent="create"><label>知识库名称<input v-model="name" required maxlength="128"
                                                             placeholder="例如：企业 IT 指南"/></label><label>英文标识<input
          v-model="collection" required pattern="[a-z][a-z0-9_-]*" maxlength="48" placeholder="例如：it-guide"/></label>
        <p class="form-help">索引名称将使用 kb- 前缀。创建后可上传文档。</p>
        <p v-if="error" class="inline-error">{{ error }}</p>
        <footer>
          <button type="button" class="secondary-button" :disabled="busy" @click="creating=false">取消</button>
          <button class="primary-button" :disabled="busy">{{ busy ? '创建中…' : '创建知识库' }}</button>
        </footer>
      </form>
    </Modal>
    <Modal v-if="preview" title="分块预览" wide @close="preview=null">
      <div class="preview-list">
        <article v-for="(chunk,i) in preview" :key="i"><span class="tag">分块 {{ i + 1 }}</span>
          <pre>{{ typeof chunk === 'object' ? JSON.stringify(chunk, null, 2) : chunk }}</pre>
        </article>
      </div>
    </Modal>
  </section>
</template>