# 一方菜单

一个无需访客登录的菜单网站，使用 Java 21、Spring Boot、Spring Security、Spring Data JPA 和 H2 文件数据库。页面为原生 HTML / CSS / JavaScript；Node.js 使用内置 HTTP 模块提供独立前端入口，无需 npm 安装、前端构建或外部 CDN。

## 已实现

- 公开菜单：浏览、按菜名/备注搜索、按图片/视频筛选。
- 匿名提交：新增、修改和删除菜品都先生成待审申请，不会立即改变菜单。
- 菜品字段：菜名必填，图片、视频链接、备注选填；支持保留、更换、移除图片。
- 独立审核入口：登录后查看待审与历史申请、修改前后对比、批准/驳回、填写审核备注。
- 审核记录保留操作、内容快照、审核人和时间；重复审核或版本过期返回冲突提示。
- 数据和图片保存于后端文件数据库，进程重启不会丢失。

首次启动菜单为空，不会自动添加示例菜品。请从公开页面提交第一道菜，再登录后台批准。

## Windows 启动

双击桌面 `Work\启动菜单网站.cmd`，或项目中的 `start-menu.cmd`，可一键启动前后端并自动打开 https://menu.yaozher.com/；前端入口使用 34455，Spring Boot 后端使用 12233。首次运行设置管理员密码后，密码会通过 Windows DPAPI 加密保存在当前用户的 `%LOCALAPPDATA%\YaoZherMenu\admin-password.dpapi`，之后无需重复输入；默认账号为 `admin`。此文件不进入 Git 仓库。若设置了 `MENU_ADMIN_PASSWORD`，优先使用环境变量且不保存。

启动窗口保持打开，按 Ctrl+C 停止网站。重复双击会打开已经运行的本项目；12233 或 34455 被其他程序占用时会提示退出。停止网站后，运行 `powershell -NoProfile -ExecutionPolicy Bypass -File .\start-desktop.ps1 -ResetPassword` 可重新设置密码。

若网站在后台运行，双击桌面 `Work\停止菜单网站.cmd` 或项目中的 `stop-menu.cmd` 可同时停止本项目的后端与前端入口，不影响其他网站和 cloudflared。

随附已验证的 `target/menu-1.0.0.jar`，直接运行需要 **JDK 21** 和 **Node.js 22 或以上** 加入 PATH。修改源码后重新构建，还需要 **Maven 3.6.3 或以上**。

```powershell
cd E:\Projects\Web\Menu
.\start.ps1 -SkipBuild
```

脚本以隐藏输入的方式询问本次运行的管理员密码。默认管理员账号为 `admin`；密码至少 12 个字符，UTF-8 编码不超过 72 字节。密码仅通过进程环境传给后端，不写入源码或配置文件。下次启动可以重新指定密码。

修改源码后运行 `.\start.ps1`，会先执行测试与打包。在不允许执行本地 PowerShell 脚本的环境中，可在当前会话按本机策略授权，或手动执行以下命令。

```powershell
mvn clean package
# 通过终端安全输入或部署环境设置 MENU_ADMIN_PASSWORD 后：
mkdir data\tmp -Force
node frontend-server.mjs --jar "$PWD\target\menu-1.0.0.jar" --tmp "$PWD\data\tmp"
```

- 公开菜单：<https://menu.yaozher.com/>
- 审核后台：<https://menu.yaozher.com/admin>
- 登录页面：<https://menu.yaozher.com/login>

没有设置管理员密码时，应用会拒绝启动。访客浏览、添加和提交修改均不需要登录。

## Cloudflare Tunnel

访问链路：`https://menu.yaozher.com → cloudflared → http://127.0.0.1:34455 → http://127.0.0.1:12233`。

当前 cloudflared 是由 Cloudflare 控制台远程管理的 Windows 服务。Published application 路由应设置为：Hostname `menu.yaozher.com`，Service URL **`http://127.0.0.1:34455`**，无需给本地服务配置 HTTPS 证书。公网的 HTTPS 由 Cloudflare 提供。

前端入口流式代理页面、API 和图片，浏览器始终同源访问，因此无需 CORS。管理员页面及接口仍由后端鉴权。直接本地访问使用 <http://127.0.0.1:34455/>；12233 是后端服务端口。启动脚本会同时管理两个进程，后端退出时前端也停止。

前后端默认只监听本机地址。后端仅信任本机代理头；前端仅在本机 cloudflared 请求使用指定域名且包含合法 CF-Connecting-IP 时接纳 HTTPS 和访客 IP，避免直接接受浏览器提交的任意转发头。

