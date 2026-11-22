// 文件职责：使用 Spring/Nimbus 签发及验证短期 RS256 访问 JWT，落实身份字段和时间契约。
// 协作关系：向 JwtAuthenticationProvider 提供 JwtDecoder；Redis 登录撤销与员工资格另由身份转换执行。
package authentication;

import authentication.vo.AccessTokenVO;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.source.ImmutableJWKSet;
import com.nimbusds.jwt.SignedJWT;
import java.io.IOException;
import java.io.InputStream;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.text.ParseException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;
import org.springframework.core.io.Resource;
import org.springframework.security.converter.RsaKeyConverters;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.jwt.BadJwtException;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;
import org.springframework.util.Assert;

/** 仅负责访问凭证，不提供无 Redis 登录检查的认证捷径；不自动生成生产密钥。 */
public final class JwtAccessTokens implements JwtDecoder {
    private static final Duration MAX_ACCESS_TTL = Duration.ofMinutes(15);
    private static final Duration MAX_LOGIN_TTL = Duration.ofHours(8);
    private static final Duration CLOCK_SKEW = Duration.ofSeconds(30);
    private static final OAuth2Error INVALID = new OAuth2Error("invalid_token", "访问凭证无效", null);
    private static final OAuth2Error EXPIRED = new OAuth2Error("ACCESS_TOKEN_EXPIRED", "访问凭证已过期", null);
    private final String issuer;
    private final String audience;
    private final Clock clock;
    private final Duration accessTtl;
    private final JwtEncoder encoder;
    private final NimbusJwtDecoder decoder;

    public JwtAccessTokens(String issuer, String audience, RSAPublicKey publicKey, RSAPrivateKey privateKey,
                           Clock clock, Duration accessTtl) {
        Assert.hasText(issuer, "JWT issuer is required");
        Assert.hasText(audience, "JWT audience is required");
        Assert.notNull(publicKey, "JWT public key is required");
        Assert.notNull(privateKey, "JWT private key is required");
        Assert.notNull(clock, "JWT clock is required");
        Assert.notNull(accessTtl, "JWT access TTL is required");
        Assert.isTrue(!accessTtl.isNegative() && accessTtl.getSeconds() > 0
                        && accessTtl.getNano() == 0 && accessTtl.compareTo(MAX_ACCESS_TTL) <= 0,
                "JWT access TTL must be whole seconds within 15 minutes");
        Assert.isTrue(publicKey.getModulus().bitLength() >= 2048
                        && publicKey.getModulus().equals(privateKey.getModulus()),
                "JWT requires a matching RSA key pair of at least 2048 bits");
        this.issuer = issuer;
        this.audience = audience;
        this.clock = clock;
        this.accessTtl = accessTtl;
        RSAKey key = new RSAKey.Builder(publicKey).privateKey(privateKey).build();
        this.encoder = new NimbusJwtEncoder(new ImmutableJWKSet<>(new JWKSet(key)));
        this.decoder = NimbusJwtDecoder.withPublicKey(publicKey).signatureAlgorithm(SignatureAlgorithm.RS256).build();
        // Nimbus 先验证签名与结构；此验证器统一必填字段、issuer/audience 和可区分的过期结果。
        this.decoder.setJwtValidator(this::validate);
    }

    /** 从外部 PKCS#8 私钥、X.509 公钥读取；不记录密钥内容，也不回退到随机或默认密钥。 */
    public static JwtAccessTokens fromPem(String issuer, String audience, Resource privatePem, Resource publicPem,
                                          Clock clock, Duration accessTtl) {
        Assert.notNull(privatePem, "JWT private key resource is required");
        Assert.notNull(publicPem, "JWT public key resource is required");
        try (InputStream privateInput = privatePem.getInputStream(); InputStream publicInput = publicPem.getInputStream()) {
            return new JwtAccessTokens(issuer, audience, RsaKeyConverters.x509().convert(publicInput),
                    RsaKeyConverters.pkcs8().convert(privateInput), clock, accessTtl);
        } catch (IOException | IllegalArgumentException failure) {
            // 错误只描述配置无效；不把 PEM 或底层解析异常中的输入材料拼进消息。
            throw new IllegalArgumentException("JWT 密钥配置无效");
        }
    }

