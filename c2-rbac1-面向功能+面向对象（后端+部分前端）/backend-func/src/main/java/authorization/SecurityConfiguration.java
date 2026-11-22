// 文件职责：配置无认证Session的JWT安全链、Cookie入口CSRF和实时业务路由保护。
// 协作关系：JwtAccessTokens验签，JwtEmployeeAuthentication检查真实登录记录/员工，AuthorizationService执行授权。
package authorization;

import assignment.OrganizationPermissionController;
import authentication.AuthenticationStoreFailure;
import authentication.JwtAccessTokens;
import authentication.JwtEmployeeAuthentication;
import com.fasterxml.jackson.databind.ObjectMapper;
import contract.vo.ApiErrorVO;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Set;
import java.util.function.Supplier;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.security.authorization.AuthorizationDecision;
import org.springframework.security.authorization.AuthorizationManager;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.oauth2.jwt.JwtValidationException;
import org.springframework.security.oauth2.server.resource.web.DefaultBearerTokenResolver;
import org.springframework.security.oauth2.server.resource.web.BearerTokenAuthenticationEntryPoint;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.access.intercept.RequestAuthorizationContext;
import org.springframework.security.web.context.NullSecurityContextRepository;
import org.springframework.security.web.context.SecurityContextHolderFilter;
import org.springframework.security.web.authentication.session.NullAuthenticatedSessionStrategy;
import org.springframework.security.web.csrf.CsrfException;
import org.springframework.security.web.csrf.CsrfFilter;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.security.web.csrf.CsrfTokenRepository;
import org.springframework.security.web.csrf.CsrfTokenRequestHandler;
import org.springframework.security.web.csrf.XorCsrfTokenRequestAttributeHandler;
import org.springframework.transaction.CannotCreateTransactionException;
import org.springframework.web.filter.OncePerRequestFilter;

