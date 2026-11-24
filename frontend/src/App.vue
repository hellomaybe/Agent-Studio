<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref, watch } from 'vue';

import { Clock, DataAnalysis, Operation, Share, VideoPlay } from '@element-plus/icons-vue';
import { ElMessage, ElMessageBox } from 'element-plus';

import {
  nodeLabels,
  ApiError,
  type SampleKey,
  samples,
  statusLabels,
  studioApi,
  type StudioConfig,
  type StudioDraft,
  type StudioPreview,
  type StudioRun,
} from './api';

import RunHistory from './RunHistory.vue';
import SampleEvaluation from './SampleEvaluation.vue';
import StudioCanvas from './StudioCanvas.vue';

defineOptions({ name: 'MeetingAgentStudio' });
const tab = ref<'edit' | 'evaluation' | 'history'>('edit');
const config = ref<StudioConfig>();
const draft = ref<StudioDraft>();
const sample = ref<SampleKey>('weekly');
const selected = ref('extract');
const error = ref('');
const preview = ref<StudioPreview>();
const sampleError = ref('');
const confirmationError = ref('');
const stepOutput = ref('');
const liveTitle = ref('会议纪要');
const mode = ref<'live' | 'sample'>('sample');
const transcript = ref('');
const busy = ref(false);
const conflict = ref(false);
const loading = ref(false);
const testing = ref(false);
const addVisible = ref(false);
const confirmVisible = ref(false);
const liveVisible = ref(false);
const chosen = ref<string[]>([]);
const confirming = ref<StudioRun>();
const recent = ref<StudioRun>();
const liveInputRunId = ref('');
watch([liveTitle, transcript], () => {
  liveInputRunId.value = '';
});
const historySeq = ref(0);
const pendingRequests = new Map<string, string>();
const maxPendingRequests = 32;
let previewSequence = 0;
let testSequence = 0;
const dirty = computed(
  () => !!config.value && JSON.stringify(config.value) !== JSON.stringify(draft.value?.config),
);
const ready = computed(() => !!config.value?.prompt.trim() && !conflict.value);
const validInput = computed(
  () =>
    mode.value === 'sample' ||
    (draft.value?.liveEnabled && !!liveTitle.value.trim() && !!transcript.value.trim()),
);
const sameGraph = computed(
  () =>
    recent.value?.revision === draft.value?.revision &&
    !dirty.value &&
    recent.value?.mode === mode.value &&
    (mode.value === 'live'
      ? recent.value?.runId === liveInputRunId.value
      : recent.value?.sampleKey === sample.value),
);
const tabs = [
  { key: 'edit', label: '流程编排', icon: Share },
  { key: 'history', label: '运行记录', icon: Clock },
  { key: 'evaluation', label: '样例评估', icon: DataAnalysis },
] as const;
const fieldInfo: Record<string, { help: string; input: string; output: string }> = {
  start: {
    input: '会议标题、会议文本',
    output: 'title, transcript',
    help: '固定样例用于调试，真实试运行使用输入的会议文本。',
  },
  sanitize: {
    input: 'start.transcript',
    output: 'text',
    help: '去除首尾空白，将连续空白合并为一个空格。',
  },
  extract: {
    input: '',
    output: 'actionItems[]',
    help: '使用已配置的模型供应商提取待办。固定样例返回预设待办。',
  },
  gate: {
    input: 'extract.actionItems',
    output: 'matched',
    help: '达到数量条件后进入用户确认，其他情况直接结束。',
  },
  confirm: {
    input: 'extract.actionItems',
    output: 'selectedIds[]',
    help: '试运行发起人勾选待保留的事项。等待中的运行可以从记录中继续。',
  },
  create: {
    input: 'confirm.selectedIds',
    output: '任务预览',
    help: '按已确认事项生成任务预览，不写入业务任务清单。',
  },
  end: {
    input: '实际执行路径与确认结果',
    output: '运行摘要',
    help: '保存本次试运行的结果、路径和草稿快照。',
  },
};
const field = computed(() => fieldInfo[selected.value]!);
const explain = (e: unknown) => (e instanceof Error ? e.message : '请求失败，请重试');
function useDraft(value: StudioDraft) {
  draft.value = value;
  config.value = { ...value.config };
  conflict.value = false;
  if (!value.liveEnabled) mode.value = 'sample';
  if (!value.config.sanitize && selected.value === 'sanitize') selected.value = 'extract';
}
async function load() {
  loading.value = true;
  error.value = '';
  try {
    useDraft(await studioApi.draft());
  } catch (error_) {
    error.value = explain(error_);
  } finally {
    loading.value = false;
  }
}
async function reload() {
  if (dirty.value) {
    try {
      await ElMessageBox.confirm('载入最新草稿将覆盖本页未保存的编辑。', '载入草稿', {
        type: 'warning',
      });
    } catch {
      return;
    }
  }
  await load();
}
async function save() {
  if (!draft.value || !config.value || !ready.value || busy.value) return;
  busy.value = true;
  error.value = '';
  try {
    useDraft(await studioApi.save(draft.value.revision, { ...config.value }));
    ElMessage.success('草稿已保存');
  } catch (error_) {
    error.value = explain(error_);
    conflict.value = error_ instanceof ApiError && error_.status === 409;
  } finally {
    busy.value = false;
  }
}
function setSanitize(on: boolean) {
  if (!config.value) return;
  config.value.sanitize = on;
  if (on && config.value.mapping === 'start.transcript') config.value.mapping = 'sanitize.text';
  if (!on && config.value.mapping === 'sanitize.text') config.value.mapping = 'start.transcript';
  selected.value = on ? 'sanitize' : 'extract';
  addVisible.value = false;
}
async function loadSample() {
  const seq = ++previewSequence;
  sampleError.value = '';
  preview.value = undefined;
  stepOutput.value = '';
  if (!draft.value) return;
  try {
    const data = await studioApi.preview(draft.value.config, sample.value);
    if (seq === previewSequence) preview.value = data;
  } catch (error_) {
    if (seq === previewSequence) sampleError.value = explain(error_);
  }
}
watch([sample, draft], loadSample);
watch(
  [selected, sample, config],
  () => {
    testSequence++;
    testing.value = false;
    stepOutput.value = '';
  },
  { deep: true },
);
async function singleStep() {
  if (!config.value || !ready.value) return;
  const seq = ++testSequence;
  testing.value = true;
  stepOutput.value = '';
  error.value = '';
  try {
    const result = await studioApi.preview({ ...config.value }, sample.value);
    if (seq !== testSequence) return;
    const id = selected.value;
    if (result.path.includes(id)) {
      switch (id) {
        case 'confirm': {
          stepOutput.value = '等待确认 ' + result.items.length + ' 项待办';
          break;
        }
        case 'create': {
          stepOutput.value = '按全部勾选预览：' + result.items.map((i) => i.text).join('、');
          break;
        }
        case 'extract': {
          stepOutput.value = JSON.stringify(result.items, null, 2);
          break;
        }
        case 'gate': {
          stepOutput.value =
            result.items.length +
            ' 项 → ' +
            (result.path.includes('confirm') ? '进入用户确认' : '直接结束');
          break;
        }
        case 'sanitize': {
          stepOutput.value = result.cleanedText;
          break;
        }
        case 'start': {
          stepOutput.value = result.transcript;
          break;
        }
        default: {
          stepOutput.value = result.path.map((node) => nodeLabels[node]).join(' → ');
        }
      }
    } else {
      stepOutput.value = '此样例未经过该节点。';
    }
  } catch (error_) {
    if (seq === testSequence) error.value = explain(error_);
  } finally {
    if (seq === testSequence) testing.value = false;
  }
}
function resume(run: StudioRun) {
  confirmationError.value = '';
  confirming.value = run;
  chosen.value = run.items.map((i) => i.id);
  confirmVisible.value = true;
}
function showRun(run: StudioRun) {
  recent.value = run;
  historySeq.value++;
  if (run.status === 'waiting') resume(run);
  else if (run.status === 'failed') error.value = run.error || '试运行失败，请查看运行记录';
}
async function run() {
  if (busy.value || !draft.value || !ready.value || dirty.value || !validInput.value) return;
  liveVisible.value = false;
  busy.value = true;
  error.value = '';
  const base =
    mode.value === 'sample'
      ? {
          mode: 'sample' as const,
          revision: draft.value.revision,
          sampleKey: sample.value,
        }
      : {
          mode: 'live' as const,
          revision: draft.value.revision,
          title: liveTitle.value.trim(),
          transcript: transcript.value.trim(),
        };
  const signature = JSON.stringify(base);
  try {
    let requestId = pendingRequests.get(signature);
    if (!requestId) {
      requestId = Array.from(crypto.getRandomValues(new Uint8Array(16)), (n) =>
        n.toString(16).padStart(2, '0'),
      ).join('');
      if (pendingRequests.size >= maxPendingRequests) {
        const oldest = pendingRequests.keys().next();
        if (!oldest.done) pendingRequests.delete(oldest.value);
      }
      pendingRequests.set(signature, requestId);
    }
    const result = await studioApi.start({ ...base, requestId });
    if (base.mode === 'live') liveInputRunId.value = result.runId;
    pendingRequests.delete(signature);
    showRun(result);
  } catch (error_) {
    error.value = explain(error_);
    if (error_ instanceof ApiError && [400, 403, 422, 429].includes(error_.status)) {
      pendingRequests.delete(signature);
    }
    if (error_ instanceof ApiError && error_.status === 409) {
      try {
        conflict.value = (await studioApi.draft()).revision !== draft.value.revision;
      } catch {
        // Preserve the original error and the request ID for a retry.
      }
    } else if (!(error_ instanceof ApiError)) {
      error.value += '。可重试同一请求，或到运行记录核对结果。';
    }
  } finally {
    busy.value = false;
  }
}
function requestRun() {
  if (mode.value === 'live') liveVisible.value = true;
  else void run();
}
async function confirmItems() {
  if (!confirming.value || busy.value) return;
  const runId = confirming.value.runId;
  busy.value = true;
  error.value = '';
  confirmationError.value = '';
  try {
    const result = await studioApi.confirm(runId, [...chosen.value]);
    confirmVisible.value = false;
    showRun(result);
    ElMessage.success('确认结果已保存');
  } catch (error_) {
    confirmationError.value = explain(error_);
    if (error_ instanceof ApiError && error_.status === 409) {
      try {
        const latest = await studioApi.run(runId);
        if (latest.status !== 'waiting') {
          confirmVisible.value = false;
          showRun(latest);
          tab.value = 'history';
          ElMessage.info('该运行已在其他窗口更新，已载入最新结果。');
        }
      } catch {
        // Keep the original conflict and the user's selection until they retry.
      }
    }
  } finally {
    busy.value = false;
  }
}
function guardUnsaved(event: BeforeUnloadEvent) {
  if (dirty.value) event.preventDefault();
}
onMounted(() => {
  window.addEventListener('beforeunload', guardUnsaved);
  void load();
});
onBeforeUnmount(() => window.removeEventListener('beforeunload', guardUnsaved));
</script>
<template>
  <main v-loading="loading" class="studio" aria-label="会议纪要 Agent 编排">
    <header class="studio-head">
      <div>
        <div class="title-line">
          <h2>会议纪要助手</h2>
          <el-tag v-if="draft" size="small" effect="plain">r{{ draft.revision }} 草稿</el-tag>
        </div>
        <p>从会议文本提取待办，由发起人确认后生成任务预览。</p>
      </div>
      <div class="actions">
        <el-button :disabled="!dirty || !ready || busy" @click="save">保存草稿</el-button>
        <el-button
          type="primary"
          :disabled="!draft || dirty || !ready || busy || !validInput"
          @click="requestRun"
        >
          <VideoPlay class="studio-icon" aria-hidden="true" />
          试运行
        </el-button>
      </div>
    </header>
    <el-alert
      v-if="error || conflict"
      type="error"
      :title="error || '草稿版本已变化，请载入最新草稿。'"
      :closable="false"
      show-icon
    >
      <el-button v-if="!draft || conflict" link @click="reload">载入最新草稿</el-button>
    </el-alert>
    <template v-if="draft && config">
      <nav class="studio-nav" aria-label="编排工作区">
        <div class="tabs">
          <button
            v-for="t in tabs"
            :key="t.key"
            type="button"
            :aria-pressed="tab === t.key"
            @click="tab = t.key"
          >
            <component :is="t.icon" class="studio-icon" aria-hidden="true" />
            {{ t.label }}
          </button>
        </div>
        <el-button
          v-if="tab === 'edit'"
          size="small"
          :disabled="config.sanitize || busy"
          @click="addVisible = true"
        >
          ＋ 添加节点
        </el-button>
      </nav>
      <template v-if="tab === 'edit'">
        <div class="editor">
          <StudioCanvas
            :selected="selected"
            :sanitize="config.sanitize"
            :threshold="config.threshold"
            :visited="sameGraph ? (recent?.path ?? []) : []"
            :run-status="sameGraph ? recent?.status : undefined"
            @select="selected = $event"
          />
          <aside class="inspector">
            <div class="panel-heading">
              <Operation class="studio-icon" aria-hidden="true" />
              节点配置
            </div>
            <h3>{{ nodeLabels[selected] }}</h3>
            <p>{{ field.help }}</p>
            <fieldset :disabled="busy">
              <template v-if="selected === 'extract'">
                <label>
                  输入映射
                  <select v-model="config.mapping">
                    <option value="start.transcript">start.transcript · 会议文本</option>
                    <option value="start.title">start.title · 会议标题</option>
                    <option v-if="config.sanitize" value="sanitize.text">
                      sanitize.text · 清洗结果
                    </option>
                  </select>
                </label>
                <label>
                  提示词
                  <textarea v-model="config.prompt" rows="4" maxlength="2000"></textarea>
                </label>
              </template>
              <template v-else>
                <label>
                  输入字段
                  <code>{{ field.input }}</code>
                </label>
                <label v-if="selected === 'gate'">
                  进入确认的最少待办数
                  <select v-model.number="config.threshold">
                    <option v-for="n in [1, 2, 3]" :key="n" :value="n">{{ n }} 项</option>
                  </select>
                </label>
                <el-button v-if="selected === 'sanitize'" size="small" @click="setSanitize(false)">
                  移除此节点
                </el-button>
              </template>
              <label>
                输出字段
                <code>{{ field.output }}</code>
              </label>
            </fieldset>
            <el-button
              class="node-test"
              :loading="testing"
              :disabled="!ready || busy"
              @click="singleStep"
            >
              单步试跑 · 固定样例
            </el-button>
            <pre v-if="stepOutput" role="status">{{ stepOutput }}</pre>
          </aside>
        </div>
        <section class="inputs">
          <div class="input-row">
            <strong>试运行输入</strong>
            <div class="actions">
              <select
                v-if="draft.liveEnabled"
                v-model="mode"
                :disabled="busy"
                aria-label="运行模式"
              >
                <option value="sample">固定样例</option>
                <option value="live">真实模型</option>
              </select>
              <select
                v-if="mode === 'sample'"
                v-model="sample"
                :disabled="busy"
                aria-label="固定输入样例"
              >
                <option v-for="s in samples" :key="s.key" :value="s.key">
                  {{ s.label }} · {{ s.count }} 项待办
                </option>
              </select>
            </div>
          </div>
          <el-alert
            v-if="mode === 'sample' && sampleError"
            :title="sampleError"
            type="error"
            :closable="false"
          >
            <el-button link @click="loadSample">重新载入样例</el-button>
          </el-alert>
          <p v-else-if="mode === 'sample'" class="source">
            <code>transcript</code>
            {{ preview?.transcript ?? '正在载入输入样例…' }}
          </p>
          <template v-else>
            <label>
              会议标题
              <input v-model="liveTitle" maxlength="100" required :disabled="busy" />
            </label>
            <label>
              会议文本
              <textarea
                v-model="transcript"
                required
                :disabled="busy"
                rows="4"
                maxlength="12000"
              ></textarea>
            </label>
          </template>
          <p v-if="dirty" class="notice">更改可用固定样例单步验证，保存草稿后可试运行完整流程。</p>
          <div v-if="recent" class="run-state" role="status">
            <el-tag size="small">{{ statusLabels[recent.status] }}</el-tag>
            <span>{{ recent.title }} · r{{ recent.revision }}</span>
            <el-button
              v-if="recent.status === 'waiting'"
              link
              type="primary"
              @click="resume(recent)"
            >
              继续确认
            </el-button>
            <el-button link @click="tab = 'history'">查看记录</el-button>
          </div>
        </section>
      </template>
      <RunHistory
        v-else-if="tab === 'history'"
        :sequence="historySeq"
        :focus="recent?.runId ?? ''"
        @resume="resume"
      />
      <SampleEvaluation
        v-else
        :baseline="draft.config"
        :candidate="config"
        :revision="draft.revision"
      />
    </template>
    <el-dialog v-model="addVisible" title="添加业务节点" width="min(420px,94vw)">
      <strong>文本清洗</strong>
      <p>插入“会议文本”和“提取待办”之间，统一换行与连续空白。</p>
      <template #footer>
        <el-button @click="addVisible = false">取消</el-button>
        <el-button type="primary" @click="setSanitize(true)">插入节点</el-button>
      </template>
    </el-dialog>
    <el-dialog v-model="confirmVisible" title="确认待办事项" width="min(480px,94vw)">
      <el-alert
        v-if="confirmationError"
        :title="confirmationError"
        type="error"
        :closable="false"
      />
      <p>勾选要纳入本次任务预览的事项。关闭后可从运行记录继续。</p>
      <el-checkbox-group v-model="chosen" class="checklist">
        <el-checkbox v-for="item in confirming?.items" :key="item.id" :value="item.id">
          {{ item.text }}
        </el-checkbox>
      </el-checkbox-group>
      <template #footer>
        <el-button @click="confirmVisible = false">稍后处理</el-button>
        <el-button type="primary" :loading="busy" @click="confirmItems">
          确认 {{ chosen.length }} 项
        </el-button>
      </template>
    </el-dialog>
    <el-dialog v-model="liveVisible" title="使用真实模型试运行" width="min(440px,94vw)">
      <p>本次会将映射的会议内容发送给已配置的模型供应商，可能产生模型费用。</p>
      <template #footer>
        <el-button @click="liveVisible = false">取消</el-button>
        <el-button type="primary" :disabled="!liveTitle.trim() || !transcript.trim()" @click="run">
          调用模型
        </el-button>
      </template>
    </el-dialog>
  </main>
