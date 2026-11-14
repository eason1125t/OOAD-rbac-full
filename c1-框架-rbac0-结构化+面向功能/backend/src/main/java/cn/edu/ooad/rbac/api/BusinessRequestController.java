package cn.edu.ooad.rbac.api;

import cn.edu.ooad.rbac.service.BusinessRequestService;
import cn.edu.ooad.rbac.service.BusinessRequestService.RequestPage;
import cn.edu.ooad.rbac.service.BusinessRequestService.RequestSummary;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** 提供系统申请的分页和审批 HTTP 接口，权限与状态由业务 Service 核验。 */
@RestController
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
@RequiredArgsConstructor
public class BusinessRequestController {
    private final BusinessRequestService requests;

    /** 供业务端展示一条申请。 */
    public record RequestSummaryVO(Long id, String title, String status) {
        /** 从用例结果组装公开字段。 */
        static RequestSummaryVO from(RequestSummary item) {
            return new RequestSummaryVO(item.id(), item.title(), item.status());
        }
    }

    /** 申请列表的 1 基分页响应。 */
    public record RequestPageVO(List<RequestSummaryVO> items, int page, int pageSize, long total) {
    }

    /** 分别向业务端报告鉴权结果和实际执行结果。 */
    public record ApprovalResultVO(String authorization, String execution, Long requestId, String status) {
    }

    /** 检查分页入参，委托查询当前身份可进入的目标系统。 */
    @GetMapping("/api/business-systems/{systemCode}/requests")
    public ResponseEntity<RequestPageVO> list(@AuthenticationPrincipal Long employeeId,
            @PathVariable @Size(min = 1, max = 64) String systemCode,
            @RequestParam(defaultValue = "1") @Min(1) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int pageSize) {
        RequestPage result = requests.list(employeeId, systemCode, page, pageSize);
        List<RequestSummaryVO> items = result.items().stream().map(RequestSummaryVO::from).toList();
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(new RequestPageVO(items, page, pageSize, result.total()));
    }

    /** 事务提交成功后才返回已审批。 */
    @PostMapping("/api/requests/{requestId}/approve")
    public ResponseEntity<ApprovalResultVO> approve(@AuthenticationPrincipal Long employeeId,
            @PathVariable @Min(1) Long requestId) {
        requests.approve(employeeId, requestId);
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(new ApprovalResultVO("GRANTED", "SUCCEEDED", requestId, "APPROVED"));
    }

}
