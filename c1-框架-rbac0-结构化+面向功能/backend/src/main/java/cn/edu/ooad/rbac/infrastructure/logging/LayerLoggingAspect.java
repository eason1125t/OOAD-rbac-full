package cn.edu.ooad.rbac.infrastructure.logging;

import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/** 统一记录业务入口耗时和异常类型，保护会话、参数和业务数据。 */
@Aspect
@Component
public class LayerLoggingAspect {
    /** 跟踪 Controller、Service 和初始化入口的每次执行。 */
    @Around("execution(public * cn.edu.ooad.rbac.api.*Controller.*(..))"
            + " || execution(public * cn.edu.ooad.rbac.service.*Service.*(..))"
            + " || execution(public * cn.edu.ooad.rbac.infrastructure.cli.*.run(..))")
    public Object trace(ProceedingJoinPoint invocation) throws Throwable {
        Class<?> type = invocation.getSignature().getDeclaringType();
        Logger logger = LoggerFactory.getLogger(type);
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

    /** 用单调时钟计算执行耗时。 */
    private long elapsedMillis(long started) {
        return (System.nanoTime() - started) / 1_000_000;
    }
}
