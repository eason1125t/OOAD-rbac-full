# 文件职责：运行选定后端的真实测试并归档 JaCoCo、Surefire 及命令输出。
# 协作关系：读取本机 C2 Docker 依赖，调用独立 Maven 工程；不修改演示库。
from pathlib import Path
import argparse, datetime, hashlib, json, os, shutil, subprocess, sys, tempfile
import xml.etree.ElementTree as ET

def main():
    """严格选择版本及专用测试库，输出可追踪的实际执行结果。"""
    parser = argparse.ArgumentParser()
    parser.add_argument('edition', choices=['func', 'oo'])
    parser.add_argument('--output-dir', type=Path, help='归档目录；相对路径以 C2 根目录为准，默认仍为结果/版本')
    parser.add_argument('--build-dir', type=Path, help='可选构建目录，相对后端根目录，限定在 target 内')
    parser.add_argument('--tests', help='可选 Surefire 测试选择表达式；默认执行全部测试')
    args = parser.parse_args()
    root = Path(__file__).resolve().parents[2]
    backend = root / ('backend-' + args.edition)
    build_dir = (backend / args.build_dir).resolve() if args.build_dir else backend / 'target'
    if args.build_dir and (args.build_dir.is_absolute() or not build_dir.is_relative_to((backend / 'target').resolve())):
        parser.error('--build-dir 必须是后端 target 内的相对路径')
    # 分阶段验证可另存批次，保留已有正式报告和修改前基线。
    output = (root / args.output_dir).resolve() if args.output_dir else root / '测试' / '结果' / args.edition
    output.mkdir(parents=True, exist_ok=True)
    env = os.environ.copy()
    # Docker 已有凭据只放入子进程环境，不进入控制台和命令文本。
    info = json.loads(subprocess.check_output(['docker', 'inspect', 'ooad-rbac-c2-dev-mysql']))[0]
    settings = dict(item.split('=', 1) for item in info['Config']['Env'] if '=' in item)
    env.update(RBAC_DB_URL='jdbc:mysql://127.0.0.1:33327/ooad_rbac1_' + args.edition + '_test',
               RBAC_DB_USER='root', RBAC_DB_PASSWORD=settings['MYSQL_ROOT_PASSWORD'],
               RBAC_REDIS_HOST='127.0.0.1', RBAC_REDIS_PORT='36390')
    # 每轮独立测试密钥只写到工作区外的临时目录，绝不使用或归档部署私钥。
    openssl = shutil.which('openssl')
    if not openssl:
        parser.error('JWT后端测试需要OpenSSL生成临时测试密钥，请先配置openssl命令')
    key_dir = Path(tempfile.mkdtemp(prefix='c2-jwt-test-' + args.edition + '-'))
    private_key, public_key = key_dir/'private.pem', key_dir/'public.pem'
    subprocess.run([openssl, 'genpkey', '-algorithm', 'RSA', '-pkeyopt', 'rsa_keygen_bits:2048',
                    '-out', str(private_key)], check=True, stdout=subprocess.DEVNULL, stderr=subprocess.PIPE)
    subprocess.run([openssl, 'pkey', '-in', str(private_key), '-pubout', '-out', str(public_key)],
                   check=True, stdout=subprocess.DEVNULL, stderr=subprocess.PIPE)
    env.update(RBAC_JWT_PRIVATE_KEY_LOCATION=private_key.as_uri(), RBAC_JWT_PUBLIC_KEY_LOCATION=public_key.as_uri(),
               RBAC_AUTH_NAMESPACE='rbac1:' + args.edition + ':jwt-test:' + key_dir.name.rsplit('-',1)[-1],
               RBAC_AUTH_COOKIE_PATH='/')
    env['MAVEN_OPTS'] = env.get('MAVEN_OPTS', '') + ' -Dfile.encoding=UTF-8 -Dsun.stdout.encoding=UTF-8 -Dsun.stderr.encoding=UTF-8'
    maven = shutil.which('mvn') or next((Path.home()/'.m2/wrapper/dists/apache-maven-3.9.11').glob('*/bin/mvn.cmd'))
    command = [str(maven), '-f', str(backend/'pom.xml'), 'verify']
    if args.build_dir:
        command.append('-Drbac.build.directory=' + args.build_dir.as_posix())
    if args.tests:
        command.append('-Dtest=' + args.tests)
    display = 'mvn -f backend-' + args.edition + '/pom.xml verify'
    if args.build_dir:
        display += ' -Drbac.build.directory=' + args.build_dir.as_posix()
    if args.tests:
        display += ' -Dtest=' + args.tests
    (output/'命令.txt').write_text('工作目录: ' + str(root) + '\n命令: ' + display + '\n环境: RBAC_DB_URL=专用测试库; RBAC_DB_USER=root; RBAC_DB_PASSWORD=<进程环境>; RBAC_REDIS_PORT=36390\n', encoding='utf-8')
    digest = hashlib.sha256()
    for file in sorted(list((backend/'src/main').rglob('*')) + list((root/'测试/后端').rglob('*.java'))):
        if file.is_file() and file.suffix in {'.java', '.sql', '.yaml', '.yml', '.xml', '.lua'}:
            digest.update(file.read_bytes())
    digest.update((backend/'pom.xml').read_bytes())
    started_time = datetime.datetime.now(datetime.timezone.utc)
    started = started_time.isoformat()
    with (output/'命令行输出.txt').open('w', encoding='utf-8') as log:
        result = subprocess.run(command, cwd=root, env=env, stdout=log, stderr=subprocess.STDOUT)
    counts = dict(tests=0, failures=0, errors=0, skipped=0)
    reports = build_dir/'surefire-reports'
    for report in reports.glob('TEST-*.xml'):
        if report.stat().st_mtime < started_time.timestamp():
            continue
        data = ET.parse(report).getroot()
        for key in counts: counts[key] += int(data.attrib[key])
        destination = output/'surefire'; destination.mkdir(exist_ok=True)
        shutil.copy2(report, destination/report.name)
    jacoco = build_dir/'site/jacoco'
    if result.returncode == 0 and jacoco.exists(): shutil.copytree(jacoco, output/'jacoco', dirs_exist_ok=True)
    counters = {}
    if result.returncode == 0 and (jacoco/'jacoco.xml').exists():
        for item in ET.parse(jacoco/'jacoco.xml').getroot().findall('counter'):
            missed, covered = int(item.attrib['missed']), int(item.attrib['covered'])
            counters[item.attrib['type']] = dict(missed=missed, covered=covered, ratio=covered/(missed+covered) if missed+covered else None)
    summary = dict(edition=args.edition, startedUtc=started, completedUtc=datetime.datetime.now(datetime.timezone.utc).isoformat(), exitCode=result.returncode, gitHead=subprocess.check_output(['git','rev-parse','HEAD'],cwd=root).decode().strip(), sourceSha256=digest.hexdigest(), tests=counts, coverage=counters,
                   jwtTestPublicKeySha256=hashlib.sha256(public_key.read_bytes()).hexdigest(), authNamespace=env['RBAC_AUTH_NAMESPACE'],
                   sourceHashIncludesLua=True)
    (output/'结果.json').write_text(json.dumps(summary, ensure_ascii=False, indent=2), encoding='utf-8')
    (output/'结果.txt').write_text(json.dumps(summary, ensure_ascii=False, indent=2), encoding='utf-8')
    print(json.dumps(summary, ensure_ascii=False))
    if result.returncode or not counts['tests'] or counts['failures'] or counts['errors'] or counts['skipped']: sys.exit(1)

if __name__ == '__main__':
    main()
