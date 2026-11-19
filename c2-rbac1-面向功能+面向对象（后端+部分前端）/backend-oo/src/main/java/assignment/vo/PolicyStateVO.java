package assignment.vo;

import java.util.List;

/** 组织权限的当前状态及重授角色标识。 */
public record PolicyStateVO(String status, List<Long> roleIds) {
}
