// 文件职责：在原身份和工作区字段上增加访问凭证，不把刷新凭证放进 JSON。
// 协作关系：AuthController 根据 CurrentSessionVO 和 AccessTokenVO 显式构造。
package authentication.vo;

import java.util.List;

public record LoginResponseVO(CurrentSessionVO.EmployeeVO employee, List<CurrentSessionVO.WorkspaceVO> workspaces,
        String defaultWorkspaceKey, String accessToken, String tokenType, long expiresIn) {
    public static LoginResponseVO from(CurrentSessionVO identity, AccessTokenVO access) {
        return new LoginResponseVO(identity.employee(), identity.workspaces(), identity.defaultWorkspaceKey(),
                access.accessToken(), access.tokenType(), access.expiresIn());
    }

    @Override public String toString() { return "LoginResponseVO[redacted]"; }
}
