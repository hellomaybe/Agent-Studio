<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref, watch } from 'vue';
import { errorText } from '../http';
import { newId, tokenCount, workbench, type Fixture, type RunView, type Published } from './client';
import type { Capability } from './types';
import GraphEditor from './GraphEditor.vue';

const props = defineProps<{
  appId: string;
  published: Published;
  fixtures: Fixture[];
  runId: string;
  liveEnabled: boolean;
  capabilities: Capability[];
}>();
const emit = defineEmits<{
  open: [id: string];
  inspect: [snapshot: import('./client').HistoricalInput];
}>();
const list = ref<import('./client').RunSummary[]>([]),
  current = ref<RunView>(),
  sample = ref(''),
  mode = ref<'sample' | 'live'>('sample');
const text = ref(''),
  consent = ref(false),
  pauseAt = ref(''),
  selectedIds = ref<string[]>([]),
  submitting = ref(false),
  readingInput = ref(false),
  problem = ref('');
const busy = computed(() => submitting.value || readingInput.value);
let viewEpoch = 0;
let nodeEpoch = 0;
const labels: Record<string, string> = {
  queued: '已排队',
  running: '执行中',
  waiting: '等待输入',
  paused: '已暂停',
  completed: '已完成',
  failed: '失败',
  unknown: '结果待核对',
  expired: '资料已过期',
  terminated: '已终止',
};
const pendingIds = new Map<string, string>();
const inspectNode = ref('');
watch(
  inspectNode,
  () => {
    nodeEpoch++;
    readingInput.value = false;
  },
  { flush: 'sync' },
);
const older = ref<import('./client').RunEvent[]>([]),
  cursor = ref(0),
  reading = ref(false);
const allEvents = computed(() =>
  [
    ...new Map([...older.value, ...(current.value?.events ?? [])].map((e) => [e.seq, e])).values(),
  ].sort((a, b) => a.seq - b.seq),
);
async function readEarlier() {
  const run = current.value;
  if (!run || reading.value) return;
  const view = viewEpoch;
  reading.value = true;
  try {
    const result = await workbench.events(props.appId, run.runId, cursor.value);
    if (view === viewEpoch) {
      older.value.push(...result.events);
      cursor.value = result.nextCursor;
    }
  } catch (e) {
    if (view === viewEpoch) problem.value = errorText(e);
  } finally {
    if (view === viewEpoch) reading.value = false;
  }
}
async function readInputs() {
  const run = current.value,
    id = inspectNode.value;
  if (!run || !id || busy.value) return;
  const view = viewEpoch,
    node = nodeEpoch;
  readingInput.value = true;
  try {
    const input = await workbench.debugInput(props.appId, run.runId, id);
    if (view === viewEpoch && node === nodeEpoch) emit('inspect', { ...input, graph: run.graph });
  } catch (e) {
    if (view === viewEpoch && node === nodeEpoch) problem.value = errorText(e);
  } finally {
    if (view === viewEpoch && node === nodeEpoch) readingInput.value = false;
  }
}
const receipt = ref(''),
  replacement = ref(''),
  reinputConsent = ref(false);
const displayEvents = computed(() =>
  allEvents.value.filter((e) => !inspectNode.value || e.nodeId === inspectNode.value),
);
const label = (s?: string) => (s ? (labels[s] ?? s) : '');
const eventNames: Record<string, string> = {
  'run.accepted': '接受请求',
  'node.started': '开始节点',
  'node.completed': '节点完成',
  'node.waiting': '等待人工',
  'run.paused': '已保存暂停点',
  'run.completed': '运行完成',
  'node.unknown': '外部结果待核对',
  'node.failed': '节点失败',
  'signal.resume': '收到确认',
  'signal.continue': '继续执行',
  'signal.pause': '请求暂停',
  'signal.retry': '请求安全重试',
  'run.reinput': '创建关联运行',
};
const transitions = computed(() =>
  allEvents.value.flatMap((e) =>
    e.nodeId && e.port && e.nextNodeId ? [{ from: e.nodeId, port: e.port, to: e.nextNodeId }] : [],
  ),
);
const eligible = computed(
  () =>
    !busy.value &&
    (mode.value === 'sample'
      ? !!sample.value
      : props.liveEnabled && consent.value && !!text.value.trim()),
);
let generation = 0,
  timer: ReturnType<typeof setTimeout> | undefined;
