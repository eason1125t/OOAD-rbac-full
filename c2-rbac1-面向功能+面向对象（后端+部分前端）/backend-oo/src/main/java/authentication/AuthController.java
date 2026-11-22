// 文件职责：提供JWT登录/刷新/退出、当前身份和CSRF API。
// 协作关系：账号校验与登录生命周期分开；Cookie不提供业务身份，业务依赖可信员工和实时授权。
package authentication;

import authentication.dto.LoginDTO;
import authentication.vo.BusinessSystemVO;
import authentication.vo.CsrfTokenVO;
import authentication.vo.AccessTokenVO;
import authentication.vo.LoginResponseVO;
import authentication.vo.CurrentSessionVO;
import authentication.vo.CurrentPermissionCheckVO;
import authentication.vo.CurrentApiCheckVO;
import contract.ApiExceptionHandler;
import contract.vo.ApiErrorVO;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.security.web.csrf.CsrfTokenRepository;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import organization.Employee;

/** HTTP 适配层，发布C2 JWT认证、当前身份与工作区接口。 */
@RestController
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
@RequiredArgsConstructor
public class AuthController {
    private final LoginAuthentication logins;
    private final AuthCookies cookies;
    private final CsrfTokenRepository csrfTokens;
    private final AccountAuthentication accounts;
    private final WorkspaceService workspaces;
    private final CurrentEmployeeService currentEmployees;
    private final CurrentAuthorizationService currentAuthorization;

    /** 返回Cookie对应的CSRF令牌，不创建匿名Session。 */
    @GetMapping("/api/auth/csrf")
    public ResponseEntity<CsrfTokenVO> csrf(CsrfToken token) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(new CsrfTokenVO(token.getToken()));
    }

    /** 先准备身份/工作区，再建立可撤销登录；成功后只通过Cookie交付刷新凭证。 */
    @PostMapping(path = "/api/auth/login", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<LoginResponseVO> login(@Valid @RequestBody LoginDTO input,
                                                  HttpServletRequest request, HttpServletResponse response) {
        Employee employee = accounts.verify(input.username(), input.password());
        CurrentSessionVO session = CurrentSessionVO.from(workspaces.current(employee));
        LoginAuthentication.Issued issued = logins.start(employee.getId(), cookies.read(request));
        cookies.write(response, issued.refresh(), issued.expiresAt());
        csrfTokens.saveToken(null, request, response);
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(LoginResponseVO.from(session, issued.access()));
    }

    /** 访问JWT过期不阻止刷新；严格轮换的凭证不从请求体或查询参数读取。 */
    @PostMapping("/api/auth/refresh")
    public ResponseEntity<AccessTokenVO> refresh(HttpServletRequest request, HttpServletResponse response) {
        LoginAuthentication.Issued issued = logins.refresh(cookies.read(request));
        cookies.write(response, issued.refresh(), issued.expiresAt());
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(issued.access());
    }

    /** 读取当前身份和最新工作区。 */
    @GetMapping("/api/auth/me")
    public ResponseEntity<CurrentSessionVO> me(@AuthenticationPrincipal Long employeeId,
            @RequestParam(required = false) @Size(min = 1, max = 64)
            @Pattern(regexp = "(?s).*\\S.*") String systemCode) {
        Employee employee = currentEmployees.requireActive(employeeId);
        CurrentSessionVO session = CurrentSessionVO.from(workspaces.current(employee));
        if (systemCode != null) {
            session = session.withAuthorization(currentAuthorization.describe(employee, systemCode));
        }
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(session);
    }

    /** 仅判断当前员工的已知系统权限，不能切换到其他员工。 */
    @GetMapping("/api/auth/me/permission-check")
    public ResponseEntity<CurrentPermissionCheckVO> permissionCheck(@AuthenticationPrincipal Long employeeId,
            @RequestParam @NotBlank @Size(max = 64) String systemCode,
            @RequestParam @NotBlank @Size(max = 128) String permissionCode) {
        Employee employee = currentEmployees.requireActive(employeeId);
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(currentAuthorization.check(employee, systemCode, permissionCode));
    }

    /** 系统级API预查询不接受目标员工，实际业务仍需重新授权及检查对象。 */
    @GetMapping("/api/auth/me/api-check")
    public ResponseEntity<CurrentApiCheckVO> apiCheck(@AuthenticationPrincipal Long employeeId,
            @RequestParam @NotBlank @Size(max = 64) String systemCode,
            @RequestParam @NotBlank @Size(max = 128) String operationId) {
        Employee employee = currentEmployees.requireActive(employeeId);
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(currentAuthorization.checkApi(employee, systemCode, operationId));
    }

    /** 列出当前账号有权进入的业务系统。 */
    @GetMapping("/api/business-systems")
    public ResponseEntity<List<BusinessSystemVO>> systems(@AuthenticationPrincipal Long employeeId) {
        List<BusinessSystemVO> visible = workspaces.listSystems(currentEmployees.requireActive(employeeId)).stream()
                .map(BusinessSystemVO::from).toList();
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(visible);
    }

    /** 撤销当前刷新凭证对应的独立登录，允许访问JWT已过期；存储故障不假报成功。 */
    @PostMapping("/api/auth/logout")
    public ResponseEntity<Void> logout(HttpServletRequest request, HttpServletResponse response) {
        logins.end(cookies.read(request));
        cookies.clear(response);
        csrfTokens.saveToken(null, request, response);
        return ResponseEntity.noContent().build();
    }

    /** 映射登录失败。 */
    @ExceptionHandler(BadCredentialsException.class)
    ResponseEntity<ApiErrorVO> invalidCredentials() {
        return ApiExceptionHandler.error(HttpStatus.UNAUTHORIZED, "INVALID_CREDENTIALS", "用户名或密码无效，或账号不可登录");
    }

    /** 确定失效的刷新凭证才清Cookie；数据库/Redis故障不走此分支。 */
    @ExceptionHandler(AuthenticationFailure.class)
    ResponseEntity<ApiErrorVO> invalidRefresh(AuthenticationFailure failure, HttpServletRequest request,
                                               HttpServletResponse response) {
        cookies.clear(response);
        csrfTokens.saveToken(null, request, response);
        return ApiExceptionHandler.error(HttpStatus.UNAUTHORIZED, failure.getError().getErrorCode(), failure.getMessage());
    }

    /** 映射非 JSON 登录请求。 */
    @ExceptionHandler(HttpMediaTypeNotSupportedException.class)
    ResponseEntity<ApiErrorVO> invalidMediaType() {
        return ApiExceptionHandler.error(HttpStatus.UNSUPPORTED_MEDIA_TYPE, "UNSUPPORTED_MEDIA_TYPE", "请使用 application/json");
    }
}
