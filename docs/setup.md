# 安装、配对与接给 agent

目标是先让 agent 看见手机的一张真实截图，再逐步增加能力。

## 1. 准备手机和运行环境

首版使用 Android 14 的无障碍截图能力。我们用的是一加 8T，系统 Android 14；这不是要求买一台同款，也不是所有安卓版本都已经验证。

你需要：

- 一台 Android 14+ 手机和可用网络。
- 一台能运行 Python 3.11+ 的电脑/服务器，或 Docker 环境。
- 一个手机能访问的 HTTPS 地址，证书需要被手机正常信任。
- 一个能调用 MCP 工具并理解图片的 agent。

基础屏幕操作走无障碍服务；要用任意路径读写、列应用和 shell 等扩展，再准备已经 Root 的手机。本教程不提供通用刷机步骤：Bootloader 解锁方式和数据影响取决于具体设备，应该查对应厂商/Root 工具的说明。普通截图和点按可以先试，不必为了它先开启支付功能。

手机登录和支付设置由本人完成。安全锁不能由伴侣猜密码；需要无人值守唤醒时，考虑这台专用机的锁屏设置与存放环境。

## 2. 安装服务端

在自己的电脑或服务器上：

```bash
git clone https://github.com/lllq-123/phone-for-ai.git
cd phone-for-ai
python3 -m venv .venv
source .venv/bin/activate
pip install -e .
phone-for-ai init
phone-for-ai serve --host 127.0.0.1 --port 8765
```

`init` 创建私有状态目录和 operator token；不会把 token 打到终端。`serve` 先在前台运行，方便首次看日志。稳定使用时可以交给进程管理器，也可以用下面的容器方式。

| 环境变量 | 用途 |
| --- | --- |
| `PHONE_FOR_AI_STATE_DIR` | 服务端状态目录；命令记录、设备配对状态和截图都放这里 |
| `PHONE_FOR_AI_TOKEN_FILE` | operator token 文件；默认位于状态目录下的 `operator.token` |
| `PHONE_FOR_AI_API_BASE` | CLI/MCP 访问服务的完整 API 地址；默认 `http://127.0.0.1:8765/api/phone` |

首次 `init`、运行 `serve`、运行 `pair` 和启动 MCP 时，应使用同一组状态目录/凭据配置。token 只放自己的设备和服务器，不写进仓库、截图或给模型看的提示词。

### 可选：Docker

```bash
docker compose build
docker compose run --rm phone-for-ai init
docker compose up -d
docker compose exec phone-for-ai phone-for-ai pair
```

容器状态保存在 Compose 配置的数据卷中；不要把一次性容器的临时文件系统当持久存储。首次按 `compose.yaml` 的实际服务名和挂载核对运行结果。

## 3. 配置手机可访问的 HTTPS

服务默认只监听本机。用已有的 HTTPS 反向代理把 `/api/phone/*` 转发到 `127.0.0.1:8765`，或使用支持正确证书的私有网络入口。

[Caddy 示例](../deploy/Caddyfile.example)：

```caddyfile
phone.example.com {
    reverse_proxy 127.0.0.1:8765
}
```

把域名替换成自己的，配置 DNS 后按 Caddy 的安装方式运行。这个例子由 HTTPS 入口所在主机访问本机服务；如果反向代理也在 Docker 内，`127.0.0.1` 指向代理容器自身，应改为 Compose 网络中的服务地址。

手机填写的完整地址类似：

```text
https://phone.example.com/api/phone
```

手机主动向它发送请求。配置正常时，不需要给家中路由器开端口。请让反向代理允许至少 3 MiB 的截图上传和 20 秒以上的长轮询，并给网络波动留一点余量。

## 4. 安装 APK 与配对