function stopPoll() {
  clearTimeout(timer);
}
function apply(run: RunView) {
  const previous = current.value;
  if (previous?.runId !== run.runId) {
    older.value = [];
    cursor.value = 0;
    inspectNode.value = '';
    receipt.value = '';
    replacement.value = '';
    reinputConsent.value = false;
  }
  if (previous?.runId !== run.runId || previous.wait?.waitId !== run.wait?.waitId)
    selectedIds.value = run.wait?.items?.map((i) => i.id) ?? [];
  current.value = run;
  const idx = list.value.findIndex((i) => i.runId === run.runId);
  if (idx < 0) list.value.unshift(run);
  else list.value[idx] = run;
}
async function refreshRun(id = props.runId) {
  if (!id) return;
  stopPoll();
  const ticket = ++generation;
  try {
    const run = await workbench.run(props.appId, id);
    if (ticket !== generation) return;
    apply(run);
    problem.value = '';
    if (['running', 'queued'].includes(run.status))
      timer = setTimeout(() => void refreshRun(id), 700);
  } catch (e) {
    if (ticket === generation) problem.value = errorText(e);
  }
}
async function loadHistory() {
  const ticket = generation;
  try {
    const rows = await workbench.runs(props.appId);
    if (ticket === generation) list.value = rows;
  } catch (e) {
    if (ticket === generation) problem.value = errorText(e);
  }
}
watch(
  () => props.runId,
  (id) => {
    ++viewEpoch;
    ++generation;
    stopPoll();
    submitting.value = false;
    readingInput.value = false;
    reading.value = false;
    problem.value = '';
    current.value = undefined;
    if (id) void refreshRun(id);
    else void loadHistory();
  },
  { immediate: true, flush: 'sync' },
);
watch(
  () => props.fixtures,
  () => {
    sample.value = props.fixtures[0]?.id ?? '';
  },
  { immediate: true },
);
onMounted(() => {
  if (props.runId) void loadHistory();
});
onBeforeUnmount(() => {
  ++viewEpoch;
  ++generation;
  stopPoll();
});
async function start() {
  if (!eligible.value) return;
  const view = viewEpoch;
  submitting.value = true;
  problem.value = '';
  try {
    const payload = {
      mode: mode.value,
      versionId: props.published.version,
      input: {
        text:
          mode.value === 'sample'
            ? props.fixtures.find((s) => s.id === sample.value)!.text
            : text.value,
      },
      persistInput: mode.value === 'live' && consent.value,
      pauseAfterNode: pauseAt.value || undefined,
    };
    const signature = JSON.stringify(payload);
    const id = pendingIds.get(signature) ?? newId();
    if (pendingIds.size >= 16 && !pendingIds.has(signature))
      pendingIds.delete(pendingIds.keys().next().value!);
    pendingIds.set(signature, id);
    const run = await workbench.start(props.appId, { ...payload, requestId: id });
    pendingIds.delete(signature);
    if (view !== viewEpoch) return;
    apply(run);
    emit('open', run.runId);
  } catch (e) {
    if (view === viewEpoch) problem.value = errorText(e);
  } finally {
    if (view === viewEpoch) submitting.value = false;
  }
}
async function signal(
  action: 'pause' | 'continue' | 'resume' | 'retry' | 'reconcile' | 'reinput',
  decision?: 'verified' | 'terminate',
) {
  const run = current.value;
  if (!run || busy.value) return;
  const view = viewEpoch;
  let data: Record<string, unknown> = {};
  if (action === 'resume')
    data = { waitId: run.wait!.waitId, values: { selectedIds: [...selectedIds.value] } };
  if (action === 'reconcile') data = { decision, receiptRef: receipt.value.trim() || undefined };
  if (action === 'reinput')
    data = { input: { text: replacement.value.trim() }, persistInput: reinputConsent.value };
  submitting.value = true;
  stopPoll();
  ++generation;
  const signature = JSON.stringify([action, run.runId, data]);
  const signalId = pendingIds.get(signature) ?? newId();
  pendingIds.set(signature, signalId);
  try {
    const key = action === 'reinput' ? { requestId: signalId } : { signalId };
    const next = await workbench.signal(props.appId, run.runId, action, { ...key, ...data });
    pendingIds.delete(signature);
    if (view !== viewEpoch) return;
    apply(next);
    if (next.runId !== run.runId) emit('open', next.runId);
    else await refreshRun(run.runId);
  } catch (e) {
    if (view !== viewEpoch) return;
    await refreshRun(run.runId);
    if (view === viewEpoch) problem.value = errorText(e);
  } finally {
    if (view === viewEpoch) submitting.value = false;
  }
}
</script>
<template>
  <section class="wb-section">
    <header class="wb-section-head">
      <div>
        <h2>运行与恢复</h2>
        <p>启动时锁定发布版本。重新打开运行记录可继续原任务。</p>
      </div>
      <button @click="loadHistory">刷新运行列表</button>
    </header>
    <div class="wb-run-form">
      <label>
        启动模式
        <select v-model="mode">
          <option value="sample">固定样例</option>
          <option v-if="liveEnabled" value="live">真实模型</option>
        </select>
      </label>
      <label v-if="mode === 'sample'">
        运行样例
        <select v-model="sample">
          <option v-for="s in fixtures" :key="s.id" :value="s.id">{{ s.label }}</option>
        </select>
      </label>
      <label v-else>
        业务文本
        <textarea v-model="text" rows="3" maxlength="32000" />
      </label>
      <label>
        节点完成后暂停
        <select v-model="pauseAt">
          <option value="">按流程运行</option>
          <option
            v-for="n in published.graph.nodes.filter((n) => n.type !== 'end')"
            :key="n.id"
            :value="n.id"
          >
            {{ n.id }}
          </option>
        </select>
      </label>
      <label v-if="mode === 'live'" class="wb-check">
        <input type="checkbox" v-model="consent" />
        同意调用供应商并在本机临时保存执行所需资料（24 小时）
      </label>
      <button class="wb-primary" :disabled="!eligible" @click="start">
        运行发布版 v{{ published.version }}
      </button>
    </div>
    <p v-if="problem" role="alert" class="wb-error">{{ problem }}</p>
    <div class="wb-run-layout">
      <nav class="wb-history" aria-label="工作流运行列表">
        <p v-if="!list.length">还没有运行记录。</p>
        <button
          v-for="run in list"
          :key="run.runId"
          :aria-pressed="run.runId === current?.runId"
          @click="emit('open', run.runId)"
        >
          <strong>{{ label(run.status) }} · v{{ run.versionId }}</strong>
          <small>{{ run.runId }}</small>
        </button>
      </nav>
      <article v-if="current" class="wb-run-details">
        <header class="wb-section-head">
          <div>
            <h3>{{ label(current.status) }} · v{{ current.versionId }}</h3>
            <small>{{ current.runId }}</small>
          </div>
          <div class="wb-actions">
            <button
              v-if="['queued', 'running'].includes(current.status)"
              :disabled="busy || current.pauseRequested"
              @click="signal('pause')"
            >
              {{ current.pauseRequested ? '已请求暂停' : '在下一边界暂停' }}
            </button>
            <button v-if="current.status === 'paused'" :disabled="busy" @click="signal('continue')">
              继续执行
            </button>
            <button @click="refreshRun()">刷新状态</button>
          </div>
        </header>
        <div v-if="current.wait && current.status === 'waiting'" class="wb-wait">
          <h3>{{ current.wait.prompt }}</h3>
          <label v-for="item in current.wait.items" :key="item.id" class="wb-check">
            <input type="checkbox" v-model="selectedIds" :value="item.id" />
            {{ item.title ?? item.text ?? item.id }}
          </label>
          <button class="wb-primary" :disabled="busy" @click="signal('resume')">
            确认并恢复（{{ selectedIds.length }}）
          </button>
        </div>
        <p v-if="current.errorCode" class="wb-error">{{ current.errorCode }}</p>
        <p v-if="current.parentRunId">
          来源运行
          <button @click="emit('open', current.parentRunId)">{{ current.parentRunId }}</button>
        </p>
        <button v-if="current.canRetry" :disabled="busy" @click="signal('retry')">
          重试可安全重放的节点
        </button>
        <div v-if="current.status === 'unknown'" class="wb-inline-warning">
          <p>外部调用结果不确定。核对供应商回执后可记录判断，系统不会重新投递。</p>
          <label>
            回执参考号
            <input v-model="receipt" maxlength="160" />
          </label>
          <button :disabled="busy || !receipt.trim()" @click="signal('reconcile', 'verified')">
            记录核对回执
          </button>
          <button :disabled="busy" @click="signal('reconcile', 'terminate')">终止这次运行</button>
          <p v-if="current.reviewed">已记录人工核对</p>
        </div>
        <details v-if="current.canReinput">
          <summary>重新提供资料</summary>
          <label>
            新运行的输入
            <textarea v-model="replacement" rows="3" maxlength="32000" />
          </label>
          <label class="wb-check">
            <input type="checkbox" v-model="reinputConsent" />
            同意新运行调用模型并在本机保留资料 24 小时
          </label>
          <button
            :disabled="busy || !reinputConsent || !replacement.trim()"
            @click="signal('reinput')"
          >
            按原版本创建关联运行
          </button>
        </details>
        <details v-if="current.output && Object.keys(current.output).length" open>
          <summary>最终输出</summary>
          <pre>{{ JSON.stringify(current.output, null, 2) }}</pre>
        </details>
        <details v-if="current.effects?.length">
          <summary>本机操作记录 · {{ current.effects.length }} 项</summary>
          <pre>{{ JSON.stringify(current.effects, null, 2) }}</pre>
        </details>
        <details>
          <summary>查看本次运行的图 v{{ current.versionId }}</summary>
          <GraphEditor
            :model-value="current.graph"
            :capabilities="capabilities"
            :selected="inspectNode"
            readonly
            :visited="current.path"
            :active-node="current.currentNodeId"
            :trace-status="current.status"
            :transitions="transitions"
            @select="inspectNode = $event"
          />
        </details>
        <div class="wb-section-head">
          <h3>节点时间线</h3>
          <label>
            查看节点
            <select v-model="inspectNode">
              <option value="">全部事件</option>
              <option v-for="n in current.graph.nodes" :key="n.id" :value="n.id">{{ n.id }}</option>
            </select>
          </label>
        </div>
        <div class="wb-actions">
          <button
            v-if="inspectNode"
            :disabled="busy || !current.path.includes(inspectNode) || current.status === 'expired'"
            @click="readInputs"
          >
            读取该次输入并固定到调试台
          </button>
          <button
            v-if="current.eventsTruncated && allEvents.length < (current.eventCount ?? 0)"
            :disabled="reading"
            @click="readEarlier"
          >
            载入较早事件
          </button>
        </div>
        <ol class="wb-events">
          <li v-for="event in displayEvents" :key="event.seq">
            <div>
              <strong>
                {{ event.nodeId || '运行' }} · {{ eventNames[event.type] ?? event.type }}
              </strong>
              <small>{{ event.occurredAt }}</small>
            </div>
            <span>
              {{ label(event.status) }}
              <template v-if="event.attemptNo">· 第 {{ event.attemptNo }} 次</template>
              <template v-if="event.elapsedMs != null">· 执行 {{ event.elapsedMs }} ms</template>
              <template v-if="event.waitMs != null">
                · 等待 {{ (event.waitMs / 1000).toFixed(1) }} 秒
              </template>
              <template v-if="event.nextNodeId">
                · {{ event.port }} → {{ event.nextNodeId }}
              </template>
            </span>
            <small v-if="event.errorCode">{{ event.errorCode }}</small>
            <small v-if="event.usage">
              {{ event.usage.model }} · 输入 {{ tokenCount(event.usage.inputTokens) }} / 输出
              {{ tokenCount(event.usage.outputTokens) }} token
            </small>
            <small v-if="event.inputFields?.length">
              输入字段 {{ event.inputFields.join('、') }}
            </small>
            <small v-if="event.outputFields?.length">
              输出字段 {{ event.outputFields.join('、') }}
            </small>
          </li>
        </ol>
      </article>
      <p v-else>选择一条运行记录以查看检查点与经过的节点。</p>
    </div>
  </section>
</template>
