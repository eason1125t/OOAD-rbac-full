"""生成 C2 原生 StarUML 模型；由 export-staruml.ps1 调用 StarUML CLI 导出。

图数据对应两版现有 Java 实现，关系、消息、执行条均保存为可编辑的 UML 图元。
不修改业务代码；生成前校验源码符号、引用及布局，导出后再检查实际 SVG。
"""

from __future__ import annotations

import argparse
import heapq
import itertools
import json
import math
import re
import sys
from pathlib import Path
from xml.etree import ElementTree as ET

HERE = Path(__file__).resolve().parent
C2 = HERE.parents[1]
IDS = itertools.count(1)
INK = "#30435b"
FONT = "Microsoft YaHei;13;0"
CATALOG: list[dict] = []


def split_java_parameters(value):
    """仅在泛型和注解括号之外切分参数，不误拆 Map<K, V>。"""
    result, start, depth = [], 0, 0
    for index, char in enumerate(value):
        depth += char in "<(["
        depth -= char in ">)]"
        if char == "," and depth == 0:
            result.append(value[start:index].strip())
            start = index+1
    if value[start:].strip():
        result.append(value[start:].strip())
    return result


def item(kind, parent=None, **fields):
    value = {"_type": kind, "_id": f"c2-uml-{next(IDS):05d}", **fields}
    if parent:
        value["_parent"] = ref(parent)
    return value


def ref(value):
    return {"$ref": value["_id"]}


def view(kind, parent, model=None, **fields):
    return item(kind, parent, **({"model": ref(model)} if model else {}),
                font=FONT, lineColor=INK, fontColor=INK, **fields)


def source(version, qualified):
    """按实际包名检查类存在，避免把设计愿望画成已实现的类。"""
    path = C2 / f"backend-{version}/src/main/java" / (qualified.replace(".", "/") + ".java")
    if not path.is_file():
        raise ValueError(f"类不存在: {path}")
    return path


def model_class(parent, version, qualified, fields=(), operations=(), interface=False):
    path = source(version, qualified)
    text = path.read_text(encoding="utf-8")
    node = item("UMLInterface" if interface else "UMLClass", parent,
                name=qualified.rsplit(".", 1)[-1], documentation=f"源码: {path.relative_to(C2).as_posix()}",
                attributes=[], operations=[])
    for name, type_name in fields:
        if not re.search(rf"\b{re.escape(name)}\b", text):
            raise ValueError(f"字段不存在: {qualified}.{name}")
        node["attributes"].append(item("UMLAttribute", node, name=name, type=type_name, visibility="private"))
    for signature in operations:
        # 参数、返回类型与修饰符从源码声明读取，手工清单只选择展示哪些方法。
        name, args, result = signature
        if not re.search(rf"\b{re.escape(name)}\s*\(", text):
            raise ValueError(f"方法不存在: {qualified}.{name}")
        declaration = re.search(r"(?m)^[ \t]*(?P<mods>(?:(?:public|private|protected|static|final|synchronized)[ \t]+)*)"
                                r"(?:<[^;\n(){}]+>[ \t]+)?(?!return\b|throw\b|new\b)"
                                r"(?P<result>[\w.$]+(?:<[^;\n(){}]+>)?(?:\[\])?)[ \t]+" + re.escape(name) + r"[ \t]*\(", text)
        if not declaration:
            raise ValueError(f"无法核对方法声明: {qualified}.{name}")
        modifiers = declaration.group("mods").split()
        visibility = next((v for v in ("public", "protected", "private") if v in modifiers),
                          "public" if interface else "package")
        operation = item("UMLOperation", node, name=name, visibility=visibility,
                         isStatic="static" in modifiers, parameters=[])
        position, depth = declaration.end(), 1
        end = position
        while depth:
            depth += text[end] == "("
            depth -= text[end] == ")"
            end += 1
        for arg in split_java_parameters(text[position:end-1]):
            cleaned = re.sub(r"@\w+(?:\([^)]*\))?", "", arg)
            cleaned = re.sub(r"\bfinal\s+", "", cleaned)
            actual_type, actual_name = " ".join(cleaned.split()).rsplit(" ", 1)
            operation["parameters"].append(item("UMLParameter", operation, name=actual_name,
                                                type=actual_type, direction="in"))
        result = declaration.group("result")
        if result:
            operation["parameters"].append(item("UMLParameter", operation, name="", type=result, direction="return"))
        node["operations"].append(operation)
    return node


def segment_hits(a, b, box):
    """线段与矩形内部求交；端点仅接触边框不算穿框。"""
    x, y, w, h = box
    lo, hi = 0.0, 1.0
    for start, delta, low, high in ((a[0], b[0]-a[0], x+.5, x+w-.5),
                                     (a[1], b[1]-a[1], y+.5, y+h-.5)):
        if abs(delta) < .00001:
            if not low < start < high:
                return False
        else:
            p, q = sorted(((low-start)/delta, (high-start)/delta))
            lo, hi = max(lo, p), min(hi, q)
            if lo >= hi:
                return False
    return lo < hi


def routed_edge(a, b, boxes):
    """先尝试直线，再在矩形间隙搜索最短正交路线，禁止穿过其他类框。"""
    ax, ay, aw, ah = boxes[a]
    bx, by, bw, bh = boxes[b]
    ac, bc = (ax+aw/2, ay+ah/2), (bx+bw/2, by+bh/2)
    blocked = {key: box if key in (a, b) else (box[0]-18, box[1]-18, box[2]+36, box[3]+36)
               for key, box in boxes.items()}

    def boundary(center, toward, rect):
        x, y, w, h = rect
        dx, dy = toward[0]-center[0], toward[1]-center[1]
        scale = min(w/2/abs(dx) if dx else math.inf, h/2/abs(dy) if dy else math.inf)
        return round(center[0]+dx*scale), round(center[1]+dy*scale)

    direct = [boundary(ac, bc, boxes[a]), boundary(bc, ac, boxes[b])]
    if not any(segment_hits(*direct, box) for key, box in blocked.items() if key not in (a, b)):
        return direct
    starts = [(ax, ac[1]), (ax+aw, ac[1]), (ac[0], ay), (ac[0], ay+ah)]
    ends = [(bx, bc[1]), (bx+bw, bc[1]), (bc[0], by), (bc[0], by+bh)]
    xs = sorted({p[0] for p in starts+ends} | {v for x, y, w, h in boxes.values() for v in (x-32, x+w+32)})
    ys = sorted({p[1] for p in starts+ends} | {v for x, y, w, h in boxes.values() for v in (y-32, y+h+32)})
    nodes = {(x, y) for x in xs for y in ys
             if not any(qx < x < qx+w and qy < y < qy+h for qx, qy, w, h in blocked.values())}
    neighbors = {p: [] for p in nodes}
    for axis in (0, 1):
        for fixed in (ys if axis == 0 else xs):
            row = sorted((p for p in nodes if p[1-axis] == fixed), key=lambda p: p[axis])
            for p, q in zip(row, row[1:]):
                if not any(segment_hits(p, q, box) for box in blocked.values()):
                    distance = abs(p[axis]-q[axis])
                    neighbors[p].append((q, distance, axis))
                    neighbors[q].append((p, distance, axis))
    queue = [(0, p, -1, [p]) for p in starts]
    heapq.heapify(queue)
    seen = set()
    while queue:
        cost, p, direction, path = heapq.heappop(queue)
        if (p, direction) in seen:
            continue
        seen.add((p, direction))
        if p in ends:
            simple = []
            for point in path:
                if len(simple) > 1 and ((simple[-2][0] == simple[-1][0] == point[0])
                                        or (simple[-2][1] == simple[-1][1] == point[1])):
                    simple.pop()
                simple.append(point)
            return simple
        for q, distance, turn in neighbors[p]:
            heapq.heappush(queue, (cost+distance+(60 if direction not in (-1, turn) else 0), q, turn, path+[q]))
    raise ValueError(f"无法避让类框: {a} -> {b}")