从 [Releases](https://github.com/lllq-123/phone-for-ai/releases) 获取 APK，或自己构建。安装后：

1. 打开「AI 手机伙伴」。
2. 填入完整 HTTPS API 地址。
3. 在服务器运行 `phone-for-ai pair`，把新生成的一次性码填进手机。
4. 点击配对，确认手机显示已连接。
5. 按页面入口打开系统无障碍设置，手动启用这个伴侣的服务。
6. 需要 Root 文件/命令功能时，在伴侣中启用对应扩展，并在 Root 管理器中确认授权。

配对码不是长期控制密码：它会过期，而且只能成功使用一次。重新配对会替换当前设备关系，旧设备凭据失效。一个服务实例对应一台手机；要同时用多台，给每台单独的实例和凭据。

手机里的“清除本机配对”删除这台手机保存的凭据。要让服务器上的旧设备令牌也失效，在服务器重新运行 `phone-for-ai pair`；新配对码生成时就会撤销旧关系，并结束旧的实时控制会话。

如果系统弹出「允许打开某 App」，按本人的用途设置。Android 的受限设置、无障碍开关、电池优化和后台管理在不同系统里位置不同；只读服务状态能帮助判断是哪一层没有开好。

## 5. 接入 MCP

支持通用 `mcpServers` 配置格式的客户端可以参考：

```json
{
  "mcpServers": {
    "phone": {
      "command": "/ABSOLUTE/PATH/phone-for-ai/.venv/bin/phone-for-ai",
      "args": ["mcp"],
      "env": {
        "PHONE_FOR_AI_API_BASE": "http://127.0.0.1:8765/api/phone",
        "PHONE_FOR_AI_TOKEN_FILE": "/ABSOLUTE/PATH/phone-state/operator.token"
      }
    }
  }
}
```

替换两个绝对路径；`operator.token` 的位置以 `init` 实际创建位置为准。MCP 和服务端不在同一台机器时，API 地址改成自己的 HTTPS 地址，并把 operator token 通过自己的安全方式放到 MCP 所在机器，权限设为仅自己可读。

不同客户端的配置文件位置和格式可能不同；这里只展示 stdio 服务需要的命令、参数和环境变量。你也可以把这些字段交给 agent，让它按本机客户端配置。

把 [PHONE_GUIDE.md](../examples/PHONE_GUIDE.md) 的使用规则一并交给 agent，会更容易形成稳定的看图与动作循环。

人也可以在同一服务的根地址打开 [网页控制台](browser-console.md)，使用自己的 operator token 连接手机。实时画面依赖 Root 扩展与 WebCodecs；反向代理同时需要支持 WebSocket Upgrade。

## 6. 做一个不花钱的检查

先运行：

```bash
phone-for-ai status
phone-for-ai call phone_capture_screen --args '{}'
```

或直接对接好 MCP 的 agent 说：

> 看一下手机是否在线，再截一张当前屏幕，告诉我现在是什么 App。先停在这里。

然后试：

> 打开小红书，进入新建笔记，填一段临时文字并走到预览。看完不保存退出，不发布。

这个检查验证看屏幕、进入 App、中文输入和结果回读。付款和正式发布应使用明确要做的那一单/那一篇，不拿真实账户不断制造测试订单。

## 7. 常见卡点

| 现象 | 下一步 |
| --- | --- |
| 手机在线，操作报无障碍不可用 | 在手机系统设置里确认伴侣服务确实开启；安装更新后再看一次 |
| `invalid_command_basis` / `stale_screen_basis` | 重新截图，用新 command id 和前台包名操作；别复用旧图 |
| 打开 App 后出现系统确认框 | 先查看新截图，按实际系统弹窗处理 |
| 输入失败 | 先点击输入框获得焦点，读控件树；没有可编辑节点时可用剪贴板＋系统粘贴 |
| Root 命令失败 | 看真实退出码与输出，检查 Root 管理器授权；支持的调用方式是 `su -M -c` |
| 指令一直 pending | 查同一个 command id，检查手机后台限制和网络；不要连续创建同样的动作 |
| 动作完成但截图上传失败 | 补拍截图或查看业务现态，别重复点击付款/发帖 |
| 亮屏正常，熄屏后迟迟不回 | 检查省电、后台活动、厂商清理；本次新包在ColorOS遇到过中断，首次先保持亮屏 |
| `phone_wake` 完成却仍是黑图 | 先由本人点亮/解锁再截图；公共版在本机有此未解决边界，完成回执不是已唤醒的证明 |
| HTTPS 能开，截图上传却慢/失败 | 检查反代上传限制和手机到服务器的实际网络；VPN 不是普遍必需条件 |

我们的家庭网络曾出现小请求正常、JPEG 上传超时；换可用的网络路径后恢复。这个经验说明要测实际上传，不代表所有读者都必须开 VPN。
