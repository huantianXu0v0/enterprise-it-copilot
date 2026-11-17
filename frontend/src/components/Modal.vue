<script setup lang="ts">
import {X} from 'lucide-vue-next';
import {ref, onMounted, onUnmounted} from 'vue';

defineProps<{ title: string; wide?: boolean }>();
const emit = defineEmits<{ close: [] }>();
const panel = ref<HTMLElement>();
let previous: HTMLElement | null = null;

function keys(e: KeyboardEvent) {
  if (e.key === 'Escape') emit('close');
  if (e.key === 'Tab' && panel.value) {
    const els = [...panel.value.querySelectorAll<HTMLElement>('button:not([disabled]),input,textarea,select,[tabindex="0"]')];
    if (!els.length) return;
    const first = els[0], last = els[els.length - 1];
    if (e.shiftKey && document.activeElement === first) {
      e.preventDefault();
      last.focus();
    } else if (!e.shiftKey && document.activeElement === last) {
      e.preventDefault();
      first.focus();
    }
  }
}

onMounted(() => {
  previous = document.activeElement as HTMLElement;
  panel.value?.focus();
  document.addEventListener('keydown', keys);
});
onUnmounted(() => {
  document.removeEventListener('keydown', keys);
  previous?.focus();
});
</script>
<template>
  <Teleport to="body">
    <div class="modal-backdrop" @click.self="emit('close')">
      <section ref="panel" class="modal" :class="{wide}" role="dialog" aria-modal="true" :aria-label="title"
               tabindex="-1">
        <header><h2>{{ title }}</h2>
          <button class="icon-button" aria-label="关闭" @click="emit('close')">
            <X :size="20"/>
          </button>
        </header>
        <slot/>
      </section>
    </div>
  </Teleport>
</template>