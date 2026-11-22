// 文件职责：用真实Redis验证登录状态、绝对期限及并发轮换/退出，不依赖模拟脚本结果。
// 协作关系：两版运行同一组测试；仅操作随机测试sid，不清空Redis或枚举删除他人数据。
package authentication;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@EnabledIfEnvironmentVariable(named = "RBAC_AUTH_NAMESPACE", matches = "rbac1:(func|oo):jwt-test:.*")
class RedisLoginStoreTest {
    private static LettuceConnectionFactory connections;
    private static StringRedisTemplate redis;
    private final AtomicReference<Instant> now = new AtomicReference<>(Instant.now());
    private final List<String> owned = new ArrayList<>();
    private RedisLoginStore store;
    private AuthenticationProperties properties;

    @BeforeAll
    static void connect() {
        RedisStandaloneConfiguration config = new RedisStandaloneConfiguration(System.getenv("RBAC_REDIS_HOST"),
                Integer.parseInt(System.getenv("RBAC_REDIS_PORT")));
        String password = System.getenv("RBAC_REDIS_PASSWORD");
        if (password != null && !password.isBlank()) config.setPassword(password);
        connections = new LettuceConnectionFactory(config);
        connections.afterPropertiesSet();
        redis = new StringRedisTemplate(connections);
    }

    @AfterAll
    static void close() { if (connections != null) connections.destroy(); }

    @BeforeEach
    void prepare() {
        properties = new AuthenticationProperties("urn:test", "test", null, null, Duration.ofMinutes(15),
                Duration.ofHours(8), System.getenv("RBAC_AUTH_NAMESPACE") + ":store:" + UUID.randomUUID(),
                "REFRESH", "CSRF", "/", false);
        Clock clock = new Clock() {
            @Override public ZoneId getZone() { return ZoneOffset.UTC; }
            @Override public Clock withZone(ZoneId zone) { return Clock.fixed(now.get(), zone); }
            @Override public Instant instant() { return now.get(); }
        };
        store = new RedisLoginStore(redis, properties, clock);
    }

    @AfterEach
    void releaseOwnedRecords() {
        // 每项都是本用例记录过的完整随机key，不执行flush或按前缀删除。
        for (String key : owned) redis.delete(key);
    }

    @Test
    void storesOnlyDigestAndChecksEmployeeOwnership() {
        RefreshCredential credential = create(7L);
        assertThat(redis.opsForHash().entries(store.key(credential.sid())).values())
                .contains(credential.digest()).doesNotContain(credential.value(), credential.secret());
        assertThatCode(() -> store.requireActive(credential.sid(), 7L)).doesNotThrowAnyException();
        assertRejected(() -> store.requireActive(credential.sid(), 8L), "AUTHENTICATION_REQUIRED");
    }

    @Test
    void rotatingDoesNotExtendAbsoluteDeadlineOrTtl() {
        RefreshCredential old = create(7L);
        RedisLoginStore.Login login = store.inspect(old);
        long ttl = redis.getExpire(store.key(old.sid()), java.util.concurrent.TimeUnit.MILLISECONDS);
        now.set(now.get().plus(Duration.ofHours(7)));
        RefreshCredential next = RefreshCredential.create(old.sid());
        store.rotate(login, old, next);
        assertThat(store.inspect(next).expiresAt()).isEqualTo(login.expiresAt());
        assertThat(redis.getExpire(store.key(old.sid()), java.util.concurrent.TimeUnit.MILLISECONDS)).isLessThanOrEqualTo(ttl);
        assertThat(redis.opsForSet().members(store.key(old.sid()) + ":used")).containsExactly(old.digest());
        now.set(login.expiresAt());
        assertRejected(() -> store.requireActive(old.sid(), 7L), "AUTHENTICATION_REQUIRED");
    }

    @Test
    void unknownSecretCannotRevokeKnownSid() {
        RefreshCredential legitimate = create(7L);
        RefreshCredential forged = RefreshCredential.create(legitimate.sid());
        assertRejected(() -> store.inspect(forged), "REFRESH_TOKEN_INVALID");
        store.revoke(forged);
        assertThatCode(() -> store.requireActive(legitimate.sid(), 7L)).doesNotThrowAnyException();
    }

    @Test
    void consumedCredentialReuseRevokesWholeLogin() {
        RefreshCredential old = create(7L);
        RefreshCredential next = RefreshCredential.create(old.sid());
        store.rotate(store.inspect(old), old, next);
        assertRejected(() -> store.inspect(old), "REFRESH_TOKEN_REUSED");
        assertRejected(() -> store.requireActive(old.sid(), 7L), "AUTHENTICATION_REQUIRED");
        assertRejected(() -> store.inspect(next), "REFRESH_TOKEN_INVALID");
    }

