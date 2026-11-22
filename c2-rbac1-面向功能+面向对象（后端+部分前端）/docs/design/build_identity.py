"""补齐本轮可信身份、自身授权、API两表及目录隔离的原生图。
协作：复用build_staruml与build_supplementary工厂，不另建模型或手工维护导出。
"""


def self_authorization(u, parent):
    d = u.ClassDiagram(parent, "oo", "43-shared-可信身份与自身授权-类图",
                       "getCurrentSession / checkCurrentPermission / checkCurrentApiPermission",
                       "Controller取得可信员工；查询服务组织自身授权，授权规则与身份适配分开。")
    d.add("service", "authentication.CurrentAuthorizationService", "C",
          [("systems", "BusinessSystemRepository"), ("authorization", "AuthorizationService"),
           ("roles", "RbacRoleRepository"), ("apiOperations", "ApiOperationRegistry")],
          [("describe", "", ""), ("check", "", ""), ("checkApi", "", "")])
    for key, cls, pos, interface, operations in [
        ("controller", "authentication.AuthController", "L", False, ["me", "permissionCheck", "apiCheck"]),
        ("current", "authentication.CurrentEmployeeService", "T", False, ["requireActive", "findActive"]),
        ("auth", "authorization.AuthorizationService", "R", False, ["describe", "effectivePermissions", "hasBusinessPermission"]),
        ("registry", "authorization.ApiOperationRegistry", "B", False, ["requiredPermissions", "initializeDemoMappings"]),
        ("systems", "simulation.BusinessSystemRepository", "BL", True, []),
        ("roles", "rolepermission.RbacRoleRepository", "BR", True, []),
    ]:
        d.add(key, cls, pos, interface=interface, ops=[(name, "", "") for name in operations])
    d.views["controller"]["showOperationSignature"] = False
    d.link("controller", "service", "currentAuthorization")
    d.link("controller", "current", "currentEmployees")
    for target, field in [("auth", "authorization"), ("registry", "apiOperations"),
                          ("systems", "systems"), ("roles", "roles")]:
        d.link("service", target, field)
    d.link("auth", "current", "currentEmployees")
    d.finish()
    d.entry.update(version="shared", requirements="ID-03、04、06",
                   testMethods=["apiTrustedActorOnly", "apiAllAndLiveRevocation"],
                   browserTest="测试/前端/tests/identity.spec.ts")


def directory(u, parent):
    d = u.ClassDiagram(parent, "oo", "44-shared-部门员工范围查询-类图",
                       "listManagementDepartments / listManagementEmployees / getManagementEmployee",
                       "目录范围与管理员维护分开；过滤、分页及count在同一数据库范围执行。")
    d.add("service", "organization.DirectoryQueryService", "C",
          [("visibility", "DirectoryVisibilityService"), ("organizations", "OrganizationRepository"),
           ("employees", "EmployeeRepository")], [("employees", "", ""), ("departments", "", "")])
    for key, cls, pos, interface, operations in [
        ("controller", "organization.ManagementQueryController", "L", False, ["employees", "departments", "employee"]),
        ("scope", "organization.DirectoryVisibilityService", "T", False, ["forCurrentEmployee", "forAdministrator"]),
        ("organizations", "organization.OrganizationRepository", "R", True, []),
        ("employees", "organization.EmployeeRepository", "B", True, []),
        ("management", "organization.ManagementQueryService", "BL", False, ["employee"]),
    ]:
        d.add(key, cls, pos, interface=interface, ops=[(name, "", "") for name in operations])
    d.views["controller"]["showOperationSignature"] = False
    d.link("controller", "service", "directory")
    d.link("controller", "management", "management")
    for target, field in [("scope", "visibility"), ("organizations", "organizations"), ("employees", "employees")]:
        d.link("service", target, field)
    d.finish()
    d.entry.update(version="shared", requirements="ID-05、07、08", browserTest="测试/前端/tests/identity.spec.ts")


