// 文件职责：组织指定系统的自身角色权限查询和单项前端权限检查。
// 协作关系：接收可信员工，复用 AuthorizationService 判权并从角色仓储补齐名称；不读取请求身份。
package authentication;

import authentication.vo.CurrentAuthorizationVO;
import authentication.vo.CurrentPermissionCheckVO;
import authentication.vo.CurrentApiCheckVO;
import authorization.ApiOperationRegistry;
import java.util.Set;
import authorization.AuthorizationService;
import contract.BusinessAccessException;
import contract.BusinessAccessException.Reason;
import java.util.Comparator;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import organization.Employee;
import rolepermission.RbacRole;
import rolepermission.RbacRoleRepository;
import simulation.BusinessSystem;
import simulation.BusinessSystemRepository;

/** 自身查询不借用管理员接口；调用者负责先取得可信有效员工。 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class CurrentAuthorizationService {
    private final BusinessSystemRepository systems;
    private final AuthorizationService authorization;
    private final RbacRoleRepository roles;
    private final ApiOperationRegistry apiOperations;

    /** 以同一次继承图快照计算来源和权限，公开响应不暴露持久化对象。 */
    public CurrentAuthorizationVO describe(Employee employee, String systemCode) {
        BusinessSystem system = system(systemCode);
        AuthorizationService.SystemAuthorization result = authorization.describe(employee, system);
        Map<Long, AuthorizationService.RoleSource> sources = result.roles().stream()
                .collect(Collectors.toMap(AuthorizationService.RoleSource::roleId, Function.identity()));
        var visibleRoles = roles.findAllById(sources.keySet()).stream()
                .sorted(Comparator.comparing(RbacRole::getId))
                .map(role -> {
                    AuthorizationService.RoleSource source = sources.get(role.getId());
                    return new CurrentAuthorizationVO.Role(role.getId(), role.getName(), source.direct(),
                            !source.inheritedFromRoleIds().isEmpty(), source.inheritedFromRoleIds(),
                            source.appliesToSystem());
                }).toList();
        var effective = result.effectivePermissions().stream()
                .map(permission -> new CurrentAuthorizationVO.Permission(permission.getId(), permission.getCode(),
                        permission.getName())).toList();
        return new CurrentAuthorizationVO(system.getCode(), visibleRoles, effective, result.globalAdministrator());
    }

    /** 未知系统/权限和系统越界由授权入口区分，正常拒绝不依靠捕获异常实现。 */
    public CurrentPermissionCheckVO check(Employee employee, String systemCode, String permissionCode) {
        BusinessSystem system = system(systemCode);
        boolean allowed = authorization.hasBusinessPermission(employee, system, permissionCode);
        return new CurrentPermissionCheckVO(system.getCode(), permissionCode, allowed);
    }

    /** 先验证系统准入，读取实时有效权限后检查映射完整性，最后执行固定ALL判断。 */
    public CurrentApiCheckVO checkApi(Employee employee, String systemCode, String operationId) {
        BusinessSystem system = system(systemCode);
        Set<String> effective = authorization.effectivePermissions(employee, system).stream()
                .map(rolepermission.Permission::getCode).collect(Collectors.toSet());
        Set<String> required = apiOperations.requiredPermissions(system.getId(), operationId);
        return new CurrentApiCheckVO(system.getCode(), operationId, effective.containsAll(required));
    }

    private BusinessSystem system(String systemCode) {
        return systems.findByCode(systemCode)
                .orElseThrow(() -> new BusinessAccessException(Reason.SYSTEM_NOT_FOUND));
    }
}
