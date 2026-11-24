import { expect, test } from '@playwright/test';

test('pin a node input and compare real handler outputs without starting a run', async ({
  page,
}) => {
  await page.goto('/');
  await page.getByRole('button', { name: '节点调试', exact: true }).click();
  await page.getByLabel('被测节点').selectOption('filter');
  const fields = page.getByLabel('节点输入 JSON');
  await fields.fill(
    JSON.stringify({
      items: [
        { id: 'a', title: 'Send report', assignee: 'Alex' },
        { id: 'b', title: 'Book meeting', assignee: '' },
      ],
    }),
  );
  await page.getByRole('button', { name: '固定输入', exact: true }).click();
  await expect(fields).toHaveAttribute('readonly', '');
  await page.getByText('复用样例文件', { exact: true }).click();
  const download = page.waitForEvent('download');
  await page.getByRole('button', { name: '导出固定输入' }).click();
  const file = await (await download).path();
  expect(file).toBeTruthy();
  await page.getByRole('button', { name: '解除固定' }).click();
  await fields.fill('{}');
  await page.getByLabel('导入样例 JSON').setInputFiles(file!);
  await expect(fields).toHaveValue(/Book meeting/);
  await page.getByRole('button', { name: '试跑草稿节点' }).click();
  await page.getByRole('button', { name: '试跑已发布节点 v1' }).click();
  await expect(page.locator('.wb-results pre')).toHaveCount(2);
  for (const pre of await page.locator('.wb-results pre').all()) {
    const value = JSON.parse(await pre.innerText());
    expect(value.items.map((item: { id: string }) => item.id)).toEqual(['a']);
    expect(value.excluded.map((item: { id: string }) => item.id)).toEqual(['b']);
  }
  await page.getByRole('button', { name: '运行与恢复', exact: true }).click();
  await expect(page.getByText('还没有运行记录。')).toBeVisible();
});

test('release diff keeps explicit historical values when old capability defaults are unavailable', async ({
  page,
}) => {
  // Simulate an archived plugin version whose descriptor is no longer in the running registry.
  await page.route(/\/api\/apps\/feedback-triage\/versions\/\d+$/, async (route) => {
    const response = await route.fetch();
    const body = await response.json();
    const archived = body.data;
    archived.capabilities['classify-feedback'] = 'older-descriptor';
    const classify = archived.graph.nodes.find((node: { id: string }) => node.id === 'classify');
    classify.config = { historicalFlag: false, historicalLimit: 0 };
    await route.fulfill({ response, json: body });
  });
  await page.goto('/#/apps/feedback-triage');
  await page.getByLabel('优先处理关键词（逗号分隔）').fill('critical');
  await page.getByRole('button', { name: '对比与发布', exact: true }).click();
  for (const line of [
    'classify.keywords：历史默认值未知 → critical',
    'classify.historicalFlag：false → 未设置',
    'classify.historicalLimit：0 → 未设置',
  ]) {
    await expect(page.getByText(line, { exact: true })).toBeVisible();
  }
});

