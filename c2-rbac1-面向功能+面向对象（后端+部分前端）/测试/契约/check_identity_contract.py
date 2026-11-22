# 文件职责：静态验证八项身份目标契约、示例和当前接口兼容约定，不启动业务服务。
# 协作关系：读取 api 下当前及目标 OpenAPI；结果由第一步验证记录引用。
from pathlib import Path
import json
import re

import yaml
from openapi_schema_validator import OAS30Validator
from openapi_spec_validator import validate
from referencing import Registry, Resource
from referencing.jsonschema import DRAFT4


ROOT = Path(__file__).resolve().parents[2]
TARGET = ROOT / "api/openapi.identity-planned.yaml"
BASELINE = ROOT / "api/openapi.yaml"
URI = "urn:c2:identity-contract"
METHODS = {"get", "post", "put", "patch", "delete", "head", "options", "trace"}


class UniqueKeyLoader(yaml.SafeLoader):
    """重复 YAML 键会覆盖路径或字段，按错误处理而非静默接受。"""


def unique_mapping(loader, node, deep=False):
    values = {}
    for key_node, value_node in node.value:
        key = loader.construct_object(key_node, deep=deep)
        if key in values:
            raise ValueError(f"Duplicate YAML key: {key}")
        values[key] = loader.construct_object(value_node, deep=deep)
    return values


UniqueKeyLoader.add_constructor(yaml.resolver.BaseResolver.DEFAULT_MAPPING_TAG, unique_mapping)


def load(path):
    return yaml.load(path.read_text(encoding="utf-8"), Loader=UniqueKeyLoader)


def operations(spec):
    for path, block in spec["paths"].items():
        for method, operation in block.items():
            if method in METHODS:
                yield path, method, operation


def check(condition, message):
    if not condition:
        raise ValueError(message)


