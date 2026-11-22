// 文件职责：验证刷新凭证的编码/脱敏和Cookie安全属性、重复值、配置边界。
// 协作关系：纯单元测试；HTTP与真实Redis状态另由共同集成测试验证。
package authentication;

import jakarta.servlet.http.Cookie;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.HttpHeaders;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AuthCookiesTest {
    @Test
    void refreshEncodingIsCanonicalRandomAndRedacted() {
        String sid = UUID.randomUUID().toString();
        RefreshCredential first = RefreshCredential.create(sid);
        RefreshCredential second = RefreshCredential.create(sid);
        assertThat(RefreshCredential.parse(first.value())).contains(first);
        assertThat(first.value()).hasSize(80);
        assertThat(first.digest()).matches("[a-f0-9]{64}");
        assertThat(first.secret()).isNotEqualTo(second.secret());
        assertThat(first.toString()).doesNotContain(first.secret(), first.value());
        assertThat(new LoginAuthentication.Issued(new authentication.vo.AccessTokenVO("access-secret", "Bearer", 10),
                first.value(), Instant.now()).toString()).doesNotContain("access-secret", first.secret());
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "not-a-refresh", "wrong-length", "wrong-sid", "wrong-separator", "noncanonical-secret"})
    void malformedCredentialCannotChooseLogin(String defect) {
        String valid = RefreshCredential.create(UUID.randomUUID().toString()).value();
        String raw = switch (defect) {
            case "" -> "";
            case "not-a-refresh" -> "not-a-refresh";
            case "wrong-length" -> valid + "x";
            case "wrong-sid" -> "00000000-0000-0000-0000-000000000000" + valid.substring(36);
            case "wrong-separator" -> valid.substring(0,36) + ":" + valid.substring(37);
            case "noncanonical-secret" -> valid.substring(0,79) + "_";
            default -> throw new AssertionError(defect);
        };
        assertThat(RefreshCredential.parse(raw)).isEmpty();
    }

    @Test
    void writesSecureHostOnlyCookieAndClearsSamePath() {
        Instant now = Instant.parse("2026-11-21T12:00:00Z");
        AuthCookies cookies = new AuthCookies(properties("normal"), Clock.fixed(now,ZoneOffset.UTC));
        MockHttpServletResponse response = new MockHttpServletResponse();
        cookies.write(response, "opaque-value", now.plusSeconds(3600));
        assertThat(response.getHeader(HttpHeaders.SET_COOKIE))
                .contains("REFRESH=opaque-value", "Path=/func/api/auth/", "HttpOnly", "Secure", "SameSite=Strict", "Max-Age=3600")
                .doesNotContain("Domain=");
        cookies.clear(response);
        assertThat(response.getHeaders(HttpHeaders.SET_COOKIE)).anyMatch(value -> value.contains("Max-Age=0")
                && value.contains("Path=/func/api/auth/") && value.startsWith("REFRESH="));
    }

    @Test
    void rejectsAmbiguousDuplicateCookie() {
        AuthCookies cookies = new AuthCookies(properties("normal"), Clock.systemUTC());
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setCookies(new Cookie("REFRESH","a"), new Cookie("REFRESH","b"));
        assertThat(cookies.read(request)).isNull();
        request.setCookies(new Cookie("OTHER","a"),new Cookie("REFRESH","b"));
        assertThat(cookies.read(request)).isEqualTo("b");
    }

    @ParameterizedTest
    @ValueSource(strings = {"access-long", "login-long", "zero-login", "namespace", "cookie-name", "path"})
    void rejectsUnsafeConfiguration(String defect) {
        assertThatThrownBy(() -> properties(defect)).isInstanceOf(IllegalArgumentException.class);
    }

    private AuthenticationProperties properties(String defect) {
        return new AuthenticationProperties("urn:test", "test", null, null,
                defect.equals("access-long") ? Duration.ofMinutes(16) : Duration.ofMinutes(15),
                defect.equals("login-long") ? Duration.ofHours(9) : defect.equals("zero-login") ? Duration.ZERO : Duration.ofHours(8),
                defect.equals("namespace") ? "key{injection}" : "test:auth",
                defect.equals("cookie-name") ? "bad;name" : "REFRESH", "CSRF",
                defect.equals("path") ? "/auth/;invalid" : "/func/api/auth/", true);
    }
}
