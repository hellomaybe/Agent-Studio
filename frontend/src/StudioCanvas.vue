<script setup lang="ts">
import type { Edge, Node, XYPosition } from '@vue-flow/core';
import type { StudioRun } from './api';

import { computed, markRaw, ref, useId } from 'vue';

import {
  Brush,
  Check,
  CircleClose,
  Clock,
  Document,
  Flag,
  FullScreen,
  List,
  MagicStick,
  Minus,
  Plus,
  Share,
  User,
  VideoPause,
} from '@element-plus/icons-vue';
import { Handle, Position, useVueFlow, VueFlow } from '@vue-flow/core';

import '@vue-flow/core/dist/style.css';

const props = defineProps<{
  runStatus?: StudioRun['status'];
  sanitize: boolean;
  selected: string;
  threshold: number;
  visited: string[];
}>();
const emit = defineEmits<{ select: [id: string] }>();

const canvasId = `agent-studio-${useId()}`;
const { fitView, viewport, zoomIn, zoomOut } = useVueFlow(canvasId);
const dragged = ref<Record<string, XYPosition>>({});

const catalog = [
  {
    id: 'start',
    name: '会议文本',
    hint: '输入 · 文本',
    icon: markRaw(Document),
    x: 148,
    y: 0,
  },
  {
    id: 'sanitize',
    name: '文本清洗',
    hint: '处理 · 规范空白',
    icon: markRaw(Brush),
    x: 148,
    y: 78,
  },
  {
    id: 'extract',
    name: '提取待办',
    hint: '模型 · 结构化输出',
    icon: markRaw(MagicStick),
    x: 148,
    y: 78,
  },
  {
    id: 'gate',
    name: '有待办？',
    hint: '',
    icon: markRaw(Share),
    x: 148,
    y: 156,
  },
  {
    id: 'confirm',
    name: '用户确认',
    hint: '人工 · 等待确认',
    icon: markRaw(User),
    x: 60,
    y: 256,
  },
  {
    id: 'create',
    name: '创建任务',
    hint: '工具 · 生成任务预览',
    icon: markRaw(List),
    x: 60,
    y: 336,
  },
  {
    id: 'end',
    name: '输出结果',
    hint: '结束 · 运行摘要',
    icon: markRaw(Flag),
    x: 148,
    y: 424,
  },
] as const;

const nodes = computed<Node[]>(() =>
  catalog
    .filter((n) => props.sanitize || n.id !== 'sanitize')
    .map((n) => {
      const offset = props.sanitize && n.id !== 'start' && n.id !== 'sanitize' ? 78 : 0;
      const moved = dragged.value[n.id];
      return {
        id: n.id,
        type: 'studio',
        deletable: false,
        focusable: false,
        position: {
          x: n.x + (moved?.x ?? 0),
          y: n.y + offset + (moved?.y ?? 0),
        },
        data: {
          name: n.id === 'gate' && props.threshold > 1 ? '待办达标？' : n.name,
          hint: n.id === 'gate' ? `条件 · 数量 ≥ ${props.threshold}` : n.hint,
          icon: n.icon,
        },
      };
    }),
);

// Store drag offsets independently of the extra row reserved for the clean-up node.
function rememberPosition(node: Node) {
  const initial = catalog.find((n) => n.id === node.id);
  if (!initial) return;
  const offset = props.sanitize && initial.id !== 'start' && initial.id !== 'sanitize' ? 78 : 0;
  dragged.value[node.id] = {
    x: node.position.x - initial.x,
    y: node.position.y - initial.y - offset,
  };
}

function link(source: string, target: string, label?: string, side = false): Edge {
  const active = props.visited.some((id, i) => id === source && props.visited[i + 1] === target);
  return {
    id: `${source}-${target}`,
    source,
    target,
    type: 'smoothstep',
    ...(side
      ? { sourceHandle: 'bypass-out', targetHandle: 'bypass-in' }
      : { sourceHandle: 'out', targetHandle: 'in' }),
    label,
    labelBgPadding: [4, 2],
    labelBgBorderRadius: 3,
    pathOptions: { borderRadius: 8, offset: side ? 50 : 14 },
    focusable: false,
    selectable: false,
    deletable: false,
    updatable: false,
    style: {
      stroke: active ? 'var(--pass)' : 'var(--wire)',
      strokeWidth: active ? 2.5 : 1.5,
    },
    labelStyle: { fill: 'var(--muted)', fontSize: 12 },
    labelBgStyle: { fill: 'var(--paper)' },
  };
}
const edges = computed(() => [
  ...(props.sanitize
    ? [link('start', 'sanitize'), link('sanitize', 'extract')]
    : [link('start', 'extract')]),
  link('extract', 'gate'),
  link('gate', 'confirm', props.threshold === 1 ? '有待办' : '达到阈值'),
  link('confirm', 'create'),
  link('create', 'end'),
  link('gate', 'end', props.threshold === 1 ? '无待办' : '未达阈值', true),
]);

