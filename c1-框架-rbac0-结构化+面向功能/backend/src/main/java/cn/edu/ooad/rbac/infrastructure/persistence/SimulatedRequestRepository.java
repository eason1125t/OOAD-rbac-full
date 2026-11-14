package cn.edu.ooad.rbac.infrastructure.persistence;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/** 管理模拟申请的分页读取和原子状态更新。 */
public interface SimulatedRequestRepository extends JpaRepository<SimulatedRequest, Long> {
    /** 分页和计数均限定为目标系统。 */
    Page<SimulatedRequest> findBySystemIdOrderByIdAsc(Long systemId, Pageable pageable);

    /** 用于演示初始化，保留既有申请及其状态。 */
    boolean existsBySystemId(Long systemId);

    /** 用于计算分页总数，支持超出 JPA 偏移上限的空页。 */
    long countBySystemId(Long systemId);

    /** 并发审批时只有仍为待审批状态的那次写入可以成功。 */
    @Modifying
    @Query("""
            update SimulatedRequest request set request.status = :approved
            where request.id = :id and request.status = :pending
            """)
    int updateStatusIfPending(@Param("id") Long id, @Param("pending") String pending,
                              @Param("approved") String approved);
}
