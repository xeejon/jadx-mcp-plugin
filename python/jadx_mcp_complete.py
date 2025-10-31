#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
JADX MCP 服务器
"""

# /// script
# requires-python = ">=3.10"
# dependencies = [ "fastmcp", "httpx", ]
# ///

import argparse
import asyncio
import httpx
import json
import logging
import sys
from typing import Any, Dict, List, Optional, Union

from fastmcp import FastMCP
from fastmcp.server.middleware.logging import StructuredLoggingMiddleware

# 默认端口
DEFAULT_JADX_POR = 8656
DEFAULT_THRESHOLD = 200

# 配置日志
# Set up logging configuration
logger = logging.getLogger()
logger.setLevel(logging.ERROR)

# Console handler for logging to the console
console_handler = logging.StreamHandler()
console_handler.setLevel(logging.ERROR)
console_handler.setFormatter(
    logging.Formatter("%(asctime)s - %(levelname)s - %(message)s")
)
logger.addHandler(console_handler)

# 初始化
mcp = FastMCP(name="jadx-mcp-server")
mcp.add_middleware(StructuredLoggingMiddleware(include_payloads=True))

# 解析参数
parser = argparse.ArgumentParser("MCP Server for Jadx")
parser.add_argument(
    "--http",
    help="Serve MCP Server over HTTP stream.",
    action="store_true",
    default=False,
)
parser.add_argument(
    "--port",
    help="Specify the port number for --http to serve on. (default:8657)",
    default=8657,
    type=int,
)
parser.add_argument(
    "--jadx-port",
    help=f"Specify the port on which JADX AI MCP Plugin is running on. (default:{DEFAULT_JADX_POR})",
    default=DEFAULT_JADX_POR,
    type=int,
)
parser.add_argument(
    "--cache-threshold",
    help=f"Specify cache threshol. (default:{DEFAULT_THRESHOLD})",
    default=DEFAULT_THRESHOLD,
    type=int,
)
args = parser.parse_args()

JADX_HTTP_BASE = (
    f"http://127.0.0.1:{args.jadx_port}"  # Base URL for the JADX-AI-MCP Plugin
)


# 健康检查
def health_ping() -> Union[str, dict]:
    """健康检查 - 同步版本用于启动前验证"""
    try:
        with httpx.Client() as client:
            resp = client.get(f"{JADX_HTTP_BASE}/health", timeout=10.0)
            resp.raise_for_status()
            return resp.text
    except httpx.HTTPStatusError as e:
        return {"error": f"HTTP error {e.response.status_code}: {e.response.text}"}
    except httpx.RequestError as e:
        return {"error": f"Request failed: {str(e)}"}
    except Exception as e:
        return {"error": f"Unexpected error: {str(e)}"}


# 通用HTTP请求函数
async def get_from_jadx(endpoint: str, params: dict = None) -> Union[str, dict]:
    """通用的JADX API请求方法"""
    if params is None:
        params = {}

    try:
        async with httpx.AsyncClient(timeout=60.0) as client:
            resp = await client.get(f"{JADX_HTTP_BASE}/{endpoint}", params=params)
            resp.raise_for_status()
            response = resp.text

            if isinstance(response, str):
                try:
                    return json.loads(response)
                except Exception:
                    return {"response": response}

            return response

    except httpx.HTTPStatusError as e:
        error_message = f"HTTP error {e.response.status_code}: {e.response.text}"
        logger.error(error_message)
        return {"error": f"{error_message}."}
    except httpx.RequestError as e:
        error_message = f"Request failed: {str(e)}"
        logger.error(error_message)
        return {"error": f"{error_message}."}
    except Exception as e:
        error_message = f"Unexpected error: {str(e)}"
        logger.error(error_message)
        return {"error": f"{error_message}."}


async def post_to_jadx(endpoint: str, data: dict = None) -> Union[str, dict]:
    """POST请求到JADX API"""
    if data is None:
        data = {}

    try:
        async with httpx.AsyncClient(timeout=60.0) as client:
            resp = await client.post(f"{JADX_HTTP_BASE}/{endpoint}", data=data)
            resp.raise_for_status()
            response = resp.text

            if isinstance(response, str):
                try:
                    return json.loads(response)
                except Exception:
                    return {"response": response}

            return response

    except httpx.HTTPStatusError as e:
        error_message = f"HTTP error {e.response.status_code}: {e.response.text}"
        logger.error(error_message)
        return {"error": f"{error_message}."}
    except httpx.RequestError as e:
        error_message = f"Request failed: {str(e)}"
        logger.error(error_message)
        return {"error": f"{error_message}."}
    except Exception as e:
        error_message = f"Unexpected error: {str(e)}"
        logger.error(error_message)
        return {"error": f"{error_message}."}


# 工具函数
def build_class_params(
    class_raw_name: str = None, class_name: str = None
) -> Dict[str, str]:
    """构建类参数 - 简化版本：原始名 + 普通名"""
    params = {}

    # 优先级：原始名 > 普通名
    if class_raw_name:
        params["class_raw_name"] = class_raw_name
    if class_name:
        params["class_name"] = class_name

    return params


def build_method_params(
    class_raw_name: str = None,
    class_name: str = None,
    method_original_name: str = None,
    method_name: str = None,
    method_signature: str = None,
) -> Dict[str, str]:
    """构建方法参数 - 简化版本：方法名必须（method_original_name或method_name二选一），方法签名可选

    参数说明：
    - method_original_name 和 method_name 必须提供其中一个
    - method_signature (方法签名) 是可选的，用于区分重载方法
    """
    params = {}

    # 类参数
    class_params = build_class_params(class_raw_name, class_name)
    params.update(class_params)

    # 方法参数 - 必须提供method_original_name或method_name其中一个
    if method_original_name:
        params["method_original_name"] = method_original_name
        # 同时添加method参数以满足Java代码的要求
        params["method"] = method_original_name
    if method_name:
        params["method_name"] = method_name
        params["method"] = method_name

    # 方法签名（original_name，即short_id）- 可选参数，用于区分重载方法
    if method_signature:
        params["method_signature"] = method_signature

    return params


def build_field_params(
    class_raw_name: str = None,
    class_name: str = None,
    field_raw_name: str = None,
    field_name: str = None,
) -> Dict[str, str]:
    """构建字段参数 - 简化版本：原始名 + 普通名"""
    params = {}

    # 类参数
    class_params = build_class_params(class_raw_name, class_name)
    params.update(class_params)

    # 字段参数 - 优先级：原始名 > 普通名
    if field_raw_name:
        params["field_raw_name"] = field_raw_name
    if field_name:
        params["field_name"] = field_name

    return params


# 基础功能工具
@mcp.tool()
async def get_current_class() -> dict:
    """获取当前在JADX GUI中选中的类源代码

    返回当前在JADX GUI界面中用户打开并选中的类的完整Java源代码。
    这个工具不需要任何参数，直接返回当前活动的类。

    Returns:
        dict: 包含当前选中类源代码的字典
    """

    result = await get_from_jadx("get-current-class")
    return result


@mcp.tool()
async def get_all_classes(page_index: int = 1, page_size: int = 100) -> dict:
    """获取项目中所有类的完整列表，支持智能缓存和分页

    获取当前反编译项目中所有类的列表，包括内部类、匿名类等。
    返回的数据会被自动缓存，支持通过 get_classes_page 进行分页浏览。

    适合用于：
    - 浏览项目中的所有类
    - 搜索特定类名
    - 了解项目整体结构

    Args:
    page_index (int): 页码，从1开始。默认为1
    page_size (int): 每页大小。默认为100

    Returns:
        dict: 包含类列表和缓存信息的字典
    """
    params = {}
    if page_index:
        params["page_index"] = page_index
    if page_size:
        params["page_size"] = page_size

    result = await get_from_jadx("get-all-classes", params)

    return result


@mcp.tool()
async def get_selected_text(page_index: int = 1, page_size: int = 1000) -> dict:
    """获取当前在JADX GUI中选中的文本内容

    返回用户在JADX GUI界面中当前选中的文本片段。
    这可能是代码的一部分、注释或其他文本内容。
    Args:
    page_index (int): 页码，从1开始。默认为1
    page_size (int): 每页大小。默认为1000

    Returns:
        dict: 包含选中文本内容的字典
    """
    params = {}
    if page_index:
        params["page_index"] = page_index
    if page_size:
        params["page_size"] = page_size
    result = await get_from_jadx("get-selected-text", params)
    return result


# 类相关工具
@mcp.tool()
async def get_class_source(
    class_raw_name: str = None,
    class_name: str = None,
    page_index: int = 1,
    page_size: int = 1000,
) -> dict:
    """获取指定类的完整Java源代码，支持精确查找和智能缓存

    获取指定类的完整Java源代码。推荐使用原始类名进行查找，因为原始类名永远不会改变。
    如果同时提供原始名和普通名，系统会优先使用原始名进行精确匹配。


    Args:
        class_raw_name (str, optional): (最高优先级) 原始类名，如 "androidx.core.i.d"、"androidx.core.i.d$a"，一般在注释中会有"/* renamed from: androidx.core.i.d */"
        class_name (str, optional): (中等优先级)普通类名，如 "com.example.MainActivity"
        page_index (int): 页码，从1开始。默认为1
        page_size (int): 每页大小。默认为1000
    """
    params = build_class_params(class_raw_name, class_name)
    if page_index:
        params["page_index"] = page_index
    if page_size:
        params["page_size"] = page_size
    result = await get_from_jadx("get-class-source", params)
    return result


@mcp.tool()
async def get_class_info(class_raw_name: str = None, class_name: str = None) -> dict:
    """获取指定类的详细信息，包括元数据和统计信息

    获取类的完整信息，包括类名、包名、类型（普通类/内部类）、方法数量、字段数量等详细信息。
    适合用于了解类的整体结构和特征。

    Args:
        同 get_class_source 的参数优先级

    Returns:
        dict: 包含类详细信息的字典
    """
    params = build_class_params(class_raw_name, class_name)
    result = await get_from_jadx("get-class-info", params)

    if result is not None and isinstance(result, dict):
        result["found_by"] = next((k for k, v in params.items() if v), "unknown")
        result["success"] = True
    return result


@mcp.tool()
async def get_smali_of_class(
    class_raw_name: str = None,
    class_name: str = None,
    page_index: int = 1,
    page_size: int = 1000,
) -> dict:
    """获取指定类的Smali字节码代码

    获取指定类的Smali反汇编代码，用于底层代码分析和理解。
    Smali是Android的虚拟机汇编语言，比Java源代码更接近机器码。

    Args:
        同 get_class_source 的参数优先级
        page_index (int): 页码，从1开始。默认为1
        page_size (int): 每页大小。默认为1000

    Returns:
        dict: 包含Smali代码的字典
    """
    params = build_class_params(class_raw_name, class_name)
    if page_index:
        params["page_index"] = page_index
    if page_size:
        params["page_size"] = page_size
    result = await get_from_jadx("get-smali-of-class", params)
    return result


# 方法相关工具
@mcp.tool()
async def get_method_source(
    class_raw_name: str = None,
    class_name: str = None,
    method_original_name: str = None,
    method_name: str = None,
    method_signature: str = None,
    page_index: int = 1,
    page_size: int = 1000,
) -> dict:
    """获取指定方法的完整Java源代码，支持精确查找

    获取指定方法的完整Java源代码。推荐使用原始方法名和原始类名进行精确查找。
    支持多种查找方式，可以通过方法名、方法签名、或者组合查找。

    🔍 **参数优先级说明**：

    **类参数优先级**：
    1. class_raw_name (最高优先级) - 原始类名，永远不会改变
    2. class_name (中等优先级) - 普通类名

    **方法参数优先级**：
    1. method_original_name (最高优先级) - 原始方法名，永远不会改变
    2. method_name (中等优先级) - 普通方法名
    3. method_signature (特殊优先级，可选) - 方法的完整签名，如 "onCreate(Landroid/os/Bundle;)V"

    - 对于重载方法，使用 method_signature 进行区分

    Args:
        class_raw_name (str, optional): (最高优先级) - 原始类名，永远不会改变 - 如 "androidx.core.i.d"、"androidx.core.i.d$a"，一般在jadx导出的java代码中，类注释中会有类似"/* renamed from: androidx.core.i.d */"
        class_name (str, optional): 普通类名，如 "com.example.MainActivity"
        method_original_name (str, optional): (最高优先级) - 原始方法名，永远不会改变，如 "y"，一般在jadx导出的java代码中，方法注释中会有类似"/* renamed from: y */"
        method_name (str, optional): 普通方法名，如 "onCreate"
        method_signature (str, optional):(特殊优先级，可选)- 方法完整签名，如 "onCreate(Landroid/os/Bundle;)V",对于重载方法，使用 method_signature 进行区分
        page_index (int): 页码，从1开始。默认为1
        page_size (int): 每页大小。默认为1000
    Returns:
        dict: 包含方法源码的字典
    """
    params = build_method_params(
        class_raw_name, class_name, method_original_name, method_name, method_signature
    )
    if page_index:
        params["page_index"] = page_index
    if page_size:
        params["page_size"] = page_size
    result = await get_from_jadx("get-method-source", params)
    return result


@mcp.tool()
async def get_method_source_page(
    page_index: int = 1, lines_per_page: int = 200
) -> dict:
    """分页浏览方法的Java源代码

    使用 get_method_source 获取的方法源码进行分页浏览。
    适合查看大型方法的源代码，避免一次性显示过多内容。

    Args:
        page_index (int): 页码，从1开始。默认为1
        lines_per_page (int): 每页显示的行数。默认为200行

    Returns:
        dict: 分页的方法源码内容，包含当前页的源码和行号信息
    """
    return cache.get_method_source_page(
        page_index=page_index, lines_per_page=lines_per_page
    )


@mcp.tool()
async def get_method_info(
    class_raw_name: str = None,
    class_name: str = None,
    method_original_name: str = None,
    method_name: str = None,
    method_signature: str = None,
) -> dict:
    """获取指定方法的详细信息，包括签名、参数、返回类型、访问修饰符等

    获取方法的完整信息，包括方法名、签名、参数列表、返回类型、访问修饰符、是否为静态方法等。
    适合用于了解方法的完整特征和调用信息。

    Args:
        参数同 get_method_source

    Returns:
        dict: 包含方法详细信息的字典
    """
    params = build_method_params(
        class_raw_name,
        class_name,
        method_original_name,
        method_name,
        method_signature,
    )
    result = await get_from_jadx("get-method-info", params)

    if result is not None and isinstance(result, dict):
        result["found_by"] = next((k for k, v in params.items() if v), "unknown")
        result["success"] = True
    return result


@mcp.tool()
async def search_method(
    method_name: str = None,
    class_raw_name: str = None,
    class_name: str = None,
    original_name: str = None,
    method_signature: str = None,
    page_index: int = 1,
    lines_per_page: int = 200,
) -> dict:
    """搜索包含指定关键词的方法，支持全项目搜索或限定类搜索

    在整个项目中或指定类中搜索包含特定关键词的方法。支持按方法名搜索或按方法签名精确搜索。
    适合用于查找特定功能的实现方法。

    🔍 **搜索模式说明**：

    - 搜索方法名包含指定关键词的所有方法
    - class_raw_name/class_name 可选，用于限定搜索范围

    - 使用方法签名进行精确匹配,支持重载方法的区分
    - original_name 参数优先级高于 method_name 参数

    Args:
        method_name (str): 要搜索的方法名关键词
        class_raw_name (str, optional): 限定搜索的原始类名
        class_name (str, optional): 限定搜索的普通类名
        original_name (str, optional): 方法的原始名，例如 y
        method_signature (str, optional): 方法的方法签名，用于精确搜索
        page_index (int): 页码，从1开始。默认为1
        lines_per_page (int): 每页大小。默认为200
    Returns:
        dict: 搜索结果，包含匹配的方法列表及其详细信息
    """
    # 添加类参数
    params = build_method_params(
        class_raw_name, class_name, original_name, method_name, method_signature
    )
    if page_index:
        params["page_index"] = page_index
    if lines_per_page:
        params["lines_per_page"] = lines_per_page
    return await get_from_jadx("search-method", params)


@mcp.tool()
async def get_methods(class_raw_name: str = None, class_name: str = None) -> dict:
    """获取指定类中的所有方法列表

    获取指定类中定义的所有方法，包括构造方法、普通方法、静态方法等。
    返回每个方法的基本信息，如方法名、签名、访问修饰符等。

    🔍 **参数优先级说明**：
    1. class_raw_name (最高优先级) - 原始类名，最可靠
    2. class_name (中等优先级) - 普通类名

    ✅ **推荐用法**：
    - 使用 class_raw_name 进行最精确的查找
    - 适合了解类的完整方法列表

    Args:
        class_raw_name (str, optional): 原始类名，如 "androidx.core.i.d"、"androidx.core.i.d$a"，一般在注释中会有"/* renamed from: androidx.core.i.d */"
        class_name (str, optional): 普通类名，如 "com.example.MainActivity"

    Returns:
        dict: 包含类中所有方法信息的列表
    """
    params = build_class_params(class_raw_name, class_name)
    result = await get_from_jadx("get-methods", params)

    if result is not None and isinstance(result, list):
        return {
            "methods": result,
            "total_methods": len(result),
            "found_by": next((k for k, v in params.items() if v), "unknown"),
            "success": True,
        }
    else:
        return result


@mcp.tool()
async def get_fields(class_raw_name: str = None, class_name: str = None) -> dict:
    """获取指定类中的所有字段列表

    获取指定类中定义的所有字段（成员变量），包括实例字段和静态字段。
    返回每个字段的基本信息，如字段名、类型、访问修饰符等。

    Args:
                class_raw_name (str, optional): 原始类名，如 "androidx.core.i.d"、"androidx.core.i.d$a"，一般在注释中会有"/* renamed from: androidx.core.i.d */"
        class_name (str, optional): 普通类名，如 "com.example.MainActivity"

    Returns:
        dict: 包含类中所有字段信息的列表
    """
    params = build_class_params(class_raw_name, class_name)
    result = await get_from_jadx("get-fields", params)

    if result is not None and isinstance(result, list):
        return {
            "fields": result,
            "total_fields": len(result),
            "found_by": next((k for k, v in params.items() if v), "unknown"),
            "success": True,
        }
    else:
        return result


@mcp.tool()
async def get_method_parameters(
    class_raw_name: str = None,
    class_name: str = None,
    method_original_name: str = None,
    method_name: str = None,
    method_signature: str = None,
) -> dict:
    """获取指定方法的参数详细信息

    获取方法的参数列表，包括每个参数的名称、类型、注解等信息。
    适合用于了解方法的调用接口和参数要求。

    Args:
        参数同 get_method_source

    Returns:
        dict: 包含方法参数详细信息的列表
    """
    params = build_method_params(
        class_raw_name, class_name, method_original_name, method_name, method_signature
    )
    result = await get_from_jadx("get-method-parameters", params)

    if result is not None and isinstance(result, list):
        return {
            "parameters": result,
            "total_parameters": len(result),
            "found_by": next((k for k, v in params.items() if v), "unknown"),
            "success": True,
        }
    else:
        return result


# Android特定功能
@mcp.tool()
async def get_android_manifest(page_index: int = 1, page_size: int = 1000) -> dict:
    """获取AndroidManifest.xml文件的完整内容

    获取Android应用的AndroidManifest.xml文件内容，这是Android应用的核心配置文件。
    包含应用组件声明、权限要求、元数据等重要信息。

    Args:
        page_index (int): 页码，从1开始。默认为1
        page_size (int): 每页大小。默认为1000

    Returns:
        dict: 包含AndroidManifest.xml内容的字典
    """
    params = {}
    if page_index:
        params["page_index"] = page_index
    if page_size:
        params["page_size"] = page_size
    result = await get_from_jadx("get-manifest", params)
    return result


@mcp.tool()
async def get_main_activity(page_index: int = 1, page_size: int = 1000) -> dict:
    """获取主Activity(启动Activity)的完整Java源代码

    获取Android应用的主Activity（LAUNCHER Activity）的完整Java源代码。
    主Activity是应用启动时首先显示的界面，通常是应用的入口点。

    Args:
        page_index (int): 页码，从1开始。默认为1
        page_size (int): 每页大小。默认为1000

    Returns:
        dict: 包含主Activity源代码的字典
    """
    params = {}
    if page_index:
        params["page_index"] = page_index
    if page_size:
        params["page_size"] = page_size
    result = await get_from_jadx("get-main-activity", params)
    return result


@mcp.tool()
async def get_main_application_classes_code(page_index: int = 1, page_size: int = 1000) -> dict:
    """获取主要Application类的源代码

    获取Android应用的Application类及其相关主要类的源代码。
    Application类是应用的全局状态管理类，在应用启动时初始化。

    Args:
        page_index (int): 页码，从1开始。默认为1
        page_size (int): 每页大小。默认为1000

    Returns:
        dict: 包含主要Application类源代码的字典
    """
    params = {}
    if page_index:
        params["page_index"] = page_index
    if page_size:
        params["page_size"] = page_size
    result = await get_from_jadx("get-main-application-classes-code", params)
    return result


@mcp.tool()
async def get_main_application_classes_names() -> dict:
    """获取主要Application类的名称列表

    获取Android应用中Application类及其相关主要类的名称列表。
    这些类通常是应用的全局管理类和核心功能类。

    Returns:
        dict: 包含主要Application类名称的列表
    """
    result = await get_from_jadx("get-main-application-classes-names")

    if result is not None and isinstance(result, list):
        return {
            "main_application_classes": result,
            "total_classes": len(result),
            "success": True,
        }
    else:
        return result


# 资源文件功能
@mcp.tool()
async def get_strings(page_index: int = 1, page_size: int = 1000) -> dict:
    """获取应用中所有字符串资源(strings.xml)的内容

    获取Android应用strings.xml文件中的所有字符串资源。
    这些字符串通常用于应用的文本显示，包括按钮文本、提示信息等。

    Args:
        page_index (int): 页码，从1开始。默认为1
        page_size (int): 每页大小。默认为1000

    Returns:
        dict: 包含字符串资源的字典
    """
    params = {}
    if page_index:
        params["page_index"] = page_index
    if page_size:
        params["page_size"] = page_size
    result = await get_from_jadx("get-strings", params)
    return result


@mcp.tool()
async def get_list_all_resource_files_names(page_index: int = 1, page_size: int = 100) -> dict:
    """获取应用中所有资源文件的名称列表

    获取Android应用resources目录下所有资源文件的名称列表。
    包括布局文件、图片文件、字符串文件、颜色文件等各种资源。

    Args:
        page_index (int): 页码，从1开始。默认为1
        page_size (int): 每页大小。默认为100

    Returns:
        dict: 包含所有资源文件名称的列表
    """
    params = {}
    if page_index:
        params["page_index"] = page_index
    if page_size:
        params["page_size"] = page_size
    result = await get_from_jadx("get-list-all-resource-files-names", params)
    return result


@mcp.tool()
async def get_resource_file(filename: str, page_index: int = 1, page_size: int = 1000) -> dict:
    """获取指定资源文件的内容

    获取指定资源文件的完整内容。支持各种类型的资源文件，如布局文件、图片文件、配置文件等。

    Args:
        filename (str): 资源文件的名称，如 "activity_main.xml" 或 "ic_launcher.png"
        page_index (int): 页码，从1开始。默认为1
        page_size (int): 每页大小。默认为1000

    Returns:
        dict: 包含资源文件内容的字典
    """
    params = {"name": filename}
    if page_index:
        params["page_index"] = page_index
    if page_size:
        params["page_size"] = page_size
    result = await get_from_jadx("get-resource-file", params)
    return result


# # 高级分析功能
# @mcp.tool()
# async def get_method_metadata(
#     class_raw_name: str = None,
#     class_name: str = None,
#     method_original_name: str = None,
#     method_name: str = None,
#     method_signature: str = None,
# ) -> dict:
#     """获取方法的详细元数据信息，包括字节码层面的详细信息