</template>
<style scoped>
.studio-icon {
  flex: none;
  width: 16px;
  height: 16px;
}

.studio {
  --studio-line: var(--el-border-color-light);

  padding: 0;
  margin: 16px;
  overflow: hidden;
  color: var(--el-text-color-primary);
  background: var(--el-bg-color);
  border: 1px solid var(--studio-line);
  border-radius: 12px;
}

.studio-head,
.studio-nav,
.title-line,
.actions,
.tabs,
.panel-heading,
.input-row,
.run-state {
  display: flex;
  gap: 9px;
  align-items: center;
}

.studio-head,
.studio-nav,
.input-row {
  justify-content: space-between;
}

.studio-head,
.input-row {
  flex-wrap: wrap;
}

.studio-head {
  padding: 19px 22px;
}

.studio h2,
.studio h3,
.studio p {
  margin: 0;
}

.studio h2 {
  font-size: 23px;
}

.studio-head p,
.inspector p,
.inputs .notice {
  margin-top: 5px;
  font-size: 13px;
  color: var(--el-text-color-secondary);
}

.actions {
  flex-wrap: wrap;
}

.actions .el-button + .el-button {
  margin: 0;
}

.studio-nav {
  flex-wrap: wrap;
  padding: 0 18px;
  border-block: 1px solid var(--studio-line);
}

