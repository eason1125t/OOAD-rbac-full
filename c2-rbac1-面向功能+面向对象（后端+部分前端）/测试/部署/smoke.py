"""从外部真实入口验证网关、两版JWT登录/刷新/撤销与可选申请写入；与部署配置协作，不启动Vite。"""
import argparse
from datetime import datetime, timezone
from http.cookiejar import CookieJar
import json
import os
from pathlib import Path
import re
import urllib.error
import urllib.parse
import urllib.request
import uuid


def main():
    """共用 CookieJar 验证版本隔离，输出仅包含检查名和非敏感申请标识。"""
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--url", required=True, help="站点根地址，不含 /func 或 /oo")
    parser.add_argument("--exercise", action="store_true", help="每版新增、修改并撤回一条合成申请")
    parser.add_argument("--output", type=Path)
    args = parser.parse_args()
    base = args.url.rstrip("/")
    address = urllib.parse.urlsplit(base)
    if address.scheme not in ("http", "https") or not address.netloc or address.path:
        parser.error("--url must be the public site origin without a path")
    cookies = CookieJar()
    opener = urllib.request.build_opener(urllib.request.HTTPCookieProcessor(cookies))
    checks = []
    access_tokens = {}  # 仅当前进程内存；禁止输出到归档。

    def request(path, expected=200, method="GET", body=None, token=None, authenticated=True, bearer=None):
        """按真实 HTTP 状态判断失败，不把 SPA HTML 误认为 API 成功。"""
        headers = {"Accept": "application/json"}
        if token:
            headers["X-CSRF-TOKEN"] = token
        match = re.match(r"/(func|oo)/api/", path)
        cookie_endpoint = bool(re.fullmatch(r"/(func|oo)/api/auth/(csrf|login|refresh|logout)", path))
        if match and not cookie_endpoint and authenticated:
            credential = bearer or access_tokens.get(match[1])
            if credential:
                headers["Authorization"] = "Bearer " + credential
        data = None
        if body is not None:
            headers["Content-Type"] = "application/json"
            data = json.dumps(body).encode()
        call = urllib.request.Request(base + path, data=data, headers=headers, method=method)
        try:
            response = opener.open(call, timeout=30)
        except urllib.error.HTTPError as failure:
            response = failure
        with response:
            allowed = expected if isinstance(expected, tuple) else (expected,)
            assert response.status in allowed, f"{method} {path}: HTTP {response.status}, expected {allowed}"
            text = response.read().decode("utf-8")
            content_type = response.headers.get("Content-Type", "")
        if response.status == 204:
            return None
        if "/api/" in path:
            assert "application/json" in content_type, f"API returned non-JSON: {path}"
            return json.loads(text)
        return text, content_type

    def passed(name, **details):
        """只保留可公开的证据，避免 Cookie、令牌、密码进入归档。"""
        checks.append({"check": name, "status": "PASS", **details})
        print(f"PASS {name}", flush=True)

    home, unused = request("/")
    for path in ("/func/", "/oo/", "/func/demo/deep", "/oo/demo/deep"):
        html, content_type = request(path)
        assert html == home and "text/html" in content_type, f"SPA fallback failed: {path}"
    asset = re.search(r'<script[^>]+src="([^"]+)"', home)
    assert asset, "Built frontend script is missing"
    unused, content_type = request(asset.group(1))
    assert "javascript" in content_type, "Frontend asset returned HTML"
    passed("C2-DEPLOY static routes, deep links and JavaScript")

    created = {}
    run_id = uuid.uuid4().hex[:12]
    for edition in ("func", "oo"):
        prefix = f"/{edition}/api"
        request(prefix + "/auth/me", expected=401)
        token = request(prefix + "/auth/csrf")["token"]
        login = request(prefix + "/auth/login", method="POST", token=token, body={
            "username": os.environ.get("RBAC_SMOKE_USERNAME", "super-admin"),
            "password": os.environ.get("RBAC_SMOKE_PASSWORD", "1234")})
        assert login["employee"]["username"] == os.environ.get("RBAC_SMOKE_USERNAME", "super-admin")
        assert login["tokenType"] == "Bearer" and 0 < login["expiresIn"] <= 900
        assert "refreshToken" not in login
        access_tokens[edition] = login["accessToken"]
        request(prefix + "/auth/me")
        request(prefix + "/auth/me", expected=401, authenticated=False)
        cookie_name = f"RBAC1_{edition.upper()}_REFRESH"
        refresh_cookie = next(cookie for cookie in cookies if cookie.name == cookie_name)
        assert refresh_cookie.path == f"/{edition}/api/auth/" and refresh_cookie.has_nonstandard_attr("HttpOnly")
        assert refresh_cookie.get_nonstandard_attr("SameSite").lower() == "strict"
        if address.scheme == "https":
            assert refresh_cookie.secure, "HTTPS requires Secure authentication cookies"
        previous = refresh_cookie.value
        csrf = request(prefix + "/auth/csrf")["token"]
        refreshed = request(prefix + "/auth/refresh", method="POST", token=csrf)
        assert set(refreshed) == {"accessToken", "tokenType", "expiresIn"}
        assert refreshed["tokenType"] == "Bearer" and 0 < refreshed["expiresIn"] <= 900
        current = next(cookie.value for cookie in cookies if cookie.name == cookie_name)
        assert current != previous and current.split(".")[0] == previous.split(".")[0]
        access_tokens[edition] = refreshed["accessToken"]
        request(prefix + "/auth/me")
        request(prefix + "/not-an-api", expected=403)
        passed(f"C2-08 {edition} Bearer login, refresh rotation, Cookie boundary and API routing")
        systems = request(prefix + "/business-systems")
        assert systems, "No business systems; initialize demonstration data first"
        code = urllib.parse.quote(systems[0]["code"], safe="")
        collection = prefix + f"/business-systems/{code}/requests"
        request(collection + "?page=1&pageSize=1")
        if args.exercise:
            title = f"C2-deploy-{run_id}-{edition}"
            row = request(collection, expected=201, method="POST", body={"title": title})
            assert row["status"] == "PENDING"
            identifier = row["id"]
            item = prefix + f"/requests/{identifier}"
            row = request(item, method="PATCH", body={"title": title + "-updated"})
            assert row["title"] == title + "-updated"
            row = request(item + "/withdraw", method="POST")
            assert row["status"] == "WITHDRAWN"
            denied = request(item + "/withdraw", expected=409, method="POST")
            assert denied["code"] == "REQUEST_STATE_CONFLICT"
            created[edition] = (identifier, title + "-updated")
            passed(f"C2-11/12 {edition} create, update, withdraw and reject terminal transition", requestId=identifier)

    if args.exercise:
        for edition, foreign in (("func", "oo"), ("oo", "func")):
            other_id, other_title = created[foreign]
            row = request(f"/{edition}/api/requests/{other_id}", expected=(200, 404))
            assert row.get("title") != other_title, "Two editions share business data"
        passed("C2-08 independent database writes")
    request("/oo/api/auth/me", expected=401, bearer=access_tokens["func"])
    token = request("/func/api/auth/csrf")["token"]
    request("/func/api/auth/logout", method="POST", token=token, expected=204)
    request("/func/api/auth/me", expected=401)
    request("/oo/api/auth/me")
    passed("C2-08 foreign JWT rejected; func logout revokes old Bearer and preserves oo login")
    token = request("/oo/api/auth/csrf")["token"]
    request("/oo/api/auth/logout", method="POST", token=token, expected=204)
    request("/oo/api/auth/me", expected=401)
    if args.output:
        args.output.parent.mkdir(parents=True, exist_ok=True)
        args.output.write_text(json.dumps({"executed_utc": datetime.now(timezone.utc).isoformat(),
            "base_url": base, "exercise": args.exercise, "checks": checks}, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(f"PASS: {len(checks)} deployment check groups; not a replacement for the full regression suite")


if __name__ == "__main__":
    main()
