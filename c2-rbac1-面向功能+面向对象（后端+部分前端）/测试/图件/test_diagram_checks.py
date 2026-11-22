"""验证绘图工具的防错能力；与 build_staruml.py 配套，不代替后端业务测试。

覆盖泛型签名、无效引用、绕障连线、执行条越界及源码字段关联，使用 Python 标准库。
"""

import unittest

import importlib.util
from pathlib import Path

SOURCE = Path(__file__).resolve().parents[2] / "docs/design/build_staruml.py"
SPEC = importlib.util.spec_from_file_location("build_staruml", SOURCE)
uml = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(uml)


class DiagramChecksTest(unittest.TestCase):
    def test_annotation_regex_does_not_pollute_http_parameter_type(self):
        parent = uml.item("UMLModel", ownedElements=[])
        node = uml.model_class(parent, "func", "authentication.AuthController", operations=[("me", "", "")])
        parameters = [(p["name"], p["type"]) for p in node["operations"][0]["parameters"]]
        self.assertEqual(parameters[:2], [("employeeId", "Long"), ("systemCode", "String")])

    def test_generic_and_annotation_commas_do_not_split_parameters(self):
        self.assertEqual(uml.split_java_parameters(
            "Map<Long, Set<Long>> parents, @A(x = 1, y = 2) Long id"),
            ["Map<Long, Set<Long>> parents", "@A(x = 1, y = 2) Long id"])

    def test_duplicate_element_id_is_rejected(self):
        with self.assertRaisesRegex(ValueError, "重复 ID"):
            uml.check_refs({"_id": "a", "child": {"_id": "a"}})

    def test_dangling_reference_is_rejected(self):
        with self.assertRaisesRegex(ValueError, "悬空引用"):
            uml.check_refs({"_id": "a", "child": {"$ref": "missing"}})

    def test_connection_routes_around_an_intervening_box(self):
        boxes = {"a": (0, 0, 100, 100), "obstacle": (180, 0, 100, 100), "b": (360, 0, 100, 100)}
        points = uml.routed_edge("a", "b", boxes)
        self.assertGreater(len(points), 2)
        self.assertFalse(any(uml.segment_hits(p, q, boxes["obstacle"])
                             for p, q in zip(points, points[1:])))

    def test_frame_cannot_cut_off_a_receiver_activation(self):
        parent = uml.item("UMLModel", ownedElements=[])
        sequence = uml.Sequence(parent, "oo", "negative-frame-test", "—", "")
        sequence.life("obj", "obj", label="调用者")
        sequence.life("service", "service", label="Service")
        sequence.begin()
        sequence.y += 80
        sequence.call("obj", "service", "operation()", duration=200)
        sequence.frame(sequence.y-100, sequence.y+20, "invalid frame")
        sequence.y += 160
        with self.assertRaisesRegex(ValueError, "控制片段"):
            sequence.finish()

    def test_synchronous_receiver_must_finish_before_its_caller(self):
        parent = uml.item("UMLModel", ownedElements=[])
        sequence = uml.Sequence(parent, "oo", "negative-caller-test", "—", "")
        for key in ("obj", "service", "repo"):
            sequence.life(key, key, label="调用者" if key == "obj" else key)
        sequence.begin()
        sequence.call("obj", "service", "useCase()", duration=115)
        sequence.call("service", "repo", "query()", duration=100)
        sequence.y += 160
        with self.assertRaisesRegex(ValueError, "超出调用者"):
            sequence.finish()

    def test_static_generic_signature_is_read_from_actual_java(self):
        parent = uml.item("UMLModel", ownedElements=[])
        node = uml.model_class(parent, "func", "simulation.BusinessRequestAudit", operations=[("record", "", "")])
        operation = node["operations"][0]
        self.assertTrue(operation["isStatic"])
        self.assertEqual(operation["visibility"], "package")
        self.assertEqual([(p["name"], p["type"]) for p in operation["parameters"]],
                         [("action", "Action"), ("actor", "Long"), ("requestId", "Long"),
                          ("operation", "Function<Target, T>"), ("", "T")])

    def test_association_requires_an_actual_field(self):
        parent = uml.item("UMLModel", ownedElements=[])
        diagram = uml.ClassDiagram(parent, "oo", "negative-field-test", "—", "")
        diagram.add("service", "simulation.BusinessRequestService", "C")
        diagram.add("repo", "simulation.port.RequestRepository", "R", interface=True)
        with self.assertRaisesRegex(ValueError, "关联字段不存在"):
            diagram.link("service", "repo", "imaginaryRepository")


if __name__ == "__main__":
    unittest.main()