#     获取方法的深层元数据信息，包括方法的字节码信息、调试信息、注解信息等。
#     这些信息通常用于高级代码分析和逆向工程。

#     Args:
#         参数同 get_method_source

#     Returns:
#         dict: 包含方法元数据的详细信息
#     """
#     params = build_method_params(
#         class_raw_name, class_name, method_original_name, method_name, method_signature
#     )
#     result = await get_from_jadx("get-method-metadata", params)

#     if result is not None and isinstance(result, list):
#         cache.set_method_metadata(result)
#         return cache.auto_page_method_metadata()
#     else:
#         return result


@mcp.tool()
async def get_method_instructions(
    class_raw_name: str = None,
    class_name: str = None,
    method_original_name: str = None,
    method_name: str = None,
    method_signature: str = None,
    page_index: int = 1,
    page_size: int = 1000,
) -> dict:
    """获取方法的字节码指令信息

    获取方法的Dalvik字节码指令列表，这是Java代码编译后的底层指令表示。
    适合用于深入理解方法的执行流程和优化分析。

    Args:
        参数同 get_method_source
        page_index (int): 页码，从1开始。默认为1
        page_size (int): 每页大小。默认为1000

    Returns:
        dict: 包含方法指令信息的列表
    """
    params = build_method_params(
        class_raw_name, class_name, method_original_name, method_name, method_signature
    )
    if page_index:
        params["page_index"] = page_index
    if page_size:
        params["page_size"] = page_size
    result = await get_from_jadx("get-method-instructions", params)
    return result