def database(u, parent):
    from build_supplementary import logical
    key = lambda name, foreign=False: (name, "BIGINT", True, foreign, False)
    logical(u, parent, "45-shared-API映射-逻辑ER图", [
        ("business_system", 80, 100, [key("id"), ("code", "VARCHAR", False, False, False)]),
        ("permission", 80, 650, [key("id"), ("system_id", "BIGINT", False, True, True),
                                ("code", "VARCHAR", False, False, False)]),
        ("api_operation", 1000, 100, [key("system_id", True), ("operation_id", "VARCHAR", True, False, False)]),
        ("api_operation_permission", 1000, 650, [key("system_id", True),
                                                ("operation_id", "VARCHAR", True, True, False), key("permission_id", True)]),
    ], [
        ("api_operation", "system_id", "business_system", False),
        ("permission", "system_id", "business_system", True),
        ("api_operation_permission", ("system_id", "operation_id"), "api_operation", False,
         ("system_id", "operation_id")),
        ("api_operation_permission", "permission_id", "permission", False),
    ], "操作主键为(system_id, operation_id)，要求表复合外键引用操作；operationId区分大小写。\n"
       "固定ALL；四个demo系统32操作、52要求。权限必须属于同一系统，由Registry额外校验。\n"
       "V9只补映射，不授角色；初始化仅补新操作，已有损坏配置拒绝而不自动修复。")
    u.CATALOG[-1].update(requirements="ID-06", operationId="checkCurrentApiPermission",
                        testMethods=["apiCorruptMappingsFailClosed"], browserTest="测试/前端/tests/identity.spec.ts")


def api_flow(u, parent):
    from build_supplementary import pipeline
    pipeline(u, parent, "46-shared-API预查询与实际授权-活动图", [
        ("AuthController：从验签、sid及资格验证后的Long principal取得可信员工", "无效身份 → 401"),
        ("CurrentAuthorizationService：按systemCode查询系统", "未知系统 → 404 SYSTEM_NOT_FOUND"),
        ("AuthorizationService：校验系统准入，按继承及组织策略计算有效权限", "无系统准入 → 403"),
        ("ApiOperationRegistry：查询目标systemId + operationId", "未登记API → 404 API_NOT_FOUND"),
        ("检查非空、引用完整、同系统及RequestOperation规范一致", "空集合/跨系统/定义漂移 → 503"),
        ("effective.containsAll(required)：返回系统、操作及allowed", None),
        ("实际调用重新按RequestOperation执行实时授权、申请归属及状态检查", "撤权/越权/状态变化 → 实际操作拒绝"),
    ], "API检查不接收其他员工ID，不查询具体申请，不缓存为操作凭证。实际入口不依赖映射表来放行。\n"
       "create只需create；修改/撤回/审批/驳回/删除要求read及对应动作；DISABLED优先，同源重授规则保留。",
       ["backend-oo/src/main/java/authentication/CurrentAuthorizationService.java",
        "backend-oo/src/main/java/authorization/ApiOperationRegistry.java",
        "backend-oo/src/main/java/simulation/RequestOperation.java"], "shared", "checkCurrentApiPermission",
       methods=["apiAllAndLiveRevocation", "apiActualObjectAndStateAuthorization"],
       browser="测试/前端/tests/identity.spec.ts")


def directory_flow(u, parent):
    from build_supplementary import pipeline
    pipeline(u, parent, "47-shared-部门员工前端与数据隔离-活动图", [
        ("App共用部门与员工入口；按需读取tree根或指定parent直接子级", "未知组织/越界 → 404/403，不保留旧列表"),
        ("后端Scope：普通本组织、部门管理员本部门、公司管理员本公司与直属部门、全局全部", None),
        ("部门搜索q及hasChildren；选部门后按departmentId和姓名/账号搜索员工", "非部门筛选 → 400；越界部门 → 403"),
        ("数据库同范围过滤列表和count，分页；普通员工额外筛选enabled", None),
        ("普通返回4字段；管理员保留原字段，详情另走受保护管理员入口", "普通直接调用他人资料 → 403"),
        ("前端筛选变化回第1页；序号丢弃旧异步响应，失败清空结果", None),
    ], "正式tree与旧原表directory显式区分；公司用于展开，不作为departmentId。\n"
       "前端不全量下载再过滤，不展示普通员工联系字段；管理员资料仍由后端校验目标范围。",
       ["frontend/src/DepartmentEmployees.vue", "backend-oo/src/main/java/organization/DirectoryQueryService.java",
        "backend-oo/src/main/java/organization/DirectoryVisibilityService.java"], "shared",
       "listManagementDepartments / listManagementEmployees / getManagementEmployee",
       browser="测试/前端/tests/identity.spec.ts")


def build(u, parent):
    self_authorization(u, parent)
    directory(u, parent)
    database(u, parent)
    api_flow(u, parent)
    directory_flow(u, parent)