class ClassDiagram:
    """原生 Class/Interface/Association/Realization，共用布局与自动检查。"""

    POS = {"C": (1030, 580), "L": (90, 580), "R": (1970, 580),
           "T": (1030, 80), "B": (1030, 1080), "TL": (90, 80),
           "TR": (1970, 80), "BL": (90, 1080), "BR": (1970, 1080)}

    def __init__(self, package, version, name, operation, summary):
        self.version, self.package = version, package
        self.diagram = item("UMLClassDiagram", package, name=name, ownedViews=[])
        self.nodes, self.views, self.boxes = {}, {}, {}
        self.edges = []
        self.entry = {"name": name, "version": version, "kind": "class", "operationId": operation,
                      "summary": summary, "sources": []}

    def add(self, key, qualified, position, fields=(), ops=(), interface=False):
        node = model_class(self.package, self.version, qualified, fields, ops, interface)
        self.package["ownedElements"].append(node)
        x, y = self.POS[position]
        box = (x, y, 540, 265)
        node_view = view("UMLInterfaceView" if interface else "UMLClassView", self.diagram, node,
                         left=x, top=y, width=box[2], height=box[3],
                         fillColor="#e7f0fd" if position == "C" else "#ffffff", stereotypeDisplay="label")
        self.diagram["ownedViews"].append(node_view)
        self.nodes[key], self.views[key], self.boxes[key] = node, node_view, box
        self.entry["sources"].append(source(self.version, qualified).relative_to(C2).as_posix())

    def link(self, a, b, role=None, realization=False):
        if role:
            path = self.nodes[a]["documentation"].removeprefix("源码: ")
            declaration = (C2/path).read_text(encoding="utf-8")
            if not re.search(r"\b(?:private|protected|public)\s+(?:final\s+)?[\w<>?,. \[\]]+\s+"
                             + re.escape(role) + r"\s*(?:;|=)", declaration):
                raise ValueError(f"关联字段不存在: {path}#{role}")
            relation = item("UMLAssociation", self.package, name="")
            relation["end1"] = item("UMLAssociationEnd", relation, reference=ref(self.nodes[a]), navigable="unspecified")
            relation["end2"] = item("UMLAssociationEnd", relation, reference=ref(self.nodes[b]),
                                     navigable="navigable", name=role)
            kind = "UMLAssociationView"
        else:
            relation = item("UMLInterfaceRealization" if realization else "UMLDependency", self.package,
                            source=ref(self.nodes[a]), target=ref(self.nodes[b]), name="")
            kind = "UMLInterfaceRealizationView" if realization else "UMLDependencyView"
        points = routed_edge(a, b, self.boxes)
        self.package["ownedElements"].append(relation)
        self.diagram["ownedViews"].insert(0, view(kind, self.diagram, relation,
            tail=ref(self.views[a]), head=ref(self.views[b]), lineStyle=0 if len(points) > 2 else 1,
            points=";".join(f"{x}:{y}" for x, y in points), showVisibility=False, showMultiplicity=False))
        self.edges.append((a, b, points))

    def finish(self):
        for (a, p), (b, q) in itertools.combinations(self.boxes.items(), 2):
            if p[0] < q[0]+q[2] and q[0] < p[0]+p[2] and p[1] < q[1]+q[3] and q[1] < p[1]+p[3]:
                raise ValueError(f"类框重叠: {a}, {b}")
        for a, b, points in self.edges:
            for p, q in zip(points, points[1:]):
                if any(segment_hits(p, q, box) for key, box in self.boxes.items() if key not in (a, b)):
                    raise ValueError(f"连线穿框: {a}, {b}")
        self.package["ownedElements"].append(self.diagram)
        CATALOG.append(self.entry)


def inheritance_class(package, version, number):
    oo = version == "oo"
    graph = "RoleHierarchy" if oo else "RoleInheritanceGraph"
    d = ClassDiagram(package, version, f"{number:02d}-{version}-新增角色继承-类图", "addRoleParent",
                     "同归属、非自继承、无环、授权增量与串行写锁；OO 端口与领域图 / func 静态规则。")
    d.add("service", "rolepermission.RoleInheritanceService", "C",
          [("hierarchy", "RoleInheritanceStore"), ("roles", "RbacRoleRepository"),
           ("authorization", "AuthorizationService"), ("assignments", "AssignmentService")],
          [("change", "actor:Long, seniorId:Long, juniorId:Long, add:boolean", "void")])
    d.add("controller", "rolepermission.RoleInheritanceController", "L", ops=[("add", "actor:Long, roleId:Long, parentRoleId:Long", "ResponseEntity")])
    d.add("store", "rolepermission.RoleInheritanceStore", "R", ops=[("lockWrites", "", "void"),
          ("snapshot", "", graph), ("contains", "key:RoleInheritanceKey", "boolean"),
          ("add", "key:RoleInheritanceKey", "void"), ("remove", "key:RoleInheritanceKey", "void")], interface=oo)
    d.add("auth", "authorization.AuthorizationService", "T", ops=[("assignmentScope", "employeeId:Long, action:String", "Optional<AdministratorScope>")])
    d.add("assign", "assignment.AssignmentService", "B", ops=[("requireManaged", "scope:AdministratorScope, owner:Long", "void"),
          ("requireInheritanceGrant", f"scope:AdministratorScope, senior:RbacRole, juniorId:Long, graph:{graph}", "void")])
    d.add("roles", "rolepermission.RbacRoleRepository", "TL", interface=True)
    d.add("graph", f"rolepermission.{'domain.' if oo else ''}{graph}", "BR", fields=[("parents", "Map<Long, Set<Long>>")],
          ops=[("wouldFormCycle", "seniorRoleId:long, juniorRoleId:long" if oo else "seniorRoleId:Long, juniorRoleId:Long", "boolean"),
               ("inheritedRoles", "roots:Set<Long>", "Set<Long>")])
    if oo:
        d.add("impl", "rolepermission.JpaRoleInheritanceStore", "TR", ops=[("snapshot", "", graph), ("lockWrites", "", "void")])
        d.link("impl", "store", realization=True)
    else:
        d.add("rules", "rolepermission.RoleInheritanceRules", "TR", ops=[("wouldFormCycle", "parents:Map, seniorRoleId:long, juniorRoleId:long", "boolean"),
              ("reachable", "edges:Map, roots:Set<Long>", "Set<Long>")])
        d.link("graph", "rules")
    for a, b, role in [("controller", "service", "inheritance"), ("service", "store", "hierarchy"),
                        ("service", "auth", "authorization"), ("service", "assign", "assignments"), ("service", "roles", "roles")]:
        d.link(a, b, role)
    d.link("service", "graph")
    d.finish()


