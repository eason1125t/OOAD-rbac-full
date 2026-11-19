// 发布角色继承写入 API，由 Service 执行范围、图与分配校验。
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

@RestController
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
@RequiredArgsConstructor
public class RoleInheritanceController {
    private static final String EDGE = "/api/roles/{roleId}/parents/{parentRoleId}";

    private final RoleInheritanceService inheritance;

    @PutMapping(EDGE)
    public ResponseEntity<Void> add(@AuthenticationPrincipal Long actor, @PathVariable @Positive Long roleId,
                                    @PathVariable @Positive Long parentRoleId) {
        inheritance.change(actor, roleId, parentRoleId, true);
        return ResponseEntity.noContent().build();
    }

    @DeleteMapping(EDGE)
    public ResponseEntity<Void> remove(@AuthenticationPrincipal Long actor, @PathVariable @Positive Long roleId,
                                       @PathVariable @Positive Long parentRoleId) {
        inheritance.change(actor, roleId, parentRoleId, false);
        return ResponseEntity.noContent().build();
    }
}
