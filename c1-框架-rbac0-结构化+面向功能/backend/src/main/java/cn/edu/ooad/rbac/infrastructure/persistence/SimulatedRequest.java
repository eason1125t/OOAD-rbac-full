package cn.edu.ooad.rbac.infrastructure.persistence;

import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/** 映射业务模拟申请，供业务列表和审批读取系统归属与状态。 */
@Entity
@Table(name = "simulated_request")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class SimulatedRequest {
    public static final String PENDING = "PENDING";
    public static final String APPROVED = "APPROVED";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    private Long systemId;
    private String title;
    private String status;

    /** 演示申请统一从待审批状态开始。 */
    public SimulatedRequest(Long systemId, String title) {
        this.systemId = systemId;
        this.title = title;
        this.status = PENDING;
    }
}
