"""构建 C2 镜像、生成私有凭据/配置及执行一次性初始化；不自动创建集群或删除数据。"""
import argparse
import json
import os
from pathlib import Path
import re
import secrets
import shutil
import subprocess
import time

HERE = Path(__file__).resolve().parent
C2 = HERE.parent
PASSWORD_KEYS = ("mysql_root_password", "func_db_password", "oo_db_password", "redis_password")
JWT_KEYS = tuple(edition + "_jwt_" + kind for edition in ("func", "oo") for kind in ("private", "public"))
SECRET_KEYS = PASSWORD_KEYS + JWT_KEYS


def read_settings(path):
    """读取简单 KEY=VALUE 文件，不执行 shell、不展开命令或秘密内容。"""
    values = {}
    for number, raw in enumerate(path.read_text(encoding="utf-8-sig").splitlines(), 1):
        line = raw.strip()
        if not line or line.startswith("#"):
            continue
        key, separator, value = line.partition("=")
        if not separator or not re.fullmatch(r"RBAC_[A-Z0-9_]+", key) or key in values:
            raise ValueError(f"Invalid or duplicate setting at line {number}")
        values[key] = value.strip().strip("\"'")
    name = values.get("RBAC_STACK_NAME", "c2")
    if not re.fullmatch(r"[a-z][a-z0-9-]{0,39}", name):
        raise ValueError("RBAC_STACK_NAME must be a short lowercase service name")
    port = int(values.get("RBAC_HTTP_PORT", "8080"))
    if not 1 <= port <= 65535:
        raise ValueError("Invalid RBAC_HTTP_PORT")
    if values.get("RBAC_AUTH_COOKIE_SECURE", "false") not in ("true", "false"):
        raise ValueError("RBAC_AUTH_COOKIE_SECURE must be true or false")
    if "RBAC_SESSION_COOKIE_SECURE" in values:
        raise ValueError("Replace retired RBAC_SESSION_COOKIE_SECURE with RBAC_AUTH_COOKIE_SECURE explicitly")
    values["RBAC_STACK_NAME"] = name
    return values


def run(arguments, settings, capture=False):
    """参数以列表传递，失败立即停止，不打印含敏感值的进程环境。"""
    result = subprocess.run(arguments, cwd=HERE, env={**os.environ, **settings},
                            text=True, encoding="utf-8", capture_output=capture, check=True)
    return result.stdout.strip() if capture else ""


def generate_secrets(directory):
    """生成与初始化 SQL 兼容的随机凭据，已有文件只校验、绝不覆盖。"""
    directory.mkdir(parents=True, exist_ok=True, mode=0o700)
    directory.chmod(0o700)
    for key in PASSWORD_KEYS:
        path = directory / key
        if not path.exists():
            with path.open("x", encoding="ascii") as output:
                output.write(secrets.token_hex(32))
        if not re.fullmatch(r"[0-9a-f]{64}", path.read_text(encoding="ascii").strip()):
            raise ValueError(f"Invalid secret file: {key}; existing value was not replaced")
        # 父目录仅本人可进入；文件只读，使 Compose 挂载后非 root 应用也能读取。
        path.chmod(0o444)



def generate_jwt_keys(directory):
    """生成两版独立RSA密钥；已存在私钥只验证，不覆盖、不自动旋转。"""
    directory.mkdir(parents=True, exist_ok=True, mode=0o700)
    directory.chmod(0o700)
    openssl = shutil.which("openssl")
    if not openssl:
        raise ValueError("OpenSSL is required to prepare external JWT keys")
    for edition in ("func", "oo"):
        private, public = directory / (edition + "_jwt_private"), directory / (edition + "_jwt_public")
        if public.exists() and not private.exists():
            raise ValueError("Incomplete JWT key pair: " + edition + "; existing file was not replaced")
        if not private.exists():
            subprocess.run([openssl, "genpkey", "-algorithm", "RSA", "-pkeyopt", "rsa_keygen_bits:2048", "-out", str(private)],
                           check=True, stdout=subprocess.DEVNULL, stderr=subprocess.PIPE)
        subprocess.run([openssl, "rsa", "-in", str(private), "-check", "-noout"],
                       check=True, stdout=subprocess.DEVNULL, stderr=subprocess.PIPE)
        derived = subprocess.check_output([openssl, "pkey", "-in", str(private), "-pubout"], stderr=subprocess.PIPE)
        if public.exists() and public.read_bytes().strip() != derived.strip():
            raise ValueError("JWT public/private keys do not match: " + edition + "; existing files were not replaced")
        if not public.exists():
            with public.open("xb") as output:
                output.write(derived)
        details = subprocess.check_output([openssl, "pkey", "-pubin", "-in", str(public), "-text_pub", "-noout"], stderr=subprocess.PIPE)
        bits = re.search(rb"\((\d+) bit\)", details)
        if not bits or int(bits.group(1)) < 2048:
            raise ValueError("JWT RSA key must have at least 2048 bits: " + edition)
        private.chmod(0o444)
        public.chmod(0o444)


