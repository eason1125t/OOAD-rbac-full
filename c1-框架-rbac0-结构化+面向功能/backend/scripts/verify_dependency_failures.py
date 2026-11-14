"""Verify C1 approval fail-closed behavior against isolated MySQL and Redis."""

import argparse
import contextlib
import http.cookiejar
import json
import os
from pathlib import Path
import socket
import socketserver
import subprocess
import threading
import time
import urllib.error
import urllib.request
import uuid


class ProxyHandler(socketserver.BaseRequestHandler):
    """Relay traffic until the test cuts the dependency connection."""

    def handle(self):
        with self.server.lock:
            if not self.server.enabled:
                return
            upstream = socket.create_connection(("127.0.0.1", self.server.upstream_port), timeout=2)
            upstream.settimeout(None)
            pair = (self.request, upstream)
            self.server.connections.add(pair)
        try:
            reverse = threading.Thread(target=self.relay, args=(upstream, self.request), daemon=True)
            reverse.start()
            self.relay(self.request, upstream)
            self.server.close_pair(pair)
            reverse.join()
        finally:
            with self.server.lock:
                self.server.connections.discard(pair)
            upstream.close()

    @staticmethod
    def relay(source, destination):
        try:
            while data := source.recv(65536):
                destination.sendall(data)
        except OSError:
            pass
        finally:
            with contextlib.suppress(OSError):
                destination.shutdown(socket.SHUT_WR)


class DependencyProxy(socketserver.ThreadingTCPServer):
    """Break existing connections and reject reconnects without stopping Docker services."""

    daemon_threads = True

    def __init__(self, upstream_port):
        super().__init__(("127.0.0.1", 0), ProxyHandler)
        self.upstream_port = upstream_port
        self.enabled = True
        self.lock = threading.Lock()
        self.connections = set()

    @staticmethod
    def close_pair(pair):
        for connection in pair:
            with contextlib.suppress(OSError):
                connection.shutdown(socket.SHUT_RDWR)

    @contextlib.contextmanager
    def unavailable(self):
        with self.lock:
            self.enabled = False
            for pair in self.connections:
                self.close_pair(pair)
        try:
            yield
        finally:
            with self.lock:
                self.enabled = True

    @contextlib.contextmanager
    def serving(self):
        with self:
            server = threading.Thread(target=self.serve_forever, daemon=True)
            server.start()
            try:
                yield self
            finally:
                self.shutdown()
                server.join()


def request(client, base, method, path, token=None, body=None):
    headers = {}
    if token:
        headers["X-CSRF-TOKEN"] = token
    data = None
    if body is not None:
        headers["Content-Type"] = "application/json"
        data = json.dumps(body).encode()
    try:
        response = client.open(urllib.request.Request(base + path, data, headers, method=method), timeout=12)
    except urllib.error.HTTPError as error:
        response = error
    with response:
        payload = response.read()
        return response.status, json.loads(payload) if payload else None


def wait_for_server(process, base):
    deadline = time.monotonic() + 35
    client = urllib.request.build_opener()
    while time.monotonic() < deadline and process.poll() is None:
        try:
            if request(client, base, "GET", "/api/auth/csrf")[0] == 200:
                return
        except urllib.error.URLError:
            time.sleep(0.25)
    raise RuntimeError("Backend did not become ready. Check target/dependency-failures.log.")


def wait_for_listing(client, base, listing):
    deadline = time.monotonic() + 15
    while True:
        status, body = request(client, base, "GET", listing)
        if status == 200:
            return body
        assert status == 503 and time.monotonic() < deadline, (status, body)
        time.sleep(0.25)


def verify(base, mysql, redis):
    client = urllib.request.build_opener(urllib.request.HTTPCookieProcessor(http.cookiejar.CookieJar()))
    status, body = request(client, base, "GET", "/api/auth/csrf")
    assert status == 200
    status, _ = request(client, base, "POST", "/api/auth/login", body={
        "username": "engineering-employee", "password": os.environ["RBAC_DEMO_PASSWORD"]
    }, token=body["token"])
    assert status == 200
    status, body = request(client, base, "GET", "/api/auth/csrf")
    assert status == 200
    csrf = body["token"]
    listing = "/api/business-systems/demo-engineering-app/requests"
    status, body = request(client, base, "GET", listing)
    assert status == 200
    ids = [item["id"] for item in body["items"] if item["status"] == "PENDING"][:2]
    assert len(ids) == 2, "The isolated database needs two pending demo requests."
    for dependency, expected, request_id in zip((mysql, redis),
            ("AUTHORIZATION_UNAVAILABLE", "SESSION_UNAVAILABLE"), ids):
        with dependency.unavailable():
            status, body = request(client, base, "POST", f"/api/requests/{request_id}/approve", token=csrf)
        assert status == 503 and body["code"] == expected, (status, body)
        body = wait_for_listing(client, base, listing)
        assert any(item["id"] == request_id and item["status"] == "PENDING" for item in body["items"]), body
        print(f"{expected}: 503, request {request_id} remained PENDING")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--mysql-port", type=int, required=True)
    parser.add_argument("--redis-port", type=int, required=True)
    parser.add_argument("--http-port", type=int, default=38081)
    args = parser.parse_args()
    backend = Path(__file__).resolve().parents[1]
    jar = backend / "target/rbac0-0.1.0-SNAPSHOT.jar"
    java = ["java", "-jar", str(jar)]
    with DependencyProxy(args.mysql_port).serving() as mysql, DependencyProxy(args.redis_port).serving() as redis:
        environment = dict(os.environ, RBAC_DB_URL=f"jdbc:mysql://127.0.0.1:{mysql.server_address[1]}/ooad_rbac0_fault",
                           RBAC_REDIS_HOST="127.0.0.1", RBAC_REDIS_PORT=str(redis.server_address[1]),
                           RBAC_SESSION_NAMESPACE=f"rbac0:fault:{uuid.uuid4()}",
                           RBAC_SESSION_SECURE="false", RBAC_DEMO_ACCOUNTS_ENABLED="true",
                           SPRING_DATASOURCE_HIKARI_CONNECTION_TIMEOUT="2000",
                           SPRING_DATA_REDIS_TIMEOUT="2s")
        with (backend / "target/dependency-failures.log").open("w", encoding="utf8") as output:
            subprocess.run(java + ["--init-demo"], env=environment, stdout=output, stderr=subprocess.STDOUT,
                           check=True, timeout=40)
            with subprocess.Popen(java + [f"--server.port={args.http_port}", "--server.address=127.0.0.1"],
                                  env=environment, stdout=output, stderr=subprocess.STDOUT) as process:
                base = f"http://127.0.0.1:{args.http_port}"
                try:
                    wait_for_server(process, base)
                    verify(base, mysql, redis)
                finally:
                    process.terminate()


if __name__ == "__main__":
    main()
