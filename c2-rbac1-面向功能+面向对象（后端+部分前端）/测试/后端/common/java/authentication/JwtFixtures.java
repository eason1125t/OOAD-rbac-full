// 文件职责：为两版 JWT 安全测试提供临时 RSA 密钥、固定时钟和真实签名样本。
// 协作关系：仅供共同测试使用；不写入密钥文件、不连接生产存储。
package authentication;

import com.nimbusds.jose.JOSEObjectType;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** 测试私钥仅在当前 JVM 内生成和使用。 */
final class JwtFixtures {
    static final Instant NOW = Instant.parse("2026-11-21T04:00:00Z");
    static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);
    static final KeyPair KEYS = keys();
    static final String SID = UUID.randomUUID().toString();
    static final String ISSUER = "urn:ooad-rbac:c2:func";
    static final String AUDIENCE = "rbac1-func";

    private JwtFixtures() { }

    static KeyPair keys() {
        try {
            KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
            generator.initialize(2048);
            return generator.generateKeyPair();
        } catch (Exception failure) {
            throw new IllegalStateException("Cannot create ephemeral test key", failure);
        }
    }

    static JwtAccessTokens tokens(Clock clock) {
        return new JwtAccessTokens(ISSUER, AUDIENCE, (RSAPublicKey) KEYS.getPublic(),
                (RSAPrivateKey) KEYS.getPrivate(), clock, Duration.ofMinutes(15));
    }

    static Map<String, Object> claims() {
        Map<String, Object> claims = new LinkedHashMap<>();
        claims.put("iss", ISSUER);
        claims.put("aud", List.of(AUDIENCE));
        claims.put("sub", "7");
        claims.put("sid", SID);
        claims.put("jti", UUID.randomUUID().toString());
        claims.put("iat", Date.from(NOW));
        claims.put("exp", Date.from(NOW.plusSeconds(900)));
        return claims;
    }

    /** 直接构造真实已签名异常 claims，避免被正常签发接口提前修正。 */
    static String sign(Map<String, Object> claims) {
        return sign(claims, KEYS, JWSAlgorithm.RS256);
    }

    static String sign(Map<String, Object> claims, KeyPair keys, JWSAlgorithm algorithm) {
        try {
            JWTClaimsSet.Builder builder = new JWTClaimsSet.Builder();
            claims.forEach(builder::claim);
            SignedJWT jwt = new SignedJWT(new JWSHeader.Builder(algorithm).type(JOSEObjectType.JWT).build(),
                    builder.build());
            jwt.sign(new RSASSASigner((RSAPrivateKey) keys.getPrivate()));
            return jwt.serialize();
        } catch (Exception failure) {
            throw new IllegalStateException("Cannot sign test sample", failure);
        }
    }
}
