"""文件职责：从唯一主OpenAPI生成有效security显式展开的Apifox导入副本，并逐操作核对等价。
协作关系：供客户端导入或serve_openapi.py本机提供；只写测试结果，不发送网络请求或改变主契约。
"""
import argparse
import copy
import hashlib
import json
from pathlib import Path

import yaml

ROOT = Path(__file__).resolve().parents[2]
METHODS = {'get', 'post', 'put', 'patch', 'delete', 'head', 'options', 'trace'}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--output-dir', type=Path, required=True)
    args = parser.parse_args()
    output = (ROOT / args.output_dir).resolve()
    assert output.is_relative_to((ROOT / '测试/结果').resolve())
    master = ROOT / 'api/openapi.yaml'
    original = yaml.safe_load(master.read_text(encoding='utf-8'))
    converted = copy.deepcopy(original)
    expanded = operations = 0
    for path, block in converted['paths'].items():
        for method, operation in block.items():
            if method not in METHODS:
                continue
            operations += 1
            if 'security' not in operation:
                operation['security'] = copy.deepcopy(original.get('security', []))
                expanded += 1
            before = copy.deepcopy(original['paths'][path][method])
            after = copy.deepcopy(operation)
            assert after.pop('security') == before.pop('security', original.get('security', []))
            assert after == before, 'Import copy changed operation behavior'
    output.mkdir(parents=True, exist_ok=True)
    target = output / 'Apifox导入.openapi.json'
    if target.exists():
        # 保留已实际导入文件的字节摘要；主契约已变化时必须换新批次，不能悄悄覆盖。
        assert json.loads(target.read_text(encoding='utf-8')) == converted, 'Choose a new batch for changed contract'
    else:
        target.write_text(json.dumps(converted, ensure_ascii=False, indent=2) + '\n', encoding='utf-8', newline='\n')
    result = {'status': 'passed', 'source': 'api/openapi.yaml',
              'sourceSha256': hashlib.sha256(master.read_bytes()).hexdigest(),
              'importSha256': hashlib.sha256(target.read_bytes()).hexdigest(),
              'explicitInheritedOperations': expanded, 'operations': operations,
              'scope': 'Only materialize inherited effective security; no operation behavior or root auth changes'}
    (output / '导入副本等价核对.json').write_text(json.dumps(result, ensure_ascii=False, indent=2) + '\n',
                                             encoding='utf-8', newline='\n')
    print(json.dumps(result, ensure_ascii=True))


if __name__ == '__main__':
    main()
