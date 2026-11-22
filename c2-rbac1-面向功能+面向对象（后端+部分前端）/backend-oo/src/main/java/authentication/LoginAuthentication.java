// 文件职责：组织登录创建、刷新轮换和当前登录撤销，身份校验与 HTTP Cookie 分开。
// 协作关系：使用 RedisLoginStore、JwtAccessTokens、CurrentEmployeeService；不保存用户版本或权限快照。
package authentication;

import authentication.vo.AccessTokenVO;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.stereotype.Service;

@Service
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
public class LoginAuthentication {
    private final RedisLoginStore logins;
    private final JwtAccessTokens tokens;
    private final CurrentEmployeeService employees;
    private final AuthenticationProperties settings;
    private final Clock clock;

    public LoginAuthentication(RedisLoginStore logins, JwtAccessTokens tokens, CurrentEmployeeService employees,
                               AuthenticationProperties settings, Clock clock) {
        this.logins = logins;
        this.tokens = tokens;
        this.employees = employees;
        this.settings = settings;
        this.clock = clock;
    }

    /** 原始刷新值仅在服务和 HTTP Cookie 之间传递；禁止 record 默认输出凭证。 */
    public record Issued(AccessTokenVO access, String refresh, Instant expiresAt) {
        @Override public String toString() { return "Issued[redacted]"; }
    }

    /** Controller 先验证密码并形成工作区；只有此方法整体成功才发布新凭证。 */
    public Issued start(Long employeeId, String previousRaw) {
        employees.requireActive(employeeId);
        Instant created = clock.instant().truncatedTo(ChronoUnit.SECONDS);
        String sid = UUID.randomUUID().toString();
        RedisLoginStore.Login login = new RedisLoginStore.Login(sid, employeeId, created, created.plus(settings.loginTtl()));
        RefreshCredential credential = RefreshCredential.create(sid);
        AccessTokenVO access = tokens.issue(employeeId, sid, login.expiresAt());
        logins.create(login, credential);
        try {
            // 替换当前浏览器登录，不能按 employeeId 撤销其他浏览器。
            RefreshCredential.parse(previousRaw).ifPresent(logins::revoke);
        } catch (RuntimeException failure) {
            try { logins.revoke(credential); } catch (RuntimeException cleanup) { failure.addSuppressed(cleanup); }
            throw failure;
        }
        return new Issued(access, credential.value(), login.expiresAt());
    }

    /** 先证明持有当前刷新凭证并检查员工，签名准备成功后才原子提交轮换。 */
    public Issued refresh(String raw) {
        RefreshCredential previous = RefreshCredential.parse(raw).orElseThrow(() ->
                new AuthenticationFailure(AuthenticationFailure.Reason.REFRESH_TOKEN_INVALID));
        RedisLoginStore.Login login = logins.inspect(previous);
        if (employees.findActive(login.employeeId()).isEmpty()) {
            logins.revoke(previous);
            throw new AuthenticationFailure(AuthenticationFailure.Reason.REFRESH_TOKEN_INVALID);
        }
        RefreshCredential next = RefreshCredential.create(login.sid());
        AccessTokenVO access;
        try {
            access = tokens.issue(login.employeeId(), login.sid(), login.expiresAt());
        } catch (IllegalArgumentException failure) {
            // 身份读取/签名准备期间可能刚过绝对期限，不能将正常到期误报为500。
            if (!login.expiresAt().isAfter(clock.instant().truncatedTo(ChronoUnit.SECONDS))) {
                logins.revoke(previous);
                throw new AuthenticationFailure(AuthenticationFailure.Reason.REFRESH_TOKEN_INVALID);
            }
            throw failure;
        }
        logins.rotate(login, previous, next);
        return new Issued(access, next.value(), login.expiresAt());
    }

    public void end(String raw) {
        RefreshCredential.parse(raw).ifPresent(logins::revoke);
    }
}
