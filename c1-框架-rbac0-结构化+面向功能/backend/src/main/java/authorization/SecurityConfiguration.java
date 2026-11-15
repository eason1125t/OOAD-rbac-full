package authorization;

import assignment.OrganizationPermissionController;
import authentication.SessionStoreFailure;
import authentication.WorkspaceService;
import com.fasterxml.jackson.databind.ObjectMapper;
import contract.vo.ApiErrorVO;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.security.authorization.AuthorizationDecision;
import org.springframework.security.authorization.AuthorizationManager;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.access.intercept.AuthorizationFilter;
import org.springframework.security.web.access.intercept.RequestAuthorizationContext;
import org.springframework.security.web.authentication.logout.SecurityContextLogoutHandler;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.security.web.csrf.CsrfException;
import org.springframework.security.web.csrf.CsrfTokenRepository;
import org.springframework.security.web.csrf.HttpSessionCsrfTokenRepository;
import org.springframework.session.web.http.SessionRepositoryFilter;
import org.springframework.transaction.CannotCreateTransactionException;
import org.springframework.web.filter.OncePerRequestFilter;

/** 配置已交付的认证路由、CSRF、会话恢复与 JSON 错误。 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
@RequiredArgsConstructor
public class SecurityConfiguration {
    private final ObjectMapper json;
    private final AuthorizationService authorization;

    /** 使用由 Spring Session 接管的 HttpSession 保存安全上下文。 */
    @Bean
    SecurityContextRepository securityContexts() {
        return new HttpSessionSecurityContextRepository();
    }

    /** 使用同一服务端会话保存 CSRF 令牌。 */
    @Bean
    CsrfTokenRepository csrfTokens() {
        return new HttpSessionCsrfTokenRepository();
    }

    /** 仅公开登录和取令牌路由，未实现的契约路由默认拒绝。 */
    @Bean
    SecurityFilterChain apiSecurity(HttpSecurity http, SecurityContextRepository contexts,
                                    CsrfTokenRepository tokens, WorkspaceService workspaces) throws Exception {
        return http
                .httpBasic(AbstractHttpConfigurer::disable)
                .formLogin(AbstractHttpConfigurer::disable)
                .logout(AbstractHttpConfigurer::disable)
                .requestCache(AbstractHttpConfigurer::disable)
                .securityContext(context -> context.securityContextRepository(contexts))
                .csrf(csrf -> csrf.csrfTokenRepository(tokens))
                .exceptionHandling(errors -> errors
                        .authenticationEntryPoint((request, response, exception) -> unauthorized(response))
                        .accessDeniedHandler((request, response, exception) -> {
                            if (exception instanceof CsrfException) {
                                error(response, 403, "CSRF_INVALID", "请重新获取安全令牌");
                            } else {
                                error(response, 403, "PERMISSION_DENIED", "无权执行该操作");
                            }
                        }))
                .addFilterBefore(activeEmployeeFilter(workspaces), AuthorizationFilter.class)
                .authorizeHttpRequests(rules -> rules
                        .requestMatchers(HttpMethod.GET, "/api/auth/csrf").permitAll()
                        .requestMatchers(HttpMethod.POST, "/api/auth/login").permitAll()
                        .requestMatchers(HttpMethod.GET, "/api/auth/me", "/api/business-systems").authenticated()
                        .requestMatchers(HttpMethod.GET, "/api/business-systems/{systemCode}/requests").authenticated()
                        .requestMatchers(HttpMethod.GET, "/api/employees", "/api/companies", "/api/departments",
                                "/api/roles", "/api/permissions", "/api/catalog-entries",
                                "/api/employees/{employeeId}", "/api/roles/{roleId}", "/api/permissions/{permissionId}",
                                "/api/employees/{employeeId}/roles", "/api/roles/{roleId}/permissions")
                                .access(managementAction(AuthorizationService.MANAGEMENT_READ))
                        .requestMatchers(HttpMethod.POST, "/api/requests/{requestId}/approve").authenticated()
                        .requestMatchers(HttpMethod.POST, "/api/auth/logout").authenticated()
                        .requestMatchers(HttpMethod.POST, "/api/employees", "/api/roles", "/api/permissions")
                                .access(managementAction(AuthorizationService.MANAGEMENT_WRITE))
                        .requestMatchers(HttpMethod.PATCH, "/api/employees/{employeeId}", "/api/roles/{roleId}",
                                "/api/permissions/{permissionId}")
                                .access(managementAction(AuthorizationService.MANAGEMENT_WRITE))
                        .requestMatchers(HttpMethod.DELETE, "/api/employees/{employeeId}", "/api/roles/{roleId}",
                                "/api/permissions/{permissionId}")
                                .access(managementAction(AuthorizationService.MANAGEMENT_WRITE))
                        .requestMatchers(HttpMethod.PUT, "/api/employees/{employeeId}/roles/{roleId}")
                                .access(managementAction(AuthorizationService.EMPLOYEE_ROLE_WRITE))
                        .requestMatchers(HttpMethod.DELETE, "/api/employees/{employeeId}/roles/{roleId}")
                                .access(managementAction(AuthorizationService.EMPLOYEE_ROLE_WRITE))
                        .requestMatchers(HttpMethod.PUT, "/api/roles/{roleId}/permissions/{permissionId}")
                                .access(managementAction(AuthorizationService.ROLE_PERMISSION_WRITE))
                        .requestMatchers(HttpMethod.DELETE, "/api/roles/{roleId}/permissions/{permissionId}")
                                .access(managementAction(AuthorizationService.ROLE_PERMISSION_WRITE))
                        .requestMatchers(HttpMethod.PUT, OrganizationPermissionController.DISABLED,
                                OrganizationPermissionController.REGRANTS)
                                .access(managementAction(AuthorizationService.ORGANIZATION_PERMISSION_WRITE))
                        .requestMatchers(HttpMethod.GET, OrganizationPermissionController.POLICY)
                                .access(managementAction(AuthorizationService.ORGANIZATION_PERMISSION_WRITE))
                        .requestMatchers(HttpMethod.DELETE, OrganizationPermissionController.DISABLED)
                                .access(managementAction(AuthorizationService.ORGANIZATION_PERMISSION_WRITE))
                        .anyRequest().denyAll())
                .build();
    }

    /** 将管理路由交给统一授权入口实时校验动作。 */
    private AuthorizationManager<RequestAuthorizationContext> managementAction(String action) {
        return (authentication, context) -> {
            if (!(authentication.get().getPrincipal() instanceof Long employeeId)) {
                return new AuthorizationDecision(false);
            }
            return new AuthorizationDecision(authorization.assignmentScope(employeeId, action).isPresent());
        };
    }

    /** 在恢复上下文后重新验证员工，停用或待匹配时结束已有会话。 */
    private OncePerRequestFilter activeEmployeeFilter(WorkspaceService workspaces) {
        return new OncePerRequestFilter() {
            @Override
            protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                            FilterChain chain) throws ServletException, IOException {
                Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
                if (authentication != null && authentication.getPrincipal() instanceof Long employeeId
                        && !"/api/auth/login".equals(request.getServletPath())
                        && !"/api/auth/csrf".equals(request.getServletPath())) {
                    try {
                        if (workspaces.findActiveEmployee(employeeId).isEmpty()) {
                            new SecurityContextLogoutHandler().logout(request, response, authentication);
                            unauthorized(response);
                            return;
                        }
                    } catch (DataAccessException | CannotCreateTransactionException exception) {
                        if (SessionStoreFailure.matches(exception)) {
                            throw exception;
                        }
                        error(response, 503, "AUTHORIZATION_UNAVAILABLE", "授权数据暂不可用");
                        return;
                    }
                }
                try {
                    chain.doFilter(request, response);
                } catch (DataAccessException | CannotCreateTransactionException exception) {
                    if (SessionStoreFailure.matches(exception) || response.isCommitted()) {
                        throw exception;
                    }
                    response.resetBuffer();
                    error(response, 503, "AUTHORIZATION_UNAVAILABLE", "授权数据暂不可用");
                }
            }
        };
    }

    /** 在 Redis 会话过滤器外处理连接和命令故障，已提交响应时交还容器处理。 */
    @Bean
    FilterRegistrationBean<OncePerRequestFilter> redisSessionErrors() {
        OncePerRequestFilter filter = new OncePerRequestFilter() {
            @Override
            protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                            FilterChain chain) throws IOException, ServletException {
                try {
                    chain.doFilter(request, response);
                } catch (RuntimeException | ServletException exception) {
                    if (!SessionStoreFailure.matches(exception) || response.isCommitted()) {
                        throw exception;
                    }
                    response.resetBuffer();
                    error(response, 503, "SESSION_UNAVAILABLE", "会话服务暂不可用");
                }
            }
        };
        FilterRegistrationBean<OncePerRequestFilter> registration = new FilterRegistrationBean<>(filter);
        registration.setOrder(SessionRepositoryFilter.DEFAULT_ORDER - 1);
        return registration;
    }

    /** 返回未经认证的 API 响应。 */
    private void unauthorized(HttpServletResponse response) throws IOException {
        error(response, 401, "AUTHENTICATION_REQUIRED", "请登录后重试");
    }

    /** 在过滤器中写入与 Controller 一致的错误结构。 */
    private void error(HttpServletResponse response, int status, String code, String message) throws IOException {
        response.setStatus(status);
        response.setHeader("Cache-Control", "no-store");
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        json.writeValue(response.getOutputStream(), new ApiErrorVO(code, message));
    }
}
