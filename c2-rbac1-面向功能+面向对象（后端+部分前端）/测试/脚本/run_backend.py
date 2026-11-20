# 文件职责：运行选定后端的真实测试并归档 JaCoCo、Surefire 及命令输出。
# 协作关系：读取本机 C2 Docker 依赖，调用独立 Maven 工程；不修改演示库。
from pathlib import Path
import argparse, datetime, hashlib, json, os, shutil, subprocess, sys
import xml.etree.ElementTree as ET

def main():
    """严格选择版本及专用测试库，输出可追踪的实际执行结果。"""
    parser = argparse.ArgumentParser()
    parser.add_argument('edition', choices=['func', 'oo'])
    args = parser.parse_args()
    root = Path(__file__).resolve().parents[2]
    backend = root / ('backend-' + args.edition)
    output = root / '测试' / '结果' / args.edition
    output.mkdir(parents=True, exist_ok=True)
    env = os.environ.copy()
    # Docker 已有凭据只放入子进程环境，不进入控制台和命令文本。
    info = json.loads(subprocess.check_output(['docker', 'inspect', 'ooad-rbac-c2-dev-mysql']))[0]
    settings = dict(item.split('=', 1) for item in info['Config']['Env'] if '=' in item)
    env.update(RBAC_DB_URL='jdbc:mysql://127.0.0.1:33327/ooad_rbac1_' + args.edition + '_test',
               RBAC_DB_USER='root', RBAC_DB_PASSWORD=settings['MYSQL_ROOT_PASSWORD'],
               RBAC_REDIS_HOST='127.0.0.1', RBAC_REDIS_PORT='36390')
    env['MAVEN_OPTS'] = env.get('MAVEN_OPTS', '') + ' -Dfile.encoding=UTF-8 -Dsun.stdout.encoding=UTF-8 -Dsun.stderr.encoding=UTF-8'
    maven = shutil.which('mvn') or next((Path.home()/'.m2/wrapper/dists/apache-maven-3.9.11').glob('*/bin/mvn.cmd'))
    command = [str(maven), '-f', str(backend/'pom.xml'), 'verify']
    (output/'命令.txt').write_text('工作目录: ' + str(root) + '\n命令: mvn -f backend-' + args.edition + '/pom.xml verify\n环境: RBAC_DB_URL=专用测试库; RBAC_DB_USER=root; RBAC_DB_PASSWORD=<进程环境>; RBAC_REDIS_PORT=36390\n', encoding='utf-8')
    digest = hashlib.sha256()
    for file in sorted(list((backend/'src/main').rglob('*')) + list((root/'测试/后端').rglob('*.java'))):
        if file.is_file() and file.suffix in {'.java', '.sql', '.yaml', '.yml', '.xml'}:
            digest.update(file.read_bytes())
    digest.update((backend/'pom.xml').read_bytes())
    started = datetime.datetime.now(datetime.timezone.utc).isoformat()
    with (output/'命令行输出.txt').open('w', encoding='utf-8') as log:
        result = subprocess.run(command, cwd=root, env=env, stdout=log, stderr=subprocess.STDOUT)
    counts = dict(tests=0, failures=0, errors=0, skipped=0)
    reports = backend/'target/surefire-reports'
    for report in reports.glob('TEST-*.xml'):
        data = ET.parse(report).getroot()
        for key in counts: counts[key] += int(data.attrib[key])
        destination = output/'surefire'; destination.mkdir(exist_ok=True)
        shutil.copy2(report, destination/report.name)
    jacoco = backend/'target/site/jacoco'
    if jacoco.exists(): shutil.copytree(jacoco, output/'jacoco', dirs_exist_ok=True)
    counters = {}
    if (jacoco/'jacoco.xml').exists():
        for item in ET.parse(jacoco/'jacoco.xml').getroot().findall('counter'):
            missed, covered = int(item.attrib['missed']), int(item.attrib['covered'])
            counters[item.attrib['type']] = dict(missed=missed, covered=covered, ratio=covered/(missed+covered) if missed+covered else None)
    summary = dict(edition=args.edition, startedUtc=started, completedUtc=datetime.datetime.now(datetime.timezone.utc).isoformat(), exitCode=result.returncode, gitHead=subprocess.check_output(['git','rev-parse','HEAD'],cwd=root).decode().strip(), sourceSha256=digest.hexdigest(), tests=counts, coverage=counters)
    (output/'结果.json').write_text(json.dumps(summary, ensure_ascii=False, indent=2), encoding='utf-8')
    (output/'结果.txt').write_text(json.dumps(summary, ensure_ascii=False, indent=2), encoding='utf-8')
    print(json.dumps(summary, ensure_ascii=False))
    if result.returncode or counts['failures'] or counts['errors'] or counts['skipped']: sys.exit(1)

if __name__ == '__main__':
    main()
