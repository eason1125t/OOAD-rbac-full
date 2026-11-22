"""文件职责：按现有JWT代码补充登录生命周期类图与严格轮换时序。
协作关系：复用build_staruml原生工厂及检查；由build_supplementary调用，不维护平行图源。
"""


def lifecycle(u, parent):
    d = u.ClassDiagram(parent, 'oo', '48-shared-JWT登录生命周期-类图',
        'login / refreshLogin / logout / getCurrentSession',
        'LoginAuthentication组织生命周期；签名、在线sid、员工与HTTP Cookie分工。'
        'JwtEmployeeAuthentication通过其LoginVerifier接口核对RedisLoginStore，不从JWT角色声明建立业务权限。')
    d.add('service', 'authentication.LoginAuthentication', 'C',
          [('logins', 'RedisLoginStore'), ('tokens', 'JwtAccessTokens'), ('employees', 'CurrentEmployeeService'),
           ('settings', 'AuthenticationProperties')], [('start', '', ''), ('refresh', '', ''), ('end', '', '')])
    for key, cls, position, operations in [
        ('controller', 'authentication.AuthController', 'L', ['login', 'refresh', 'logout']),
        ('store', 'authentication.RedisLoginStore', 'R', ['create', 'inspect', 'rotate', 'revoke', 'requireActive']),
        ('tokens', 'authentication.JwtAccessTokens', 'T', ['issue', 'decode']),
        ('employees', 'authentication.CurrentEmployeeService', 'B', ['findActive', 'requireActive']),
        ('settings', 'authentication.AuthenticationProperties', 'TR', []),
        ('cookies', 'authentication.AuthCookies', 'BL', ['read', 'write', 'clear']),
        ('accounts', 'authentication.AccountAuthentication', 'TL', ['verify']),
        ('converter', 'authentication.JwtEmployeeAuthentication', 'BR', ['convert']),
    ]:
        d.add(key, cls, position, ops=[(operation, '', '') for operation in operations])
        d.views[key]['showOperationSignature'] = False
    d.views['service']['showOperationSignature'] = False
    for target, role in [('store', 'logins'), ('tokens', 'tokens'), ('employees', 'employees'), ('settings', 'settings')]:
        d.link('service', target, role)
    for target, role in [('service', 'logins'), ('accounts', 'accounts'), ('cookies', 'cookies')]:
        d.link('controller', target, role)
    d.link('converter', 'employees', 'employees')
    d.link('converter', 'tokens')  # 转换器实际调用静态employeeId/isRandomId，不伪造仓储字段关联。
    d.finish()
    d.entry.update(version='shared', requirements='JWT身份、生命周期、即时撤销',
                   testSource='测试/后端/common/java/authentication/JwtLoginHttpTest.java',
                   testMethods=['loginIssuesBearerAndCookieCannotAuthenticateBusiness',
                                'expiredAccessCanRefreshAndLogoutButCannotAccessBusiness'],
                   browserTest='测试/前端/tests/jwt.spec.ts')


def rotation(u, parent):
    from build_supplementary import end_at
    s = u.Sequence(parent, 'oo', '49-shared-JWT刷新与重用撤销-时序图', 'refreshLogin',
        '先核验、读最新员工、准备签名，再Lua原子轮换；使用过的旧摘要重用撤销整次登录。')
    for key, obj, label in [('obj', 'obj', '调用者'), ('http', 'controller', 'AuthController'),
                            ('service', 'logins', 'LoginAuthentication'), ('store', 'store', 'RedisLoginStore'),
                            ('employee', 'employees', 'CurrentEmployeeService'), ('jwt', 'tokens', 'JwtAccessTokens')]:
        s.life(key, obj, label=label)
    s.begin('已通过Cookie CSRF；访问JWT可过期，刷新入口不读取Bearer')
    start = s.y - 25
    s.y += 55
    request = s.call('obj', 'http', 'refresh(request, response)', duration=950)
    service = s.call('http', 'service', 'issued = refresh(cookies.read(request))', duration=800)
    s.call('service', 'store', 'login = inspect(previous)  // 摘要与绝对期限')
    s.call('service', 'employee', 'employee = findActive(login.employeeId())')
    s.call('service', 'jwt', 'access = issue(employeeId, sid, expiresAt)  // 先准备签名')
    s.call('service', 'store', 'rotate(login, previous, next)  // Lua原子核对/消费/替换')
    for call in (request, service):
        end_at(call, s.y + 15)
    s.y += 45
    s.frame(start, s.y, '成功：返回访问JWT；HttpOnly Cookie轮换刷新凭证，绝对8小时不续期', operator='seq')
    s.y += 80
    start = s.y - 25
    s.y += 55
    request = s.call('obj', 'http', 'refresh(request, response)  // 再提交已消费旧凭证', duration=600)
    service = s.call('http', 'service', 'refresh(oldRaw)', duration=450)
    store = s.call('service', 'store', 'inspect(previous)  // Lua确认摘要已使用，删除该sid', duration=250)
    s.call('store', 'obj', 'AuthenticationFailure: REFRESH_TOKEN_REUSED → HTTP 401', reply=True)
    for call in (request, service, store):
        end_at(call, s.y + 15)
    s.y += 45
    s.frame(start, s.y, '该sid的所有访问JWT在下一次在线认证时拒绝；其他独立登录不受影响', operator='seq')
    s.note('第二段是一次独立请求；未捕获异常直接返回调用者，安全过滤链负责HTTP错误映射，不画虚构的逐层catch。\n'
           '并发在inspect后竞争时，rotate仍原子核对，旧摘要重用同样撤销sid；未知secret只拒绝，不能凭sid撤销他人。\n'
           'Redis/数据库故障503不清除Cookie、不放行；签名/员工读取失败不先消费刷新值；刷新网络结果未知不自动重试。\n'
           '浏览器用同版SharedWorker串行协调；随机sid不是tokenVersion。图中省略刷新随机值生成与Cookie封装小步骤。')
    s.finish()
    s.entry.update(version='shared', requirements='JWT严格轮换、重用撤销、故障边界',
                   sources=['backend-oo/src/main/java/authentication/AuthController.java',
                            'backend-oo/src/main/java/authentication/LoginAuthentication.java',
                            'backend-oo/src/main/java/authentication/RedisLoginStore.java',
                            'backend-oo/src/main/resources/authentication/login-state.lua', 'frontend/src/auth.worker.ts'],
                   testSource='测试/后端/common/java/authentication/JwtLoginHttpTest.java',
                   testMethods=['refreshRotatesAndConfirmedReuseRevokesAllAccessForThatLogin',
                                'concurrentRefreshHasOneSuccessAndOneReuseRejection',
                                'redisFailureDuringRefreshDoesNotClearCookieOrPretendLogoutSucceeded'],
                   browserTest='测试/前端/tests/jwt.spec.ts')


def build(u, parent):
    lifecycle(u, parent)
    rotation(u, parent)
