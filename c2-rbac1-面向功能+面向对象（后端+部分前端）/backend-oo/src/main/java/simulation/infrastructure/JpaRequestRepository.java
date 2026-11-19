// 将申请仓储端口接到 Spring Data JPA，保留行锁和事务内持久化。
package simulation.infrastructure;

import contract.BusinessAccessException;
import contract.BusinessAccessException.Reason;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Repository;
import simulation.SimulatedRequest;
import simulation.SimulatedRequestRepository;
import simulation.domain.BusinessRequest;
import simulation.port.RequestRepository;

/** 查询和锁定时将 JPA 实体还原为独立领域对象。 */
@Repository
@RequiredArgsConstructor
public class JpaRequestRepository implements RequestRepository {
    private final SimulatedRequestRepository requests;

    @Override
    public Slice list(Long systemId, int page, int pageSize) {
        PageRequest pagination = PageRequest.of(page - 1, pageSize);
        if (pagination.getOffset() > Integer.MAX_VALUE) {
            return new Slice(java.util.List.of(), requests.countBySystemIdAndDeletedAtIsNull(systemId));
        }
        Page<SimulatedRequest> result = requests.findBySystemIdAndDeletedAtIsNullOrderByIdAsc(systemId, pagination);
        return new Slice(result.stream().map(JpaRequestRepository::domain).toList(), result.getTotalElements());
    }

    @Override
    public Optional<BusinessRequest> find(Long id) {
        return requests.findByIdAndDeletedAtIsNull(id).map(JpaRequestRepository::domain);
    }

    @Override
    public Optional<BusinessRequest> lock(Long id) {
        return requests.findLockedByIdAndDeletedAtIsNull(id).map(JpaRequestRepository::domain);
    }

    /** 更新仍使用锁定后的托管实体，领域校验通过后才复制变更字段。 */
    @Override
    public BusinessRequest save(BusinessRequest request) {
        SimulatedRequest row = request.getId() == null
                ? new SimulatedRequest(request.getSystemId(), request.getApplicantId(), request.getTitle())
                : requests.findById(request.getId())
                    .orElseThrow(() -> new BusinessAccessException(Reason.REQUEST_NOT_FOUND));
        row.apply(request);
        return domain(requests.save(row));
    }

    private static BusinessRequest domain(SimulatedRequest row) {
        return new BusinessRequest(row.getId(), row.getSystemId(), row.getApplicantId(),
                row.getTitle(), row.getStatus(), row.getDeletedAt());
    }
}
