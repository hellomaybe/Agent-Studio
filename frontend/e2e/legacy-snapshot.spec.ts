import { expect, test } from '@playwright/test';
import { legacySnapshot } from '../src/legacy-snapshot';
import type { StudioConfig } from '../src/api';

const config: StudioConfig = {
  sanitize: false,
  mapping: 'start.title',
  threshold: 2,
  prompt: '保留原始提示词。',
};
const prefix = ['start', 'extract', 'gate'];

test('legacy approval and zero-selection receipts remain distinct from a gate bypass', () => {
  const selected = legacySnapshot({
    config,
    status: 'completed',
    path: [...prefix, 'confirm', 'create', 'end'],
    selectedIds: ['task-2'],
  });
  const skipped = legacySnapshot({
    config,
    status: 'completed',
    path: [...prefix, 'confirm', 'end'],
    selectedIds: [],
  });
  const bypass = legacySnapshot({
    config,
    status: 'completed',
    path: [...prefix, 'end'],
    selectedIds: [],
  });
  expect(selected.format).toBe('legacy-meeting-run/v1');
  expect(selected.history.selectedIds).toEqual(['task-2']);
  expect(selected.history.transitions).toContainEqual({
    from: 'confirm',
    port: 'yes',
    to: 'create',
  });
  expect(skipped.history.transitions).toContainEqual({ from: 'confirm', port: 'no', to: 'end' });
  expect(skipped.history.path).not.toContain('create');
  expect(bypass.history.transitions).toContainEqual({ from: 'gate', port: 'no', to: 'end' });
  expect(bypass.history.path).not.toContain('confirm');
  expect(selected.graph.nodes.find((n) => n.id === 'gate')?.config.threshold).toBe(2);
  expect(selected.graph.nodes.find((n) => n.id === 'extract')).toMatchObject({
    config: { prompt: config.prompt },
    bindings: { text: 'start.title' },
  });
  expect(selected.graph.nodes.some((n) => n.id === 'sanitize')).toBe(false);
});

test('optional cleaning preserves the chosen source field and stops at a historical wait', () => {
  const saved = {
    config: { ...config, sanitize: true, mapping: 'sanitize.text' as const },
    status: 'waiting' as const,
    path: ['start', 'sanitize', 'extract', 'gate', 'confirm'],
    selectedIds: [],
  };
  const snapshot = legacySnapshot(saved);
  expect(snapshot.graph.nodes.find((n) => n.id === 'extract')?.bindings.text).toBe('sanitize.text');
  expect(snapshot.graph.edges).toContainEqual({ from: 'start', port: 'next', to: 'sanitize' });
  expect(snapshot.history.path?.at(-1)).toBe('confirm');
  expect(snapshot.history.transitions?.some((e) => e.from === 'confirm')).toBe(false);
  expect(legacySnapshot(saved)).toEqual(snapshot);
  const raw = legacySnapshot({
    ...saved,
    config: { ...saved.config, mapping: 'start.transcript' },
  });
  expect(raw.graph.nodes.find((n) => n.id === 'extract')?.bindings.text).toBe('start.transcript');
});

test('information not saved in the old run is unknown and never substituted from a current draft', () => {
  const empty = legacySnapshot({ config: null });
  expect(empty.unknown).toEqual(
    expect.arrayContaining([
      'config.sanitize',
      'config.prompt',
      'config.threshold',
      'config.mapping',
      'history.path',
      'history.selectedIds',
    ]),
  );
  expect(empty.history.path).toBeNull();
  expect(empty.history.transitions).toBeNull();
  expect(empty.graph.edges.some((e) => e.from === 'start')).toBe(false);
  expect(empty.graph.nodes.find((n) => n.id === 'extract')?.bindings).toEqual({});
  const unexpected = legacySnapshot({ config, path: ['start', 'historical-extension', 'end'] });
  expect(unexpected.history.path).toEqual(['start', 'historical-extension', 'end']);
  expect(unexpected.history.transitions?.[0]?.port).toBeNull();
});

test('the history view exposes its old snapshot and copies it without modifying a draft', async ({
  page,
  context,
}) => {
  const api = '**/api/studio/meeting-notes';
  await page.route(api + '/draft', (route) =>
    route.fulfill({
      json: {
        code: 200,
        data: { revision: 32, config: { ...config, prompt: '最新草稿' }, liveEnabled: false },
      },
    }),
  );
  await page.route(api + '/preview', (route) =>
    route.fulfill({
      json: {
        code: 200,
        data: { title: '', transcript: '', cleanedText: '', items: [], path: [] },
      },
    }),
  );
  await page.route(api + '/runs', (route) =>
    route.fulfill({
      json: {
        code: 200,
        data: [
          {
            runId: 'old-run',
            title: '旧记录',
            revision: 2,
            status: 'completed',
            mode: 'sample',
            sampleKey: 'weekly',
            config,
            createdAt: '2026-01-20T10:00:00Z',
            items: [],
            selectedIds: [],
            path: [...prefix, 'confirm', 'end'],
          },
        ],
      },
    }),
  );
  await page.goto('/#/legacy');
  await page.getByRole('button', { name: '运行记录', exact: true }).click();
  await page.getByText('查看旧版只读图定义', { exact: true }).click();
  const snapshot = await page.getByLabel('旧版只读图定义', { exact: true }).inputValue();
  expect(JSON.parse(snapshot)).toMatchObject({
    readOnly: true,
    source: { draftRevision: 2 },
    config: { prompt: config.prompt },
    history: { path: [...prefix, 'confirm', 'end'] },
  });
  await context.grantPermissions(['clipboard-read', 'clipboard-write']);
  await page.getByRole('button', { name: '复制旧版图快照' }).click();
  expect(JSON.parse(await page.evaluate(() => navigator.clipboard.readText()))).toEqual(
    JSON.parse(snapshot),
  );
});
