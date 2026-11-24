<script setup lang="ts">
import { computed, ref, watch } from 'vue';
import { ApiError, errorText } from '../http';
import {
  newId,
  tokenCount,
  workbench,
  type DebugResult,
  type Fields,
  type Published,
} from './client';
import type { Capability, GraphDefinition } from './types';

const props = defineProps<{
  appId: string;
  graph: GraphDefinition;
  revision: number;
  published: Published;
  nodes: Capability[];
  selected: string;
  liveEnabled: boolean;
  historyInput?: import('./client').HistoricalInput;
}>();
const emit = defineEmits<{ select: [id: string] }>();
const draftNode = computed(() => props.graph.nodes.find((n) => n.id === props.selected));
const source = computed(() =>
  props.historyInput?.nodeId === props.selected ? props.historyInput : undefined,
);
const baselineVersion = computed(() => source.value?.versionId ?? props.published.version);
const baselineGraph = computed(() => source.value?.graph ?? props.published.graph);
const node = computed(
  () => draftNode.value ?? baselineGraph.value.nodes.find((n) => n.id === props.selected),
);
const capability = computed(() => props.nodes.find((c) => c.type === node.value?.type));
const inputs = ref('{}'),
  pinned = ref(false),
  mode = ref<'sample' | 'live'>('sample');
const samples = new Map<string, string>();
const outputs = new Map<string, Fields>();
const results = ref<Partial<Record<'candidate' | 'baseline', DebugResult>>>({});
const activePrompt = ref<null | 'candidate' | 'baseline'>(null);
const problem = ref(''),
  busy = ref(false);
