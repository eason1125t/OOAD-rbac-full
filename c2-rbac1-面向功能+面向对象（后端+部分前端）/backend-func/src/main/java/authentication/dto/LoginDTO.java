// 文件职责：声明登录请求的账号与密码字段校验。
// 协作关系：由 AuthController 接收，凭据只传给 SessionAuthentication。
package authentication.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** JSON 登录请求，由认证接口校验。 */
public record LoginDTO(@NotBlank @Size(max = 128) String username,
                       @NotBlank @Size(max = 128) String password) {
}