/** Cookie只服务登录/刷新/退出；所有业务请求只接受Bearer，不恢复旧Session。 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
@RequiredArgsConstructor
public class SecurityConfiguration {
    private static final Set<String> COOKIE_ENDPOINTS = Set.of("/api/auth/csrf", "/api/auth/login",
            "/api/auth/refresh", "/api/auth/logout");
    private final ObjectMapper json;
    private final AuthorizationService authorization;

    @Bean
    SecurityFilterChain apiSecurity(HttpSecurity http, CsrfTokenRepository csrfTokens,
            JwtAccessTokens tokens, JwtEmployeeAuthentication identities) throws Exception {
        DefaultBearerTokenResolver bearer = new DefaultBearerTokenResolver();
        CsrfTokenRequestHandler csrfHandler = new CsrfTokenRequestHandler() {
            private final XorCsrfTokenRequestAttributeHandler delegate = new XorCsrfTokenRequestAttributeHandler();
            @Override
            public void handle(HttpServletRequest request, HttpServletResponse response, Supplier<CsrfToken> token) {
                delegate.handle(request, response, token);
            }
            @Override
            public String resolveCsrfTokenValue(HttpServletRequest request, CsrfToken token) {
                return request.getHeader(token.getHeaderName()) == null ? null : delegate.resolveCsrfTokenValue(request, token);
            }
        };
        return http.httpBasic(AbstractHttpConfigurer::disable).formLogin(AbstractHttpConfigurer::disable)
                .logout(AbstractHttpConfigurer::disable).requestCache(AbstractHttpConfigurer::disable)
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .securityContext(context -> context.securityContextRepository(new NullSecurityContextRepository()))
                .csrf(csrf -> csrf.csrfTokenRepository(csrfTokens).csrfTokenRequestHandler(csrfHandler)
                        // 登录/退出由Controller显式轮换CSRF；逐请求Bearer认证不能清除浏览器CSRF。
                        .sessionAuthenticationStrategy(new NullAuthenticatedSessionStrategy())
                        .requireCsrfProtectionMatcher(request -> COOKIE_ENDPOINTS.contains(request.getServletPath())
                                && CsrfFilter.DEFAULT_CSRF_MATCHER.matches(request)))
                .exceptionHandling(errors -> errors.authenticationEntryPoint(this::unauthenticated)
                        .accessDeniedHandler((request, response, failure) -> denied(response, failure)))
                .oauth2ResourceServer(resource -> resource
                        .bearerTokenResolver(request -> COOKIE_ENDPOINTS.contains(request.getServletPath())
                                ? null : bearer.resolve(request))
                        .jwt(jwt -> jwt.decoder(tokens).jwtAuthenticationConverter(identities))
                        .authenticationEntryPoint(this::unauthenticated)
                        .accessDeniedHandler((request, response, failure) -> denied(response, failure)))
                .addFilterBefore(dependencyErrors(), SecurityContextHolderFilter.class)
                .authorizeHttpRequests(rules -> rules
                        .requestMatchers(HttpMethod.GET, "/api/auth/csrf").permitAll()
                        .requestMatchers(HttpMethod.POST, "/api/auth/login", "/api/auth/refresh", "/api/auth/logout").permitAll()
                        .requestMatchers(HttpMethod.GET, "/api/auth/me", "/api/auth/me/permission-check", "/api/auth/me/api-check",
                                "/api/business-systems", "/api/employees", "/api/departments").authenticated()
                        .requestMatchers(HttpMethod.GET, "/api/business-systems/{systemCode}/requests",
                                "/api/requests/{requestId}").authenticated()
                        .requestMatchers(HttpMethod.POST, "/api/business-systems/{systemCode}/requests").authenticated()
                        .requestMatchers(HttpMethod.GET, "/api/companies",
                                "/api/roles", "/api/permissions", "/api/catalog-entries",
                                "/api/employees/{employeeId}", "/api/roles/{roleId}", "/api/permissions/{permissionId}",
                                "/api/employees/{employeeId}/roles", "/api/roles/{roleId}/permissions",
                                "/api/roles/{roleId}/parents", "/api/roles/{roleId}/inherited-permissions",
                                "/api/employees/{employeeId}/effective-permissions")
                                .access(managementAction(AuthorizationService.MANAGEMENT_READ))
                        .requestMatchers(HttpMethod.POST, "/api/requests/{requestId}/approve",
                                "/api/requests/{requestId}/reject", "/api/requests/{requestId}/withdraw").authenticated()
                        .requestMatchers(HttpMethod.PATCH, "/api/requests/{requestId}").authenticated()
                        .requestMatchers(HttpMethod.DELETE, "/api/requests/{requestId}").authenticated()
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
                        .requestMatchers(HttpMethod.PUT, "/api/roles/{roleId}/permissions/{permissionId}",
                                "/api/roles/{roleId}/parents/{parentRoleId}")
                                .access(managementAction(AuthorizationService.ROLE_PERMISSION_WRITE))
                        .requestMatchers(HttpMethod.DELETE, "/api/roles/{roleId}/permissions/{permissionId}",
                                "/api/roles/{roleId}/parents/{parentRoleId}")
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

    /** 管理动作继续实时计算，不采用JWT中的roles/scope。 */
    private AuthorizationManager<RequestAuthorizationContext> managementAction(String action) {
        return (authentication, context) -> {
            if (!(authentication.get().getPrincipal() instanceof Long employeeId)) return new AuthorizationDecision(false);
            return new AuthorizationDecision(authorization.assignmentScope(employeeId, action).isPresent());
        };
    }

    /** 在Bearer转换与MVC之外处理存储故障，不伪装为401或allowed=false。 */
    private OncePerRequestFilter dependencyErrors() {
        return new OncePerRequestFilter() {
            @Override
            protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
                    throws ServletException, IOException {
                try {
                    chain.doFilter(request, response);
                } catch (RuntimeException | ServletException failure) {
                    if (response.isCommitted()) throw failure;
                    boolean authenticationStore = AuthenticationStoreFailure.matches(failure);
                    boolean database = false;
                    for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
                        if (cause instanceof DataAccessException || cause instanceof CannotCreateTransactionException) database = true;
                    }
                    if (!authenticationStore && !database) throw failure;
                    response.resetBuffer();
                    error(response, 503, authenticationStore ? "AUTHENTICATION_UNAVAILABLE" : "AUTHORIZATION_UNAVAILABLE",
                            authenticationStore ? "认证服务暂不可用" : "授权数据暂不可用");
                }
            }
        };
    }

    /** 保留Bearer challenge；只有验证器确认的单纯过期才允许客户端刷新。 */
    private void unauthenticated(HttpServletRequest request, HttpServletResponse response,
                                 AuthenticationException failure) throws IOException {
        new BearerTokenAuthenticationEntryPoint().commence(request, response, failure);
        boolean expired = false;
        for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
            if (cause instanceof JwtValidationException validation) {
                expired = !validation.getErrors().isEmpty() && validation.getErrors().stream()
                        .allMatch(error -> "ACCESS_TOKEN_EXPIRED".equals(error.getErrorCode()));
                break;
            }
        }
        error(response, 401, expired ? "ACCESS_TOKEN_EXPIRED" : "AUTHENTICATION_REQUIRED",
                expired ? "访问凭证已过期" : "请登录后重试");
    }

    private void denied(HttpServletResponse response, Exception failure) throws IOException {
        boolean csrf = failure instanceof CsrfException;
        error(response, 403, csrf ? "CSRF_INVALID" : "PERMISSION_DENIED",
                csrf ? "请重新获取安全令牌" : "无权执行该操作");
    }

    private void error(HttpServletResponse response, int status, String code, String message) throws IOException {
        response.setStatus(status);
        response.setHeader("Cache-Control", "no-store");
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        json.writeValue(response.getOutputStream(), new ApiErrorVO(code, message));
    }
}
