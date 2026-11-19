package assignment;

import assignment.vo.PolicyStateVO;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RestController;

/** 发布组织停用、重新开放和角色重授接口，写入规则由 Service 管理。 */
@RestController
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
@RequiredArgsConstructor
public class OrganizationPermissionController {
    public static final String POLICY = "/api/organizations/{organizationId}/business-systems/{systemCode}"
            + "/permissions/{permissionCode}";
    public static final String DISABLED = POLICY + "/disabled";
    public static final String REGRANTS = POLICY + "/regrants/{roleId}";

    private final OrganizationPermissionService policies;

    /** 回读选定组织自身的策略状态。 */
    @GetMapping(POLICY)
    public ResponseEntity<PolicyStateVO> current(@AuthenticationPrincipal Long operator,
            @PathVariable @Positive Long organizationId, @PathVariable @Size(min = 1, max = 64) String systemCode,
            @PathVariable @Size(min = 1, max = 128) String permissionCode) {
        OrganizationPermissionService.PolicyState state = policies.current(operator, organizationId, systemCode, permissionCode);
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(new PolicyStateVO(state.status(), state.roleIds()));
    }

    /** 停用指定范围的动作。 */
    @PutMapping(DISABLED)
    public ResponseEntity<Void> disable(@AuthenticationPrincipal Long operator, @PathVariable @Positive Long organizationId,
            @PathVariable @Size(min = 1, max = 64) String systemCode,
            @PathVariable @Size(min = 1, max = 128) String permissionCode) {
        policies.changeDisabled(operator, organizationId, systemCode, permissionCode, true);
        return ResponseEntity.noContent().build();
    }

    /** 重新开放动作并保留普通账号的重授要求。 */
    @DeleteMapping(DISABLED)
    public ResponseEntity<Void> reopen(@AuthenticationPrincipal Long operator, @PathVariable @Positive Long organizationId,
            @PathVariable @Size(min = 1, max = 64) String systemCode,
            @PathVariable @Size(min = 1, max = 128) String permissionCode) {
        policies.changeDisabled(operator, organizationId, systemCode, permissionCode, false);
        return ResponseEntity.noContent().build();
    }

    /** 恢复已重新开放范围内的指定角色路径。 */
    @PutMapping(REGRANTS)
    public ResponseEntity<Void> regrant(@AuthenticationPrincipal Long operator, @PathVariable @Positive Long organizationId,
            @PathVariable @Size(min = 1, max = 64) String systemCode,
            @PathVariable @Size(min = 1, max = 128) String permissionCode, @PathVariable @Positive Long roleId) {
        policies.regrant(operator, organizationId, systemCode, permissionCode, roleId);
        return ResponseEntity.noContent().build();
    }
}
