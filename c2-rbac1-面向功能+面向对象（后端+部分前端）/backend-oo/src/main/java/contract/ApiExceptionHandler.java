// 文件职责：将业务拒绝、输入错误和依赖故障转换为稳定 HTTP 错误。
// 协作关系：协作 BusinessAccessException、AuthenticationStoreFailure 和 ApiErrorVO。
package contract;

import authentication.AuthenticationStoreFailure;
import authentication.AuthenticationFailure;
import contract.vo.ApiErrorVO;
import org.springframework.dao.DataAccessException;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.transaction.CannotCreateTransactionException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

/** 统一 MVC 参数、业务和存储故障响应，安全过滤器沿用相同错误结构。 */
@RestControllerAdvice
public class ApiExceptionHandler {
    /** 将业务异常映射为 OpenAPI 中的状态码。 */
    @ExceptionHandler(BusinessAccessException.class)
    ResponseEntity<ApiErrorVO> businessFailure(BusinessAccessException failure) {
        HttpStatus status = switch (failure.getReason()) {
            case AUTHENTICATION_REQUIRED -> HttpStatus.UNAUTHORIZED;
            case VALIDATION_FAILED -> HttpStatus.BAD_REQUEST;
            case SYSTEM_ACCESS_DENIED, PERMISSION_DENIED -> HttpStatus.FORBIDDEN;
            case SYSTEM_NOT_FOUND, REQUEST_NOT_FOUND, RESOURCE_NOT_FOUND, PERMISSION_NOT_FOUND, API_NOT_FOUND -> HttpStatus.NOT_FOUND;
            case REQUEST_ALREADY_APPROVED, REQUEST_STATE_CONFLICT, ASSIGNMENT_CONFLICT, RESOURCE_CONFLICT,
                    SELF_ROLE_INHERITANCE, ROLE_INHERITANCE_CYCLE, ROLE_INHERITANCE_SCOPE_MISMATCH -> HttpStatus.CONFLICT;
        };
        return error(status, failure.getReason().name(), failure.getMessage());
    }

    /** 统一请求体、路径、查询参数格式错误。 */
    @ExceptionHandler({HandlerMethodValidationException.class, MethodArgumentTypeMismatchException.class,
            MethodArgumentNotValidException.class, HttpMessageNotReadableException.class,
            MissingServletRequestParameterException.class})
    ResponseEntity<ApiErrorVO> invalidInput() {
        return error(HttpStatus.BAD_REQUEST, "VALIDATION_FAILED", "请求参数无效");
    }

    /** 区分 Redis 认证存储故障与授权数据库访问故障。 */
    @ExceptionHandler({DataAccessException.class, CannotCreateTransactionException.class})
    ResponseEntity<ApiErrorVO> unavailable(RuntimeException exception) {
        if (AuthenticationStoreFailure.matches(exception)) {
            return error(HttpStatus.SERVICE_UNAVAILABLE, "AUTHENTICATION_UNAVAILABLE", "认证服务暂不可用");
        }
        return error(HttpStatus.SERVICE_UNAVAILABLE, "AUTHORIZATION_UNAVAILABLE", "授权数据暂不可用");
    }

    /** 所有错误响应采用相同结构并禁止缓存。 */
    public static ResponseEntity<ApiErrorVO> error(HttpStatus status, String code, String message) {
        return ResponseEntity.status(status).cacheControl(CacheControl.noStore()).body(new ApiErrorVO(code, message));
    }

    /** 刷新/登录记录的确定性拒绝，与存储故障区分。 */
    @ExceptionHandler(AuthenticationFailure.class)
    ResponseEntity<ApiErrorVO> authenticationFailure(AuthenticationFailure failure) {
        return error(HttpStatus.UNAUTHORIZED, failure.getError().getErrorCode(), failure.getMessage());
    }
}
