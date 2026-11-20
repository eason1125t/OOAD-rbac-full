// 文件职责：提供直接继承边的幂等增删 API。
// 协作关系：将可信操作者及角色 ID 传给 RoleInheritanceService。
package rolepermission;

import jakarta.validation.constraints.Positive;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RestController;

/** 提供直接继承边的幂等增删 API，将可信操作者及角色 ID 传给 RoleInheritanceService。 */
@RestController
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
@RequiredArgsConstructor
public class RoleInheritanceController {
    private static final String EDGE = "/api/roles/{roleId}/parents/{parentRoleId}";

    private final RoleInheritanceService inheritance;

    /** 新增直接继承边，由 Service 校验范围、环路与增量授权后返回 204。 */
    @PutMapping(EDGE)
    public ResponseEntity<Void> add(@AuthenticationPrincipal Long actor, @PathVariable @Positive Long roleId,
                                    @PathVariable @Positive Long parentRoleId) {
        inheritance.change(actor, roleId, parentRoleId, true);
        return ResponseEntity.noContent().build();
    }

    /** 移除直接继承边，合法重复移除保持幂等并返回 204。 */
    @DeleteMapping(EDGE)
    public ResponseEntity<Void> remove(@AuthenticationPrincipal Long actor, @PathVariable @Positive Long roleId,
                                       @PathVariable @Positive Long parentRoleId) {
        inheritance.change(actor, roleId, parentRoleId, false);
        return ResponseEntity.noContent().build();
    }
}
