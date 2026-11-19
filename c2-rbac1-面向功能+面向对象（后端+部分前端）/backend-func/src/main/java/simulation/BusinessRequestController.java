// 将业务申请 HTTP 调用映射到应用服务，并保持 C1 审批响应结构。
package simulation;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import simulation.BusinessRequestService.RequestDetail;
import simulation.BusinessRequestService.RequestPage;
import simulation.dto.RequestTitleDTO;
import simulation.vo.RequestDetailVO;
import simulation.vo.ApprovalResultVO;
import simulation.vo.RequestPageVO;
import simulation.vo.RequestSummaryVO;

/** 提供系统申请的分页和审批 HTTP 接口，权限与状态由业务 Service 核验。 */
@RestController
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
@RequiredArgsConstructor
public class BusinessRequestController {
    private final BusinessRequestService requests;

    /** 检查分页入参，委托查询当前身份可进入的目标系统。 */
    @GetMapping("/api/business-systems/{systemCode}/requests")
    public ResponseEntity<RequestPageVO> list(@AuthenticationPrincipal Long employeeId,
            @PathVariable @Size(min = 1, max = 64) String systemCode,
            @RequestParam(defaultValue = "1") @Min(1) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int pageSize) {
        RequestPage result = requests.list(employeeId, systemCode, page, pageSize);
        List<RequestSummaryVO> items = result.items().stream().map(RequestSummaryVO::from).toList();
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(new RequestPageVO(items, page, pageSize, result.total(), result.allowedActions()));
    }

    @PostMapping("/api/business-systems/{systemCode}/requests")
    public ResponseEntity<RequestDetailVO> create(@AuthenticationPrincipal Long employeeId,
            @PathVariable @Size(min = 1, max = 64) String systemCode, @Valid @RequestBody RequestTitleDTO input) {
        return detailResponse(HttpStatus.CREATED, requests.create(employeeId, systemCode, input.getTitle()));
    }

    @GetMapping("/api/requests/{requestId}")
    public ResponseEntity<RequestDetailVO> detail(@AuthenticationPrincipal Long employeeId,
            @PathVariable @Min(1) Long requestId) {
        return detailResponse(HttpStatus.OK, requests.detail(employeeId, requestId));
    }

    @PatchMapping("/api/requests/{requestId}")
    public ResponseEntity<RequestDetailVO> update(@AuthenticationPrincipal Long employeeId,
            @PathVariable @Min(1) Long requestId, @Valid @RequestBody RequestTitleDTO input) {
        return detailResponse(HttpStatus.OK, requests.update(employeeId, requestId, input.getTitle()));
    }

    @PostMapping("/api/requests/{requestId}/withdraw")
    public ResponseEntity<RequestDetailVO> withdraw(@AuthenticationPrincipal Long employeeId,
            @PathVariable @Min(1) Long requestId) {
        return detailResponse(HttpStatus.OK, requests.withdraw(employeeId, requestId));
    }

    @PostMapping("/api/requests/{requestId}/reject")
    public ResponseEntity<RequestDetailVO> reject(@AuthenticationPrincipal Long employeeId,
            @PathVariable @Min(1) Long requestId) {
        return detailResponse(HttpStatus.OK, requests.reject(employeeId, requestId));
    }

    @DeleteMapping("/api/requests/{requestId}")
    public ResponseEntity<Void> delete(@AuthenticationPrincipal Long employeeId, @PathVariable @Min(1) Long requestId) {
        requests.delete(employeeId, requestId);
        return ResponseEntity.noContent().cacheControl(CacheControl.noStore()).build();
    }

    private static ResponseEntity<RequestDetailVO> detailResponse(HttpStatus status, RequestDetail result) {
        return ResponseEntity.status(status).cacheControl(CacheControl.noStore())
                .body(RequestDetailVO.from(result));
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