def prepare_secrets(settings, create_in_swarm):
    """本机文件与 Swarm secret 分开创建，已存在的 Swarm secret 不旋转。"""
    generate_secrets(HERE / "secrets")
    generate_jwt_keys(HERE / "secrets")
    if create_in_swarm:
        swarm = json.loads(run(["docker", "info", "--format", "{{json .Swarm}}"], settings, True))
        if not swarm.get("ControlAvailable"):
            raise ValueError("Run --create-in-swarm on a Swarm manager; no swarm was initialized")
        for key in SECRET_KEYS:
            name = f"{settings['RBAC_STACK_NAME']}_{key}"
            existing = subprocess.run(["docker", "secret", "inspect", name],
                                      stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
            if existing.returncode == 0:
                print(f"Kept existing Swarm secret: {name} (not rotated)")
            else:
                run(["docker", "secret", "create", name, str(HERE / "secrets" / key)], settings, True)
                print(f"Created Swarm secret: {name}")
    print("Secret files ready; values were not printed. Keep the ignored directory private.")


def build_images(settings, push):
    """两版均安装课程 CloneFactory；前端只构建一次，可选发布三份镜像。"""
    for edition, key in (("func", "RBAC_FUNC_IMAGE"), ("oo", "RBAC_OO_IMAGE")):
        command = ["docker", "build", "--progress=plain", "-f", str(HERE / "Dockerfile.backend"),
                   "--build-arg", f"EDITION={edition}", "-t", settings[key]]
        for arg in ("MAVEN_IMAGE", "JAVA_IMAGE"):
            if settings.get(f"RBAC_{arg}"):
                command += ["--build-arg", f"{arg}={settings[f'RBAC_{arg}']}"]
        run(command + [str(C2)], settings)
    command = ["docker", "build", "--progress=plain", "-f", str(HERE / "Dockerfile.frontend"),
               "-t", settings["RBAC_FRONTEND_IMAGE"]]
    for arg in ("NODE_IMAGE", "NGINX_IMAGE"):
        if settings.get(f"RBAC_{arg}"):
            command += ["--build-arg", f"{arg}={settings[f'RBAC_{arg}']}"]
    run(command + [str(C2)], settings)
    if push:
        for key in ("RBAC_FUNC_IMAGE", "RBAC_OO_IMAGE", "RBAC_FRONTEND_IMAGE"):
            run(["docker", "push", settings[key]], settings)


def render(settings, local):
    """Swarm 显式渲染参数；本地验证使用独立 bridge、文件 secret 与持久卷。"""
    if local:
        command = ["docker", "compose", "--project-name", settings["RBAC_STACK_NAME"],
                   "-f", str(HERE / "stack.yaml"), "config", "--format", "json"]
        config = json.loads(run(command, settings, True))
        config["networks"]["app"] = {"driver": "bridge"}
        config["secrets"] = {key: {"file": str(HERE / "secrets" / key)} for key in SECRET_KEYS}
        for name, service in config["services"].items():
            service.pop("deploy", None)
            service["restart"] = "unless-stopped"
            for mounted_config in service.get("configs", []):
                mounted_config.pop("mode", None)
            if name.startswith("backend-"):
                service["depends_on"] = {key: {"condition": "service_healthy"} for key in ("mysql", "redis")}
        config["services"]["frontend"]["ports"] = [{"target": 8080,
                "published": str(settings.get("RBAC_HTTP_PORT", "8080")), "host_ip": "127.0.0.1",
                "protocol": "tcp"}]
        config["services"]["frontend"]["depends_on"] = {
                key: {"condition": "service_healthy"} for key in ("backend-func", "backend-oo")}
        output = HERE / "local.json"
        output.write_text(json.dumps(config, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    else:
        if settings.get("RBAC_DATA_NODE", "").startswith("replace-") or not settings.get("RBAC_DATA_NODE"):
            raise ValueError("Fill RBAC_DATA_NODE before rendering Swarm configuration")
        output = HERE / "stack.rendered.yaml"
        output.write_text(run(["docker", "stack", "config", "-c", str(HERE / "stack.yaml")],
                              settings, True) + "\n", encoding="utf-8")
    print(f"Rendered: {output.name}; inspect before deployment")


def initialize(settings, editions, mode, excel_dir):
    """创建不重启的一次性 Swarm 服务，成功退出才继续下一版，失败保留现场。"""
    stack = settings["RBAC_STACK_NAME"]
    if mode == "excel" and (not excel_dir or not excel_dir.startswith("/")):
        raise ValueError("Excel input must be an absolute directory on the configured data node")
    if not settings.get("RBAC_DATA_NODE") or settings["RBAC_DATA_NODE"].startswith("replace-"):
        raise ValueError("Fill RBAC_DATA_NODE before initialization")
    for edition in editions:
        job = f"{stack}-init-{edition}-{secrets.token_hex(4)}"
        command = ["docker", "service", "create", "--detach=true", "--with-registry-auth",
                   "--no-healthcheck", "--name", job,
                   "--network", f"{stack}_app", "--restart-condition", "none",
                   "--constraint", f"node.hostname=={settings['RBAC_DATA_NODE']}",
                   "--secret", f"source={stack}_{edition}_db_password,target=db_password",
                   "--secret", f"source={stack}_redis_password,target=redis_password"]
        environment = {"RBAC_DB_URL": f"jdbc:mysql://{stack}_mysql:3306/ooad_rbac1_{edition}",
                       "RBAC_DB_USER": f"rbac_{edition}", "RBAC_DB_PASSWORD_FILE": "/run/secrets/db_password",
                       "RBAC_REDIS_HOST": f"{stack}_redis", "RBAC_REDIS_PORT": "6379",
                       "RBAC_REDIS_PASSWORD_FILE": "/run/secrets/redis_password",
                       "RBAC_DEMO_ACCOUNTS_ENABLED": "true" if mode == "demo" else "false"}
        for key, value in environment.items():
            command += ["--env", f"{key}={value}"]
        if mode == "excel":
            command += ["--mount", f"type=bind,source={excel_dir},target=/input,readonly"]
        command += [settings[f"RBAC_{'FUNC' if edition == 'func' else 'OO'}_IMAGE"],
                    "--init-demo" if mode == "demo" else "--import-excel=/input"]
        run(command, settings, True)
        print(f"Waiting for {job}", flush=True)
        deadline = time.monotonic() + 600
        while time.monotonic() < deadline:
            tasks = run(["docker", "service", "ps", "--no-trunc", "--format", "{{.ID}}", job], settings, True).splitlines()
            if tasks:
                status = json.loads(run(["docker", "inspect", "--format", "{{json .Status}}", tasks[0]], settings, True))
                if status["State"] == "complete" and status.get("ContainerStatus", {}).get("ExitCode") == 0:
                    print(f"Completed {job}; task retained for evidence")
                    break
                if status["State"] in ("failed", "rejected", "shutdown", "orphaned"):
                    raise RuntimeError(f"Initialization failed: {job}; inspect its service logs")
            time.sleep(2)
        else:
            raise TimeoutError(f"Initialization timeout: {job}; task retained for diagnosis")


def main():
    """操作由明确子命令触发，默认不推镜像、不部署、不初始化集群。"""
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--env", type=Path, default=HERE / ".env")
    commands = parser.add_subparsers(dest="command", required=True)
    commands.add_parser("build").add_argument("--push", action="store_true")
    commands.add_parser("secrets").add_argument("--create-in-swarm", action="store_true")
    commands.add_parser("render").add_argument("--local", action="store_true")
    init = commands.add_parser("init")
    init.add_argument("--edition", choices=("func", "oo", "both"), default="both")
    init.add_argument("--mode", choices=("demo", "excel"), required=True)
    init.add_argument("--excel-dir")
    args = parser.parse_args()
    settings = read_settings(args.env.resolve())
    if args.command == "build":
        build_images(settings, args.push)
    elif args.command == "secrets":
        prepare_secrets(settings, args.create_in_swarm)
    elif args.command == "render":
        render(settings, args.local)
    else:
        editions = ("func", "oo") if args.edition == "both" else (args.edition,)
        initialize(settings, editions, args.mode, args.excel_dir)


if __name__ == "__main__":
    try:
        main()
    except (ValueError, OSError, RuntimeError, subprocess.CalledProcessError) as failure:
        if isinstance(failure, subprocess.CalledProcessError) and failure.stderr:
            print(failure.stderr)
        raise SystemExit(str(failure)) from failure
