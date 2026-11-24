<script setup lang="ts">
import { computed, ref, watch } from 'vue';

import { nodeLabels, statusLabels, studioApi, type StudioRun } from './api';

import EditorCanvas from './workbench/EditorCanvas.vue';
import { legacySnapshot } from './legacy-snapshot';

const props = defineProps<{ focus: string; sequence: number }>();
const emit = defineEmits<{ resume: [run: StudioRun] }>();
const rows = ref<StudioRun[]>([]);
const selected = ref<StudioRun>();
const selectedNode = ref('start');
const busy = ref(false);
const error = ref('');
const snapshotText = ref<HTMLTextAreaElement>();
const copyNote = ref('');
const snapshot = computed(() => (selected.value ? legacySnapshot(selected.value) : undefined));
const snapshotJson = computed(() =>
  snapshot.value ? JSON.stringify(snapshot.value, null, 2) : '',
);
watch(
  () => selected.value?.runId,
  () => {
    copyNote.value = '';
  },
);
async function copySnapshot() {
  try {
    await navigator.clipboard.writeText(snapshotJson.value);
    copyNote.value = '已复制旧版图快照。';
  } catch {
    snapshotText.value?.select();
    copyNote.value = '已选中图快照文本，可使用系统复制快捷键。';
  }
}
let request = 0;
const kept = computed(() => {
  const run = selected.value;
  if (!Array.isArray(run?.items) || !Array.isArray(run.selectedIds)) return undefined;
  return run.items.filter((item) => run.selectedIds.includes(item.id));
});
async function refresh(focusId?: string) {
  const ticket = ++request;
  const previousId = selected.value?.runId;
  busy.value = true;
  error.value = '';
  try {
    const list = await studioApi.runs();
    if (ticket !== request) return;
    rows.value = list;
    const currentId = selected.value?.runId;
    const id = currentId === previousId ? (focusId ?? currentId) : currentId;
    const run = list.find((item) => item.runId === id) ?? list[0];
    if (run?.runId !== currentId) selectedNode.value = run?.path?.at(-1) ?? 'start';
    selected.value = run;
  } catch (error_) {
    if (ticket === request)
      error.value = error_ instanceof Error ? error_.message : '读取运行记录失败';
  } finally {
    if (ticket === request) busy.value = false;
  }
}
watch(
  [() => props.focus, () => props.sequence],
  ([focus], [previousFocus]) => {
    void refresh(focus && focus !== previousFocus ? focus : undefined);
  },
  { immediate: true },
);
function inspect(run: StudioRun) {
  selected.value = run;
  selectedNode.value = run.path?.at(-1) ?? 'start';
}
</script>
<template>
  <section class="history">
    <header>
      <div>
        <h3>运行记录</h3>
        <p>最近 50 次试运行。回放使用运行时保存的草稿快照。</p>
      </div>
      <el-button :loading="busy" @click="refresh()">刷新记录</el-button>
    </header>
    <el-alert v-if="error" :title="error" type="error" :closable="false" />
    <el-empty v-else-if="rows.length === 0 && !busy" description="还没有试运行记录" />
    <div v-if="rows.length > 0" class="history-layout">
      <div class="run-list" aria-label="运行列表">
        <button
          v-for="row in rows"
          :key="row.runId"
          type="button"
          :aria-pressed="row.runId === selected?.runId"
          @click="inspect(row)"
        >
          <strong>{{ row.title }}</strong>
          <span>{{ statusLabels[row.status] }} · r{{ row.revision }}</span>
          <small>{{ row.createdAt?.replace('T', ' ').slice(0, 19) }}</small>
        </button>
      </div>
      <div v-if="selected" class="detail">
        <div class="detail-header">
          <div>
            <strong>{{ selected.title }}</strong>
            <p>
              {{ selected.mode === 'live' ? '真实模型' : '固定样例' }} ·
              {{ selected.runId }}
            </p>
            <p v-if="selected.usage">
              {{ selected.usage.model }} · 输入 {{ selected.usage.inputTokens }} token · 输出
              {{ selected.usage.outputTokens }} token
            </p>
          </div>
          <el-button
            v-if="selected.status === 'waiting'"
            type="primary"
            @click="emit('resume', selected)"
          >
            继续确认
          </el-button>
        </div>
        <EditorCanvas
          v-if="snapshot"
          :graph="snapshot.graph"
          :capabilities="snapshot.operations"
          :selected="selectedNode"
          readonly
          :issues="[]"
          :visited="snapshot.history.path ?? []"
          :active-node="snapshot.history.path?.at(-1)"
          :trace-status="selected.status"
          @select="selectedNode = $event"
        />
        <p>
          {{ nodeLabels[selectedNode] ?? selectedNode }} ·
          {{
            !snapshot?.history.path
              ? '执行路径未知'
              : snapshot.history.path.includes(selectedNode)
                ? '已进入此节点'
                : '未经过此节点'
          }}
        </p>
        <el-alert v-if="selected.error" :title="selected.error" type="error" :closable="false" />
        <p v-if="selected.status === 'completed'">
          {{ kept ? `任务预览共 ${kept.length} 项` : '任务预览数量未知' }}
          <span v-if="kept?.length">：{{ kept.map((x) => x.text).join('、') }}</span>
        </p>
        <details v-if="snapshot" :key="selected.runId" class="legacy-definition">
          <summary>查看旧版只读图定义</summary>
          <p>按本次运行保存的配置和路径还原。旧版记录未保存的字段在快照中标为未知。</p>
          <p v-if="snapshot.unknown.length">未知字段：{{ snapshot.unknown.join('、') }}</p>
          <textarea ref="snapshotText" readonly aria-label="旧版只读图定义" :value="snapshotJson" />
          <el-button @click="copySnapshot">复制旧版图快照</el-button>
          <span role="status">{{ copyNote }}</span>
        </details>
      </div>
    </div>
  </section>
