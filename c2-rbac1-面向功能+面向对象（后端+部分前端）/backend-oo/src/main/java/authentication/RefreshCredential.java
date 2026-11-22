// 文件职责：生成和解析高熵刷新凭证，计算存储摘要，隐藏默认文本表示。
// 协作关系：仅由登录生命周期使用；Redis 只接收摘要，原始值只交给 HttpOnly Cookie。
package authentication;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Optional;

/** 随机 sid 加256位随机 secret；不是 JWT，不承载用户或权限信息。 */
record RefreshCredential(String sid, String secret) {
    private static final SecureRandom RANDOM = new SecureRandom();

    static RefreshCredential create(String sid) {
        if (!JwtAccessTokens.isRandomId(sid)) throw new IllegalArgumentException("Invalid login ID");
        byte[] value = new byte[32];
        RANDOM.nextBytes(value);
        return new RefreshCredential(sid, Base64.getUrlEncoder().withoutPadding().encodeToString(value));
    }

    /** 固定长度及规范编码，畸形输入不查询 Redis。 */
    static Optional<RefreshCredential> parse(String raw) {
        if (raw == null || raw.length() != 80 || raw.charAt(36) != '.') return Optional.empty();
        String sid = raw.substring(0, 36);
        String secret = raw.substring(37);
        if (!JwtAccessTokens.isRandomId(sid) || !secret.matches("[A-Za-z0-9_-]{43}")) return Optional.empty();
        byte[] bytes = Base64.getUrlDecoder().decode(secret);
        if (bytes.length != 32 || !Base64.getUrlEncoder().withoutPadding().encodeToString(bytes).equals(secret)) {
            return Optional.empty();
        }
        return Optional.of(new RefreshCredential(sid, secret));
    }

    String value() { return sid + "." + secret; }

    String digest() {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value().getBytes(StandardCharsets.US_ASCII)));
        } catch (NoSuchAlgorithmException failure) {
            throw new IllegalStateException("SHA-256 is required");
        }
    }

    @Override
    public String toString() { return "RefreshCredential[redacted]"; }
}
