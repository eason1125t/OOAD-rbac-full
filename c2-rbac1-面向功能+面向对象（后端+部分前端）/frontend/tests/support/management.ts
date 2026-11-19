// 两版管理端测试共用分页操作，避免依赖测试角色恰好位于第一页。
import { expect, type Page } from '@playwright/test'

export async function openRoleDialog(page: Page, name: string, action: '管理权限' | '继承管理' = '管理权限') {
  await page.getByRole('navigation', { name: '管理导航' }).getByRole('link', { name: '角色管理' }).click()
  const table = page.getByRole('region', { name: '角色管理' })
  const row = table.getByRole('row').filter({ has: page.getByRole('cell', { name, exact: true }) })
  await expect(table.getByRole('columnheader', { name: '角色名称' })).toBeVisible()
  const previous = table.getByRole('button', { name: '上一页', exact: true })
  while (await previous.count() && await previous.isEnabled()) {
    await previous.click()
    await expect(table.getByRole('status')).toHaveCount(0)
  }
  const next = table.getByRole('button', { name: '下一页', exact: true })
  while (!(await row.count()) && await next.count() && await next.isEnabled()) {
    await next.click()
    await expect(table.getByRole('status')).toHaveCount(0)
  }
  await expect(row).toContainText('示例工程部')
  await row.getByRole('button', { name: action, exact: true }).click()
}

export async function selectInheritanceSource(page: Page, name: string) {
  const dialog = page.getByRole('dialog', { name: '继承管理' })
  const select = dialog.getByRole('combobox', { name: '继承自' })
  await expect(select).toBeEnabled()
  const pager = dialog.locator('.pager').filter({ hasText: '可选角色' })
  const previous = pager.getByRole('button', { name: '上一页', exact: true })
  while (await previous.count() && await previous.isEnabled()) {
    await previous.click()
    await expect(select).toBeEnabled()
  }
  const next = pager.getByRole('button', { name: '下一页', exact: true })
  while (!(await select.getByRole('option', { name, exact: true }).count()) &&
      await next.count() && await next.isEnabled()) {
    await next.click()
    await expect(select).toBeEnabled()
  }
  await select.selectOption({ label: name })
}

export async function selectRegrantRole(page: Page, name: string) {
  const dialog = page.getByRole('dialog', { name: '组织策略' })
  const select = dialog.getByRole('combobox', { name: '待重授角色' })
  await expect(select).toBeEnabled()
  const next = dialog.getByRole('button', { name: '下一页', exact: true })
  while (!(await select.getByRole('option', { name, exact: true }).count()) &&
      await next.count() && await next.isEnabled()) {
    await next.click()
    await expect(select).toBeEnabled()
  }
  await select.selectOption({ label: name })
}
