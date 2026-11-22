// 文件职责：编排申请读取和写入的实时授权、事务与结果映射。
// 协作关系：由申请 Controller 调用，并通过 BusinessRequestAudit 记录事务结果。
package simulation;

import cn.edu.xmu.clonefactory.CopyFrom;
import cn.edu.xmu.clonefactory.util.CloneFactory;
import contract.BusinessAccessException.Reason;
import contract.BusinessAccessException;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.Setter;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import simulation.BusinessRequestAudit.Action;
import simulation.domain.BusinessRequest;
import simulation.domain.RequestSystem;
import simulation.port.RequestAccess;
import simulation.port.RequestRepository;
import simulation.port.RequestSystems;

/** 编排申请读取和写入的实时授权、事务与结果映射，由申请 Controller 调用，并通过 BusinessRequestAudit 记录事务结果。 */
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

    /** 同一系统的分页结果及当前可用写动作；逐行所有权和状态由写入口复核。 */
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
            requireOperation(employeeId, system, RequestOperation.LIST);
            List<String> allowed = authorization.allowedActions(employeeId, system);
            RequestRepository.Slice result = requests.list(system.id(), page, pageSize);
            List<RequestSummary> items = result.items().stream()
                    .map(row -> CloneFactory.copy(new RequestSummary(), row)).toList();
            return new RequestPage(items, result.total(), allowed);
        });
    }

    /** 按系统发起待审批申请，申请人身份由可信会话确定。 */
    @Transactional
    public RequestDetail create(Long employeeId, String systemCode, String title) {
        return BusinessRequestAudit.record(Action.CREATE, employeeId, null, target -> {
            RequestSystem system = systems.byCode(systemCode);
            target.identifySystem(system.id());
            requireOperation(employeeId, system, RequestOperation.CREATE);
            BusinessRequest row = requests.save(BusinessRequest.create(system.id(), employeeId, title));
            target.identifyRequest(row.getId());
            return detailOf(row);
        });
    }

    /** 读取未删除申请并校验所属系统的查看权。 */
    @Transactional(readOnly = true)
    public RequestDetail detail(Long employeeId, Long requestId) {
        return BusinessRequestAudit.record(Action.DETAIL, employeeId, requestId, target -> {
            BusinessRequest row = requests.find(requestId)
                    .orElseThrow(() -> new BusinessAccessException(Reason.REQUEST_NOT_FOUND));
            target.identifySystem(row.getSystemId());
            requireOperation(employeeId, systems.byId(row.getSystemId()), RequestOperation.DETAIL);
            return detailOf(row);
        });
    }

    /** 在查看权和修改权通过后，修改本人待审批申请的标题。 */
    @Transactional
    public RequestDetail update(Long employeeId, Long requestId, String title) {
        return change(Action.UPDATE, employeeId, requestId, RequestOperation.UPDATE, row -> row.rename(employeeId, title));
    }

    /** 在实时授权后撤回本人待审批申请，原单保留且不可重提。 */
    @Transactional
    public RequestDetail withdraw(Long employeeId, Long requestId) {
        return change(Action.WITHDRAW, employeeId, requestId, RequestOperation.WITHDRAW, row -> row.withdraw(employeeId));
    }

    /** 执行实时查看与审批授权，重复审批保留兼容错误码。 */
    @Transactional
    public void approve(Long employeeId, Long requestId) {
        change(Action.APPROVE, employeeId, requestId, RequestOperation.APPROVE, BusinessRequest::approve);
    }

    /** 在查看权和驳回权通过后将待审批申请置为已驳回。 */
    @Transactional
    public RequestDetail reject(Long employeeId, Long requestId) {
        return change(Action.REJECT, employeeId, requestId, RequestOperation.REJECT, BusinessRequest::reject);
    }

    /** 仅逻辑删除本人已驳回申请，后续列表与详情隐藏记录。 */
    @Transactional
    public void delete(Long employeeId, Long requestId) {
        change(Action.DELETE, employeeId, requestId, RequestOperation.DELETE,
                row -> row.delete(employeeId, LocalDateTime.now(ZoneOffset.UTC)));
    }

    /** 先锁定当前申请，再根据系统与权限执行领域操作。 */
    private void requireOperation(Long employeeId, RequestSystem system, RequestOperation operation) {
        // 有序ALL保留先查看、再动作的既有行为；目标操作始终重新读取实时权限。
        for (String code : operation.requiredPermissions()) {
            authorization.require(employeeId, system, code);
        }
    }

    /** 锁定申请后复核系统级权限，再执行本版的对象和状态规则。 */
    private RequestDetail change(Action action, Long employeeId, Long requestId, RequestOperation operation,
                                 Consumer<BusinessRequest> mutation) {
        return BusinessRequestAudit.record(action, employeeId, requestId, target -> {
            BusinessRequest row = requests.lock(requestId)
                    .orElseThrow(() -> new BusinessAccessException(Reason.REQUEST_NOT_FOUND));
            target.identifySystem(row.getSystemId());
            RequestSystem system = systems.byId(row.getSystemId());
            requireOperation(employeeId, system, operation);
            mutation.accept(row);
            requests.save(row);
            return detailOf(row);
        });
    }

    /** 将当前申请的公开字段映射为服务结果，不暴露持久化实体。 */
    private static RequestDetail detailOf(BusinessRequest row) {
        return CloneFactory.copy(new RequestDetail(), row);
    }
}
