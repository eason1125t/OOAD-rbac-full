"""从同一份布局生成可编辑的 StarUML 项目与独立 SVG 预览。"""

import json
from itertools import count
from pathlib import Path
from xml.etree import ElementTree as ET


IDS = count(1)
INK = "#24384a"


def element(kind, **fields):
    """为项目、图和可编辑图元生成稳定的 StarUML 标识。"""
    return {"_type": kind, "_id": f"rbac0-chen-{next(IDS):04}", **fields}


def ref(item):
    return {"$ref": item["_id"]}


class ChenDiagram:
    """使用 StarUML 自带图元组合陈氏符号，并按连线、图形、文字顺序绘制。"""

    def __init__(self, name):
        self.model = element("ERDDiagram", name=name)
        self.lines = []
        self.shapes = []
        self.labels = []

    def line(self, *points, color=INK):
        self.lines.append(element(
            "FreelineEdgeView", points=";".join(f"{x}:{y}" for x, y in points),
            lineStyle=1, lineColor=color,
        ))

    def view(self, kind, x, y, width, height, **fields):
        return element(kind, left=x-width//2, top=y-height//2,
                       width=width, height=height, **fields)

    def label(self, value, x, y, width=145, size=16, bold=False, color=INK):
        self.labels.append(self.view(
            "UMLTextView", x, y, width, 32, text=value, wordWrap=False,
            horzAlign=2, vertAlign=5, font=f"Microsoft YaHei;{size};{int(bold)}",
            fontColor=color,
        ))

    def entity(self, value, x, y):
        self.shapes.append(self.view(
            "RectangleView", x, y, 140, 56, lineColor=INK, fillColor="#dceefb",
        ))
        self.label(value, x, y, bold=True)

    def attribute(self, value, x, y, anchor, key=False, width=126, height=42):
        self.line(anchor, (x, y), color="#637d92")
        self.shapes.append(self.view(
            "EllipseView", x, y, width, height, lineColor=INK, fillColor="#ffffff",
        ))
        self.label(value, x, y, width=width, size=14)
        if key:
            half = len(value) * 7
            self.line((x-half, y+10), (x+half, y+10))
            self.shapes.append(self.lines.pop())

    def relation(self, name, x, y, width=120, height=68):
        # 四条闭合边使菱形保持为普通可编辑线条，无需安装扩展。
        vertices = ((x-width//2, y), (x, y-height//2),
                    (x+width//2, y), (x, y+height//2))
        self.line(*vertices, vertices[0])
        self.label(name, x, y, width=width, size=15, bold=True)

    def horizontal(self, name, left, right, y, left_count, right_count):
        middle = (left + right) // 2
        self.relation(name, middle, y)
        self.line((left+70, y), (middle-60, y))
        self.line((middle+60, y), (right-70, y))
        self.label(left_count, left+98, y-20, size=13)
        self.label(right_count, right-98, y-20, size=13)

    def finish(self):
        """按父图引用保存图元，供 StarUML 加载和导出。"""
        views = self.lines + self.shapes + self.labels
        for view in views:
            view["_parent"] = ref(self.model)
        self.model["ownedViews"] = views
        return self.model


def core():
    diagram = ChenDiagram("01-核心授权")
    diagram.label("RBAC0 核心授权 · 陈氏 ER 图", 590, 45, 620, 26, True)
    for name, x in (("员工", 170), ("角色", 590), ("权限", 1010)):
        diagram.entity(name, x, 220)
    diagram.horizontal("分配角色", 170, 590, 220, "N", "M")
    diagram.horizontal("授予权限", 590, 1010, 220, "N", "M")
    for name, x in (("员工 ID", 170), ("角色 ID", 590), ("权限 ID", 1010)):
        diagram.attribute(name, x, 125, (x, 192), key=True)
    for name, x, anchor in (
        ("用户名", 95, (145, 248)), ("姓名", 245, (195, 248)),
        ("角色名", 590, (590, 248)),
        ("权限编码", 935, (985, 248)), ("权限名称", 1085, (1035, 248)),
    ):
        diagram.attribute(name, x, 325, anchor)
    diagram.label("矩形：实体　菱形：联系　椭圆：属性　下划线：主标识　M / N：多", 590, 410, 860)
    diagram.label("两组多对多联系均可为空。落库分别使用 employee_role 与 role_permission。", 590, 449, 960)
    return diagram.finish()


def scopes():
    diagram = ChenDiagram("02-组织与授权范围")
    diagram.label("RBAC0 组织与授权范围 · 陈氏 ER 图", 630, 44, 850, 26, True)
    diagram.label("组织归属", 630, 96, 300, 18, True)

    # 同名实体在分区中重复显示，以减少长连线和交叉。
    for name, x in (("员工", 145), ("组织", 630), ("业务系统", 1115)):
        diagram.entity(name, x, 195)
    diagram.horizontal("归属", 145, 630, 195, "N", "1")
    diagram.horizontal("拥有系统", 630, 1115, 195, "1", "N")
    diagram.attribute("组织 ID", 555, 130, (610, 167), key=True)
    diagram.attribute("组织类型", 705, 130, (650, 167))
    diagram.relation("上下级", 630, 288)
    diagram.line((600, 223), (600, 254), (630, 254))
    diagram.line((662, 223), (770, 223), (770, 288), (690, 288))
    diagram.label("N 下级", 540, 254, size=13)
    diagram.label("1 上级", 820, 258, size=13)
    diagram.label("公司为根，部门属于一家分公司。待匹配员工可暂缺归属。", 630, 351, 950)

    diagram.label("管理范围与定义归属", 630, 412, 630, 18, True)
    for name, x in (("员工", 145), ("组织", 630), ("角色", 1115)):
        diagram.entity(name, x, 501)
    diagram.horizontal("管理范围", 145, 630, 501, "N", "1")
    diagram.horizontal("拥有角色", 630, 1115, 501, "1", "N")
    diagram.label("普通员工可无管理范围，超级管理员使用全局范围。全局角色可无所属组织。", 630, 566, 1080)

    diagram.label("按系统定义权限，按组织开放或停用", 630, 634, 850, 18, True)
    for name, x in (("业务系统", 145), ("权限", 630), ("组织", 1115)):
        diagram.entity(name, x, 741)
    diagram.horizontal("定义权限", 145, 630, 741, "1", "N")
    diagram.horizontal("配置权限", 630, 1115, 741, "M", "N")
    diagram.attribute("开放／停用", 872, 675, (872, 707), width=154)
    diagram.label("管理权限可无业务系统。同一系统内权限码唯一。", 630, 810, 1090)
    diagram.label("权限定义归属", 630, 873, 500, 18, True)
    diagram.entity("组织", 368, 950)
    diagram.entity("权限", 890, 950)
    diagram.horizontal("拥有定义", 368, 890, 950, "1", "N")
    diagram.label("全局权限可无所属组织。组织停用优先于角色授权。", 630, 1018, 1090)
    return diagram.finish()


def write_svg(diagram, output):
    """独立绘制同一组坐标与符号，便于在文档中查看。"""
    views = diagram["ownedViews"]
    boxes = [item for item in views if "left" in item]
    width = max(item["left"] + item["width"] for item in boxes) + 32
    height = max(item["top"] + item["height"] for item in boxes) + 24
    svg = ET.Element("svg", xmlns="http://www.w3.org/2000/svg", role="img",
                     viewBox=f"0 0 {width} {height}", width=str(width), height=str(height))
    ET.SubElement(svg, "title").text = diagram["name"]
    ET.SubElement(svg, "rect", width="100%", height="100%", fill="white")
    for item in views:
        kind = item["_type"]
        if kind == "FreelineEdgeView":
            points = item["points"].replace(":", ",").replace(";", " ")
            closed = points.split()[0] == points.split()[-1]
            ET.SubElement(svg, "polyline", points=points, stroke=item["lineColor"],
                          fill="#edf7ff" if closed else "none", **{"stroke-width": "1.5"})
            continue
        left, top, w, h = (item[key] for key in ("left", "top", "width", "height"))
        x, y = left+w//2, top+h//2
        if kind == "UMLTextView":
            face, size, bold = item["font"].split(";")
            label = ET.SubElement(svg, "text", x=str(x), y=str(y), fill=item["fontColor"],
                                  **{"font-family": f"{face}, SimHei, sans-serif",
                                     "font-size": size, "font-weight": "700" if bold == "1" else "400",
                                     "text-anchor": "middle", "dominant-baseline": "central"})
            label.text = item["text"]
        elif kind == "RectangleView":
            ET.SubElement(svg, "rect", x=str(left), y=str(top), width=str(w), height=str(h),
                          fill=item["fillColor"], stroke=item["lineColor"])
        else:
            assert kind == "EllipseView"
            ET.SubElement(svg, "ellipse", cx=str(x), cy=str(y), rx=str(w//2), ry=str(h//2),
                          fill=item["fillColor"], stroke=item["lineColor"])
    ET.indent(svg)
    ET.ElementTree(svg).write(output, encoding="utf-8", xml_declaration=True)


def main():
    """写入可在 StarUML 中打开的双图项目文件。"""
    project = element("Project", name="RBAC0 陈氏 ER 图")
    model = element("ERDDataModel", name="C1 概念模型", _parent=ref(project))
    diagrams = [core(), scopes()]
    for diagram in diagrams:
        diagram["_parent"] = ref(model)
    model["ownedElements"] = diagrams
    project["ownedElements"] = [model]
    output = Path(__file__).with_name("RBAC0-陈氏ER图.mdj")
    output.write_text(json.dumps(project, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    for diagram in diagrams:
        write_svg(diagram, output.with_name(f"{diagram['name']}.svg"))


if __name__ == "__main__":
    main()
