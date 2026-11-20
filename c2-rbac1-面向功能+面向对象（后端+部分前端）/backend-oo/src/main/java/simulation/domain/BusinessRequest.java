// 文件职责：维护申请人的操作边界及申请生命周期规则。
// 协作关系：由对象版应用 Service 调用，由 JpaRequestRepository 还原及保存。
package simulation.domain;

import contract.BusinessAccessException.Reason;
import contract.BusinessAccessException;
import java.time.LocalDateTime;
import java.util.Objects;
import lombok.AllArgsConstructor;
import lombok.Getter;

/** 维护申请人的操作边界及申请生命周期。 */
@Getter
@AllArgsConstructor
public final class BusinessRequest {
    public static final String PENDING = "PENDING";
    public static final String APPROVED = "APPROVED";
    public static final String WITHDRAWN = "WITHDRAWN";
    public static final String REJECTED = "REJECTED";

    private Long id;
    private Long systemId;
    private Long applicantId;
    private String title;
    private String status;
    private LocalDateTime deletedAt;

    /** 创建待持久化的新申请，身份和系统由服务端提供。 */
    public static BusinessRequest create(Long systemId, Long applicantId, String title) {
        return new BusinessRequest(null, systemId, applicantId, title.strip(), PENDING, null);
    }

    /** 本人可以修改待审批申请。 */
    public void rename(Long actor, String title) {
        requireApplicant(actor);
        requirePending();
        this.title = title.strip();
    }

    /** 撤回是终态。 */
    public void withdraw(Long actor) {
        requireApplicant(actor);
        requirePending();
        status = WITHDRAWN;
    }

    /** 重复审批保留原接口的业务错误码。 */
    public void approve() {
        if (APPROVED.equals(status)) {
            throw new BusinessAccessException(Reason.REQUEST_ALREADY_APPROVED);
        }
        requirePending();
        status = APPROVED;
    }

    /** 仅待审批单可以驳回。 */
    public void reject() {
        requirePending();
        status = REJECTED;
    }

    /** 本人只可删除已驳回申请。 */
    public void delete(Long actor, LocalDateTime time) {
        requireApplicant(actor);
        if (!REJECTED.equals(status) || deletedAt != null) {
            throw new BusinessAccessException(Reason.REQUEST_STATE_CONFLICT);
        }
        deletedAt = time;
    }

    /** 旧数据没有申请人时，无法执行本人专属操作。 */
    private void requireApplicant(Long actor) {
        if (applicantId == null || !Objects.equals(applicantId, actor)) {
            throw new BusinessAccessException(Reason.PERMISSION_DENIED, "无权执行该申请操作");
        }
    }

    /** 仅允许未删除的待审批申请继续修改或转换状态。 */
    private void requirePending() {
        if (!PENDING.equals(status) || deletedAt != null) {
            throw new BusinessAccessException(Reason.REQUEST_STATE_CONFLICT);
        }
    }
}
