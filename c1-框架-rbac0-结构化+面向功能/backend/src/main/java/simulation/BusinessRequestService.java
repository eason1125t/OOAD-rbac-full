package simulation;

import authorization.AuthorizationService;
import contract.BusinessAccessException;
import contract.BusinessAccessException.Reason;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 组织模拟申请的查询和审批，状态更新与鉴权处于同一用例事务。 */
@Service
@RequiredArgsConstructor
public class BusinessRequestService {
    public static final String APPROVE_PERMISSION = "request:approve";

    private final BusinessSystemRepository systems;
    private final SimulatedRequestRepository requests;
    private final AuthorizationService authorization;

    /** 对外申请所需的业务字段。 */
    public record RequestSummary(Long id, String title, String status) {
    }

    /** 与系统范围绑定的申请分页。 */
    public record RequestPage(List<RequestSummary> items, long total) {
    }

    /** 先检查系统准入，再查询该系统的申请和总数。 */
    @Transactional(readOnly = true)
    public RequestPage list(Long employeeId, String systemCode, int page, int pageSize) {
        BusinessSystem system = systems.findByCode(systemCode)
                .orElseThrow(() -> new BusinessAccessException(Reason.SYSTEM_NOT_FOUND));
        authorization.requireSystemAccess(employeeId, system);
        PageRequest pagination = PageRequest.of(page - 1, pageSize);
        if (pagination.getOffset() > Integer.MAX_VALUE) {
            return new RequestPage(List.of(), requests.countBySystemId(system.getId()));
        }
        Page<SimulatedRequest> result = requests.findBySystemIdOrderByIdAsc(system.getId(), pagination);
        List<RequestSummary> items = result.stream()
                .map(request -> new RequestSummary(request.getId(), request.getTitle(), request.getStatus()))
                .toList();
        return new RequestPage(items, result.getTotalElements());
    }

    /** 由申请记录决定目标系统，先判权，再尝试一次待审批状态转换。 */
    @Transactional
    public void approve(Long employeeId, Long requestId) {
        SimulatedRequest request = requests.findById(requestId)
                .orElseThrow(() -> new BusinessAccessException(Reason.REQUEST_NOT_FOUND));
        BusinessSystem system = systems.findById(request.getSystemId())
                .orElseThrow(() -> new BusinessAccessException(Reason.SYSTEM_NOT_FOUND));
        authorization.requireBusinessPermission(employeeId, system, APPROVE_PERMISSION);
        if (requests.updateStatusIfPending(requestId, SimulatedRequest.PENDING, SimulatedRequest.APPROVED) == 0) {
            throw new BusinessAccessException(Reason.REQUEST_ALREADY_APPROVED);
        }
    }
}
