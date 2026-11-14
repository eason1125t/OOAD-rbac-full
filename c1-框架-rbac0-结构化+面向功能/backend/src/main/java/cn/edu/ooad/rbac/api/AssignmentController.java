package cn.edu.ooad.rbac.api;

import cn.edu.ooad.rbac.service.AssignmentService;
import jakarta.validation.constraints.Positive;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RestController;

/** 发布管理端的关系接口，校验路径参数后将写入交给授权配置 Service。 */
@RestController
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
@RequiredArgsConstructor
public class AssignmentController {
    private static final String EMPLOYEE_ROLE = "/api/employees/{employeeId}/roles/{roleId}";
    private static final String ROLE_PERMISSION = "/api/roles/{roleId}/permissions/{permissionId}";

    private final AssignmentService assignments;

    /** 事务提交后确认员工已分配角色。 */
    @PutMapping(EMPLOYEE_ROLE)
    public ResponseEntity<Void> grantRole(@AuthenticationPrincipal Long operator,
            @PathVariable @Positive Long employeeId, @PathVariable @Positive Long roleId) {
        assignments.changeEmployeeRole(operator, employeeId, roleId, true);
        return ResponseEntity.noContent().build();
    }

    /** 事务提交后确认员工角色关系已移除。 */
    @DeleteMapping(EMPLOYEE_ROLE)
    public ResponseEntity<Void> revokeRole(@AuthenticationPrincipal Long operator,
            @PathVariable @Positive Long employeeId, @PathVariable @Positive Long roleId) {
        assignments.changeEmployeeRole(operator, employeeId, roleId, false);
        return ResponseEntity.noContent().build();
    }

    /** 事务提交后确认角色拥有目标权限。 */
    @PutMapping(ROLE_PERMISSION)
    public ResponseEntity<Void> grantPermission(@AuthenticationPrincipal Long operator,
            @PathVariable @Positive Long roleId, @PathVariable @Positive Long permissionId) {
        assignments.changeRolePermission(operator, roleId, permissionId, true);
        return ResponseEntity.noContent().build();
    }

    /** 事务提交后确认角色权限关系已移除。 */
    @DeleteMapping(ROLE_PERMISSION)
    public ResponseEntity<Void> revokePermission(@AuthenticationPrincipal Long operator,
            @PathVariable @Positive Long roleId, @PathVariable @Positive Long permissionId) {
        assignments.changeRolePermission(operator, roleId, permissionId, false);
        return ResponseEntity.noContent().build();
    }
}
