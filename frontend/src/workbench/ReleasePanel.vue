<script setup lang="ts">
import { computed, ref, watch } from 'vue';
import { errorText } from '../http';
import { workbench, type Draft, type Published, type Evaluation, type Suite } from './client';
import type { Capability, ConfigValue, GraphDefinition } from './types';
const props = defineProps<{
  appId: string;
  graph: GraphDefinition;
  draft: Draft;
  published: Published;
  capabilities: Capability[];
  dirty: boolean;
  busy: boolean;
  changeId: number;
}>();
const emit = defineEmits<{ publish: []; restore: [graph: GraphDefinition] }>();
const suite = ref<Suite>(),
  savedCases = ref(''),
  report = ref<Evaluation>(),
  problem = ref(''),
  working = ref(false);
const versions = ref<{ version: number; revision: number }[]>([]),
  selectedVersion = ref(0),
  confirmPublish = ref(false);
let generation = 0;
const suiteDirty = computed(
  () => !!suite.value && JSON.stringify(suite.value.cases) !== savedCases.value,
);
const approved = computed(
  () =>
    !!report.value &&
    report.value.cases.length > 0 &&
    report.value.cases.every((c) => c.candidate.pass) &&
    !props.dirty &&
    !suiteDirty.value &&
    report.value.revision === props.draft.revision &&
    report.value.version === props.published.version,
);
function valueLabel(value: ConfigValue | undefined) {
  if (value === undefined) return '未设置';
  if (value === null) return '空值';
  if (value === '') return '空字符串';
  return String(value);
}
const diffs = computed(() => {
  const before = props.published.graph,
    after = props.graph,
    changes: string[] = [];
  for (const node of after.nodes) {
    const old = before.nodes.find((n) => n.id === node.id);
    if (!old) changes.push('新增节点 ' + node.id + ' · ' + node.type);
    else {
      if (node.type !== old.type)
        changes.push('更换能力 ' + node.id + '：' + old.type + ' → ' + node.type);
      const oldCap = props.capabilities.find((cap) => cap.type === old.type);
      const newCap = props.capabilities.find((cap) => cap.type === node.type);
      const pinned = props.published.capabilities?.[old.type];
      const matched =
        pinned != null && oldCap?.version != null && String(pinned) === String(oldCap.version);
      const previous = { ...(matched ? oldCap.config : {}), ...old.config };
      const current = { ...newCap?.config, ...node.config };
      if (old.type === node.type && pinned != null && newCap?.version != null && !matched)
        changes.push(node.id + ' 能力版本：' + pinned + ' → ' + newCap.version);
      for (const k of new Set([...Object.keys(previous), ...Object.keys(current)])) {
        const unknown = !matched && !Object.hasOwn(old.config, k);
        // Neither graph supplies an override; an unavailable old default cannot prove a change.
        if (unknown && old.type === node.type && !Object.hasOwn(node.config, k)) continue;
        if (unknown || previous[k] !== current[k])
          changes.push(
            node.id +
              '.' +
              k +
              '：' +
              (unknown ? '历史默认值未知' : valueLabel(previous[k])) +
              ' → ' +
              valueLabel(current[k]),
          );
      }
      for (const k of new Set([...Object.keys(old.bindings), ...Object.keys(node.bindings)])) {
        if (old.bindings[k] !== node.bindings[k])
          changes.push(
            '映射 ' +
              node.id +
              '.' +
              k +
              '：' +
              (old.bindings[k] ?? '未连接') +
              ' → ' +
              (node.bindings[k] ?? '未连接'),
          );
      }
    }
  }
  for (const n of before.nodes)
    if (!after.nodes.some((x) => x.id === n.id)) changes.push('移除节点 ' + n.id);
  const edges = (g: GraphDefinition) =>
    new Set(g.edges.map((e) => e.from + '.' + e.port + ' → ' + e.to));
  const first = edges(before),
    second = edges(after);
  for (const e of first) if (!second.has(e)) changes.push('移除连线 ' + e);
  for (const e of second) if (!first.has(e)) changes.push('新增连线 ' + e);
  return changes;
});
async function load() {
  try {
    const [s, vs] = await Promise.all([
      workbench.suite(props.appId),
      workbench.versions(props.appId),
    ]);
    suite.value = s;
    savedCases.value = JSON.stringify(s.cases);
    versions.value = vs;
    selectedVersion.value = props.published.version;
    problem.value = '';
  } catch (e) {
    problem.value = errorText(e);
  }
}
watch(() => props.published.version, load, { immediate: true });
watch(
  () => props.changeId,
  () => {
    generation++;
    report.value = undefined;
    confirmPublish.value = false;
    working.value = false;
  },
);
watch(
  suite,
  () => {
    report.value = undefined;
    confirmPublish.value = false;
  },
  { deep: true },
);
async function saveCases() {
  if (!suite.value) return;
  working.value = true;
  problem.value = '';
  try {
    const copy = await workbench.saveSuite(props.appId, suite.value);
    suite.value = copy;
    savedCases.value = JSON.stringify(copy.cases);
  } catch (e) {
    problem.value = errorText(e);
  } finally {
    working.value = false;
  }
}
async function compare() {
  const ticket = ++generation;
  working.value = true;
  problem.value = '';
  confirmPublish.value = false;
  try {
    const data = await workbench.evaluate(props.appId, props.draft.revision, props.graph);
    if (ticket === generation) report.value = data;
  } catch (e) {
    if (ticket === generation) {
      problem.value = errorText(e);
      report.value = undefined;
    }
  } finally {
    if (ticket === generation) working.value = false;
  }
}
async function restore() {
  try {
    const v = await workbench.version(props.appId, selectedVersion.value);
    emit('restore', v.graph);
  } catch (e) {
    problem.value = errorText(e);
  }
}
</script>
<template>
  <section class="wb-section">
    <header class="wb-section-head">
      <div>
        <h2>对比与发布</h2>
        <p>比较当前草稿与 v{{ published.version }}。通过已保存样例后可发布新版本。</p>
      </div>
      <button
        class="wb-primary"
        :disabled="!approved || busy || working || draft.revision === published.revision"
        @click="confirmPublish = true"
      >
        发布新版本
      </button>
    </header>
    <p v-if="problem" class="wb-error" role="alert">
      {{ problem }}
      <button @click="load">载入套件与版本</button>
    </p>
    <div v-if="confirmPublish" class="wb-inline-warning">
      <p>将草稿 r{{ draft.revision }} 发布为新版本。正在运行的任务继续使用各自版本。</p>
      <button
        @click="
          confirmPublish = false;
          emit('publish');
        "
      >
        确认发布
      </button>
      <button @click="confirmPublish = false">取消</button>
    </div>
    <h3>业务变更</h3>
    <ul v-if="diffs.length" class="wb-diffs">
      <li v-for="diff in diffs" :key="diff">{{ diff }}</li>
    </ul>
    <p v-else>流程行为与当前发布版一致。</p>
    <details v-if="suite">
      <summary>固定样例与断言 · 套件 r{{ suite.revision }}</summary>
      <fieldset :disabled="working || busy">
        <div v-for="(f, i) in suite.cases" :key="i" class="wb-case">
          <label>
            样例名称
            <input v-model="f.name" maxlength="80" />
          </label>
          <label>
            样例文本
            <textarea v-model="f.text" rows="2" maxlength="8000" />
          </label>
          <div class="wb-actions">
            <label class="wb-check">
              <input type="checkbox" v-model="f.approve" />
              等待时全部通过
            </label>
            <label class="wb-check">
              <input type="checkbox" v-model="f.expectWait" />
              应进入人工等待
            </label>
            <label>
              预期操作数
              <input type="number" v-model.number="f.effects" min="0" max="100" />
            </label>
            <label>
              校验输出字段（可选）
              <input v-model="f.field" placeholder="如 classify.priority" />
            </label>
            <label v-if="f.field">
              预期字段值
              <input v-model="f.expectedValue" maxlength="500" />
            </label>
            <button :disabled="suite.cases.length <= 1" @click="suite.cases.splice(i, 1)">
              移除样例
            </button>
          </div>
        </div>
        <div class="wb-actions">
          <button
            :disabled="suite.cases.length >= 20"
            @click="
              suite.cases.push({
                name: '新样例',
                text: '',
                approve: true,
                expectWait: false,
                effects: 0,
              })
            "
          >
            添加样例
          </button>
          <button :disabled="!suiteDirty" @click="saveCases">保存验收样例</button>
        </div>
      </fieldset>
    </details>
    <div class="wb-section-head">
      <div>
        <h3>样例评估</h3>
        <p>
          使用注册的样例处理器；记录路径和拟执行的操作数。评估不会调用模型、创建工单或发出通知。
        </p>
      </div>
      <button :disabled="!suite || suiteDirty || working || busy" @click="compare">
        运行样例对比
      </button>
    </div>
    <template v-if="report">
      <table class="wb-table">
        <thead>
          <tr>
            <th>样例</th>
            <th>预期</th>
            <th>发布版</th>
            <th>草稿</th>
          </tr>
        </thead>
        <tbody>
          <tr v-for="c in report.cases" :key="c.name">
            <td>{{ c.name }}</td>
            <td>{{ c.expected }}</td>
            <td>{{ c.baseline.pass ? '通过' : '不符' }} · {{ c.baseline.detail }}</td>
            <td>{{ c.candidate.pass ? '通过' : '不符' }} · {{ c.candidate.detail }}</td>
          </tr>
        </tbody>
      </table>
      <p role="status">
        草稿通过 {{ report.cases.filter((c) => c.candidate.pass).length }}/{{
          report.cases.length
        }}
        · 套件 r{{ report.suiteRevision }} · {{ report.fingerprint.slice(0, 12) }}
      </p>
    </template>
    <details>
      <summary>历史发布版本</summary>
      <div class="wb-actions">
        <label>
          版本
          <select v-model.number="selectedVersion">
            <option v-for="v in versions" :value="v.version" :key="v.version">
              v{{ v.version }} · 草稿 r{{ v.revision }}
            </option>
          </select>
        </label>
        <button :disabled="busy || dirty || !selectedVersion" @click="restore">
          复制此版到编辑区
        </button>
      </div>
    </details>
  </section>
</template>