test('publish edited feedback rules while an existing review finishes on v1', async ({ page }) => {
  await page.goto('/#/apps/feedback-triage');
  await page.getByRole('button', { name: '运行与恢复', exact: true }).click();
  await page.getByRole('button', { name: '运行发布版 v1' }).click();
  await expect(page.getByRole('button', { name: /确认并恢复/ })).toBeVisible({ timeout: 20000 });
  const waitingUrl = page.url();
  await page.getByRole('button', { name: '流程编排', exact: true }).click();
  const keywords = page.getByLabel('优先处理关键词（逗号分隔）');
  const beforeKeywords = await keywords.inputValue();
  expect(beforeKeywords).toContain('urgent');
  await keywords.fill(beforeKeywords + ',critical');
  await page.getByRole('button', { name: '保存图草稿', exact: true }).click();
  await expect(page.getByText('已保存草稿 r2')).toBeVisible();
  await page.getByRole('button', { name: '对比与发布', exact: true }).click();
  await expect(
    page.getByText('classify.keywords：' + beforeKeywords + ' → ' + beforeKeywords + ',critical', {
      exact: true,
    }),
  ).toBeVisible();
  await page.getByRole('button', { name: '运行样例对比' }).click();
  await expect(page.getByText(/草稿通过 3\/3/)).toBeVisible();
  await page.getByRole('button', { name: '发布新版本' }).click();
  await page.getByRole('button', { name: '确认发布' }).click();
  await expect(page.getByText('已发布 v2', { exact: true })).toBeVisible();
  await page.goto(waitingUrl);
  await page.reload();
  await page.getByRole('button', { name: /确认并恢复/ }).click();
  await expect(page.locator('.wb-run-details h3').first()).toHaveText('已完成 · v1', {
    timeout: 20000,
  });
  await expect(page.getByText('本机操作记录 · 1 项')).toBeVisible();
  await expect(page.locator('.wb-events')).toContainText('review');
});

test('restore a paused meeting checkpoint and cancel all tasks', async ({ page }) => {
  await page.goto('/#/apps/meeting-notes');
  await page.getByRole('button', { name: '运行与恢复', exact: true }).click();
  await page.getByLabel('节点完成后暂停').selectOption('start');
  await page.getByRole('button', { name: '运行发布版 v1' }).click();
  await expect(page.getByRole('button', { name: '继续执行' })).toBeVisible({ timeout: 20000 });
  await page.reload();
  await page.getByRole('button', { name: '继续执行' }).click();
  const wait = page.locator('.wb-wait');
  await expect(wait.getByRole('checkbox')).toHaveCount(1, { timeout: 20000 });
  await wait.getByRole('checkbox').uncheck();
  await wait.getByRole('button', { name: '确认并恢复（0）' }).click();
  await expect(page.locator('.wb-run-details h3').first()).toHaveText('已完成 · v1', {
    timeout: 20000,
  });
  await expect(page.locator('.wb-run-details')).toContainText('No confirmed tasks');
  await expect(page.locator('.wb-run-details')).not.toContainText('本机操作记录 · 1 项');
  await page.getByLabel('查看节点').selectOption('extract');
  await page.getByRole('button', { name: '读取该次输入并固定到调试台' }).click();
  await expect(page.getByLabel('节点输入 JSON')).toHaveAttribute('readonly', '');
  await expect(page.getByLabel('节点输入 JSON')).toHaveValue(/Alice: Send the release report/);
  await expect(page.getByText(/输入来源：运行时 v1/)).toBeVisible();
});

test('insert a registered extension on an existing wire, save and reload the business graph', async ({
  page,
}, testInfo) => {
  await page.goto('/#/apps/meeting-notes');
  await page.getByLabel('节点能力').selectOption('filter-assigned');
  const insertion = page.getByRole('button', { name: '在此连线插入所选能力' });
  await insertion.click();
  const settings = page.getByRole('complementary', { name: '节点配置' });
  const newId = (await settings.locator('header code').innerText()).trim();
  await expect(settings.getByLabel('输入映射 items')).toHaveValue('extract.items');
  await page.getByRole('button', { name: '保存图草稿' }).click();
  await expect(page.getByText(/已保存草稿 r/)).toBeVisible();
  await page.reload();
  const added = page.getByRole('button', { name: new RegExp('节点 ' + newId + '$') });
  await added.click();
  await expect(settings.getByLabel('输入映射 items')).toHaveValue('extract.items');
  const first = await page.getByRole('button', { name: /节点 extract$/ }).boundingBox();
  const next = await added.boundingBox();
  expect(
    first &&
      next &&
      (first.y + first.height <= next.y ||
        first.x + first.width <= next.x ||
        next.x + next.width <= first.x),
  ).toBeTruthy();
  await page.screenshot({ path: testInfo.outputPath('public-workbench.png'), fullPage: true });
});
