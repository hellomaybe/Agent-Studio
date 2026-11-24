<script setup lang="ts">
import { computed, onMounted, ref, watch } from 'vue';
import { ApiError, errorText } from '../http';
import GraphEditor from './GraphEditor.vue';
import NodeLab from './NodeLab.vue';
import RunConsole from './RunConsole.vue';
import ReleasePanel from './ReleasePanel.vue';
import { workbench, type AppInfo, type Draft, type Fixture, type Published } from './client';
import type { Capability, GraphDefinition, GraphIssue } from './types';

const props = defineProps<{ appId: string; runId: string }>();
const emit = defineEmits<{ navigate: [app: string, run?: string]; dirty: [value: boolean] }>();
const historyInput = ref<import('./client').HistoricalInput>();
type Tab = 'editor' | 'debug' | 'review' | 'runs';
const tabs: { key: Tab; title: string }[] = [
  { key: 'editor', title: '流程编排' },
  { key: 'debug', title: '节点调试' },
  { key: 'review', title: '对比与发布' },
  { key: 'runs', title: '运行与恢复' },
];
const active = ref<Tab>(props.runId ? 'runs' : 'editor');
const apps = ref<AppInfo[]>([]),
  caps = ref<Capability[]>([]),
  fixtures = ref<Fixture[]>([]);
const draft = ref<Draft>(),
  published = ref<Published>(),
  graph = ref<GraphDefinition>(),
  selected = ref(''),
  error = ref(''),
  note = ref(''),
  busy = ref(false);
const issues = ref<GraphIssue[]>([]),
  compareKey = ref(0);
const dirty = computed(() =>
  Boolean(
    graph.value && draft.value && JSON.stringify(graph.value) !== JSON.stringify(draft.value.graph),
  ),
);
watch(dirty, (n) => emit('dirty', n), { immediate: true });
watch(
  () => props.runId,
  (id) => {
    if (id) active.value = 'runs';
  },
);
const clone = (g: GraphDefinition) => JSON.parse(JSON.stringify(g)) as GraphDefinition;
function setDraft(d: Draft) {
  draft.value = d;
  graph.value = clone(d.graph);
  if (!graph.value.nodes.some((n) => n.id === selected.value))
    selected.value =
      graph.value.nodes.find((n) => n.type === 'extract-tasks' || n.type === 'classify-feedback')
        ?.id ??
      graph.value.nodes[0]?.id ??
      '';
}
async function load() {
  busy.value = true;
  error.value = '';
  note.value = '';
  issues.value = [];
  try {
    const [list, nodes, s, d] = await Promise.all([
      workbench.apps(),
      workbench.nodes(),
      workbench.samples(props.appId),
      workbench.draft(props.appId),
    ]);
    const release = await workbench.version(props.appId, d.version);
    apps.value = list;
    caps.value = nodes;
    fixtures.value = s;
    published.value = release;
    setDraft(d);
  } catch (e) {
    error.value = errorText(e);
  } finally {
    busy.value = false;
  }
}
function graphChanged(g: GraphDefinition) {
  graph.value = g;
  issues.value = [];
  error.value = '';
  note.value = '';
  compareKey.value++;
}
async function save() {
  if (!graph.value || !draft.value || busy.value) return;
  busy.value = true;
  error.value = '';
  note.value = '';
  try {
    const saved = await workbench.save(props.appId, draft.value.revision, graph.value);
    setDraft(saved);
    note.value = '已保存草稿 r' + saved.revision;
    compareKey.value++;
  } catch (e) {
    error.value = errorText(e);
    issues.value = [{ detail: error.value, ...(e instanceof ApiError ? e.location : {}) }];
    if (e instanceof ApiError && e.location?.nodeId) selected.value = e.location.nodeId;
  } finally {
    busy.value = false;
  }
}
async function publish() {
  if (busy.value || !draft.value || dirty.value) return;
  busy.value = true;
  error.value = '';
  try {
    const release = await workbench.publish(props.appId, draft.value.revision);
    published.value = release;
    draft.value = { ...draft.value, version: release.version };
    note.value = '已发布 v' + release.version;
    compareKey.value++;
  } catch (e) {
    error.value = errorText(e);
  } finally {
    busy.value = false;
  }
}
function reload() {
  if (!dirty.value || window.confirm('重新载入将覆盖本页未保存的图。继续？')) void load();
}
function debug(id: string) {
  selected.value = id;
  active.value = 'debug';
}
function switchApplication(event: Event) {
  const control = event.target as HTMLSelectElement;
  const id = control.value;
  control.value = props.appId;
  emit('navigate', id);
}
onMounted(load);
</script>
<template>
  <main class="wb" aria-label="应用编排工作台">
    <header class="wb-heading">
      <div>
        <h1>{{ apps.find((a) => a.id === appId)?.name ?? '应用工作台' }}</h1>
        <p v-if="draft">
          草稿 r{{ draft.revision }}
          <span aria-hidden="true">/</span>
          已发布 v{{ published?.version }}
          <span v-if="dirty" class="wb-unsaved">· 有未保存的编辑</span>
        </p>
      </div>
      <div class="wb-actions">
        <label v-if="apps.length" class="wb-app-picker">
          应用
          <select :value="appId" @change="switchApplication">
            <option v-for="a in apps" :key="a.id" :value="a.id">{{ a.name }}</option>
          </select>
        </label>
        <button class="wb-primary" :disabled="!dirty || busy" @click="save">保存图草稿</button>
      </div>
    </header>
    <nav class="wb-tabs" aria-label="工作台页面">
      <button
        v-for="tab in tabs"
        :key="tab.key"
        :aria-pressed="active === tab.key"
        @click="active = tab.key"
      >
        {{ tab.title }}
      </button>
    </nav>
    <div v-if="error" class="wb-error" role="alert">
      {{ error }}
      <button @click="reload">重新载入</button>
    </div>
    <p v-if="note" class="wb-note" role="status">{{ note }}</p>
    <template v-if="graph && draft && published">
      <GraphEditor
        v-if="active === 'editor'"
        :model-value="graph"
        :capabilities="caps"
        :selected="selected"
        :issues="issues"
        :readonly="busy"
        @update:model-value="graphChanged"
        @select="selected = $event"
        @debug="debug"
      />
      <NodeLab
        :history-input="historyInput"
        v-show="active === 'debug'"
        :app-id="appId"
        :graph="graph"
        :revision="draft.revision"
        :published="published"
        :nodes="caps"
        :selected="selected"
        :live-enabled="!!draft.liveEnabled"
        @select="selected = $event"
      />
      <ReleasePanel
        :capabilities="caps"
        v-show="active === 'review'"
        :app-id="appId"
        :graph="graph"
        :draft="draft"
        :published="published"
        :dirty="dirty"
        :busy="busy"
        :change-id="compareKey"
        @publish="publish"
        @restore="
          (g) => {
            graphChanged(clone(g));
            active = 'editor';
          }
        "
      />
      <RunConsole
        v-if="active === 'runs'"
        :app-id="appId"
        :published="published"
        :fixtures="fixtures"
        :run-id="runId"
        :live-enabled="!!draft.liveEnabled"
        :capabilities="caps"
        @open="emit('navigate', appId, $event)"
        @inspect="
          (h) => {
            historyInput = h;
            debug(h.nodeId);
          }
        "
      />
    </template>
    <p v-else-if="busy" class="wb-section" aria-live="polite">正在载入应用…</p>
  </main>
</template>
