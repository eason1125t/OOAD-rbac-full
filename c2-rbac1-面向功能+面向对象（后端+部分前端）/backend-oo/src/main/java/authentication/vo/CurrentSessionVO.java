// 文件职责：返回当前员工、管理员范围和可选工作区。
// 协作关系：由 WorkspaceService 构造，AuthController 输出给前端。
package authentication.vo;

import authentication.WorkspaceService.CurrentSession;
import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.List;

/** 登录与当前身份接口共用的导航响应，不暴露 JPA 对象。 */
public record CurrentSessionVO(EmployeeVO employee, List<WorkspaceVO> workspaces, String defaultWorkspaceKey,
                               @JsonInclude(JsonInclude.Include.NON_NULL) CurrentAuthorizationVO authorization) {
    /** 将当前身份和业务导航映射为公开响应。 */
    public static CurrentSessionVO from(CurrentSession session) {
        return new CurrentSessionVO(
                new EmployeeVO(session.employee().id(), session.employee().username(), session.employee().name(),
                        session.employee().organizationId()),
                session.workspaces().stream()
                        .map(workspace -> new WorkspaceVO(workspace.key(), workspace.type(), workspace.name(), workspace.path()))
                        .toList(),
                session.defaultWorkspaceKey(), null);
    }

    /** 只在显式指定系统时增加授权；登录和无参数me继续省略该字段。 */
    public CurrentSessionVO withAuthorization(CurrentAuthorizationVO value) {
        return new CurrentSessionVO(employee, workspaces, defaultWorkspaceKey, value);
    }

    /** 当前登录员工的公开身份。 */
    public record EmployeeVO(Long id, String username, String name, Long organizationId) {
    }

    /** 当前员工可进入的同源工作区。 */
    public record WorkspaceVO(String key, String type, String name, String path) {
    }
}
