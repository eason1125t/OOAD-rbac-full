# 文件职责：只读核对JWT交付的源码摘要、JAR资源、OpenAPI及当前说明链接。
# 协作关系：沿用run_backend.py摘要规则；不把静态核对当作HTTP、浏览器或集群验收。
import argparse
import hashlib
import json
from pathlib import Path
import re
import zipfile

import yaml

ROOT = Path(__file__).resolve().parents[2]


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--evidence-dir', type=Path, required=True)
    parser.add_argument('--output-dir', type=Path, help='另存本轮静态核对，保留引用的后端验收批次')
    args = parser.parse_args()
    evidence = (ROOT / args.evidence_dir).resolve()
    assert evidence.is_relative_to((ROOT / '测试/结果').resolve())
    output = (ROOT / args.output_dir).resolve() if args.output_dir else evidence
    assert output.is_relative_to((ROOT / '测试/结果').resolve())
    output.mkdir(parents=True, exist_ok=True)
    results = {}
    for edition, count in (('func', 99), ('oo', 106)):
        backend = ROOT / ('backend-' + edition)
        files = list((backend / 'src/main/java').rglob('*.java'))
        assert len(files) == count
        index = (backend / 'docs/源码索引.md').read_text(encoding='utf-8')
        assert index.count('| [src/main/java/') == count
        digest = hashlib.sha256()
        for file in sorted(list((backend / 'src/main').rglob('*')) + list((ROOT / '测试/后端').rglob('*.java'))):
            if file.is_file() and file.suffix in {'.java', '.sql', '.yaml', '.yml', '.xml', '.lua'}:
                digest.update(file.read_bytes())
        digest.update((backend / 'pom.xml').read_bytes())
        tested = json.loads((evidence / ('后端-' + edition) / '结果.json').read_text(encoding='utf-8'))
        assert digest.hexdigest() == tested['sourceSha256'], 'Source changed after backend acceptance'
        jar = backend / f'target/jwt-final/rbac1-{edition}-0.1.0-SNAPSHOT.jar'
        with zipfile.ZipFile(jar) as archive:
            names = archive.namelist()
            assert not any('SessionAuthentication.class' in name or '/spring-session-' in name for name in names)
            assert not any(name.lower().endswith(('.pem', '.key', '.p12', '.pfx')) for name in names)
            assert archive.read('BOOT-INF/classes/authentication/login-state.lua') == (backend / 'src/main/resources/authentication/login-state.lua').read_bytes()
        results[edition] = {'productionJavaFiles': count, 'sourceSha256': digest.hexdigest(),
                            'jarSha256': hashlib.sha256(jar.read_bytes()).hexdigest(),
                            'luaMatchesSource': True, 'retiredSessionAbsent': True, 'keyFilesAbsent': True}
    contract = yaml.safe_load((ROOT / 'api/openapi.yaml').read_text(encoding='utf-8'))
    operations = sum(method in {'get', 'post', 'put', 'patch', 'delete', 'head', 'options'}
                     for path in contract['paths'].values() for method in path)
    assert contract['info']['version'] == '2.5.0' and operations == 49
    docs = [ROOT / name for name in ('README.md', 'frontend/README.md', '部署/README.md',
            'docs/项目总结.md', 'docs/design/README.md', 'docs/design/图件索引.md',
            'docs/design/图件复核记录.md', 'docs/design/绘图skill核对记录.md', '测试/README.md')]
    for edition in ('func', 'oo'):
        docs.extend((ROOT / f'backend-{edition}').rglob('README.md'))
        docs.extend((ROOT / f'backend-{edition}/docs').glob('*.md'))
    # 生成目录中的README不是项目说明，不跨入缓存或外部依赖。
    docs = [file for file in docs if not {'target', 'node_modules'}.intersection(file.relative_to(ROOT).parts)]
    links = 0
    for file in docs:
        content = file.read_text(encoding='utf-8')
        assert '\ufffd' not in content, file
        for link in re.findall(r'\]\(([^)\n]+)\)', content):
            if link.startswith(('http:', 'https:', '#', 'mailto:')):
                continue
            assert (file.parent / link.split('#')[0]).exists(), f'Broken link: {file.relative_to(ROOT)} -> {link}'
            links += 1
    for name in ('backend-entrypoint.sh', 'mysql-init.sh', 'redis-start.sh', 'mysql-backup.sh'):
        assert b'\r' not in (ROOT / '部署' / name).read_bytes(), 'Linux entrypoint requires LF'
    results.update(status='passed', openApiVersion='2.5.0', operations=operations,
                   schemas=len(contract['components']['schemas']), checkedDocuments=len(docs), localLinks=links,
                   shellLineEndings='LF', scope='static artifact validation only')
    (output / '交付静态核对.json').write_text(json.dumps(results, ensure_ascii=False, indent=2) + '\n', encoding='utf-8')
    print(json.dumps(results, ensure_ascii=True))


if __name__ == '__main__':
    main()
