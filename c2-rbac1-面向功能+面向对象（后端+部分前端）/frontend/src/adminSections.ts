// 导航按管理对象组织。后续阶段可注册独立页面并继续复用管理外壳。
export interface Column { key: string; label: string }
export interface DirectorySection {
  id: string
  label: string
  path: string
  columns: Column[]
}

export const directories: DirectorySection[] = [
  { id: 'employees', label: '员工管理', path: '/api/employees', columns: [
    { key: 'name', label: '姓名' }, { key: 'username', label: '用户名' },
    { key: 'sourceBranchName', label: '分公司' }, { key: 'sourceDepartmentName', label: '部门' },
    { key: 'jobTitle', label: '岗位' }, { key: 'phone', label: '电话' }, { key: 'enabled', label: '状态' },
  ] },
  { id: 'departments', label: '部门目录', path: '/api/departments', columns: [
    { key: 'name', label: '部门名称' }, { key: 'managerName', label: '部门经理' },
    { key: 'sourceParentName', label: '上级部门' },
  ] },
  { id: 'roles', label: '角色管理', path: '/api/roles', columns: [
    { key: 'name', label: '角色名称' }, { key: 'ownerOrganizationId', label: '所属组织' },
  ] },
  { id: 'permissions', label: '权限管理', path: '/api/permissions', columns: [
    { key: 'name', label: '权限名称' }, { key: 'code', label: '权限码' },
    { key: 'systemId', label: '业务系统' }, { key: 'ownerOrganizationId', label: '所属组织' },
  ] },
  { id: 'catalog', label: '功能权限清单', path: '/api/catalog-entries', columns: [
    { key: 'systemName', label: '软件系统' }, { key: 'moduleName', label: '功能模块' },
    { key: 'featureName', label: '功能点' }, { key: 'description', label: '功能说明' },
    { key: 'administratorCell', label: '系统管理员' }, { key: 'executiveCell', label: '公司领导' },
    { key: 'departmentManagerCell', label: '部门经理' }, { key: 'projectManagerCell', label: '项目经理' },
    { key: 'employeeCell', label: '普通员工' },
  ] },
]
