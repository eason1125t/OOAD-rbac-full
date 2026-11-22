// 文件职责：返回当前用户在指定系统中的角色来源和有效权限。
// 协作关系：由 CurrentAuthorizationService 显式映射，派生来源不通过全字段复制生成。
package authentication.vo;

import java.util.List;

/** 角色配置相关性与策略处理后的有效权限分别表达。 */
public record CurrentAuthorizationVO(String systemCode, List<Role> roles, List<Permission> effectivePermissions,
                                     boolean globalAdministrator) {
    /** 一个角色可以同时直接分配并由其他直接根继承；来源仅列根ID，不枚举路径。 */
    public record Role(Long id, String name, boolean direct, boolean inherited,
                       List<Long> inheritedFromRoleIds, boolean appliesToSystem) {
    }

    /** 权限已限定目标系统，仅返回前端需要的身份和展示字段。 */
    public record Permission(Long id, String code, String name) {
    }
}
