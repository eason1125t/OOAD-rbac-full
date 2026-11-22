# 文件职责：构建并在隔离库、JWT登录空间和端口运行前端及可选原表全量验收。
# 协作关系：复用指定的已验证JAR和Playwright配置；归档日志，只停止自己创建的进程。
from pathlib import Path
import argparse
import concurrent.futures
import datetime
import hashlib
import json
import os
import shutil
import socket
import subprocess
import time
import tempfile
import urllib.request

ROOT = Path(__file__).resolve().parents[2]


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--output-dir", type=Path, required=True)
    parser.add_argument("--suite", choices=["identity", "regression", "full"], default="identity")
    parser.add_argument("--excel-dir", type=Path, help="full模式必需：私密课程原表目录")
    parser.add_argument("--backend-build-dir", type=Path, default=Path("target/jwt-final"))
    parser.add_argument("--grep", help="可选：只运行匹配标题的Playwright场景；不作为完整回归")
    args = parser.parse_args()
    if args.backend_build_dir.is_absolute() or args.backend_build_dir.parts[:1] != ("target",) or ".." in args.backend_build_dir.parts:
        parser.error("后端构建目录必须在target内")
    if args.suite == "full" and (not args.excel_dir or not args.excel_dir.resolve().is_dir()):
        parser.error("full模式必须指定已有的课程原表目录")
    output = (ROOT / args.output_dir).resolve()
    assert output.is_relative_to((ROOT / "测试/结果").resolve()), "Output must stay in C2 test results"
    if output.exists() and any(output.iterdir()):
        parser.error("请选择新的空归档目录，不能覆盖已有浏览器证据")
    output.mkdir(parents=True, exist_ok=True)
    for port in [28181, 28182, 15173]:
        with socket.socket() as probe:
            assert probe.connect_ex(("127.0.0.1", port)) != 0, f"Test port {port} already occupied"
    docker = json.loads(subprocess.check_output(["docker", "inspect", "ooad-rbac-c2-dev-mysql"]))[0]
    settings = dict(item.split("=", 1) for item in docker["Config"]["Env"] if "=" in item)
    environment = os.environ.copy()
    environment.update(MYSQL_PWD=settings["MYSQL_ROOT_PASSWORD"], RBAC_DB_USER="root",
                       RBAC_DB_PASSWORD=settings["MYSQL_ROOT_PASSWORD"], RBAC_REDIS_HOST="127.0.0.1",
                       RBAC_REDIS_PORT="36390", RBAC_DEMO_ACCOUNTS_ENABLED="true")
    schemas = {edition: "ooad_rbac1_" + edition + "_ui_test" for edition in ["func", "oo"]}
    if args.suite == "full":
        # 每批全量原表验收使用新库，防止前次UI夹具污染原表摘要；不清空任何旧库。
        suffix = datetime.datetime.now(datetime.timezone.utc).strftime("%Y%m%d%H%M%S")
        schemas = {edition: "ooad_rbac1_" + edition + "_final_test_" + suffix for edition in ["func", "oo"]}
    sql = "\n".join("CREATE DATABASE IF NOT EXISTS `" + name
                    + "` CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci;" for name in schemas.values())
    subprocess.run(["docker", "exec", "-i", "-e", "MYSQL_PWD", "ooad-rbac-c2-dev-mysql",
                    "mysql", "-uroot", "--batch"], input=sql.encode(), env=environment, check=True)
    npm = shutil.which("npm.cmd") or shutil.which("npm")
    java = shutil.which("java")
    assert npm and java
    hidden = subprocess.CREATE_NO_WINDOW if os.name == "nt" else 0
    processes = []
    handles = []
    started = datetime.datetime.now(datetime.timezone.utc).isoformat()
    metadata = {"startedUtc": started, "suite": args.suite, "grep": args.grep, "databases": schemas,
                "ports": [28181, 28182, 15173], "jars": {}}
    (output / "命令.txt").write_text(
        "工作目录：C2\n入口：python 测试/脚本/run_frontend_identity.py --output-dir " + str(args.output_dir)
        + " --suite " + args.suite + " --backend-build-dir " + str(args.backend_build_dir)
        + (" --grep " + args.grep if args.grep else "")
        + (" --excel-dir <私密原表目录>" if args.suite == "full" else "")
        + "\n前端：npm run build；Playwright使用Edge、15173，产物预览模式。"
        + "\n后端：指定构建目录JAR，28181/28182；隔离测试库、外部临时测试密钥及独立JWT登录命名空间。"
        + "\n凭据仅传入子进程环境。原开发进程不停止，不清空数据库或批量删除文件。\n", encoding="utf-8")
    try:
        with (output / "前端构建.txt").open("w", encoding="utf-8") as log:
            subprocess.run([npm, "run", "build"], cwd=ROOT / "frontend", env=environment,
                           stdout=log, stderr=subprocess.STDOUT, check=True, creationflags=hidden)
        def initialize(edition):
            jar = ROOT / f"backend-{edition}" / args.backend_build_dir / f"rbac1-{edition}-0.1.0-SNAPSHOT.jar"
            assert jar.is_file()
            env = environment.copy()
            env.update(RBAC_DB_URL="jdbc:mysql://127.0.0.1:33327/" + schemas[edition],
                       RBAC_AUTH_NAMESPACE="rbac1:" + edition + ":ui-jwt-test:" + datetime.datetime.now(datetime.timezone.utc).strftime("%Y%m%d%H%M%S") + "_" + os.urandom(4).hex() ,
                       RBAC_AUTH_COOKIE_PATH="/" + edition + "/api/auth/")
            openssl = shutil.which("openssl")
            assert openssl, "OpenSSL is required for isolated JWT test keys"
            keys = Path(tempfile.mkdtemp(prefix="c2-jwt-browser-" + edition + "-"))
            subprocess.run([openssl, "genpkey", "-algorithm", "RSA", "-pkeyopt", "rsa_keygen_bits:2048", "-out", str(keys / "private.pem")],
                           check=True, stdout=subprocess.DEVNULL, stderr=subprocess.PIPE)
            subprocess.run([openssl, "pkey", "-in", str(keys / "private.pem"), "-pubout", "-out", str(keys / "public.pem")],
                           check=True, stdout=subprocess.DEVNULL, stderr=subprocess.PIPE)
            env.update(RBAC_JWT_PRIVATE_KEY_LOCATION=(keys / "private.pem").as_uri(),
                       RBAC_JWT_PUBLIC_KEY_LOCATION=(keys / "public.pem").as_uri())
            directory = output / ("runtime-" + edition)
            directory.mkdir(exist_ok=True)
            with (output / (edition + "-初始化.txt")).open("w", encoding="utf-8") as log:
                subprocess.run([java, "-jar", str(jar), "--init-demo"], cwd=directory, env=env,
                               stdout=log, stderr=subprocess.STDOUT, check=True, timeout=120, creationflags=hidden)
            if args.suite == "full":
                with (output / (edition + "-原表导入.txt")).open("w", encoding="utf-8") as log:
                    subprocess.run([java, "-jar", str(jar), "--import-excel=" + str(args.excel_dir.resolve())],
                                   cwd=directory, env=env, stdout=log, stderr=subprocess.STDOUT,
                                   check=True, timeout=180, creationflags=hidden)
            return edition, jar, env
        with concurrent.futures.ThreadPoolExecutor(max_workers=2) as pool:
            initialized = list(pool.map(initialize, ["func", "oo"]))
        for edition, jar, env in initialized:
            metadata["jars"][edition] = hashlib.sha256(jar.read_bytes()).hexdigest()
            port = 28181 if edition == "func" else 28182
            handle = (output / (edition + "-服务.txt")).open("w", encoding="utf-8")
            handles.append(handle)
            process = subprocess.Popen([java, "-jar", str(jar), "--server.port=" + str(port)],
                                       cwd=output / ("runtime-" + edition), env=env,
                                       stdout=handle, stderr=subprocess.STDOUT, creationflags=hidden)
            processes.append(process)
        deadline = time.monotonic() + 90
        for port in [28181, 28182]:
            while True:
                assert all(process.poll() is None for process in processes), "Test backend exited"
                try:
                    with urllib.request.urlopen(f"http://127.0.0.1:{port}/api/auth/csrf", timeout=2) as response:
                        assert response.status == 200
                    break
                except (OSError, TimeoutError):
                    assert time.monotonic() < deadline, "Test backend startup timed out"
                    time.sleep(0.25)
        browser_env = environment.copy()
        for edition, jar, env in initialized:
            browser_env["RBAC_" + edition.upper() + "_AUTH_NAMESPACE"] = env["RBAC_AUTH_NAMESPACE"]
            metadata.setdefault("authNamespaces", {})[edition] = env["RBAC_AUTH_NAMESPACE"]
        browser_env.update(RBAC_FUNC_API_TARGET="http://127.0.0.1:28181",
                           RBAC_OO_API_TARGET="http://127.0.0.1:28182", RBAC_FRONTEND_TEST_PORT="15173",
                           RBAC_BROWSER_CHANNEL="msedge", RBAC_BROWSER_PREVIEW="true",
                           RBAC_BROWSER_RESULTS_DIR=str(output))
        files = ["identity.spec.ts", "jwt.spec.ts"]
        if args.suite in {"regression", "full"}:
            files += ["authorization.spec.ts", "role-permissions.spec.ts", "request-read.spec.ts",
                      "oo-entry.spec.ts", "isolation.spec.ts"]
        if args.suite == "full":
            files += ["excel-review.spec.ts"]
            browser_env["RBAC_EXCEL_REVIEW_DIR"] = str(args.excel_dir.resolve())
        with (output / "浏览器命令行.txt").open("w", encoding="utf-8") as log:
            result = subprocess.run([npm, "exec", "--", "playwright", "test", *files]
                                    + (["--grep", args.grep] if args.grep else []),
                                    cwd=ROOT / "测试/前端", env=browser_env, stdout=log,
                                    stderr=subprocess.STDOUT, creationflags=hidden)
        metadata["exitCode"] = result.returncode
        report = output / "browser-results.json"
        if report.exists():
            metadata["stats"] = json.loads(report.read_text(encoding="utf-8"))["stats"]
        digest = hashlib.sha256()
        for file in sorted(list((ROOT / "frontend/src").rglob("*")) + [ROOT / "frontend/vite.config.ts",
                          ROOT / "frontend/package.json", ROOT / "frontend/package-lock.json"]
                          + list((ROOT / "测试/前端/tests").rglob("*.ts")) + [ROOT / "测试/前端/playwright.config.ts"]):
            if file.is_file():
                digest.update(file.read_bytes())
        metadata["frontendAndTestSha256"] = digest.hexdigest()
    except Exception as failure:
        metadata.update(exitCode=1, failure=type(failure).__name__ + ": " + str(failure))
    finally:
        for process in processes:
            if process.poll() is None:
                process.terminate()
        for process in processes:
            try:
                process.wait(timeout=15)
            except subprocess.TimeoutExpired:
                process.kill()
                process.wait(timeout=10)
        for handle in handles:
            handle.close()
        metadata["temporaryBackendsStopped"] = all(process.poll() is not None for process in processes)
        metadata["completedUtc"] = datetime.datetime.now(datetime.timezone.utc).isoformat()
        (output / "结果.json").write_text(json.dumps(metadata, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
        print(json.dumps(metadata, ensure_ascii=True))
    raise SystemExit(metadata["exitCode"])


if __name__ == "__main__":
    main()
