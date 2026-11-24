import { expect, test } from '@playwright/test';
import type { Page, Route } from '@playwright/test';

const app = 'meeting-notes';
const root = '/api/apps/' + app;
const graph = {
  nodes: ['start', 'end'].map((id) => ({ id, type: id, config: {}, bindings: {} })),
  edges: [{ from: 'start', port: 'next', to: 'end' }],
};
const published = { appId: app, revision: 1, version: 1, graph };
const view = (runId: string) => ({
  runId,
  appId: app,
  mode: 'sample',
  versionId: 1,
  status: 'paused',
  currentNodeId: 'end',
  events: [],
  path: ['start', 'end'],
  graph,
  effects: [],
  pauseRequested: false,
  createdAt: '2026-01-01T00:00:00Z',
});

function deferred() {
  let resolve!: () => void;
  const promise = new Promise<void>((done) => {
    resolve = done;
  });
  return { promise, resolve };
}
const data = (route: Route, value: unknown) => route.fulfill({ json: { data: value } });
async function setup(page: Page) {
  await page.route('**/api/**', (route) => {
    const p = new URL(route.request().url()).pathname;
    if (p === '/api/apps')
      return data(route, [{ id: app, name: 'Meeting', revision: 1, version: 1 }]);
    if (p === '/api/nodes')
      return data(
        route,
        graph.nodes.map((n) => ({
          type: n.type,
          label: n.id,
          version: '1',
          inputs: {},
          outputs: {},
          ports: n.id === 'end' ? [] : ['next'],
          config: {},
          exampleInputs: {},
        })),
      );
    if (p === root + '/draft') return data(route, { ...published, liveEnabled: false });
    if (p === root + '/versions/1') return data(route, published);
    if (p === root + '/versions') return data(route, [{ version: 1, revision: 1 }]);
    if (p === root + '/cases') return data(route, { revision: 1, cases: [] });
    if (p === root + '/samples') return data(route, [{ id: 's', label: 'Sample', text: 'test' }]);
    if (p === root + '/runs') return data(route, [view('run-a'), view('run-b')]);
    if (p === root + '/runs/run-a' || p === root + '/runs/run-b')
      return data(route, view(p.split('/').at(-1)!));
    return route.abort('failed');
  });
}
async function openA(page: Page) {
  await page.goto('/#/apps/meeting-notes/runs/run-a');
  await expect(page.locator('.wb-run-details header small')).toHaveText('run-a');
}
async function openB(page: Page) {
  await page.getByRole('button', { name: /run-b/ }).click();
  await expect(page.locator('.wb-run-details header small')).toHaveText('run-b');
}
async function painted(page: Page) {
  await page.evaluate(
    () =>
      new Promise<void>((done) => requestAnimationFrame(() => requestAnimationFrame(() => done()))),
  );
}

test('a late signal cannot replace the newly selected run or unlock its pending signal', async ({
  page,
}) => {
  await setup(page);
  const entered = { a: deferred(), b: deferred() },
    release = { a: deferred(), b: deferred() };
  const requests: string[] = [];
  await page.route('**/runs/*/continue', async (route) => {
    const id = route.request().url().includes('/run-a/') ? 'a' : 'b';
    requests.push(route.request().postDataJSON().signalId);
    entered[id].resolve();
    await release[id].promise;
    await data(route, { ...view('run-' + id), status: 'completed' });
  });
  await openA(page);
  const resume = page.getByRole('button', { name: '继续执行', exact: true });
  await resume.click();
  await entered.a.promise;
  await openB(page);
  await resume.click();
  await entered.b.promise;
  const stale = page.waitForResponse((r) => r.url().endsWith('/run-a/continue'));
  release.a.resolve();
  await stale;
  await painted(page);
  await expect(page.locator('.wb-run-details header small')).toHaveText('run-b');
  await expect(resume).toBeDisabled();
  const ok = page.waitForResponse((r) => r.url().endsWith('/run-b/continue'));
  release.b.resolve();
  await ok;
  expect(new Set(requests).size).toBe(2);
});

test('returning to a run may retry an unacknowledged signal with its original ID', async ({
  page,
}) => {
  await setup(page);
  const sent = deferred(),
    release = deferred();
  const ids: string[] = [];
  await page.route('**/runs/run-a/continue', async (route) => {
    ids.push(route.request().postDataJSON().signalId);
    if (ids.length === 1) {
      sent.resolve();
      await release.promise;
      await route.abort('failed');
    } else {
      await data(route, view('run-a'));
    }
  });
  await openA(page);
  const resume = page.getByRole('button', { name: '继续执行', exact: true });
  await resume.click();
  await sent.promise;
  await openB(page);
  const failure = page.waitForEvent('requestfailed', (r) => r.url().endsWith('/run-a/continue'));
  release.resolve();
  await failure;
  await page.getByRole('button', { name: /run-a/ }).click();
  await expect(page.locator('.wb-run-details header small')).toHaveText('run-a');
  await resume.click();
  await expect.poll(() => ids.length).toBe(2);
  expect(ids[1]).toBe(ids[0]);
});

for (const change of ['node', 'run'] as const) {
  test(`a late historical input cannot open the debugger after a ${change} change`, async ({
    page,
  }) => {
    await setup(page);
    const entered = deferred(),
      release = deferred();
    await page.route('**/runs/run-a/debug-input', async (route) => {
      entered.resolve();
      await release.promise;
      await data(route, { nodeId: 'start', versionId: 1, inputs: { text: 'private input A' } });
    });
    await openA(page);
    const select = page.getByRole('combobox', { name: /^查看节点/ });
    const read = page.getByRole('button', { name: '读取该次输入并固定到调试台' });
    await select.selectOption('start');
    await read.click();
    await entered.promise;
    if (change === 'run') await openB(page);
    await select.selectOption(change === 'run' ? 'start' : 'end');
    await expect(read).toBeEnabled();
    const stale = page.waitForResponse((r) => r.url().endsWith('/run-a/debug-input'));
    release.resolve();
    await stale;
    await painted(page);
    await expect(page.getByRole('button', { name: '运行与恢复', exact: true })).toHaveAttribute(
      'aria-pressed',
      'true',
    );
    await expect(select).toHaveValue(change === 'run' ? 'start' : 'end');
  });
}
