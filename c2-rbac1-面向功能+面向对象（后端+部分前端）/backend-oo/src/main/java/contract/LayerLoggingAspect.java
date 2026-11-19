package contract;

import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.stereotype.Service;
import org.springframework.web.bind.annotation.RestController;

/** 统一记录业务入口耗时和异常类型，保护会话、参数和业务数据。 */
@Aspect
@Component
public class LayerLoggingAspect {
    /** 跟踪 Controller、Service、会话适配器和初始化命令的公开操作。 */
    @Around("execution(public * *(..))"
            + " && (@within(org.springframework.web.bind.annotation.RestController)"
            + " || @within(org.springframework.stereotype.Service)"
            + " || execution(* authentication.SessionAuthentication.verify(..))"
            + " || execution(* authentication.SessionAuthentication.start(..))"
            + " || execution(* authentication.SessionAuthentication.end(..))"
            + " || (execution(* org.springframework.boot.ApplicationRunner+.run(..))"
            + " && (within(initialization..*) || within(authentication..*))))")
    public Object trace(ProceedingJoinPoint invocation) throws Throwable {
        Class<?> type = invocation.getSignature().getDeclaringType();
        Logger logger = LoggerFactory.getLogger(loggerName(type));
        String method = invocation.getSignature().getName();
        long started = System.nanoTime();
        logger.debug("{} 开始", method);
        try {
            Object result = invocation.proceed();
            logger.debug("{} 完成，耗时 {} ms", method, elapsedMillis(started));
            return result;
        } catch (Throwable failure) {
            logger.debug("{} 失败，耗时 {} ms，异常 {}", method, elapsedMillis(started),
                    failure.getClass().getSimpleName());
            throw failure;
        }
    }

    /** 按职责设置日志级别，日志名仍包含实际功能包和类名。 */
    private String loggerName(Class<?> type) {
        if (type.isAnnotationPresent(RestController.class)) {
            return "rbac0.api." + type.getName();
        }
        if (type.isAnnotationPresent(Service.class)) {
            return "rbac0.service." + type.getName();
        }
        return "rbac0.infrastructure." + type.getName();
    }

    /** 用单调时钟计算执行耗时。 */
    private long elapsedMillis(long started) {
        return (System.nanoTime() - started) / 1_000_000;
    }
}