def effective_class(package, version, number):
    graph = "RoleHierarchy" if version == "oo" else "RoleInheritanceGraph"
    d = ClassDiagram(package, version, f"{number:02d}-{version}-有效权限-类图", "listEmployeeEffectivePermissions",
                     "查询用例使用统一 AuthorizationService；组织停用优先，多路径来源 Set 去重。")
    d.add("auth", "authorization.AuthorizationService", "C", [("hierarchy", "RoleInheritanceStore"),
          ("employeeRoles", "EmployeeRoleRepository"), ("rolePermissions", "RolePermissionRepository"),
          ("organizationPermissions", "OrganizationPermissionRepository"), ("permissions", "PermissionRepository"),
          ("workspaces", "WorkspaceService")], [("effectivePermissions", "employeeId:Long, system:BusinessSystem", "List<Permission>"),
          ("requireBusinessPermission", "employeeId:Long, system:BusinessSystem, code:String", "void")])
    d.add("query", "organization.ManagementQueryService", "L", [("authorization", "AuthorizationService")],
          [("effectivePermissions", "operator:Long, employeeId:Long, systemCode:String, page:Pageable", "Page<Permission>")])
    d.add("hierarchy", "rolepermission.RoleInheritanceStore", "R", ops=[("snapshot", "", graph)], interface=version == "oo")
    d.add("workspaces", "authentication.WorkspaceService", "T", ops=[("findActiveEmployee", "employeeId:Long", "Optional<Employee>"), ("context", "employee:Employee", "Context")])
    d.add("org", "assignment.OrganizationPermissionRepository", "B", interface=True,
          ops=[("findByPermissionIdAndOrganizationIdIn", "permissionId:Long, organizations:Set<Long>", "List<OrganizationPermission>")])
    d.add("employee", "assignment.EmployeeRoleRepository", "TL", interface=True, ops=[("findDirectRoleIds", "employeeId:Long", "Set<Long>")])
    d.add("permission", "rolepermission.PermissionRepository", "BL", interface=True,
          ops=[("findBySystemIdOrderByIdAsc", "systemId:Long", "List<Permission>")])
    d.add("roleperm", "assignment.RolePermissionRepository", "BR", interface=True,
          ops=[("existsByPermissionIdAndRoleIdIn", "permissionId:Long, roleIds:Set<Long>", "boolean"),
               ("hasRegrantedSource", "permissionId:Long, roleIds:Set<Long>, organizations:Set<Long>, count:long", "boolean")])
    d.add("graph", f"rolepermission.{'domain.' if version == 'oo' else ''}{graph}", "TR",
          ops=[("inheritedRoles", "roots:Set<Long>", "Set<Long>")])
    d.link("query", "auth", "authorization")
    for key, role in [("hierarchy", "hierarchy"), ("workspaces", "workspaces"), ("org", "organizationPermissions"),
                      ("employee", "employeeRoles"), ("permission", "permissions"), ("roleperm", "rolePermissions")]:
        d.link("auth", key, role)
    d.link("auth", "graph")
    d.finish()


def approval_class(package, version, number):
    oo = version == "oo"
    d = ClassDiagram(package, version, f"{number:02d}-{version}-审批申请-类图", "approveRequest",
                     "OO：Service 协调端口、领域对象维护状态；func：Service 检查状态并修改 JPA 实体。")
    service_ops = [("approve", "employeeId:Long, requestId:Long", "void")]
    if not oo:
        service_ops.append(("applyChange", "action:Action, employeeId:Long, row:SimulatedRequest, title:String", "void"))
    d.add("service", "simulation.BusinessRequestService", "C", [("requests", "RequestRepository" if oo else "SimulatedRequestRepository"),
          ("authorization", "RequestAccess" if oo else "AuthorizationService"), ("systems", "RequestSystems" if oo else "BusinessSystemRepository")],
          service_ops)
    d.add("controller", "simulation.BusinessRequestController", "L", ops=[("approve", "actor:Long, requestId:Long", "ResponseEntity")])
    if oo:
        d.add("repo", "simulation.port.RequestRepository", "R", interface=True,
              ops=[("lock", "id:Long", "Optional<BusinessRequest>"), ("save", "request:BusinessRequest", "BusinessRequest")])
        d.add("access", "simulation.port.RequestAccess", "T", interface=True,
              ops=[("require", "actor:Long, system:RequestSystem, permission:String", "void")])
        d.add("systems", "simulation.port.RequestSystems", "TL", interface=True, ops=[("byId", "id:Long", "RequestSystem")])
        d.add("context", "simulation.infrastructure.RequestContextAdapter", "TR", ops=[("require", "actor:Long, system:RequestSystem, permission:String", "void"), ("byId", "id:Long", "RequestSystem")])
        d.add("impl", "simulation.infrastructure.JpaRequestRepository", "BR", ops=[("lock", "id:Long", "Optional<BusinessRequest>"), ("save", "request:BusinessRequest", "BusinessRequest")])
        d.add("domain", "simulation.domain.BusinessRequest", "B", [("status", "String"), ("deletedAt", "LocalDateTime")],
              [("approve", "", "void"), ("reject", "", "void"), ("withdraw", "actor:Long", "void"), ("rename", "actor:Long, title:String", "void")])
        d.link("context", "access", realization=True)
        d.link("context", "systems", realization=True)
        d.link("impl", "repo", realization=True)
    else:
        d.add("repo", "simulation.SimulatedRequestRepository", "R", interface=True,
              ops=[("findLockedByIdAndDeletedAtIsNull", "id:Long", "Optional<SimulatedRequest>")])
        d.add("access", "authorization.AuthorizationService", "T",
              ops=[("requireBusinessPermission", "employeeId:Long, system:BusinessSystem, code:String", "void")])
        d.add("systems", "simulation.BusinessSystemRepository", "TL", interface=True)
        d.add("domain", "simulation.SimulatedRequest", "B", [("status", "String"), ("deletedAt", "LocalDateTime")])
    d.add("audit", "simulation.BusinessRequestAudit", "BL", ops=[("record", "action:Action, actor:Long, requestId:Long, operation:Function", "T")])
    d.link("controller", "service", "requests")
    for key, role in [("repo", "requests"), ("access", "authorization"), ("systems", "systems")]:
        d.link("service", key, role)
    d.link("service", "domain")
    d.link("service", "audit")
    d.finish()


