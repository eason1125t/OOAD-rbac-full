// 文件职责：承载访问 JWT、凭证类型及实际剩余秒数，不承载刷新凭证。
// 协作关系：由 JwtAccessTokens 签发；后续登录/刷新 Controller 使用，toString 不泄露凭证。
package authentication.vo;

/** JSON 字段沿用定稿契约；刷新凭证只允许通过 HttpOnly Cookie 交付。 */
public record AccessTokenVO(String accessToken, String tokenType, long expiresIn) {
    @Override
    public String toString() {
        return "AccessTokenVO[tokenType=" + tokenType + ", expiresIn=" + expiresIn + "]";
    }
}
