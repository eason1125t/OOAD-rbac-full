// 申请应用服务，协调实时授权、仓储事务和操作日志，状态规则由申请实体执行。
package simulation;

import cn.edu.xmu.clonefactory.CopyFrom;
import cn.edu.xmu.clonefactory.util.CloneFactory;
import contract.BusinessAccessException;
import contract.BusinessAccessException.Reason;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import lombok.RequiredArgsConstructor;
import lombok.Getter;
import lombok.Setter;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import simulation.domain.BusinessRequest;
import simulation.domain.RequestSystem;
import simulation.port.RequestAccess;
import simulation.port.RequestRepository;
import simulation.port.RequestSystems;
import simulation.BusinessRequestAudit.Action;

@Service
@RequiredArgsConstructor
public class BusinessRequestService {
    public static final String READ_PERMISSION = "request:read";
    public static final String APPROVE_PERMISSION = "request:approve";
    public static final String CREATE_PERMISSION = "request:create";
    public static final String UPDATE_PERMISSION = "request:update";
    public static final String WITHDRAW_PERMISSION = "request:withdraw";
    public static final String REJECT_PERMISSION = "request:reject";
    public static final String DELETE_PERMISSION = "request:delete";
    public static final Map<String, String> REQUEST_ACTIONS = Map.of(
            CREATE_PERMISSION, "发起申请", UPDATE_PERMISSION, "修改申请",
            WITHDRAW_PERMISSION, "撤回申请", APPROVE_PERMISSION, "审批申请",
            REJECT_PERMISSION, "驳回申请", DELETE_PERMISSION, "删除已驳回申请");

    private final RequestSystems systems;
    private final RequestRepository requests;
    private final RequestAccess authorization;

    /** 单一来源字段由注解处理器生成映射。 */
    @Getter
    @Setter
    @CopyFrom(BusinessRequest.class)
    public static class RequestSummary {
        private Long id;
        private String title;
        private String status;
    }

    public record RequestPage(List<RequestSummary> items, long total, List<String> allowedActions) {
    }

    /** 详情只暴露申请公开字段。 */
    @Getter
    @Setter
    @CopyFrom(BusinessRequest.class)
    public static class RequestDetail {
        private Long id;
        private String title;
        private String status;
        private Long applicantId;
    }

    /** 列表和总数采用相同的系统及删除筛选条件。 */
    @Transactional(readOnly = true)
    public RequestPage list(Long employeeId, String systemCode, int page, int pageSize) {
        return BusinessRequestAudit.record(Action.LIST, employeeId, null, target -> {
            RequestSystem system = systems.byCode(systemCode);
            target.identifySystem(system.id());
            authorization.require(employeeId, system, READ_PERMISSION);
            List<String> allowed = authorization.allowedActions(employeeId, system);
            RequestRepository.Slice result = requests.list(system.id(), page, pageSize);
            List<RequestSummary> items = result.items().stream()
                    .map(row -> CloneFactory.copy(new RequestSummary(), row)).toList();
            return new RequestPage(items, result.total(), allowed);
        });
    }

    @Transactional
    public RequestDetail create(Long employeeId, String systemCode, String title) {
        return BusinessRequestAudit.record(Action.CREATE, employeeId, null, target -> {
            RequestSystem system = systems.byCode(systemCode);
            target.identifySystem(system.id());
            authorization.require(employeeId, system, CREATE_PERMISSION);
            BusinessRequest row = requests.save(BusinessRequest.create(system.id(), employeeId, title));
            target.identifyRequest(row.getId());
            return detailOf(row);
        });
    }

    @Transactional(readOnly = true)
    public RequestDetail detail(Long employeeId, Long requestId) {
        return BusinessRequestAudit.record(Action.DETAIL, employeeId, requestId, target -> {
            BusinessRequest row = requests.find(requestId)
                    .orElseThrow(() -> new BusinessAccessException(Reason.REQUEST_NOT_FOUND));
            target.identifySystem(row.getSystemId());
            authorization.require(employeeId, systems.byId(row.getSystemId()), READ_PERMISSION);
            return detailOf(row);
        });
    }

    @Transactional
    public RequestDetail update(Long employeeId, Long requestId, String title) {
        return change(Action.UPDATE, employeeId, requestId, UPDATE_PERMISSION, row -> row.rename(employeeId, title));
    }

    @Transactional
    public RequestDetail withdraw(Long employeeId, Long requestId) {
        return change(Action.WITHDRAW, employeeId, requestId, WITHDRAW_PERMISSION, row -> row.withdraw(employeeId));
    }

    @Transactional
    public void approve(Long employeeId, Long requestId) {
        change(Action.APPROVE, employeeId, requestId, APPROVE_PERMISSION, BusinessRequest::approve);
    }

    @Transactional
    public RequestDetail reject(Long employeeId, Long requestId) {
        return change(Action.REJECT, employeeId, requestId, REJECT_PERMISSION, BusinessRequest::reject);
    }

    @Transactional
    public void delete(Long employeeId, Long requestId) {
        change(Action.DELETE, employeeId, requestId, DELETE_PERMISSION,
                row -> row.delete(employeeId, LocalDateTime.now(ZoneOffset.UTC)));
    }

    /** 先锁定当前申请，再根据系统与权限执行领域操作。 */
    private RequestDetail change(Action action, Long employeeId, Long requestId, String permission,
                                 Consumer<BusinessRequest> mutation) {
        return BusinessRequestAudit.record(action, employeeId, requestId, target -> {
            BusinessRequest row = requests.lock(requestId)
                    .orElseThrow(() -> new BusinessAccessException(Reason.REQUEST_NOT_FOUND));
            target.identifySystem(row.getSystemId());
            RequestSystem system = systems.byId(row.getSystemId());
            authorization.require(employeeId, system, READ_PERMISSION);
            authorization.require(employeeId, system, permission);
            mutation.accept(row);
            requests.save(row);
            return detailOf(row);
        });
    }

    private static RequestDetail detailOf(BusinessRequest row) {
        return CloneFactory.copy(new RequestDetail(), row);
    }
}
