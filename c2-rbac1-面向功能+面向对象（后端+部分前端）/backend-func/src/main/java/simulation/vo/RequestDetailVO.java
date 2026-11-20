// 文件职责：返回申请 ID、标题、状态及可空申请人。
// 协作关系：由申请 Controller 使用 CloneFactory 映射服务详情结果。
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