Cloudflare 路由设置参考：[官方说明](https://developers.cloudflare.com/tunnel/get-started/)。修改仓库文件不会自动变更 Cloudflare 控制台中的路由。

如本机浏览器经系统代理访问时报连接中断，而直连正常，可在自己的代理软件中将 `menu.yaozher.com` 设置为直连。本项目不会更改系统代理配置。
## 配置

| 环境变量 | 默认值 | 用途 |
| --- | --- | --- |
| `PORT` | `12233` | Spring Boot 后端端口 |
| `FRONTEND_PORT` | `34455` | 前端入口端口 |
| `SERVER_ADDRESS` | `127.0.0.1` | 后端监听地址，仅供本机前端访问 |
| `MENU_ADMIN_USERNAME` | `admin` | 审核账号 |
| `MENU_ADMIN_PASSWORD` | 无，必填 | 审核密码 |
| `MENU_DATA_PATH` | `./data/menu` | H2 文件路径前缀，不带扩展名 |
| `MENU_DB_PASSWORD` | 空 | 数据库连接密码，已有数据库应保持一致 |
| `COOKIE_SECURE` | `false` | HTTPS 部署设为 `true` |

`data/menu.mv.db` 同时包含菜单、申请和图片。备份前正常停止应用，再复制整个 `data` 目录。恢复时使用相同数据库密码。生产部署时保护数据库文件和环境变量，使用 HTTPS。代理会传递可信的 HTTPS 信息，公网会话自动使用 Secure Cookie；本地 HTTP 调试仍可登录。仅允许 HTTPS 的部署也可把 `COOKIE_SECURE` 设为 `true`。H2 适合小规模、单实例使用；不应让多个应用进程同时写同一数据库文件。

当前公开菜单和管理页面是同一 Spring Boot 服务的两个入口，管理页面及 API 均由服务端保护。需要不同域名时，可以在反向代理将菜单域名与审核域名路由到相同服务，并保留后端鉴权。

## 登录方式

本项目使用 Spring Security 管理员账号、BCrypt 密码校验和 HttpOnly Session Cookie。所有写操作（包括匿名提交与登录）都要求 CSRF token，退出使用 POST 并销毁会话。登录有效期为 30 分钟无操作。

根据 [OpenAI 官方网站登录文档](https://developers.openai.com/siwc/website)，Sign in with ChatGPT 目前仅面向部分商业合作伙伴试用，需要已注册的 OAuth 客户端、回调地址与相关配置。本项目尚未接入该服务，当前提供可直接使用的本地管理员登录，无需 OpenAI API Key 或 ChatGPT 账号。取得客户端配置后可使用 Spring Security OAuth2/OIDC 接入，并为审核员设置主体白名单；不能让任何成功登录的 ChatGPT 用户都获得审核权限。Sites 的平台登录依赖其托管环境，也不等同于本地 Spring Boot 登录。

## 图片与输入

- PNG / JPEG，单张不超过 5 MiB，宽高各不超过 4096 像素，总像素不超过 1600 万。
- 后端检查真实图片格式并重新编码；不保留上传文件名或 EXIF 等元数据。
- 未审核图片仅审核员可访问；匿名用户只能读取当前公开菜单引用的图片。
- 菜名最多 80 字符，备注最多 2000 字符，视频链接最多 2048 字符，审核备注最多 1000 字符。
- 视频链接只接受有效的 HTTP/HTTPS 网址，以新标签页打开，不自动抓取或嵌入第三方内容。
- 单 IP 每小时最多提交 30 次；每分钟最多尝试登录 10 次，超限返回 429。限流为单进程内存计数，重启后重置。cloudflared 通过本机前端入口访问时，使用校验后的 CF-Connecting-IP 还原访客 IP，避免所有访客共享同一限额。

## 开发与验证

```powershell
mvn test
node --test frontend-server.test.mjs
mvn package
```

集成测试使用独立内存数据库和专用测试密码，不会读写运行时数据。覆盖登录/退出、CSRF、管理员权限、审批后发布、驳回、编辑/删除、重复审批、版本冲突、输入验证和图片访问控制。

核心接口：

| 方法 | 路径 | 权限 / 行为 |
| --- | --- | --- |
| GET | `/api/menu` | 公开菜单 |
| GET | `/api/csrf` | 获取当前会话 CSRF token 和 headerName |
| POST | `/api/submissions` | 匿名提交 multipart 表单，需 CSRF |
| POST | `/api/auth/login` | 表单账号密码，需 CSRF |
| POST | `/api/auth/logout` | 退出，需 CSRF |
| GET | `/api/admin/me` | 当前审核员 |
| GET | `/api/admin/submissions?status=PENDING` | 审核员列表，支持 APPROVED / REJECTED / ALL |
| POST | `/api/admin/submissions/{id}/approve` | 批准，JSON `{ "note": "..." }` |
| POST | `/api/admin/submissions/{id}/reject` | 驳回，JSON `{ "note": "..." }` |
| GET | `/media/{uuid}` | 公开图片或审核员访问待审图片 |

提交字段：`operation`（CREATE / UPDATE / DELETE）、`name`、`videoUrl`、`notes`、`imageAction`（KEEP / REMOVE / REPLACE）和可选文件 `image`。修改/删除还需要 `dishId` 和从菜单接口取得的 `expectedVersion`。审核前菜单版本若已改变，批准返回 409；审核员应驳回旧申请，请访客基于新菜单重新提交。

目录：`src/main/java` 为后端，`src/main/resources/static` 为前端，`src/test/java` 为集成测试。
