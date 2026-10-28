# 菜单

无需登录即可浏览菜单、提交新增/修改/删除；由厨师审核后发布。待审核列表默认只显示菜名，点击展开。

厨师登录后回到菜单，可直接保存菜品修改。点击菜品旁的 **+** 加入待采购，通过导航切换菜单、待采购和审核。

图片支持 JPG / PNG（≤ 5 MB）；视频栏可粘贴链接或整段分享文案，自动提取其中的网址。

- 菜单：https://menu.yaozher.com/
- 待采购：https://menu.yaozher.com/shopping
- 厨师审核：https://menu.yaozher.com/chef
- 厨师登录：https://menu.yaozher.com/chef/login
- 默认用户名：`chef`，升级后沿用原密码。

## 启动与停止

需要 Java 21、Node.js 22+；构建源码还需要 Maven。

双击桌面 Work 中的 **启动菜单网站.cmd** / **停止菜单网站.cmd**，或运行项目中的 `start-menu.cmd` / `stop-menu.cmd`。

首次设置密码后由 Windows 加密保存。也可通过 `MENU_CHEF_PASSWORD` 配置密码。重设密码：`powershell -ExecutionPolicy Bypass -File .\start-desktop.ps1 -ResetPassword`。

## 备菜备注

使用编辑器的 **•**、**//** 按钮，或直接输入：

```text
• 鸡蛋 2 个
• 番茄 1 个
// 鸡蛋打散，热锅炒熟
// 加入番茄翻炒
少盐
```

圆点行是材料，`//` 行是步骤，其他行是普通备注。待采购按菜品列出材料，可逐项打勾，展开“做法”备菜。清单和勾选保存在数据库；修改原料后同步更新，未变动原料保留勾选。

## 部署与数据

`menu.yaozher.com → cloudflared → 127.0.0.1:34455 前端 → 127.0.0.1:12233 后端`。Cloudflare 的 Service URL 使用 `http://127.0.0.1:34455`。

数据库位于 `data/menu.mv.db`，备份前先停止网站。运行 `mvn package` 构建并测试后端，`node --test *.test.mjs` 验证前端输入和代理。更多验证记录见 [VERIFICATION.md](VERIFICATION.md)。
