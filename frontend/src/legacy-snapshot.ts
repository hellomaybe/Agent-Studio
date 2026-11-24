import type { StudioConfig, StudioRun } from './api';
import type { Capability, GraphDefinition, GraphEdge, GraphNode } from './workbench/types';

type LegacyRecord = Partial<Omit<StudioRun, 'config'>> & { config?: Partial<StudioConfig> | null };

// These are the fixed operations of the legacy meeting service. The namespaced
// types must never be passed to the current capability registry for execution.
function operation(
  type: string,
  label: string,
  inputs: Record<string, string>,
  outputs: Record<string, string>,
  ports: string[],
): Capability {
  return { type: `legacy.${type}`, label, inputs, outputs, ports, config: {} };
}
const operations = [
  operation('input', '会议文本', {}, { transcript: 'TEXT', title: 'TEXT' }, ['next']),
  operation('sanitize', '文本清洗', { text: 'TEXT' }, { text: 'TEXT' }, ['next']),
  operation('extract', '提取待办', { text: 'TEXT' }, { items: 'LEGACY_ITEMS' }, ['next']),
  operation('threshold', '有待办？', { items: 'LEGACY_ITEMS' }, { matched: 'BOOLEAN' }, [
    'yes',
    'no',
  ]),
  operation('confirm', '用户确认', { items: 'LEGACY_ITEMS' }, { selectedIds: 'ID_LIST' }, [
    'yes',
    'no',
  ]),
  operation(
    'task-preview',
    '创建任务预览',
    { items: 'LEGACY_ITEMS', selectedIds: 'ID_LIST' },
    { preview: 'LEGACY_ITEMS' },
    ['next'],
  ),
  operation('end', '输出结果', {}, {}, []),
];

export function legacySnapshot(run: LegacyRecord) {
  const config = run.config;
  const unknown: string[] = [];
  const sanitize = typeof config?.sanitize === 'boolean' ? config.sanitize : null;
  const mapping = typeof config?.mapping === 'string' ? config.mapping : null;
  const prompt = typeof config?.prompt === 'string' ? config.prompt : null;
  const threshold =
    typeof config?.threshold === 'number' && Number.isInteger(config.threshold)
      ? config.threshold
      : null;
  const path = Array.isArray(run.path) ? [...run.path] : null;
  const selectedIds = Array.isArray(run.selectedIds) ? [...run.selectedIds] : null;
  for (const [name, value] of Object.entries({ sanitize, mapping, prompt, threshold })) {
    if (value === null) unknown.push(`config.${name}`);
  }
  if (!path) unknown.push('history.path');
  if (!selectedIds) unknown.push('history.selectedIds');
  if (!run.runId) unknown.push('source.runId');
  if (run.revision == null) unknown.push('source.draftRevision');
  if (!run.mode) unknown.push('source.mode');
  if (run.mode === 'sample' && !run.sampleKey) unknown.push('source.sampleKey');
  if (!run.status) unknown.push('history.status');

  const nodes: GraphNode[] = [
    { id: 'start', type: 'legacy.input', config: {}, bindings: {} },
    ...(sanitize === true || path?.includes('sanitize')
      ? [
          {
            id: 'sanitize',
            type: 'legacy.sanitize',
            config: { rule: 'Collapse Unicode whitespace and BOM to an ASCII space, then trim.' },
            bindings: { text: 'start.transcript' },
          },
        ]
      : []),
    {
      id: 'extract',
      type: 'legacy.extract',
      config: {
        prompt,
        sampleRule:
          'For start.title return no items; otherwise use the fixed tasks in the selected sample.',
        liveRule: 'Call the model with the configured prompt and mapped text.',
      },
      bindings: mapping ? { text: mapping } : {},
    },
    {
      id: 'gate',
      type: 'legacy.threshold',
      config: { operator: 'count >= threshold', threshold },
      bindings: { items: 'extract.items' },
    },
    {
      id: 'confirm',
      type: 'legacy.confirm',
      config: { yes: 'one or more selected IDs', no: 'zero selected IDs' },
      bindings: { items: 'extract.items' },
    },
    {
      id: 'create',
      type: 'legacy.task-preview',
      config: { effect: 'preview only' },
      bindings: { items: 'extract.items', selectedIds: 'confirm.selectedIds' },
    },
    { id: 'end', type: 'legacy.end', config: {}, bindings: {} },
  ];
  const edges: GraphEdge[] = [
    ...(sanitize === null
      ? []
      : [{ from: 'start', port: 'next', to: sanitize ? 'sanitize' : 'extract' }]),
    ...(nodes.some((n) => n.id === 'sanitize')
      ? [{ from: 'sanitize', port: 'next', to: 'extract' }]
      : []),
    { from: 'extract', port: 'next', to: 'gate' },
    { from: 'gate', port: 'yes', to: 'confirm' },
    { from: 'gate', port: 'no', to: 'end' },
    { from: 'confirm', port: 'yes', to: 'create' },
    { from: 'confirm', port: 'no', to: 'end' },
    { from: 'create', port: 'next', to: 'end' },
  ];
  // History is evidence in its own right. Never reconstruct the recorded path
  // from today's draft, the count of extracted items or the latest selection.
  const transitions =
    path?.slice(1).map((to, index) => {
      const from = path[index]!;
      const known = edges.find((edge) => edge.from === from && edge.to === to);
      if (!known) unknown.push(`history.transitions[${index}].port`);
      return { from, to, port: known?.port ?? null };
    }) ?? null;
  return {
    format: 'legacy-meeting-run/v1',
    readOnly: true,
    source: {
      runId: run.runId ?? null,
      draftRevision: run.revision ?? null,
      mode: run.mode ?? null,
      sampleKey: run.sampleKey ?? null,
    },
    config: { sanitize, mapping, prompt, threshold },
    operations,
    graph: { nodes, edges } satisfies GraphDefinition,
    history: { status: run.status ?? null, path, transitions, selectedIds },
    unknown,
  };
}
