// 文件职责：识别异常链中的 Redis 会话存储故障。
// 协作关系：供安全过滤器和 ApiExceptionHandler 区分会话与数据库不可用。
package authentication;

import io.lettuce.core.RedisException;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.RedisSystemException;

/** 识别 Spring Session 访问 Redis 时的连接、命令超时和客户端故障。 */
public final class SessionStoreFailure {
    /** 禁止实例化仅提供静态规则或嵌套模型的工具容器。 */
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
