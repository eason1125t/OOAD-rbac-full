// 文件职责：提供登录、退出、当前会话和 CSRF API。
// 协作关系：协作 SessionAuthentication 与 WorkspaceService。
package authentication;

import authentication.dto.LoginDTO;
import authentication.vo.BusinessSystemVO;
import authentication.vo.CsrfTokenVO;
import authentication.vo.CurrentSessionVO;
import contract.ApiExceptionHandler;
import contract.vo.ApiErrorVO;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.AuthenticationCredentialsNotFoundException;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import organization.Employee;

/** HTTP 适配层，沿用 C1 契约并发布 C2 登录、会话与工作区接口。 */
@RestController
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
@RequiredArgsConstructor
public class AuthController {
    private final SessionAuthentication sessions;
    private final WorkspaceService workspaces;

    /** 返回匿名或登录会话的 CSRF 令牌。 */
    @GetMapping("/api/auth/csrf")
    public ResponseEntity<CsrfTokenVO> csrf(CsrfToken token) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(new CsrfTokenVO(token.getToken()));
    }

    /** 校验凭据后生成工作区，在成功返回时建立登录会话。 */
    @PostMapping(path = "/api/auth/login", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<CurrentSessionVO> login(@Valid @RequestBody LoginDTO input,
                                                  HttpServletRequest request, HttpServletResponse response) {
        Employee employee = sessions.verify(input.username(), input.password());
        CurrentSessionVO session = CurrentSessionVO.from(workspaces.current(employee));
        sessions.start(employee.getId(), request, response);
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(session);
    }

    /** 读取当前身份和最新工作区。 */
    @GetMapping("/api/auth/me")
    public ResponseEntity<CurrentSessionVO> me(@AuthenticationPrincipal Long employeeId) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(CurrentSessionVO.from(workspaces.current(activeEmployee(employeeId))));
    }

    /** 列出当前账号有权进入的业务系统。 */
    @GetMapping("/api/business-systems")
    public ResponseEntity<List<BusinessSystemVO>> systems(@AuthenticationPrincipal Long employeeId) {
        List<BusinessSystemVO> visible = workspaces.listSystems(activeEmployee(employeeId)).stream()
                .map(BusinessSystemVO::from).toList();
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(visible);
    }

    /** 注销会话，Spring Session 会在响应中清除 Cookie。 */
    @PostMapping("/api/auth/logout")
    public ResponseEntity<Void> logout(HttpServletRequest request, HttpServletResponse response) {
        sessions.end(request, response);
        return ResponseEntity.noContent().build();
    }

    /** 读取受保护接口所需的最新员工数据。 */
    private Employee activeEmployee(Long employeeId) {
        return workspaces.findActiveEmployee(employeeId)
                .orElseThrow(() -> new AuthenticationCredentialsNotFoundException("请登录后重试"));
    }

    /** 映射登录失败。 */
    @ExceptionHandler(BadCredentialsException.class)
    ResponseEntity<ApiErrorVO> invalidCredentials() {
        return ApiExceptionHandler.error(HttpStatus.UNAUTHORIZED, "INVALID_CREDENTIALS", "用户名或密码无效，或账号不可登录");
    }

    /** 映射读取身份时刚刚失效的会话。 */
    @ExceptionHandler(AuthenticationCredentialsNotFoundException.class)
    ResponseEntity<ApiErrorVO> expiredIdentity() {
        return ApiExceptionHandler.error(HttpStatus.UNAUTHORIZED, "AUTHENTICATION_REQUIRED", "请登录后重试");
    }

    /** 映射非 JSON 登录请求。 */
    @ExceptionHandler(HttpMediaTypeNotSupportedException.class)
    ResponseEntity<ApiErrorVO> invalidMediaType() {
        return ApiExceptionHandler.error(HttpStatus.UNSUPPORTED_MEDIA_TYPE, "UNSUPPORTED_MEDIA_TYPE", "请使用 application/json");
    }
}
