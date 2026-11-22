"""文件职责：只读验收切换后的两版JWT登录、刷新、目录及隔离，不写申请/角色/组织数据。
协作关系：连接现有本机服务；凭证仅进程内存，旧Session探针可从用户私有目录读取。
"""
import argparse
from datetime import datetime, timezone
from http.cookiejar import CookieJar
from http.cookies import SimpleCookie
import json
import os
from pathlib import Path
import urllib.error
import urllib.request

ROOT = Path(__file__).resolve().parents[2]


class Actor:
    def __init__(self, base, edition, direct=False):
        self.base, self.edition, self.direct = base.rstrip('/'), edition, direct
        self.jar = CookieJar()
        self.cookies = {}
        self.opener = urllib.request.build_opener(urllib.request.HTTPCookieProcessor(self.jar))
        self.access = None

    def request(self, path, status=200, method='GET', body=None, bearer=True, csrf=None, cookie=None):
        headers = {'Accept': 'application/json'}
        if self.access and bearer:
            headers['Authorization'] = 'Bearer ' + self.access
        if csrf:
            headers['X-CSRF-TOKEN'] = csrf
        if cookie is not None:
            headers['Cookie'] = cookie
        elif self.direct:
            # 备用端口直连测试显式带Cookie；生产配置仍使用网关公开Path，绝不改为根路径。
            headers['Cookie'] = '; '.join(name + '=' + value for name, value in self.cookies.items())
        data = None
        if body is not None:
            headers['Content-Type'] = 'application/json'
            data = json.dumps(body).encode('utf-8')
        request = urllib.request.Request(self.base + path, data=data, method=method, headers=headers)
        try:
            response = self.opener.open(request, timeout=15)
        except urllib.error.HTTPError as failure:
            response = failure
        with response:
            assert response.status == status, f'{self.edition}: {method} {path} HTTP {response.status}, expected {status}'
            for line in response.headers.get_all('Set-Cookie', []):
                parsed = SimpleCookie(); parsed.load(line)
                for name, item in parsed.items():
                    if item['max-age'] == '0':
                        self.cookies.pop(name, None)
                    else:
                        self.cookies[name] = item.value
            assert 'no-store' in response.headers.get('Cache-Control', '') or '/auth/' not in path
            return None if status == 204 else json.loads(response.read())

    def cookie(self, name):
        return self.cookies[name] if self.direct else next(cookie.value for cookie in self.jar if cookie.name == name)

    def login(self, username, password):
        csrf = self.request('/api/auth/csrf', bearer=False)['token']
        result = self.request('/api/auth/login', method='POST', body={'username': username, 'password': password},
                              csrf=csrf, bearer=False)
        assert set(result) == {'employee', 'workspaces', 'defaultWorkspaceKey', 'accessToken', 'tokenType', 'expiresIn'}
        assert result['tokenType'] == 'Bearer' and 0 < result['expiresIn'] <= 900
        self.access = result['accessToken']
        return result

    def logout(self):
        csrf = self.request('/api/auth/csrf', bearer=False)['token']
        self.request('/api/auth/logout', status=204, method='POST', csrf=csrf, bearer=False)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--url', default='http://127.0.0.1:5173')
    parser.add_argument('--direct', action='store_true', help='备用28181/28182直连预检，Cookie仅测试客户端显式发送')
    parser.add_argument('--output', type=Path, required=True)
    args = parser.parse_args()
    output = (ROOT / args.output).resolve()
    assert output.is_relative_to((ROOT / '测试/结果').resolve())
    assert not output.exists(), 'Choose a new evidence filename'
    output.parent.mkdir(parents=True, exist_ok=True)
    actors = {edition: Actor(f'http://127.0.0.1:{28181 if edition == "func" else 28182}' if args.direct
                             else args.url.rstrip('/') + '/' + edition, edition, args.direct) for edition in ('func', 'oo')}
    checks = []
    try:
        for edition, actor in actors.items():
            actor.request('/api/auth/me', status=401)
            identity = actor.login(os.environ.get('RBAC_SWITCH_USERNAME', 'super-admin'), os.environ.get('RBAC_SWITCH_PASSWORD', '1234'))
            name = 'RBAC1_' + edition.upper() + '_REFRESH'
            previous = actor.cookie(name)
            actor.request('/api/auth/me', status=401, bearer=False)
            assert 'authorization' not in actor.request('/api/auth/me')
            detailed = actor.request('/api/auth/me?systemCode=demo-engineering-app')
            assert detailed['authorization']['systemCode'] == 'demo-engineering-app'
            assert isinstance(actor.request('/api/auth/me/permission-check?systemCode=demo-engineering-app&permissionCode=request:read')['allowed'], bool)
            assert isinstance(actor.request('/api/auth/me/api-check?systemCode=demo-engineering-app&operationId=listSystemRequests')['allowed'], bool)
            actor.request('/api/auth/me/api-check?systemCode=demo-engineering-app&operationId=jwt-switch-unknown', status=404)
            assert actor.request('/api/departments?view=tree&rootsOnly=true&pageSize=1')['items']
            assert actor.request('/api/employees?pageSize=1')['items']
            actor.request('/api/employees/' + str(identity['employee']['id']))
            csrf = actor.request('/api/auth/csrf', bearer=False)['token']
            refreshed = actor.request('/api/auth/refresh', method='POST', csrf=csrf, bearer=False)
            assert set(refreshed) == {'accessToken', 'tokenType', 'expiresIn'}
            current = actor.cookie(name)
            assert current != previous and current.split('.')[0] == previous.split('.')[0]
            actor.access = refreshed['accessToken']
            actor.request('/api/auth/me')
            legacy_rejected = None  # 备用直连没有旧Session探针，不能记成已验证。
            if not args.direct:
                legacy = Path(os.environ['LOCALAPPDATA']) / 'OOAD-RBAC/c2-jwt/legacy-cookie-probe.json'
                if legacy.exists():
                    old_cookie = json.loads(legacy.read_text(encoding='utf-8'))[edition]
                    actor.request('/api/auth/me', status=401, bearer=False, cookie=old_cookie)
                    legacy_rejected = True
            checks.append({'edition': edition, 'identityAndEightCapabilities': 'PASS', 'cookieCannotAuthenticateBusiness': True,
                           'refreshRotatedWithoutChangingSid': True, 'legacySessionRejected': legacy_rejected})
        own = actors['oo'].access
        actors['oo'].access = actors['func'].access
        actors['oo'].request('/api/auth/me', status=401)
        actors['oo'].access = own
        actors['func'].logout()
        actors['func'].request('/api/auth/me', status=401)
        actors['oo'].request('/api/auth/me')
        checks.append({'crossEditionJwtRejected': True, 'funcLogoutPreservesOo': True, 'logoutRevokesAccessImmediately': True})
        result = {'status': 'passed', 'executedUtc': datetime.now(timezone.utc).isoformat(), 'mode': 'direct-preflight' if args.direct else 'current-gateway',
                  'checks': checks, 'businessWrites': 0, 'note': 'Real existing local databases; login lifecycle modifies only Redis records'}
        output.write_text(json.dumps(result, ensure_ascii=False, indent=2) + '\n', encoding='utf-8')
        print(json.dumps(result, ensure_ascii=True))
    finally:
        for actor in actors.values():
            if actor.access:
                try: actor.logout()
                except Exception: pass


if __name__ == '__main__':
    main()
