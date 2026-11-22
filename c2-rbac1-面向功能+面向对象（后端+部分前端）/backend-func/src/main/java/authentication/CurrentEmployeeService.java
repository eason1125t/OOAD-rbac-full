// 文件职责：根据可信员工 ID 读取最新有效身份，统一员工可登录条件。
// 协作关系：供安全过滤器、身份 Controller 和授权 Service 使用；不读取 Cookie 或计算业务权限。
package authentication;

import contract.BusinessAccessException;
import contract.BusinessAccessException.Reason;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import organization.Employee;
import organization.EmployeeRepository;

/** 身份适配层提供可信 ID，本服务只读取当前员工状态，不建立会话或授予权限。 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class CurrentEmployeeService {
    private final EmployeeRepository employees;

    /** 每次读取最新记录；过滤器使用空结果结束已失效的会话，不缓存员工状态。 */
    public Optional<Employee> findActive(Long employeeId) {
        return employeeId == null ? Optional.empty()
                : employees.findById(employeeId).filter(CurrentEmployeeService::canSignIn);
    }

    /** 用例需要有效身份时统一返回认证错误，存储故障保持原异常传播。 */
    public Employee requireActive(Long employeeId) {
        return findActive(employeeId)
                .orElseThrow(() -> new BusinessAccessException(Reason.AUTHENTICATION_REQUIRED));
    }

    /** 登录及授角共用资格规则；此谓词不能证明任意传入员工已经通过认证。 */
    public static boolean canSignIn(Employee employee) {
        return employee != null && employee.isEnabled() && employee.getOrganizationId() != null;
    }
}