</template>
<style scoped>
.history {
  padding: 20px;
}
.legacy-definition {
  margin-top: 14px;
}
.legacy-definition summary {
  cursor: pointer;
}
.legacy-definition textarea {
  display: block;
  width: 100%;
  height: 200px;
  margin: 10px 0;
  padding: 8px;
  font: 12px/1.6 monospace;
  border: 1px solid var(--el-border-color);
  border-radius: 5px;
}

.history header,
.detail-header {
  display: flex;
  flex-wrap: wrap;
  gap: 14px;
  align-items: center;
  justify-content: space-between;
  margin-bottom: 16px;
}

.history h3 {
  margin: 0;
  font-size: 18px;
}

.history p,
.run-list small {
  margin: 4px 0;
  font-size: 12px;
  color: var(--el-text-color-secondary);
}

.history-layout {
  display: grid;
  grid-template-columns: minmax(160px, 24%) minmax(0, 1fr);
  gap: 16px;
}

.run-list {
  display: flex;
  flex-direction: column;
  gap: 6px;
  max-height: 540px;
  overflow: auto;
}

.run-list button {
  display: grid;
  gap: 3px;
  padding: 10px;
  color: var(--el-text-color-primary);
  text-align: left;
  cursor: pointer;
  background: var(--el-bg-color);
  border: 1px solid var(--el-border-color);
  border-radius: 8px;
}

.run-list button[aria-pressed='true'] {
  background: var(--el-color-primary-light-9);
  border-color: var(--el-color-primary);
}

.run-list span {
  font-size: 12px;
}

.detail {
  min-width: 0;
}

.detail-header p {
  overflow-wrap: anywhere;
}

@media (max-width: 760px) {
  .history-layout {
    grid-template-columns: 1fr;
  }

  .run-list {
    max-height: 180px;
  }
}
</style>
