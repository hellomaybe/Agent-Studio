<script setup lang="ts">
import { computed, onMounted, onBeforeUnmount, ref, useId } from 'vue';
import { FullScreen, Minus, Plus } from '@element-plus/icons-vue';
import { Handle, Position, useVueFlow, VueFlow } from '@vue-flow/core';
import type { Connection, Edge, Node } from '@vue-flow/core';
import '@vue-flow/core/dist/style.css';
import { initialPositions, portLabel } from './editor-graph';
import type { Capability, GraphDefinition, GraphEdge, GraphIssue, GraphPosition } from './types';

const props = defineProps<{
  graph: GraphDefinition;
  capabilities: Capability[];
  selected: string;
  readonly: boolean;
  issues: GraphIssue[];
  visited?: string[];
  transitions?: GraphEdge[];
  activeNode?: string;
  traceStatus?: string;
}>();
const emit = defineEmits<{
  select: [id: string];
  connect: [source: string, port: string, target: string];
  move: [id: string, position: GraphPosition];
}>();
const flowId = `workbench-${useId()}`;
const { fitView, zoomIn, zoomOut, viewport } = useVueFlow(flowId);
const metadata = computed(() => new Map(props.capabilities.map((c) => [c.type, c])));
const statusLabels: Record<string, string> = {
  waiting: '等待输入',
  paused: '已暂停',
  failed: '失败',
  running: '执行中',
  unknown: '待核对',
  queued: '已排队',
};
function traceLabel(id: string) {
  if (!props.readonly || !props.traceStatus) return '';
  if (id === props.activeNode && statusLabels[props.traceStatus])
    return statusLabels[props.traceStatus];
  if (props.visited?.includes(id)) return '已经过';
  return '';
}
const edgeId = (edge: GraphEdge) => JSON.stringify([edge.from, edge.port, edge.to]);
const tracedEdges = computed(() => {
  const marked = new Set<string>();
  if (!props.readonly) return marked;
  if (props.transitions !== undefined) return new Set(props.transitions.map(edgeId));
  const visited = props.visited ?? [];
  for (let i = 1; i < visited.length; i++) {
    const links = props.graph.edges.filter((e) => e.from === visited[i - 1] && e.to === visited[i]);
    if (links.length === 1) marked.add(edgeId(links[0]!));
  }
  return marked;
});
const nodes = computed<Node[]>(() => {
  const positions = initialPositions(props.graph);
  return props.graph.nodes.map((n) => ({
    id: n.id,
    type: 'capability',
    position: positions.get(n.id)!,
    deletable: false,
    focusable: false,
    draggable: !props.readonly,
    data: {
      category: metadata.value.get(n.type)?.liveModel ? 'model' : n.type,
      label: metadata.value.get(n.type)?.label ?? n.type,
      ports: metadata.value.get(n.type)?.ports ?? [],
      nodeType: n.type,
      invalid: props.issues.some((issue) => issue.nodeId === n.id),
      trace: traceLabel(n.id),
    },
  }));
});
const edges = computed<Edge[]>(() =>
  props.graph.edges.map((e) => {
    const id = edgeId(e);
    const traced = tracedEdges.value.has(id);
    const selected = !props.readonly && e.from === props.selected;
    return {
      id,
      source: e.from,
      target: e.to,
      sourceHandle: e.port,
      targetHandle: 'in',
      type: 'smoothstep',
      deletable: false,
      updatable: false,
      focusable: false,
      label: e.port === 'next' ? undefined : portLabel(e.port),
      labelBgPadding: [5, 2],
      labelBgBorderRadius: 3,
      style: {
        stroke: traced ? '#24836a' : selected ? '#3e65a8' : '#8b9bac',
        strokeWidth: traced ? 2.7 : selected ? 2.2 : 1.5,
      },
      class: traced ? 'traced-edge' : undefined,
    };
  }),
);
function connect(e: Connection) {
  if (e.sourceHandle && !props.readonly) emit('connect', e.source, e.sourceHandle, e.target);
}
function fit() {
  return fitView({ padding: 0.12, maxZoom: 1.05 });
}
const canvas = ref<HTMLElement>();
const phone = window.matchMedia(
  '(max-width: 700px) and (pointer: coarse), (max-width: 1000px) and (max-height: 500px) and (pointer: coarse)',
);
const orientation = window.matchMedia('(orientation: landscape)');
let disclosure: HTMLDetailsElement | null = null;
let frame = 0;
function fitVisiblePhoneCanvas() {
  if (!phone.matches || (disclosure && !disclosure.open)) return;
  // Details can initialize its graph at zero width. Let the newly visible
  // element and Vue Flow measure their bounds before centering this viewport.
  cancelAnimationFrame(frame);
  frame = requestAnimationFrame(() => {
    frame = requestAnimationFrame(() => void initialView());
  });
}
onMounted(() => {
  disclosure = canvas.value?.closest('details') ?? null;
  disclosure?.addEventListener('toggle', fitVisiblePhoneCanvas);
  orientation.addEventListener('change', fitVisiblePhoneCanvas);
});
onBeforeUnmount(() => {
  disclosure?.removeEventListener('toggle', fitVisiblePhoneCanvas);
  orientation.removeEventListener('change', fitVisiblePhoneCanvas);
  cancelAnimationFrame(frame);
});
function initialView() {
  if (phone.matches) return fitView({ padding: 0.04, maxZoom: 1.05 });
  // Keep node labels readable. For a large graph the explicit fit control shows the overview.
  return fitView({ padding: 0.045, minZoom: 0.82, maxZoom: 1.05 });
}
</script>
<template>
  <section ref="canvas" class="graph-canvas" aria-label="业务流程画布">
    <VueFlow
      :id="flowId"
      :nodes="nodes"
      :edges="edges"
      :nodes-connectable="!readonly"
      :nodes-draggable="!readonly"
      :edges-updatable="false"
      :elements-selectable="false"
      :delete-key-code="null"
      :min-zoom="0.22"
      :max-zoom="2"
      :zoom-on-double-click="false"
      @nodes-initialized="initialView"
      @connect="connect"
      @node-drag-stop="emit('move', $event.node.id, $event.node.position)"
      @edge-click="emit('select', $event.edge.source)"
    >
      <template #node-capability="{ id, data }">
        <Handle
          v-if="data.nodeType !== 'start'"
          id="in"
          type="target"
          :position="Position.Top"
          :connectable="!readonly"
        />
        <button
          type="button"
          class="graph-node"
          :class="{ invalid: data.invalid }"
          :data-trace="data.trace"
          :data-category="data.category"
          :aria-pressed="selected === id"
          :aria-label="[`${data.label}，节点 ${id}`, data.trace].filter(Boolean).join('，')"
          @click="emit('select', id)"
        >
          <strong>{{ data.label }}</strong>
          <code>{{ id }}</code>
          <span v-if="data.trace" class="trace-chip">{{ data.trace }}</span>
          <span v-if="data.invalid" class="invalid-mark" aria-label="节点存在错误">!</span>
        </button>
        <Handle
          v-for="(port, index) in data.ports as string[]"
          :key="port"
          :id="port"
          type="source"
          :position="Position.Bottom"
          :style="{ left: `${((index + 1) * 100) / (data.ports.length + 1)}%` }"
          :connectable="!readonly"
        />
      </template>
    </VueFlow>
    <footer>
      <span>{{ nodes.length }} 个节点 · {{ edges.length }} 条连线</span>
      <div role="group" aria-label="画布缩放">
        <button type="button" aria-label="缩小" @click="zoomOut()"><Minus /></button>
        <output>{{ Math.round(viewport.zoom * 100) }}%</output>
        <button type="button" aria-label="放大" @click="zoomIn()"><Plus /></button>
        <button type="button" aria-label="适应画布" @click="fit"><FullScreen /></button>
      </div>
    </footer>
  </section>
