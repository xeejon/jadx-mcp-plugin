# JADX MCP Plugin

**Based on modified versions of [jadx-mcp-server](https://github.com/zinja-coder/jadx-mcp-server) and [jadx-ai-mcp](https://github.com/zinja-coder/jadx-ai-mcp), providing enhanced Android APK analysis functionality.**

**[📖 中文文档](README_CN.md)** | English

A powerful Model Context Protocol (MCP) plugin for JADX decompiler integration, enabling AI assistants to directly analyze Android APK files through JADX's decompilation capabilities.

## Feature Enhancements

1. **Add comments for classes, fields, and methods** - Enhance code readability
2. **Pagination functionality** - Handle large content responses that might exceed AI token limits with pagination support

## Installation Instructions

### Only supports jadx-gui mode

**Step 1: Install JADX Plugin**
1. In JADX menu bar, click: Plugins � Manage plugins � Install plugin
2. Select jadx-mcp-plugin-1.0.0.jar and install
3. Restart JADX

**Step 2: Install Python Dependencies**
```bash
pip install -r ./python/requirements.txt
```

**Step 3: Configure AI's MCP Server**

#### Method 1: Direct JSON file editing (remember to modify paths to your actual paths):
4. Install MCP server for AI, you can directly edit JSON file similar to (remember to change paths to your paths):

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

#### Method 2: Use corresponding AI's command line (Claude Code example):
```bash
claude mcp add jadx-mcp-server -s user -- python /path/to/jadx-mcp-plugin/python/jadx_mcp_complete.py
claude --dangerously-skip-permissions
```

#### Verify connection:
Enter `/mcp` in Claude Code, you should see something like:

```text
2. jadx-mcp-server             connected � Enter to view details
```

## Usage Examples

1. Tell AI something like:
```bash
'Please help me perform deep deobfuscation on the 'C3773c.java' file, requiring deobfuscation renaming and adding comments for all (including inner classes) class names, field names, and method names, and sync to jadx using MCP for each operation. Note that the androidx.core.i.d in comments like '/* renamed from: androidx.core.i.d */' in the main class is the original obfuscated name"
```

2. Click file -> save project in jadx
3. If comment additions are not immediately displayed in jadx, you can manually update by pressing semicolon (;) on any class or method in jadx, which will refresh all modifications

## Acknowledgments
This project is built based on the following excellent open source projects:

- jadx-mcp-server by Zinja
- jadx-ai-mcp by Zinja
- JADX Decompiler by skylot
- jadx-example-plugin by JADX team

Special thanks to Zinja for providing the basic MCP implementation, and the JADX team for providing the powerful decompiler.

## License
This project is licensed under the Apache License 2.0. See LICENSE file for details.

Original copyright � 2024 Zinja
Modified copyright � 2025 xeejon
JADX copyright � 2014-2024 skylot

## Contributing
Contributions are welcome! Please feel free to submit issues and pull requests.