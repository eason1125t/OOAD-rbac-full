// 文件职责：定义按代码或 ID 解析申请所属系统的端口。
// 协作关系：由 RequestContextAdapter 适配业务系统仓储。
package simulation.port;

import simulation.domain.RequestSystem;

/** 系统缺失由实现转换为既有业务错误。 */
public interface RequestSystems {
    /** 按业务代码解析申请系统，缺失时抛出系统不存在错误。 */
    RequestSystem byCode(String code);
    /** 按持久化系统 ID 解析申请归属，不接受客户端覆盖所属系统。 */
    RequestSystem byId(Long id);
}
