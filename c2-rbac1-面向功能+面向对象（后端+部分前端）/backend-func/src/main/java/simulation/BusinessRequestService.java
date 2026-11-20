// 文件职责：编排申请读取和写入的实时授权、事务与结果映射。
// 协作关系：由申请 Controller 调用，并通过 BusinessRequestAudit 记录事务结果。
package simulation;

import authorization.AuthorizationService;
import cn.edu.xmu.clonefactory.CopyFrom;
import cn.edu.xmu.clonefactory.util.CloneFactory;
import contract.BusinessAccessException.Reason;
import contract.BusinessAccessException;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.Setter;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import rolepermission.Permission;
import simulation.BusinessRequestAudit.Action;

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

    /** 同一系统的分页结果及当前可用写动作；逐行所有权和状态由写入口复核。 */
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

    /** 按系统发起待审批申请，申请人身份由可信会话确定。 */
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

    /** 读取未删除申请并校验所属系统的查看权。 */
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

    /** 在查看权和修改权通过后，修改本人待审批申请的标题。 */
    @Transactional
    public RequestDetail update(Long employeeId, Long requestId, String title) {
        return change(Action.UPDATE, employeeId, requestId, UPDATE_PERMISSION, title);
    }

    /** 在实时授权后撤回本人待审批申请，原单保留且不可重提。 */
    @Transactional
    public RequestDetail withdraw(Long employeeId, Long requestId) {
        return change(Action.WITHDRAW, employeeId, requestId, WITHDRAW_PERMISSION, null);
    }

    /** 执行实时查看与审批授权，重复审批保留兼容错误码。 */
    @Transactional
    public void approve(Long employeeId, Long requestId) {
        change(Action.APPROVE, employeeId, requestId, APPROVE_PERMISSION, null);
    }

    /** 在查看权和驳回权通过后将待审批申请置为已驳回。 */
    @Transactional
    public RequestDetail reject(Long employeeId, Long requestId) {
        return change(Action.REJECT, employeeId, requestId, REJECT_PERMISSION, null);
    }

    /** 仅逻辑删除本人已驳回申请，后续列表与详情隐藏记录。 */
    @Transactional
    public void delete(Long employeeId, Long requestId) {
        change(Action.DELETE, employeeId, requestId, DELETE_PERMISSION, null);
    }

    /** 先锁定当前申请并检查查看权及动作权，再由过程式规则修改托管字段。 */
    private RequestDetail change(Action action, Long employeeId, Long requestId, String permission,
                                 String title) {
        return BusinessRequestAudit.record(action, employeeId, requestId, target -> {
            SimulatedRequest row = requests.findLockedByIdAndDeletedAtIsNull(requestId)
                    .orElseThrow(() -> new BusinessAccessException(Reason.REQUEST_NOT_FOUND));
            target.identifySystem(row.getSystemId());
            BusinessSystem system = systemFor(row);
            authorization.requireBusinessPermission(employeeId, system, READ_PERMISSION);
            authorization.requireBusinessPermission(employeeId, system, permission);
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

    /** 仅允许明确登记的申请人执行本人专属动作，历史空申请人不能匹配。 */
    private static void requireApplicant(SimulatedRequest row, Long employeeId) {
        if (!employeeId.equals(row.getApplicantId())) {
            throw new BusinessAccessException(Reason.PERMISSION_DENIED, "无权执行该申请操作");
        }
    }

    /** 校验当前申请状态，拒绝非法迁移而不修改原记录。 */
    private static void requireState(SimulatedRequest row, String expected) {
        if (!expected.equals(row.getStatus())) {
            throw new BusinessAccessException(Reason.REQUEST_STATE_CONFLICT);
        }
    }

    /** 以 URL 系统代码解析持久化系统，不存在时返回系统错误。 */
    private BusinessSystem systemByCode(String code) {
        return systems.findByCode(code).orElseThrow(() -> new BusinessAccessException(Reason.SYSTEM_NOT_FOUND));
    }

    /** 从申请记录解析所属系统，避免客户端改变后续鉴权目标。 */
    private BusinessSystem systemFor(SimulatedRequest row) {
        return systems.findById(row.getSystemId())
                .orElseThrow(() -> new BusinessAccessException(Reason.SYSTEM_NOT_FOUND));
    }

    /** 将当前申请的公开字段映射为服务结果，不暴露持久化实体。 */
    private static RequestDetail detailOf(SimulatedRequest row) {
        return CloneFactory.copy(new RequestDetail(), row);
    }
}