    @Test
    void twoConcurrentRotationsHaveOneWinnerAndRevokeOnReuse() throws Exception {
        RefreshCredential old = create(7L);
        RedisLoginStore.Login login = store.inspect(old);
        CyclicBarrier barrier = new CyclicBarrier(2);
        try (var workers = Executors.newFixedThreadPool(2)) {
            var action = (java.util.concurrent.Callable<String>) () -> {
                barrier.await(5, java.util.concurrent.TimeUnit.SECONDS);
                try { store.rotate(login, old, RefreshCredential.create(old.sid())); return "OK"; }
                catch (AuthenticationFailure failure) { return failure.getError().getErrorCode(); }
            };
            var first = workers.submit(action);
            var second = workers.submit(action);
            assertThat(List.of(first.get(10, java.util.concurrent.TimeUnit.SECONDS), second.get(10, java.util.concurrent.TimeUnit.SECONDS)))
                    .containsExactlyInAnyOrder("OK", "REFRESH_TOKEN_REUSED");
        }
        assertRejected(() -> store.requireActive(old.sid(), 7L), "AUTHENTICATION_REQUIRED");
    }

    @Test
    void logoutAndRefreshRaceCannotResurrectLogin() throws Exception {
        RefreshCredential old = create(7L);
        RedisLoginStore.Login login = store.inspect(old);
        CyclicBarrier barrier = new CyclicBarrier(2);
        try (var workers = Executors.newFixedThreadPool(2)) {
            var logout = workers.submit(() -> { barrier.await(); store.revoke(old); return "OUT"; });
            var refresh = workers.submit(() -> {
                barrier.await();
                try { store.rotate(login, old, RefreshCredential.create(old.sid())); return "OK"; }
                catch (AuthenticationFailure failure) { return failure.getError().getErrorCode(); }
            });
            assertThat(logout.get(10, java.util.concurrent.TimeUnit.SECONDS)).isEqualTo("OUT");
            assertThat(refresh.get(10, java.util.concurrent.TimeUnit.SECONDS)).isIn("OK", "REFRESH_TOKEN_INVALID");
        }
        assertRejected(() -> store.requireActive(old.sid(), 7L), "AUTHENTICATION_REQUIRED");
    }

    @Test
    void independentLoginsAreNotUserVersions() {
        RefreshCredential first = create(7L);
        RefreshCredential second = create(7L);
        store.revoke(first);
        assertRejected(() -> store.requireActive(first.sid(), 7L), "AUTHENTICATION_REQUIRED");
        assertThatCode(() -> store.requireActive(second.sid(), 7L)).doesNotThrowAnyException();
    }

    @Test
    void ineligibleActorRevocationRequiresVerifiedOwnership() {
        RefreshCredential credential = create(7L);
        store.rejectEmployee(credential.sid(), 8L);
        assertThatCode(() -> store.requireActive(credential.sid(), 7L)).doesNotThrowAnyException();
        store.rejectEmployee(credential.sid(), 7L);
        assertRejected(() -> store.requireActive(credential.sid(), 7L), "AUTHENTICATION_REQUIRED");
    }

    @Test
    void oldConsumedCredentialCanLogoutCurrentLogin() {
        RefreshCredential old = create(7L);
        RefreshCredential next = RefreshCredential.create(old.sid());
        store.rotate(store.inspect(old), old, next);
        store.revoke(old);
        assertRejected(() -> store.inspect(next), "REFRESH_TOKEN_INVALID");
    }

    @Test
    void missingOrCorruptedRecordNeverRestoresLogin() {
        RefreshCredential credential = create(7L);
        redis.opsForHash().delete(store.key(credential.sid()), "expiresAt");
        assertThatThrownBy(() -> store.requireActive(credential.sid(), 7L))
                .isInstanceOf(AuthenticationStoreFailure.InvalidState.class);
        redis.delete(store.key(credential.sid()));
        assertRejected(() -> store.requireActive(credential.sid(), 7L), "AUTHENTICATION_REQUIRED");
        assertThat(redis.hasKey(store.key(credential.sid()))).isFalse();
    }

    private RefreshCredential create(Long employeeId) {
        RefreshCredential credential = RefreshCredential.create(UUID.randomUUID().toString());
        owned.add(store.key(credential.sid()));
        owned.add(store.key(credential.sid()) + ":used");
        store.create(new RedisLoginStore.Login(credential.sid(), employeeId, now.get(),
                now.get().plus(Duration.ofHours(8))), credential);
        return credential;
    }

    private void assertRejected(org.assertj.core.api.ThrowableAssert.ThrowingCallable action, String code) {
        assertThatThrownBy(action).isInstanceOfSatisfying(AuthenticationFailure.class,
                failure -> assertThat(failure.getError().getErrorCode()).isEqualTo(code));
    }
}
