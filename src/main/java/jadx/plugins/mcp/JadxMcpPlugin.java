package jadx.plugins.mcp;

import io.javalin.Javalin;
import io.javalin.http.Context;

import jadx.api.JavaClass;
import jadx.api.JavaField;
import jadx.api.JavaMethod;
import jadx.api.ResourceFile;
import jadx.api.metadata.ICodeNodeRef;
import jadx.api.plugins.JadxPlugin;
import jadx.api.plugins.JadxPluginContext;
import jadx.api.plugins.JadxPluginInfo;
import jadx.api.plugins.JadxPluginInfoBuilder;
import jadx.api.*;
import jadx.api.data.*;
import jadx.api.data.impl.*;
import jadx.api.plugins.gui.*;
import jadx.api.plugins.events.types.NodeRenamedByUser;
import jadx.api.security.IJadxSecurity;
import jadx.core.utils.android.AndroidManifestParser;
import jadx.core.utils.android.AppAttribute;
import jadx.core.utils.android.ApplicationParams;
import jadx.core.utils.exceptions.JadxRuntimeException;
// import jadx.core.utils.CodeUtils;
import jadx.core.xmlgen.ResContainer;
import jadx.gui.JadxWrapper;
import jadx.gui.ui.MainWindow;
import jadx.gui.settings.JadxSettings;
import jadx.gui.utils.pkgs.JRenamePackage;
import jadx.core.dex.instructions.args.*;
import jadx.api.metadata.annotations.*;
// import jadx.core.dex.instructions.args.SSAVar;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import jadx.core.dex.nodes.*;

import jadx.gui.settings.JadxProject;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.w3c.dom.Element;

import org.w3c.dom.Document;

import javax.swing.*;

import java.awt.*;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;
import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.NoSuchProviderException;
import java.util.*;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import java.util.prefs.Preferences;
import java.util.function.Function;


public class JadxMcpPlugin implements JadxPlugin {

	public static final String PLUGIN_ID = "mcp-plugin";

	private final jadx.plugins.mcp.McpOptions options = new McpOptions();

	private static final Logger logger = LoggerFactory.getLogger(JadxMcpPlugin.class);

	private JadxGuiContext guiContext;
	private MainWindow mainWindow;
	private Javalin app;

	private final AtomicBoolean shouldStop = new AtomicBoolean(false);

	/**
	 * 定时任务调度器，用于延迟启动HTTP服务器 采用单线程执行器，确保初始化的顺序性
	 */
	private ScheduledExecutorService scheduler;

	/**
	 * 服务器启动状态标志，使用volatile确保多线程可见性 防止重复启动HTTP服务器
	 */
	private volatile boolean serverStarted = false;

	// ======================== 延迟初始化配置常量 ========================
	/**
	 * 最大启动等待时间：30秒 防止插件无限等待JADX加载完成
	 */
	private static final int MAX_STARTUP_ATTEMPTS = 30;

	/**
	 * 检查间隔：1秒 定时检查JADX是否完全加载的时间间隔
	 */
	private static final int CHECK_INTERVAL_SECONDS = 1;

	// ======================== 用户配置相关 ========================
	/**
	 * 用户偏好设置中端口号的键名 用于持久化保存用户配置的端口号
	 */
	private static final String PREF_KEY_PORT = "jadx_ai_mcp_port";

	/**
	 * 默认HTTP服务器端口 用户未自定义时使用的默认端口
	 */
	private static final int DEFAULT_PORT = 8656;

	/**
	 * 当前使用的端口号 从用户偏好设置中读取，用户可以在GUI中修改
	 */
	private int currentPort = DEFAULT_PORT;

	/**
	 * 用户偏好设置对象 用于读取和保存用户的配置信息
	 */
	private Preferences prefs;

	@Override
	public JadxPluginInfo getPluginInfo() {
		return JadxPluginInfoBuilder.pluginId(PLUGIN_ID)
				.name("Jadx mcp plugin")
				.description("Add jadx mcp plugin for AI")
				.homepage("https://github.com/jadx-decompiler/jadx-example-plugin")
				.requiredJadxVersion("1.5.2, r2472")
				.build();
	}