class Sequence:
    """使用原生 Lifeline、Message 和内嵌 Activation，交给 StarUML 计算箭头端点。"""

    def __init__(self, package, version, name, operation, summary):
        self.package, self.version = package, version
        self.interaction = item("UMLInteraction", package, name=name, participants=[], messages=[], fragments=[], ownedElements=[])
        self.diagram = item("UMLSequenceDiagram", self.interaction, name=name, ownedViews=[],
                            showSequenceNumber=True, sequenceNumbering="custom", showActivation=True)
        self.lifelines, self.parts, self.centers, self.activations = {}, {}, {}, []
        self.y, self.number, self.cursor = 175, 0, 90
        self.entry = {"name": name, "version": version, "kind": "sequence", "operationId": operation,
                      "summary": summary, "sources": []}

    def life(self, key, object_name, qualified=None, static=False, label=None):
        if qualified:
            self.entry["sources"].append(source(self.version, qualified).relative_to(C2).as_posix())
        class_name = qualified.rsplit(".", 1)[-1] if qualified else label
        text = f"{object_name}: {class_name}" if object_name else f": {class_name}"
        width = max(240, len(text)*8+48)
        model = item("UMLLifeline", self.interaction, name=text, stereotype="MetaClass" if static else "")
        self.interaction["participants"].append(model)
        node = view("UMLSeqLifelineView", self.diagram, model, left=self.cursor, top=70, width=width,
                    height=1900, showType=False, fillColor="#edf3fc" if key == "obj" else "#ffffff")
        part = view("UMLLinePartView", node, model, left=self.cursor+width/2, top=132, width=1, height=1838)
        node.update(subViews=[part], linePart=ref(part))
        self.diagram["ownedViews"].append(node)
        self.lifelines[key], self.parts[key], self.centers[key] = model, part, self.cursor+width/2
        self.cursor += width+80

    def call(self, sender, receiver, text, duration=30, reply=False, advance=76):
        self.number += not reply
        message = item("UMLMessage", self.interaction, name=text,
                       source=ref(self.lifelines[sender]), target=ref(self.lifelines[receiver]),
                       messageSort="reply" if reply else "synchCall", sequenceNumber="" if reply else str(self.number))
        self.interaction["messages"].append(message)
        x, xx = self.centers[sender], self.centers[receiver]
        points = [(x, self.y), (xx, self.y)] if sender != receiver else [(x, self.y), (x+30, self.y), (x+30, self.y+20), (x, self.y+20)]
        edge = view("UMLSeqMessageView", self.diagram, message, tail=ref(self.parts[sender]), head=ref(self.parts[receiver]),
                    points=";".join(f"{a}:{b}" for a, b in points), lineStyle=0)
        activation = view("UMLActivationView", edge, message, left=xx-7, top=points[-1][1], width=14,
                          height=duration, fillColor="#ffffff")
        edge.update(subViews=[activation], activation=ref(activation))
        self.diagram["ownedViews"].append(edge)
        if not reply:
            self.activations.append(activation)
        self.y += advance
        return edge

    def begin(self, text="执行验收场景"):
        self.root = self.call("obj", "obj", text, duration=2600, advance=95)

    def frame(self, start, end, guard, operator="opt", left=60, right=None):
        fragment = item("UMLCombinedFragment", self.interaction, interactionOperator=operator, operands=[])
        fragment["operands"].append(item("UMLInteractionOperand", fragment, guard=guard))
        self.interaction["fragments"].append(fragment)
        frame = view("UMLCombinedFragmentView", self.diagram, fragment, left=left, top=start,
                     width=(right or self.cursor-35)-left, height=end-start, fillColor="#ffffff",
                     zIndex=-10)
        self.diagram["ownedViews"].insert(0, frame)

    def note(self, text):
        self.diagram["ownedViews"].append(view("UMLTextView", self.diagram, left=90, top=self.y+30,
            width=self.cursor-180, height=115, text=text, wordWrap=True))

    def local(self, participant, text):
        """局部条件与分页表达式用文字注记，避免伪造源码中不存在的自调用方法。"""
        self.diagram["ownedViews"].append(view("UMLTextView", self.diagram,
            left=self.centers[participant]+35, top=self.y-14, width=780, height=54,
            text=text, wordWrap=True))
        self.y += 76

    def finish(self):
        bottom = self.y+170
        self.root["subViews"][0]["height"] = bottom-self.root["subViews"][0]["top"]-70
        for node in self.diagram["ownedViews"]:
            if node["_type"] == "UMLSeqLifelineView":
                node["height"] = bottom-70
                node["subViews"][0]["height"] = bottom-132
        # 每次短调用应独立，只有外层 Service/审计包装调用允许覆盖嵌套消息。
        for activation in self.activations:
            y, duration = activation["top"], activation["height"]
            if duration <= 0 or y+duration > bottom:
                raise ValueError(f"执行条越界: {self.entry['name']}")
        edges = [v for v in self.diagram["ownedViews"] if v["_type"] == "UMLSeqMessageView"]
        models = {m["_id"]: m for m in self.interaction["messages"]}
        for edge in edges[1:]:
            y = float(edge["points"].split(";")[0].split(":")[1])
            receiver_end = edge["subViews"][0]["top"]+edge["subViews"][0]["height"]
            # 每个消息的发送方必须处在某一次真实调用的执行条中，异常回复也一样。
            if not any(e["head"] == edge["tail"] and models[e["model"]["$ref"]]["messageSort"] != "reply"
                       and e["subViews"][0]["top"] <= y < e["subViews"][0]["top"]+e["subViews"][0]["height"]
                       for e in edges):
                raise ValueError(f"消息发出时未激活: {self.entry['name']}: {models[edge['model']['$ref']]['name']}")
            if models[edge["model"]["$ref"]]["messageSort"] != "reply" and not any(
                    e is not edge and e["head"] == edge["tail"]
                    and models[e["model"]["$ref"]]["messageSort"] != "reply"
                    and e["subViews"][0]["top"] <= y
                    and receiver_end <= e["subViews"][0]["top"]+e["subViews"][0]["height"]
                    for e in edges):
                raise ValueError(f"同步执行条超出调用者: {self.entry['name']}: {models[edge['model']['$ref']]['name']}")
        for frame in (v for v in self.diagram["ownedViews"] if v["_type"] == "UMLCombinedFragmentView"):
            top, end = frame["top"], frame["top"]+frame["height"]
            for activation in self.activations:
                y, end_y = activation["top"], activation["top"]+activation["height"]
                if top <= y < end and end_y > end-5:
                    raise ValueError(f"执行条穿越控制片段底边: {self.entry['name']} {models[activation['model']['$ref']]['name']} ({y}..{end_y}) frame {top}..{end}")
        self.interaction["ownedElements"].append(self.diagram)
        self.package["ownedElements"].append(self.interaction)
        CATALOG.append(self.entry)


