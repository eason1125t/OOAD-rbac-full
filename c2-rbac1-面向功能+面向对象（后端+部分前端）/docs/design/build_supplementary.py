"""补齐现有 C2 实现的数据库、流程、状态、前端和辅助协作图。

由 build_staruml.py 传入同一模型工厂和 ID 序列，统一索引、验证与 CLI 导出。
仅描述已有实现；不包含尚未规划的模型比较和课程 Swarm 拓扑。
"""

from __future__ import annotations

import itertools
import re


def record(u, name, kind, sources, operation="—", version="shared", requirements="课程结构与职责检查", methods=(), browser="测试/前端/tests/isolation.spec.ts"):
    for path in sources:
        if not (u.C2/path).exists():
            raise ValueError(f"图件源码不存在: {path}")
    entry = dict(name=name, kind=kind, version=version, operationId=operation,
                 summary="基于当前实现的补充图；具体边界和省略项见图内说明。", sources=sources,
                 requirements=requirements, testMethods=list(methods), browserTest=browser)
    u.CATALOG.append(entry)
    return entry


class Graph:
    """封装原生组件图、活动图与状态图，检查节点和关系线的输入布局。"""

    def __init__(self, u, parent, name, kind="component"):
        self.u, self.parent, self.kind = u, parent, kind
        if kind == "activity":
            self.owner = u.item("UMLActivity", parent, name=name, nodes=[], edges=[], ownedElements=[])
            diagram_type = "UMLActivityDiagram"
        elif kind == "state":
            self.owner = u.item("UMLStateMachine", parent, name=name, regions=[], ownedElements=[])
            self.region = u.item("UMLRegion", self.owner, vertices=[], transitions=[])
            self.owner["regions"].append(self.region)
            diagram_type = "UMLStatechartDiagram"
        else:
            self.owner = u.item("UMLModel", parent, name=name, ownedElements=[])
            diagram_type = "UMLComponentDiagram"
        parent["ownedElements"].append(self.owner)
        self.diagram = u.item(diagram_type, self.owner, name=name, ownedViews=[])
        self.owner["ownedElements"].append(self.diagram)
        self.nodes, self.views, self.boxes, self.edges = {}, {}, {}, []

    def add(self, key, text, x, y, w=450, h=115, decision=False, initial=False):
        u = self.u
        if self.kind == "component":
            model_type, view_type, owner, field = "UMLComponent", "UMLComponentView", self.owner, "ownedElements"
        elif self.kind == "activity":
            model_type = "UMLInitialNode" if initial else "UMLDecisionNode" if decision else "UMLAction"
            view_type = "UMLControlNodeView" if initial or decision else "UMLActionView"
            owner, field = self.owner, "nodes"
        else:
            model_type, view_type = ("UMLPseudostate", "UMLPseudostateView") if initial else ("UMLState", "UMLStateView")
            owner, field = self.region, "vertices"
        model = u.item(model_type, owner, name=text)
        if initial and self.kind == "state":
            model["kind"] = "initial"
        owner[field].append(model)
        node = u.view(view_type, self.diagram, model, left=x, top=y, width=w, height=h,
                      fillColor="#e7f0fd" if initial or key == "main" else "#ffffff", wordWrap=True)
        self.diagram["ownedViews"].append(node)
        self.nodes[key], self.views[key], self.boxes[key] = model, node, (x, y, w, h)

    def link(self, a, b, label="", points=None):
        u = self.u
        if self.kind == "component":
            model_type, view_type, owner, field = "UMLDependency", "UMLDependencyView", self.owner, "ownedElements"
        elif self.kind == "activity":
            model_type, view_type, owner, field = "UMLControlFlow", "UMLControlFlowView", self.owner, "edges"
        else:
            model_type, view_type, owner, field = "UMLTransition", "UMLTransitionView", self.region, "transitions"
        model = u.item(model_type, owner, source=u.ref(self.nodes[a]), target=u.ref(self.nodes[b]),
                       **({"guard": label} if self.kind == "activity" else {"name": label}))
        owner[field].append(model)
        points = points or u.routed_edge(a, b, self.boxes)
        edge = u.view(view_type, self.diagram, model, tail=u.ref(self.views[a]), head=u.ref(self.views[b]),
                      points=";".join(f"{x}:{y}" for x, y in points), lineStyle=0 if len(points)>2 else 1)
        self.diagram["ownedViews"].insert(0, edge)
        self.edges.append((a, b, points))

    def note(self, text, y=None):
        bottom = max(y+h for x, y, w, h in self.boxes.values())
        self.diagram["ownedViews"].append(self.u.view("UMLTextView", self.diagram,
            left=80, top=y if y is not None else bottom+70, width=max(x+w for x,y,w,h in self.boxes.values())-80,
            height=170, text=text, wordWrap=True))

    def finish(self):
        for (a, p), (b, q) in itertools.combinations(self.boxes.items(), 2):
            if p[0] < q[0]+q[2] and q[0] < p[0]+p[2] and p[1] < q[1]+q[3] and q[1] < p[1]+p[3]:
                raise ValueError(f"补充图节点重叠: {a}, {b}")
        for a,b,points in self.edges:
            for p,q in zip(points, points[1:]):
                if any(self.u.segment_hits(p,q,box) for key,box in self.boxes.items() if key not in (a,b)):
                    raise ValueError(f"补充图连线穿框: {a}, {b}")


def pipeline(u, parent, name, stages, note, sources, version="func", operation="—", methods=(), browser="测试/前端/tests/authorization.spec.ts"):
    """主流程由上到下，检查失败向右进入独立终止动作；箭头标签是真实条件。"""
    graph = Graph(u, parent, name, "activity")
    graph.add("start", "", 595, 40, 24, 24, initial=True)
    previous, links = "start", []
    for index, stage in enumerate(stages):
        label, failure = stage[:2]
        exit_guard, continue_guard = stage[2:] or ("检查不通过", "检查通过")
        key, y = f"step{index}", 140+index*250
        graph.add(key, label, 380, y, 455, 95)
        links.append((previous, key, ""))
        previous = key
        if failure:
            check = f"check{index}"
            graph.add(check, "", 577, y+135, 60, 60, decision=True)
            graph.add(f"fail{index}", failure, 1110, y+122, 480, 90)
            links += [(key, check, ""), (check, f"fail{index}", exit_guard)]
            previous = check
            # 守卫按真实退出条件显示，提前成功返回与校验失败不混写。
            graph.nodes[check]["documentation"] = continue_guard
    graph.add("end", "完成本次请求／操作", 380, 160+len(stages)*250, 455, 85)
    links.append((previous, "end", ""))
    for a,b,label in links:
        graph.link(a,b,label or (graph.nodes[a]["documentation"] if a.startswith("check") else ""))
    graph.note(note+"\n右侧动作终止本次流程，不进入后续步骤。流程只展示应用层，401/CSRF/参数格式由公共边界处理。")
    graph.finish()
    record(u,name,"activity",sources,operation,version,methods=methods,browser=browser)


