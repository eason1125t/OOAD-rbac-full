-- 为现有系统建立查看权限，只为约定的演示账号配置只读角色。
INSERT INTO permission (code, name, system_id, owner_organization_id)
SELECT 'request:read', '查看申请', app.id, NULL
FROM business_system app
WHERE NOT EXISTS (SELECT 1 FROM permission p WHERE p.system_id = app.id AND p.code = 'request:read');

INSERT INTO rbac_role (name, owner_organization_id)
SELECT '示例申请查看员', app.owner_organization_id
FROM business_system app
WHERE app.code IN ('demo-company-app', 'demo-engineering-app', 'demo-finance-app', 'demo-company-b-app')
  AND NOT EXISTS (SELECT 1 FROM rbac_role r
      WHERE r.name = '示例申请查看员' AND r.owner_organization_id = app.owner_organization_id);

INSERT INTO role_permission (role_id, permission_id)
SELECT r.id, p.id
FROM rbac_role r
JOIN business_system app ON app.owner_organization_id = r.owner_organization_id
JOIN permission p ON p.system_id = app.id AND p.code = 'request:read'
WHERE r.name = '示例申请查看员'
  AND app.code IN ('demo-company-app', 'demo-engineering-app', 'demo-finance-app', 'demo-company-b-app')
  AND NOT EXISTS (SELECT 1 FROM role_permission rp WHERE rp.role_id = r.id AND rp.permission_id = p.id);

INSERT INTO employee_role (employee_id, role_id)
SELECT e.id, r.id
FROM employee e
JOIN business_system app ON
    (app.code = 'demo-company-app' AND e.username = 'company-a-admin')
    OR (app.code = 'demo-engineering-app' AND e.username IN
        ('company-a-admin', 'engineering-admin', 'engineering-employee', 'engineering-requester', 'engineering-inherited-approver'))
    OR (app.code = 'demo-finance-app' AND e.username IN ('company-a-admin', 'finance-employee'))
    OR (app.code = 'demo-company-b-app' AND e.username = 'company-b-employee')
JOIN rbac_role r ON r.owner_organization_id = app.owner_organization_id AND r.name = '示例申请查看员'
WHERE NOT EXISTS (SELECT 1 FROM employee_role er WHERE er.employee_id = e.id AND er.role_id = r.id);
