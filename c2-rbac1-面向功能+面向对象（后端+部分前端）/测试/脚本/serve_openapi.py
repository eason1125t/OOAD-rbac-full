"""文件职责：仅向本机Apifox导入提供当前一份OpenAPI；不提供目录或私密文件。
协作关系：读取C2/api/openapi.yaml，导入结束后终止本次进程，不作为项目运行服务。
"""
from http.server import BaseHTTPRequestHandler, HTTPServer
from pathlib import Path
import argparse

ROOT = Path(__file__).resolve().parents[2]
CONTRACT = ROOT / 'api/openapi.yaml'


class ContractHandler(BaseHTTPRequestHandler):
    def do_GET(self):
        if self.path != '/openapi.yaml':
            self.send_error(404)
            return
        content = CONTRACT.read_bytes()
        self.send_response(200)
        self.send_header('Content-Type', 'application/yaml; charset=utf-8')
        self.send_header('Content-Length', str(len(content)))
        self.send_header('Cache-Control', 'no-store')
        self.end_headers()
        self.wfile.write(content)


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--contract', type=Path, default=CONTRACT, help='主契约或测试结果内已验证的Apifox等价副本')
    args = parser.parse_args()
    CONTRACT = args.contract.resolve()
    assert CONTRACT == ROOT / 'api/openapi.yaml' or CONTRACT.is_relative_to(ROOT / '测试/结果')
    HTTPServer(('127.0.0.1', 18908), ContractHandler).serve_forever()
