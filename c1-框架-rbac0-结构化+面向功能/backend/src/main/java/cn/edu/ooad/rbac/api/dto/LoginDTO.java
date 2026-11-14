package cn.edu.ooad.rbac.api.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** JSON 登录请求，由认证接口校验。 */
public record LoginDTO(@NotBlank @Size(max = 128) String username,
                       @NotBlank @Size(max = 128) String password) {
}
