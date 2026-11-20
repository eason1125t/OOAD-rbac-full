// 文件职责：适配申请系统查询与实时授权两个端口。
// 协作关系：委托 BusinessSystemRepository 和 AuthorizationService，隔离应用服务的基础设施依赖。
package simulation.infrastructure;

import authorization.AuthorizationService;
import contract.BusinessAccessException.Reason;
import contract.BusinessAccessException;
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

    /** 按业务代码解析申请系统，缺失时抛出系统不存在错误。 */
    @Override
    public RequestSystem byCode(String code) {
        BusinessSystem system = systems.findByCode(code)
                .orElseThrow(() -> new BusinessAccessException(Reason.SYSTEM_NOT_FOUND));
        return new RequestSystem(system.getId());
    }

    /** 按持久化系统 ID 解析申请归属，不接受客户端覆盖所属系统。 */
    @Override
    public RequestSystem byId(Long id) {
        BusinessSystem system = system(id);
        return new RequestSystem(system.getId());
    }

    /** 按系统及动作实时授权，无法确认授权时拒绝执行业务。 */
    @Override
    public void require(Long actor, RequestSystem system, String permission) {
        authorization.requireBusinessPermission(actor, system(system.id()), permission);
    }

    /** 返回当前系统中实时获准的申请写动作，查看权另行校验。 */
    @Override
    public List<String> allowedActions(Long actor, RequestSystem system) {
        return authorization.effectivePermissions(actor, system(system.id())).stream().map(Permission::getCode)
                .filter(BusinessRequestService.REQUEST_ACTIONS::containsKey).toList();
    }

    /** 按 ID 读取授权所需的 JPA 系统实体，缺失时转换为系统错误。 */
    private BusinessSystem system(Long id) {
        return systems.findById(id).orElseThrow(() -> new BusinessAccessException(Reason.SYSTEM_NOT_FOUND));
    }
}
