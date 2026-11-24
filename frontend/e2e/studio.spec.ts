import { expect, test } from '@playwright/test';

test('a waiting meeting run survives refresh and saves only confirmed items', async ({ page }) => {
  await page.goto('/#/legacy');
  await page.getByRole('button', { name: '试运行', exact: true }).click();
  const dialog = page.getByRole('dialog', { name: '确认待办事项' });
  await expect(dialog.getByRole('checkbox')).toHaveCount(2);
  await dialog.getByRole('button', { name: '稍后处理' }).click();
  await page.reload();
  await page.getByRole('button', { name: '运行记录', exact: true }).click();
  await page.getByRole('button', { name: '继续确认' }).click();
  // Element Plus renders the input through a visible label.
  await dialog.locator('label').last().click();
  await dialog.getByRole('button', { name: '确认 1 项' }).click();
  await expect(page.getByText(/任务预览共 1 项/)).toBeVisible();
});

test('the empty sample ends immediately and evaluations use the edited draft', async ({ page }) => {
  await page.goto('/#/legacy');
  await page.getByLabel('固定输入样例').selectOption('empty');
  await page.getByRole('button', { name: '试运行', exact: true }).click();
  await expect(page.getByText('已完成', { exact: true })).toBeVisible();
  await expect(page.getByRole('dialog')).toHaveCount(0);
  await page.getByRole('button', { name: /^有待办？/ }).click();
  await page.getByLabel('进入确认的最少待办数').selectOption('2');
  await page.getByRole('button', { name: '样例评估', exact: true }).click();
  await page.getByRole('button', { name: '运行对比' }).click();
  await expect(page.getByRole('status').filter({ hasText: '当前草稿通过 2/3' })).toBeVisible();
});

test('another editor cannot overwrite a newer draft', async ({ page, context }) => {
  await page.goto('/#/legacy');
  const editor = await context.newPage();
  await editor.goto('/#/legacy');
  const prompt = page.getByRole('textbox', { name: '提示词' });
  const stalePrompt = editor.getByRole('textbox', { name: '提示词' });
  await expect(stalePrompt).toBeVisible();
  const current = await prompt.inputValue();
  const winner = current + ' Include a deadline.';
  await prompt.fill(winner);
  await page.getByRole('button', { name: '保存草稿' }).click();
  await expect(page.getByRole('button', { name: '保存草稿' })).toBeDisabled();
  await stalePrompt.fill(current + ' From the older revision.');
  await editor.getByRole('button', { name: '保存草稿' }).click();
  await expect(editor.getByRole('button', { name: '载入最新草稿' })).toBeVisible();
  await page.reload();
  await expect(prompt).toHaveValue(winner);
});
