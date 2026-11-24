<script setup lang="ts">
import { computed, nextTick, ref, watch } from 'vue';
import EditorCanvas from './EditorCanvas.vue';
import {
  bindingChoices,
  initialPositions,
  insertOnEdge,
  portLabel,
  reachable,
} from './editor-graph';
import type {
  Capability,
  ConfigField,
  ConfigValue,
  GraphDefinition,
  GraphEdge,
  GraphIssue,
  GraphNode,
  GraphPosition,
} from './types';

const props = withDefaults(
  defineProps<{
    modelValue: GraphDefinition;
    capabilities: Capability[];
    selected: string;
    issues?: GraphIssue[];
    readonly?: boolean;
    visited?: string[];
    transitions?: GraphEdge[];
    activeNode?: string;
    traceStatus?: string;
  }>(),
  { issues: () => [], readonly: false },
);
const emit = defineEmits<{
  'update:modelValue': [graph: GraphDefinition];
  select: [id: string];
  debug: [id: string];
}>();
const pick = ref('');
const message = ref('');
const canvasArea = ref<HTMLElement>();
const inspector = ref<HTMLElement>();
function inspect(id: string) {
  emit('select', id);
  if (
    !props.readonly &&
    window.matchMedia(
      '(max-width: 700px) and (pointer: coarse), (max-width: 1000px) and (max-height: 500px) and (pointer: coarse)',
    ).matches
  )
    void nextTick(() => inspector.value?.scrollIntoView({ block: 'start' }));
}
const graph = computed(() => props.modelValue);
const node = computed(() => graph.value.nodes.find((n) => n.id === props.selected));
const cap = computed(() => props.capabilities.find((c) => c.type === node.value?.type));
const available = computed(() =>
  props.capabilities.filter(
    (c) => c.type !== 'start' || !graph.value.nodes.some((n) => n.type === 'start'),
  ),
);
watch(
  available,
  (choices) => {
    if (!choices.some((c) => c.type === pick.value)) pick.value = choices[0]?.type ?? '';
  },
  { immediate: true },
);
watch(
  () => props.selected,
  () => {
    message.value = '';
  },
);
const picked = computed(() => available.value.find((c) => c.type === pick.value));
const fields = computed<ConfigField[]>(() => {
  if (!cap.value) return [];
  const declared = cap.value.configFields ?? [];
  return [
    ...declared,
    ...Object.entries(cap.value.config)
      .filter(([key]) => !declared.some((f) => f.key === key))
      .map(
        ([key, value]): ConfigField => ({
          key,
          label: key,
          type:
            typeof value === 'number' ? 'number' : typeof value === 'boolean' ? 'boolean' : 'text',
        }),
      ),
  ];
});
const optionsByInput = computed(() =>
  Object.fromEntries(
    Object.entries(cap.value?.inputs ?? {}).map(([key, type]) => [
      key,
      bindingChoices(props.selected, type, graph.value, props.capabilities),
    ]),
  ),
);
const issues = computed(() => {
  const list = [...props.issues];
  if (!node.value || !cap.value || node.value.type === 'start') return list;
  for (const key of Object.keys(cap.value.inputs)) {
    const mapping = node.value.bindings[key];
    if (!optionsByInput.value[key]?.some((o) => o.value === mapping)) {
      if (!list.some((i) => i.nodeId === node.value!.id && i.field === key)) {
        list.push({
          nodeId: node.value.id,
          field: key,
          detail: mapping
            ? `输入 ${key} 的映射已失效，请重新选择上游字段。`
            : `请为输入 ${key} 选择上游字段。`,
        });
      }
    }
  }
  return list;
});

