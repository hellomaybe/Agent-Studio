import { expect, test } from '@playwright/test';
import type { Page } from '@playwright/test';
import type { RunInput, StudioRun } from '../src/api';

const api = '**/api/studio/meeting-notes';
const config = {
  mapping: 'start.transcript',
  prompt: 'Find tasks',
  sanitize: false,
  threshold: 1,
} as const;
const path = ['start', 'extract', 'gate', 'confirm'];
const items = [
  { id: 'first', text: '制作样稿' },
  { id: 'second', text: '核对数据' },
];

function run(runId: string, status: 'waiting' | 'completed'): StudioRun {
  return {
    runId,
    title: runId,
    status,
    revision: 1,
    mode: 'sample',
    sampleKey: 'weekly',
    createdAt: '2026-01-01T10:00:00Z',
    items,
    config,
    path: status === 'waiting' ? path : [...path, 'create', 'end'],
    selectedIds: status === 'waiting' ? [] : ['first'],
  };
}

async function openStudio(page: Page) {
  await page.route(api + '/draft', (route) =>
    route.fulfill({ json: { code: 200, data: { revision: 1, config, liveEnabled: false } } }),
  );
  await page.route(api + '/preview', (route) =>
    route.fulfill({
      json: {
        code: 200,
        data: { path, items, title: '会议', transcript: '会议', cleanedText: '会议' },
      },
    }),
  );
  await page.goto('/#/legacy');
}

test('refresh preserves the manually inspected run and graph node', async ({ page }) => {
  const latest = run('本次试跑', 'waiting');
  const prior = run('较早的运行', 'completed');
  await page.route(api + '/runs', (route) =>
    route.fulfill({
      json: { code: 200, data: route.request().method() === 'POST' ? latest : [latest, prior] },
    }),
  );
  await openStudio(page);
  await page.getByRole('button', { name: '试运行', exact: true }).click();
  await page.getByRole('button', { name: '稍后处理' }).click();
  await page.getByRole('button', { name: '运行记录', exact: true }).click();
  const older = page.getByRole('button', { name: /较早的运行/ });
  await older.click();
  const gate = page.getByRole('button', { name: /^有待办？/ });
  await gate.click();
  const done = page.waitForResponse(
    (response) => response.url().endsWith('/runs') && response.request().method() === 'GET',
  );
  await page.getByRole('button', { name: '刷新记录' }).click();
  await done;
  await expect(older).toHaveAttribute('aria-pressed', 'true');
  await expect(gate).toHaveAttribute('aria-pressed', 'true');
});

test('a concurrent confirmation reopens the authoritative result', async ({ page }) => {
  const waiting = run('同时确认', 'waiting');
  const completed = run(waiting.runId, 'completed');
  await page.route(api + '/runs', (route) =>
    route.fulfill({
      json: { code: 200, data: route.request().method() === 'POST' ? waiting : [completed] },
    }),
  );
  await page.route(api + '/runs/*/confirm', (route) =>
    route.fulfill({ status: 409, json: { code: 409, msg: '当前运行已经结束。' } }),
  );
  await page.route(api + '/runs/*', (route) =>
    route.fulfill({ json: { code: 200, data: completed } }),
  );
  await openStudio(page);
  await page.getByRole('button', { name: '试运行', exact: true }).click();
  await page.getByRole('button', { name: '确认 2 项' }).click();
  await expect(page.getByRole('dialog')).toHaveCount(0);
  await expect(page.getByText(/任务预览共 1 项/)).toContainText('制作样稿');
});

test('retry reuses its ID after a different sample was submitted', async ({ page }) => {
  const requests: RunInput[] = [];
  await page.route(api + '/runs', async (route) => {
    requests.push(route.request().postDataJSON());
    if (requests.length === 1) return route.abort('failed');
    return route.fulfill({ json: { code: 200, data: run(String(requests.length), 'completed') } });
  });
  await openStudio(page);
  const start = page.getByRole('button', { name: '试运行', exact: true });
  await start.click();
  await expect(page.getByRole('alert')).toContainText('重试同一请求');
  await page.getByLabel('固定输入样例').selectOption('empty');
  await start.click();
  await expect(page.getByText('已完成', { exact: true })).toBeVisible();
  await page.getByLabel('固定输入样例').selectOption('weekly');
  await start.click();
  await expect.poll(() => requests.length).toBe(3);
  expect(requests[0]!.requestId).toBe(requests[2]!.requestId);
  expect(requests[0]!.requestId).not.toBe(requests[1]!.requestId);
});