function stateOf(id: string) {
  if (id === props.visited.at(-1)) {
    if (props.runStatus === 'failed') return '执行失败';
    if (props.runStatus === 'running') return '执行中';
    if (props.runStatus === 'waiting') return '等待确认';
  }
  if (props.visited.includes(id)) return '已执行';
  return props.runStatus === 'completed' ? '已跳过' : '';
}

function fit() {
  // Allow room beyond the rightmost node for the bypass edge and its label.
  return fitView({
    maxZoom: 1.1,
    padding: { left: '16px', right: '80px', top: '14px', bottom: '14px' },
  });
}
</script>

<template>
  <section class="studio-canvas" aria-label="会议待办流程画布">
    <VueFlow
      :id="canvasId"
      class="viewport"
      :nodes="nodes"
      :edges="edges"
      :delete-key-code="null"
      :elements-selectable="false"
      :nodes-connectable="false"
      :edges-updatable="false"
      :min-zoom="0.35"
      :max-zoom="2"
      :zoom-on-double-click="false"
      @nodes-initialized="fit"
      @node-drag-stop="rememberPosition($event.node)"
    >
      <template #node-studio="{ id, data }">
        <Handle
          v-if="id !== 'start'"
          id="in"
          type="target"
          :position="Position.Top"
          :connectable="false"
        />
        <Handle
          v-if="id !== 'end'"
          id="out"
          type="source"
          :position="Position.Bottom"
          :connectable="false"
        />
        <Handle
          v-if="id === 'gate'"
          id="bypass-out"
          type="source"
          :position="Position.Right"
          :connectable="false"
        />
        <Handle
          v-if="id === 'end'"
          id="bypass-in"
          type="target"
          :position="Position.Right"
          :connectable="false"
        />
        <button
          type="button"
          class="node"
          :data-id="id"
          :data-state="stateOf(id)"
          :aria-pressed="selected === id"
          :aria-label="[data.name, data.hint, stateOf(id)].filter(Boolean).join('，')"
          @click="emit('select', id)"
        >
          <span class="node-icon"><component :is="data.icon" aria-hidden="true" /></span>
          <span class="node-text">
            <b>{{ data.name }}</b>
            <small>{{ data.hint }}</small>
          </span>
          <span class="status" aria-hidden="true">
            <CircleClose v-if="stateOf(id) === '执行失败'" aria-hidden="true" />
            <Clock v-else-if="stateOf(id) === '执行中'" aria-hidden="true" />
            <VideoPause v-else-if="stateOf(id) === '等待确认'" aria-hidden="true" />
            <Check v-else-if="stateOf(id) === '已执行'" aria-hidden="true" />
            <Minus v-else-if="stateOf(id) === '已跳过'" aria-hidden="true" />
          </span>
        </button>
      </template>
    </VueFlow>
    <footer class="canvas-footer">
      <span>{{ nodes.length }} 个节点</span>
      <div class="zoom-controls" role="group" aria-label="画布缩放">
        <button
          type="button"
          aria-label="缩小"
          :disabled="viewport.zoom <= 0.35"
          @click="zoomOut()"
        >
          <Minus aria-hidden="true" />
        </button>
        <output aria-label="缩放比例">{{ Math.round(100 * viewport.zoom) }}%</output>
        <button type="button" aria-label="放大" :disabled="viewport.zoom >= 2" @click="zoomIn()">
          <Plus aria-hidden="true" />
        </button>
        <button type="button" aria-label="适应画布" title="适应画布" @click="fit">
          <FullScreen aria-hidden="true" />
        </button>
      </div>
    </footer>
  </section>
</template>

