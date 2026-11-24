<script setup lang="ts">
import { computed, onMounted, onBeforeUnmount, ref } from 'vue';
import LegacyMeeting from './App.vue';
import Workbench from './workbench/Workbench.vue';
import './workbench/workbench.css';

const locationHash = ref(window.location.hash);
const parts = computed(() => locationHash.value.replace(/^#\/?/, '').split('/'));
const legacy = computed(() => parts.value[0] === 'legacy');
const appId = computed(() =>
  parts.value[0] === 'apps' && parts.value[1] ? parts.value[1] : 'meeting-notes',
);
const runId = computed(() => (parts.value[2] === 'runs' ? (parts.value[3] ?? '') : ''));
const dirty = ref(false);
function sync() {
  const next = window.location.hash.replace(/^#\/?/, '').split('/');
  const changing =
    (next[0] === 'legacy') !== legacy.value || (next[0] === 'apps' && next[1] !== appId.value);
  if (changing && dirty.value && !window.confirm('离开会丢弃未保存的图编辑。继续？')) {
    history.replaceState(
      null,
      '',
      window.location.pathname + window.location.search + locationHash.value,
    );
    return;
  }
  if (changing) dirty.value = false;
  locationHash.value = window.location.hash;
}
function navigate(app: string, run?: string) {
  if (
    (legacy.value || app !== appId.value) &&
    dirty.value &&
    !window.confirm('离开会丢弃未保存的图编辑。继续？')
  )
    return;
  if (app !== appId.value) dirty.value = false;
  window.location.hash =
    '/apps/' + encodeURIComponent(app) + (run ? '/runs/' + encodeURIComponent(run) : '');
}
function openLegacy() {
  if (!dirty.value || window.confirm('离开会丢弃未保存的图编辑。继续？')) {
    dirty.value = false;
    window.location.hash = '/legacy';
  }
}
function unload(e: BeforeUnloadEvent) {
  if (dirty.value) {
    e.preventDefault();
    e.returnValue = '';
  }
}
onMounted(() => {
  window.addEventListener('hashchange', sync);
  window.addEventListener('beforeunload', unload);
});
onBeforeUnmount(() => {
  window.removeEventListener('hashchange', sync);
  window.removeEventListener('beforeunload', unload);
});
</script>
<template>
  <header class="wb-top">
    <div class="wb-brand">
      <span class="wb-mark" aria-hidden="true">A</span>
      Agent Studio
    </div>
    <button v-if="legacy" @click="navigate('meeting-notes')">返回应用工作台</button>
    <button v-else @click="openLegacy">旧版会议运行</button>
  </header>
  <LegacyMeeting v-if="legacy" />
  <Workbench
    v-else
    :key="appId"
    :app-id="appId"
    :run-id="runId"
    @navigate="navigate"
    @dirty="dirty = $event"
  />
</template>
