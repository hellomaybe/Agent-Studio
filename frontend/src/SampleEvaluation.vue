<script setup lang="ts">
import { computed, ref, watch } from 'vue';

import { samples, studioApi, type StudioConfig, type StudioPreview } from './api';

const props = defineProps<{
  baseline: StudioConfig;
  candidate: StudioConfig;
  revision: number;
}>();
const loading = ref(false);
const error = ref('');
interface Score {
  pass: boolean;
  label: string;
}
const rows = ref<{ base: Score; candidate: Score }[]>([]);
const sampleRows = computed(() =>
  samples.map((sample, index) => ({ ...sample, scores: rows.value[index] })),
);
const passes = computed(() => ({
  base: rows.value.filter((row) => row.base.pass).length,
  candidate: rows.value.filter((row) => row.candidate.pass).length,
}));
let epoch = 0;
watch(
  () => [props.baseline, props.candidate],
  () => {
    epoch++;
    rows.value = [];
    loading.value = false;
    error.value = '';
  },
  { deep: true },
);
function score(value: StudioPreview, count: number): Score {
  const needsConfirmation = value.path.includes('confirm');
  const expectedConfirmation = count > 0;
  return {
    pass: value.items.length === count && needsConfirmation === expectedConfirmation,
    label: `${value.items.length} 项 · ${needsConfirmation ? '等待确认' : '直接结束'}`,
  };
}
async function compare() {
  const current = ++epoch;
  loading.value = true;
  error.value = '';
  try {
    const result = await Promise.all(
      samples.map(async (sample) => {
        const [base, candidate] = await Promise.all([
          studioApi.preview(props.baseline, sample.key),
          studioApi.preview(props.candidate, sample.key),
        ]);
        return {
          base: score(base, sample.count),
          candidate: score(candidate, sample.count),
        };
      }),
    );
    if (current === epoch) rows.value = result;
  } catch (error_) {
    if (current === epoch) error.value = error_ instanceof Error ? error_.message : '评估失败';
  } finally {
    if (current === epoch) loading.value = false;
  }
}
</script>
<template>
  <section class="assessment">
    <header>
      <div>
        <h3>样例评估</h3>
        <p>
          对比已保存的 r{{ revision }}
          与当前编辑内容。固定样例验证待办数量和分支路径，不调用模型。
        </p>
      </div>
      <el-button type="primary" :loading="loading" @click="compare">运行对比</el-button>
    </header>
    <el-alert v-if="error" :title="error" type="error" :closable="false" />
    <el-table :data="sampleRows" row-key="key">
      <el-table-column prop="label" label="输入样例" min-width="100" />
      <el-table-column label="预期结果" min-width="138">
        <template #default="{ row }">
          {{ row.count }} 项 ·
          {{ row.count ? '等待确认' : '直接结束' }}
        </template>
      </el-table-column>
      <el-table-column
        v-for="side in ['base', 'candidate'] as const"
        :key="side"
        :label="side === 'base' ? '已保存版本' : '当前草稿'"
        min-width="174"
      >
        <template #default="{ row }">
          <template v-if="row.scores">
            <el-tag :type="row.scores[side].pass ? 'success' : 'warning'" size="small">
              {{ row.scores[side].pass ? '通过' : '未通过' }}
            </el-tag>
            {{ row.scores[side].label }}
          </template>
          <span v-else>待评估</span>
        </template>
      </el-table-column>
    </el-table>
    <p v-if="rows.length > 0" role="status">
      已保存版本通过 {{ passes.base }}/{{ samples.length }}，当前草稿通过 {{ passes.candidate }}/{{
        samples.length
      }}。
    </p>
  </section>
</template>
<style scoped>
.assessment {
  padding: 22px;
}

.assessment header {
  display: flex;
  flex-wrap: wrap;
  gap: 12px;
  align-items: center;
  justify-content: space-between;
  margin-bottom: 18px;
}

.assessment h3 {
  margin: 0;
  font-size: 18px;
}

.assessment p {
  margin: 6px 0;
  font-size: 13px;
  color: var(--el-text-color-secondary);
}
</style>