def inheritance_sequence(package, version, number):
    oo = version == "oo"
    graph = "RoleHierarchy" if oo else "RoleInheritanceGraph"
    s = Sequence(package, version, f"{number:02d}-{version}-新增角色继承-时序图", "addRoleParent",
                 "同一 READ_COMMITTED 事务：先全图写锁，再范围、环路和授权增量校验。")
    for key, obj, cls in [("obj", "obj", None), ("controller", "controller", "rolepermission.RoleInheritanceController"),
                          ("service", "inheritance", "rolepermission.RoleInheritanceService"),
                          ("auth", "authorization", "authorization.AuthorizationService"),
                          ("store", "hierarchy", "rolepermission.JpaRoleInheritanceStore" if oo else "rolepermission.RoleInheritanceStore"),
                          ("roles", "roles", "rolepermission.RbacRoleRepository"),
                          ("assign", "assignments", "assignment.AssignmentService"),
                          ("graph", "before", f"rolepermission.{'domain.' if oo else ''}{graph}")]:
        s.life(key, obj, cls, label="调用者")
    if not oo:
        s.life("rules", "", "rolepermission.RoleInheritanceRules", static=True)
    s.begin()
    s.call("obj", "controller", "add(actor, roleId, parentRoleId)", duration=1460)
    s.call("controller", "service", "change(actor, seniorId, juniorId, true)", duration=1310)
    s.call("service", "auth", "scope = assignmentScope(actor, ROLE_PERMISSION_WRITE)")
    s.call("service", "store", "lockWrites()")
    s.call("service", "roles", "senior = findById(seniorId)")
    s.y += 35
    junior_start = s.y-35
    s.y += 50
    s.call("service", "roles", "junior = findById(juniorId)")
    s.frame(junior_start, s.y-18, "seniorId ≠ juniorId；同 ID 时直接复用 senior")
    s.y += 40
    s.call("service", "assign", "requireManaged(scope, senior.owner)")
    s.call("service", "assign", "requireManaged(scope, junior.owner)")
    s.y += 35
    top = s.y-35
    s.y += 50
    s.call("service", "obj", "ROLE_INHERITANCE_SCOPE_MISMATCH / SELF_ROLE_INHERITANCE", reply=True)
    s.frame(top, s.y-18, "归属不一致或自继承 → 抛出异常，结束本次调用")
    s.y += 35
    s.call("service", "store", "exists = contains(key)")
    s.y += 35
    duplicate_start = s.y-35
    s.y += 50
    s.call("service", "obj", "已存在：204（幂等，无重复写入）", reply=True)
    s.frame(duplicate_start, s.y-18, "exists 为 true → 正常结束；后续仅表示 exists=false 分支")
    s.y += 40
    s.call("service", "store", "before = snapshot()")
    s.call("service", "graph", "cycle = wouldFormCycle(seniorId, juniorId)", duration=112 if not oo else 30)
    if not oo:
        s.call("graph", "rules", "cycle = wouldFormCycle(parents, seniorId, juniorId)")
    s.y += 30
    top = s.y-32
    s.y += 50
    s.call("service", "obj", "ROLE_INHERITANCE_CYCLE", reply=True)
    s.frame(top, s.y-18, "cycle 为 true → 拒绝并结束；后续仅表示 cycle=false 分支")
    s.y += 40
    s.call("service", "assign", "requireInheritanceGrant(scope, senior, juniorId, before)")
    s.call("service", "store", "add(key)")
    s.note("事务提交后返回 204；已存在的边幂等返回。\n范围、自继承与环路错误为 409；无权限为 403。框内异常路径终止，不执行后续新增。")
    # 外层执行条覆盖至最后一次写入，异常从当前 Service 执行条直接指向调用者。
    calls = [v for v in s.diagram["ownedViews"] if v["_type"] == "UMLSeqMessageView"]
    for v in calls:
        if v.get("head") in (ref(s.parts["service"]), ref(s.parts["controller"])) and v["subViews"][0]["height"] > 100:
            v["subViews"][0]["height"] = s.y-v["subViews"][0]["top"]+10
    s.finish()


def effective_sequence(package, version, number):
    graph = "RoleHierarchy" if version == "oo" else "RoleInheritanceGraph"
    s = Sequence(package, version, f"{number:02d}-{version}-有效权限-时序图", "listEmployeeEffectivePermissions",
                 "先管理员可见范围，再目标员工系统准入、继承闭包及组织策略；分页不改变判权。")
    for key, obj, cls in [("obj", "obj", None), ("query", "management", "organization.ManagementQueryService"),
                          ("auth", "authorization", "authorization.AuthorizationService"),
                          ("work", "workspaces", "authentication.WorkspaceService"),
                          ("emp", "employeeRoles", "assignment.EmployeeRoleRepository"),
                          ("store", "hierarchy", "rolepermission.RoleInheritanceStore"),
                          ("graph", "graph", f"rolepermission.{'domain.' if version == 'oo' else ''}{graph}"),
                          ("systems", "systems", "simulation.BusinessSystemRepository"),
                          ("permission", "permissions", "rolepermission.PermissionRepository"),
                          ("org", "organizationPermissions", "assignment.OrganizationPermissionRepository"),
                          ("role", "rolePermissions", "assignment.RolePermissionRepository")]:
        s.life(key, obj, cls, label="调用者")
    if version == "func":
        s.life("rules", "", "rolepermission.RoleInheritanceRules", static=True)
    s.begin()
    query = s.call("obj", "query", "effectivePermissions(operator, employeeId, systemCode, page)", duration=1700)
    s.call("query", "query", "scope = scope(operator)")
    s.call("query", "query", "employee = requireEmployeeVisible(employeeId, scope)")
    s.call("query", "systems", "system = findByCode(systemCode)")
    s.local("query", "局部判断：系统不存在 → SYSTEM_NOT_FOUND；\n非全局管理员且 policyIds 不含系统归属 → PERMISSION_DENIED。")
    auth = s.call("query", "auth", "effective = effectivePermissions(employeeId, system)", duration=1350)
    s.call("auth", "work", "employee = findActiveEmployee(employeeId)")
    s.call("auth", "work", "context = context(employee)")
    s.local("auth", "以上调用位于 systemContext 内；context.canEnter(system) 不满足则拒绝准入。")
    s.y += 35
    start = s.y-35
    s.y += 50
    s.call("auth", "emp", "roots = findDirectRoleIds(employeeId)")
    s.y += 35
    roots_start = s.y-35
    s.y += 50
    s.call("auth", "store", "graph = snapshot()")
    s.call("auth", "graph", "sources = inheritedRoles(roots)", duration=115 if version == "func" else 30)
    if version == "func":
        s.call("graph", "rules", "sources = reachable(parents, roots)")
    s.frame(roots_start, s.y-18, "roots 非空；Set 去重", left=s.centers["auth"]-45)
    s.y += 24
    s.frame(start, s.y, "非全局管理员；空 roots 时 sources 为空集")
    s.y += 40
    s.call("auth", "permission", "candidates = findBySystemIdOrderByIdAsc(system.id)")
    s.y += 35
    start = s.y-35
    s.y += 50
    allowed = s.call("auth", "auth", "allowed = isAllowed(permission.id, target)", duration=500)
    s.call("auth", "org", "policies = findByPermissionIdAndOrganizationIdIn(id, affected)")
    s.local("auth", "局部判断：存在 DISABLED 则 false；否则 global 为 true；\n普通员工 sources 为空则 false，其余进入重开与来源判断。")
    s.y += 35
    inner_start = s.y-30
    s.y += 50
    s.call("auth", "role", "existsByPermissionIdAndRoleIdIn(id, sources)")
    s.frame(inner_start, s.y-18, "普通角色分支，reopened 为空", left=s.centers["auth"]-45)
    s.y += 45
    inner_start = s.y-30
    s.y += 50
    s.call("auth", "role", "hasRegrantedSource(id, sources, reopened, reopened.size)")
    s.frame(inner_start, s.y-18, "普通角色分支，reopened 非空：同一个权限来源满足全部重开组织", left=s.centers["auth"]-45)
    s.y += 30
    allowed["subViews"][0]["height"] = s.y-allowed["subViews"][0]["top"]-12
    s.frame(start, s.y, "逐个候选权限执行 isAllowed；同一权限只输出一次", operator="loop", left=s.centers["auth"]-65)
    s.y += 65
    s.local("query", "局部表达式：按 page.getOffset/pageSize 截取 effective，\n构造 PageImpl 并保留完整 total；偏移越界时返回空 items。")
    s.note("返回的每项均来自统一实时授权入口；组织停用优先于全局管理员及继承来源。\n展示核心内部调用；Controller 的参数绑定、VO 映射及系统/组织仓储细节见源代码。")
    for edge in (query, auth):
        edge["subViews"][0]["height"] = s.y-edge["subViews"][0]["top"]-(80 if edge is auth else 0)
    s.finish()