function change(next: GraphDefinition) {
  if (props.readonly) return;
  message.value = '';
  emit('update:modelValue', next);
}
function patchNode(id: string, partial: Partial<GraphNode>) {
  change({
    ...graph.value,
    nodes: graph.value.nodes.map((n) => (n.id === id ? { ...n, ...partial } : n)),
  });
}
function move(id: string, position: GraphPosition) {
  if (!Number.isFinite(position.x) || !Number.isFinite(position.y)) return;
  patchNode(id, { position: { x: Math.round(position.x), y: Math.round(position.y) } });
}
function createNode(capability: Capability, position?: GraphPosition): GraphNode {
  const key = capability.type.replace(/[^a-zA-Z0-9_-]/g, '-');
  const prefix = (/^[A-Za-z]/.test(key) ? key : `node-${key}`).slice(0, 42);
  let id = prefix;
  let count = 1;
  while (graph.value.nodes.some((n) => n.id === id)) id = `${prefix}-${++count}`;
  return { id, type: capability.type, config: { ...capability.config }, bindings: {}, position };
}
function add(edge?: GraphEdge) {
  if (!picked.value || props.readonly) return;
  const positions = initialPositions(graph.value);
  const start = positions.get(edge?.from ?? props.selected) ?? { x: 0, y: 0 };
  const end = edge ? positions.get(edge.to) : undefined;
  let position: GraphPosition | undefined = end
    ? { x: Math.round((start.x + end.x) / 2), y: Math.round((start.y + end.y) / 2) }
    : { x: start.x + 215, y: start.y + 40 };
  if (edge && !graph.value.nodes.some((n) => n.position)) {
    // Inserting a node changes downstream ranks. Let the layout place the entire chain.
    position = undefined;
  } else {
    const spot = position;
    while (
      [...positions.values()].some(
        (other) => Math.abs(other.x - spot.x) < 195 && Math.abs(other.y - spot.y) < 70,
      )
    )
      spot.x += 210;
  }
  const newNode = createNode(picked.value, position);
  if (edge && picked.value.ports.length !== 1) {
    message.value = '插入连线需要只有一个后续分支的节点。';
    return;
  }
  change(
    edge
      ? insertOnEdge(graph.value, edge, newNode, props.capabilities)
      : { ...graph.value, nodes: [...graph.value.nodes, newNode] },
  );
  inspect(newNode.id);
}
function removeSelected() {
  if (!node.value || props.readonly || node.value.type === 'start') return;
  const removed = node.value.id;
  const nodes = graph.value.nodes
    .filter((n) => n.id !== removed)
    .map((n) => ({
      ...n,
      bindings: Object.fromEntries(
        Object.entries(n.bindings).filter(([, value]) => !value.startsWith(`${removed}.`)),
      ),
    }));
  change({
    ...graph.value,
    nodes,
    edges: graph.value.edges.filter((e) => e.from !== removed && e.to !== removed),
  });
  emit('select', nodes[0]?.id ?? '');
}
function destination(from: string, port: string) {
  return graph.value.edges.find((e) => e.from === from && e.port === port);
}
function chooseTarget(from: string, port: string, to: string, replace = true) {
  if (props.readonly) return;
  const source = graph.value.nodes.find((n) => n.id === from);
  if (!props.capabilities.find((c) => c.type === source?.type)?.ports.includes(port)) return;
  const previous = destination(from, port);
  if (previous?.to === to) return;
  if (previous && !replace) {
    message.value = '该分支已有连线，请在「分支去向」中更改。';
    return;
  }
  const rest = graph.value.edges.filter((e) => e.from !== from || e.port !== port);
  if (to) {
    const target = graph.value.nodes.find((n) => n.id === to);
    if (!target || target.type === 'start' || reachable(to, rest).has(from)) {
      message.value = '该连线会指向起点或形成循环，请选择其他后续节点。';
      return;
    }
    rest.push({ from, port, to });
  }
  change({ ...graph.value, edges: rest });
}
function chooseConfig(key: string, value: ConfigValue) {
  if (node.value) patchNode(node.value.id, { config: { ...node.value.config, [key]: value } });
}
async function changeDestination(id: string, port: string, event: Event) {
  const select = event.target as HTMLSelectElement;
  chooseTarget(id, port, select.value);
  await nextTick();
  select.value = destination(id, port)?.to ?? '';
}
function chooseInput(key: string, path: string) {
  if (!node.value) return;
  const bindings = { ...node.value.bindings };
  if (path) bindings[key] = path;
  else delete bindings[key];
  patchNode(node.value.id, { bindings });
}
function targetValue(event: Event) {
  return (event.target as HTMLInputElement).value;
}
function configOptions(field: ConfigField) {
  return (field.options ?? []).map((o) => (typeof o === 'string' ? { label: o, value: o } : o));
}
function selectOption(field: ConfigField, event: Event) {
  const option = configOptions(field)[(event.target as HTMLSelectElement).selectedIndex];
  if (option) chooseConfig(field.key, option.value);
}
</script>
<template>
  <section class="graph-editor" aria-label="业务流程编辑器">
    <div v-if="!readonly" class="palette">
      <label>
        节点能力
        <select v-model="pick" aria-label="节点能力">
          <option v-for="choice in available" :key="choice.type" :value="choice.type">
            {{ choice.label }} · {{ choice.type }}
          </option>
        </select>
      </label>
      <button type="button" :disabled="!picked" @click="add()">添加节点</button>
    </div>
    <p v-if="message" class="error" role="alert">{{ message }}</p>
    <div v-if="issues.length" class="errors" aria-live="polite">
      <div v-for="(issue, index) in issues" :key="index">
        <button v-if="issue.nodeId" type="button" @click="emit('select', issue.nodeId)">
          {{ issue.nodeId }}
        </button>
        {{ issue.detail }}
      </div>
    </div>
    <div ref="canvasArea" class="workspace">
      <EditorCanvas
        :graph="graph"
        :capabilities="capabilities"
        :selected="selected"
        :readonly="readonly"
        :issues="issues"
        :visited="visited"
        :transitions="transitions"
        :active-node="activeNode"
        :trace-status="traceStatus"
        @move="move"
        @select="inspect"
        @connect="(from, port, to) => chooseTarget(from, port, to, false)"
      />
      <aside ref="inspector" aria-label="节点配置">
        <template v-if="node">
          <header>
            <strong>{{ cap?.label ?? node.type }}</strong>
            <code>{{ node.id }}</code>
            <button
              v-if="!readonly"
              class="back-to-canvas"
              type="button"
              @click="canvasArea?.scrollIntoView({ block: 'start' })"
            >
              返回画布
            </button>
          </header>
          <fieldset :disabled="readonly" :key="node.id">
            <legend>节点参数</legend>
            <p v-if="!fields.length">此节点没有可配置参数。</p>
            <label v-for="f in fields" :key="f.key">
              {{ f.label || f.key }}
              <textarea
                v-if="f.type === 'textarea'"
                :aria-label="f.label || f.key"
                :value="String(node.config[f.key] ?? cap?.config[f.key] ?? '')"
                rows="3"
                @input="chooseConfig(f.key, targetValue($event))"
              />
              <select
                v-else-if="f.type === 'select'"
                :aria-label="f.label || f.key"
                :value="String(node.config[f.key] ?? cap?.config[f.key] ?? '')"
                @change="selectOption(f, $event)"
              >
                <option
                  v-for="opt in configOptions(f)"
                  :key="String(opt.value)"
                  :value="String(opt.value)"
                >
                  {{ opt.label }}
                </option>
              </select>
              <input
                v-else-if="f.type === 'boolean'"
                :aria-label="f.label || f.key"
                type="checkbox"
                :checked="(node.config[f.key] ?? cap?.config[f.key]) === true"
                @change="chooseConfig(f.key, ($event.target as HTMLInputElement).checked)"
              />
              <input
                v-else-if="f.type === 'number'"
                :aria-label="f.label || f.key"
                type="number"
                :min="f.min ?? undefined"
                :max="f.max ?? undefined"
                :value="node.config[f.key] ?? cap?.config[f.key] ?? ''"
                @input="
                  chooseConfig(
                    f.key,
                    targetValue($event) === '' ? null : Number(targetValue($event)),
                  )
                "
              />
              <input
                v-else
                type="text"
                :aria-label="f.label || f.key"
                :value="String(node.config[f.key] ?? cap?.config[f.key] ?? '')"
                @input="chooseConfig(f.key, targetValue($event))"
              />
            </label>
          </fieldset>
          <fieldset v-if="cap && Object.keys(cap.inputs).length" :disabled="readonly">
            <legend>{{ node.type === 'start' ? '外部输入' : '输入映射' }}</legend>
            <label v-for="(type, key) in cap.inputs" :key="key">
              <span>
                {{ key }}
                <small>{{ type }}</small>
              </span>
              <select
                v-if="node.type !== 'start'"
                :aria-label="`输入映射 ${key}`"
                :value="node.bindings[key] ?? ''"
                @change="chooseInput(key, targetValue($event))"
              >
                <option value="">选择上游字段</option>
                <option
                  v-if="
                    node.bindings[key] &&
                    !optionsByInput[key]?.some((o) => o.value === node!.bindings[key])
                  "
                  :value="node.bindings[key]"
                  disabled
                >
                  {{ node.bindings[key] }} · 已失效
                </option>
                <option
                  v-for="choice in optionsByInput[key]"
                  :key="choice.value"
                  :value="choice.value"
                >
                  {{ choice.label }}
                </option>
              </select>
            </label>
          </fieldset>
          <fieldset v-if="cap?.ports.length" :disabled="readonly">
            <legend>分支去向</legend>
            <div v-for="port in cap.ports" :key="port">
              <label>
                {{ portLabel(port) }}
                <select
                  :aria-label="`${portLabel(port)}分支去向`"
                  :value="destination(node.id, port)?.to ?? ''"
                  @change="changeDestination(node.id, port, $event)"
                >
                  <option value="">未连接</option>
                  <option
                    v-for="other in graph.nodes.filter(
                      (n) => n.id !== node!.id && n.type !== 'start',
                    )"
                    :key="other.id"
                    :value="other.id"
                  >
                    {{ capabilities.find((c) => c.type === other.type)?.label ?? other.type }} ·
                    {{ other.id }}
                  </option>
                </select>
              </label>
              <button
                v-if="!readonly && destination(node.id, port)"
                type="button"
                class="link-action"
                :disabled="picked?.ports.length !== 1"
                @click="add(destination(node.id, port))"
              >
                在此连线插入所选能力
              </button>
            </div>
          </fieldset>
          <section v-if="cap && Object.keys(cap.outputs).length" class="outputs">
            <h4>输出字段</h4>
            <code v-for="(type, key) in cap.outputs" :key="key">{{ key }} · {{ type }}</code>
          </section>
          <div class="node-actions">
            <button v-if="!readonly" type="button" @click="emit('debug', node.id)">
              调试此节点
            </button>
            <button
              v-if="!readonly && node.type !== 'start'"
              class="remove"
              type="button"
              @click="removeSelected"
            >
              移除节点
            </button>
          </div>
        </template>
        <p v-else>选择节点以查看配置。</p>
      </aside>
    </div>
  </section>
