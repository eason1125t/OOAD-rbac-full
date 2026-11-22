// 文件职责：返回当前用户的单项系统权限判断。
// 协作关系：由 CurrentAuthorizationService 复用 AuthorizationService 生成，不构成后续业务调用凭证。
package authentication.vo;

/** 已知权限的允许/拒绝结果；未知权限使用独立404错误。 */
public record CurrentPermissionCheckVO(String systemCode, String permissionCode, boolean allowed) {
}