let epoch = 0;
const json = (value: unknown) => JSON.stringify(value, null, 2);
function resetComparison() {
  activePrompt.value = null;
  epoch++;
  results.value = {};
  problem.value = '';
  busy.value = false;
}
watch(
  () => props.selected,
  () => {
    const saved = samples.get(props.selected);
    inputs.value = saved ?? json(capability.value?.exampleInputs ?? {});
    pinned.value = saved !== undefined;
    mode.value = 'sample';
    resetComparison();
  },
  { immediate: true },
);
watch(
  () => [props.graph, props.published.version, props.revision],
  () => {
    outputs.clear();
    resetComparison();
  },
  { deep: true },
);
watch(mode, resetComparison);
watch(
  () => props.historyInput,
  (h) => {
    if (!h) return;
    samples.set(h.nodeId, json(h.inputs));
    inputs.value = json(h.inputs);
    pinned.value = true;
    mode.value = 'sample';
    resetComparison();
  },
);
function readInputs(): Fields {
  const value: unknown = JSON.parse(inputs.value);
  if (!value || typeof value !== 'object' || Array.isArray(value))
    throw new Error('节点输入必须是 JSON 对象。');
  return value as Fields;
}
function pin() {
  try {
    const value = json(readInputs());
    samples.set(props.selected, value);
    inputs.value = value;
    pinned.value = true;
    problem.value = '';
  } catch (e) {
    problem.value = errorText(e);
  }
}
function unpin() {
  samples.delete(props.selected);
  pinned.value = false;
  resetComparison();
}
function exportFixture() {
  try {
    if (!pinned.value || !capability.value) return;
    const fixture = {
      format: 'studio-node-fixture/v1',
      type: capability.value.type,
      capabilityVersion: capability.value.version,
      inputs: readInputs(),
    };
    const url = URL.createObjectURL(
      new Blob([json(fixture)], { type: 'application/json;charset=utf-8' }),
    );
    const a = document.createElement('a');
    a.href = url;
    a.download = props.appId + '-' + props.selected + '-fixture.json';
    a.click();
    window.setTimeout(() => URL.revokeObjectURL(url), 1000);
  } catch (e) {
    problem.value = errorText(e);
  }
}
async function importFixture(event: Event) {
  const control = event.target as HTMLInputElement,
    file = control.files?.[0],
    id = props.selected,
    version = capability.value?.version,
    type = capability.value?.type;
  control.value = '';
  if (!file) return;
  try {
    if (file.size > 262144) throw new Error('样例文件不能超过 256 KiB。');
    const fixture = JSON.parse(await file.text());
    if (props.selected !== id) return;
    if (
      fixture?.format !== 'studio-node-fixture/v1' ||
      !type ||
      fixture.type !== type ||
      fixture.capabilityVersion !== version ||
      !fixture.inputs ||
      Array.isArray(fixture.inputs) ||
      typeof fixture.inputs !== 'object'
    )
      throw new Error('样例格式或节点能力版本不匹配。');
    inputs.value = json(fixture.inputs);
    mode.value = 'sample';
    resetComparison();
    pin();
  } catch (e) {
    problem.value = errorText(e);
  }
}
function useUpstream() {
  if (!node.value) return;
  try {
    const mapped: Fields = {};
    for (const [field, ref] of Object.entries(node.value.bindings)) {
      const dot = ref.indexOf('.'),
        source = outputs.get(ref.slice(0, dot)),
        key = ref.slice(dot + 1);
      if (!source || !Object.hasOwn(source, key)) throw new Error('请先调试上游节点：' + ref);
      mapped[field] = source[key];
    }
    inputs.value = json(mapped);
    resetComparison();
  } catch (e) {
    problem.value = errorText(e);
  }
}
const pendingIds = new Map<string, string>();
async function execute(side: 'baseline' | 'candidate') {
  activePrompt.value = null;
  if (!node.value || busy.value) return;
  delete results.value[side];
  const ticket = ++epoch;
  busy.value = true;
  problem.value = '';
  try {
    const input = readInputs(),
      id = props.selected;
    const target =
      side === 'baseline' ? { version: baselineVersion.value } : { graph: props.graph };
    const payload = {
      revision: props.revision,
      nodeId: id,
      mode: mode.value,
      inputs: input,
      ...target,
    };
    const signature = JSON.stringify(payload);
    const requestId = pendingIds.get(signature) ?? newId();
    if (pendingIds.size >= 16 && !pendingIds.has(signature))
      pendingIds.delete(pendingIds.keys().next().value!);
    pendingIds.set(signature, requestId);
    const result = await workbench.debug(props.appId, { ...payload, requestId });
    pendingIds.delete(signature);
    if (ticket !== epoch) return;
    results.value[side] = result;
    if (side === 'candidate') outputs.set(id, result.outputs);
  } catch (e) {
    if (ticket === epoch) {
      problem.value = errorText(e);
      if (e instanceof ApiError && e.measurement) {
        const m = e.measurement;
        problem.value +=
          ' · ' +
          (m.model ?? '模型') +
          ' · 输入 ' +
          tokenCount(m.inputTokens) +
          ' / 输出 ' +
          tokenCount(m.outputTokens) +
          ' token';
        if (m.durationMs != null) problem.value += ' · ' + m.durationMs + ' ms';
      }
    }
  } finally {
    if (ticket === epoch) busy.value = false;
  }
}
function run(side: 'baseline' | 'candidate') {
  if (mode.value === 'live') activePrompt.value = side;
  else void execute(side);
}
function usage(result: DebugResult) {
  const usage = result.usage ?? (result.outputs._usage as DebugResult['usage']);
  if (!usage) return result.mode === 'sample' ? '样例 · 无模型用量' : '供应商未返回用量';
  return [
    usage.model,
    '输入 ' +
      tokenCount(usage.inputTokens) +
      ' / 输出 ' +
      tokenCount(usage.outputTokens) +
      ' token',
  ]
    .filter(Boolean)
    .join(' · ');
}
</script>
<template>
  <section class="wb-section" aria-label="节点调试">
    <header class="wb-section-head">
      <div>
        <h2>节点调试</h2>
        <p>固定一次输入，分别检查草稿和已发布节点的输出。人工节点和写入节点仅预览。</p>
      </div>
      <label>
        被测节点
        <select
          :value="selected"
          @change="emit('select', ($event.target as HTMLSelectElement).value)"
        >
          <option v-for="n in graph.nodes" :key="n.id" :value="n.id">
            {{ nodes.find((c) => c.type === n.type)?.label ?? n.type }} · {{ n.id }}
          </option>
          <option v-if="source && !draftNode" :value="selected">历史节点 · {{ selected }}</option>
        </select>
      </label>
    </header>
    <div v-if="node" class="wb-grid-2">
      <div>
        <h3>输入</h3>
        <p v-if="source">
          输入来源：运行时 v{{ source.versionId }} 的
          {{ source.nodeId }} 节点。重新试跑不会改变原运行。
        </p>
        <dl v-if="Object.keys(node.bindings).length" class="wb-bind">
          <template v-for="(binding, field) in node.bindings" :key="field">
            <dt>{{ field }}</dt>
            <dd>{{ binding }}</dd>
          </template>
        </dl>
        <label>
          节点输入 JSON
          <textarea
            v-model="inputs"
            rows="8"
            spellcheck="false"
            :readonly="pinned || busy"
            @input="resetComparison"
          />
        </label>
        <div class="wb-actions">
          <button v-if="!pinned" @click="pin">固定输入</button>
          <button v-else @click="unpin">解除固定</button>
          <button
            v-if="Object.keys(node.bindings).length"
            :disabled="pinned || busy"
            @click="useUpstream"
          >
            取上游调试输出
          </button>
        </div>
        <details>
          <summary>复用样例文件</summary>
          <p>可导出已经脱敏的固定输入；导入只填充调试面板，不会运行节点。</p>
          <label>
            导入样例 JSON
            <input
              type="file"
              accept=".json,application/json"
              :disabled="busy"
              @change="importFixture"
            />
          </label>
          <button :disabled="!pinned || busy" @click="exportFixture">导出固定输入</button>
        </details>
      </div>
      <div>
        <h3>执行设置</h3>
        <label>
          调试模式
          <select v-model="mode" :disabled="busy">
            <option value="sample">固定样例</option>
            <option v-if="liveEnabled && capability?.liveModel" value="live">真实模型</option>
          </select>
        </label>
        <p>能力版本 {{ capability?.version }} · {{ node.type }}</p>
        <pre v-if="Object.keys({ ...capability?.config, ...node.config }).length">{{
          json({ ...capability?.config, ...node.config })
        }}</pre>
        <div class="wb-actions">
          <button class="wb-primary" :disabled="busy || !draftNode" @click="run('candidate')">
            试跑草稿节点
          </button>
          <button
            :disabled="busy || !baselineGraph.nodes.some((n) => n.id === selected)"
            @click="run('baseline')"
          >
            试跑已发布节点 v{{ baselineVersion }}
          </button>
        </div>
        <div v-if="activePrompt" class="wb-inline-warning">
          <p>本次会将当前输入交给配置的供应商，可能产生费用。</p>
          <button @click="execute(activePrompt)">确认调用一次</button>
          <button @click="activePrompt = null">取消</button>
        </div>
      </div>
    </div>
    <p v-if="problem" role="alert" class="wb-error">{{ problem }}</p>
    <div class="wb-grid-2 wb-results" aria-live="polite">
      <div v-for="side in ['baseline', 'candidate'] as const" :key="side">
        <h3>{{ side === 'baseline' ? '已发布输出' : '草稿输出' }}</h3>
        <template v-if="results[side]">
          <p>{{ results[side]!.durationMs }} ms · {{ usage(results[side]!) }}</p>
          <pre>{{ json(results[side]!.outputs) }}</pre>
          <p v-if="results[side]!.waiting">将进入人工等待</p>
          <p v-if="results[side]!.effects?.length">
            拟执行 {{ results[side]!.effects.length }} 项操作
          </p>
        </template>
        <p v-else>尚未试跑</p>
      </div>
    </div>
  </section>
</template>
