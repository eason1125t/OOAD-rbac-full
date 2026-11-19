// 接收发起与修改申请的标题，拒绝客户端写入其他字段。
package simulation.dto;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class RequestTitleDTO {
    @NotBlank
    @Size(max = 128)
    private String title;

    @JsonAnySetter
    void rejectUnknownField(String name, Object value) {
        throw new IllegalArgumentException("申请仅接受标题字段");
    }
}
