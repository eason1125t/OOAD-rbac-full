// 文件职责：集中记录申请读取、写入及拒绝结果。
// 协作关系：由申请 Service 包装操作，使用事务完成回调确认实际提交。
package simulation;

import contract.BusinessAccessException;
import java.util.function.Function;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** 集中记录申请读取、写入及拒绝结果，由申请 Service 包装操作，使用事务完成回调确认实际提交。 */
final class BusinessRequestAudit {
    private static final Logger LOG = LoggerFactory.getLogger("rbac1.func.request");

    /** 申请审计动作，读取结果与提交写入使用不同成功标记。 */
    enum Action {
        LIST, DETAIL, CREATE, UPDATE, WITHDRAW, APPROVE, REJECT, DELETE
    }

    /** 禁止实例化仅提供静态规则或嵌套模型的工具容器。 */
    private BusinessRequestAudit() {
    }

    /** 执行受审计操作，并在事务完成时区分成功提交与回滚；异常原样抛出。 */
    static <T> T record(Action action, Long actor, Long requestId, Function<Target, T> operation) {
        Target target = new Target(requestId);
        try {
            T result = operation.apply(target);
            String success = action == Action.LIST || action == Action.DETAIL ? "SUCCEEDED" : "COMMITTED";
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                /** 事务最终完成后才确认写入结果，回滚不能产生 COMMITTED 事件。 */
                @Override
                public void afterCompletion(int status) {
                    boolean committed = status == STATUS_COMMITTED;
                    log(action, actor, target, committed ? success : "FAILED",
                            committed ? "-" : "TRANSACTION_NOT_COMMITTED");
                }
            });
            return result;
        } catch (BusinessAccessException failure) {
            log(action, actor, target, "REJECTED", failure.getReason().name());
            throw failure;
        } catch (RuntimeException failure) {
            log(action, actor, target, "FAILED", failure.getClass().getSimpleName());
            throw failure;
        }
    }

    /** 输出版本、操作者和目标 ID 等审计元数据，省略申请正文和凭据。 */
    private static void log(Action action, Long actor, Target target, String outcome, String code) {
        LOG.info("version=func action={} actorId={} systemId={} requestId={} outcome={} code={}",
                action, actor, target.systemId, target.requestId, outcome, code);
    }

    /** 保存操作过程中逐步确定的目标 ID，不保存申请内容或凭据。 */
    static final class Target {
        private Long systemId;
        private Long requestId;

        /** 已有申请先记 ID，新建操作在保存后补齐。 */
        Target(Long requestId) {
            this.requestId = requestId;
        }

        /** 从已解析系统或持久化申请确定日志归属。 */
        void identifySystem(Long systemId) {
            this.systemId = systemId;
        }

        /** 新建成功后记录数据库分配的申请 ID。 */
        void identifyRequest(Long requestId) {
            this.requestId = requestId;
        }
    }
}
