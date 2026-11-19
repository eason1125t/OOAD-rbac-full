// 通过业务标识查询申请系统，应用层不接触 JPA 系统实体。
package simulation.port;

import simulation.domain.RequestSystem;

/** 系统缺失由实现转换为既有业务错误。 */
public interface RequestSystems {
    RequestSystem byCode(String code);
    RequestSystem byId(Long id);
}