</template>
<style scoped>
.graph-editor {
  container: graph-editor / inline-size;
  min-width: 0;
  color: var(--el-text-color-primary, #253548);
}
.palette {
  display: flex;
  flex-wrap: wrap;
  align-items: end;
  gap: 8px;
  padding: 12px;
  border-bottom: 1px solid #dce3ea;
}
.graph-editor label {
  display: grid;
  gap: 4px;
  font-size: 13px;
  font-weight: 550;
}
.palette label {
  min-width: 170px;
}
.graph-editor select,
.graph-editor input:not([type='checkbox']),
.graph-editor textarea {
  min-width: 0;
  width: 100%;
  padding: 6px 8px;
  color: inherit;
  font: inherit;
  font-weight: 400;
  background: var(--el-bg-color, #fff);
  border: 1px solid var(--el-border-color, #c6d0dc);
  border-radius: 5px;
}
.graph-editor textarea {
  resize: vertical;
}
.palette button,
.node-actions button {
  padding: 6px 12px;
  border: 1px solid #bdc9d5;
  border-radius: 5px;
  background: #fff;
  color: #385a84;
}
.graph-editor button:disabled {
  opacity: 0.5;
}
.workspace {
  display: grid;
  grid-template-columns: minmax(0, 1fr) minmax(228px, 30%);
  border-bottom: 1px solid #dce3ea;
}
aside {
  min-width: 0;
  padding: 15px;
  border-left: 1px solid #dce3ea;
  background: var(--el-bg-color, #fff);
}
aside header {
  display: grid;
  gap: 4px;
  padding-bottom: 10px;
}
aside header code,
small {
  color: #65748a;
}
aside fieldset {
  display: grid;
  gap: 8px;
  min-width: 0;
  margin: 0 0 12px;
  padding: 8px 0 0;
  border: 0;
  border-top: 1px solid #e1e6ed;
}
aside legend,
.outputs h4 {
  font-weight: 650;
  font-size: 13px;
  margin: 0;
}
aside p {
  font-size: 13px;
  color: #627288;
  margin: 0;
}
small {
  font-weight: 400;
  font-family: monospace;
}
.link-action,
.errors button {
  padding: 4px 0;
  color: #2d619b;
  border: 0;
  background: transparent;
  font-size: 12px;
}
.outputs code {
  display: inline-block;
  margin: 5px 3px 0 0;
  padding: 2px 5px;
  background: #f0f3f7;
  border-radius: 3px;
  font-size: 11px;
}
.node-actions {
  display: flex;
  flex-wrap: wrap;
  gap: 6px;
  margin-top: 16px;
}
.node-actions .remove {
  color: #a43b3b;
}
.errors,
.error {
  margin: 0;
  padding: 8px 14px;
  background: #fff4ed;
  color: #893c26;
  font-size: 13px;
}
.errors button {
  color: inherit;
  font-weight: 650;
}
@container graph-editor (max-width:660px) {
  .workspace {
    grid-template-columns: 1fr;
  }
  aside {
    border-left: 0;
    border-top: 1px solid #dce3ea;
  }
}
.back-to-canvas {
  display: none;
}
@media (max-width: 700px) and (pointer: coarse),
  (max-width: 1000px) and (max-height: 500px) and (pointer: coarse) {
  aside header {
    grid-template-columns: minmax(0, 1fr) auto;
  }
  aside header code {
    grid-column: 1;
  }
  .back-to-canvas {
    display: inline-flex;
    align-items: center;
    grid-column: 2;
    grid-row: 1 / 3;
    min-height: 44px;
    padding: 0 8px;
    border: 0;
    background: transparent;
    color: #285b95;
  }
  .graph-editor :is(select, input:not([type='checkbox']), textarea) {
    min-height: 44px;
    font-size: 16px;
  }
  .palette button,
  .node-actions button,
  .link-action {
    min-height: 44px;
  }
}
</style>
