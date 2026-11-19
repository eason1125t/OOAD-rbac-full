// 申请记录的 JPA 映射，状态与所有权规则由面向功能版 Service 校验。
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
import lombok.Setter;

@Entity
@Table(name = "simulated_request")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class SimulatedRequest {
    public static final String PENDING = "PENDING";
    public static final String APPROVED = "APPROVED";
    public static final String WITHDRAWN = "WITHDRAWN";
    public static final String REJECTED = "REJECTED";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    private Long systemId;
    private Long applicantId;
    @Setter(AccessLevel.PACKAGE)
    private String title;
    @Setter(AccessLevel.PACKAGE)
    private String status;
    @Setter(AccessLevel.PACKAGE)
    private LocalDateTime deletedAt;

    public SimulatedRequest(Long systemId, String title) {
        this(systemId, null, title);
    }

    public SimulatedRequest(Long systemId, Long applicantId, String title) {
        this.systemId = systemId;
        this.applicantId = applicantId;
        this.title = title.strip();
        status = PENDING;
    }
}
