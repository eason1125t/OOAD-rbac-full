// 文件职责：返回当前请求使用的 CSRF 令牌元数据。
// 协作关系：由 AuthController 输出，前端据此设置写请求头。
package authentication.vo;

/** CSRF 令牌的 HTTP 响应。 */
public record CsrfTokenVO(String token) {
}
