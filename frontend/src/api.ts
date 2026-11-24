import { request } from './http';
export { ApiError } from './http';

const base = '/api/studio/meeting-notes';

export interface StudioConfig {
  mapping: 'sanitize.text' | 'start.title' | 'start.transcript';
  prompt: string;
  sanitize: boolean;
  threshold: number;
}

export interface StudioDraft {
  config: StudioConfig;
  liveEnabled: boolean;
  revision: number;
}

export const samples = [
  { key: 'weekly', label: '产品周会', count: 2 },
  { key: 'empty', label: '信息同步会', count: 0 },
  { key: 'ops', label: '运营复盘', count: 1 },
] as const;
export type SampleKey = (typeof samples)[number]['key'];
export interface ActionItem {
  id: string;
  text: string;
}
export interface StudioPreview {
  cleanedText: string;
  items: ActionItem[];
  path: string[];
  title: string;
  transcript: string;
}
export interface StudioRun {
  config: StudioConfig;
  createdAt: string;
  error?: string;
  items: ActionItem[];
  mode: 'live' | 'sample';
  path: string[];
  revision: number;
  runId: string;
  sampleKey?: SampleKey;
  selectedIds: string[];
  status: 'completed' | 'failed' | 'running' | 'waiting';
  title: string;
  usage?: null | {
    inputTokens: number;
    model: string;
    outputTokens: number;
  };
}
export interface RunInput {
  mode: 'live' | 'sample';
  requestId: string;
  revision: number;
  sampleKey?: SampleKey;
  title?: string;
  transcript?: string;
}

function call<T>(path: string, method = 'GET', data?: unknown): Promise<T> {
  return request<T>(base + path, method, data);
}

export const studioApi = {
  draft: () => call<StudioDraft>('/draft'),
  save: (revision: number, config: StudioConfig) =>
    call<StudioDraft>('/draft', 'PUT', { revision, config }),
  preview: (config: StudioConfig, sampleKey: SampleKey) =>
    call<StudioPreview>('/preview', 'POST', { config, sampleKey }),
  start: (input: RunInput) => call<StudioRun>('/runs', 'POST', input),
  runs: () => call<StudioRun[]>('/runs'),
  run: (id: string) => call<StudioRun>('/runs/' + encodeURIComponent(id)),
  confirm: (id: string, selectedIds: string[]) =>
    call<StudioRun>('/runs/' + encodeURIComponent(id) + '/confirm', 'POST', {
      selectedIds,
    }),
};

export const nodeLabels: Record<string, string> = {
  start: '会议文本',
  sanitize: '文本清洗',
  extract: '提取待办',
  gate: '有待办？',
  confirm: '用户确认',
  create: '创建任务预览',
  end: '输出结果',
};

export const statusLabels: Record<StudioRun['status'], string> = {
  completed: '已完成',
  failed: '失败',
  running: '执行中',
  waiting: '等待确认',
};
