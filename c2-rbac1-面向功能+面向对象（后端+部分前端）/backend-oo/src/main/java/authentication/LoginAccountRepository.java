// 文件职责：按登录名读取本地账号和密码哈希。
// 协作关系：供 AccountAuthentication 校验及 DemoLoginAccounts 幂等初始化。
package authentication;

import org.springframework.data.jpa.repository.JpaRepository;

/** 按员工 ID 查询登录账号，供认证和演示初始化使用。 */
public interface LoginAccountRepository extends JpaRepository<LoginAccount, Long> {
}