def approval_sequence(package, version, number):
    oo = version == "oo"
    s = Sequence(package, version, f"{number:02d}-{version}-审批申请-时序图", "approveRequest",
                 "请求行锁、read+approve 实时检查、状态变更、CloneFactory 映射、提交后记录审计。")
    for key, obj, cls in [("obj", "obj", None), ("service", "requests", "simulation.BusinessRequestService"),
                          ("audit", "", "simulation.BusinessRequestAudit"),
                          ("repo", "repository", "simulation.infrastructure.JpaRequestRepository" if oo else "simulation.SimulatedRequestRepository"),
                          ("access", "access", "simulation.infrastructure.RequestContextAdapter" if oo else "authorization.AuthorizationService"),
                          ("auth", "authorization" if oo else "systems", "authorization.AuthorizationService" if oo else "simulation.BusinessSystemRepository"),
                          ("row", "row", "simulation.domain.BusinessRequest" if oo else "simulation.SimulatedRequest")]:
        s.life(key, obj, cls, static=key == "audit", label="调用者")
    if oo:
        s.life("jpa", "jpaRequests", "simulation.SimulatedRequestRepository")
    s.life("clone", "", label="CloneFactory", static=True)
    if not oo:
        s.life("procedures", "", "simulation.BusinessRequestService", static=True)
    s.begin()
    service = s.call("obj", "service", "approve(employeeId, requestId)", duration=1600)
    audit = s.call("service", "audit", "record(APPROVE, employeeId, requestId, operation)", duration=1350)
    operation = s.call("audit", "service", "operation.apply(target)  // change 内定义的 lambda", duration=1150)
    s.call("service", "repo", "row = lock(requestId)" if oo else "row = findLockedByIdAndDeletedAtIsNull(requestId)", duration=115 if oo else 30)
    if oo:
        s.call("repo", "jpa", "entity = findLockedByIdAndDeletedAtIsNull(id)")
    s.call("service", "access" if oo else "auth", "system = byId(row.systemId)" if oo else "system = findById(row.systemId)")
    for code in ("request:read", "request:approve"):
        s.call("service", "access", f'require(employeeId, system, "{code}")' if oo else f'requireBusinessPermission(employeeId, system, "{code}")', duration=115 if oo else 30)
        if oo:
            s.call("access", "auth", f'requireBusinessPermission(employeeId, system, "{code}")')
    s.y += 35
    start = s.y-35
    s.y += 50
    if oo:
        s.call("service", "row", "approve()  // PENDING → APPROVED")
    else:
        s.call("service", "procedures", "applyChange(APPROVE, employeeId, row, null)", duration=115)
        s.call("procedures", "row", 'setStatus("APPROVED")  // requireState 后')
    s.frame(start, s.y, "read 与 approve 均允许且状态 PENDING；否则进入拒绝分支")
    s.y += 40
    if oo:
        s.call("service", "repo", "save(row)  // 锁内托管实体 apply(domain)", duration=115)
        s.call("repo", "jpa", "save(entity)")
    s.call("service", "clone", "detail = copy(new RequestDetail(), row)")
    s.note("权限拒绝：AuthorizationService 抛出异常，由 BusinessRequestAudit.record 捕获并记录 REJECTED；状态错误同样回滚。\n成功：record 注册 afterCompletion；Spring 提交事务后才记录 COMMITTED。func 通过 JPA 脏检查保存，OO 显式调用仓储端口保存。\nController 返回 200 ApprovalResultVO；自审核允许。CloneFactory 仍由 Maven 注解处理器生成映射。")
    # 此图展示成功分支；拒绝和跨请求撤权的实际异常路径另有专图，不画未经 catch 的逐层异常箭头。
    for edge in (service, audit, operation):
        edge["subViews"][0]["height"] = s.y-edge["subViews"][0]["top"]+15
    s.finish()