# @mcp.tool()
# async def get_method_code_refs_by_line(
#     class_raw_name: str = None,
#     class_name: str = None,
#     method_original_name: str = None,
#     method_name: str = None,
#     method_signature: str = None,
#     line: int = 0,
#     page_index: int = 1,
#     page_size: int = 1000,
# ) -> dict:
#     """获取方法中指定行号的代码引用信息
#
#     获取方法源码中指定行号位置的代码引用信息，包括该行引用的其他类、方法、字段等。
#     适合用于代码依赖分析和调用关系分析。
#
#     Args:
#         参数同 get_method_source
#         line (int): 要分析的行号，从1开始计数，默认为0表示整个方法
#         page_index (int): 页码，从1开始。默认为1
#         page_size (int): 每页大小。默认为1000
#
#     Returns:
#         dict: 包含指定行代码引用信息的字典
#     """
#     params = build_method_params(
#         class_raw_name, class_name, method_original_name, method_name, method_signature
#     )
#     params["line"] = line
#     if page_index:
#         params["page_index"] = page_index
#     if page_size:
#         params["page_size"] = page_size
#     result = await get_from_jadx("get-method-code-refs-by-line", params)
#
#     return result


# 重命名功能
@mcp.tool()
async def rename_class(
    class_raw_name: str = None, class_name: str = None, new_name: str = None
) -> dict:
    """重命名指定类

    重命名指定的类，包括更新所有引用该类的地方。

    Args:
        类参数同 get_class_source
        new_name (str): 新的类名, 如"MainActivity",如为空则重置为原始类名

    Returns:
        dict: 重命名操作的结果信息
    """
    params = build_class_params(class_raw_name, class_name)
    if new_name:
        params["newName"] = new_name

    return await post_to_jadx("rename-class", params)


