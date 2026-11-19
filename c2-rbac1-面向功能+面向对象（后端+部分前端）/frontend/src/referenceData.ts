// 管理表单和策略页复用同一套范围内的组织与业务系统选项。
import { list, read, type BusinessSystem, type NamedItem } from './api'

export async function allPages<T>(path: string): Promise<T[]> {
  const options: T[] = []
  const pageSize = 100
  let page = 1
  while (true) {
    const result = await list<T>(path, page, pageSize)
    options.push(...result.items)
    if (page * pageSize >= result.total || !result.items.length) return options
    page += 1
  }
}

export async function organizationOptions(): Promise<NamedItem[]> {
  const [companies, departments] = await Promise.all([
    allPages<NamedItem>('/api/companies'), allPages<NamedItem>('/api/departments'),
  ])
  const companyIds = new Set(companies.map(item => item.id))
  return [...companies.map(item => ({ ...item, name: `${item.name}（公司）` })),
    ...departments.filter(item => !companyIds.has(item.id)).map(item => ({ ...item, name: `${item.name}（部门）` }))]
}

export async function businessSystems(): Promise<BusinessSystem[]> {
  return read('/api/business-systems')
}
