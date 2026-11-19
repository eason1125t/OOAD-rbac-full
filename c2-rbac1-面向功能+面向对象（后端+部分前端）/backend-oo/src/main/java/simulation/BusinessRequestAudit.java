// 记录申请操作的结果，写入成功事件与实际事务提交对应。
package simulation;

import contract.BusinessAccessException;
import java.util.function.Function;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

final class BusinessRequestAudit {
    private static final Logger LOG = LoggerFactory.getLogger("rbac1.oo.request");

    enum Action {
        LIST, DETAIL, CREATE, UPDATE, WITHDRAW, APPROVE, REJECT, DELETE
    }

    private BusinessRequestAudit() {
    }

    static <T> T record(Action action, Long actor, Long requestId, Function<Target, T> operation) {
        Target target = new Target(requestId);
        try {
            T result = operation.apply(target);
            String success = action == Action.LIST || action == Action.DETAIL ? "SUCCEEDED" : "COMMITTED";
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
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

    private static void log(Action action, Long actor, Target target, String outcome, String code) {
        LOG.info("version=oo action={} actorId={} systemId={} requestId={} outcome={} code={}",
                action, actor, target.systemId, target.requestId, outcome, code);
    }

    static final class Target {
        private Long systemId;
        private Long requestId;

        Target(Long requestId) {
            this.requestId = requestId;
        }

        void identifySystem(Long systemId) {
            this.systemId = systemId;
        }

        void identifyRequest(Long requestId) {
            this.requestId = requestId;
        }
    }
}