@mcp.tool()
async def rename_method(
    class_raw_name: str = None,
    class_name: str = None,
    method_original_name: str = None,
    method_name: str = None,
    method_signature: str = None,
    new_name: str = None,
) -> dict:
    """重命名指定方法

    重命名指定的方法，包括更新所有调用该方法的地方。


    Args:
        类参数同 get_class_source
        方法参数同 get_method_source
        new_name (str): 新的方法名, 如"onCreate",如为空则重置为原始方法名

    Returns:
        dict: 重命名操作的结果信息
    """
    # 构建方法参数
    params = build_method_params(
        class_raw_name, class_name, method_original_name, method_name, method_signature
    )
    if new_name:
        params["newName"] = new_name

    return await post_to_jadx("rename-method", params)


@mcp.tool()
async def rename_field(
    class_raw_name: str = None,
    class_name: str = None,
    field_raw_name: str = None,
    field_name: str = None,
    new_name: str = None,
) -> dict:
    """重命名字段

    重命名指定的字段（成员变量），包括更新所有访问该字段的地方。

    Args:
        class_raw_name (str, optional): (最高优先级) - 原始类名，永远不会改变 - 如 "androidx.core.i.d"、"androidx.core.i.d$a"，一般在jadx导出的java代码中，类注释中会有类似"/* renamed from: androidx.core.i.d */"
        class_name (str, optional): 普通类名，如 "com.example.MainActivity"
        field_raw_name (str, optional): (最高优先级) - 原始字段名，永远不会改变，如 "y"，一般在jadx导出的java代码中，字段注释中会有类似"/* renamed from: y */"
        field_name (str, optional): 普通字段名，如 "userId"
        new_name (str): 新的字段名, 如"userId",如为空则重置为原始字段名

    Returns:
        dict: 重命名操作的结果信息
    """
    params = build_field_params(class_raw_name, class_name, field_raw_name, field_name)
    if new_name:
        params["newName"] = new_name

    return await post_to_jadx("rename-field", params)

