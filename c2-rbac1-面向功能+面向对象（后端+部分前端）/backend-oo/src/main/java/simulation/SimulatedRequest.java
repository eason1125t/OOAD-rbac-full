// 申请的 JPA 映射，领域行为由 simulation.domain.BusinessRequest 承担。
package simulation;

import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.LocalDateTime;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity
@Table(name = "simulated_request")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class SimulatedRequest {
    public static final String PENDING = simulation.domain.BusinessRequest.PENDING;
    public static final String APPROVED = simulation.domain.BusinessRequest.APPROVED;
    public static final String WITHDRAWN = simulation.domain.BusinessRequest.WITHDRAWN;
    public static final String REJECTED = simulation.domain.BusinessRequest.REJECTED;

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    private Long systemId;
    private Long applicantId;
    private String title;
    private String status;
    private LocalDateTime deletedAt;

    /** 沿用未指定申请人的演示与历史记录形式。 */
    public SimulatedRequest(Long systemId, String title) {
        this(systemId, null, title);
    }

    public SimulatedRequest(Long systemId, Long applicantId, String title) {
        this.systemId = systemId;
        this.applicantId = applicantId;
        this.title = title.strip();
        status = PENDING;
    }

    /** 由仓储适配器同步经过领域校验的可变状态，身份和归属保持原值。 */
    public void apply(simulation.domain.BusinessRequest request) {
        title = request.getTitle();
        status = request.getStatus();
        deletedAt = request.getDeletedAt();
    }
}