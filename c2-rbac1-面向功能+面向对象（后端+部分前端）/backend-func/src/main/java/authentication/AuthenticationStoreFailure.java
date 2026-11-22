// 文件职责：识别异常链中的 Redis 认证存储故障及损坏的登录记录。
// 协作关系：供安全过滤器和 ApiExceptionHandler 区分认证存储与授权数据库不可用。
package authentication;

import io.lettuce.core.RedisException;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.RedisSystemException;
import org.springframework.dao.DataAccessResourceFailureException;

/** 不把认证存储故障降级为登录失败或权限不足。 */
public final class AuthenticationStoreFailure {
    /** 禁止实例化仅提供静态规则或嵌套模型的工具容器。 */
    private AuthenticationStoreFailure() {
    }

    /** 检查异常链，保持 MySQL 超时和 Redis 超时的错误码各自准确。 */
    public static boolean matches(Throwable failure) {
        for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
            if (cause instanceof InvalidState || cause instanceof RedisConnectionFailureException || cause instanceof RedisSystemException
                    || cause instanceof RedisException) {
                return true;
            }
        }
        return false;
    }

    /** 登录记录损坏或脚本结果不确定时拒绝服务，错误文本不含记录内容。 */
    public static final class InvalidState extends DataAccessResourceFailureException {
        public InvalidState() { super("认证存储状态不可用"); }
    }
}