@mcp.tool()
async def rename_method_parameter(    class_raw_name: str = None,
    class_name: str = None,
    method_original_name: str = None,
    method_name: str = None,
    method_signature: str = None,
    parameter_index: int = 0,
    new_name: str = None,) -> dict:
    """重命名指定方法参数

    重命名指定的方法参数。

    Args:
        类参数同 get_class_source
        方法参数同 get_method_source
        parameter_index (int): 参数索引，从0开始计数，默认为0
        new_name (str): 新的方法参数名, 如"userId",如为空则重置为原始方法参数名

    Returns:
        dict: 重命名操作的结果信息
    """
    params = build_method_params(class_raw_name, class_name, method_original_name, method_name, method_signature)
    if new_name:
        params["newName"] = new_name
    if parameter_index:
        params["parameterIndex"] = parameter_index

    return await post_to_jadx("rename-method-parameter", params)
# 注释功能
@mcp.tool()
async def add_class_comment(
    class_raw_name: str = None,
    class_name: str = None,
    comment: str = "",
    style: str = "JAVADOC",
) -> dict:
    """为指定类添加注释

    为指定的类添加Javadoc或行注释。注释会保存在项目中，并在反编译时显示。
    适合为代码添加说明文档和使用指导。
    注意：注释使用原生字符串格式，不需要额外添加/*或\n等

    Args:
        类参数同 get_class_source
        comment (str): 要添加的注释内容
        style (str): 注释风格，"JAVADOC"(默认) 或 "LINE"，类注释风格应为"JAVADOC"

    Returns:
        dict: 添加注释操作的结果信息
    """
    params = build_class_params(class_raw_name, class_name)
    params["comment"] = comment
    params["style"] = style

    return await post_to_jadx("add-class-comment", params)


