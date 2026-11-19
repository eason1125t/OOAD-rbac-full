// 申请仓储端口，分页与锁定语义由持久化适配器实现。
package simulation.port;

import java.util.List;
import java.util.Optional;
import simulation.domain.BusinessRequest;

/** 在调用方的业务事务中读取、锁定和保存申请。 */
public interface RequestRepository {
    /** 同一系统的记录与总数使用一致的删除过滤。 */
    record Slice(List<BusinessRequest> items, long total) {
    }

    Slice list(Long systemId, int page, int pageSize);
    Optional<BusinessRequest> find(Long id);
    Optional<BusinessRequest> lock(Long id);
    BusinessRequest save(BusinessRequest request);
}
