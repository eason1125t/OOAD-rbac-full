// 文件职责：以真实 RSA 签名验证 JWT 契约、时间边界、密钥及异常分类。
// 协作关系：两版共用固定时钟和临时密钥；不依赖 Redis 或 HTTP 服务。
package authentication;

import authentication.vo.AccessTokenVO;
import com.nimbusds.jose.JWSAlgorithm;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.time.Clock;
import java.time.Duration;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.Date;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtException;
import org.springframework.security.oauth2.jwt.JwtValidationException;

import static authentication.JwtFixtures.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class JwtAccessTokensTest {
    private final JwtAccessTokens tokens = tokens(CLOCK);

    @Test
    void signsIdentityOnlyAndRoundTrips() {
        AccessTokenVO response = tokens.issue(7L, SID, NOW.plus(Duration.ofHours(8)));
        Jwt jwt = tokens.decode(response.accessToken());
        assertThat(jwt.getClaims()).containsOnlyKeys("iss", "aud", "sub", "sid", "jti", "iat", "exp");
        assertThat(jwt.getSubject()).isEqualTo("7");
        assertThat(jwt.getClaimAsString("sid")).isEqualTo(SID);
        assertThat(jwt.getHeaders().get("alg")).isEqualTo("RS256");
        assertThat(response.tokenType()).isEqualTo("Bearer");
        assertThat(response.expiresIn()).isEqualTo(900);
        assertThat(jwt.getExpiresAt()).isEqualTo(NOW.plusSeconds(900));
    }

    @Test
    void clampsToAbsoluteLoginDeadlineAndIssuesDistinctTokens() {
        AccessTokenVO first = tokens.issue(7L, SID, NOW.plusSeconds(40));
        AccessTokenVO second = tokens.issue(7L, SID, NOW.plusSeconds(40));
        assertThat(first.expiresIn()).isEqualTo(40);
        assertThat(tokens.decode(first.accessToken()).getExpiresAt()).isEqualTo(NOW.plusSeconds(40));
        assertThat(tokens.decode(first.accessToken()).getId()).isNotEqualTo(tokens.decode(second.accessToken()).getId());
    }

    @Test
    void handlesRealClockFractionWithoutRejectingEightHourLogin() {
        Clock fractional = Clock.fixed(NOW.plusMillis(123), ZoneOffset.UTC);
        AccessTokenVO issued = tokens(fractional).issue(7L, SID, fractional.instant().plus(Duration.ofHours(8)));
        assertThat(issued.expiresIn()).isEqualTo(900);
    }

    @ParameterizedTest
    @ValueSource(strings = {"numeric-sub", "leading-zero-sub", "typed-sid", "typed-iat", "typed-exp"})
    void rejectsMalformedClaimTypes(String defect) throws Exception {
        Map<String, Object> claims = claims();
        switch (defect) {
            case "numeric-sub" -> claims.put("sub", 7L);
            case "leading-zero-sub" -> claims.put("sub", "007");
            case "typed-sid" -> claims.put("sid", Map.of("value", SID));
            case "typed-iat" -> claims.put("iat", "not-a-date");
            case "typed-exp" -> claims.put("exp", "not-a-date");
            default -> throw new AssertionError(defect);
        }
        String sample = sign(claims);
        if (defect.equals("numeric-sub")) {
            assertThat(com.nimbusds.jwt.SignedJWT.parse(sample).getPayload().toJSONObject().get("sub"))
                    .isInstanceOf(Number.class);
        }
        assertThatThrownBy(() -> tokens.decode(sample)).isInstanceOf(JwtException.class);
    }

    @Test
    void rejectsRsaKeysBelow2048Bits() throws Exception {
        java.security.KeyPairGenerator generator = java.security.KeyPairGenerator.getInstance("RSA");
        generator.initialize(1024);
        KeyPair weak = generator.generateKeyPair();
        assertThatThrownBy(() -> new JwtAccessTokens(ISSUER, AUDIENCE, (RSAPublicKey) weak.getPublic(),
                (RSAPrivateKey) weak.getPrivate(), CLOCK, Duration.ofMinutes(15)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @ParameterizedTest
    @ValueSource(strings = {"iss", "aud", "sub", "sid", "jti", "iat", "exp"})
    void rejectsMissingRequiredClaim(String field) {
        Map<String, Object> claims = claims();
        claims.remove(field);
        assertThatThrownBy(() -> tokens.decode(sign(claims))).isInstanceOf(JwtException.class);
    }

    @ParameterizedTest
    @ValueSource(strings = {"wrong-issuer", "wrong-audience", "zero-sub", "negative-sub", "overflow-sub",
            "text-sub", "bad-sid", "bad-jti", "future-iat", "expiry-before-issue", "too-long", "future-nbf"})
    void rejectsSignedButInvalidClaims(String defect) {
        Map<String, Object> claims = claims();
        switch (defect) {
            case "wrong-issuer" -> claims.put("iss", "urn:ooad-rbac:c2:oo");
            case "wrong-audience" -> claims.put("aud", "rbac1-oo");
            case "zero-sub" -> claims.put("sub", "0");
            case "negative-sub" -> claims.put("sub", "-7");
            case "overflow-sub" -> claims.put("sub", "9223372036854775808");
            case "text-sub" -> claims.put("sub", "employee-seven");
            case "bad-sid" -> claims.put("sid", "known-user-7");
            case "bad-jti" -> claims.put("jti", "token-7");
            case "future-iat" -> claims.put("iat", Date.from(NOW.plusSeconds(31)));
            case "expiry-before-issue" -> claims.put("exp", Date.from(NOW.minusSeconds(1)));
            case "too-long" -> claims.put("exp", Date.from(NOW.plusSeconds(901)));
            case "future-nbf" -> claims.put("nbf", Date.from(NOW.plusSeconds(31)));
            default -> throw new AssertionError(defect);
        }
        assertThatThrownBy(() -> tokens.decode(sign(claims))).isInstanceOf(JwtException.class);
    }

    @Test
    void distinguishesExpiredTokenOnlyAfterAllOtherChecksPass() {
        String access = tokens.issue(7L, SID, NOW.plusSeconds(900)).accessToken();
        JwtAccessTokens later = tokens(Clock.fixed(NOW.plusSeconds(930), ZoneOffset.UTC));
        assertThatThrownBy(() -> later.decode(access)).isInstanceOfSatisfying(JwtValidationException.class,
                failure -> assertThat(failure.getErrors()).extracting(error -> error.getErrorCode())
                        .containsExactly("ACCESS_TOKEN_EXPIRED"));
        Map<String, Object> claims = claims();
        claims.put("aud", "rbac1-oo");
        assertThatThrownBy(() -> later.decode(sign(claims))).isInstanceOfSatisfying(JwtValidationException.class,
                failure -> assertThat(failure.getErrors()).extracting(error -> error.getErrorCode())
                        .doesNotContain("ACCESS_TOKEN_EXPIRED"));
    }

    @Test
    void acceptsOnlyConfiguredClockSkew() {
        String access = tokens.issue(7L, SID, NOW.plusSeconds(900)).accessToken();
        assertThat(tokens(Clock.fixed(NOW.plusSeconds(929), ZoneOffset.UTC)).decode(access)).isNotNull();
    }

    @ParameterizedTest
    @ValueSource(strings = {"other-key", "other-algorithm", "tampered", "unsigned", "garbage"})
    void rejectsUntrustedToken(String defect) {
        String valid = sign(claims());
        String sample = switch (defect) {
            case "other-key" -> sign(claims(), keys(), JWSAlgorithm.RS256);
            case "other-algorithm" -> sign(claims(), KEYS, JWSAlgorithm.RS512);
            case "tampered" -> valid.substring(0, valid.lastIndexOf('.') + 1)
                    + Base64.getUrlEncoder().withoutPadding().encodeToString(new byte[256]);
            case "unsigned" -> "eyJhbGciOiJub25lIn0.eyJzdWIiOiI3In0.";
            case "garbage" -> "not-a-jwt";
            default -> throw new AssertionError(defect);
        };
        assertThatThrownBy(() -> tokens.decode(sample)).isInstanceOf(JwtException.class);
    }

    @ParameterizedTest
    @ValueSource(strings = {"bad-id", "bad-sid", "expired-login", "excess-login"})
    void rejectsInvalidIssuance(String defect) {
        assertThatThrownBy(() -> tokens.issue(defect.equals("bad-id") ? 0L : 7L,
                defect.equals("bad-sid") ? "7" : SID,
                defect.equals("expired-login") ? NOW : defect.equals("excess-login")
                        ? NOW.plus(Duration.ofHours(9)) : NOW.plusSeconds(900)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @ParameterizedTest
    @ValueSource(strings = {"blank-issuer", "blank-audience", "zero-ttl", "long-ttl", "mismatched-key"})
    void rejectsInvalidConfiguration(String defect) {
        KeyPair other = defect.equals("mismatched-key") ? keys() : KEYS;
        assertThatThrownBy(() -> new JwtAccessTokens(defect.equals("blank-issuer") ? " " : ISSUER,
                defect.equals("blank-audience") ? "" : AUDIENCE, (RSAPublicKey) KEYS.getPublic(),
                (RSAPrivateKey) other.getPrivate(), CLOCK,
                defect.equals("zero-ttl") ? Duration.ZERO : defect.equals("long-ttl")
                        ? Duration.ofMinutes(16) : Duration.ofMinutes(15)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void loadsExternalPemAndKeepsTokensValidAcrossInstances() {
        JwtAccessTokens reloaded = JwtAccessTokens.fromPem(ISSUER, AUDIENCE,
                pem("PRIVATE KEY", KEYS.getPrivate().getEncoded()), pem("PUBLIC KEY", KEYS.getPublic().getEncoded()),
                CLOCK, Duration.ofMinutes(15));
        assertThat(reloaded.decode(tokens.issue(7L, SID, NOW.plusSeconds(900)).accessToken()).getSubject()).isEqualTo("7");
    }

    @Test
    void rejectsInvalidPemWithoutPrintingSecretMaterial() {
        assertThatThrownBy(() -> JwtAccessTokens.fromPem(ISSUER, AUDIENCE,
                new ByteArrayResource("private-invalid-test-material".getBytes(StandardCharsets.US_ASCII)),
                pem("PUBLIC KEY", KEYS.getPublic().getEncoded()), CLOCK, Duration.ofMinutes(15)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("JWT 密钥配置无效")
                .hasMessageNotContaining("private-invalid-test-material");
    }

    @Test
    void tokenResponseDoesNotExposeCredentialInToString() {
        AccessTokenVO response = tokens.issue(7L, SID, NOW.plusSeconds(900));
        assertThat(response.toString()).doesNotContain(response.accessToken());
    }

    private ByteArrayResource pem(String type, byte[] value) {
        String content = "-----BEGIN " + type + "-----\n" + Base64.getMimeEncoder(64, new byte[]{'\n'})
                .encodeToString(value) + "\n-----END " + type + "-----\n";
        return new ByteArrayResource(content.getBytes(StandardCharsets.US_ASCII));
    }
}
