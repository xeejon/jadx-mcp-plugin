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




## 致谢
本项目基于以下几个优秀的开源项目构建：

jadx-mcp-server by Zinja

jadx-ai-mcp by Zinja

JADX 反编译器 by skylot

jadx-example-plugin by JADX 团队

特别感谢 Zinja 提供的基础 MCP 实现，以及 JADX 团队提供的强大反编译器。


## 许可证
本项目采用 Apache License 2.0 许可证。详见 LICENSE 文件。

原版权 © 2024 Zinja
修改版权 © 2025 xeejon
JADX 版权 © 2014-2024 skylot

## 贡献
欢迎贡献！请随时提交 issue 和 pull request。
