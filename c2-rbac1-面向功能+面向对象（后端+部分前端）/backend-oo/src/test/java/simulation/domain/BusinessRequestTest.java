// 独立于数据库验证申请生命周期，对应 C2-12、C2-13 的领域职责。
package simulation.domain;

import contract.BusinessAccessException;
import java.time.LocalDateTime;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class BusinessRequestTest {
    @Test
    @DisplayName("C2-12 申请人修改与撤回，撤回后不能再次改变状态")
    void applicantWithdraws() {
        BusinessRequest request = BusinessRequest.create(7L, 17L, "  申请  ");
        assertThat(request.getTitle()).isEqualTo("申请");
        request.rename(17L, "  修改  ");
        assertThat(request.getTitle()).isEqualTo("修改");
        request.withdraw(17L);
        assertThat(request.getStatus()).isEqualTo(BusinessRequest.WITHDRAWN);
        assertThatThrownBy(request::approve).isInstanceOf(BusinessAccessException.class);
        assertThatThrownBy(() -> request.rename(17L, "重提")).isInstanceOf(BusinessAccessException.class);
    }

    @Test
    @DisplayName("C2-13 仅申请人可删除已驳回单")
    void applicantDeletesRejected() {
        BusinessRequest request = BusinessRequest.create(7L, 17L, "申请");
        LocalDateTime now = LocalDateTime.of(2026, 11, 19, 0, 0);
        assertThatThrownBy(() -> request.delete(17L, now)).isInstanceOf(BusinessAccessException.class);
        request.reject();
        assertThatThrownBy(() -> request.delete(18L, now)).isInstanceOf(BusinessAccessException.class);
        request.delete(17L, now);
        assertThat(request.getDeletedAt()).isEqualTo(now);
        assertThatThrownBy(request::approve).isInstanceOf(BusinessAccessException.class);
    }

    @Test
    @DisplayName("C2-13 审批后不允许重复转换，历史申请仍可审批")
    void approvesLegacyRequest() {
        BusinessRequest legacy = BusinessRequest.create(7L, null, "旧申请");
        assertThatThrownBy(() -> legacy.withdraw(17L)).isInstanceOf(BusinessAccessException.class);
        legacy.approve();
        assertThat(legacy.getStatus()).isEqualTo(BusinessRequest.APPROVED);
        assertThatThrownBy(legacy::approve).isInstanceOf(BusinessAccessException.class);
        assertThatThrownBy(legacy::reject).isInstanceOf(BusinessAccessException.class);
    }
}
