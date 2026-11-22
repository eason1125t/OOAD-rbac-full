// 文件职责：原子维护独立 sid 登录记录、当前刷新摘要和已消费摘要，不缓存角色或用户版本。
// 协作关系：由 LoginAuthentication 组织生命周期，并为 JwtEmployeeAuthentication 提供真实 LoginVerifier。
package authentication;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;

/** 所有状态转换共用一项 Lua 脚本；两个 key 使用同一 sid hash tag。 */
@Component
public class RedisLoginStore implements JwtEmployeeAuthentication.LoginVerifier {
    private static final DefaultRedisScript<List> SCRIPT = script();
    private final StringRedisTemplate redis;
    private final AuthenticationProperties settings;
    private final Clock clock;

    public RedisLoginStore(StringRedisTemplate redis, AuthenticationProperties settings, Clock clock) {
        this.redis = redis;
        this.settings = settings;
        this.clock = clock;
    }

    /** 状态只包含身份归属和绝对期限，不包含原始刷新凭证。 */
    public record Login(String sid, Long employeeId, Instant createdAt, Instant expiresAt) { }

    public void create(Login login, RefreshCredential credential) {
        result("CREATE", credential.sid(), login.employeeId(), login.createdAt(), login.expiresAt(), credential.digest(), "");
    }

    /** 当前凭证可读取元数据；已消费凭证的重用在同一原子操作中撤销登录。 */
    public Login inspect(RefreshCredential credential) {
        List<?> values = result("INSPECT", credential.sid(), null, null, null, credential.digest(), "");
        return login(credential.sid(), values);
    }

    /** 再次核对当前摘要和归属，原子消费旧值并保存新值；绝不延长原到期时间。 */
    public void rotate(Login login, RefreshCredential previous, RefreshCredential next) {
        result("ROTATE", previous.sid(), login.employeeId(), null, null, previous.digest(), next.digest());
    }

    /** 无效或未知凭证幂等返回；只持有 sid 不能撤销他人的登录。 */
    public void revoke(RefreshCredential credential) {
        execute("REVOKE", credential.sid(), null, null, null, credential.digest(), "");
    }

    @Override
    public void requireActive(String sid, Long employeeId) {
        if (!JwtAccessTokens.isRandomId(sid) || employeeId == null || employeeId <= 0) {
            throw new AuthenticationFailure(AuthenticationFailure.Reason.AUTHENTICATION_REQUIRED);
        }
        List<?> values = execute("CHECK", sid, employeeId, null, null, "", "");
        if (!"OK".equals(values.getFirst())) {
            throw new AuthenticationFailure(AuthenticationFailure.Reason.AUTHENTICATION_REQUIRED);
        }
    }

    private List<?> result(String action, String sid, Long employeeId, Instant createdAt, Instant expiresAt,
                           String digest, String nextDigest) {
        List<?> values = execute(action, sid, employeeId, createdAt, expiresAt, digest, nextDigest);
        if ("REUSED".equals(values.getFirst())) {
            throw new AuthenticationFailure(AuthenticationFailure.Reason.REFRESH_TOKEN_REUSED);
        }
        if (!"OK".equals(values.getFirst())) {
            throw new AuthenticationFailure(AuthenticationFailure.Reason.REFRESH_TOKEN_INVALID);
        }
        return values;
    }

    /** 仅供已验签且已核对归属的身份转换使用；保持原Session观察到失效后不能复活的语义。 */
    @Override
    public void rejectEmployee(String sid, Long employeeId) {
        if (!JwtAccessTokens.isRandomId(sid) || employeeId == null || employeeId <= 0) {
            throw new AuthenticationFailure(AuthenticationFailure.Reason.AUTHENTICATION_REQUIRED);
        }
        execute("REJECT_EMPLOYEE", sid, employeeId, null, null, "", "");
    }

    /** Redis 异常继续传播；损坏/不确定结果单列为认证存储不可用，不能按无记录放行。 */
    private List<?> execute(String action, String sid, Long employeeId, Instant createdAt, Instant expiresAt,
                            String digest, String nextDigest) {
        List<?> values = redis.execute(SCRIPT, List.of(key(sid), key(sid) + ":used"), action,
                Long.toString(clock.millis()), employeeId == null ? "" : employeeId.toString(),
                createdAt == null ? "" : Long.toString(createdAt.toEpochMilli()),
                expiresAt == null ? "" : Long.toString(expiresAt.toEpochMilli()), digest, nextDigest);
        if (values == null || values.isEmpty() || !List.of("OK", "INVALID", "REUSED").contains(values.getFirst())) {
            throw new AuthenticationStoreFailure.InvalidState();
        }
        return values;
    }

    private Login login(String sid, List<?> values) {
        try {
            if (values.size() != 4) throw new IllegalArgumentException();
            long employeeId = Long.parseLong(values.get(1).toString());
            Instant created = Instant.ofEpochMilli(Long.parseLong(values.get(2).toString()));
            Instant expires = Instant.ofEpochMilli(Long.parseLong(values.get(3).toString()));
            if (employeeId <= 0 || !expires.isAfter(created)) throw new IllegalArgumentException();
            return new Login(sid, employeeId, created, expires);
        } catch (RuntimeException failure) {
            throw new AuthenticationStoreFailure.InvalidState();
        }
    }

    String key(String sid) { return settings.namespace() + ":{" + sid + "}"; }

    /** 通过资源位置加载脚本，由Spring处理首次执行/脚本缓存失效后的重载。 */
    private static DefaultRedisScript<List> script() {
        DefaultRedisScript<List> script = new DefaultRedisScript<>();
        script.setLocation(new ClassPathResource("authentication/login-state.lua"));
        script.setResultType(List.class);
        return script;
    }
}
