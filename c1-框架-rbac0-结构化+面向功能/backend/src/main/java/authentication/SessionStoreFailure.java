package authentication;

import io.lettuce.core.RedisException;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.RedisSystemException;

/** 识别 Spring Session 访问 Redis 时的连接、命令超时和客户端故障。 */
public final class SessionStoreFailure {
    private SessionStoreFailure() {
    }

    /** 检查异常链，保持 MySQL 超时和 Redis 超时的错误码各自准确。 */
    public static boolean matches(Throwable failure) {
        for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
            if (cause instanceof RedisConnectionFailureException || cause instanceof RedisSystemException
                    || cause instanceof RedisException) {
                return true;
            }
        }
        return false;
    }
}