def chen(u, parent):
    """陈氏 ER 的递归联系采用同一个角色实体的两个角色端，而非伪造两类角色。"""
    name = "17-shared-角色继承-陈氏ER图"
    diagram = u.item("ERDDiagram", parent, name=name, ownedViews=[])
    parent["ownedElements"].append(diagram)
    v = diagram["ownedViews"]
    def label(text,x,y,w=300,size=16):
        value=u.view("UMLTextView",diagram,left=x,top=y,width=w,height=48,text=text,wordWrap=True,horzAlign=2)
        value["font"]=f"Microsoft YaHei;{size};0";v.append(value)
    def line(*points):
        v.insert(0,u.view("FreelineEdgeView",diagram,points=";".join(f"{x}:{y}" for x,y in points),lineStyle=1))
    def rectangle(text,x,y,w=260,h=85):
        v.append(u.view("RectangleView",diagram,left=x,top=y,width=w,height=h,fillColor="#e7f0fd"));label(text,x,y+22,w)
    def diamond(text,x,y):
        line((x,y-65),(x+110,y),(x,y+65),(x-110,y),(x,y-65));label(text,x-95,y-14,190)
    def attribute(text,x,y,anchor,key=False):
        v.append(u.view("EllipseView",diagram,left=x-100,top=y-25,width=200,height=50,fillColor="#ffffff"))
        label(text,x-95,y-13,190);line(anchor,(x,y-25))
        if key:
            # 主标识下划线置于属性椭圆上层，避免被椭圆白底遮住。
            # EdgeView 会被 StarUML 统一放到图形下层，细矩形保持为可编辑上层标识线。
            v.append(u.view("RectangleView",diagram,left=x-32,top=y+14,width=64,height=1,fillColor=u.INK))
    rectangle("角色（rbac_role）",650,275)
    diamond("继承",1250,315)
    line((910,295),(1040,210),(1250,210),(1250,250))
    line((1250,380),(1250,465),(780,465),(780,360))
    label("senior（获得权限）0..N",910,160,370)
    label("junior（提供权限）0..N",800,470,400)
    attribute("角色 ID",630,150,(700,275),True)
    attribute("角色名称",860,150,(860,275))
    rectangle("组织（organization）",40,275)
    diamond("归属",430,315)
    line((300,315),(320,315));line((540,315),(650,315))
    label("0..1",285,230,80);label("0..N",550,250,80)
    label("递归多对多联系：一对有向角色端仅存一条边；双方同归属（含双方共享）。",80,590,1250)
    label("高级角色沿 senior → junior 获取权限；禁止自身与传递环路。闭包不落为额外继承边。",80,645,1250)
    label("矩形=实体；菱形=联系；椭圆=属性；下划线=主标识。范围约束由应用层检查。",80,700,1250)
    record(u,name,"chen",["backend-oo/src/main/resources/db/migration/V6__create_role_inheritance.sql"],
           requirements="C2-01、03、05")


def logical(u,parent,name,tables,relationships,note):
    """原生 ERD 表、列、FK 引用及基数；列集仅选当前主题的相关字段。"""
    data=u.item("ERDDataModel",parent,name=name,ownedElements=[])
    parent["ownedElements"].append(data)
    diagram=u.item("ERDDiagram",data,name=name,ownedViews=[]);data["ownedElements"].append(diagram)
    nodes,views,columns,boxes={},{},{},{}
    sql="\n".join(p.read_text(encoding="utf-8") for p in (u.C2/"backend-oo/src/main/resources/db/migration").glob("V*.sql"))
    for table,x,y,fields in tables:
        if not re.search(rf"CREATE TABLE {table}\s*\(",sql):raise ValueError(f"不存在表: {table}")
        model=u.item("ERDEntity",data,name=table,columns=[]);data["ownedElements"].append(model)
        nodes[table]=model
        for column,type_name,pk,fk,nullable in fields:
            if not re.search(rf"\b{column}\s+{re.escape(type_name)}\b",sql,re.I):raise ValueError(f"不存在列: {table}.{column}")
            value=u.item("ERDColumn",model,name=column,type=type_name,primaryKey=pk,foreignKey=fk,nullable=nullable)
            model["columns"].append(value);columns[table,column]=value
        box=(x,y,500,220)
        node=u.view("ERDEntityView",diagram,model,left=x,top=y,width=500,height=220,fillColor="#ffffff")
        views[table],boxes[table]=node,box;diagram["ownedViews"].append(node)
    for index,(child,column,parent_table,nullable) in enumerate(relationships):
        columns[child,column]["referenceTo"]=u.ref(columns[parent_table,"id"])
        relation=u.item("ERDRelationship",data,name=column,identifying=columns[child,column]["primaryKey"])
        relation["end1"]=u.item("ERDRelationshipEnd",relation,reference=u.ref(nodes[parent_table]),cardinality="0..1" if nullable else "1")
        relation["end2"]=u.item("ERDRelationshipEnd",relation,reference=u.ref(nodes[child]),cardinality="0..*")
        data["ownedElements"].append(relation)
        points=u.routed_edge(parent_table,child,boxes)
        if child=="role_inheritance":
            px,py,pw,ph=boxes[parent_table];cx,cy,cw,ch=boxes[child]
            yy=py+55 if column.startswith("senior") else py+ph-55
            points=[(px+pw,yy),(px+pw+120,yy),(px+pw+120,cy+(55 if column.startswith("senior") else ch-55)),(cx,cy+(55 if column.startswith("senior") else ch-55))]
        diagram["ownedViews"].insert(0,u.view("ERDRelationshipView",diagram,relation,tail=u.ref(views[parent_table]),head=u.ref(views[child]),
            points=";".join(f"{x}:{y}" for x,y in points),lineStyle=0 if len(points)>2 else 1))
    diagram["ownedViews"].append(u.view("UMLTextView",diagram,left=80,top=max(y+h for x,y,w,h in boxes.values())+65,
        width=2450,height=160,text=note,wordWrap=True))
    record(u,name,"logical-er",["backend-oo/src/main/resources/db/migration/"],requirements="C2-01、03、11、12")


