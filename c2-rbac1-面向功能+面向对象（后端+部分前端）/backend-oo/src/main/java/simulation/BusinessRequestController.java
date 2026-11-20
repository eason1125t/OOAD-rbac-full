// 文件职责：适配申请列表、详情及生命周期操作的 HTTP 请求。
// 协作关系：校验 RequestTitleDTO，调用 BusinessRequestService 后映射公开 VO。
package simulation;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
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
import simulation.vo.ApprovalResultVO;
import simulation.vo.RequestDetailVO;
import simulation.vo.RequestPageVO;
import simulation.vo.RequestSummaryVO;

/** 提供申请分页、详情和生命周期 HTTP 接口，实时授权及状态规则由 Service 核验。 */
@RestController
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
@RequiredArgsConstructor
public class BusinessRequestController {
    private final BusinessRequestService requests;

    /** 校验分页参数，委托 Service 同时检查系统准入和申请查看权。 */
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

    /** 按系统发起待审批申请，申请人身份由可信会话确定。 */
    @PostMapping("/api/business-systems/{systemCode}/requests")
    public ResponseEntity<RequestDetailVO> create(@AuthenticationPrincipal Long employeeId,
            @PathVariable @Size(min = 1, max = 64) String systemCode, @Valid @RequestBody RequestTitleDTO input) {
        return detailResponse(HttpStatus.CREATED, requests.create(employeeId, systemCode, input.getTitle()));
    }

    /** 读取未删除申请并校验所属系统的查看权。 */
    @GetMapping("/api/requests/{requestId}")
    public ResponseEntity<RequestDetailVO> detail(@AuthenticationPrincipal Long employeeId,
            @PathVariable @Min(1) Long requestId) {
        return detailResponse(HttpStatus.OK, requests.detail(employeeId, requestId));
    }

    /** 在查看权和修改权通过后，修改本人待审批申请的标题。 */
    @PatchMapping("/api/requests/{requestId}")
    public ResponseEntity<RequestDetailVO> update(@AuthenticationPrincipal Long employeeId,
            @PathVariable @Min(1) Long requestId, @Valid @RequestBody RequestTitleDTO input) {
        return detailResponse(HttpStatus.OK, requests.update(employeeId, requestId, input.getTitle()));
    }

    /** 在实时授权后撤回本人待审批申请，原单保留且不可重提。 */
    @PostMapping("/api/requests/{requestId}/withdraw")
    public ResponseEntity<RequestDetailVO> withdraw(@AuthenticationPrincipal Long employeeId,
            @PathVariable @Min(1) Long requestId) {
        return detailResponse(HttpStatus.OK, requests.withdraw(employeeId, requestId));
    }

    /** 在查看权和驳回权通过后将待审批申请置为已驳回。 */
    @PostMapping("/api/requests/{requestId}/reject")
    public ResponseEntity<RequestDetailVO> reject(@AuthenticationPrincipal Long employeeId,
            @PathVariable @Min(1) Long requestId) {
        return detailResponse(HttpStatus.OK, requests.reject(employeeId, requestId));
    }

    /** 仅逻辑删除本人已驳回申请，后续列表与详情隐藏记录。 */
    @DeleteMapping("/api/requests/{requestId}")
    public ResponseEntity<Void> delete(@AuthenticationPrincipal Long employeeId, @PathVariable @Min(1) Long requestId) {
        requests.delete(employeeId, requestId);
        return ResponseEntity.noContent().cacheControl(CacheControl.noStore()).build();
    }

    /** 以 CloneFactory 映射公开详情，并禁止浏览器缓存敏感响应。 */
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
