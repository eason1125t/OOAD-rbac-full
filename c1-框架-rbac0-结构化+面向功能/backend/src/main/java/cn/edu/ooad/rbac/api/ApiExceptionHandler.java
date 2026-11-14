package cn.edu.ooad.rbac.api;

import cn.edu.ooad.rbac.api.vo.ApiErrorVO;
import cn.edu.ooad.rbac.infrastructure.security.SessionStoreFailure;
import cn.edu.ooad.rbac.service.BusinessAccessException;
import org.springframework.dao.DataAccessException;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.transaction.CannotCreateTransactionException;
import org.springframework.web.bind.MethodArgumentNotValidException;
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
            case SYSTEM_ACCESS_DENIED, PERMISSION_DENIED -> HttpStatus.FORBIDDEN;
            case SYSTEM_NOT_FOUND, REQUEST_NOT_FOUND, RESOURCE_NOT_FOUND -> HttpStatus.NOT_FOUND;
            case REQUEST_ALREADY_APPROVED, ASSIGNMENT_CONFLICT, RESOURCE_CONFLICT -> HttpStatus.CONFLICT;
        };
        return error(status, failure.getReason().name(), failure.getMessage());
    }

    /** 统一请求体、路径、查询参数格式错误。 */
    @ExceptionHandler({HandlerMethodValidationException.class, MethodArgumentTypeMismatchException.class,
            MethodArgumentNotValidException.class, HttpMessageNotReadableException.class})
    ResponseEntity<ApiErrorVO> invalidInput() {
        return error(HttpStatus.BAD_REQUEST, "VALIDATION_FAILED", "请求参数无效");
    }

    /** 区分 Redis 会话故障与数据库访问故障。 */
    @ExceptionHandler({DataAccessException.class, CannotCreateTransactionException.class})
    ResponseEntity<ApiErrorVO> unavailable(RuntimeException exception) {
        if (SessionStoreFailure.matches(exception)) {
            return error(HttpStatus.SERVICE_UNAVAILABLE, "SESSION_UNAVAILABLE", "会话服务暂不可用");
        }
        return error(HttpStatus.SERVICE_UNAVAILABLE, "AUTHORIZATION_UNAVAILABLE", "授权数据暂不可用");
    }

    /** 所有错误响应采用相同结构并禁止缓存。 */
    static ResponseEntity<ApiErrorVO> error(HttpStatus status, String code, String message) {
        return ResponseEntity.status(status).cacheControl(CacheControl.noStore()).body(new ApiErrorVO(code, message));
    }
}