	@Override
	public void init(JadxPluginContext context) {
		context.registerOptions(options);
		if (options.isEnable()) {
			// context.addPass(new AddCommentPass());

			logger.info("Jadx mcp plugin load");

			try {
				// 获取JADX主窗口引用，这是访问反编译数据的入口
				this.mainWindow = (MainWindow) context.getGuiContext().getMainFrame();
				if (this.mainWindow == null) {
					logger.error("JADX-AI-MCP插件：主窗口为null，JADX AI MCP将无法启动。");
					return;
				}

				// 初始化用户偏好设置，读取保存的端口号
				prefs = Preferences.userNodeForPackage(JadxMcpPlugin.class);
				currentPort = prefs.getInt(PREF_KEY_PORT, DEFAULT_PORT);

				// 向JADX菜单栏添加插件菜单项
				addMenuItems();

				logger.info("JADX-AI-MCP插件：正在初始化并等待JADX完全加载...");

				// 创建单线程调度器，用于延迟启动HTTP服务器
				scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
					Thread t = new Thread(r, "JADX-AI-MCP-Startup");
					t.setDaemon(true); // 设置为守护线程，不阻止JADX退出
					return t;
				});

				// 启动延迟初始化流程
				startDelayedInitialization();

			} catch (Exception e) {
				logger.error("JADX-AI-MCP插件：初始化错误：" + e.getMessage(), e);
			}

		}
	}

	// ======================== 延迟初始化核心逻辑 ========================

	/**
	 * 启动延迟初始化流程
	 * <p>
	 * **重要：这是插件启动时机的核心控制！**
	 * <p>
	 * 工作原理： 1. 等待2秒后开始检查（给JADX一些启动时间） 2. 每1秒检查一次JADX是否完全加载 3.
	 * 如果检测到APK反编译完成，立即启动HTTP服务器 4. 如果30秒后仍未就绪，强制启动服务器（避免无限等待）
	 * <p>
	 * 检查内容包括： - JadxWrapper是否存在 - 反编译器是否初始化 - 是否有反编译的类数据
	 * <p>
	 * 设计考虑： - 使用守护线程避免阻止JADX退出 - 设置超时机制防止无限等待 - 双重检查确保不会重复启动
	 */
	private void startDelayedInitialization() {

		// 设置定时任务：2秒后开始，每1秒检查一次
		scheduler.scheduleAtFixedRate(() -> {
			try {
				// 如果服务器已启动，关闭调度器
				if (serverStarted) {
					scheduler.shutdown();
					return;
				}

				// 检查JADX是否完全加载（包括APK反编译完成）
				if (isJadxFullyLoaded()) {
					logger.info("JADX-AI-MCP插件：JADX完全加载，正在启动HTTP服务器...");
					start(); // 启动HTTP服务器
					serverStarted = true;
					scheduler.shutdown(); // 关闭调度器
				} else {
					logger.debug("JADX-AI-MCP插件：等待JADX完全加载...");
				}
			} catch (Exception e) {
				logger.error("JADX-AI-MCP插件：延迟初始化期间出错：" + e.getMessage(), e);
			}
		}, 2, CHECK_INTERVAL_SECONDS, TimeUnit.SECONDS);

		// 设置超时保护：30秒后如果还没启动，强制启动
		scheduler.schedule(() -> {
			if (!serverStarted) {
				logger.warn("JADX-AI-MCP插件：等待JADX加载超时，强制启动服务器...");
				try {
					start(); // 强制启动HTTP服务器
					serverStarted = true;
				} catch (Exception e) {
					logger.error("JADX-AI-MCP插件：超时后启动服务器失败：" + e.getMessage(), e);
				}
			}
		}, MAX_STARTUP_ATTEMPTS, TimeUnit.SECONDS);
	}

	/**
	 * 检查JADX是否完全加载
	 * <p>
	 * **这是判断APK反编译是否完成的关键方法！**
	 * <p>
	 * 检查条件： 1. 主窗口存在 2. JadxWrapper存在（包含反编译数据） 3. 类列表不为null 4. 反编译器实例存在
	 * <p>
	 * 技术细节： - 使用JadxWrapper作为反编译数据的主要访问入口 - 检查getIncludedClassesWithInners()返回值
	 * - 验证getDecompiler()实例的可用性
	 *
	 * @return true表示JADX完全加载且APK反编译完成
	 */
	private boolean isJadxFullyLoaded() {
		try {
			if (mainWindow == null) {
				return false;
			}

			// 获取JadxWrapper，这是访问反编译数据的入口
			JadxWrapper wrapper = mainWindow.getWrapper();
			if (wrapper == null) {
				logger.debug("JADX-AI-MCP插件：JadxWrapper为null，尚未就绪");
				return false;
			}

			// 检查包装器是否正确初始化且有类数据
			List<JavaClass> classes = wrapper.getIncludedClassesWithInners();
			if (classes == null) {
				logger.debug("JADX-AI-MCP插件：类列表为null，尚未就绪");
				return false;
			}

			// 检查反编译器是否可用，确保APK反编译完成
			boolean hasDecompilerData = wrapper.getDecompiler() != null;

			if (!hasDecompilerData) {
				logger.debug("JADX-AI-MCP插件：反编译器尚未就绪");
				return false;
			}

			logger.debug("JADX-AI-MCP插件：发现{}个类，JADX似乎已加载", classes.size());
			return true;

		} catch (Exception e) {
			logger.debug("JADX-AI-MCP插件：就绪检查期间出现异常：" + e.getMessage());
			return false;
		}
	}

	// ======================== 服务器和资源管理 ========================

	/**
	 * 清理方法，用于正确关闭服务器和调度器
	 * <p>
	 * 在JADX关闭或插件卸载时被调用，确保所有资源被正确释放 包括关闭HTTP服务器、停止定时任务等
	 */
	public void shutdown() {
		try {
			// 关闭调度器，停止所有定时任务
			if (scheduler != null && !scheduler.isShutdown()) {
				scheduler.shutdown();
				// 等待5秒，如果还未完成则强制关闭
				if (!scheduler.awaitTermination(5, TimeUnit.SECONDS)) {
					scheduler.shutdownNow();
				}
			}

			// 停止HTTP服务器
			if (app != null) {
				app.stop();
				logger.info("JADX-AI-MCP插件：HTTP服务器已停止");
			}
		} catch (Exception e) {
			logger.error("JADX-AI-MCP插件：关闭期间出错：" + e.getMessage(), e);
		}
	}

	/**
	 * 启动HTTP服务器
	 * <p>
	 * 此方法在确认JADX完全加载后（包括APK反编译完成）被调用。 启动Javalin HTTP服务器并注册所有API路由。
	 * <p>
	 * API功能包括： - 代码访问：获取当前类、所有类、类源代码、Smali代码等 - 搜索功能：按方法名搜索、获取类的方法和字段 -
	 * 重命名功能：支持类、方法、字段的重命名操作 - 资源访问：AndroidManifest.xml、字符串资源、资源文件等 -
	 * 健康检查：服务器状态监控
	 */
	public void start() {
		try {
			// 创建并启动Javalin HTTP服务器
			logger.info("创建并启动Javalin HTTP服务器");
			app = Javalin.create().start(currentPort);

			// 注册所有API路由
			logger.info("注册所有API路由");
			registerApiRoutes();

			// 启动成功日志信息
			logger.info("启动成功日志信息");
			printStartupBanner();
			logger.info("start启动完成");

		} catch (Exception e) {
			logger.error("JADX-AI-MCP插件错误：无法启动HTTP服务器。异常：" + e.getMessage());
		}
	}

	/**
	 * 注册所有API路由
	 */
	private void registerApiRoutes() {
		// 代码访问相关路由
		app.get("/get-current-class", this::handleCurrentClass);
		app.get("/get-all-classes", this::handleAllClasses);
		app.get("/get-selected-text", this::handleSelectedText);
		app.get("/get-class-source", this::handleClassSource);
		app.get("/get-smali-of-class", this::handleSmaliOfClass);

		//new
		app.get("/get-class-info", this::handleClassInfo);

		//方法
		app.get("/get-method-source", this::handleMethodByName);
		app.get("/get-method-info", this::handleMethodInfo);

		app.get("/search-method", this::handleSearchMethod);

		// 类结构相关路由
		app.get("/get-methods", this::handleMethodsOfClass);
		app.get("/get-fields", this::handleFieldsOfClass);
		app.get("/get-method-parameters", this::handleGetParameters);

		// Android应用相关路由
		app.get("/get-manifest", this::handleManifest);
		app.get("/get-main-activity", this::handleMainActivity);
		app.get("/get-main-application-classes-code", this::handleMainApplicationClassesCode);
		app.get("/get-main-application-classes-names", this::handleMainApplicationClassesNames);

		// 资源文件相关路由
		app.get("/get-strings", this::handleStrings);
		app.get("/get-list-all-resource-files-names", this::handleListAllResourceFilesNames);
		app.get("/get-resource-file", this::handleGetResourceFile);

		// 重命名相关路由
		app.post("/rename-class", this::handleRenameClass);
		app.post("/rename-method", this::handleRenameMethod);
		app.post("/rename-field", this::handleRenameField);
		app.post("/rename-method-parameter", this::handleRenameMethodParameter);

		//new
		// app.post("/rename-package", this::handleRenamePackage);  //fuck
		app.post("/add-class-comment", this::handleAddClassComment);
		app.post("/add-method-comment", this::handleAddMethodComment);
		app.post("/add-field-comment", this::handleAddFieldComment);

		app.get("/get-method-metadata", this::handleGetMethodMetadata);

		app.get("/get-method-instructions", this::handleGetMethodInstructions);

		// 代码引用分析路由
		app.get("/get-method-code-refs-by-line", this::handleGetMethodCodeRefsByLine);

		// 系统相关路由
		app.get("/health", this::handleHealth);
	}

	/**
	 * 打印启动横幅信息
	 */
	private void printStartupBanner() {
		logger.info(
				"// -------------------- JADX AI MCP PLUGIN -------------------- //\n"
						+ " - 由 Jafar Pathan (https://github.com/zinja-coder) 开发\n"
						+ " - 报告问题: https://github.com/zinja-coder/jadx-ai-mcp\n\n");
		logger.info("JADX AI MCP插件HTTP服务器已启动，地址：http://127.0.0.1:" + currentPort + "/");
	}

	// ======================== GUI菜单集成 ========================

	/**
	 * 向JADX菜单栏添加插件菜单项
	 * <p>
	 * 创建"Plugins"菜单（如果不存在）并添加"JADX AI MCP Server"子菜单， 提供端口配置、服务器重启等功能的GUI入口。
	 * <p>
	 * 菜单结构： Plugins └── JADX AI MCP Server ├── Configure Port... (配置端口) └──
	 * Default Port (恢复默认端口) ├── Restart Server (重启服务器) └── Server Status
	 * (服务器状态)
	 */
	private void addMenuItems() {
		SwingUtilities.invokeLater(() -> {
			try {
				// 获取JADX主窗口的菜单栏
				JMenuBar menuBar = mainWindow.getJMenuBar();
				if (menuBar == null) {
					logger.warn("JADX-AI-MCP插件：菜单栏未找到，无法添加菜单项");
					return;
				}

				// 查找现有的Plugins菜单或创建新菜单
				JMenu pluginsMenu = findOrCreatePluginsMenu(menuBar);

				// 创建JADX AI MCP子菜单
				JMenu jadxAIMcpMenu = new JMenu("JADX AI MCP Server");

				// 添加配置端口菜单项
				JMenuItem configurePortItem = new JMenuItem("Configure Port...");
				configurePortItem.addActionListener(new ActionListener() {
					@Override
					public void actionPerformed(ActionEvent e) {
						showPortConfigDialog();
					}
				});

				// Restart Server menu item
				JMenuItem restartServerItem = new JMenuItem("Restart Server");
				restartServerItem.addActionListener(new ActionListener() {
					@Override
					public void actionPerformed(ActionEvent e) {
						restartServer();
					}
				});

				// set back to default port
				JMenuItem setDefaultPortItem = new JMenuItem("Default Port");
				setDefaultPortItem.addActionListener(new ActionListener() {
					@Override
					public void actionPerformed(ActionEvent e) {
						setToDefautlPort();
					}
				});

				// Server Status menu item
				JMenuItem serverStatusItem = new JMenuItem("Server Status");
				serverStatusItem.addActionListener(new ActionListener() {
					@Override
					public void actionPerformed(ActionEvent e) {
						showServerStatus();
					}
				});

				jadxAIMcpMenu.add(configurePortItem);
				jadxAIMcpMenu.addSeparator();
				jadxAIMcpMenu.add(setDefaultPortItem);
				jadxAIMcpMenu.add(restartServerItem);
				jadxAIMcpMenu.add(serverStatusItem);

				pluginsMenu.add(jadxAIMcpMenu);

				logger.info("JADX-AI-MCP插件：菜单项添加成功");

			} catch (Exception e) {
				logger.error("JADX-AI-MCP插件：添加菜单项时出错：" + e.getMessage(), e);
			}
		});
	}

	/**
	 * 查找现有的Plugins菜单或创建新菜单
	 *
	 * @param menuBar JADX主窗口的菜单栏
	 * @return Plugins菜单对象
	 */
	private JMenu findOrCreatePluginsMenu(JMenuBar menuBar) {
		// 查找现有的"Plugins"或"Plugin"菜单
		for (int i = 0; i < menuBar.getMenuCount(); i++) {
			JMenu menu = menuBar.getMenu(i);
			if (menu != null && ("Plugins".equals(menu.getText()) || "Plugin".equals(menu.getText()))) {
				return menu;
			}
		}

		// 如果没有找到Plugins菜单，创建一个新菜单
		JMenu pluginsMenu = new JMenu("Plugins");

		// 尝试在Help菜单之前插入，否则添加到末尾
		boolean inserted = false;
		for (int i = 0; i < menuBar.getMenuCount(); i++) {
			JMenu menu = menuBar.getMenu(i);
			if (menu != null && "Help".equals(menu.getText())) {
				menuBar.add(pluginsMenu, i);
				inserted = true;
				break;
			}
		}

		if (!inserted) {
			menuBar.add(pluginsMenu);
		}

		return pluginsMenu;
	}

	/**
	 * 恢复默认端口设置
	 * <p>
	 * 将端口号重置为默认值8650，并保存到用户偏好设置中。 然后自动重启HTTP服务器以应用新的端口配置。
	 * <p>
	 * 操作流程： 1. 重置端口号为默认值8650 2. 保存新端口到用户偏好设置 3. 显示确认对话框通知用户 4. 调用重启服务器方法应用新配置
	 */
	private void setToDefautlPort() {
		// 重置端口为默认值8650
		currentPort = 8650;

		// 保存新端口到用户偏好设置，确保下次启动时使用该端口
		prefs.putInt(PREF_KEY_PORT, currentPort);

		// 显示确认对话框，通知用户端口已更新，服务器将自动重启
		JOptionPane.showMessageDialog(
				mainWindow,
				"Port updated to " + currentPort + ". Server will restart automatically.",
				"Port Updated",
				JOptionPane.INFORMATION_MESSAGE);

		// 调用重启服务器方法，使用新的端口配置
		restartServer();
	}

	/**
	 * 显示端口配置对话框
	 * <p>
	 * 创建并显示一个图形用户界面对话框，允许用户输入新的端口号。 对话框包含输入验证、范围检查和错误提示功能。
	 * <p>
	 * 对话框功能： 1. 显示当前端口号 2. 提供输入框让用户输入新端口 3. 显示有效端口范围提示（1024-65535） 4.
	 * 验证输入的端口号是否有效 5. 处理用户确认或取消操作
	 * <p>
	 * 验证规则： - 端口号必须是整数 - 端口号范围：1024-65535 - 如果输入无效，显示错误提示 -
	 * 如果新端口与当前端口相同，不执行任何操作
	 */
	private void showPortConfigDialog() {
		// 创建主面板，使用GridBagLayout布局管理器
		JPanel panel = new JPanel(new GridBagLayout());
		GridBagConstraints gbc = new GridBagConstraints();

		// 设置"Server Port:"标签
		gbc.gridx = 0;
		gbc.gridy = 0;
		gbc.anchor = GridBagConstraints.WEST;
		gbc.insets = new Insets(5, 5, 5, 5);
		panel.add(new JLabel("Server Port:"), gbc);

		// 创建端口输入框，预填充当前端口号，设置宽度为10个字符
		JTextField portField = new JTextField(String.valueOf(currentPort), 10);
		gbc.gridx = 1;
		panel.add(portField, gbc);

		// 添加端口范围提示信息
		gbc.gridx = 0;
		gbc.gridy = 1;
		gbc.gridwidth = 2;  // 横跨两列
		gbc.fill = GridBagConstraints.HORIZONTAL;
		panel.add(new JLabel("<html><i>Valid range: 1024-65535</i></html>"), gbc);

		// 添加当前端口信息
		gbc.gridy = 2;
		panel.add(new JLabel("<html><i>Current port: " + currentPort + "</i></html>"), gbc);

		// 显示确认对话框，包含OK和Cancel按钮
		int result = JOptionPane.showConfirmDialog(
				mainWindow,
				panel,
				"Configure AI MCP Server Port",
				JOptionPane.OK_CANCEL_OPTION,
				JOptionPane.PLAIN_MESSAGE);

		// 如果用户点击了OK按钮
		if (result == JOptionPane.OK_OPTION) {
			try {
				// 解析用户输入的端口号
				int newPort = Integer.parseInt(portField.getText().trim());

				// 验证端口范围是否有效
				if (newPort < 1024 || newPort > 65535) {
					// 显示端口范围错误提示
					JOptionPane.showMessageDialog(
							mainWindow,
							"Port must be between 1024 and 65535",
							"Invalid Port",
							JOptionPane.ERROR_MESSAGE);
					return;
				}

				// 只有当新端口与当前端口不同时才进行更新
				if (newPort != currentPort) {
					// 更新当前端口号
					currentPort = newPort;

					// 保存新端口到用户偏好设置
					prefs.putInt(PREF_KEY_PORT, currentPort);

					// 显示端口更新成功提示
					JOptionPane.showMessageDialog(
							mainWindow,
							"Port updated to " + currentPort + ". Server will restart automatically.",
							"Port Updated",
							JOptionPane.INFORMATION_MESSAGE);

					// 调用重启服务器方法应用新配置
					restartServer();
				}

			} catch (NumberFormatException e) {
				// 如果用户输入的不是有效数字，显示错误提示
				JOptionPane.showMessageDialog(
						mainWindow,
						"Please enter a valid port number",
						"Invalid Port",
						JOptionPane.ERROR_MESSAGE);
			}
		}
	}

	/**
	 * 重启HTTP服务器
	 * <p>
	 * 使用新线程执行重启操作，避免阻塞主GUI线程。重启流程包括停止当前服务器、 等待端口释放、启动新服务器，并向用户显示操作结果。
	 * <p>
	 * 重启流程： 1. 记录重启开始日志 2. 停止当前运行的HTTP服务器 3. 等待1秒确保端口完全释放 4. 启动新的HTTP服务器实例 5.
	 * 在EDT线程中显示重启结果对话框
	 * <p>
	 * 线程安全考虑： - 使用独立线程执行重启，避免阻塞GUI - 使用SwingUtilities.invokeLater确保GUI操作在EDT中执行
	 * - 设置合适的线程名称便于调试
	 * <p>
	 * 错误处理： - 重启失败时显示错误对话框 - 记录详细的错误日志 - 确保用户得到明确的错误反馈
	 */
	private void restartServer() {
		// 创建新线程执行重启操作，避免阻塞GUI线程
		new Thread(() -> {
			try {
				// 记录重启开始日志，包含当前端口号信息
				logger.info("JADX-AI-MCP Plugin: Restarting server on port " + currentPort);

				// 停止当前运行的HTTP服务器实例
				if (app != null) {
					app.stop();            // 停止Javalin HTTP服务器
					app = null;            // 清除服务器引用
					serverStarted = false;  // 重置服务器启动状态标志
				}

				// 等待1秒，确保端口完全释放，避免端口冲突
				Thread.sleep(1000);

				// 启动新的HTTP服务器实例，使用当前端口号
				start();

				// 在EDT线程中显示重启成功对话框
				SwingUtilities.invokeLater(() -> {
					JOptionPane.showMessageDialog(
							mainWindow,
							"AI MCP Server restarted successfully on port " + currentPort,
							"Server Restarted",
							JOptionPane.INFORMATION_MESSAGE);
				});

			} catch (Exception e) {
				// 记录重启失败的错误日志
				logger.error("JADX-AI-MCP Plugin: Error restarting server: " + e.getMessage(), e);

				// 在EDT线程中显示重启失败错误对话框
				SwingUtilities.invokeLater(() -> {
					JOptionPane.showMessageDialog(
							mainWindow,
							"Failed to restart server: " + e.getMessage(),
							"Server Restart Error",
							JOptionPane.ERROR_MESSAGE);
				});
			}
		}, "JADX-AI-MCP-Restart").start();  // 设置线程名称便于调试
	}

	/**
	 * 显示服务器状态对话框
	 * <p>
	 * 创建并显示一个对话框，向用户展示MCP服务器的当前状态信息。 状态信息包括运行状态、端口号和访问URL等关键信息。
	 * <p>
	 * 显示的信息： 1. Status: 服务器运行状态（Running/Stopped） 2. Port: 当前监听的端口号 3. URL:
	 * 服务器访问地址（仅在运行时显示）
	 * <p>
	 * 状态判断逻辑： - Running: serverStarted=true && app!=null - Stopped: 其他所有情况
	 * <p>
	 * UI设计考虑： - 使用GridBagLayout实现整齐的表格式布局 - 左侧显示标签，右侧显示值 - 合理的边距和间距设置 -
	 * 状态信息清晰易读
	 */
	private void showServerStatus() {
		// 判断服务器当前运行状态：已启动且app实例存在则为Running，否则为Stopped
		String status = serverStarted && app != null ? "Running" : "Stopped";

		// 根据运行状态设置访问URL：运行时显示实际URL，停止时显示N/A
		String url = serverStarted ? "http://127.0.0.1:" + currentPort + "/" : "N/A";

		// 创建主面板，使用GridBagLayout实现表格式布局
		JPanel panel = new JPanel(new GridBagLayout());
		GridBagConstraints gbc = new GridBagConstraints();

		// 设置默认的布局参数：左对齐，5像素边距
		gbc.anchor = GridBagConstraints.WEST;
		gbc.insets = new Insets(5, 5, 5, 5);

		// 添加状态信息行
		gbc.gridx = 0;  // 第一列
		gbc.gridy = 0;  // 第一行
		panel.add(new JLabel("Status:"), gbc);  // 状态标签
		gbc.gridx = 1;  // 第二列
		panel.add(new JLabel(status), gbc);      // 状态值

		// 添加端口信息行
		gbc.gridx = 0;  // 第一列
		gbc.gridy = 1;  // 第二行
		panel.add(new JLabel("Port:"), gbc);     // 端口标签
		gbc.gridx = 1;  // 第二列
		panel.add(new JLabel(String.valueOf(currentPort)), gbc);  // 端口值

		// 添加URL信息行
		gbc.gridx = 0;  // 第一列
		gbc.gridy = 2;  // 第三行
		panel.add(new JLabel("URL:"), gbc);      // URL标签
		gbc.gridx = 1;  // 第二列
		panel.add(new JLabel(url), gbc);          // URL值

		// 显示信息对话框
		JOptionPane.showMessageDialog(
				mainWindow, // 父窗口为JADX主窗口
				panel, // 自定义面板内容
				"AI MCP Server Status", // 对话框标题
				JOptionPane.INFORMATION_MESSAGE); // 对话框类型（信息提示）
	}

	// ======================== 分页工具类 ========================

	/**
	 * 分页处理工具类
	 * <p>
	 * 提供统一的分页处理功能，适用于各种MCP工具和API端点。 支持灵活的配置选项和错误处理机制。
	 * <p>
	 * 主要功能： 1. 解析和验证分页参数（offset, limit/count） 2. 计算分页边界和导航信息 3. 生成标准化的分页响应格式 4.
	 * 支持自定义数据转换器 5. 提供向后兼容性支持
	 * <p>
	 * 设计原则： - 参数验证严格但友好 - 性能优化（避免大内存使用） - 错误处理清晰明确 - 响应格式标准化 - 支持多种分页策略
	 */
	public static class PaginationUtils {

		// ======================== 配置常量 ========================
		/**
		 * 默认页面大小：每页100条记录 在用户未指定分页大小时使用此默认值
		 */
		public static final int DEFAULT_PAGE_SIZE = 100;

		/**
		 * 最大页面大小限制：每页10000条记录 防止用户请求过大的页面导致内存问题
		 */
		public static final int MAX_PAGE_SIZE = 10000;

		/**
		 * 最大偏移量限制：1000000条记录 防止用户请求过大的偏移量导致性能问题
		 */
		public static final int MAX_OFFSET = 1000000;

		// ======================== 通用分页处理方法 ========================

		/**
		 * 处理分页请求的通用方法（使用默认转换器）
		 * <p>
		 * 此方法适用于简单对象列表的分页处理，使用对象的toString()方法作为显示值。
		 *
		 * @param <T>      数据项的类型参数
		 * @param ctx      Javalin HTTP请求上下文，包含查询参数
		 * @param allItems 要分页处理的完整数据列表
		 * @param dataType 数据类型标识符，用于响应中的type字段
		 * @param itemsKey 在响应中存放数据项的键名
		 * @return 包含分页数据和元数据的响应Map
		 * @throws PaginationException 分页参数错误时抛出
		 *                             <p>
		 *                             使用示例： Map<String, Object> result = PaginationUtils.handlePagination(
		 *                             ctx, classList, "class-list", "classes");
		 */
		public static <T> Map<String, Object> handlePagination(
				Context ctx,
				List<T> allItems,
				String dataType,
				String itemsKey) throws PaginationException {

			// 调用带自定义转换器的重载方法，使用对象的toString()作为转换器
			return handlePagination(ctx, allItems, dataType, itemsKey, item -> item.toString());
		}

		/**
		 * 处理分页请求的通用方法（支持自定义转换器）
		 * <p>
		 * 这是核心分页处理方法，支持自定义数据转换器，可以将原始数据对象 转换为适合API响应的格式。
		 *
		 * @param <T>             原始数据项的类型参数
		 * @param ctx             Javalin HTTP请求上下文
		 * @param allItems        要分页处理的完整数据列表
		 * @param dataType        数据类型标识符，用于响应中的type字段
		 * @param itemsKey        在响应中存放数据项的键名
		 * @param itemTransformer 自定义数据转换器函数，将T类型转换为Object类型
		 * @return 包含分页数据和元数据的响应Map
		 * @throws PaginationException 分页参数错误时抛出
		 *                             <p>
		 *                             处理流程： 1. 验证和准备数据列表 2. 解析分页参数（offset, limit/count） 3. 计算分页边界 4.
		 *                             转换和提取分页数据 5. 构建标准化响应格式
		 */
		public static <T> Map<String, Object> handlePagination(
				Context ctx,
				List<T> allItems,
				String dataType,
				String itemsKey,
				Function<T, Object> itemTransformer) throws PaginationException {

			// 验证输入数据，防止null指针异常
			if (allItems == null) {
				allItems = new ArrayList<>();
			}

			// 获取总数据量，用于后续计算
			int totalItems = allItems.size();

			// 解析分页参数并进行验证
			PaginationParams params = parsePaginationParams(ctx, totalItems);

			// 计算分页边界（起始索引、结束索引等）
			PaginationBounds bounds = calculatePaginationBounds(params, totalItems);

			// 转换和提取当前页的数据
			List<Object> transformedItems = allItems.subList(bounds.startIndex, bounds.endIndex)
					.stream()
					.map(itemTransformer) // 应用自定义转换器
					.collect(Collectors.toList());

			// 构建包含数据和元数据的完整响应
			return buildPaginationResponse(transformedItems, params, bounds, totalItems, dataType, itemsKey);
		}

		// Parse and validate pagination parameters
		private static PaginationParams parsePaginationParams(Context ctx, int totalItems) throws PaginationException {
			String offsetParam = ctx.queryParam("offset");
			String limitParam = ctx.queryParam("limit");
			String countParam = ctx.queryParam("count"); // Legacy support

			// Use 'limit' if provided, otherwise fall back to 'count'
			String pageSizeParam = limitParam != null ? limitParam : countParam;

			int offset = 0;
			int requestedLimit = 0;
			boolean hasCustomLimit = pageSizeParam != null && !pageSizeParam.isEmpty();

			// Parse offset
			if (offsetParam != null && !offsetParam.isEmpty()) {
				try {
					offset = Integer.parseInt(offsetParam.trim());
					if (offset < 0) {
						throw new PaginationException("Offset must be non-negative, got: " + offset);
					}
					if (offset > MAX_OFFSET) {
						throw new PaginationException("Offset too large, maximum: " + MAX_OFFSET);
					}
				} catch (NumberFormatException e) {
					throw new PaginationException("Invalid offset format: '" + offsetParam + "'");
				}
			}

			// Parse limit/count
			if (hasCustomLimit) {
				try {
					requestedLimit = Integer.parseInt(pageSizeParam.trim());
					if (requestedLimit < 0) {
						throw new PaginationException("Limit must be non-negative, got: " + requestedLimit);
					}
					if (requestedLimit > MAX_PAGE_SIZE) {
						throw new PaginationException("Limit too large, maximum: " + MAX_PAGE_SIZE);
					}
				} catch (NumberFormatException e) {
					throw new PaginationException("Invalid limit format: '" + pageSizeParam + "'");
				}
			}

			// Determine effective limit
			int effectiveLimit;
			if (hasCustomLimit) {
				effectiveLimit = requestedLimit == 0 ? Math.max(0, totalItems - offset) : requestedLimit;
			} else {
				effectiveLimit = Math.min(DEFAULT_PAGE_SIZE, Math.max(0, totalItems - offset));
			}

			effectiveLimit = Math.max(0, Math.min(effectiveLimit, totalItems - offset));

			return new PaginationParams(offset, effectiveLimit, requestedLimit, hasCustomLimit);
		}

		// Calculate pagination boundaries
		private static PaginationBounds calculatePaginationBounds(PaginationParams params, int totalItems) {
			if (params.offset >= totalItems) {
				return new PaginationBounds(0, 0, false, totalItems);
			}

			int startIndex = params.offset;
			int endIndex = Math.min(startIndex + params.limit, totalItems);
			boolean hasMore = endIndex < totalItems;
			int nextOffset = hasMore ? endIndex : -1;

			return new PaginationBounds(startIndex, endIndex, hasMore, nextOffset);
		}

		// Build comprehensive pagination response
		private static Map<String, Object> buildPaginationResponse(
				List<Object> data,
				PaginationParams params,
				PaginationBounds bounds,
				int totalItems,
				String dataType,
				String itemsKey) {

			Map<String, Object> result = new HashMap<>();

			// Core data
			result.put("type", dataType);
			result.put(itemsKey, data);

			// Pagination metadata
			Map<String, Object> pagination = new HashMap<>();
			pagination.put("total", totalItems);
			pagination.put("offset", params.offset);
			pagination.put("limit", params.limit);
			pagination.put("count", data.size());
			pagination.put("has_more", bounds.hasMore);

			// Navigation helpers
			if (bounds.hasMore) {
				pagination.put("next_offset", bounds.nextOffset);
			}

			if (params.offset > 0) {
				int prevOffset = Math.max(0, params.offset - params.limit);
				pagination.put("prev_offset", prevOffset);
			}

			// Page calculations
			if (params.limit > 0) {
				int currentPage = (params.offset / params.limit) + 1;
				int totalPages = (int) Math.ceil((double) totalItems / params.limit);
				pagination.put("current_page", currentPage);
				pagination.put("total_pages", totalPages);
				pagination.put("page_size", params.limit);
			}

			// Legacy compatibility
			result.put("requested_count", params.requestedLimit);
			result.put("pagination", pagination);

			return result;
		}

		// Helper classes remain the same as before
		private static class PaginationParams {

			final int offset;
			final int limit;
			final int requestedLimit;
			final boolean hasCustomLimit;

			PaginationParams(int offset, int limit, int requestedLimit, boolean hasCustomLimit) {
				this.offset = offset;
				this.limit = limit;
				this.requestedLimit = requestedLimit;
				this.hasCustomLimit = hasCustomLimit;
			}
		}

		private static class PaginationBounds {

			final int startIndex;
			final int endIndex;
			final boolean hasMore;
			final int nextOffset;

			PaginationBounds(int startIndex, int endIndex, boolean hasMore, int nextOffset) {
				this.startIndex = startIndex;
				this.endIndex = endIndex;
				this.hasMore = hasMore;
				this.nextOffset = nextOffset;
			}
		}

		public static class PaginationException extends Exception {

			public PaginationException(String message) {
				super(message);
			}
		}
	}

	// ======================== HTTP API请求处理方法 ========================

	/**
	 * 处理健康检查请求
	 * <p>
	 * 此API端点用于检查插件和MCP服务器的运行状态，确保系统正常工作。 通常用于监控系统、服务发现或健康检查脚本。
	 * <p>
	 * 响应格式： { "status": "Running|Stopped", // 服务器运行状态 "url":
	 * "http://127.0.0.1:8650/" // 访问URL（运行时） }
	 *
	 * @param ctx Javalin HTTP请求上下文，包含请求参数和响应对象
	 */
	public void handleHealth(Context ctx) {
		try {
			// 判断服务器当前状态：已启动且app实例存在则为Running，否则为Stopped
			String status = serverStarted && app != null ? "Running" : "Stopped";

			// 根据运行状态设置访问URL：运行时显示实际URL，停止时显示N/A
			String url = serverStarted ? "http://127.0.0.1:" + currentPort + "/" : "N/A";

			// 构建响应数据结构
			Map<String, Object> result = new HashMap<>();
			result.put("status", status);  // 服务器状态
			result.put("url", url);        // 访问地址

			// 记录健康检查请求日志
			logger.info("JADX AI MCP Plugin: GOT HEALTH PING");

			// 返回JSON格式的响应
			ctx.json(result);

		} catch (Exception e) {
			// 记录错误日志
			logger.error("JADX AI MCP Error: " + e.getMessage(), e);

			// 返回500错误响应
			ctx.status(500)
					.json(Map.of("error",
							"Internal Error while trying to handle health ping request: " + e.getMessage()));
		}
	}

	/**
	 * 处理获取当前选中类的请求
	 * <p>
	 * 获取用户在JADX GUI中当前选中的标签页的类信息，包括类名和源代码内容。 此API用于获取当前正在分析的类的详细信息。
	 * <p>
	 * 响应格式： { "name": "MainActivity", // 类名（不包含.java扩展名） "type": "code/java",
	 * // 数据类型标识 "content": "class MainActivity { ... }" // 类的完整源代码 }
	 * <p>
	 * 处理流程： 1. 获取当前选中标签页的标题 2. 提取标签页中的文本内容 3. 清理和格式化数据 4. 构建标准化响应
	 *
	 * @param ctx Javalin HTTP请求上下文
	 */
	public void handleCurrentClass(Context ctx) {
		try {
			// 获取当前选中标签页的标题（包含.java扩展名）
			String className = getSelectedTabTitle();

			// 提取当前标签页中的完整文本内容
			String code = extractTextFromCurrentTab();

			// 构建响应数据结构
			Map<String, Object> result = new HashMap<>();
			result.put("name", className != null ? className.replace(".java", "") : "unknown");  // 清理类名
			result.put("type", "code/java");  // 设置数据类型
			result.put("content", code != null ? code : "");  // 设置源代码内容

			// 返回JSON格式响应
			ctx.json(result);

		} catch (Exception e) {
			// 记录错误日志
			logger.error("JADX AI MCP Error: " + e.getMessage(), e);

			// 返回500错误响应
			ctx.status(500)
					.json(Map.of("error", "Internal Error while trying to fetch current class: " + e.getMessage()));
		}
	}

	/**
	 * 处理获取所有类的请求
	 * <p>
	 * 返回APK中所有反编译的类的完整列表，支持分页处理。 此API用于获取项目中所有可用类的概览。
	 * <p>
	 * 请求参数： - offset: 分页偏移量（可选，默认0） - limit: 每页记录数（可选，默认100） - count:
	 * 页面大小（兼容旧版本，可选）
	 * <p>
	 * 响应格式： { "type": "class-list", "classes": ["com.example.MainActivity",
	 * ...], "pagination": { "total": 1000, "offset": 0, "limit": 100, "count":
	 * 100, "has_more": true, "current_page": 1, "total_pages": 10 } }
	 *
	 * @param ctx Javalin HTTP请求上下文
	 */
	private void handleAllClasses(Context ctx) {
		try {
			// 获取JADX包装器，用于访问反编译数据
			JadxWrapper wrapper = mainWindow.getWrapper();
			List<JavaClass> classes = wrapper.getIncludedClassesWithInners();

			List<String> clsList = classes.stream().map(JavaClass::toString).toList();
			Map<String, Object> result = new HashMap<>();
			result.put("total", classes.size());
			result.put("classes", clsList);

			// 使用分页工具类处理分页请求
//			Map<String, Object> result = PaginationUtils.handlePagination(
//					ctx, // HTTP请求上下文
//					classes, // 完整的类列表
//					"class-list", // 数据类型标识
//					"classes", // 响应中存放类名的键名
//					cls -> cls.getFullName());  // 转换器：提取类的完整名称

			// 返回JSON格式响应
			ctx.json(result);

//		} catch (PaginationUtils.PaginationException e) {
//			// 处理分页参数错误
//			logger.error("JADX AI MCP Pagination Error: " + e.getMessage());
//			ctx.status(400).json(Map.of("error", "Pagination error: " + e.getMessage()));

		} catch (Exception e) {
			// 处理其他异常
			logger.error("JADX AI MCP Error: " + e.getMessage(), e);
			ctx.status(500).json(Map.of("error", "Failed to load class list: " + e.getMessage()));
		}
	}

	/**
	 * 处理获取选中文本的请求
	 * <p>
	 * 获取用户在JADX GUI中当前选中的文本内容。 此API用于获取用户当前关注的特定代码片段。
	 * <p>
	 * 响应格式： { "selectedText": "String text = \"Hello\";" // 用户选中的文本 }
	 *
	 * @param ctx Javalin HTTP请求上下文
	 */
	private void handleSelectedText(Context ctx) {
		try {
			// 从当前选中标签页中查找JTextArea组件
			JTextArea textArea = findTextArea(mainWindow.getTabbedPane().getSelectedComponent());

			// 获取选中的文本内容，如果没有选中文本则返回空字符串
			String selectedText = textArea != null ? textArea.getSelectedText() : "";

			// 构建响应数据
			Map<String, String> result = new HashMap<>();
			result.put("selectedText", selectedText);

			// 返回JSON格式响应
			ctx.json(result);

		} catch (Exception e) {
			// 记录错误日志
			logger.error("JADX AI MCP Error: " + e.getMessage(), e);

			// 返回500错误响应
			ctx.status(500)
					.json(Map.of("error", "Internal error while trying to fetch selected text: " + e.getMessage()));
		}
	}

	/**
	 * 处理按名称获取方法的请求
	 * <p>
	 * 此API端点根据方法名和可选的类名参数获取指定方法的代码。 支持两种查询模式：全局搜索（跨所有类）和特定类内的方法查找。
	 * <p>
	 * 请求参数： - method (必需): 方法名称，如"onCreate" - class (可选):
	 * 类名，如"com.example.MainActivity"
	 * <p>
	 * 响应格式： { "class": "com.example.MainActivity", // 方法所属类 "method":
	 * "onCreate", // 方法名 "decl": "CodeNodeRef", // 方法代码节点引用 "code": "protected
	 * void onCreate(...) { // 方法的完整源代码 super.onCreate(savedInstanceState);
	 * setContentView(R.layout.activity_main); }" }
	 * <p>
	 * 查询逻辑： 1. 验证必需的method参数 2. 转换类名中的$为.以支持内部类 3. 如果未指定类名，在所有类中搜索同名方法（返回第一个匹配）
	 * 4. 如果指定了类名，只在该类中搜索方法 5. 返回找到的方法的完整信息
	 *
	 * @param ctx Javalin HTTP请求上下文，包含查询参数
	 */
	private void handleMethodByName(Context ctx) {
		try {

			JavaMethod method = findMethod(ctx);
			// 构建包含方法完整信息的响应
			Map<String, Object> result = new HashMap<>();
			result.put("className", method.getDeclaringClass().getName());
			jadx.core.dex.nodes.MethodNode methodNode = method.getMethodNode();
			jadx.core.dex.info.MethodInfo methodInfo = methodNode.getMethodInfo();
			result.put("name(originalName)", methodInfo.getName());
			result.put("Alias", method.getName());
			result.put("fullName", method.getFullName());             // 方法名称
			result.put("signature", method.getMethodNode().getMethodInfo().getShortId());
			result.put("isPublic", method.getAccessFlags().isPublic());
			result.put("isStatic", method.getAccessFlags().isStatic());
			result.put("arguments", method.getArguments().stream()
					.map(arg -> arg.toString()) // 转换为字符串
					.collect(java.util.stream.Collectors.toList()));            //List<ArgType> 类型
			result.put("returnType", method.getReturnType().toString());             //ArgType 类型
			result.put("defPos", method.getDefPos());
			result.put("nodeRef", method.getCodeNodeRef().toString());
			result.put("isConstructor", method.isConstructor());
			result.put("isClassInit", method.isClassInit());
			result.put("regsCount", methodNode.getRegsCount());
			result.put("insnsCount", methodNode.getInsnsCount());
			result.put("methodCodeOffset", methodNode.getMethodCodeOffset());
			result.put("useInCount", methodNode.getUseIn().size());

			// result.put("nodeRef", String.valueOf(method.getCodeNodeRef())); // 方法代码节点引用
			result.put("code", method.getCodeStr());                       // 方法的完整源代码

			// 返回找到的方法
			ctx.json(result);

		} catch (NoSuchElementException e) {
			ctx.status(404).json(Map.of("error", e.getMessage()));
		} catch (IllegalArgumentException e) {
			ctx.status(400).json(Map.of("error", e.getMessage()));
		} catch (Exception e) {
			ctx.status(500).json(Map.of("error", "Internal server error: " + e.getMessage()));
		}
	}

	private void handleMethodInfo(Context ctx) {
		try {

			JavaMethod method = findMethod(ctx);
			// 构建包含方法完整信息的响应
			Map<String, Object> result = new HashMap<>();
			result.put("className", method.getDeclaringClass().getName());
			jadx.core.dex.nodes.MethodNode methodNode = method.getMethodNode();
			jadx.core.dex.info.MethodInfo methodInfo = methodNode.getMethodInfo();
			result.put("name(originalName)", methodInfo.getName());
			result.put("Alias", method.getName());
			result.put("fullName", method.getFullName());             // 方法名称
			result.put("signature", method.getMethodNode().getMethodInfo().getShortId());
			result.put("isPublic", method.getAccessFlags().isPublic());
			result.put("isStatic", method.getAccessFlags().isStatic());
			result.put("arguments", method.getArguments().stream()
					.map(arg -> arg.toString()) // 转换为字符串
					.collect(java.util.stream.Collectors.toList()));            //List<ArgType> 类型
			result.put("returnType", method.getReturnType().toString());             //ArgType 类型
			result.put("defPos", method.getDefPos());
			result.put("nodeRef", method.getCodeNodeRef().toString());
			result.put("isConstructor", method.isConstructor());
			result.put("isClassInit", method.isClassInit());
			result.put("regsCount", methodNode.getRegsCount());
			result.put("insnsCount", methodNode.getInsnsCount());
			result.put("methodCodeOffset", methodNode.getMethodCodeOffset());
			result.put("useInCount", methodNode.getUseIn().size());

			// result.put("nodeRef", String.valueOf(method.getCodeNodeRef())); // 方法代码节点引用
			result.put("codeLength", method.getCodeStr().length());                       // 方法的完整源代码

			// 返回找到的方法
			ctx.json(result);

		} catch (NoSuchElementException e) {
			ctx.status(404).json(Map.of("error", e.getMessage()));
		} catch (IllegalArgumentException e) {
			ctx.status(400).json(Map.of("error", e.getMessage()));
		} catch (Exception e) {
			ctx.status(500).json(Map.of("error", "Internal server error: " + e.getMessage()));
		}
	}

	/**
	 * 处理获取类源代码的请求
	 * <p>
	 * 此API端点根据类名参数获取指定类的完整源代码。 返回的是经过JADX反编译处理后的完整Java源代码文件内容。
	 * <p>
	 * 请求参数： - class (必需): 完整类名，支持内部类格式 例如："com.example.MainActivity" 或
	 * "com.example.MainActivity$InnerClass"
	 * <p>
	 * 响应： 成功时：返回类的完整源代码（纯文本格式） 失败时：返回JSON格式的错误信息
	 * <p>
	 * 处理逻辑： 1. 验证必需的class参数 2. 转换类名格式，支持内部类（$替换为.） 3. 在所有反编译的类中查找匹配的类 4.
	 * 返回找到的类的完整源代码
	 * <p>
	 * 使用示例： GET /class-source?class=com.example.MainActivity GET
	 * /class-source?class=com.example.MainActivity$InnerClass
	 *
	 * @param ctx Javalin HTTP请求上下文，包含查询参数
	 */
	private void handleClassSource(Context ctx) {
		try {
			JavaClass cls = findClass(ctx);
			ctx.result(cls.getCode());

		} catch (NoSuchElementException e) {
			ctx.status(404).json(Map.of("error", e.getMessage()));
		} catch (IllegalArgumentException e) {
			ctx.status(400).json(Map.of("error", e.getMessage()));
		} catch (Exception e) {
			ctx.status(500).json(Map.of("error", "Internal server error: " + e.getMessage()));
		}

	}

	/**
	 * 处理方法搜索请求
	 */
	private void handleSearchMethod(Context ctx) {
		try {
			String methodName = getParameter(ctx, "method", "method_name", "methodName");
			String originalName = getParameter(ctx, "original_name", "originalName", "method_original_name");
			String methodSignature = getParameter(ctx, "method_signature", "signature");
			String className = getParameter(ctx, "name", "class_name", "class");
			String rawName = getParameter(ctx, "rawName", "raw_name", "class_raw_name", "classRawName");

			boolean hasClassName = className != null && !className.isEmpty();
			boolean hasClassRawName = rawName != null && rawName.isEmpty();
			boolean hasMethodName = methodName != null && !methodName.isEmpty();
			boolean hasOriginalName = originalName != null && !originalName.isEmpty();
//			boolean hasMethodSignature = methodSignature != null && !methodSignature.isEmpty();

			if (!hasMethodName && !hasOriginalName) {
				throw new IllegalArgumentException("Missing 'method_name' or 'original_name' parameter");
			}

			JadxWrapper wrapper = mainWindow.getWrapper();
			if (wrapper == null) {
				logger.error("JADX AI MCP Error: JadxWrapper not initialized");
				ctx.status(500).json(Map.of("error", "JadxWrapper not initialized"));
				return;
			}
			List<Map<String, Object>> methodMatches = new ArrayList<>();
			if (hasClassName || hasClassRawName) {
				JavaMethod method = findMethod(ctx);
				Map<String, Object> methodInfo = new HashMap<>();
				methodInfo.put("class", method.getDeclaringClass().getFullName());
				methodInfo.put("method", method.getName());
				methodInfo.put("signature", method.getMethodNode().getMethodInfo().getShortId());
				methodInfo.put("node", method.toString()); // 直接使用JADX的toString()
				methodInfo.put("fullName", method.getFullName());
				methodMatches.add(methodInfo);
			} else {
				for (JavaClass cls : wrapper.getIncludedClassesWithInners()) {
					try {
						JavaMethod method = findMethod(cls, methodName, originalName, methodSignature);
						Map<String, Object> methodInfo = new HashMap<>();
						methodInfo.put("class", cls.getFullName());
						methodInfo.put("method", method.getName());
						methodInfo.put("signature", method.getMethodNode().getMethodInfo().getShortId());
						methodInfo.put("node", method.toString()); // 直接使用JADX的toString()
						methodInfo.put("fullName", method.getFullName());
						methodMatches.add(methodInfo);
					} catch (Exception e) {
					}

				}
			}
			// 构建响应
			Map<String, Object> result = new HashMap<>();
			result.put("class_filter", className != null ? className : "all");
			result.put("match_count", methodMatches.size());
			result.put("methods", methodMatches);

			ctx.status(200).json(result);
		} catch (Exception e) {
			logger.error("JADX AI MCP Error: " + e.getMessage(), e);
			ctx.status(500).json(Map.of("error", "Internal error during method search: " + e.getMessage()));
		}

	}

	/**
	 * 获取代码预览
	 *
	 * @param code  完整代码
	 * @param lines 行数
	 * @return 指定行数的代码预览
	 */
	private String getCodePreview(String code, int lines) {
		if (code == null || code.isEmpty()) {
			return "";
		}

		String[] codeLines = code.split("\n");
		StringBuilder preview = new StringBuilder();
		int maxLines = Math.min(lines, codeLines.length);

		for (int i = 0; i < maxLines; i++) {
			if (preview.length() > 0) {
				preview.append("\n");
			}
			preview.append(codeLines[i].trim()); // 去除多余空格
		}

		return preview.toString();
	}

	/**
	 * 处理获取指定类所有方法的请求
	 * <p>
	 * 此API端点返回指定类中所有方法的详细信息列表，包括访问修饰符、返回类型、方法名等。 提供了类中方法的完整概览，用于代码分析和结构理解。
	 * <p>
	 * 请求参数： - class_name (必需): 完整类名，支持内部类格式 例如："com.example.MainActivity" 或
	 * "com.example.MainActivity$InnerClass"
	 * <p>
	 * 响应： 成功时：返回方法信息列表，每行一个方法，格式为： "访问修饰符 返回类型 方法名 方法节点 完整方法路径"
	 * 失败时：返回JSON格式的错误信息
	 * <p>
	 * 方法信息格式说明： - 访问修饰符：public、private、protected等 - 返回类型：方法返回值的数据类型 - 方法名：方法的名称
	 * - 方法节点：JADX内部方法节点的字符串表示 - 完整方法路径：包含类名的完整方法标识符
	 *
	 * @param ctx Javalin HTTP请求上下文，包含查询参数
	 */
	private void handleMethodsOfClass(Context ctx) {

		try {
			JavaClass cls = findClass(ctx);

			// 安全地处理方法信息
			List<Map<String, Object>> methodInfos = new ArrayList<>();
			for (JavaMethod method : cls.getMethods()) {
				Map<String, Object> methodInfo = new HashMap<>();

				// 核心：方法签名（包含所有类型信息）
				jadx.core.dex.info.MethodInfo methodInfoDetail = method.getMethodNode().getMethodInfo();
				methodInfo.put("signature", methodInfoDetail.getShortId());
				methodInfo.put("originalName", methodInfoDetail.getName());
				// 基本信息
				methodInfo.put("name", method.getName());
				methodInfo.put("isPublic", method.getAccessFlags().isPublic());
				methodInfo.put("isStatic", method.getAccessFlags().isStatic());
				methodInfo.put("isConstructor", method.isConstructor());

				methodInfos.add(methodInfo);
			}

			// Map<String, Object> classInfo = new HashMap<>();
			// classInfo.put("className", cls.getFullName());
			// classInfo.put("classRawName", cls.getClassNode().getRawName());
			// classInfo.put("methodsCount", cls.getMethods().size());
			// classInfo.put("methods", cls.getMethods());
			ctx.json(methodInfos);

		} catch (NoSuchElementException e) {
			ctx.status(404).json(Map.of("error", e.getMessage()));
		} catch (IllegalArgumentException e) {
			ctx.status(400).json(Map.of("error", e.getMessage()));
		} catch (Exception e) {
			ctx.status(500).json(Map.of("error", "Internal server error: " + e.getMessage()));
		}

	}


	private void handleGetParameters(Context ctx) {
		try {
			JavaMethod method = findMethod(ctx);

			MethodNode methodNode = method.getMethodNode();
			List<VarNode> argNodes = methodNode.collectArgNodes();
			List<ArgType> argTypes = method.getArguments();

			List<Map<String, Object>> parameters = new ArrayList<>();
			for (int i = 0; i < argNodes.size(); i++) {
				VarNode varNode = argNodes.get(i);
				ArgType argType = i < argTypes.size() ? argTypes.get(i) : null;

				Map<String, Object> paramInfo = new HashMap<>();
				paramInfo.put("index", i);
				paramInfo.put("name", varNode.getName());
				paramInfo.put("type", argType != null ? argType.toString() : "unknown");
				paramInfo.put("register", varNode.getReg());
				paramInfo.put("ssa_version", varNode.getSsa());

				parameters.add(paramInfo);
			}

			Map<String, Object> result = new HashMap<>();
			result.put("method", method.getName());
			result.put("parameters", parameters);
			result.put("total_count", parameters.size());

			ctx.json(result);

		} catch (NoSuchElementException e) {
			ctx.status(404).json(Map.of("error", e.getMessage()));
		} catch (IllegalArgumentException e) {
			ctx.status(400).json(Map.of("error", e.getMessage()));
		} catch (Exception e) {
			ctx.status(500).json(Map.of("error", "Internal server error: " + e.getMessage()));
		}
	}

	/**
	 * 处理获取指定类所有字段的请求
	 * <p>
	 * 此API端点返回指定类中所有字段（成员变量）的详细信息列表。 包括访问修饰符、字段类型和字段名等完整信息。
	 * <p>
	 * 请求参数： - class_name (必需): 完整类名，支持内部类格式 例如："com.example.MainActivity" 或
	 * "com.example.MainActivity$InnerClass"
	 * <p>
	 * 响应： 成功时：返回字段信息列表，每行一个字段，格式为： "访问修饰符 字段类型 字段名" 失败时：返回JSON格式的错误信息
	 * <p>
	 * 字段信息格式说明： - 访问修饰符：public、private、protected、static、final等 -
	 * 字段类型：字段的数据类型（如String、int、boolean等） - 字段名：变量的名称
	 *
	 * @param ctx Javalin HTTP请求上下文，包含查询参数
	 */
	private void handleFieldsOfClass(Context ctx) {

		try {
			JavaClass cls = findClass(ctx);

			// 安全地处理方法信息
			List<Map<String, Object>> FieldInfos = new ArrayList<>();
			for (JavaField field : cls.getFields()) {
				Map<String, Object> fieldInfo = new HashMap<>();

				fieldInfo.put("name", field.getName());
				fieldInfo.put("fullName", field.getFullName());
				fieldInfo.put("rawName", field.getRawName());
				fieldInfo.put("isPublic", field.getAccessFlags().isPublic());
				fieldInfo.put("isStatic", field.getAccessFlags().isStatic());
				fieldInfo.put("type", field.getType().toString());

				FieldInfos.add(fieldInfo);
			}
			ctx.json(FieldInfos);

		} catch (NoSuchElementException e) {
			ctx.status(404).json(Map.of("error", e.getMessage()));
		} catch (IllegalArgumentException e) {
			ctx.status(400).json(Map.of("error", e.getMessage()));
		} catch (Exception e) {
			ctx.status(500).json(Map.of("error", "Internal server error: " + e.getMessage()));
		}

	}

	/**
	 * 处理重命名类的请求
	 * <p>
	 * 此API端点允许通过HTTP请求重命名指定的类。重命名操作会触发JADX的事件系统，
	 * 导致所有相关引用被自动更新，并在GUI界面中实时反映重命名结果。
	 * <p>
	 * 请求参数： - class (必需): 要重命名的完整类名 - newName (必需): 新的类名（仅短名称，不包含包名）
	 * <p>
	 * 响应： 成功时：HTTP 200状态，返回重命名成功的确认信息 失败时：HTTP 4xx/5xx状态，返回JSON格式的错误信息
	 * <p>
	 * 重命名效果： - 类的短名称被更新 - 所有对该类的引用被自动更新 - GUI界面中的标签页标题会实时更新 - 重命名操作会被记录到操作历史中
	 * <p>
	 * 注意事项： - 只能重命名类的短名称，不能更改包名 - 内部类名称使用$分隔符 - 重命名是一个影响范围较大的操作，需谨慎使用
	 *
	 * @param ctx Javalin HTTP请求上下文，包含查询参数
	 */
	private void handleRenameClass(Context ctx) {
		// 从请求参数中获取要重命名的类名和新名称
		String newName = getParameter(ctx, "newName", "new_name");

		// 验证必需的参数
		if (newName == null) {
			logger.error("JADX AI MCP Error: Missing  'newName' parameter.'newName' can be empty to reset,but not null");
			ctx.status(400).json(Map.of("error", "Missing 'newName' parameter.'newName' can be empty to reset,but not null"));
			return;
		}

		try {
			JavaClass cls = findClass(ctx);
			// 获取类的代码节点引用，用于重命名事件
			ICodeNodeRef nodeRef = cls.getCodeNodeRef();
			// 创建重命名事件对象
			NodeRenamedByUser event = new NodeRenamedByUser(nodeRef, cls.getName(), newName);
			// 设置要重命名的节点为类节点
			event.setRenameNode(cls.getClassNode());
			// 设置是否重置名称（如果新名称为空则重置）
			event.setResetName(newName.isEmpty());
			// 发送重命名事件到JADX事件系统，触发实际的重命名操作
			mainWindow.events().send(event);

			// 记录重命名操作日志
			logger.info("rename Class " + cls.getName() + " to " + newName);
			// 构建操作结果响应
			Map<String, Object> result = new HashMap<>();
			result.put("result", "rename Class " + cls.getName() + " to " + newName);
			ctx.json(result);

		} catch (NoSuchElementException e) {
			ctx.status(404).json(Map.of("error", e.getMessage()));
		} catch (IllegalArgumentException e) {
			ctx.status(400).json(Map.of("error", e.getMessage()));
		} catch (Exception e) {
			logger.error("JADX AI MCP Error: " + e.getMessage(), e);
			ctx.status(500).json(Map.of("error", "Internal error rename Class: " + e.getMessage()));
		}

	}

	/**
	 * 处理重命名方法的请求
	 * <p>
	 * 此API端点允许通过HTTP请求重命名指定的方法。重命名操作会触发JADX的事件系统，
	 * 导致所有对该方法的调用被自动更新，并在GUI界面中实时反映重命名结果。
	 * <p>
	 * 请求参数： - method (必需): 要重命名的完整方法路径，格式为"类名.方法名" - newName (必需): 新的方法名
	 * <p>
	 * 响应： 成功时：HTTP 200状态，返回重命名成功的确认信息 失败时：HTTP 4xx/5xx状态，返回JSON格式的错误信息
	 * <p>
	 * 方法路径格式： 支持格式："com.example.MainActivity.onCreate"
	 * 支持格式："com.example.MainActivity$InnerClass.methodName"
	 * 自动处理方法签名的括号部分，如"com.example.MainActivity.onCreate(Bundle)
	 * savedInstanceState"
	 *
	 * @param ctx Javalin HTTP请求上下文，包含查询参数
	 */
	private void handleRenameMethod(Context ctx) {
		String newName = getParameter(ctx, "newName", "new_name");

		if (newName == null) {
			logger.error("JADX AI MCP Error: Missing 'method' or 'original_name' or 'newName' parameter.'newName' can be empty,but not null");
			ctx.status(400).json(Map.of("error", "Missing 'method' or 'original_name' or 'newName' parameter.'newName' can be empty,but not null"));
			return;
		}

		try {

			JavaMethod method = findMethod(ctx);

			// 获取方法的代码节点引用，用于重命名事件
			ICodeNodeRef nodeRef = method.getCodeNodeRef();
			// 创建重命名事件对象
			NodeRenamedByUser event = new NodeRenamedByUser(nodeRef, method.getName(), newName);
			// 设置要重命名的节点为方法节点
			event.setRenameNode(method.getMethodNode());
			// 设置是否重置名称（如果新名称为空则重置）
			event.setResetName(newName.isEmpty());
			// 发送重命名事件到JADX事件系统，触发实际的重命名操作
			mainWindow.events().send(event);

			// 记录重命名操作日志
			logger.info("rename method " + method.getName() + " to " + newName);
			// 构建操作结果响应
			Map<String, Object> result = new HashMap<>();
			result.put("result", "rename method " + method.getName() + " to " + newName);
			ctx.json(result);

		} catch (NoSuchElementException e) {
			ctx.status(404).json(Map.of("error", e.getMessage()));
		} catch (IllegalArgumentException e) {
			ctx.status(400).json(Map.of("error", e.getMessage()));
		} catch (Exception e) {
			ctx.status(500).json(Map.of("error", "Internal server error: " + e.getMessage()));
		}

	}

	/**
	 * 处理重命名字段的请求
	 * <p>
	 * 此API端点允许通过HTTP请求重命名指定类中的字段。重命名操作会触发JADX的事件系统， 导致所有对该字段的引用被自动更新。
	 * <p>
	 * 请求参数： - class (必需): 类名，指定包含要重命名字段的类 - field (必需): 当前字段名 - newFieldName
	 * (必需): 新的字段名
	 *
	 * @param ctx Javalin HTTP请求上下文
	 */
	private void handleRenameField(Context ctx) {

		String newFieldName = getParameter(ctx, "newFieldName", "new_field_name", "new_field", "new_name", "newName");

		if (newFieldName == null) {
			logger.error("JADX AI MCP Error: Missing 'newFieldName',it can be empty to reset,but can't be null.");
			ctx.status(400).json(Map.of("error", "Missing 'newFieldName',it can be empty to reset,but can't be null."));
			return;
		}

		try {
			JavaField field = findField(ctx);
			logger.info("Renaming field: " + field.getName() + " to " + newFieldName);
			// 获取字段的代码节点引用并创建重命名事件
			ICodeNodeRef nodeRef = field.getCodeNodeRef();
			NodeRenamedByUser event = new NodeRenamedByUser(nodeRef, field.getName(), newFieldName);
			event.setRenameNode(field.getFieldNode());
			event.setResetName(newFieldName.isEmpty());
			// 发送重命名事件
			mainWindow.events().send(event);
			Map<String, Object> result = new HashMap<>();
			result.put("result", "rename field " + field.getName() + " to " + newFieldName);
			ctx.json(result);

		} catch (NoSuchElementException e) {
			ctx.status(404).json(Map.of("error", e.getMessage()));
		} catch (IllegalArgumentException e) {
			ctx.status(400).json(Map.of("error", e.getMessage()));
		} catch (Exception e) {
			ctx.status(500).json(Map.of("error", "Internal server error: " + e.getMessage()));
		}

	}

	/**
	 * 处理获取类Smali代码的请求
	 * <p>
	 * 此API端点返回指定类的Smali（Dalvik字节码）表示形式。 Smali是Android应用的字节码汇编语言，用于底层代码分析。
	 * <p>
	 * 请求参数： - class (必需): 完整类名
	 * <p>
	 * 响应：成功时返回类的Smali代码（纯文本格式）
	 *
	 * @param ctx Javalin HTTP请求上下文
	 */
	private void handleSmaliOfClass(Context ctx) {

		try {
			JavaClass cls = findClass(ctx);
			ctx.result(cls.getSmali());
			return;

		} catch (NoSuchElementException e) {
			ctx.status(404).json(Map.of("error", e.getMessage()));
		} catch (IllegalArgumentException e) {
			ctx.status(400).json(Map.of("error", e.getMessage()));
		} catch (Exception e) {
			ctx.status(500).json(Map.of("error", "Internal server error: " + e.getMessage()));
		}

	}

	/**
	 * 处理获取AndroidManifest.xml文件的请求
	 * <p>
	 * 此API端点返回应用的AndroidManifest.xml文件的完整内容。
	 * AndroidManifest.xml是Android应用的核心配置文件，包含应用组件、权限等信息。
	 * <p>
	 * 响应格式： { "name": "AndroidManifest.xml", // 文件名 "type": "manifest/xml", //
	 * 文件类型 "content": "<?xml version=\"1.0\" encoding=\"utf-8\"?>" // 文件内容 }
	 *
	 * @param ctx Javalin HTTP请求上下文
	 */
	private void handleManifest(Context ctx) {
		try {
			// 获取JADX包装器和资源文件列表
			JadxWrapper wrapper = mainWindow.getWrapper();
			List<ResourceFile> resources = wrapper.getResources();
			// 使用JADX的AndroidManifestParser解析manifest文件
			ResourceFile manifest = AndroidManifestParser.getAndroidManifest(resources);

			if (manifest == null) {
				logger.error("JADX AI MCP Error: AndroidManifest.xml not found.");
				ctx.status(404).json(Map.of("error", "AndroidManifest.xml not found."));
				return;
			}

			// 加载manifest文件内容
			ResContainer container = manifest.loadContent();
			String manifestContent = container.getText().getCodeStr();

			// 构建响应数据
			Map<String, Object> result = new HashMap<>();
			result.put("name", manifest.getOriginalName());
			result.put("type", "manifest/xml");
			result.put("content", manifestContent);

			ctx.json(result);
		} catch (Exception e) {
			logger.error("JADX AI MCP Error: " + e.getMessage(), e);
			ctx.status(500).json(Map.of("error", "Internal error retrieving AndroidManifest.xml: " + e.getMessage()));
		}
	}

	/**
	 * 处理获取主应用类名称列表的请求
	 * <p>
	 * 此API端点返回Android应用主包下的所有类名列表。 主包是从AndroidManifest.xml中提取的package属性。
	 * <p>
	 * 响应格式： { "classes": [ {"name": "com.example.MainActivity"}, {"name":
	 * "com.example.HelperClass"} ] }
	 *
	 * @param ctx Javalin HTTP请求上下文
	 */
	private void handleMainApplicationClassesNames(Context ctx) {
		try {
			JadxWrapper wrapper = mainWindow.getWrapper();
			List<ResourceFile> resources = wrapper.getResources();

			// 获取AndroidManifest.xml资源文件
			ResourceFile manifestRes = AndroidManifestParser.getAndroidManifest(resources);
			if (manifestRes == null) {
				logger.error("JADX AI MCP Error: AndroidManifest.xml not found.");
				ctx.status(404).json(Map.of("error", "AndroidManifest.xml not found."));
				return;
			}

			// 加载manifest内容并解析XML
			String manifestXml = manifestRes.loadContent().getText().getCodeStr();
			Document manifestDoc = parseManifestXml(manifestXml, wrapper.getArgs().getSecurity());

			// 从<manifest>标签中提取包名
			Element manifestElement = (Element) manifestDoc.getElementsByTagName("manifest").item(0);
			String packageName = manifestElement.getAttribute("package");

			if (packageName.isEmpty()) {
				logger.error("JADX AI MCP Error: Package name not found in manifest");
				ctx.status(404).json(Map.of("error", "Package name not found in manifest."));
				return;
			}

			// 过滤出该包下的所有类
			List<JavaClass> matchedClasses = wrapper.getDecompiler()
					.getClasses()
					.stream()
					.filter(cls -> cls.getFullName().startsWith(packageName))
					.collect(Collectors.toList());

			List<Map<String, Object>> classesInfo = new ArrayList<>();
			for (JavaClass cls : matchedClasses) {
				Map<String, Object> classInfo = new HashMap<>();
				classInfo.put("name", cls.getFullName());
				classesInfo.add(classInfo);
			}

			Map<String, Object> result = new HashMap<>();
			result.put("classes", classesInfo);
			ctx.json(result);
		} catch (Exception e) {
			logger.error("JADX AI MCP Error: " + e.getMessage(), e);
			ctx.status(500).json(Map.of("error", "Internal error retrieving AndroidManifest.xml: " + e.getMessage()));
		}
	}

	// handle /main-application-classes-codes
	private void handleMainApplicationClassesCode(Context ctx) {
		try {
			JadxWrapper wrapper = mainWindow.getWrapper();
			List<ResourceFile> resources = wrapper.getResources();

			// get the manifest resource file
			ResourceFile manifestRes = AndroidManifestParser.getAndroidManifest(resources);
			if (manifestRes == null) {
				logger.error("JADX AI MCP Error: AndroidManifest.xml not found.");
				ctx.status(404).json(Map.of("error", "AndroidManifest.xml not found."));
				return;
			}

			// load manifest content and parse xml
			String manifestXml = manifestRes.loadContent()
					.getText()
					.getCodeStr();
			Document manifestDoc = parseManifestXml(manifestXml, wrapper.getArgs().getSecurity());

			// Extract the package name from the <manifest> tag
			Element manifestElement = (Element) manifestDoc.getElementsByTagName("manifest").item(0);
			String packageName = manifestElement.getAttribute("package");

			if (packageName.isEmpty()) {
				logger.error("JADX AI MCP Error: Package name not found manifest.");
				ctx.status(404).json(Map.of("error", "Package name not found manifest."));
				return;
			}

			// filter classes under this package
			List<JavaClass> matchedClasses = wrapper.getDecompiler()
					.getClasses()
					.stream()
					.filter(cls -> cls.getFullName().startsWith(packageName))
					.collect(Collectors.toList());

			List<Map<String, Object>> classesInfo = new ArrayList<>();
			for (JavaClass cls : matchedClasses) {
				Map<String, Object> classInfo = new HashMap<>();
				classInfo.put("name", cls.getFullName());
				classInfo.put("type", "code/java");
				classInfo.put("content", cls.getCode());
				classesInfo.add(classInfo);
			}

			Map<String, Object> result = new HashMap<>();
			result.put("allClassesInPackage", classesInfo);
			ctx.json(result);
		} catch (Exception e) {
			logger.error("JADX AI MCP Error: " + e.getMessage(), e);
			ctx.status(500).json(Map.of("error", "Internal error retrieving AndroidManifest.xml: " + e.getMessage()));
		}
	}

	/**
	 * 处理获取主Activity的请求
	 * <p>
	 * 此API端点返回Android应用的主Activity类的完整源代码。 主Activity是从AndroidManifest.xml中通过MAIN
	 * action和LAUNCHER category确定的。
	 * <p>
	 * 响应格式： { "name": "com.example.MainActivity", // 主Activity的完整类名 "type":
	 * "code/java", // 数据类型 "content": "public class MainActivity { ... }" //
	 * Activity的完整源代码 }
	 *
	 * @param ctx Javalin HTTP请求上下文
	 */
	private void handleMainActivity(Context ctx) {
		try {
			JadxWrapper wrapper = mainWindow.getWrapper();
			List<ResourceFile> resources = wrapper.getResources();

			// 创建AndroidManifestParser，专门查找主Activity
			AndroidManifestParser parser = new AndroidManifestParser(
					AndroidManifestParser.getAndroidManifest(resources),
					EnumSet.of(AppAttribute.MAIN_ACTIVITY),
					wrapper.getArgs().getSecurity());

			if (!parser.isManifestFound()) {
				logger.error("JADX AI MCP Error: AndroidManifest.xml not found.");
				ctx.status(404).json(Map.of("error", "AndroidManifest.xml not found."));
				return;
			}

			// 解析manifest获取应用参数
			ApplicationParams results = parser.parse();
			if (results.getMainActivity() == null) {
				logger.error("JADX AI MCP Error: Failed to get main activity from manifest.");
				ctx.status(404).json(Map.of("error", "Failed to get main activity from manifest."));
				return;
			}

			// 获取主Activity的Java类对象
			JavaClass mainActivityClass = results.getMainActivityJavaClass(wrapper.getDecompiler());

			if (mainActivityClass == null) {
				logger.error("JADX AI MCP Error: Failed to get activity class: " + results.getApplication());
				ctx.status(404).json(Map.of("error", "Failed to get activity class: " + results.getApplication()));
				return;
			}

			// 构建响应数据
			Map<String, Object> result = new HashMap<>();
			result.put("name", mainActivityClass.getFullName());
			result.put("type", "code/java");
			result.put("content", mainActivityClass.getCode());

			ctx.json(result);
		} catch (Exception e) {
			logger.error("JADX AI MCP Error: " + e.getMessage(), e);
			ctx.status(500).json(Map.of("error", "Internal error retrieving AndroidManifest.xml: " + e.getMessage()));
		}
	}

	/**
	 * 处理获取字符串资源的请求
	 * <p>
	 * 此API端点返回应用中的所有字符串资源，从strings.xml文件中提取。
	 * 支持从resources.arsc和直接的strings.xml文件中获取字符串资源。
	 *
	 * @param ctx Javalin HTTP请求上下文
	 */
	private void handleStrings(Context ctx) {
		try {
			JadxWrapper wrapper = mainWindow.getWrapper();
			List<ResourceFile> resourceFiles = wrapper.getResources();

			// Explicit element type
			List<Map<String, Object>> allStringEntries = new ArrayList<>();

			for (ResourceFile resFile : resourceFiles) {
				try {
					if ("resources.arsc".equals(resFile.getDeobfName())) {
						ResContainer container = resFile.loadContent();
						List<ResContainer> subFiles = container.getSubFiles();
						for (ResContainer file : subFiles) {
							if ("res/values/strings.xml".equals(file.getFileName())) {
								Map<String, Object> entry = new HashMap<>();
								entry.put("file", file.getFileName());
								entry.put("content", file.getText().getCodeStr());
								allStringEntries.add(entry);
							}
						}
					} else if ("res/values/strings.xml".equals(resFile.getDeobfName())) {
						ResContainer container = resFile.loadContent();
						Map<String, Object> entry = new HashMap<>();
						entry.put("file", resFile.getDeobfName());
						entry.put("content", container.getText().getCodeStr());
						allStringEntries.add(entry);
					}
				} catch (Exception e) {
					logger.error("JADX AI MCP Error: {}", e.getMessage(), e);
				}
			}

			if (allStringEntries.isEmpty()) {
				ctx.status(404).json(Map.of("error", "No strings.xml resource found"));
				return;
			}

			// Use the generic pagination with an explicit transformer signature
			Map<String, Object> result = PaginationUtils.handlePagination(
					ctx,
					allStringEntries,
					"resource/strings-xml",
					"strings",
					(java.util.function.Function<Map<String, Object>, Object>) item -> {
						// Return the same map as Object to satisfy Function<T, Object>
						return item;
					}
			);

			ctx.json(result);
		} catch (JadxMcpPlugin.PaginationUtils.PaginationException e) {
			logger.error("JADX AI MCP Pagination Error: {}", e.getMessage());
			ctx.status(400).json(Map.of("error", "Pagination error: " + e.getMessage()));
		} catch (Exception e) {
			logger.error("JADX AI MCP Error: {}", e.getMessage(), e);
			ctx.status(500).json(Map.of("error", "Internal error while retrieving strings.xml file: " + e.getMessage()));
		}
	}

	/**
	 * 处理获取所有资源文件名列表的请求
	 * <p>
	 * 此API端点返回APK中所有资源文件的名称列表，包括从resources.arsc中提取的文件。
	 *
	 * @param ctx Javalin HTTP请求上下文
	 */
	private void handleListAllResourceFilesNames(Context ctx) {
		try {
			JadxWrapper wrapper = mainWindow.getWrapper();
			List<ResourceFile> resourceFiles = wrapper.getResources();
			List<String> resourceFileNames = new ArrayList<>();

			for (ResourceFile resFile : resourceFiles) {
				try {
					if (resFile.getDeobfName().equals("resources.arsc")) {

						ResContainer container = resFile.loadContent();
						List<ResContainer> subFiles = container.getSubFiles();
						for (ResContainer file : subFiles) {
							resourceFileNames.add(file.getFileName());
						}
					}
					resourceFileNames.add(resFile.getDeobfName());
				} catch (Exception e) {
					logger.error("JADX AI MCP Error: " + e.getMessage(), e);
				}
			}

			if (resourceFileNames.isEmpty()) {
				ctx.status(404).json(Map.of("error", "No resources found"));
				return;
			}

			Map<String, Object> result = new HashMap<>();
			result.put("files", resourceFileNames);

			ctx.json(result);
		} catch (Exception e) {
			logger.error("JADX AI MCP Error: " + e.getMessage(), e);
			ctx.status(500).json(
					Map.of("error", "Internal error while retrieving list of resource files names: " + e.getMessage()));
		}
	}

	/**
	 * 处理获取指定资源文件内容的请求
	 * <p>
	 * 此API端点根据文件名获取指定的资源文件内容。 支持从resources.arsc和直接资源文件中查找和返回内容。
	 * <p>
	 * 请求参数： - name (必需): 资源文件名
	 *
	 * @param ctx Javalin HTTP请求上下文
	 */
	private void handleGetResourceFile(Context ctx) {
		try {
			JadxWrapper wrapper = mainWindow.getWrapper();
			List<ResourceFile> resourceFiles = wrapper.getResources();
			Map<String, Object> resFileContent = new HashMap<>();
			String filename = ctx.queryParam("file_name");

			if (filename == null || filename.isEmpty()) {
				ctx.status(400).json(Map.of("error", "Missing required 'name' parameter."));
				return;
			}

			for (ResourceFile resFile : resourceFiles) {

				if (resFile.getDeobfName().equals(filename)) {

					ResContainer container = resFile.loadContent();
					resFileContent.put("file", resFile.getDeobfName());
					resFileContent.put("content", container.getText().getCodeStr());
					break;
				} else if (resFile.getDeobfName().equals("resources.arsc")) {
					ResContainer container = resFile.loadContent();
					List<ResContainer> subFiles = container.getSubFiles();
					for (ResContainer file : subFiles) {
						if (file.getFileName().equals(filename)) {
							resFileContent.put("file", file.getFileName());
							resFileContent.put("content", file.getText().getCodeStr());
							break;
						}
					}
				}
			}

			if (resFileContent.isEmpty()) {
				ctx.status(404).json(Map.of("error", "No resource file found"));
				return;
			}

			Map<String, Object> result = new HashMap<>();
			result.put("type", "resource/text");
			result.put("file", resFileContent);

			ctx.json(result);
		} catch (Exception e) {
			logger.error("JADX AI MCP Error: " + e.getMessage(), e);
			ctx.status(500).json(Map.of("error", "Internal error while retrieving resource file: " + e.getMessage()));
		}
	}

	// ======================== 辅助方法 ========================

	/**
	 * 获取当前选中标签页的标题
	 * <p>
	 * 从JADX主窗口的标签页控件中获取当前选中标签的标题文本。 此标题通常包含完整的类名，如"MainActivity.java"。
	 *
	 * @return 当前选中标签页的标题，如果没有选中的标签页则返回null
	 */
	private String getSelectedTabTitle() {
		// 获取JADX主窗口的标签页控件
		JTabbedPane tabs = mainWindow.getTabbedPane();

		// 获取当前选中标签页的索引
		int index = tabs.getSelectedIndex();

		// 如果有选中的标签页，返回其标题，否则返回null
		return (index != -1) ? tabs.getTitleAt(index) : null;
	}

	/**
	 * 提取当前标签页中的文本内容
	 * <p>
	 * 从JADX主窗口当前选中的标签页组件中提取完整的文本内容。 通常用于获取当前显示的类源代码。
	 * <p>
	 * 处理流程： 1. 获取当前选中的组件 2. 递归查找组件中的JTextArea 3. 提取JTextArea的完整文本内容
	 *
	 * @return 当前标签页的完整文本内容，如果没有找到JTextArea则返回null
	 */
	private String extractTextFromCurrentTab() {
		// 获取当前选中的标签页组件
		Component selectedComponent = mainWindow.getTabbedPane().getSelectedComponent();

		// 递归查找组件中的JTextArea并提取文本
		JTextArea textArea = findTextArea(selectedComponent);

		// 返回找到的文本内容，否则返回null
		return textArea != null ? textArea.getText() : null;
	}

	/**
	 * 递归查找组件中的JTextArea
	 * <p>
	 * 深度优先搜索组件树，查找第一个JTextArea实例。 JADX GUI中的代码通常显示在JTextArea组件中。
	 * <p>
	 * 搜索策略： - 深度优先遍历组件树 检查每个组件是否为JTextArea 如果组件是容器，递归搜索其子组件
	 *
	 * @param component 要搜索的根组件
	 * @return 找到的第一个JTextArea，如果没有找到则返回null
	 */
	private JTextArea findTextArea(Component component) {
		// 如果当前组件就是JTextArea，直接返回
		if (component instanceof JTextArea) {
			return (JTextArea) component;
		}

		// 如果当前组件是容器，递归搜索其子组件
		if (component instanceof Container) {
			// 遍历容器中的所有子组件
			for (Component child : ((Container) component).getComponents()) {
				// 递归搜索子组件
				JTextArea result = findTextArea(child);

				// 如果找到了，立即返回结果
				if (result != null) {
					return result;
				}
			}
		}

		// 没有找到JTextArea，返回null
		return null;
	}

	/**
	 * 解析AndroidManifest.xml文件
	 * <p>
	 * 重新使用JADX的安全XML解析逻辑来解析AndroidManifest.xml文件。
	 * 此方法直接从JADX核心代码中复制，确保与JADX的解析逻辑保持一致。
	 * <p>
	 * 源代码位置：
	 * https://github.com/skylot/jadx/blob/47647bbb9a9a3cd3150705e09cc1f84a5e9f0be6/jadx-core/src/main/java/jadx/core/utils/android/AndroidManifestParser.java#L214
	 * <p>
	 * 处理流程： 1. 将XML字符串转换为字节数组输入流 2. 使用JADX的安全解析器解析XML 3. 标准化文档结构 4.
	 * 返回可操作的Document对象
	 *
	 * @param xmlContent XML文件的字符串内容
	 * @param security   JADX安全处理器，用于XML解析
	 * @return 解析后的DOM Document对象
	 * @throws JadxRuntimeException 解析失败时抛出
	 */
	private Document parseManifestXml(String xmlContent, IJadxSecurity security) {
		// 使用try-with-resources确保输入流正确关闭
		try (InputStream xmlStream = new ByteArrayInputStream(xmlContent.getBytes(StandardCharsets.UTF_8))) {
			// 使用JADX的安全XML解析器解析输入流
			Document doc = security.parseXml(xmlStream);

			// 标准化文档结构，确保一致性
			doc.getDocumentElement().normalize();

			// 返回解析后的文档对象
			return doc;
		} catch (Exception e) {
			// 将任何解析异常包装为JADX运行时异常
			throw new JadxRuntimeException("Failed to parse AndroidManifest.xml", e);
		}
	}

	// ======================== 新增高级API方法 ========================

	/**
	 * 处理重命名包的请求
	 * <p>
	 * 此API端点允许重命名整个包名。这是一个高风险操作，会影响该包下的所有类。
	 * <p>
	 * 请求参数： - package (必需): 当前包名，如"com.example" - newName (必需):
	 * 新的包名，如"com.newname"
	 * <p>
	 * 响应： 成功时：HTTP 200，返回重命名成功信息 失败时：HTTP 4xx/5xx，返回错误信息
	 */
	private void handleRenamePackage(Context ctx) {
		String packageName = ctx.queryParam("package");
		String newName = ctx.queryParam("newName");

		// 参数验证
		if (packageName == null || packageName.isEmpty() || newName == null) {
			ctx.status(400).json(Map.of("error", "Missing 'package' or 'newName' parameter"));
			return;
		}

		try {
			JavaPackage javaPackage = findPackage(packageName);
			if (javaPackage == null) {
				ctx.status(404).json(Map.of("error", "Package not found: " + packageName));
				return;
			}

			// 必须使用 JRenamePackage 包装器
			JRenamePackage renamePackage = new JRenamePackage(
					javaPackage,
					javaPackage.getRawFullName(),
					javaPackage.getFullName(),
					javaPackage.getName()
			);

			// 验证新名称
			if (!newName.isEmpty() && !renamePackage.isValidName(newName)) {
				ctx.status(400).json(Map.of("error", "Invalid name: " + newName));
				return;
			}

			String oldName = renamePackage.getName();
			String newNodeName;
			boolean reset = newName.isEmpty();

			if (reset) {
				renamePackage.removeAlias();
				newNodeName = jadx.core.utils.Utils.getOrElse(renamePackage.getJavaNode().getName(), "");
			} else {
				newNodeName = newName;
			}

			// 创建重命名事件
			ICodeNodeRef nodeRef = javaPackage.getCodeNodeRef();
			NodeRenamedByUser renameEvent = new NodeRenamedByUser(nodeRef, oldName, newNodeName);
			renameEvent.setRenameNode(renamePackage);
			renameEvent.setResetName(reset);

			// 在 EDT 线程发送事件
			javax.swing.SwingUtilities.invokeLater(() -> {
				mainWindow.events().send(renameEvent);
				mainWindow.getProject().save();
			});

			logger.info("Renamed package: {} -> {}", packageName, newName);

			Map<String, Object> result = new HashMap<>();
			result.put("success", true);
			result.put("message", "Package renamed successfully");
			result.put("oldName", oldName);
			result.put("newName", newName);
			ctx.json(result);

		} catch (Exception e) {
			logger.error("Error renaming package: " + e.getMessage(), e);
			ctx.status(500).json(Map.of("error", "Internal server error: " + e.getMessage()));
		}
	}

	private JavaPackage findPackage(String packageName) {
		JadxWrapper wrapper = mainWindow.getWrapper();
		if (wrapper == null) {
			return null;
		}

		for (JavaPackage pkg : wrapper.getPackages()) {
			if (pkg.getFullName().equals(packageName)) {
				return pkg;
			}
		}
		return null;
	}

	/**
	 * 处理重命名方法参数的请求
	 * <p>
	 * 此API端点允许重命名方法的参数。需要提供参数在方法签名中的索引位置。
	 * <p>
	 * 请求参数： - class_name (必需): 完整类名 - method_name (必需):
	 * 方法名，支持完整路径如"com.example.MainActivity.onCreate" - method_signature (可选):
	 * 方法签名，用于区分重载方法 - param_index (必需): 参数索引（从0开始） - new_name (必需): 新的参数名 -
	 * skip_refresh (可选): 是否跳过UI刷新，默认false
	 */
	private void handleRenameMethodParameter(Context ctx) {
		try {
			// 1. 获取方法
			JavaMethod method = findMethod(ctx);
			// 2. 获取参数索引和新名称
			String paramIndexStr = getParameter(ctx, "param_index", "paramIndex");
			String newName = getRenameParameter(ctx, "newName", "new_name", "newMethodName", "new_method_name");

			int paramIndex = Integer.parseInt(paramIndexStr);

			// 3. 使用新的方法获取所有参数节点
			MethodNode methodNode = method.getMethodNode();
			List<VarNode> argNodes = methodNode.collectArgNodes();

			// 4. 验证参数索引
			if (paramIndex < 0 || paramIndex >= argNodes.size()) {
				ctx.status(400).json(Map.of("error", "Invalid parameter index. Method has "
						+ argNodes.size() + " parameters (index 0-" + (argNodes.size() - 1) + ")"));
				return;
			}

			// 5. 获取目标参数节点
			VarNode targetParamNode = argNodes.get(paramIndex);

			// 6. 获取当前参数名
			String oldParameterName = targetParamNode.getName();
			if (oldParameterName == null) {
				oldParameterName = "param" + paramIndex;
			}

			// 7. 执行重命名
			NodeRenamedByUser event = new NodeRenamedByUser(
					targetParamNode, // 变量节点的引用
					oldParameterName, // 旧名称
					newName // 新名称
			);
			event.setRenameNode(targetParamNode);
			event.setResetName(newName.isEmpty());

			// 8. 发送事件
			mainWindow.events().send(event);

			// 9. 返回结果
			Map<String, Object> result = new HashMap<>();
			result.put("success", true);
			result.put("message", "Parameter renamed from " + oldParameterName + " to " + newName);
			result.put("method", method.getName());
			result.put("parameter_index", paramIndex);
			result.put("old_name", oldParameterName);
			result.put("new_name", newName);
			result.put("total_parameters", argNodes.size());

			ctx.json(result);

		} catch (NoSuchElementException e) {
			ctx.status(404).json(Map.of("error", e.getMessage()));
		} catch (IllegalArgumentException e) {
			ctx.status(400).json(Map.of("error", e.getMessage()));
		} catch (Exception e) {
			ctx.status(500).json(Map.of("error", "Internal server error: " + e.getMessage()));
		}
	}


	// ======================== 注释操作API方法 ========================

	/**
	 * 处理添加类注释的请求
	 * <p>
	 * 此API端点专门为类添加JavaDoc注释，支持内部类和匿名类。 使用JADX的类查找机制定位目标类，并通过事件系统添加注释。
	 * <p>
	 * 请求参数： - class_name (必需): 完整类名，支持内部类格式如com.example.MainActivity$InnerClass
	 * - comment (必需): 注释内容 - style (可选): 注释样式，默认JAVADOC
	 * <p>
	 * 响应格式： 成功时：HTTP 200，返回操作成功信息和类详情 失败时：HTTP 4xx/5xx，返回详细的错误信息
	 * <p>
	 * 使用示例： GET
	 * /add-class-comment?class_name=com.example.MainActivity&comment=应用的主Activity类
	 * GET
	 * /add-class-comment?class_name=com.example.MainActivity$InnerClass&comment=内部处理类
	 */
	private void handleAddClassComment(Context ctx) {
		// 获取请求参数
		String comment = getParameter(ctx, "comment");
		String style = getRenameParameter(ctx, "style");

		// 验证必需的参数
		if (comment == null) {
			logger.error("JADX AI MCP Error: Missing  'comment',it can be empty to reset,but not null");
			ctx.status(400).json(Map.of("error", "Missing  'comment',it can be empty to reset,but not null"));
			return;
		}

		try {
			JavaClass cls = findClass(ctx);
			ICodeNodeRef nodeRef = cls.getCodeNodeRef();
			JadxNodeRef jadxNodeRef = JadxNodeRef.forCls(cls);
			CommentStyle commentStyle = CommentStyle.valueOf(style != null && style.isEmpty() ? style.toUpperCase() : "JAVADOC");

			// Create the comment object
			JadxCodeComment codeComment = new JadxCodeComment(jadxNodeRef, null, comment, commentStyle);

			// Add comment to project data using the same mechanism as CommentDialog
			addCommentToProject(codeComment);

			Map<String, Object> result = new HashMap<>();
			result.put("success", true);
			result.put("message", "Class comment added successfully");
			result.put("className", cls.getFullName());
			result.put("comment", comment);
			result.put("style", style);
			result.put("type", "class_comment");

			ctx.json(result);

		} catch (NoSuchElementException e) {
			ctx.status(404).json(Map.of("error", e.getMessage()));
		} catch (IllegalArgumentException e) {
			ctx.status(400).json(Map.of("error", e.getMessage()));
		} catch (Exception e) {
			logger.error("JADX AI MCP Error: " + e.getMessage(), e);
			ctx.status(500).json(Map.of("error", "Internal error rename Class: " + e.getMessage()));
		}
	}

	/**
	 * 处理添加方法注释的请求
	 * <p>
	 * 此API端点专门为方法添加JavaDoc注释，支持重载方法和内部类方法。 通过类名和方法精确定位目标方法，支持方法签名来区分重载方法。
	 * <p>
	 * 请求参数： - class_name (必需): 完整类名，支持内部类格式 - method_name (必需): 方法名 -
	 * method_signature (可选): 方法签名，用于区分重载方法 - comment (必需): 注释内容 - style (可选):
	 * 注释样式，默认JAVADOC
	 * <p>
	 * 响应格式： 成功时：HTTP 200，返回操作成功信息和方法详情 失败时：HTTP 4xx/5xx，返回详细的错误信息
	 * <p>
	 * 使用示例： GET
	 * /add-method-comment?class_name=com.example.MainActivity&method_name=onCreate&comment=Activity初始化方法
	 * GET
	 * /add-method-comment?class_name=com.example.MainActivity&method_name=onCreate&method_signature=(Landroid/os/Bundle;)V&comment=带签名的注释
	 */
	private void handleAddMethodComment(Context ctx) {
		// 获取请求参数
		String comment = getParameter(ctx, "comment");
		String style = getRenameParameter(ctx, "style");

		// 验证必需的参数
		if (comment == null) {
			logger.error("JADX AI MCP Error: Missing  'comment',it can be empty to reset,but not null");
			ctx.status(400).json(Map.of("error", "Missing  'comment',it can be empty to reset,but not null"));
			return;
		}

		try {
			// JavaClass cls = findClass(ctx);
			JavaMethod method = findMethod(ctx);
			JadxNodeRef jadxNodeRef = JadxNodeRef.forMth(method);
			CommentStyle commentStyle = CommentStyle.valueOf(style != null && style.isEmpty() ? style.toUpperCase() : "JAVADOC");

			// Create the comment object
			JadxCodeComment codeComment = new JadxCodeComment(jadxNodeRef, null, comment, commentStyle);

			// Add comment to project data using the same mechanism as CommentDialog
			addCommentToProject(codeComment);

			Map<String, Object> result = new HashMap<>();
			result.put("success", true);
			result.put("message", "method comment added successfully");
			result.put("className", method.getDeclaringClass().getFullName());
			result.put("methodName", method.getName());
			result.put("comment", comment);
			result.put("style", style);
			result.put("type", "method_comment");

			ctx.json(result);

		} catch (NoSuchElementException e) {
			ctx.status(404).json(Map.of("error", e.getMessage()));
		} catch (IllegalArgumentException e) {
			ctx.status(400).json(Map.of("error", e.getMessage()));
		} catch (Exception e) {
			ctx.status(500).json(Map.of("error", "Internal server error: " + e.getMessage()));
		}
	}

	/**
	 * 处理添加字段注释的请求
	 * <p>
	 * 此API端点专门为字段添加注释，支持实例字段和静态字段。 通过类名和字段名精确定位目标字段，支持内部类的字段注释。
	 * <p>
	 * 请求参数： - class_name (必需): 完整类名，支持内部类格式 - field_name (必需): 字段名 - comment
	 * (必需): 注释内容 - style (可选): 注释样式，默认LINE
	 * <p>
	 * 响应格式： 成功时：HTTP 200，返回操作成功信息和字段详情 失败时：HTTP 4xx/5xx，返回详细的错误信息
	 * <p>
	 * 使用示例： GET
	 * /add-field-comment?class_name=com.example.MainActivity&field_name=textView&comment=主文本显示控件
	 * GET
	 * /add-field-comment?class_name=com.example.MainActivity$InnerClass&field_name=innerField&comment=内部类字段
	 */
	private void handleAddFieldComment(Context ctx) {
		// 获取请求参数
		String comment = getParameter(ctx, "comment");
		String style = getRenameParameter(ctx, "style");

		// 验证必需的参数
		if (comment == null) {
			logger.error("JADX AI MCP Error: Missing  'comment',it can be empty to reset,but not null");
			ctx.status(400).json(Map.of("error", "Missing  'comment',it can be empty to reset,but not null"));
			return;
		}

		try {
			// JavaClass cls = findClass(ctx);
			JavaField field = findField(ctx);

			JadxNodeRef jadxNodeRef = JadxNodeRef.forFld(field);
			CommentStyle commentStyle = CommentStyle.valueOf(style != null && style.isEmpty() ? style.toUpperCase() : "JAVADOC");

			// Create the comment object
			JadxCodeComment codeComment = new JadxCodeComment(jadxNodeRef, null, comment, commentStyle);

			// Add comment to project data using the same mechanism as CommentDialog
			addCommentToProject(codeComment);

			Map<String, Object> result = new HashMap<>();
			result.put("success", true);
			result.put("message", "field comment added successfully");
			result.put("class", field.getDeclaringClass().getFullName());
			result.put("fieldName", field.getName());
			result.put("comment", comment);
			result.put("style", style);
			result.put("type", "field_comment");

			ctx.json(result);

		} catch (NoSuchElementException e) {
			ctx.status(404).json(Map.of("error", e.getMessage()));
		} catch (IllegalArgumentException e) {
			ctx.status(400).json(Map.of("error", e.getMessage()));
		} catch (Exception e) {
			logger.error("JADX AI MCP Error: " + e.getMessage(), e);
			ctx.status(500).json(Map.of("error", "Internal error add comment: " + e.getMessage()));
		}
	}


	// ======================== 元数据操作API方法 ========================

	/**
	 * 处理获取方法详细元数据的请求
	 * <p>
	 * 此API端点返回方法的详细元数据信息，包括每行代码对应的指令偏移量。 这对于精确定位代码位置和添加指令级注释非常重要。
	 * <p>
	 * 请求参数： - class_name (必需): 完整类名 - method_name (必需): 方法名 - method_signature
	 * (可选): 方法签名，用于区分重载方法
	 * <p>
	 * 响应格式： { "class_name": "com.example.MainActivity", "method_name":
	 * "onCreate", "method_signature": "(Landroid/os/Bundle;)V", "code_lines": [
	 * { "line_number": 1, "line_content":
	 * "super.onCreate(savedInstanceState);", "instruction_offset": 12,
	 * "code_type": "method_call" } ] }
	 */
	private void handleGetMethodMetadata(Context ctx) {
		try {

			JavaMethod method = findMethod(ctx);

			// 获取方法源代码并分析每行的指令偏移量
			String methodCode = method.getCodeStr();
			List<Map<String, Object>> codeLines = parseMethodCodeLines(methodCode, method);

			Map<String, Object> result = new HashMap<>();
			result.put("class", method.getDeclaringClass().getFullName());
			result.put("method_name", method.getName());
			result.put("method_signature", method.getMethodNode().getMethodInfo().getShortId());
			result.put("code_lines", codeLines);

			ctx.status(200).json(result);
		} catch (NoSuchElementException e) {
			ctx.status(404).json(Map.of("error", e.getMessage()));
		} catch (IllegalArgumentException e) {
			ctx.status(400).json(Map.of("error", e.getMessage()));
		} catch (Exception e) {
			ctx.status(500).json(Map.of("error", "Internal server error: " + e.getMessage()));
		}
	}

	// ======================== 辅助方法 ========================

	/**
	 * 验证节点类型是否有效
	 */
	private boolean isValidNodeType(String nodeType) {
		return Arrays.asList("class", "method", "field", "instruction").contains(nodeType.toLowerCase());
	}

	/**
	 * 验证注释样式是否有效
	 */
	private boolean isValidCommentStyle(String style) {
		return Arrays.asList("LINE", "BLOCK", "JAVADOC").contains(style.toUpperCase());
	}

	/**
	 * 根据类名查找JavaClass对象
	 */
	private JavaClass findClass(Context ctx) {
		// 同时检查两种参数来源，按优先级顺序
		String className = getParameter(ctx, "name", "class_name", "class");
		String rawName = getParameter(ctx, "rawName", "raw_name", "class_raw_name", "classRawName");

		return findClassByName(className, rawName);
	}

	/**
	 * 获取参数 - 空字符串被视为无效值
	 */
	private String getParameter(Context ctx, String... paramNames) {
		// 按优先级检查参数名
		for (String paramName : paramNames) {
			String value = ctx.queryParam(paramName);
			if (value == null || value.isEmpty()) {
				value = ctx.formParam(paramName);
			}
			if (value != null && !value.isEmpty()) {
				return value;
			}
		}
		return null;
	}

	/**
	 * 获取重命名参数 - 允许空字符串作为重置标志
	 */
	private String getRenameParameter(Context ctx, String... paramNames) {
		for (String paramName : paramNames) {
			String value = ctx.queryParam(paramName);
			if (value != null) {
				return value;  // 包括空字符串 ""
			}

			value = ctx.formParam(paramName);
			if (value != null) {
				return value;  // 包括空字符串 ""
			}
		}
		return null;
	}

	private JavaClass findClassByName(String className, String rawName) {
		JadxWrapper wrapper = mainWindow.getWrapper();
		if (wrapper == null) {
			logger.error("wrapper is null");
			throw new IllegalStateException("wrapper is null");
		}

		// 检查参数是否为空
		if ((className == null || className.isEmpty()) && (rawName == null || rawName.isEmpty())) {
			logger.error("JADX AI MCP Error: Missing class name parameters");
			throw new IllegalArgumentException("Missing 'className' or 'rawName' parameter.");
		}

		// 先尝试用 className 查找
		if (className != null && !className.isEmpty()) {
			String searchName = className.replace('$', '.');
			for (JavaClass cls : wrapper.getIncludedClassesWithInners()) {
				if (cls.getFullName().equals(searchName)) {
					return cls;
				}
			}
		}

		// 再尝试用 rawName 查找
		if (rawName != null && !rawName.isEmpty()) {
			for (JavaClass cls : wrapper.getIncludedClassesWithInners()) {
				if (cls.getClassNode().getRawName().equals(rawName)) {
					return cls;
				}
			}
		}

		// 都找不到则抛出异常
		String searchName = className != null ? className : rawName;
		logger.error("JADX AI MCP Error: Class not found: " + searchName);
		throw new NoSuchElementException("Class not found: " + searchName);
	}

	//找字段
	private JavaField findField(Context ctx) {
		// 同时检查两种参数来源，按优先级顺序
		String fieldName = getParameter(ctx, "fieldName", "field_name", "field");
		String fieldRawName = getParameter(ctx, "field_rawName", "field_raw_name", "fieldRawName");

		JavaClass cls = findClass(ctx);
		return findField(cls, fieldName, fieldRawName);
	}

	private JavaField findField(JavaClass cls, String fieldName, String fieldRawName) {
		boolean hasFieldName = fieldName != null && !fieldName.isEmpty();
		boolean hasFieldRawName = fieldRawName != null && !fieldRawName.isEmpty();

		if (!hasFieldName && !hasFieldRawName) {
			logger.error("JADX AI MCP Error: Missing 'field_name' or 'field_rawName' name parameters");
			throw new IllegalArgumentException("Missing 'field_name' or 'field_rawName' name parameters");
		}

		try {
			for (JavaField field : cls.getFields()) {
				// 优先级1：fieldRawName匹配
				if (hasFieldRawName && field.getRawName().equals(fieldRawName)) {
					return field;
				}
				// 优先级2：fieldName匹配
				if (hasFieldName && field.getName().equals(fieldName)) {
					return field;
				}
			}
		} catch (Exception e) {
			logger.error("Error finding field: " + e.getMessage(), e);
		}
		throw new NoSuchElementException("Field not found: " + (hasFieldRawName ? fieldRawName : fieldName));

	}

	/**
	 * 在类中查找指定的方法
	 */
	private JavaMethod findMethod(JavaClass cls, String methodName, String originalName, String methodSignature) {
		boolean hasMethodName = methodName != null && !methodName.isEmpty();
		boolean hasOriginalName = originalName != null && !originalName.isEmpty();
		boolean hasSignature = methodSignature != null && !methodSignature.isEmpty();

		if (!hasMethodName && !hasOriginalName) {
			throw new IllegalArgumentException("Missing 'method_name' or 'original_name' parameter");
		}

		try {
			for (JavaMethod method : cls.getMethods()) {
				jadx.core.dex.nodes.MethodNode methodNode = method.getMethodNode();
				jadx.core.dex.info.MethodInfo methodInfo = methodNode.getMethodInfo();

				boolean nameMatch = false;
				String searchTarget = null;

				// 优先级1：originalName匹配
				if (hasOriginalName && methodInfo.getName().equals(originalName)) {
					nameMatch = true;
					searchTarget = originalName;
				}
				// 优先级2：methodName匹配
				else if (hasMethodName && method.getName().equals(methodName)) {
					nameMatch = true;
					searchTarget = methodName;
				}

				// 如果名称匹配，检查签名（如果提供了签名）
				if (nameMatch) {
					if (hasSignature) {
						// 检查签名是否匹配
						if (methodInfo.getShortId().equals(methodSignature) ||
								methodInfo.getShortId().contains(methodSignature)) {
							return method;
						}
						// 如果提供了签名但不匹配，继续搜索下一个方法
						continue;
					}
					// 如果没有提供签名，返回匹配名称的第一个方法
					return method;
				}
			}
		} catch (Exception e) {
			logger.error("Error searching methods in class {}: {}", cls.getFullName(), e.getMessage());
			throw new RuntimeException("Failed to search methods: " + e.getMessage(), e);
		}

		// 构建详细的错误信息
		String errorMsg = String.format("Method not found in class %s", cls.getFullName());
		if (hasOriginalName) {
			errorMsg += String.format(" (searched for original name: '%s'", originalName);
		}
		if (hasMethodName) {
			errorMsg += String.format("%ssearched for name: '%s'",
					hasOriginalName ? ", " : " (", methodName);
		}
		if (hasSignature) {
			errorMsg += String.format(" with signature containing: '%s'", methodSignature);
		}
		errorMsg += ")";

		throw new NoSuchElementException(errorMsg);

	}

	private JavaMethod findMethod(Context ctx) {

		String methodName = getParameter(ctx, "method", "method_name", "methodName");
		String originalName = getParameter(ctx, "original_name", "originalName", "method_original_name");
		String methodSignature = getParameter(ctx, "method_signature", "signature");

		JavaClass cls = findClass(ctx);

		return findMethod(cls, methodName, originalName, methodSignature);
	}


	/**
	 * 解析方法代码行，为每行添加指令偏移量信息
	 */
	private List<Map<String, Object>> parseMethodCodeLines(String methodCode, JavaMethod method) {
		List<Map<String, Object>> codeLines = new ArrayList<>();

		if (methodCode == null || methodCode.isEmpty()) {
			return codeLines;
		}

		String[] lines = methodCode.split("\n");
		int currentOffset = 0;

		for (int i = 0; i < lines.length; i++) {
			String line = lines[i].trim();
			if (line.isEmpty()) {
				continue;
			}

			Map<String, Object> lineInfo = new HashMap<>();
			lineInfo.put("line_number", i + 1);
			lineInfo.put("line_content", line);
			lineInfo.put("instruction_offset", currentOffset);
			lineInfo.put("code_type", determineCodeType(line));

			codeLines.add(lineInfo);
			currentOffset += estimateInstructionSize(line);
		}

		return codeLines;
	}

	/**
	 * 确定代码行的类型
	 */
	private String determineCodeType(String line) {
		line = line.trim();
		if (line.startsWith("//") || line.startsWith("/*")) {
			return "comment";
		} else if (line.contains("(") && line.contains(")")) {
			return "method_call";
		} else if (line.contains("=")) {
			return "assignment";
		} else if (line.startsWith("return")) {
			return "return";
		} else if (line.startsWith("if") || line.startsWith("for") || line.startsWith("while")) {
			return "control_flow";
		} else {
			return "statement";
		}
	}

	/**
	 * 估算指令的字节码大小
	 */
	private int estimateInstructionSize(String line) {
		// 简化的指令大小估算
		// 实际实现需要更精确的字节码分析
		return line.length() + 2; // 简单估算
	}

	//new
	public void handleClassInfo(Context ctx) {
		try {
			JavaClass cls = findClass(ctx);
			Map<String, Object> classInfo = new HashMap<>();
			classInfo.put("className", cls.getFullName());
			classInfo.put("shortName", cls.getName());
			classInfo.put("rawName", cls.getClassNode().getRawName());
			classInfo.put("type", cls.isInner() ? "inner class" : "class");
			classInfo.put("packageName", cls.getPackage());
			classInfo.put("isInner", cls.isInner());
			classInfo.put("codeSourceLength", cls.getCode().length());

			// 统计信息而不是完整对象
			classInfo.put("innerClassesCount", cls.getInnerClasses().size());
			classInfo.put("inlinedClassesCount", cls.getInlinedClasses().size());
			classInfo.put("fieldsCount", cls.getFields().size());
			classInfo.put("methodsCount", cls.getMethods().size());

			List<String> innerClassNames = new ArrayList<>();
			for (JavaClass innerClass : cls.getInnerClasses()) {
				innerClassNames.add(innerClass.getFullName());
			}
			classInfo.put("innerClassNames", innerClassNames);

			ctx.json(classInfo);

		} catch (NoSuchElementException e) {
			ctx.status(404).json(Map.of("error", e.getMessage()));
		} catch (IllegalArgumentException e) {
			ctx.status(400).json(Map.of("error", e.getMessage()));
		} catch (Exception e) {
			ctx.status(500).json(Map.of("error", "Internal server error: " + e.getMessage()));
		}

	}

	// Helper method to add comment to project data using the same mechanism as CommentDialog
	private void addCommentToProject(ICodeComment comment) {
		try {
			JadxProject project = mainWindow.getProject();
			JadxCodeData codeData = project.getCodeData();
			if (codeData == null) {
				codeData = new JadxCodeData();
			}

			List<ICodeComment> commentList = new ArrayList<>(codeData.getComments());

			// Remove existing comment if it exists (for update behavior)
			commentList.removeIf(c -> Objects.equals(c.getNodeRef(), comment.getNodeRef())
					&& Objects.equals(c.getCodeRef(), comment.getCodeRef()));

			// Add the new comment
			commentList.add(comment);
			Collections.sort(commentList);

			// Update project data
			codeData.setComments(commentList);
			project.setCodeData(codeData);

			// Reload code data to refresh UI
			mainWindow.getWrapper().reloadCodeData();

		} catch (Exception e) {
			logger.error("Failed to add comment to project", e);
			throw new RuntimeException("Failed to add comment to project: " + e.getMessage());
		}

		// Refresh code display on EDT thread (same as CommentDialog does)
		// javax.swing.SwingUtilities.invokeLater(() -> {
		//     try {
		//         refreshCurrentCodeArea();
		//     } catch (Exception e) {
		//         logger.error("Failed to reload code", e);
		//     }
		// });
	}

	/**
	 * 处理获取方法内部所有CodeRef的请求
	 * <p>
	 * 此API端点返回方法内部所有可用于注释的CodeRef列表，包括： - 方法参数的CodeRef - 局部变量的CodeRef -
	 * 异常处理器的CodeRef - 指令的CodeRef
	 * <p>
	 * 请求参数： - class_name (必需): 完整类名 - method_name (必需): 方法名 - method_signature
	 * (可选): 方法签名，用于区分重载方法
	 * <p>
	 * 响应格式： { "class_name": "com.example.MainActivity", "method_name":
	 * "onCreate", "code_refs": [ { "type": "MTH_ARG", "index": 0, "name":
	 * "savedInstanceState", "display_name": "参数0: savedInstanceState",
	 * "code_ref": {"attachType": "MTH_ARG", "index": 0} }, { "type": "VAR",
	 * "index": 65536, "name": "tempVar", "register": 1, "ssa_version": 0,
	 * "display_name": "变量: tempVar (寄存器1:SSA0)", "code_ref": {"attachType":
	 * "VAR", "index": 65536} }, { "type": "INSN", "index": 12, "instruction":
	 * "super.onCreate(savedInstanceState)", "offset": 12, "display_name":
	 * "指令@12: super.onCreate(savedInstanceState)", "code_ref": {"attachType":
	 * "INSN", "index": 12} } ] }
	 */
	private void handleGetMethodInstructions(Context ctx) {
		try {
			JavaMethod method = findMethod(ctx);
			MethodNode methodNode = method.getMethodNode();

			if (!methodNode.isLoaded()) {
				logger.warn(methodNode.getName() + " 需要重新加载");
				methodNode.reload();
			}

			List<Map<String, Object>> allCodeRefs = new ArrayList<>();
			List<String> insS = new ArrayList<>();
			if (methodNode.getInstructions() != null) {
				for (InsnNode ins : methodNode.getInstructions()) {
					if (ins == null) {
						continue;
					}
					String str = ins.toString();
					JadxCodeRef codeRef = JadxCodeRef.forInsn(ins.getOffset());
					insS.add(str);

				}
			}

			Map<String, Object> result = new HashMap<>();
			result.put("method", method.getName());
			result.put("signature", method.getMethodNode().getMethodInfo().getShortId());
			// result.put("total_code_refs", allCodeRefs.size());
			result.put("instructionCount", methodNode.getInstructions() != null ? methodNode.getInstructions().length : null);
			result.put("insList", insS);

			ctx.json(result);

		} catch (NoSuchElementException e) {
			ctx.status(404).json(Map.of("error", e.getMessage()));
		} catch (IllegalArgumentException e) {
			ctx.status(400).json(Map.of("error", e.getMessage()));
		} catch (Exception e) {
			logger.error("JADX AI MCP Error: " + e.getMessage(), e);
			ctx.status(500).json(Map.of("error", "Internal server error: " + e.getMessage()));
		}
	}

	/**
	 * 获取方法每行源代码对应的JadxCodeRef映射关系
	 * <p>
	 * 该接口根据JADX反编译的Java代码，建立源代码行号与字节码指令偏移量、变量声明等CodeRef之间的映射关系。
	 * 这对于代码分析、注释添加、变量重命名等操作非常有用。
	 * <p>
	 * 功能特性：
	 * 1. 精确映射：每行源代码对应的所有CodeRef
	 * 2. 多类型支持：指令偏移量、变量声明、参数引用等
	 * 3. 位置信息：提供字符位置和字节码偏移量
	 * 4. 灵活查询：支持按行号或按位置范围查询
	 * <p>
	 * 使用场景：
	 * - 在特定代码行添加注释或分析标记
	 * - 精确定位恶意代码的字节码位置
	 * - 重命名特定位置的变量或参数
	 * - 代码安全审计和漏洞分析
	 * <p>
	 * 请求参数：
	 * - rawName: 类的原始名称（必需）
	 * - methodName: 方法名称（必需）
	 * - methodSignature: 方法签名（必需，用于重载方法区分）
	 * - lineNumbers: 可选，要查询的特定行号列表，逗号分隔（如：1,5,10）
	 * - offset: 分页偏移量，默认0
	 * - limit: 每页行数限制，默认100，最大1000
	 * <p>
	 * 响应格式：
	 * {
	 * "method": "方法名称",
	 * "className": "类的完整名称",
	 * "signature": "方法签名",
	 * "totalLines": 25,
	 * "codeLines": [
	 * {
	 * "lineNumber": 10,
	 * "lineContent": "String message = \"Hello\";",
	 * "startPosition": 245,
	 * "endPosition": 270,
	 * "codeRefs": [
	 * {
	 * "type": "VARIABLE",
	 * "name": "message",
	 * "register": 2,
	 * "ssa": 1,
	 * "position": 245,
	 * "codeRef": "JadxCodeRef{attachType=VAR, index=131073}"
	 * },
	 * {
	 * "type": "INSTRUCTION",
	 * "offset": 12,
	 * "position": 250,
	 * "codeRef": "JadxCodeRef{attachType=INSN, index=12}"
	 * }
	 * ]
	 * }
	 * ],
	 * "pagination": {
	 * "offset": 0,
	 * "limit": 100,
	 * "hasMore": false
	 * }
	 * }
	 *
	 * @param ctx Javalin HTTP请求上下文，包含查询参数
	 */
	private void handleGetMethodCodeRefsByLine(Context ctx) {
		try {
			// 获取请求参数
			String lineNumbersParam = getParameter(ctx, "lineNumbers");
			int offset = 0;
			int limit = 100;

			try {
				String offsetStr = getParameter(ctx, "offset");
				if (offsetStr != null && !offsetStr.trim().isEmpty()) {
					offset = Integer.parseInt(offsetStr.trim());
				}
			} catch (NumberFormatException e) {
				// 使用默认值0
			}

			try {
				String limitStr = getParameter(ctx, "limit");
				if (limitStr != null && !limitStr.trim().isEmpty()) {
					limit = Math.min(1000, Math.max(1, Integer.parseInt(limitStr.trim())));
				}
			} catch (NumberFormatException e) {
				// 使用默认值100
			}

			// 解析行号参数
			Set<Integer> targetLineNumbers = null;
			if (lineNumbersParam != null && !lineNumbersParam.trim().isEmpty()) {
				try {
					targetLineNumbers = Arrays.stream(lineNumbersParam.split(","))
							.map(String::trim)
							.mapToInt(Integer::parseInt)
							.boxed()
							.collect(Collectors.toSet());
				} catch (NumberFormatException e) {
					ctx.status(400).json(Map.of("error", "Invalid lineNumbers format. Expected comma-separated integers like '1,5,10'"));
					return;
				}
			}

			// 查找方法
			JavaMethod method = findMethod(ctx);
			MethodNode methodNode = method.getMethodNode();

			// 确保方法已加载
			if (!methodNode.isLoaded()) {
				logger.warn(methodNode.getName() + " 需要重新加载");
				methodNode.reload();
			}

			// 获取方法的源代码和元数据
			String methodCode = method.getCodeStr();
			jadx.api.ICodeInfo codeInfo = methodNode.getTopParentClass().getCode();

			// 收集所有CodeRef的映射关系
			List<Map<String, Object>> codeRefsMapping = new ArrayList<>();

			if (codeInfo != null && codeInfo.hasMetadata()) {
				int methodStartPos = methodNode.getDefPosition();
				int methodEndPos = jadx.api.utils.CodeUtils.getMethodEnd(methodNode, codeInfo);

				if (methodEndPos != -1) {
					// 分割方法代码为行
					String[] lines = methodCode.split("\n");
					int currentPos = methodStartPos;

					// 应用分页
					int startLine = offset;
					int endLine = Math.min(lines.length, startLine + limit);

					for (int lineNum = startLine; lineNum < endLine; lineNum++) {
						// 如果指定了目标行号，跳过其他行
						if (targetLineNumbers != null && !targetLineNumbers.contains(lineNum + 1)) {
							currentPos += lines[lineNum].length() + 1; // +1 for newline
							continue;
						}

						String line = lines[lineNum];
						int lineStartPos = currentPos;
						int lineEndPos = lineStartPos + line.length();

						// 收集这一行的所有CodeRef
						List<Map<String, Object>> lineCodeRefs = new ArrayList<>();

						// 搜索这个位置范围内的所有元数据标注
						codeInfo.getCodeMetadata().searchDown(lineStartPos, (pos, ann) -> {
							if (pos > lineEndPos) {
								return null; // 超出当前行范围
							}

							Map<String, Object> codeRefInfo = new HashMap<>();

							try {
								// 处理不同类型的标注
								if (ann instanceof jadx.api.metadata.annotations.InsnCodeOffset) {
									// 指令偏移量标注
									jadx.api.metadata.annotations.InsnCodeOffset offsetAnn =
											(jadx.api.metadata.annotations.InsnCodeOffset) ann;
									codeRefInfo.put("type", "INSTRUCTION");
									codeRefInfo.put("offset", offsetAnn.getOffset());
									codeRefInfo.put("position", pos);
									// 创建对应的JadxCodeRef
									codeRefInfo.put("codeRef", String.format("JadxCodeRef{attachType=INSN, index=%d}", offsetAnn.getOffset()));

								} else if (ann instanceof jadx.api.metadata.annotations.NodeDeclareRef) {
									// 节点声明标注（变量、参数等）
									jadx.api.metadata.ICodeNodeRef nodeRef =
											((jadx.api.metadata.annotations.NodeDeclareRef) ann).getNode();

									if (nodeRef instanceof jadx.api.metadata.annotations.VarNode) {
										jadx.api.metadata.annotations.VarNode varNode =
												(jadx.api.metadata.annotations.VarNode) nodeRef;
										codeRefInfo.put("type", "VARIABLE");
										codeRefInfo.put("name", varNode.getName());
										codeRefInfo.put("register", varNode.getReg());
										codeRefInfo.put("ssa", varNode.getSsa());
										codeRefInfo.put("position", pos);
										// 创建对应的JadxCodeRef
										int varIndex = (varNode.getReg() << 16) | varNode.getSsa();
										codeRefInfo.put("codeRef", String.format("JadxCodeRef{attachType=VAR, index=%d}", varIndex));
									}

								} else if (ann instanceof jadx.api.metadata.annotations.NodeEnd) {
									// 节点结束标注
									codeRefInfo.put("type", "NODE_END");
									codeRefInfo.put("position", pos);

								} else {
									// 其他类型的标注
									codeRefInfo.put("type", "OTHER");
									codeRefInfo.put("annotationType", ann.getAnnType().toString());
									codeRefInfo.put("position", pos);
								}
							} catch (Exception e) {
								// 处理单个标注解析错误，不中断整个过程
								logger.warn("Failed to parse annotation at position {}: {}", pos, e.getMessage());
								codeRefInfo.put("type", "ERROR");
								codeRefInfo.put("error", e.getMessage());
								codeRefInfo.put("position", pos);
							}

							if (!codeRefInfo.isEmpty()) {
								lineCodeRefs.add(codeRefInfo);
							}

							return null; // 继续搜索
						});

						// 如果这一行有CodeRef或者请求了特定行号，添加到结果中
						if (!lineCodeRefs.isEmpty() || (targetLineNumbers != null && targetLineNumbers.contains(lineNum + 1))) {
							Map<String, Object> lineMapping = new HashMap<>();
							lineMapping.put("lineNumber", lineNum + 1);
							lineMapping.put("lineContent", line);
							lineMapping.put("startPosition", lineStartPos);
							lineMapping.put("endPosition", lineEndPos);
							lineMapping.put("codeRefs", lineCodeRefs);
							lineMapping.put("hasCodeRefs", !lineCodeRefs.isEmpty());
							codeRefsMapping.add(lineMapping);
						}

						currentPos = lineEndPos + 1; // +1 for newline character
					}
				}
			}

			// 构建分页信息
			Map<String, Object> pagination = new HashMap<>();
			pagination.put("offset", offset);
			pagination.put("limit", limit);
			pagination.put("hasMore", offset + limit < codeRefsMapping.size());
			pagination.put("totalItems", codeRefsMapping.size());

			// 构建最终响应
			Map<String, Object> result = new HashMap<>();
			result.put("method", method.getName());
			result.put("className", method.getDeclaringClass().getFullName());
			result.put("signature", method.getMethodNode().getMethodInfo().getShortId());
			result.put("totalLines", codeRefsMapping.size());
			result.put("codeLines", codeRefsMapping);
			result.put("pagination", pagination);

			// 添加请求信息用于调试
			if (targetLineNumbers != null) {
				result.put("requestedLines", targetLineNumbers);
			}
			result.put("hasMetadata", codeInfo != null && codeInfo.hasMetadata());

			ctx.json(result);

		} catch (NoSuchElementException e) {
			ctx.status(404).json(Map.of("error", e.getMessage()));
		} catch (IllegalArgumentException e) {
			ctx.status(400).json(Map.of("error", e.getMessage()));
		} catch (Exception e) {
			logger.error("JADX AI MCP Error in handleGetMethodCodeRefsByLine: " + e.getMessage(), e);
			ctx.status(500).json(Map.of("error", "Internal server error: " + e.getMessage()));
		}
	}

}
