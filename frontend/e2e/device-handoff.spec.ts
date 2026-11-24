import { devices, expect, test } from '@playwright/test';

test('a decision made on a phone wins over a stale desktop confirmation', async ({
  browser,
  page,
  baseURL,
}) => {
  const phoneContext = await browser.newContext({ ...devices['Pixel 7'], baseURL });
  try {
    await page.goto('/#/apps/feedback-triage');
    await page.getByRole('button', { name: '运行与恢复', exact: true }).click();
    await page.getByRole('button', { name: '运行发布版 v1' }).click();
    const staleConfirmation = page.getByRole('button', { name: '确认并恢复（1）' });
    await expect(staleConfirmation).toBeVisible({ timeout: 20000 });

    const phone = await phoneContext.newPage();
    await phone.goto(page.url());
    await phone.reload();
    await phone.getByRole('checkbox', { name: /Urgent/ }).tap();
    await phone.getByRole('button', { name: '确认并恢复（0）' }).tap();
    const completed = '已完成 · v1';
    await expect(phone.locator('.wb-run-details h3').first()).toHaveText(completed, {
      timeout: 20000,
    });

    const response = page.waitForResponse(
      (r) => r.url().endsWith('/resume') && r.request().method() === 'POST',
    );
    await staleConfirmation.click();
    expect((await response).status()).toBe(409);
    const authoritative = page.locator('.wb-run-details');
    await expect(authoritative.locator('h3').first()).toHaveText(completed);
    await expect(authoritative).not.toContainText('本机操作记录 · 1 项');
    await expect(authoritative).toContainText('Feedback recorded without escalation');
  } finally {
    await phoneContext.close();
  }
});
