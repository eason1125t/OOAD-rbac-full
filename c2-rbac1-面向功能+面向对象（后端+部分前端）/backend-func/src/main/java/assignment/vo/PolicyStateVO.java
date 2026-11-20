// 文件职责：返回单个组织策略及已重授角色列表。
// 协作关系：由 OrganizationPermissionController 将服务结果转换为 API 响应。
package assignment.vo;

import java.util.List;

/** 组织权限的当前状态及重授角色标识。 */
public record PolicyStateVO(String status, List<Long> roleIds) {
}
