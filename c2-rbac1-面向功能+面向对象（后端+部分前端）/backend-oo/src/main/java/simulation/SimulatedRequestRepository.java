// 申请仓储，按系统读取未删除记录，写入前锁定目标申请。
package simulation;

import jakarta.persistence.LockModeType;
import java.util.Optional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;

public interface SimulatedRequestRepository extends JpaRepository<SimulatedRequest, Long> {
    Page<SimulatedRequest> findBySystemIdAndDeletedAtIsNullOrderByIdAsc(Long systemId, Pageable pageable);

    boolean existsBySystemId(Long systemId);

    long countBySystemIdAndDeletedAtIsNull(Long systemId);

    Optional<SimulatedRequest> findByIdAndDeletedAtIsNull(Long id);

    /** 同一个申请的状态修改、审批、驳回和撤回在该行上串行化。 */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    Optional<SimulatedRequest> findLockedByIdAndDeletedAtIsNull(Long id);
}
