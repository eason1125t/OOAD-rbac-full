package authentication;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/** 登录账号，以员工 ID 为主键并保存编码后的密码。 */
@Entity
@Table(name = "login_account")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class LoginAccount {
    @Id
    private Long employeeId;
    private String passwordHash;

    /** 为员工创建账号，调用方传入密码哈希。 */
    public LoginAccount(Long employeeId, String passwordHash) {
        this.employeeId = employeeId;
        this.passwordHash = passwordHash;
    }
}
