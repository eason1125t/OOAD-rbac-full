"""验证部署工具的凭据保留与配置拒绝边界，不连接集群，也不读取真实凭据。"""
import importlib.util
from pathlib import Path
import tempfile
import unittest

C2 = Path(__file__).resolve().parents[2]
SPEC = importlib.util.spec_from_file_location("c2_deploy", C2 / "部署/manage.py")
DEPLOY = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(DEPLOY)


class DeploymentSettingsTest(unittest.TestCase):
    """夹具留在被忽略的临时目录，不自动批量删除文件。"""

    def setUp(self):
        """每项使用独立合成夹具，不触碰部署所用 secret 目录。"""
        parent = C2 / "部署/结果/管理脚本夹具"
        parent.mkdir(parents=True, exist_ok=True)
        self.folder = Path(tempfile.mkdtemp(dir=parent))

    def test_creates_unique_secrets(self):
        """四个新凭据各自随机，且满足数据库/Redis 初始化的安全格式。"""
        DEPLOY.generate_secrets(self.folder)
        values = [path.read_text(encoding="ascii") for path in self.folder.iterdir()]
        self.assertEqual(4, len(set(values)))
        for value in values:
            self.assertRegex(value, r"^[0-9a-f]{64}$")

    def test_preserves_existing_credentials(self):
        """重复准备不能意外旋转已经使用的数据库密码。"""
        DEPLOY.generate_secrets(self.folder)
        before = {path.name: path.read_bytes() for path in self.folder.iterdir()}
        DEPLOY.generate_secrets(self.folder)
        self.assertEqual(before, {path.name: path.read_bytes() for path in self.folder.iterdir()})

    def test_rejects_invalid_existing_secret(self):
        """已有非法凭据应报告错误，不能偷偷覆盖后导致数据库认证不一致。"""
        existing = self.folder / "mysql_root_password"
        existing.write_text("invalid-secret", encoding="ascii")
        with self.assertRaises(ValueError):
            DEPLOY.generate_secrets(self.folder)
        self.assertEqual("invalid-secret", existing.read_text(encoding="ascii"))

    def test_rejects_ambiguous_settings(self):
        """重复键、非法项目名和端口拒绝，避免误用另一组服务或宿主机端口。"""
        path = self.folder / "settings"
        for content in ("RBAC_STACK_NAME=c2\nRBAC_STACK_NAME=other", "RBAC_STACK_NAME=bad/name",
                        "RBAC_HTTP_PORT=0", "RBAC_HTTP_PORT=65536", "RBAC_SESSION_SECURE=maybe"):
            path.write_text(content, encoding="utf-8")
            with self.subTest(content=content), self.assertRaises(ValueError):
                DEPLOY.read_settings(path)

    def test_does_not_execute_or_expand_values(self):
        """环境值只是配置数据，不触发 shell 展开。"""
        path = self.folder / "settings"
        path.write_text('RBAC_STACK_NAME=c2\nRBAC_FUNC_IMAGE="repo/${UNCHANGED}:tag"\n', encoding="utf-8")
        self.assertEqual("repo/${UNCHANGED}:tag", DEPLOY.read_settings(path)["RBAC_FUNC_IMAGE"])


if __name__ == "__main__":
    unittest.main()
