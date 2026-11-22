# 文件职责：以独立Compose项目构建并验收JWT部署；密钥只生成在工作区外，不部署或修改课程集群。
# 协作关系：复用部署manage.py、正式Dockerfile/网关、HTTP冒烟及远程浏览器配置；结束仅停止本次容器。
import argparse
from datetime import datetime, timezone
import importlib.util
import json
import os
from pathlib import Path
import shutil
import socket
import subprocess
import tempfile

ROOT = Path(__file__).resolve().parents[2]


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--output-dir', type=Path, required=True)
    parser.add_argument('--port', type=int, default=18081)
    args = parser.parse_args()
    output = (ROOT / args.output_dir).resolve()
    assert output.is_relative_to((ROOT / '测试/结果').resolve())
    assert not output.exists() or not any(output.iterdir()), 'Choose a new empty evidence directory'
    with socket.socket() as probe:
        assert probe.connect_ex(('127.0.0.1', args.port)) != 0, 'Acceptance port already occupied'
    output.mkdir(parents=True, exist_ok=True)
    spec = importlib.util.spec_from_file_location('jwt_deploy', ROOT / '部署/manage.py')
    deploy = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(deploy)
    stamp = datetime.now(timezone.utc).strftime('%Y%m%d%H%M%S')
    project = 'c2-jwt-check-' + stamp
    settings = deploy.read_settings(ROOT / '部署/.env.example')
    settings.update(RBAC_STACK_NAME=project, RBAC_HTTP_PORT=str(args.port), RBAC_DATA_NODE='local-acceptance',
                    RBAC_MAVEN_IMAGE='public.ecr.aws/docker/library/maven:3.9.11-eclipse-temurin-21',
                    RBAC_JAVA_IMAGE='public.ecr.aws/docker/library/eclipse-temurin:21-jre-noble',
                    RBAC_NODE_IMAGE='public.ecr.aws/docker/library/node:24-alpine',
                    RBAC_NGINX_IMAGE='public.ecr.aws/docker/library/nginx:1.28-alpine')
    metadata = {'startedUtc': datetime.now(timezone.utc).isoformat(), 'project': project,
                'port': args.port, 'scope': 'isolated local Compose; not Swarm', 'exitCode': 1}
    (output / '命令.txt').write_text(
        'python 测试/脚本/run_jwt_deployment.py --output-dir ' + str(args.output_dir)
        + '\n正式Dockerfile构建3镜像；临时工作区外密钥；独立bridge、两库和数据卷。'
        + '\nCompose up --wait；两版--init-demo；smoke.py --exercise；3项外部浏览器；Compose stop。'
        + '\n不push、不启用Swarm、不删除容器/卷、不修改旧服务。\n', encoding='utf-8')
    started = False
    compose = None

    def execute(arguments, name, cwd=ROOT, extra=None, timeout=1200):
        with (output / (name + '.txt')).open('w', encoding='utf-8') as log:
            subprocess.run(arguments, cwd=cwd, env={**os.environ, 'PYTHONIOENCODING': 'utf-8', **settings, **(extra or {})},
                           stdout=log, stderr=subprocess.STDOUT, check=True, timeout=timeout)

    try:
        execute(['python', '测试/部署/test_manage.py', '-v'], '部署脚本测试')
        for edition in ('func', 'oo'):
            image = f'rbac-c2/backend-{edition}:jwt-{stamp}'
            settings['RBAC_' + edition.upper() + '_IMAGE'] = image
            execute(['docker', 'build', '--progress=plain', '-f', '部署/Dockerfile.backend',
                     '--build-arg', 'EDITION=' + edition, '--build-arg', 'MAVEN_IMAGE=' + settings['RBAC_MAVEN_IMAGE'],
                     '--build-arg', 'JAVA_IMAGE=' + settings['RBAC_JAVA_IMAGE'], '-t', image, '.'], edition + '-镜像构建')
        settings['RBAC_FRONTEND_IMAGE'] = 'rbac-c2/frontend:jwt-' + stamp
        execute(['docker', 'build', '--progress=plain', '-f', '部署/Dockerfile.frontend',
                 '--build-arg', 'NODE_IMAGE=' + settings['RBAC_NODE_IMAGE'], '--build-arg', 'NGINX_IMAGE=' + settings['RBAC_NGINX_IMAGE'],
                 '-t', settings['RBAC_FRONTEND_IMAGE'], '.'], '前端镜像构建')
        # 临时私有目录不进入证据或构建上下文；仅公开路径和公钥摘要可归档。
        temporary = Path(tempfile.mkdtemp(prefix='c2-jwt-container-'))
        for filename in ('stack.yaml', 'mysql-init.sh', 'redis-start.sh'):
            shutil.copy2(ROOT / '部署' / filename, temporary / filename)
        deploy.HERE = temporary
        deploy.generate_secrets(temporary / 'secrets')
        deploy.generate_jwt_keys(temporary / 'secrets')
        execute(['docker', 'stack', 'config', '-c', str(temporary / 'stack.yaml')], 'Swarm配置渲染', cwd=temporary)
        deploy.render(settings, local=True)
        compose = ['docker', 'compose', '-p', project, '-f', str(temporary / 'local.json')]
        execute(compose + ['config', '--quiet'], 'Compose配置核对')
        started = True
        execute(compose + ['up', '-d', '--wait', '--wait-timeout', '300'], '容器启动', timeout=360)
        for edition in ('func', 'oo'):
            execute(compose + ['run', '--rm', '-e', 'RBAC_DEMO_ACCOUNTS_ENABLED=true',
                               'backend-' + edition, '--init-demo'], edition + '-演示初始化', timeout=120)
        execute(['python', '测试/部署/smoke.py', '--url', f'http://127.0.0.1:{args.port}',
                 '--exercise', '--output', str(output / 'HTTP冒烟结果.json')], 'HTTP冒烟命令行', timeout=120)
        execute(['npm.cmd' if os.name == 'nt' else 'npm', 'exec', '--', 'playwright', 'test',
                 '--config=playwright.remote.config.ts'], '外部浏览器命令行', cwd=ROOT / '测试/前端',
                extra={'RBAC_DEPLOY_BASE_URL': f'http://127.0.0.1:{args.port}',
                       'RBAC_BROWSER_CHANNEL': 'msedge', 'RBAC_BROWSER_RESULTS_DIR': str(output)}, timeout=240)
        execute(compose + ['ps', '--format', 'json'], '容器状态')
        metadata.update(exitCode=0, images={key: value for key, value in settings.items()
                                          if key in ('RBAC_FUNC_IMAGE', 'RBAC_OO_IMAGE', 'RBAC_FRONTEND_IMAGE')})
    except Exception as failure:
        metadata['failure'] = type(failure).__name__ + ': ' + str(failure)
    finally:
        if started:
            for edition in ('func', 'oo'):
                try:
                    execute(compose + ['logs', '--no-color', 'backend-' + edition], edition + '-服务器日志')
                except Exception:
                    pass  # 保留原失败；即使日志读取故障也继续停止本次容器。
            try:
                execute(compose + ['stop'], '容器停止', timeout=90)
                metadata['temporaryContainersStopped'] = True
            except Exception:
                metadata['temporaryContainersStopped'] = False
        metadata['completedUtc'] = datetime.now(timezone.utc).isoformat()
        (output / '结果.json').write_text(json.dumps(metadata, ensure_ascii=False, indent=2) + '\n', encoding='utf-8')
        print(json.dumps(metadata, ensure_ascii=True))
    raise SystemExit(metadata['exitCode'])


if __name__ == '__main__':
    main()
