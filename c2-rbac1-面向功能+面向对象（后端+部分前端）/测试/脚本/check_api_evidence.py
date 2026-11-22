# 文件职责：核对阶段归档是否对应当前源码、真实测试和可运行包，避免引用过期结果。
# 协作关系：复用run_backend摘要范围，检查第五步两版结果及本轮维护的文档链接。
from pathlib import Path
import hashlib
import json
import re
import zipfile
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[2]
BATCH = ROOT / "测试/结果/API映射-20261121"
REPORT = BATCH / "GREEN-verified"


def main():
    results = {}
    for edition, expected_tests, expected_sources in [("func", 88, 88), ("oo", 92, 95)]:
        backend = ROOT / ("backend-" + edition)
        evidence = REPORT / edition
        record = json.loads((evidence / "结果.json").read_text(encoding="utf-8"))
        assert record["exitCode"] == 0
        assert record["tests"] == {"tests": expected_tests, "failures": 0, "errors": 0, "skipped": 0}
        digest = hashlib.sha256()
        for file in sorted(list((backend / "src/main").rglob("*")) + list((ROOT / "测试/后端").rglob("*.java"))):
            if file.is_file() and file.suffix in {".java", ".sql", ".yaml", ".yml", ".xml"}:
                digest.update(file.read_bytes())
        digest.update((backend / "pom.xml").read_bytes())
        assert digest.hexdigest() == record["sourceSha256"], edition + ": archive does not match source"
        sources = len(list((backend / "src/main/java").rglob("*.java")))
        assert sources == expected_sources
        http = ET.parse(evidence / "surefire/TEST-contract.AuthSessionTest.xml").getroot()
        migration = ET.parse(evidence / "surefire/TEST-contract.ApiMigrationTest.xml").getroot()
        assert int(http.attrib["tests"]) == 65 and int(migration.attrib["tests"]) == 2
        jar = backend / ("target/identity-step5/rbac1-" + edition + "-0.1.0-SNAPSHOT.jar")
        with zipfile.ZipFile(jar) as archive:
            names = set(archive.namelist())
            assert "BOOT-INF/classes/authorization/ApiOperationRegistry.class" in names
            assert "BOOT-INF/classes/db/migration/V9__api_operation_permissions.sql" in names
            assert not any("AuthSessionTest" in name or "ApiMigrationTest" in name for name in names)
        for file in ["命令.txt", "命令行输出.txt", "结果.txt", "jacoco/index.html", "jacoco/jacoco.xml", "JaCoCo结果.png"]:
            assert (evidence / file).is_file()
        results[edition] = {"sourceSha256": digest.hexdigest(), "productionJavaFiles": sources,
                            "tests": record["tests"], "commonHttpTests": 65, "migrationTests": 2,
                            "jarBytes": jar.stat().st_size}
    documents = [ROOT / "测试/README.md", ROOT / "docs/design/数据库逻辑设计.md"]
    for edition in ["func", "oo"]:
        backend = ROOT / ("backend-" + edition)
        documents += [backend / "docs/模块索引.md", backend / "docs/源码索引.md"]
        documents += [backend / f"src/main/java/{module}/README.md"
                      for module in ["authentication", "authorization", "initialization", "simulation"]]
    links = 0
    for document in documents:
        content = document.read_text(encoding="utf-8")
        assert "\ufffd" not in content
        for link in re.findall(r"\]\(([^)]+)\)", content):
            if link.startswith(("http:", "https:", "#")):
                continue
            assert (document.parent / link.split("#")[0]).exists(), str(document) + ": " + link
            links += 1
    result = {"status": "passed", "editions": results, "documentLinks": links,
              "browserBusinessTests": "not run", "deployment": "not performed", "jwt": "deferred"}
    (BATCH / "复核结果.json").write_text(json.dumps(result, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(json.dumps(result, ensure_ascii=True))


if __name__ == "__main__":
    main()