@mcp.tool()
async def add_method_comment(
    class_raw_name: str = None,
    class_name: str = None,
    method_original_name: str = None,
    method_name: str = None,
    method_signature: str = None,
    comment: str = "",
    style: str = "JAVADOC",
) -> dict:
    """为指定方法添加注释

    为指定的方法添加Javadoc或行注释。注释会保存在项目中，并在反编译时显示。
    适合为方法添加功能说明、参数描述、使用示例等。
    注意：注释使用原生字符串格式，不需要额外添加/*或\n等

    Args:
        类参数同 get_class_source
        方法参数同 get_method_source
        comment (str): 要添加的注释内容
        style (str): 注释风格，"JAVADOC"(默认) 或 "LINE"

    Returns:
        dict: 添加注释操作的结果信息
    """
    # 构建类参数
    class_params = build_class_params(class_raw_name, class_name)

    # 构建方法参数
    method_params = {}
    if method_original_name:
        method_params["method_original_name"] = method_original_name
    elif method_name:
        method_params["method_name"] = method_name
    if method_signature:
        method_params["method_signature"] = method_signature
    method_params["comment"] = comment
    method_params["style"] = style

    # 合并参数
    params = {**class_params, **method_params}

    return await post_to_jadx("add-method-comment", params)


@mcp.tool()
async def add_field_comment(
    class_raw_name: str = None,
    class_name: str = None,
    field_raw_name: str = None,
    field_name: str = None,
    comment: str = "",
    style: str = "LINE",
) -> dict:
    """为指定字段添加注释

    为指定的字段（成员变量）添加Javadoc或行注释。注释会保存在项目中，并在反编译时显示。
    适合为字段添加用途说明、取值范围、使用示例等。
    注意：注释使用原生字符串格式，不需要额外添加/*或\n等

    Args:
        class_raw_name (str, optional): (最高优先级) - 原始类名，永远不会改变 - 如 "androidx.core.i.d"、"androidx.core.i.d$a"，一般在jadx导出的java代码中，类注释中会有类似"/* renamed from: androidx.core.i.d */"
        class_name (str, optional): 普通类名，如 "com.example.MainActivity"
        field_raw_name (str, optional): (最高优先级) - 原始字段名，永远不会改变，如 "y"，一般在jadx导出的java代码中，字段注释中会有类似"/* renamed from: y */"
        field_name (str, optional): 普通字段名，如 "userId"
        comment (str): 要添加的注释内容
        style (str): 注释风格，"JAVADOC" 或 "LINE"(默认)，字段注释如果超过一行可用JAVADOC风格

    Returns:
        dict: 添加注释操作的结果信息
    """
    # 构建类参数
    class_params = build_class_params(class_raw_name, class_name)

    # 构建字段参数
    field_params = build_field_params(
        class_raw_name, class_name, field_raw_name, field_name
    )
    field_params["comment"] = comment
    field_params["style"] = style

    # 合并参数
    params = {**class_params, **field_params}

    return await post_to_jadx("add-field-comment", params)






