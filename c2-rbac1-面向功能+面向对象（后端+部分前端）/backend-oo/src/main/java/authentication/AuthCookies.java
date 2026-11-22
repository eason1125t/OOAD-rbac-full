// 文件职责：读写本版 HttpOnly 刷新 Cookie，不从 Cookie 建立业务身份。
// 协作关系：AuthController 调用；Cookie 属性来自 AuthenticationProperties，CSRF 由安全链独立处理。
package authentication;

import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.time.Clock;
import java.time.Instant;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseCookie;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
public class AuthCookies {
    private final AuthenticationProperties settings;
    private final Clock clock;

    public AuthCookies(AuthenticationProperties settings, Clock clock) {
        this.settings = settings;
        this.clock = clock;
    }

    /** 同名重复 Cookie 无法明确选择，按无效处理，不能用第一个值切换身份。 */
    public String read(HttpServletRequest request) {
        if (request.getCookies() == null) return null;
        String value = null;
        boolean found = false;
        for (Cookie cookie : request.getCookies()) {
            if (settings.refreshCookieName().equals(cookie.getName())) {
                if (found) return null;
                found = true;
                value = cookie.getValue();
            }
        }
        return value;
    }

    public void write(HttpServletResponse response, String raw, Instant expiresAt) {
        add(response, raw, Math.max(0, expiresAt.getEpochSecond() - clock.instant().getEpochSecond()));
    }

    public void clear(HttpServletResponse response) { add(response, "", 0); }

    private void add(HttpServletResponse response, String value, long maxAge) {
        ResponseCookie cookie = ResponseCookie.from(settings.refreshCookieName(), value).httpOnly(true)
                .secure(settings.cookieSecure()).sameSite("Strict").path(settings.cookiePath()).maxAge(maxAge).build();
        response.addHeader(HttpHeaders.SET_COOKIE, cookie.toString());
    }
}