def database(u,parent):
    p=lambda name,nullable=False:(name,"BIGINT",False,True,nullable)
    id_col=("id","BIGINT",True,False,False)
    logical(u,parent,"18-shared-角色继承-逻辑ER图",[
        ("organization",80,100,[id_col,("name","VARCHAR",False,False,False)]),
        ("rbac_role",780,100,[id_col,("name","VARCHAR",False,False,False),p("owner_organization_id",True)]),
        ("role_inheritance",1660,100,[("senior_role_id","BIGINT",True,True,False),("junior_role_id","BIGINT",True,True,False)]),
        ("role_hierarchy_lock",780,570,[("id","INT",True,False,False)])],
        [("rbac_role","owner_organization_id","organization",True),("role_inheritance","senior_role_id","rbac_role",False),("role_inheritance","junior_role_id","rbac_role",False)],
        "V6：role_inheritance 组合 PK=(senior_role_id,junior_role_id)，两列分别 FK→rbac_role.id，反向索引 junior_role_id，CHECK 两列不同。\nrole_hierarchy_lock 只有预置 id=1，与角色无 FK；lockGraph() 获取串行写锁。无环、同归属与增量授权由同一事务的应用规则保障，数据库 CHECK 不检测传递环。\n两版迁移结构一致，分别存入 ooad_rbac1_func / ooad_rbac1_oo；展示主题相关列。")
    logical(u,parent,"19-shared-业务申请-逻辑ER图",[
        ("organization",80,90,[id_col,("name","VARCHAR",False,False,False)]),
        ("business_system",870,90,[id_col,("code","VARCHAR",False,False,False),p("owner_organization_id")]),
        ("employee",80,640,[id_col,("username","VARCHAR",False,False,False),p("organization_id",True)]),
        ("simulated_request",1660,640,[id_col,p("system_id"),p("applicant_id",True),("title","VARCHAR",False,False,False),("status","VARCHAR",False,False,False),("deleted_at","DATETIME",False,False,True)])],
        [("business_system","owner_organization_id","organization",False),("employee","organization_id","organization",True),
         ("simulated_request","system_id","business_system",False),("simulated_request","applicant_id","employee",True)],
        "V4/V7：status∈PENDING,APPROVED,WITHDRAWN,REJECTED；deleted_at 非空仅允许 REJECTED。逻辑删除不会新增 DELETED 枚举值。\nV7 保留旧数据 applicant_id=NULL；此类旧单不能执行本人专属操作。索引：(system_id,id)、(system_id,deleted_at,id)。查询必须排除 deleted_at 非空项。\n省略通用时间字段；鉴权通过业务系统归属与组织策略计算，申请表不保存权限快照。")


