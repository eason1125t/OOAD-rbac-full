package authentication;

import org.springframework.data.jpa.repository.JpaRepository;

/** 按员工 ID 查询登录账号，供认证和演示初始化使用。 */
public interface LoginAccountRepository extends JpaRepository<LoginAccount, Long> {
}