.tabs button {
  display: inline-flex;
  gap: 5px;
  align-items: center;
  padding: 12px 5px;
  color: var(--el-text-color-regular);
  cursor: pointer;
  background: none;
  border: 0;
  border-bottom: 2px solid transparent;
}

.tabs button[aria-pressed='true'] {
  color: var(--el-color-primary);
  border-color: var(--el-color-primary);
}

.editor {
  display: grid;
  grid-template-columns: minmax(0, 1fr) clamp(250px, 28%, 330px);
}

.inspector {
  min-width: 0;
  padding: 16px;
  border-left: 1px solid var(--studio-line);
}

.panel-heading {
  padding-bottom: 12px;
  font-size: 13px;
  color: var(--el-text-color-secondary);
  border-bottom: 1px solid var(--studio-line);
}

.inspector h3 {
  margin-top: 16px;
}

.inspector fieldset {
  padding: 0;
  margin: 14px 0;
  border: 0;
}

.studio label {
  display: grid;
  gap: 5px;
  margin: 10px 0;
  font-size: 13px;
  color: var(--el-text-color-regular);
}

.studio select,
.studio textarea,
.studio input:not([type='checkbox']) {
  width: 100%;
  padding: 7px 8px;
  font: inherit;
  color: var(--el-text-color-primary);
  background: var(--el-bg-color);
  border: 1px solid var(--el-border-color);
  border-radius: 6px;
}

.studio textarea {
  resize: vertical;
}

.studio code,
.inspector pre,
.source {
  display: block;
  padding: 7px 9px;
  overflow-wrap: anywhere;
  background: var(--el-fill-color-light);
  border-radius: 6px;
}

.inspector pre {
  max-height: 200px;
  overflow: auto;
  font-size: 12px;
  white-space: pre-wrap;
}

.node-test {
  width: 100%;
}

.inputs {
  padding: 14px 18px;
  border-top: 1px solid var(--studio-line);
}

.inputs select {
  width: auto;
}

.inputs .source {
  margin-top: 10px;
  font-size: 13px;
}

.source code {
  display: inline;
  padding: 0 6px 0 0;
  color: var(--el-text-color-secondary);
}

.run-state {
  flex-wrap: wrap;
  margin-top: 10px;
}

.checklist {
  display: grid;
  gap: 6px;
}

@media (max-width: 850px) {
  .editor {
    grid-template-columns: 1fr;
  }

  .inspector {
    border-top: 1px solid var(--studio-line);
    border-left: 0;
  }

  .studio {
    margin: 6px;
  }

  .studio-head {
    padding: 14px;
  }

  .studio-nav {
    padding: 0 8px;
  }
}
</style>
