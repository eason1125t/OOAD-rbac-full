// 申请系统查询与授权端口的适配器，统一调用既有系统仓储和实时授权服务。
package simulation.infrastructure;

import authorization.AuthorizationService;
import contract.BusinessAccessException;
import contract.BusinessAccessException.Reason;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import rolepermission.Permission;
import simulation.BusinessRequestService;
import simulation.BusinessSystem;
import simulation.BusinessSystemRepository;
import simulation.domain.RequestSystem;
import simulation.port.RequestAccess;
import simulation.port.RequestSystems;

/** 在基础设施边界转换系统实体并复用实时授权。 */
@Component
@RequiredArgsConstructor
public class RequestContextAdapter implements RequestAccess, RequestSystems {
    private final BusinessSystemRepository systems;
    private final AuthorizationService authorization;

    @Override
    public RequestSystem byCode(String code) {
        BusinessSystem system = systems.findByCode(code)
                .orElseThrow(() -> new BusinessAccessException(Reason.SYSTEM_NOT_FOUND));
        return new RequestSystem(system.getId(), system.getCode());
    }

    @Override
    public RequestSystem byId(Long id) {
        BusinessSystem system = system(id);
        return new RequestSystem(system.getId(), system.getCode());
    }

    @Override
    public void require(Long actor, RequestSystem system, String permission) {
        authorization.requireBusinessPermission(actor, system(system.id()), permission);
    }

    @Override
    public List<String> allowedActions(Long actor, RequestSystem system) {
        return authorization.effectivePermissions(actor, system(system.id())).stream().map(Permission::getCode)
                .filter(BusinessRequestService.REQUEST_ACTIONS::containsKey).toList();
    }

    private BusinessSystem system(Long id) {
        return systems.findById(id).orElseThrow(() -> new BusinessAccessException(Reason.SYSTEM_NOT_FOUND));
    }
}
