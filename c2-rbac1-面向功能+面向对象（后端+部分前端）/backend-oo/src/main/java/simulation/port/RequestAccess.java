// 文件职责：定义申请用例的实时授权和可用动作端口。
// 协作关系：由 RequestContextAdapter 接入既有 AuthorizationService。
package simulation.port;

import java.util.List;
import simulation.domain.RequestSystem;

/** 对每次申请操作重新计算目标系统的权限。 */
public interface RequestAccess {
    /** 按系统及动作实时授权，无法确认授权时拒绝执行业务。 */
    void require(Long actor, RequestSystem system, String permission);
    /** 返回当前系统中实时获准的申请写动作，查看权另行校验。 */
    List<String> allowedActions(Long actor, RequestSystem system);
}
