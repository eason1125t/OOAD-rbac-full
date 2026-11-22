# C2 共用前端

一份Vue/TypeScript源码提供/func/、/oo/，分别连接两套后端。当前认证为JWT、HttpOnly刷新Cookie及SharedWorker内存协调，已接入工作区、权限面板、申请按钮和部门员工查询；后端继续执行实际授权。

本目录不再重复维护进度和验收结果：

- [项目总结](../docs/项目总结.md)：功能、两版设计及Session到JWT的变化。
- [部署说明](../部署/README.md)：启动、生产网关、密钥和Swarm交接。
- [测试说明](../测试/README.md)：安装、构建、浏览器与部署后验收。

日常在本目录执行npm ci、npm run dev；前端5173同源代理到18181/18182，可用RBAC_FUNC_API_TARGET、RBAC_OO_API_TARGET调整目标。npm run build产出dist，生产仍需Nginx代理，Vite配置不会随dist生效。
