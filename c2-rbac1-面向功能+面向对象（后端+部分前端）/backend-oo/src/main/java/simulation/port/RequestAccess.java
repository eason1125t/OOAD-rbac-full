// 申请模块的实时授权端口，由适配器复用既有 C1 授权策略。
package simulation.port;

import java.util.List;
import simulation.domain.RequestSystem;

/** 对每次申请操作重新计算目标系统的权限。 */
public interface RequestAccess {
    void require(Long actor, RequestSystem system, String permission);
    List<String> allowedActions(Long actor, RequestSystem system);
}