def revocation_sequence(package, version, number):
    oo = version == "oo"
    s = Sequence(package, version, f"{number:02d}-{version}-同会话实时撤权-时序图", "removeRoleParent / approveRequest",
                 "三次独立 HTTP 请求；先成功，再提交删边，再用原会话操作另一待审批申请被拒绝。")
    for key, obj, cls in [("obj", "obj", None), ("request", "requests", "simulation.BusinessRequestService"),
                          ("audit", "", "simulation.BusinessRequestAudit"),
                          ("auth", "authorization", "authorization.AuthorizationService"),
                          ("inherit", "inheritance", "rolepermission.RoleInheritanceService"),
                          ("store", "hierarchy", "rolepermission.JpaRoleInheritanceStore" if oo else "rolepermission.RoleInheritanceStore")]:
        s.life(key, obj, cls, static=key == "audit", label="调用者")
    s.begin("执行三次独立请求（员工与管理员各自已登录）")
    start = s.y-30
    s.y += 55
    first = s.call("obj", "request", "approve(employeeId, pendingA)  // 员工原 Cookie", duration=245)
    s.call("request", "auth", 'requireBusinessPermission(employeeId, system, "request:approve")', duration=115)
    s.call("auth", "store", "graph = snapshot()")
    s.call("request", "obj", "200；A=APPROVED；事务已提交", reply=True)
    first["subViews"][0]["height"] = s.y-25-first["subViews"][0]["top"]
    s.frame(start, s.y-18, "请求 1：唯一继承路径存在，read 和 approve 均允许", operator="seq")
    s.y += 50
    start = s.y-30
    s.y += 55
    second = s.call("obj", "inherit", "change(admin, seniorId, juniorId, false)  // 管理员 Cookie", duration=245)
    s.call("inherit", "store", "lockWrites()")
    s.call("inherit", "store", "remove(key)")
    s.call("inherit", "obj", "204；删边事务已提交", reply=True)
    second["subViews"][0]["height"] = s.y-25-second["subViews"][0]["top"]
    s.frame(start, s.y-18, "请求 2：管理员范围校验通过，删除唯一权限来源的继承边", operator="seq")
    s.y += 50
    start = s.y-30
    s.y += 55
    third = s.call("obj", "request", "approve(employeeId, pendingB)  // 继续使用原 Cookie", duration=480)
    audit = s.call("request", "audit", "record(APPROVE, employeeId, pendingB, operation)", duration=365)
    s.call("request", "auth", 'requireBusinessPermission(employeeId, system, "request:approve")', duration=165)
    s.call("auth", "store", "graph = snapshot()  // 从 MySQL 读取删边后数据")
    s.call("auth", "audit", "PERMISSION_DENIED  // record 捕获并记录 REJECTED", reply=True)
    s.call("audit", "obj", "重新抛出；异常映射为 HTTP 403", reply=True)
    s.frame(start, s.y-18, "请求 3：read 仍保留，approve 已失去；申请 B 保持 PENDING", operator="seq")
    s.note("图中事务提交以 HTTP 响应明确标记；Redis 只保存登录会话，不缓存业务权限。func/oo 使用独立 Cookie、会话命名空间和 MySQL 库。\n为聚焦跨请求变化，省略 Controller、请求行锁与 OO RequestAccess 适配跳转；完整单次审批链见前图。\n另一路继承仍存在时，来源 Set 仍包含授权角色，下一次操作应继续成功；对应自动化测试覆盖多路径。")
    # 延长外层调用至各分支结束，保持异常/回复两端均在执行条上。
    for edge, end in [(third, s.y-25), (audit, s.y-25)]:
        top = edge["subViews"][0]["top"]
        if end > top:
            edge["subViews"][0]["height"] = end-top
    s.finish()


