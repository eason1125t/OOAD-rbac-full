// 文件职责：分页读取未删除申请并锁定状态变更目标。
// 协作关系：由功能版 Service 或对象版 JPA 适配器在业务事务内调用。
package simulation;

import jakarta.persistence.LockModeType;
import java.util.Optional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;

/** 分页读取未删除申请并锁定状态变更目标，由功能版 Service 或对象版 JPA 适配器在业务事务内调用。 */
public interface SimulatedRequestRepository extends JpaRepository<SimulatedRequest, Long> {
    /** 只分页返回指定系统未逻辑删除的申请，按 ID 稳定排序。 */
    Page<SimulatedRequest> findBySystemIdAndDeletedAtIsNullOrderByIdAsc(Long systemId, Pageable pageable);

    /** 判断系统是否已有申请，避免演示初始化重复生成样例。 */
    boolean existsBySystemId(Long systemId);

    /** 使用与列表一致的系统和删除条件统计总数。 */
    long countBySystemIdAndDeletedAtIsNull(Long systemId);

    /** 读取未删除的申请详情，逻辑删除记录按不存在处理。 */
    Optional<SimulatedRequest> findByIdAndDeletedAtIsNull(Long id);

    /** 同一个申请的状态修改、审批、驳回和撤回在该行上串行化。 */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    Optional<SimulatedRequest> findLockedByIdAndDeletedAtIsNull(Long id);
}