<style scoped>
.studio-canvas {
  --paper: var(--el-fill-color-lighter, #f6f8fa);
  --surface: var(--el-bg-color, #fff);
  --border: var(--el-border-color, #d5dce3);
  --wire: var(--el-text-color-placeholder, #a4b1b4);
  --muted: var(--el-text-color-secondary, #657580);
  --accent: var(--el-color-primary, #365ad5);
  --pass: var(--el-color-success-dark-2, #168670);

  display: flex;
  flex-direction: column;
  min-width: 0;
  height: clamp(500px, 65vh, 720px);
  overflow: hidden;
  color: var(--el-text-color-primary, #203139);
  background-color: var(--paper);
  background-image: radial-gradient(var(--border) 1px, transparent 1px);
  background-size: 15px 15px;
}

.viewport {
  flex: 1;
  min-height: 0;
}

.viewport :deep(.vue-flow__handle) {
  width: 6px;
  height: 6px;
  background: var(--surface);
  border: 1px solid var(--wire);
}

.node {
  box-sizing: border-box;
  display: flex;
  gap: 9px;
  align-items: center;
  width: 179px;
  height: 50px;
  padding: 6px 10px;
  color: inherit;
  text-align: start;
  cursor: grab;
  background: var(--surface);
  border: 1px solid var(--border);
  border-radius: 8px;
  box-shadow: 0 2px 4px #203d4810;
}

.node:active {
  cursor: grabbing;
}

.node[aria-pressed='true'] {
  border-color: var(--accent);
  box-shadow: 0 0 0 3px color-mix(in srgb, var(--accent) 15%, transparent);
}

.node:hover,
.node:focus-visible {
  border-color: var(--accent);
}

.node:focus-visible,
.zoom-controls button:focus-visible {
  outline: 2px solid var(--accent);
  outline-offset: 2px;
}

.node-icon {
  --tone: #337e7d;

  display: grid;
  flex: 0 0 30px;
  place-items: center;
  height: 30px;
  color: var(--tone);
  background: color-mix(in srgb, var(--tone) 11%, var(--surface));
  border-radius: 7px;
}

.node[data-id='extract'] .node-icon {
  --tone: #8061b9;
}

.node[data-id='gate'] .node-icon {
  --tone: #ac7a1c;
}

.node[data-id='confirm'] .node-icon {
  --tone: #b2763b;
}

.node[data-id='create'] .node-icon {
  --tone: #4483ac;
}

.node-text b,
.node-text small {
  display: block;
  white-space: nowrap;
}

.node-text b {
  font-size: 14px;
  font-weight: 650;
  line-height: 20px;
}

.node-text small {
  font-size: 12px;
  line-height: 16px;
  color: var(--muted);
}

.status {
  position: absolute;
  top: 5px;
  right: 5px;
  color: var(--pass);
}

.node-icon svg,
.zoom-controls svg {
  width: 17px;
  height: 17px;
}

.status svg {
  width: 13px;
  height: 13px;
}

.node[data-state='等待确认'] .status {
  color: var(--el-color-warning-dark-2, #986822);
}

.node[data-state='执行失败'] .status {
  color: var(--el-color-danger-dark-2);
}
.node[data-state='执行中'] .status {
  color: var(--accent);
}

.node[data-state='已跳过'] {
  border-style: dashed;
}

.node[data-state='已跳过'] .status {
  color: var(--muted);
}

.canvas-footer {
  display: flex;
  gap: 8px;
  align-items: center;
  justify-content: space-between;
  padding: 5px 12px 8px;
  font-size: 12px;
  color: var(--muted);
}

.zoom-controls {
  display: flex;
  align-items: center;
  padding: 2px;
  background: var(--surface);
  border: 1px solid var(--border);
  border-radius: 7px;
}

.zoom-controls button {
  display: grid;
  place-items: center;
  width: 30px;
  height: 28px;
  border-radius: 4px;
  border: 0;
  color: inherit;
  background: transparent;
}

.zoom-controls button:last-child {
  border-left: 1px solid var(--border);
}

.zoom-controls button:hover:enabled {
  color: var(--accent);
  background: var(--paper);
}

.zoom-controls button:disabled {
  opacity: 0.4;
}

.zoom-controls output {
  min-width: 35px;
  font-variant-numeric: tabular-nums;
  text-align: center;
}

@media (pointer: coarse) {
  .zoom-controls button {
    width: 40px;
    height: 40px;
  }
}
</style>