def package_diagram(package, version, number):
    """只展示源码中确实存在的核心包依赖；OO 子包明确区分端口与基础设施。"""
    diagram = item("UMLPackageDiagram", package, name=f"{number:02d}-{version}-后端包图", ownedViews=[])
    names = ["authentication", "authorization", "assignment", "rolepermission", "organization", "simulation", "contract", "initialization"]
    if version == "oo":
        names += ["simulation.port", "simulation.domain", "simulation.infrastructure", "rolepermission.domain"]
    boxes, nodes, views = {}, {}, {}
    cols = 4
    for index, name in enumerate(names):
        folder = C2 / f"backend-{version}/src/main/java" / name.replace(".", "/")
        if not folder.is_dir():
            raise ValueError(f"包不存在: {name}")
        node = item("UMLPackage", package, name=name)
        package["ownedElements"].append(node)
        x, y = 90+(index % cols)*620, 100+(index//cols)*430
        boxes[name] = (x, y, 440, 170)
        node_view = view("UMLPackageView", diagram, node, left=x, top=y, width=440, height=170,
                         fillColor="#e7f0fd" if name in ("simulation", "rolepermission") else "#ffffff")
        nodes[name], views[name] = node, node_view
        diagram["ownedViews"].append(node_view)
    pairs = [("simulation.infrastructure" if version == "oo" else "simulation", "authorization"), ("authorization", "rolepermission"), ("authorization", "assignment"),
             ("authorization", "authentication"), ("organization", "authorization"), ("rolepermission", "assignment"),
             ("initialization", "organization"), ("simulation", "contract")]
    if version == "oo":
        pairs += [("simulation", "simulation.port"), ("simulation", "simulation.domain"),
                  ("simulation.infrastructure", "simulation.port"), ("simulation.infrastructure", "simulation.domain"),
                  ("rolepermission", "rolepermission.domain")]
    for a, b in pairs:
        # 查询真实 import，包图只展示直接依赖，不把传递依赖当成 import。
        content = "\n".join(p.read_text(encoding="utf-8") for p in (C2/f"backend-{version}/src/main/java"/a.replace(".", "/")).glob("*.java"))
        if not re.search(rf"import\s+{re.escape(b)}\.", content):
            raise ValueError(f"源码没有此包依赖: {a} -> {b}")
        relation = item("UMLDependency", package, source=ref(nodes[a]), target=ref(nodes[b]))
        package["ownedElements"].append(relation)
        points = routed_edge(a, b, boxes)
        diagram["ownedViews"].insert(0, view("UMLDependencyView", diagram, relation, tail=ref(views[a]), head=ref(views[b]),
            lineStyle=0 if len(points) > 2 else 1, points=";".join(f"{x}:{y}" for x, y in points)))
    diagram["ownedViews"].append(view("UMLTextView", diagram, left=90, top=1460 if version == "oo" else 1030,
        width=2350, height=90, wordWrap=True,
        text="选取当前核心包的直接 import 依赖，箭头指向被依赖包；DTO/VO、安全配置与外部框架省略。\nOO 差异集中在角色图领域对象和申请 port/domain/infrastructure；统一授权仍保留现有 C1 Service/JPA 结构。"))
    package["ownedElements"].append(diagram)
    CATALOG.append({"name": diagram["name"], "version": version, "kind": "package", "operationId": "—",
                    "summary": "源代码核心包及直接 import 依赖；非完整运行部署图。", "sources": [f"backend-{version}/src/main/java/"]})


def check_refs(project):
    ids, references = set(), []

    def walk(value):
        if isinstance(value, dict):
            if "_id" in value:
                if value["_id"] in ids:
                    raise ValueError(f"重复 ID: {value['_id']}")
                ids.add(value["_id"])
            if "$ref" in value:
                references.append(value["$ref"])
            for child in value.values():
                walk(child)
        elif isinstance(value, list):
            for child in value:
                walk(child)
    walk(project)
    missing = set(references)-ids
    if missing:
        raise ValueError(f"悬空引用: {missing}")
    return len(ids), len(references)


def check_exports(catalog):
    """复核 StarUML 实际导出的尺寸与原生类框，防止自动扩框后产生重叠。"""
    actual_boxes = 0
    for entry in catalog:
        for suffix in ("svg", "png"):
            path = HERE/"exports"/(entry["name"]+"."+suffix)
            if not path.is_file() or path.stat().st_size < 100:
                raise ValueError(f"缺少导出: {path}")
        root = ET.parse(HERE/"exports"/(entry["name"]+".svg")).getroot()
        if float(root.attrib["width"]) <= 100 or float(root.attrib["height"]) <= 100:
            raise ValueError(f"导出画布异常: {entry['name']}")
        if entry["kind"] != "class":
            continue
        def is_box(element):
            return (element.tag.endswith("rect") and element.get("fill", "").lower() in ("#ffffff", "#e7f0fd")
                    and float(element.get("width", 0)) > 200 and float(element.get("height", 0)) > 100)

        boxes = []
        for element in filter(is_box, root.iter()):
            matrix = [float(n) for n in re.findall(r"-?\d+(?:\.\d+)?", element.get("transform", "matrix(1 0 0 1 0 0)"))]
            boxes.append((float(element.get("x"))+matrix[-2], float(element.get("y"))+matrix[-1],
                          float(element.get("width")), float(element.get("height"))))
        for a, b in itertools.combinations(boxes, 2):
            if a[0] < b[0]+b[2] and b[0] < a[0]+a[2] and a[1] < b[1]+b[3] and b[1] < a[1]+a[3]:
                raise ValueError(f"StarUML 自动扩框后重叠: {entry['name']}")
        actual_boxes += len(boxes)
        # StarUML 有时会自动重排折线，必须再查实际导出的关系线，不能只查输入坐标。
        for element in root.iter():
            if is_box(element):
                break
            if not element.tag.endswith("path") or element.get("stroke") != INK:
                continue
            numbers = [float(n) for n in re.findall(r"-?\d+(?:\.\d+)?", element.get("d", ""))]
            points = list(zip(numbers[::2], numbers[1::2]))
            for p, q in zip(points, points[1:]):
                if any(segment_hits(p, q, (x+3, y+3, w-6, h-6)) for x, y, w, h in boxes):
                    raise ValueError(f"StarUML 导出关系线穿框: {entry['name']}: {p} -> {q}")
    return actual_boxes


def write_index():
    """把每张图关联到共同契约、需求、真实测试与共用前端，供课堂抽查。"""
    spec = (C2/"api/openapi.yaml").read_text(encoding="utf-8")
    rows = ["# C2 图件索引", "", "同一模型 `C2-RBAC1.mdj` 包含 func、oo 核心图及已实现内容补充图三组。PNG/SVG 由 StarUML CLI 导出。", "",
            "| 图件 | 需求 | OpenAPI operationId | 预览 |", "| --- | --- | --- | --- |"]
    for entry in CATALOG:
        name, version = entry["name"], entry["version"]
        for path in entry["sources"]:
            if not (C2/path).exists():
                raise ValueError(f"索引源码不存在: {path}")
        contract = f"backend-{'oo' if version == 'shared' else version}/src/test/java/contract/AuthSessionTest.java"
        if "新增角色继承" in name:
            requirements = "C2-01、03、05、07"
            methods = ["inheritanceRevocationAppliesToExistingSession", "serializesInheritanceWritesWithDisjointEndpoints"]
            browser = "frontend/tests/authorization.spec.ts"
        elif "有效权限" in name:
            requirements = "C2-01、02、05、06"
            methods = ["inheritanceRevocationAppliesToExistingSession", "matchesReopenedScopesToOneInheritedPermissionSource"]
            browser = "frontend/tests/role-permissions.spec.ts"
        elif "同会话" in name:
            requirements = "C2-04、10、15"
            methods = ["inheritanceRevocationAppliesToExistingSession", "requiresReadPermissionForRequests"]
            browser = "frontend/tests/authorization.spec.ts"
        elif "审批" in name:
            requirements = "C2-11、12、13、14、15、16"
            methods = ["approvesOnlyInGrantedSystem", "approvesOnlyOnceConcurrently", "recordsRequestActionOutcomes", "requiresReadPermissionForRequests"]
            browser = "frontend/tests/oo-entry.spec.ts"
        else:
            requirements, methods, browser = "课程结构与职责检查", [], "frontend/tests/isolation.spec.ts"
        requirements = entry.get("requirements", requirements)
        methods = entry.get("testMethods", methods)
        browser = entry.get("browserTest", browser)
        tests = (C2/contract).read_text(encoding="utf-8")
        for method in methods:
            if not re.search(rf"\b{method}\s*\(", tests):
                raise ValueError(f"测试不存在: {contract}#{method}")
        if not (C2/browser).is_file():
            raise ValueError(f"浏览器测试不存在: {browser}")
        for op in entry["operationId"].split(" / "):
            if op != "—" and not re.search(rf"operationId:\s*[\"']?{op}\b", spec):
                raise ValueError(f"共同 OpenAPI 不含 operationId: {op}")
        paths = "/func/ 与 /oo/" if version == "shared" else f"/{version}/admin/；/{version}/business/demo-engineering-app/"
        entry.update(requirements=requirements, model="C2-RBAC1.mdj", tests=[f"{contract}#{m}" for m in methods],
                     browserTest=browser, frontend=entry.get("frontend", paths))
        rows.append(f"| {name} | {requirements} | `{entry['operationId']}` | [PNG](exports/{name}.png) · [SVG](exports/{name}.svg) |")
    rows += ["", "## 代码与测试入口", ""]
    for entry in CATALOG:
        rows += [f"### {entry['name']}", "", entry["summary"], "",
                 "源码："+"、".join(f"[{p}](../../{p})" for p in entry["sources"]), "",
                 "测试："+("、".join(f"`{p}`" for p in entry["tests"]) or "参见下方关联浏览器用例；本图不单独声明测试已运行"), "",
                 f"浏览器：[{entry['browserTest']}](../../{entry['browserTest']})；入口：`{entry['frontend']}`。", ""]
    (HERE/"图件索引.md").write_text("\n".join(rows), encoding="utf-8")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--check", action="store_true", help="检查当前 mdj、索引和导出，不覆盖文件")
    args = parser.parse_args()
    target = HERE/"C2-RBAC1.mdj"
    if args.check:
        project = json.loads(target.read_text(encoding="utf-8"))
        ids, refs = check_refs(project)
        catalog = json.loads((HERE/"diagram-index.json").read_text(encoding="utf-8"))
        boxes = check_exports(catalog)
        print(f"PASS: {len(catalog)} diagrams; {ids} unique elements; {refs} resolved references; {boxes} rendered class boxes; PNG/SVG present")
        return
    project = item("Project", name="C2 RBAC1 · func / oo", ownedElements=[])
    for version, start in (("func", 1), ("oo", 9)):
        root = item("UMLModel", project, name=f"backend-{version}", ownedElements=[])
        project["ownedElements"].append(root)
        package_diagram(root, version, start)
        inheritance_class(root, version, start+1)
        inheritance_sequence(root, version, start+2)
        effective_class(root, version, start+3)
        effective_sequence(root, version, start+4)
        approval_class(root, version, start+5)
        approval_sequence(root, version, start+6)
        revocation_sequence(root, version, start+7)
    from build_supplementary import build
    build(project, sys.modules[__name__])
    ids, refs = check_refs(project)
    HERE.mkdir(parents=True, exist_ok=True)
    (HERE/"exports").mkdir(exist_ok=True)
    target.write_text(json.dumps(project, ensure_ascii=False, indent=2)+"\n", encoding="utf-8")
    write_index()
    (HERE/"diagram-index.json").write_text(json.dumps(CATALOG, ensure_ascii=False, indent=2)+"\n", encoding="utf-8")
    print(f"PASS: {len(CATALOG)} diagrams; {ids} elements; {refs} references; source symbols and class layouts checked")


if __name__ == "__main__":
    main()