</template>
<style scoped>
.graph-canvas {
  display: flex;
  flex-direction: column;
  min-width: 0;
  height: clamp(590px, 73vh, 780px);
  background-color: #f5f7fa;
  background-image: radial-gradient(#ced7e1 1px, transparent 1px);
  background-size: 16px 16px;
}
.graph-canvas :deep(.vue-flow) {
  flex: 1;
  min-height: 0;
}
.graph-node {
  pointer-events: auto;
  display: grid;
  gap: 3px;
  align-content: center;
  width: 174px;
  height: 56px;
  padding: 6px 13px;
  border: 1px solid #bbc8d6;
  border-radius: 8px;
  text-align: start;
  background: white;
  color: #253548;
  box-shadow: 0 2px 5px #19334c0c;
  cursor: grab;
}
.graph-node[aria-pressed='true'] {
  border-color: #396eb1;
  box-shadow: 0 0 0 3px #396eb124;
}
.graph-node[data-category='model'] {
  border-inline-start: 4px solid #8860b1;
}
.graph-node[data-category='confirm'] {
  border-inline-start: 4px solid #ad772e;
}
.graph-node[data-category='condition'] {
  border-inline-start: 4px solid #388385;
}
.graph-node[data-category='filter-assigned'] {
  border-inline-start: 4px solid #507ca8;
}
.graph-node.invalid {
  border-color: #b53b38;
}
.graph-node[data-trace]:not([data-trace='']) {
  border-left: 4px solid #24836a;
}
.graph-node[data-trace='等待输入'],
.graph-node[data-trace='已暂停'] {
  border-left-color: #b87819;
}
.graph-node[data-trace='失败'],
.graph-node[data-trace='待核对'] {
  border-left-color: #aa3f3e;
}
.graph-node[data-trace]:not([data-trace='']) code {
  max-width: 107px;
}
.trace-chip {
  position: absolute;
  right: 6px;
  bottom: 7px;
  font-size: 10px;
  color: #4d5f71;
}
.graph-node strong,
.graph-node code {
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.graph-node strong {
  font-size: 14px;
}
.graph-node code {
  font-size: 11px;
  color: #5a6c80;
}
.invalid-mark {
  position: absolute;
  right: 8px;
  top: 6px;
  color: #a73735;
}
.graph-canvas :deep(.vue-flow__handle) {
  width: 10px;
  height: 10px;
  background: #507498;
  border: 2px solid white;
  box-shadow: 0 0 0 1px #a7b7c5;
}
footer {
  display: flex;
  gap: 6px;
  align-items: center;
  justify-content: space-between;
  padding: 7px 12px;
  color: #53667b;
  font-size: 12px;
}
footer div {
  display: flex;
  align-items: center;
  gap: 2px;
  border: 1px solid #cad3dd;
  border-radius: 7px;
  background: #fff;
  padding: 2px;
}
footer button {
  background: transparent;
  border: 0;
  display: grid;
  place-items: center;
  width: 29px;
  height: 28px;
  color: #45596e;
}
footer svg {
  width: 15px;
  height: 15px;
}
footer output {
  min-width: 32px;
  text-align: center;
  font-variant-numeric: tabular-nums;
}
@media (max-width: 700px) and (pointer: coarse),
  (max-width: 1000px) and (max-height: 500px) and (pointer: coarse) {
  .graph-node {
    height: 64px;
  }
  .graph-node strong {
    font-size: 16px;
  }
  footer button {
    width: 44px;
    height: 44px;
  }
}
</style>