def main():
    baseline, target = load(BASELINE), load(TARGET)
    validate(baseline)
    validate(target)
    expected = {
        ("/api/auth/login", "post"), ("/api/auth/logout", "post"),
        ("/api/auth/me", "get"), ("/api/auth/me/permission-check", "get"),
        ("/api/departments", "get"), ("/api/auth/me/api-check", "get"),
        ("/api/employees", "get"), ("/api/employees/{employeeId}", "get"),
    }
    actual = list(operations(target))
    check({(p, m) for p, m, _ in actual} == expected, "Expected exactly eight identity operations")
    ids = [op["operationId"] for _, _, op in actual]
    check(len(ids) == len(set(ids)), "Duplicate operationId")
    check(target["x-implementation-status"] in {"planned", "partially-implemented", "implemented"},
          "Target must distinguish actual implementation status")
    if target["x-implementation-status"] == "implemented":
        check(all(op["x-implementation-status"] in {"implemented", "retained"} for _, _, op in actual),
              "Completed backend snapshot still contains planned operations")
    check(target["security"] == baseline["security"] == [{"BearerAuth": []}], "Bearer schemes drifted")
    check(baseline["components"]["securitySchemes"]["BearerAuth"]["scheme"] == "bearer", "Missing JWT scheme")
    check("FuncSessionCookie" not in baseline["components"]["securitySchemes"], "Legacy session authentication retained")
    check(target["servers"] == baseline["servers"], "Edition routes changed")
    for path, method, op in actual:
        check("x-implementation-status" in op, "Missing implementation status")
        old = baseline["paths"].get(path, {}).get(method)
        if old:
            check(op["operationId"] == old["operationId"], "Existing operationId changed")
        if op["x-implementation-status"] == "implemented":
            check(old is not None, "Implemented target missing from main OpenAPI")
            success = "204" if path == "/api/auth/logout" else "200"
            check(op["responses"][success] == old["responses"][success],
                  "Implemented success response differs between main and target OpenAPI")
        if path.startswith("/api/auth/me"):
            check(not any(p.get("name") in {"employeeId", "username"} for p in op.get("parameters", [])),
                  "Self query must not accept target identity")
    for path in ["/api/auth/login", "/api/auth/logout"]:
        check(target["paths"][path]["post"]["parameters"] == baseline["paths"][path]["post"]["parameters"],
              "Existing CSRF requirement changed")
    for name in ["LoginRequest", "EmployeeIdentity", "Workspace", "ManagementEmployee", "ManagementOrganization"]:
        check(target["components"]["schemas"][name] == baseline["components"]["schemas"][name],
              f"Legacy schema changed: {name}")
    check(baseline["paths"]["/api/auth/refresh"]["post"]["operationId"] == "refreshLogin", "Missing refresh auxiliary API")
    check(baseline["paths"]["/api/auth/login"]["post"]["responses"]["200"]["content"]["application/json"]["schema"]
          == {"$ref": "#/components/schemas/LoginResponse"}, "Login does not expose access JWT")
    for path, method, operation in operations(baseline):
        if path not in {"/api/auth/login", "/api/auth/logout", "/api/auth/refresh"}:
            check(not any(parameter.get("$ref") == "#/components/parameters/Csrf"
                          for parameter in operation.get("parameters", [])), "Bearer business operation still requires CSRF")
    if target["paths"]["/api/auth/me"]["get"]["x-implementation-status"] == "implemented":
        for name in ["CurrentSession", "CurrentSessionResult", "AuthorizedCurrentSession", "CurrentAuthorization",
                     "CurrentRole", "CurrentPermission", "CurrentPermissionCheck"]:
            check(target["components"]["schemas"][name] == baseline["components"]["schemas"][name],
                  f"Implemented schema drifted: {name}")
    if target["paths"]["/api/departments"]["get"]["x-implementation-status"] == "implemented":
        for name in ["DirectoryEmployee", "DirectoryEmployeePage", "EmployeeDirectoryResult", "OrganizationTreeNode",
                     "OrganizationTreePage", "DepartmentDirectoryResult"]:
            check(target["components"]["schemas"][name] == baseline["components"]["schemas"][name],
                  f"Implemented directory schema drifted: {name}")

    if target["paths"]["/api/auth/me/api-check"]["get"]["x-implementation-status"] == "implemented":
        for section, name in [("schemas", "CurrentApiCheck"), ("parameters", "IdentityOperationId")]:
            check(target["components"][section][name] == baseline["components"][section][name],
                  f"Implemented API check contract drifted: {name}")
        definitions = []
        migrations = []
        for edition in ["func", "oo"]:
            source = ROOT / f"backend-{edition}/src/main"
            definitions.append((source / "java/simulation/RequestOperation.java").read_bytes())
            migrations.append((source / "resources/db/migration/V9__api_operation_permissions.sql").read_bytes())
        check(definitions[0] == definitions[1] and migrations[0] == migrations[1],
              "Edition API operation definitions or migrations differ")
        request_ids = re.findall(r'^    [A-Z]+\("([^"]+)"', definitions[0].decode(), re.M)
        main_ids = {operation["operationId"] for _, _, operation in operations(baseline)}
        check(len(request_ids) == 8 and set(request_ids) <= main_ids,
              "Mapped request operationIds differ from actual OpenAPI operations")

    # 使用 OpenAPI 3.0 的 nullable/Schema 语义校验示例，并解析同文件引用。
    registry = Registry().with_resource(URI, Resource(contents=target, specification=DRAFT4))
    examples = 0
    for _, _, op in actual:
        containers = [op.get("requestBody", {})] + list(op["responses"].values())
        for container in containers:
            for media in container.get("content", {}).values():
                values = ([media["example"]] if "example" in media else [])
                values += [entry["value"] for entry in media.get("examples", {}).values()]
                for value in values:
                    OAS30Validator(media["schema"], _resolver=registry.resolver(URI)).validate(value)
                    examples += 1

    schemas = target["components"]["schemas"]
    check("phone" not in schemas["DirectoryEmployee"]["properties"], "Ordinary projection exposes phone")
    ordinary = {"id": 101, "username": "employee", "name": "员工", "organizationId": 2}
    validator = OAS30Validator(schemas["DirectoryEmployee"])
    check(not validator.is_valid({**ordinary, "phone": "13800000000"}), "Ordinary schema permits extra contacts")
    empty = {"items": [], "page": 2, "pageSize": 20, "total": 1}
    OAS30Validator(schemas["EmployeeDirectoryResult"], _resolver=registry.resolver(URI)).validate(empty)

    # 额外手写用例覆盖输入边界，避免只校验成功示例。
    negatives = 2
    jwt_registry = Registry().with_resource(URI, Resource(contents=baseline, specification=DRAFT4))
    token_validator = OAS30Validator(baseline["components"]["schemas"]["AccessToken"], _resolver=jwt_registry.resolver(URI))
    check(token_validator.is_valid({"accessToken": "sample", "tokenType": "Bearer", "expiresIn": 900}), "Invalid JWT response schema")
    for invalid in ({"accessToken":"sample","tokenType":"Bearer","expiresIn":0},
                    {"accessToken":"sample","tokenType":"Bearer","expiresIn":901},
                    {"accessToken":"sample","tokenType":"Bearer","expiresIn":900,"refreshToken":"must-not-be-json"}):
        check(not token_validator.is_valid(invalid), "JWT response boundary accepted")
        negatives += 1
    for name, invalid in [("IdentitySystemCode", "   "), ("IdentityPermissionCode", ""),
                          ("IdentityOperationId", "x" * 129), ("DirectoryKeyword", "x" * 129),
                          ("DepartmentFilter", 0), ("DirectoryParent", -1), ("DirectoryView", "unknown"),
                          ("ManagementPageNumber", 0), ("PageSize", 101)]:
        check(not OAS30Validator(target["components"]["parameters"][name]["schema"]).is_valid(invalid),
              f"Invalid parameter accepted by schema: {name}")
        negatives += 1

    markdown = ROOT / "api/统一身份鉴权接口契约.md"
    check(all(f"ID-{n:02}" in markdown.read_text(encoding="utf-8") for n in range(1, 9)), "Missing capability")
    # 本检查覆盖此次维护的文档；不会修复或重新整理用户的其他文件。
    docs = [markdown, ROOT / "README.md", ROOT / "docs/项目总结.md",
            ROOT / "部署/README.md", ROOT / "测试/README.md"]
    links = 0
    for file in docs:
        content = file.read_text(encoding="utf-8")
        check("\ufffd" not in content, f"Encoding replacement character: {file}")
        check(all(line.rstrip() == line for line in content.splitlines()), f"Trailing spaces: {file}")
        for link in re.findall(r"\]\(([^)]+)\)", content):
            if link.startswith(("http:", "https:", "#")):
                continue
            check((file.parent / link.split("#")[0]).exists(), f"Broken local link in {file.name}: {link}")
            links += 1
    print(json.dumps({"status": "passed", "operations": len(actual), "examples": examples,
                      "negativeAndEmptyCases": negatives, "localLinks": links,
                      "scope": "static contract validation only; no HTTP, database or browser"},
                     ensure_ascii=True))


if __name__ == "__main__":
    main()
