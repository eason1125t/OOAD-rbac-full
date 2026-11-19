// 按业务过程执行申请授权、状态校验、数据变更和日志记录。
package simulation;

import authorization.AuthorizationService;
import cn.edu.xmu.clonefactory.CopyFrom;
import cn.edu.xmu.clonefactory.util.CloneFactory;
import contract.BusinessAccessException;
import contract.BusinessAccessException.Reason;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.Getter;
import lombok.Setter;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import rolepermission.Permission;
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

    private final BusinessSystemRepository systems;
    private final SimulatedRequestRepository requests;
    private final AuthorizationService authorization;

    /** 单一来源字段由注解处理器生成映射。 */
    @Getter
    @Setter
    @CopyFrom(SimulatedRequest.class)
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
    @CopyFrom(SimulatedRequest.class)
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
            BusinessSystem system = systemByCode(systemCode);
            target.identifySystem(system.getId());
            authorization.requireBusinessPermission(employeeId, system, READ_PERMISSION);
            List<String> allowed = authorization.effectivePermissions(employeeId, system).stream()
                    .map(Permission::getCode).filter(REQUEST_ACTIONS::containsKey).toList();
            PageRequest pagination = PageRequest.of(page - 1, pageSize);
            if (pagination.getOffset() > Integer.MAX_VALUE) {
                return new RequestPage(List.of(), requests.countBySystemIdAndDeletedAtIsNull(system.getId()), allowed);
            }
            Page<SimulatedRequest> result = requests.findBySystemIdAndDeletedAtIsNullOrderByIdAsc(
                    system.getId(), pagination);
            List<RequestSummary> items = result.stream()
                    .map(row -> CloneFactory.copy(new RequestSummary(), row)).toList();
            return new RequestPage(items, result.getTotalElements(), allowed);
        });
    }

    @Transactional
    public RequestDetail create(Long employeeId, String systemCode, String title) {
        return BusinessRequestAudit.record(Action.CREATE, employeeId, null, target -> {
            BusinessSystem system = systemByCode(systemCode);
            target.identifySystem(system.getId());
            authorization.requireBusinessPermission(employeeId, system, CREATE_PERMISSION);
            SimulatedRequest row = requests.save(new SimulatedRequest(system.getId(), employeeId, title));
            target.identifyRequest(row.getId());
            return detailOf(row);
        });
    }

    @Transactional(readOnly = true)
    public RequestDetail detail(Long employeeId, Long requestId) {
        return BusinessRequestAudit.record(Action.DETAIL, employeeId, requestId, target -> {
            SimulatedRequest row = requests.findByIdAndDeletedAtIsNull(requestId)
                    .orElseThrow(() -> new BusinessAccessException(Reason.REQUEST_NOT_FOUND));
            target.identifySystem(row.getSystemId());
            authorization.requireBusinessPermission(employeeId, systemFor(row), READ_PERMISSION);
            return detailOf(row);
        });
    }

    @Transactional
    public RequestDetail update(Long employeeId, Long requestId, String title) {
        return change(Action.UPDATE, employeeId, requestId, UPDATE_PERMISSION, title);
    }

    @Transactional
    public RequestDetail withdraw(Long employeeId, Long requestId) {
        return change(Action.WITHDRAW, employeeId, requestId, WITHDRAW_PERMISSION, null);
    }

    @Transactional
    public void approve(Long employeeId, Long requestId) {
        change(Action.APPROVE, employeeId, requestId, APPROVE_PERMISSION, null);
    }

    @Transactional
    public RequestDetail reject(Long employeeId, Long requestId) {
        return change(Action.REJECT, employeeId, requestId, REJECT_PERMISSION, null);
    }

    @Transactional
    public void delete(Long employeeId, Long requestId) {
        change(Action.DELETE, employeeId, requestId, DELETE_PERMISSION, null);
    }

    /** 先锁定当前申请，再根据系统与权限执行领域操作。 */
    private RequestDetail change(Action action, Long employeeId, Long requestId, String permission,
                                 String title) {
        return BusinessRequestAudit.record(action, employeeId, requestId, target -> {
            SimulatedRequest row = requests.findLockedByIdAndDeletedAtIsNull(requestId)
                    .orElseThrow(() -> new BusinessAccessException(Reason.REQUEST_NOT_FOUND));
            target.identifySystem(row.getSystemId());
            authorization.requireBusinessPermission(employeeId, systemFor(row), READ_PERMISSION);
            authorization.requireBusinessPermission(employeeId, systemFor(row), permission);
            applyChange(action, employeeId, row, title);
            return detailOf(row);
        });
    }

    /** 状态和所有权在流程中检查，实体只映射持久化字段。 */
    private static void applyChange(Action action, Long employeeId, SimulatedRequest row, String title) {
        switch (action) {
            case UPDATE -> {
                requireApplicant(row, employeeId);
                requireState(row, SimulatedRequest.PENDING);
                row.setTitle(title.strip());
            }
            case WITHDRAW -> {
                requireApplicant(row, employeeId);
                requireState(row, SimulatedRequest.PENDING);
                row.setStatus(SimulatedRequest.WITHDRAWN);
            }
            case APPROVE -> {
                if (SimulatedRequest.APPROVED.equals(row.getStatus())) {
                    throw new BusinessAccessException(Reason.REQUEST_ALREADY_APPROVED);
                }
                requireState(row, SimulatedRequest.PENDING);
                row.setStatus(SimulatedRequest.APPROVED);
            }
            case REJECT -> {
                requireState(row, SimulatedRequest.PENDING);
                row.setStatus(SimulatedRequest.REJECTED);
            }
            case DELETE -> {
                requireApplicant(row, employeeId);
                requireState(row, SimulatedRequest.REJECTED);
                row.setDeletedAt(LocalDateTime.now(ZoneOffset.UTC));
            }
            default -> throw new IllegalArgumentException("不支持的写入动作");
        }
    }

    private static void requireApplicant(SimulatedRequest row, Long employeeId) {
        if (!employeeId.equals(row.getApplicantId())) {
            throw new BusinessAccessException(Reason.PERMISSION_DENIED, "无权执行该申请操作");
        }
    }

    private static void requireState(SimulatedRequest row, String expected) {
        if (!expected.equals(row.getStatus())) {
            throw new BusinessAccessException(Reason.REQUEST_STATE_CONFLICT);
        }
    }

    private BusinessSystem systemByCode(String code) {
        return systems.findByCode(code).orElseThrow(() -> new BusinessAccessException(Reason.SYSTEM_NOT_FOUND));
    }

    private BusinessSystem systemFor(SimulatedRequest row) {
        return systems.findById(row.getSystemId())
                .orElseThrow(() -> new BusinessAccessException(Reason.SYSTEM_NOT_FOUND));
    }

    private static RequestDetail detailOf(SimulatedRequest row) {
        return CloneFactory.copy(new RequestDetail(), row);
    }
}
