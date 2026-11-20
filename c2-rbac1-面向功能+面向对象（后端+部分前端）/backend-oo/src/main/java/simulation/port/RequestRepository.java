// 文件职责：定义申请分页、读取、加锁和保存的业务端口。
// 协作关系：供对象版应用 Service 使用，JpaRequestRepository 实现持久化。
package simulation.port;

import java.util.List;
import java.util.Optional;
import simulation.domain.BusinessRequest;

/** 在调用方的业务事务中读取、锁定和保存申请。 */
public interface RequestRepository {
    /** 同一系统的记录与总数使用一致的删除过滤。 */
    record Slice(List<BusinessRequest> items, long total) {
    }

    /** 读取指定系统的未删除记录和总数，页码从 1 开始。 */
    Slice list(Long systemId, int page, int pageSize);
    /** 读取未删除申请，不存在时返回空值。 */
    Optional<BusinessRequest> find(Long id);
    /** 在调用方事务中锁定未删除申请，供互斥状态转换使用。 */
    Optional<BusinessRequest> lock(Long id);
    /** 持久化领域状态并返回带数据库标识的申请。 */
    BusinessRequest save(BusinessRequest request);
}
