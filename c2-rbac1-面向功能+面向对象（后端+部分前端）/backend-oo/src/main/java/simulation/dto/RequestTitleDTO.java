// 文件职责：只接收标题并拒绝未声明的申请写入字段。
// 协作关系：由申请 Controller 执行 Bean Validation，不接受客户端指定申请人或状态。
package simulation.dto;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.Setter;

/** 只接收标题并拒绝未声明的申请写入字段，由申请 Controller 执行 Bean Validation，不接受客户端指定申请人或状态。 */
@Getter
@Setter
public class RequestTitleDTO {
    @NotBlank
    @Size(max = 128)
    private String title;

    /** 拒绝标题以外的 JSON 字段，阻止客户端写入申请人或状态。 */
    @JsonAnySetter
    void rejectUnknownField(String name, Object value) {
        throw new IllegalArgumentException("申请仅接受标题字段");
    }
}
