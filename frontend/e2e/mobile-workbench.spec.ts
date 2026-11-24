import { devices, expect, test } from '@playwright/test';
import type { Page } from '@playwright/test';

test.use({ ...devices['Pixel 7'], viewport: { width: 360, height: 800 } });

async function fitsScreen(page: Page) {
  const dimensions = await page.evaluate(() => ({
    viewport: document.documentElement.clientWidth,
    page: document.documentElement.scrollWidth,
  }));
  expect(dimensions.viewport).toBe(360);
  expect(dimensions.page).toBeLessThanOrEqual(dimensions.viewport + 1);
}

test('the initial phone canvas fits both branches and exposes tap-sized zoom controls', async ({
  page,
}) => {
  await page.goto('/#/apps/meeting-notes');
  const canvas = page.getByRole('region', { name: '业务流程画布' });
  await expect(canvas.getByRole('button', { name: /节点 filter$/ })).toBeVisible();
  await expect
    .poll(() =>
      canvas.evaluate((section) => {
        const bounds = section.querySelector('.vue-flow')!.getBoundingClientRect();
        const nodes = [...section.querySelectorAll<HTMLButtonElement>('.graph-node')];
        return (
          nodes.length > 0 &&
          nodes.every((node) => {
            const box = node.getBoundingClientRect();
            return box.width > 0 && box.left >= bounds.left - 1 && box.right <= bounds.right + 1;
          })
        );
      }),
    )
    .toBe(true);
  for (const name of ['缩小', '放大', '适应画布']) {
    const box = await canvas.getByRole('button', { name, exact: true }).boundingBox();
    expect(box!.width).toBeGreaterThanOrEqual(44);
    expect(box!.height).toBeGreaterThanOrEqual(44);
  }
  // Tap from the graph while its inspector is below the fold.
  await canvas.getByRole('button', { name: /节点 filter$/ }).tap();
  const settings = page.getByRole('complementary', { name: '节点配置' });
  await expect(settings.getByLabel('进入确认的最少条数')).toBeInViewport();
  await settings.getByRole('button', { name: '返回画布' }).tap();
  await expect(canvas.getByRole('button', { name: /节点 start$/ })).toBeInViewport();
  await fitsScreen(page);
});

test('a narrow touch viewport can select and edit a feedback rule and its debug input', async ({
  page,
}) => {
  await page.goto('/#/apps/feedback-triage');
  await page.getByRole('button', { name: /节点 classify/ }).tap();
  const keywords = page.getByLabel('优先处理关键词（逗号分隔）');
  await expect(keywords).toBeInViewport();
  await keywords.fill('urgent,critical');
  await expect(page.getByRole('button', { name: '保存图草稿' })).toBeEnabled();
  await page.getByRole('button', { name: '调试此节点' }).tap();
  const input = page.getByLabel('节点输入 JSON');
  await input.fill(JSON.stringify({ text: 'critical: test issue' }));
  await page.getByRole('button', { name: '试跑草稿节点' }).tap();
  await expect(page.locator('.wb-results pre').first()).toContainText('"urgent": true');
  await fitsScreen(page);
});

test('a phone can open a collapsed replay, select its branch, and rotate to landscape', async ({
  page,
}) => {
  const app = 'feedback-triage';
  const version = (await (await page.request.get(`/api/apps/${app}/versions/1`)).json()).data;
  const id = 'mobile-layout-fixture';
  // Use the real published graph but do not add a run to the shared suite database.
  await page.route(`**/api/apps/${app}/runs/${id}`, (r) =>
    r.fulfill({
      json: {
        data: {
          appId: app,
          runId: id,
          versionId: 1,
          graph: version.graph,
          status: 'completed',
          mode: 'sample',
          path: ['start', 'classify', 'route', 'review', 'ticket', 'end'],
          events: [],
          effects: [],
          currentNodeId: 'end',
          pauseRequested: false,
          createdAt: '2026-01-01T00:00:00Z',
          updatedAt: '2026-01-01T00:00:01Z',
        },
      },
    }),
  );
  await page.goto(`/#/apps/${app}/runs/${id}`);
  await page.getByText(/查看本次运行的图/).tap();
  const canvas = page.locator('.wb-run-details .graph-canvas');
  const ticket = canvas.getByRole('button', { name: /节点 ticket/ });
  await ticket.tap();
  await expect(ticket).toHaveAttribute('aria-pressed', 'true');
  await page.setViewportSize({ width: 800, height: 360 });
  const zoom = canvas.getByRole('button', { name: '缩小', exact: true });
  await zoom.tap();
  const rect = await zoom.boundingBox();
  expect(rect!.width).toBeGreaterThanOrEqual(44);
  expect(rect!.height).toBeGreaterThanOrEqual(44);
  await expect(page.getByRole('button', { name: '刷新状态' })).toBeEnabled();
  expect(await page.evaluate(() => document.documentElement.scrollWidth)).toBe(800);
});