    /** 返回签名准备结果；调用方发布前必须建立有效登录记录，exp不超过该次登录绝对上限。 */
    public AccessTokenVO issue(Long employeeId, String sid, Instant loginExpiresAt) {
        Instant clockNow = clock.instant();
        Instant now = clockNow.truncatedTo(ChronoUnit.SECONDS);
        Assert.isTrue(employeeId != null && employeeId > 0, "A positive employee ID is required");
        Assert.isTrue(isRandomId(sid), "A random login ID is required");
        Assert.isTrue(loginExpiresAt != null && loginExpiresAt.isAfter(now)
                        && !loginExpiresAt.isAfter(clockNow.plus(MAX_LOGIN_TTL)),
                "Login deadline must be within eight hours");
        Instant expiresAt = now.plus(accessTtl).isBefore(loginExpiresAt) ? now.plus(accessTtl) : loginExpiresAt;
        expiresAt = expiresAt.truncatedTo(ChronoUnit.SECONDS);
        Assert.isTrue(expiresAt.isAfter(now), "Login deadline must leave at least one second");
        JwtClaimsSet claims = JwtClaimsSet.builder().issuer(issuer).audience(List.of(audience))
                .subject(employeeId.toString()).id(UUID.randomUUID().toString()).claim("sid", sid)
                .issuedAt(now).expiresAt(expiresAt).build();
        String value = encoder.encode(JwtEncoderParameters.from(
                JwsHeader.with(SignatureAlgorithm.RS256).type("JWT").build(), claims)).getTokenValue();
        return new AccessTokenVO(value, "Bearer", Duration.between(now, expiresAt).getSeconds());
    }

    /** 委托 Spring/Nimbus 完成签名、算法、JWT 结构与下述契约验证。 */
    @Override
    public Jwt decode(String token) {
        // 解码库会把数字 sub 规范化成字符串，预检原始 JSON 类型；此处只拒绝，绝不据此建立身份。
        // 仍由下方 NimbusJwtDecoder 完成全部验签及契约校验，不手写 JWT 分割或密码算法。
        try {
            var claims = SignedJWT.parse(token).getPayload().toJSONObject();
            if (claims == null || !(claims.get("sub") instanceof String)) {
                throw new BadJwtException("访问凭证无效");
            }
        } catch (ParseException | IllegalArgumentException failure) {
            throw new BadJwtException("访问凭证无效");
        }
        return decoder.decode(token);
    }

    /** 先验证非过期问题，避免错误签发方/身份被当作可自动刷新的单纯过期。 */
    private OAuth2TokenValidatorResult validate(Jwt jwt) {
        Instant now = clock.instant();
        try {
            Instant issuedAt = jwt.getIssuedAt();
            Instant expiresAt = jwt.getExpiresAt();
            Instant notBefore = jwt.getNotBefore();
            if (!issuer.equals(jwt.getClaimAsString("iss")) || jwt.getAudience() == null
                    || !jwt.getAudience().contains(audience)
                    || employeeId(jwt.getSubject()) == null || !isRandomId(jwt.getClaimAsString("sid"))
                    || !isRandomId(jwt.getId()) || issuedAt == null || expiresAt == null
                    || !expiresAt.isAfter(issuedAt) || Duration.between(issuedAt, expiresAt).compareTo(accessTtl) > 0
                    || issuedAt.isAfter(now.plus(CLOCK_SKEW))
                    || (notBefore != null && (notBefore.isAfter(now.plus(CLOCK_SKEW)) || !expiresAt.isAfter(notBefore)))) {
                return OAuth2TokenValidatorResult.failure(INVALID);
            }
            if (!expiresAt.isAfter(now.minus(CLOCK_SKEW))) {
                return OAuth2TokenValidatorResult.failure(EXPIRED);
            }
            return OAuth2TokenValidatorResult.success();
        } catch (IllegalArgumentException | ClassCastException failure) {
            return OAuth2TokenValidatorResult.failure(INVALID);
        }
    }

    /** 只接受正 Long 的十进制身份，不把用户名、浮点数或溢出值用作员工 ID。 */
    static Long employeeId(String subject) {
        if (subject == null || !subject.matches("[1-9][0-9]{0,18}")) return null;
        try {
            return Long.valueOf(subject);
        } catch (NumberFormatException failure) {
            return null;
        }
    }

    /** 使用标准随机 UUID 文本，拒绝缩写、零值或用户号；不使用任何递增版本。 */
    static boolean isRandomId(String value) {
        if (value == null) return false;
        try {
            UUID id = UUID.fromString(value);
            return id.version() == 4 && id.variant() == 2 && id.toString().equals(value);
        } catch (IllegalArgumentException failure) {
            return false;
        }
    }
}