def components(u,parent,name,items,pairs,note,sources,version="shared"):
    g=Graph(u,parent,name)
    for index,(key,label) in enumerate(items):
        g.add(key,label,80+(index%3)*850,90+(index//3)*370,610,170)
    for a,b in pairs:g.link(a,b)
    g.note(note);g.finish();record(u,name,"component",sources,version=version)


def structures(u,parent):
    components(u,parent,"20-func-功能模块结构图",[
        ("http","HTTP 入口\nAuth/Management/RoleInheritance/BusinessRequestController"),
        ("inherit","继承维护\nRoleInheritanceService.change"),("rules","继承规则过程\nRoleInheritanceRules"),
        ("query","管理查询与维护\nManagementQueryService / ManagementMaintenanceService"),
        ("auth","统一实时授权\nAuthorizationService"),("assignment","分配与组织策略\nAssignmentService / OrganizationPermissionService"),
        ("business","申请业务过程\nBusinessRequestService.change / applyChange"),
        ("store","数据访问\nSpring Data JPA / RoleInheritanceStore"),("session","登录与工作区\nSessionAuthentication / WorkspaceService")],
        [("http","inherit"),("http","query"),("http","business"),("http","session"),("inherit","rules"),("inherit","assignment"),
         ("inherit","store"),("query","auth"),("business","auth"),("business","store"),("auth","store"),("auth","session")],
        "组件符号用于展示已存在的功能分解及调用依赖，不表示部署进程或新建类。流程与状态检查集中在各功能 Service；图计算由显式参数规则函数完成。",["backend-func/src/main/java/"],"func")
    components(u,parent,"21-oo-领域与基础设施协作图",[
        ("request","申请应用\nsimulation.BusinessRequestService"),("ports","申请端口\nRequestRepository / RequestSystems / RequestAccess"),
        ("domain","申请领域\nBusinessRequest / RequestSystem"),("inherit","角色继承应用\nRoleInheritanceService"),
        ("roleport","角色存储端口\nRoleInheritanceStore"),("hierarchy","角色图领域\nrolepermission.domain.RoleHierarchy"),
        ("infra","申请基础设施\nJpaRequestRepository / RequestContextAdapter"),("jpa","JPA 实体与仓储\nSimulatedRequest / JpaRoleInheritanceStore 等"),
        ("auth","C1 实时授权与工作区\nAuthorizationService / WorkspaceService")],
        [("request","ports"),("request","domain"),("infra","ports"),("infra","domain"),("infra","jpa"),("infra","auth"),
         ("inherit","roleport"),("inherit","hierarchy"),("jpa","roleport"),("auth","roleport")],
        "箭头=源码依赖，不是运行调用方向；基础设施实现端口，应用不依赖适配器。角色继承的管理范围与增量授权仍由应用 Service/AssignmentService 检查；组织策略仍沿用 C1 实现。",["backend-oo/src/main/java/"],"oo")


def flows(u,parent):
    pipeline(u,parent,"22-func-继承维护-活动流程图",[
        ("assignmentScope(actor, ROLE_PERMISSION_WRITE)","无管理员范围 → 403"),
        ("lockWrites；查询 senior/junior（同 ID 复用）","角色不存在 → 404"),
        ("requireManaged：两个角色均须在管理范围","越出范围（含共享角色）→ 403"),
        ("按 add 标志区分删除与新增","remove(key)，提交 → 204，结束","add=false","add=true"),
        ("新增要求 owner 相等且 seniorId≠juniorId","范围不同／自继承 → 409"),
        ("contains(key)；已存在幂等返回，新增边继续","边已存在 → 204，结束","exists=true","exists=false"),
        ("snapshot().wouldFormCycle → RoleInheritanceRules","会形成传递环 → 409"),
        ("requireInheritanceGrant：检查增量来源及上游使用者","无管理／授予资格 → 403；组织停用 → 409"),
        ("add(key)，Spring 事务提交，返回 204",None)],
        "主干为新增成功路径；删除、重复边为提前成功返回，右侧说明并非都属于失败。隔离级别 READ_COMMITTED，全图锁与校验、保存位于同一事务。",["backend-func/src/main/java/rolepermission/RoleInheritanceService.java","backend-func/src/main/java/assignment/AssignmentService.java"],
        operation="addRoleParent / removeRoleParent",methods=["serializesInheritanceWritesWithDisjointEndpoints"])
    pipeline(u,parent,"23-func-实时授权-活动流程图",[
        ("systemContext：员工有效且 context.canEnter(system)","员工失效 → 401；系统不可进入 → 403"),
        ("查找目标系统内的权限 code","目标系统无此动作 → 403"),
        ("收集系统归属与员工组织链；非 global 时展开角色闭包",None),
        ("isAllowed：适用组织中不存在 DISABLED","存在停用 → 拒绝（global 也不例外）"),
        ("判断 global（组织停用判断已通过）","允许，结束","global=true","global=false"),
        ("普通身份要求 sources 非空","无来源 → 拒绝"),
        ("reopened 为空：任一来源直接持有权限；非空：同一来源覆盖所有重授","不满足来源与重授条件 → 拒绝"),
        ("requireBusinessPermission 返回；查询用例保留该权限",None)],
        "继承来源为 Set，候选权限按 ID 唯一遍历，避免多路径重复。有效权限查询与实际写入共用本判定。两版行为一致，OO 的闭包对象为 RoleHierarchy。",["backend-func/src/main/java/authorization/AuthorizationService.java"],
        operation="listEmployeeEffectivePermissions / approveRequest",methods=["matchesReopenedScopesToOneInheritedPermissionSource"])
    pipeline(u,parent,"24-func-申请CRUD-活动流程图",[
        ("BusinessRequestAudit.record 包装本次操作；创建查系统，其他写入先锁申请","缺少系统／未删除申请 → 404"),
        ("创建检查 create；既有申请写入检查 read + 对应动作","实时权限不足 → 403，记录 REJECTED"),
        ("UPDATE/WITHDRAW/DELETE 核对 applicantId=当前员工","非本人或旧数据无申请人 → 403"),
        ("创建跳过已有申请状态判断；UPDATE/WITHDRAW/APPROVE/REJECT 要求 PENDING；DELETE 要求 REJECTED","状态不符 → 409；重复审批为 REQUEST_ALREADY_APPROVED"),
        ("applyChange：修改标题／变更状态／设置 deletedAt；创建为 PENDING",None),
        ("CloneFactory 映射详情；成功注册 afterCompletion；Spring 提交事务","提交失败 → FAILED；不得记录 COMMITTED"),
        ("提交成功后日志 COMMITTED；Controller 返回契约响应",None)],
        "本人专属检查只适用于修改、撤回和删除；审批／驳回允许有权限者处理本人。LIST/DETAIL 需 read、排除逻辑删除，不经过写入状态迁移。",["backend-func/src/main/java/simulation/BusinessRequestService.java","backend-func/src/main/java/simulation/BusinessRequestAudit.java"],
        operation="createRequest / updateRequest / withdrawRequest / rejectRequest / deleteRejectedRequest",methods=["updatesAndWithdrawsPendingRequest","rejectsAndDeletesOnlyOwnRejectedRequest"],browser="测试/前端/tests/oo-entry.spec.ts")


def request_state(u,parent):
    name="25-shared-申请生命周期-状态图";g=Graph(u,parent,name,"state")
    g.add("start","",80,280,25,25,initial=True)
    for key,label,x,y in [("pending","PENDING\n待审批",480,220),("approved","APPROVED\n已审批",1270,80),
                           ("withdrawn","WITHDRAWN\n已撤回",1270,500),("rejected","REJECTED\n已驳回，deleted_at=NULL",480,900),
                           ("deleted","REJECTED\n已逻辑删除，deleted_at≠NULL",1270,900)]:g.add(key,label,x,y,470,150)
    g.link("start","pending","create [request:create]")
    g.link("pending","approved","approve [read + approve]")
    g.link("pending","withdrawn","withdraw [本人；read + withdraw]")
    g.link("pending","rejected","reject [read + reject]")
    g.link("rejected","deleted","delete [本人；read + delete]")
    g.link("pending","pending","rename [本人；read + update]",[(715,220),(715,150),(1040,150),(1040,290),(950,290)])
    g.note("撤回不可重提；APPROVED/WITHDRAWN 不可再修改、驳回或删除。REJECTED 只允许本人逻辑删除；deleted_at 非空项不再被列表/详情/锁定查询返回。\n图中的删除态是持久化可见性子状态，数据库 status 仍为 REJECTED，不存在 DELETED 枚举。所有转换还受系统准入和实时组织策略约束。")
    g.finish();record(u,name,"state",["backend-oo/src/main/java/simulation/domain/BusinessRequest.java","backend-func/src/main/java/simulation/BusinessRequestService.java"],
        "createRequest / updateRequest / withdrawRequest / approveRequest / rejectRequest / deleteRejectedRequest",requirements="C2-11 至 C2-16",methods=["rejectsAndDeletesOnlyOwnRejectedRequest"])


def policy_class(u,parent,version,number):
    d=u.ClassDiagram(parent,version,f"{number:02d}-{version}-组织策略-类图","disableOrganizationPermission / reopenOrganizationPermission / regrantOrganizationPermission",
        "两版组织策略仍保留 C1 Service/JPA；权限行锁、重开与直接来源重授。")
    d.add("service","assignment.OrganizationPermissionService","C",[("authorization","AuthorizationService"),("policies","OrganizationPermissionRepository"),("regrants","PermissionRegrantRepository")],
          [("changeDisabled","",""),("regrant","","")])
    # 长参数签名仍保存在 UML Operation 中，图上显示方法名以保持主图可读。
    d.views["service"]["showOperationSignature"]=False
    for key,cls,pos,interface,ops in [
        ("auth","authorization.AuthorizationService","T",False,[("assignmentScope","","")]),
        ("policy","assignment.OrganizationPermissionRepository","R",True,[]),
        ("regrant","assignment.PermissionRegrantRepository","B",True,[]),
        ("permission","rolepermission.PermissionRepository","L",True,[("findLockedBySystemIdAndCode","","")]),
        ("roles","rolepermission.RbacRoleRepository","TL",True,[("findLockedById","","")]),
        ("roleperm","assignment.RolePermissionRepository","BR",True,[]),
        ("org","organization.OrganizationRepository","TR",True,[])]:d.add(key,cls,pos,interface=interface,ops=ops)
    for key,role in [("auth","authorization"),("policy","policies"),("regrant","regrants"),("permission","permissions"),
                     ("roles","roles"),("roleperm","rolePermissions"),("org","organizations")]:d.link("service",key,role)
    d.finish()


def end_at(edge,end):
    edge["subViews"][0]["height"]=end-edge["subViews"][0]["top"]


def policy_sequence(u,parent,version,number):
    name=f"{number:02d}-{version}-组织停用重开重授-时序图"
    s=u.Sequence(parent,version,name,"disableOrganizationPermission / reopenOrganizationPermission / regrantOrganizationPermission",
        "三个独立事务，停用清理本组织及子组织重授，重开不自动恢复。")
    for key,obj,cls in [("obj","obj",None),("service","policyService","assignment.OrganizationPermissionService"),
        ("auth","authorization","authorization.AuthorizationService"),("perm","permissions","rolepermission.PermissionRepository"),
        ("policy","policies","assignment.OrganizationPermissionRepository"),("grants","regrants","assignment.PermissionRegrantRepository"),
        ("role","roles","rolepermission.RbacRoleRepository"),("roleperm","rolePermissions","assignment.RolePermissionRepository")]:s.life(key,obj,cls,label="调用者")
    s.begin("全局管理员执行三次独立写入")
    for disabled in (True,False):
        start=s.y-25;s.y+=55
        outer=s.call("obj","service",f"changeDisabled(actor, orgId, systemCode, code, {str(disabled).lower()})",duration=500)
        s.call("service","auth","scope = assignmentScope(actor, ORGANIZATION_PERMISSION_WRITE)")
        s.call("service","perm","permission = findLockedBySystemIdAndCode(system.id, code)")
        if disabled:
            s.call("service","policy","save(new OrganizationPermission(orgId, permissionId, DISABLED))")
            s.local("service","findByParentId 收集本组织及直接下属组织；该列表用于清理旧重授。")
            s.call("service","grants","deleteInScope(permissionId, affected)")
        else:
            s.call("service","policy","current = findById(key)")
            branch=s.y-20;s.y+=60
            s.call("service","policy","save(new OrganizationPermission(orgId, permissionId, REOPENED))")
            s.frame(branch,s.y-15,"当前直接策略为 DISABLED；其他状态不变")
        end_at(outer,s.y+10);s.y+=45;s.frame(start,s.y,"停用事务提交" if disabled else "重开事务提交；仍需显式重授",operator="seq");s.y+=80
    start=s.y-25;s.y+=55
    outer=s.call("obj","service","regrant(actor, orgId, systemCode, code, sourceRoleId)",duration=700)
    s.call("service","auth","scope = assignmentScope(actor, ORGANIZATION_PERMISSION_WRITE)")
    s.call("service","role","role = findLockedById(roleId)")
    s.call("service","perm","permission = findLockedBySystemIdAndCode(system.id, code)")
    s.local("service","核对角色归属；收集目标组织及系统归属组织链。")
    s.call("service","policy","reopened = findById(key)")
    s.call("service","roleperm","direct = existsById(RolePermission.Key)")
    s.call("service","policy","disabled = existsByPermissionIdAndOrganizationIdInAndStatus(...)")
    s.local("service","要求本组织 REOPENED、来源角色直接持有权限、适用链无 DISABLED；不符则抛出冲突。")
    s.call("service","grants","exists = existsById(key)")
    branch=s.y-20;s.y+=60;s.call("service","grants","save(new PermissionRegrant(orgId, roleId, permissionId))")
    s.frame(branch,s.y-15,"尚无该重授记录；已有记录幂等")
    end_at(outer,s.y+10);s.y+=45;s.frame(start,s.y,"重授事务提交；下一次授权读取最新来源",operator="seq")
    s.note("为突出策略事务，省略系统/组织读取与 Controller 映射。requireGlobal 失败为 403；状态或直接来源不满足为 409。\n两版此段结构相同；实时判权须同一个来源覆盖全部适用 REOPENED，不能混合不同角色的重授。")
    s.finish()


def request_supplement(u,parent,version,number,transitions=False):
    oo=version=="oo"
    name=f"{number:02d}-{version}-申请{'撤回驳回删除' if transitions else '发起修改'}-时序图"
    ops="withdrawRequest / rejectRequest / deleteRejectedRequest" if transitions else "createRequest / updateRequest"
    s=u.Sequence(parent,version,name,ops,"每个操作是独立 HTTP/事务；展开该用例的共同锁、实时授权与两版状态职责。")
    for key,obj,cls in [("obj","obj",None),("service","requests","simulation.BusinessRequestService"),
        ("repo","repository","simulation.infrastructure.JpaRequestRepository" if oo else "simulation.SimulatedRequestRepository"),
        ("auth","access" if oo else "authorization","simulation.infrastructure.RequestContextAdapter" if oo else "authorization.AuthorizationService"),
        ("row","row","simulation.domain.BusinessRequest" if oo else "simulation.SimulatedRequest")]:s.life(key,obj,cls,label="调用者")
    if not oo:s.life("procedures","","simulation.BusinessRequestService",static=True)
    actions=["withdraw","reject","delete"] if transitions else ["create","update"]
    s.begin("执行独立的申请操作；各操作使用符合其前置状态的申请")
    for action in actions:
        start=s.y-25;s.y+=55
        outer=s.call("obj","service",f"{action}(employeeId, {'systemCode, title' if action=='create' else 'requestId, title' if action=='update' else 'requestId'})",duration=700)
        if action!="create":s.call("service","repo","row = lock(requestId)" if oo else "row = findLockedByIdAndDeletedAtIsNull(requestId)")
        s.local("service","创建通过 byCode/systemByCode 取得系统；其他动作使用已锁申请的 systemId。所有操作由 Audit.record 包装。")
        for permission in (["create"] if action=="create" else ["read",action]):
            s.call("service","auth",f'require(employeeId, system, "request:{permission}")' if oo else f'requireBusinessPermission(employeeId, system, "request:{permission}")')
        if oo:
            if action=="create":
                s.local("service","BusinessRequest.create(system.id, employeeId, title) 创建 PENDING；此为静态工厂，见申请状态图。")
            else:
                method={"update":"rename(employeeId, title)","withdraw":"withdraw(employeeId)","reject":"reject()","delete":"delete(employeeId, UTC time)"}[action]
                s.call("service","row",method)
            s.call("service","repo","saved = save(row)")
        elif action=="create":
            s.call("service","repo","saved = save(new SimulatedRequest(system.id, employeeId, title))")
        else:
            s.call("service","procedures",f"applyChange({action.upper()}, employeeId, row, title/null)",duration=115)
            change={"update":"setTitle(title.strip())","withdraw":"setStatus(WITHDRAWN)","reject":"setStatus(REJECTED)","delete":"setDeletedAt(UTC time)"}[action]
            s.call("procedures","row",change)
        s.local("service","校验失败抛出原业务异常并回滚；成功执行 CloneFactory 映射并注册提交后审计。")
        end_at(outer,s.y+10);s.y+=45
        s.frame(start,s.y,f"{action}：独立事务提交；日志 COMMITTED",operator="seq");s.y+=80
    s.note("update/withdraw/delete 要求本人；update/withdraw 仅 PENDING，delete 仅 REJECTED。reject 不要求本人且允许自审核；撤回不能重提。\n本图省略统一 Audit.record 的回调跳转、OO 适配器内部映射和 Controller 响应，详见核心审批图与事务审计图。")
    s.finish()


def frontend(u,parent):
    components(u,parent,"35-shared-前端组件与API路由图",[
        ("app","App.vue\n版本入口、身份与工作区"),("admin","AdminConsole.vue\n管理入口与目录"),("business","BusinessPage.vue\n申请列表、详情与动作"),
        ("role","RolePermissions / AssignmentEditor\n直接授权、继承来源与继承边"),("policy","PermissionPolicy / EffectivePermissions\n组织策略与员工有效权限"),("directory","AdminDirectory / DirectorySelect\n完整目录、范围内选择"),
        ("reference","referenceData.ts\nbusinessSystems / allPages"),("api","api.ts\nedition / read / write / CSRF"),("proxy","vite.config.ts\n/func/api→18181；/oo/api→18182")],
        [("app","admin"),("app","business"),("app","api"),("directory","role"),("directory","policy"),("admin","directory"),
         ("role","reference"),("policy","reference"),("directory","api"),("reference","api"),("business","api"),("api","proxy")],
        "实线组件框为代码组织；虚线箭头为导入或请求协作。代理是本机 Vite 开发/预览已实现配置，不代表已部署的课程 Swarm 拓扑。\n前端显示按钮与权限提示只提供交互，后端每次操作仍执行实时授权。",["frontend/src/","frontend/vite.config.ts"])
    pipeline(u,parent,"36-shared-前端申请操作-活动图",[
        ("BusinessPage.load：带版本前缀读取当前系统分页","401/403：清空列表、详情和动作权限；显示错误"),
        ("展示 allowedActions；点击详情执行 details(item)","详情 401/403 清除旧数据；404 刷新列表"),
        ("发起/修改标题或选择审批、驳回、撤回、删除",None),
        ("api.write 先获取当前 Cookie 的 CSRF，再发送动作请求","网络/接口错误：保留错误回执"),
        ("成功更新列表与详情状态；创建/修改/删除按需重新 load",None)],
        "write 的错误分支：action() 收到 403/404/409 后重新 load，再按需 details；submitEditor() 保留编辑器与回执，不保证编辑错误自动刷新。\n按钮可见性不能替代后端鉴权；列表/详情拒绝时清空旧数据。",["frontend/src/BusinessPage.vue","frontend/src/api.ts"],"shared",methods=["requiresReadPermissionForRequests"],browser="测试/前端/tests/request-read.spec.ts")
    excel_browse(u,parent)
    pipeline(u,parent,"39-shared-角色权限页面-活动图",[
        ("RolePermissions.load 并行读取系统、权限定义、直接与继承分页",None),
        ("业务系统以下拉名称展示；先选系统再过滤动作",None),
        ("直接授予：允许 PUT/DELETE；继承权限标记无法修改",None),
        ("change 只写 /roles/{id}/permissions/{permissionId}","接口拒绝/冲突：显示错误，不改为直接授权"),
        ("重读直接权限页；删空末页回退；继承来源通过重新读取更新",None)],
        "继承权限来自 inherited-permissions，编辑的是当前角色的直接授权，不会修改提供权限的角色。\n继承关系由 AssignmentEditor 的 parents 入口维护；需要删除来源边或来源授权才能撤销继承权限。",["frontend/src/RolePermissions.vue","frontend/src/AssignmentEditor.vue"],"shared",browser="测试/前端/tests/role-permissions.spec.ts")


def excel_browse(u,parent):
    """目录翻页与选择项全页读取分别画出，循环退出条件来自 allPages。"""
    name="37-shared-Excel全量浏览-活动图";g=Graph(u,parent,name,"activity")
    g.add("start","",595,40,24,24,initial=True)
    g.add("entry","App 身份进入 AdminConsole\n选择员工、组织或权限清单",380,140,455,95)
    g.add("read","AdminDirectory 请求 page/pageSize\n后端按管理范围筛选；拒绝则显示错误并结束",380,340,455,100)
    g.add("display","展示当前页原表字段、来源和 total\n等待用户翻页或进入编辑表单",380,550,455,100)
    g.add("choice","",577,750,60,60,decision=True)
    g.add("page","选定下一页 page",60,930,440,95)
    g.add("form","进入编辑表单，读取组织/系统选择项",1050,930,520,95)
    g.add("all","referenceData.allPages：每页最多 100\n累加 items；判断是否达到 total 或遇到空页",1050,1150,520,115)
    g.add("loop","",1275,1360,60,60,decision=True)
    g.add("options","返回范围内完整选择项\n继续填写表单",1050,1530,520,100)
    g.add("end","保留当前目录页，完成本次浏览",380,1180,455,95)
    for a,b,label in [("start","entry",""),("entry","read",""),("read","display","读取成功"),
        ("display","choice",""),("choice","page","翻页"),("page","read",""),("choice","form","编辑表单"),
        ("choice","end","停止浏览"),("form","all",""),("all","loop",""),("loop","options","已完成")]:g.link(a,b,label)
    g.link("loop","all","未完成：page += 1",[(1335,1390),(1750,1390),(1750,1207),(1570,1207)])
    g.note("目录表按需分页，allPages 只用于表单选择项，不是一次下载全部员工。全局原表基线：10,000 员工、111 组织目录、359 功能清单；局部管理员只看到自己的范围。\n原表和演示角色/申请分别维护；电话脱敏及未匹配组织标记沿用现有页面。浏览目录和进入表单为用户选择，图中没有强制串联。")
    g.finish();record(u,name,"activity",["frontend/src/AdminDirectory.vue","frontend/src/referenceData.ts","backend-oo/src/main/java/organization/ManagementQueryService.java"],
        version="shared",requirements="C2-09",browser="测试/前端/tests/excel-review.spec.ts")


def login_sequence(u,parent):
    name="34-shared-双标签页登录退出-时序图"
    s=u.Sequence(parent,"oo",name,"getCsrf / login / getCurrentSession / logout","共用前端按 URL 固定版本；独立 Cookie 与 Redis 命名空间。")
    for key,obj,label in [("obj","obj","调用者"),("front","app","App.vue / api.ts"),("proxy","proxy","ViteProxy"),
                          ("func","func","AuthController"),("oo","oo","AuthController"),("redis","redis","Spring Session Redis")]:
        s.life(key,obj,label=label)
    s.begin("同一浏览器打开 /func/ 与 /oo/ 两个标签页")
    for version,key in [("func","func"),("oo","oo")]:
        start=s.y-25;s.y+=55
        front=s.call("obj","front",f"signIn()  // {version} 标签页",duration=550)
        proxy=s.call("front","proxy",f"GET /{version}/api/auth/csrf",duration=115)
        s.call("proxy",key,"GET /api/auth/csrf")
        proxy2=s.call("front","proxy",f"POST /{version}/api/auth/login + X-CSRF-TOKEN",duration=270)
        controller=s.call("proxy",key,"login(input, request, response)",duration=190)
        s.call(key,"redis",f"Spring Session 保存 {version} 会话；响应独立 Cookie")
        for edge in (proxy2,controller):end_at(edge,s.y+5)
        end_at(front,s.y+10);s.y+=45
        s.frame(start,s.y,f"{version} 登录；前端按 workspacePath 进入本版工作区",operator="seq");s.y+=80
    start=s.y-25;s.y+=55
    front=s.call("obj","front","signOut()  // 仅 func",duration=550)
    proxy=s.call("front","proxy","GET /func/api/auth/csrf；POST /func/api/auth/logout",duration=270)
    controller=s.call("proxy","func","logout(request, response)",duration=190)
    s.call("func","redis","SessionAuthentication.end → 当前 func 会话失效")
    for edge in (proxy,controller):end_at(edge,s.y+5)
    end_at(front,s.y+10);s.y+=45;s.frame(start,s.y,"func 返回 204 并回到 /func/；oo Cookie 不变",operator="seq");s.y+=80
    front=s.call("obj","front","loadSession()  // oo 标签页",duration=400)
    proxy=s.call("front","proxy","GET /oo/api/auth/me",duration=190)
    s.call("proxy","oo","me(employeeId)  // oo 会话仍有效")
    end_at(proxy,s.y+5)
    end_at(front,s.y+15)
    s.note("这是协议级时序：HTTP 与 Redis 箭头表示协议/框架协作，不是 AuthController 直接操作 Redis。\n控制器内部调用 sessions.verify → workspaces.current → sessions.start；start 轮换会话 ID、清旧 CSRF，仅保存可信员工 ID。\n会话配置来自 application.yaml；按版本独立库写业务数据，不画成复制同步。")
    s.finish();s.entry.update(version="shared",sources=["frontend/src/App.vue","frontend/src/api.ts","frontend/vite.config.ts","backend-oo/src/main/java/authentication/SessionAuthentication.java","backend-oo/src/main/resources/application.yaml","backend-func/src/main/resources/application.yaml"])


def commit_sequence(u,parent):
    name="38-shared-事务提交与审计-时序图"
    s=u.Sequence(parent,"oo",name,"approveRequest","审计包装、lambda、事务提交和 afterCompletion 的真实次序；不把 save 等同于提交。")
    for key,obj,label in [("obj","obj","调用者"),("proxy","txProxy","Spring TransactionInterceptor"),("service","requests","BusinessRequestService"),
                          ("audit","","BusinessRequestAudit"),("syncManager","","TransactionSynchronizationManager"),
                          ("tx","txManager","JpaTransactionManager"),("sync","sync","TransactionSynchronization 匿名实现")]:s.life(key,obj,label=label,static=key in ("audit","syncManager"))
    s.begin()
    proxy=s.call("obj","proxy","invoke(approve)  // 开启业务事务",duration=1300)
    service=s.call("proxy","service","approve(employeeId, requestId)",duration=800)
    audit=s.call("service","audit","record(APPROVE, actor, requestId, operation)",duration=600)
    work=s.call("audit","service","result = operation.apply(target)",duration=105)
    s.local("service","lambda 执行请求行锁、实时授权、状态修改及映射；此时尚未提交。")
    s.call("audit","syncManager","registerSynchronization(sync)")
    for edge in (service,audit):end_at(edge,s.y+5)
    s.y+=85
    commit=s.call("proxy","tx","commit(status)",duration=350)
    callback=s.call("tx","sync","afterCompletion(STATUS_COMMITTED)",duration=190)
    s.call("sync","audit","log(APPROVE, actor, target, COMMITTED, '-')")
    for edge in (commit,callback):end_at(edge,s.y+5)
    end_at(proxy,s.y+15)
    s.note("提交与回调之间省略 Spring 事务模板内部的 TransactionSynchronizationUtils 分发。匿名回调实现定义于 Audit.record，不能画成 record 在提交后继续执行。\n事务未提交：afterCompletion 记录 FAILED / TRANSACTION_NOT_COMMITTED。业务异常被 record 捕获时立即 REJECTED 并重新抛出，不注册成功回调；运行时异常为 FAILED。\nfunc/oo 仅日志 logger/version 不同；提交前失败不能记录 COMMITTED。")
    s.finish();s.entry.update(version="shared",sources=["backend-func/src/main/java/simulation/BusinessRequestAudit.java","backend-oo/src/main/java/simulation/BusinessRequestAudit.java"])


def login_class(u,parent):
    d=u.ClassDiagram(parent,"oo","40-shared-登录与工作区-类图","login / getCurrentSession / logout / listBusinessSystems",
        "两版复用已验证 C1 登录及工作区，差异位于 Cookie/Redis/数据库配置。")
    d.add("service","authentication.WorkspaceService","C",[("employees","EmployeeRepository"),("scopes","AdministratorScopeRepository"),
          ("organizations","OrganizationRepository"),("systems","BusinessSystemRepository")],[("current","",""),("context","",""),("findActiveEmployee","","")])
    for key,cls,pos,interface,ops in [("controller","authentication.AuthController","L",False,[("login","",""),("me","","")]),
        ("session","authentication.SessionAuthentication","B",False,[("verify","",""),("start","",""),("end","","")]),
        ("employees","organization.EmployeeRepository","T",True,[]),("scopes","authorization.AdministratorScopeRepository","TL",True,[]),
        ("orgs","organization.OrganizationRepository","TR",True,[]),("systems","simulation.BusinessSystemRepository","R",True,[])]:d.add(key,cls,pos,interface=interface,ops=ops)
    for key in ("controller","session"):d.views[key]["showOperationSignature"]=False
    d.link("controller","service","workspaces");d.link("controller","session","sessions")
    for key,role in [("employees","employees"),("scopes","scopes"),("orgs","organizations"),("systems","systems")]:d.link("service",key,role)
    d.finish();d.entry["version"]="shared"


def competing_transitions(u,parent):
    """用锁的两种合法先后表达竞争，不虚构固定线程胜者或异步 Java API。"""
    name="41-shared-申请并发状态竞争-活动图"
    g=Graph(u,parent,name,"activity")
    g.add("start","",800,30,24,24,initial=True)
    g.add("launch","同一 PENDING 申请：审批请求与本人撤回请求并发到达",570,120,490,100)
    g.add("lock","各事务执行 findLockedByIdAndDeletedAtIsNull\nMySQL 悲观写锁决定谁先继续；另一事务等待",570,300,490,115)
    g.add("choice","",785,475,65,65,decision=True)
    g.add("approve","审批先持锁：实时 read+approve 检查\n状态 PENDING → APPROVED",100,650,500,115)
    g.add("withdraw","撤回先持锁：实时 read+withdraw 和本人检查\n状态 PENDING → WITHDRAWN",1040,650,530,115)
    g.add("commitA","审批事务提交并释放锁；成功日志 COMMITTED\n审批响应 200",100,900,500,115)
    g.add("commitB","撤回事务提交并释放锁；成功日志 COMMITTED\n撤回响应 200",1040,900,530,115)
    g.add("readA","等待的撤回事务取得锁，读到 APPROVED\n本人检查后，待审批状态检查失败",100,1170,500,115)
    g.add("readB","等待的审批事务取得锁，读到 WITHDRAWN\n待审批状态检查失败",1040,1170,530,115)
    g.add("rejected","失败事务回滚；记录 REJECTED / REQUEST_STATE_CONFLICT\n响应 409；已提交的胜者状态保持",570,1470,630,115)
    for a,b,label in [("start","launch",""),("launch","lock",""),("lock","choice",""),
        ("choice","approve","审批先取得锁"),("choice","withdraw","撤回先取得锁"),
        ("approve","commitA","授权通过"),("withdraw","commitB","授权及本人检查通过"),
        ("commitA","readA",""),("commitB","readB",""),("readA","rejected",""),("readB","rejected","")]:g.link(a,b,label)
    g.note("前提：双方 Cookie/CSRF、系统准入及动作权限有效，且撤回操作者为本人。调度胜者不固定；授权失败另按通用拒绝处理。\nOO 由 BusinessRequest 维护状态，func 由 applyChange 检查；两版都保留相同 JPA 行锁与事务边界。\n同理，驳回与审批只能提交一个状态；重复审批的 409 业务码为 REQUEST_ALREADY_APPROVED。")
    g.finish();record(u,name,"activity",["backend-func/src/main/java/simulation/SimulatedRequestRepository.java","backend-oo/src/main/java/simulation/SimulatedRequestRepository.java","backend-oo/src/main/java/simulation/domain/BusinessRequest.java"],
        "approveRequest / withdrawRequest / rejectRequest",requirements="C2-13、14、16",methods=["serializesCompetingRequestTransitions","approvesOnlyOnceConcurrently"])


def alternative_paths(u,parent):
    """展示删边后的有来源/无来源分支，来源去重与政策检查不被省略。"""
    name="42-shared-多路径权限与删边-活动图"
    g=Graph(u,parent,name,"activity")
    g.add("start","",570,30,24,24,initial=True)
    g.add("setup","员工持有 A；A→B→D 且 A→C→D\nD 直接持有 request:approve；查看权限独立有效",330,120,570,115)
    g.add("unique","inheritedRoles({A})={A,B,C,D}\nD 与候选权限 ID 去重，有效权限只返回一条",330,360,570,115)
    g.add("delete","管理员删除一条继承边并提交\n员工原 Cookie 发起下一次审批，重新读 MySQL",330,610,570,115)
    g.add("recompute","展开最新闭包，检查组织策略与权限来源\n是否仍有一条可用来源？",330,860,570,115)
    g.add("choice","",585,1055,65,65,decision=True)
    g.add("allow","仍可到达 D（或存在其他有效直接来源）\n且组织策略通过 → 200，允许审批",1030,1260,570,120)
    g.add("deny","唯一来源已断开，或组织策略不允许\n→ 403，申请保持 PENDING",80,1260,570,120)
    for a,b,label in [("start","setup",""),("setup","unique",""),("unique","delete",""),("delete","recompute",""),
        ("recompute","choice",""),("choice","allow","是"),("choice","deny","否")]:g.link(a,b,label)
    g.note("只删除 A→B 时 A→C→D 仍可用；再删除 A→C 后才失去 D。撤权针对有效来源，而非删任一边必拒绝。\n任一适用 DISABLED 优先拒绝。存在 REOPENED 时必须同一个直接权限来源覆盖所有适用重授，不能拼接不同角色。\n这是当前算法的具体推演；HTTP 重开/多来源测试及图 08/16 的唯一继承路径测试共同提供证据。")
    g.finish();record(u,name,"activity",["backend-func/src/main/java/rolepermission/RoleInheritanceRules.java","backend-oo/src/main/java/rolepermission/domain/RoleHierarchy.java","backend-oo/src/main/java/authorization/AuthorizationService.java"],
        "listEmployeeEffectivePermissions / removeRoleParent / approveRequest",requirements="C2-01、02、04、06",methods=["matchesReopenedScopesToOneInheritedPermissionSource","inheritanceRevocationAppliesToExistingSession"])


def build(project,u):
    """新增图统一放入现有原生项目的第三组，保持原有 01–16 图名不变。"""
    parent=u.item("UMLModel",project,name="C2 已实现内容 · 补充图",ownedElements=[]);project["ownedElements"].append(parent)
    chen(u,parent);database(u,parent);structures(u,parent);flows(u,parent);request_state(u,parent)
    for version,base in (("func",26),("oo",28)):
        policy_class(u,parent,version,base);policy_sequence(u,parent,version,base+1)
    for version,base in (("func",30),("oo",32)):
        request_supplement(u,parent,version,base);request_supplement(u,parent,version,base+1,True)
    login_sequence(u,parent);frontend(u,parent);commit_sequence(u,parent);login_class(u,parent)
    competing_transitions(u,parent);alternative_paths(u,parent)
