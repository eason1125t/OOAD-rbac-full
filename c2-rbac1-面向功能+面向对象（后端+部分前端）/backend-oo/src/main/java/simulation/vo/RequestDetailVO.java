// 申请公开响应，由 CloneFactory 从应用结果复制同名同类型字段。
package simulation.vo;

import cn.edu.xmu.clonefactory.CopyFrom;
import cn.edu.xmu.clonefactory.util.CloneFactory;
import lombok.Getter;
import lombok.Setter;
import simulation.BusinessRequestService.RequestDetail;

/** 保持共同 OpenAPI 的申请响应字段。 */
@Getter
@Setter
@CopyFrom(RequestDetail.class)
public class RequestDetailVO {
    private Long id;
    private String title;
    private String status;
    private Long applicantId;

    /** 将应用结果映射为响应，保留历史记录的空申请人。 */
    public static RequestDetailVO from(RequestDetail result) {
        return CloneFactory.copy(new RequestDetailVO(), result);
    }
}