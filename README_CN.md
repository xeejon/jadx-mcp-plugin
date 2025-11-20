# JADX MCP 插件

**基于 [jadx-mcp-server](https://github.com/zinja-coder/jadx-mcp-server) 和 [jadx-ai-mcp](https://github.com/zinja-coder/jadx-ai-mcp) 的修改版本，提供增强的 Android APK 分析功能。**

**中文 | [📖 English Documentation](README.md)**

一个强大的模型上下文协议（MCP）插件，用于 JADX 反编译器集成，使 AI 助手能够直接通过 JADX 的反编译能力分析 Android APK 文件。

## 功能增强

1. **为类、字段、方法添加注释** - 增强代码可读性
2. **分页功能** - 针对可能返回内容过大导致超过 AI token 限制的调用做了分页处理

## 安装说明

### 只支持 jadx-gui 模式

**步骤 1：安装 JADX 插件**
1. 在 jadx 的菜单栏点击：Plugins → Manage plugins → Install plugin
2. 选择 jadx-mcp-plugin-1.0.0.jar 并安装
3. 重启 jadx

**步骤 2：安装 Python 依赖**
```bash
pip install -r ./python/requirements.txt
```
**步骤 3：配置 AI 的 MCP server**

方法一：直接编辑配置文件（注意修改路径为你的实际路径）：
4. 为AI安装mcp server，可直接编辑JSON文件类似(注意修改路径改为你的路径)：

```json
{
    "jadx-mcp-server": {
      "command": "python",
      "args": [
        "/path/to/jadx-mcp-plugin/python/jadx_mcp_complete.py"
      ]
    }
}
```
方法二：使用对应 AI 的命令行添加（以 Claude Code 为例）：
```bash
claude mcp add jadx-mcp-server -s user -- python /path/to/jadx-mcp-plugin/python/jadx_mcp_complete.py
claude --dangerously-skip-permissions
```
验证连接：
在 Claude Code 中输入 /mcp，会看到类似：

```text
2. jadx-mcp-server            ✔ connected · Enter to view details
```

## 使用示例

1. 对AI说类似：
```bash
'帮我把'C3773c.java'文件进行深度反混淆，要求为所有（包括内部类的）类名、字段名、方法名进行反混淆重命名并添加注释，并在每个操作的同时使用mcp同步到jadx。注意主类注释中类似'/* renamed from: androidx.core.i.d */'中的androidx.core.i.d 是原始混淆名"

```
2. 在jadx中点击file -> save project
3. 如果注释的添加在jadx中没有即时显示更改，可以在jadx中对任一类或方法按分号（;）手动更新，会刷新所有修改


## 编译
```bash
./gradlew.bat build
```


## 致谢
本项目基于以下几个优秀的开源项目构建：

jadx-mcp-server by Zinja

jadx-ai-mcp by Zinja

JADX 反编译器 by skylot

jadx-example-plugin by JADX 团队

特别感谢 Zinja 提供的基础 MCP 实现，以及 JADX 团队提供的强大反编译器。


## 法律警告
### 免责声明

jadx-mcp-plugin 工具严格仅用于教育、研究和道德安全评估目的。这些工具按"原样"提供，不作任何明示或暗示的担保。用户有责任确保他们对这些工具的使用符合所有适用的法律、法规和道德准则。

通过使用 jadx-mcp-plugin，您同意仅在被授权测试的环境中使用它们，例如您拥有或明确授权分析的应用程序。严禁将这些工具用于未经授权的反向工程、知识产权侵权或恶意活动。

jadx-mcp-plugin 的开发人员不对因使用或误用这些工具而造成的任何损害、数据丢失、法律后果或其他后果承担任何责任。用户对其行为和造成的任何影响承担全部责任。

负责任地使用。尊重知识产权。遵循道德黑客实践。

## 许可证
本项目采用 Apache License 2.0 许可证。详见 LICENSE 文件。

原版权 © 2024 Zinja
修改版权 © 2025 xeejon
JADX 版权 © 2014-2024 skylot

## 贡献
欢迎贡献！请随时提交 issue 和 pull request。


## ☕ 支持作者

如果 jadx-mcp-plugin 让你的逆向工程工作更轻松，欢迎请我喝杯咖啡提提神～

**扫码请喝咖啡 ☕**
<img src="alipay_qr.jpg" alt="支付宝收款码" style="width: 200px; height: auto;">

每一份支持都是持续优化的动力！
