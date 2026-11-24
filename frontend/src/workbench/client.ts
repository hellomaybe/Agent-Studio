import { request } from '../http';
import type { Capability, GraphDefinition } from './types';

export interface AppInfo {
  id: string;
  name: string;
  revision: number;
  version: number;
}
export interface Published {
  appId: string;
  version: number;
  revision: number;
  graph: GraphDefinition;
  capabilities?: Record<string, string | number>;
}
export interface Draft extends Published {
  liveEnabled?: boolean;
}
export interface Fixture {
  id: string;
  label: string;
  text: string;
}
export type Fields = Record<string, unknown>;
export interface DebugResult {
  nodeId: string;
  mode: string;
  outputs: Fields;
  port: string;
  waiting: boolean;
  effects: Fields[];
  durationMs: number;
  usage?: { model?: string; inputTokens?: number; outputTokens?: number } | null;
}
export interface HistoricalInput {
  nodeId: string;
  versionId: number;
  inputs: Fields;
  graph: GraphDefinition;
}
export interface RunEvent {
  waitMs?: number;
  seq: number;
  type: string;
  nodeId?: string;
  nodeType?: string;
  status?: string;
  attemptNo?: number;
  activationNo?: number;
  port?: string;
  nextNodeId?: string;
  occurredAt: string;
  elapsedMs?: number;
  errorCode?: string;
  inputFields?: string[];
  outputFields?: string[];
  operationKey?: string;
  usage?: DebugResult['usage'];
}

export function tokenCount(value: number | null | undefined): string {
  return typeof value === 'number' && value >= 0 ? String(value) : '未知';
}
export interface RunView {
  eventCount?: number;
  eventsTruncated?: boolean;
  runId: string;
  appId: string;
  versionId: number;
  status: string;
  currentNodeId?: string;
  pauseRequested: boolean;
  output?: Fields;
  events: RunEvent[];
  errorCode?: string;
  mode?: string;
  createdAt: string;
  updatedAt: string;
  graph: GraphDefinition;
  path: string[];
  effects: Fields[];
  expiresAt?: string;
  canRetry?: boolean;
  canReinput?: boolean;
  reviewed?: boolean;
  receiptFingerprint?: string;
  parentRunId?: string;
  wait?: {
    waitId: string;
    nodeId: string;
    prompt: string;
    items: { id: string; title?: string; text?: string }[];
  } | null;
}
export interface EvaluationCase {
  name: string;
  expected: string;
  baseline: { pass: boolean; detail: string };
  candidate: { pass: boolean; detail: string };
}
export type RunSummary = Pick<
  RunView,
  'runId' | 'appId' | 'versionId' | 'status' | 'mode' | 'createdAt'
>;
export interface Evaluation {
  revision: number;
  version: number;
  suiteRevision: number;
  fingerprint: string;
  cases: EvaluationCase[];
}
export interface CheckFixture {
  field?: string;
  expectedValue?: string;
  name: string;
  text: string;
  approve: boolean;
  expectWait: boolean;
  effects: number;
}
export interface Suite {
  revision: number;
  cases: CheckFixture[];
}
const path = (app: string) => '/api/apps/' + encodeURIComponent(app);
export const workbench = {
  apps: () => request<AppInfo[]>('/api/apps'),
  nodes: () => request<Capability[]>('/api/nodes'),
  draft: (app: string) => request<Draft>(path(app) + '/draft'),
  samples: (app: string) => request<Fixture[]>(path(app) + '/samples'),
  version: (app: string, version: number) => request<Published>(path(app) + '/versions/' + version),
  versions: (app: string) =>
    request<{ version: number; revision: number }[]>(path(app) + '/versions'),
  suite: (app: string) => request<Suite>(path(app) + '/cases'),
  saveSuite: (app: string, suite: Suite) => request<Suite>(path(app) + '/cases', 'PUT', suite),
  save: (app: string, revision: number, graph: GraphDefinition) =>
    request<Draft>(path(app) + '/draft', 'PUT', { revision, graph }),
  publish: (app: string, revision: number) =>
    request<Published>(path(app) + '/publish', 'POST', { revision }),
  debug: (app: string, payload: Fields) =>
    request<DebugResult>(path(app) + '/debug', 'POST', payload),
  evaluate: (app: string, revision: number, graph: GraphDefinition) =>
    request<Evaluation>(path(app) + '/evaluations', 'POST', { revision, graph }),
  runs: (app: string) => request<RunSummary[]>(path(app) + '/runs'),
  run: (app: string, id: string) => request<RunView>(path(app) + '/runs/' + encodeURIComponent(id)),
  debugInput: (app: string, id: string, nodeId: string) =>
    request<Omit<HistoricalInput, 'graph'>>(
      path(app) + '/runs/' + encodeURIComponent(id) + '/debug-input',
      'POST',
      { nodeId, includeContent: true },
    ),
  events: (app: string, id: string, after: number) =>
    request<{ events: RunEvent[]; nextCursor: number; hasMore: boolean }>(
      path(app) + '/runs/' + encodeURIComponent(id) + '/events?after=' + after + '&limit=100',
    ),
  start: (app: string, data: Fields) => request<RunView>(path(app) + '/runs', 'POST', data),
  signal: (
    app: string,
    id: string,
    action: 'pause' | 'continue' | 'resume' | 'retry' | 'reconcile' | 'reinput',
    data: Fields,
  ) => request<RunView>(path(app) + '/runs/' + encodeURIComponent(id) + '/' + action, 'POST', data),
};

export function newId(): string {
  return Array.from(crypto.getRandomValues(new Uint8Array(16)), (x) =>
    x.toString(16).padStart(2, '0'),
  ).join('');
}