# 系统功能
@mcp.tool()
async def health() -> dict:
    """检查JADX MCP服务器连接状态和健康状况

    检查与JADX后端服务器的连接状态，确认服务器是否正常运行。
    这是诊断连接问题的首选工具。

    Returns:
        dict: 包含服务器状态信息的字典
    """
    result = await get_from_jadx("health")
    return {
        "health": result,
        "jadx_url": JADX_HTTP_BASE,
        "success": "health" in str(result).lower(),
    }


def main():
    """主函数 - 启动JADX MCP完整缓存服务器"""
    print("=" * 90)
    print("    JADX MCP Complete Cache Server - 完整缓存版本")
    print("    参考客户端版本，为所有大内容实现完整缓存和分页系统")
    print(f"    目标JADX服务器: {JADX_HTTP_BASE}")
    print("=" * 90)

    # 启动前健康检查
    print("检查JADX服务器连接...")
    try:
        result = health_ping()
        if "error" in result:
            print(f"[FAILED] 健康检查失败: {result}")
        else:
            print(f"[SUCCESS] 健康检查通过: {result}")
    except Exception as e:
        print(f"[ERROR] 健康检查异常: {e}")

    # 运行服务器
    print("启动MCP...")
    if args.http:
        port = args.port if args.port else 8651
        mcp.run(transport="streamable-http", port=port)
    else:
        mcp.run()


if __name__ == "__main__":
    main()
