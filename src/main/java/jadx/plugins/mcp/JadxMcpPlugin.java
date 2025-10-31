package jadx.plugins.mcp;


import jadx.api.*;
import jadx.api.data.*;
import jadx.api.data.impl.*;
import jadx.api.metadata.ICodeNodeRef;
import jadx.api.metadata.annotations.*;
import jadx.api.plugins.*;
import jadx.api.plugins.events.types.NodeRenamedByUser;
import jadx.api.plugins.gui.*;
import jadx.api.security.IJadxSecurity;
import jadx.core.dex.instructions.args.*;
import jadx.core.dex.nodes.*;
import jadx.core.utils.android.AndroidManifestParser;
import jadx.core.utils.android.AppAttribute;
import jadx.core.utils.android.ApplicationParams;
import jadx.core.utils.exceptions.JadxRuntimeException;
import jadx.core.xmlgen.ResContainer;
import jadx.gui.JadxWrapper;
import jadx.gui.ui.MainWindow;
import jadx.gui.settings.JadxProject;
import jadx.gui.utils.pkgs.JRenamePackage;

import io.javalin.Javalin;
import io.javalin.http.Context;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.w3c.dom.Document;
import org.w3c.dom.Element;

import javax.swing.*;
import java.awt.*;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;
import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.prefs.Preferences;
import java.util.stream.Collectors;


public class JadxMcpPlugin implements JadxPlugin {

	public static final String PLUGIN_ID = "mcp-plugin";

	private final jadx.plugins.mcp.McpOptions options = new McpOptions();

	private static final Logger logger = LoggerFactory.getLogger(JadxMcpPlugin.class);

	private JadxGuiContext guiContext;
	private MainWindow mainWindow;
	private Javalin app;

	private final AtomicBoolean shouldStop = new AtomicBoolean(false);


	private ScheduledExecutorService scheduler;


	private volatile boolean serverStarted = false;


	private static final int MAX_STARTUP_ATTEMPTS = 30;


	private static final int CHECK_INTERVAL_SECONDS = 1;


	private static final String PREF_KEY_PORT = "jadx_ai_mcp_port";
	private static final String PREF_KEY_THRESHOLD = "jadx_auto_page_threshold";
	private static final String PREF_KEY_PAGESIZE = "jadx_auto_page_pagesize";


	private static final int DEFAULT_PORT = 8656;


	private int currentPort = DEFAULT_PORT;
	private int currentthreshold = 500;
	private int currentPageSize = 100;


	private Preferences prefs;

	private PaginationHelper paginationHelper;

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


			logger.info("Jadx mcp plugin load");

			try {

				this.mainWindow = (MainWindow) context.getGuiContext().getMainFrame();
				if (this.mainWindow == null) {
					logger.error("JADX-AI-MCP插件：主窗口为null，JADX AI MCP将无法启动。");
					return;
				}

				prefs = Preferences.userNodeForPackage(JadxMcpPlugin.class);
				currentPort = prefs.getInt(PREF_KEY_PORT, DEFAULT_PORT);

				currentthreshold = prefs.getInt(PREF_KEY_THRESHOLD, 500);
				currentPageSize = prefs.getInt(PREF_KEY_PAGESIZE, 100);

				paginationHelper = new PaginationHelper(currentthreshold, currentPageSize);

				addMenuItems();

				logger.info("JADX-AI-MCP插件：正在初始化并等待JADX完全加载...");

				scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
					Thread t = new Thread(r, "JADX-AI-MCP-Startup");
					t.setDaemon(true); // 设置为守护线程，不阻止JADX退出
					return t;
				});

				startDelayedInitialization();

			} catch (Exception e) {
				logger.error("JADX-AI-MCP插件：初始化错误：" + e.getMessage(), e);
			}

		}
	}


	private void startDelayedInitialization() {

		scheduler.scheduleAtFixedRate(() -> {
			try {

				if (serverStarted) {
					scheduler.shutdown();
					return;
				}

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


	private boolean isJadxFullyLoaded() {
		try {
			if (mainWindow == null) {
				return false;
			}

			JadxWrapper wrapper = mainWindow.getWrapper();
			if (wrapper == null) {
				logger.debug("JADX-AI-MCP插件：JadxWrapper为null，尚未就绪");
				return false;
			}

			List<JavaClass> classes = wrapper.getIncludedClassesWithInners();
			if (classes == null) {
				logger.debug("JADX-AI-MCP插件：类列表为null，尚未就绪");
				return false;
			}

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


	public void shutdown() {
		try {

			if (scheduler != null && !scheduler.isShutdown()) {
				scheduler.shutdown();

				if (!scheduler.awaitTermination(5, TimeUnit.SECONDS)) {
					scheduler.shutdownNow();
				}
			}

			if (app != null) {
				app.stop();
				logger.info("JADX-AI-MCP插件：HTTP服务器已停止");
			}
		} catch (Exception e) {
			logger.error("JADX-AI-MCP插件：关闭期间出错：" + e.getMessage(), e);
		}
	}


	public void start() {
		try {

			logger.info("创建并启动Javalin HTTP服务器");
			app = Javalin.create().start(currentPort);

			logger.info("注册所有API路由");
			registerApiRoutes();

			logger.info("启动成功日志信息");
			printStartupBanner();
			logger.info("start启动完成");

		} catch (Exception e) {
			logger.error("JADX-AI-MCP插件错误：无法启动HTTP服务器。异常：" + e.getMessage());
		}
	}


	private void registerApiRoutes() {

		app.get("/get-current-class", this::handleCurrentClass);
		app.get("/get-all-classes", this::handleAllClasses);
		app.get("/get-selected-text", this::handleSelectedText);
		app.get("/get-class-source", this::handleClassSource);
		app.get("/get-smali-of-class", this::handleSmaliOfClass);

		app.get("/get-class-info", this::handleClassInfo);

		app.get("/get-method-source", this::handleMethodSource);
		app.get("/get-method-info", this::handleMethodInfo);

		app.get("/search-method", this::handleSearchMethod);

		app.get("/get-methods", this::handleMethodsOfClass);
		app.get("/get-fields", this::handleFieldsOfClass);
		app.get("/get-method-parameters", this::handleGetParameters);

		app.get("/get-manifest", this::handleManifest);
		app.get("/get-main-activity", this::handleMainActivity);
		app.get("/get-main-application-classes-code", this::handleMainApplicationClassesCode);
		app.get("/get-main-application-classes-names", this::handleMainApplicationClassesNames);

		app.get("/get-strings", this::handleStrings);
		app.get("/get-list-all-resource-files-names", this::handleListAllResourceFilesNames);
		app.get("/get-resource-file", this::handleGetResourceFile);

		app.get("/get-method-instructions", this::handleGetMethodInstructions);

		//todo 获取方法内部block、var等的nodeRef，用于对其进行重命名或注释注释
//		app.get("/get-method-code-refs-by-line", this::handleGetMethodCodeRefsByLine);

		//禁用，容易出问题，即使人为修改也容易出错
//		app.post("/rename-class", this::handleRenameClass);
		app.post("/rename-class", this::handleRenameClass);
		app.post("/rename-method", this::handleRenameMethod);
		app.post("/rename-field", this::handleRenameField);
		app.post("/rename-method-parameter", this::handleRenameMethodParameter);

		app.post("/add-class-comment", this::handleAddClassComment);
		app.post("/add-method-comment", this::handleAddMethodComment);
		app.post("/add-field-comment", this::handleAddFieldComment);




		app.get("/health", this::handleHealth);
	}


	private void printStartupBanner() {
		logger.info(
				"// -------------------- JADX AI MCP PLUGIN -------------------- //\n"
						+ " - 由 Jafar Pathan (https://github.com/zinja-coder) 开发\n"
						+ " - 报告问题: https://github.com/zinja-coder/jadx-ai-mcp\n\n");
		logger.info("JADX AI MCP插件HTTP服务器已启动，地址：http://127.0.0.1:" + currentPort + "/");
	}


	private void addMenuItems() {
		SwingUtilities.invokeLater(() -> {
			try {

				JMenuBar menuBar = mainWindow.getJMenuBar();
				if (menuBar == null) {
					logger.warn("JADX-AI-MCP插件：菜单栏未找到，无法添加菜单项");
					return;
				}

				JMenu pluginsMenu = findOrCreatePluginsMenu(menuBar);

				JMenu jadxAIMcpMenu = new JMenu("JADX AI MCP Server");

				JMenuItem configurePortItem = new JMenuItem("Configure Port...");
				configurePortItem.addActionListener(new ActionListener() {
					@Override
					public void actionPerformed(ActionEvent e) {
						showPortConfigDialog();
					}
				});                // 添加配置端口菜单项
				JMenuItem configureThresholdItem = new JMenuItem("Configure Threashold...");
				configureThresholdItem.addActionListener(new ActionListener() {
					@Override
					public void actionPerformed(ActionEvent e) {
						showThresholdConfigDialog();
					}
				});                // 添加配置端口菜单项
				JMenuItem configurePageSizeItem = new JMenuItem("Configure Page size...");
				configurePageSizeItem.addActionListener(new ActionListener() {
					@Override
					public void actionPerformed(ActionEvent e) {
						showPageSizeConfigDialog();
					}
				});

				JMenuItem restartServerItem = new JMenuItem("Restart Server");
				restartServerItem.addActionListener(new ActionListener() {
					@Override
					public void actionPerformed(ActionEvent e) {
						restartServer();
					}
				});

				JMenuItem setDefaultPortItem = new JMenuItem("Default Port");
				setDefaultPortItem.addActionListener(new ActionListener() {
					@Override
					public void actionPerformed(ActionEvent e) {
						setToDefautlPort();
					}
				});

				JMenuItem serverStatusItem = new JMenuItem("Server Status");
				serverStatusItem.addActionListener(new ActionListener() {
					@Override
					public void actionPerformed(ActionEvent e) {
						showServerStatus();
					}
				});

				jadxAIMcpMenu.add(configurePortItem);
				jadxAIMcpMenu.add(configureThresholdItem);
				jadxAIMcpMenu.add(configurePageSizeItem);
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


	private JMenu findOrCreatePluginsMenu(JMenuBar menuBar) {

		for (int i = 0; i < menuBar.getMenuCount(); i++) {
			JMenu menu = menuBar.getMenu(i);
			if (menu != null && ("Plugins".equals(menu.getText()) || "Plugin".equals(menu.getText()))) {
				return menu;
			}
		}

		JMenu pluginsMenu = new JMenu("Plugins");

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


	private void setToDefautlPort() {

		currentPort = 8650;

		prefs.putInt(PREF_KEY_PORT, currentPort);

		JOptionPane.showMessageDialog(
				mainWindow,
				"Port updated to " + currentPort + ". Server will restart automatically.",
				"Port Updated",
				JOptionPane.INFORMATION_MESSAGE);

		restartServer();
	}


	private void showPortConfigDialog() {

		JPanel panel = new JPanel(new GridBagLayout());
		GridBagConstraints gbc = new GridBagConstraints();

		gbc.gridx = 0;
		gbc.gridy = 0;
		gbc.anchor = GridBagConstraints.WEST;
		gbc.insets = new Insets(5, 5, 5, 5);
		panel.add(new JLabel("Server Port:"), gbc);

		JTextField portField = new JTextField(String.valueOf(currentPort), 10);
		gbc.gridx = 1;
		panel.add(portField, gbc);

		gbc.gridx = 0;
		gbc.gridy = 1;
		gbc.gridwidth = 2;  // 横跨两列
		gbc.fill = GridBagConstraints.HORIZONTAL;
		panel.add(new JLabel("<html><i>Valid range: 1024-65535</i></html>"), gbc);

		gbc.gridy = 2;
		panel.add(new JLabel("<html><i>Current port: " + currentPort + "</i></html>"), gbc);

		int result = JOptionPane.showConfirmDialog(
				mainWindow,
				panel,
				"Configure AI MCP Server Port",
				JOptionPane.OK_CANCEL_OPTION,
				JOptionPane.PLAIN_MESSAGE);

		if (result == JOptionPane.OK_OPTION) {
			try {

				int newPort = Integer.parseInt(portField.getText().trim());

				if (newPort < 1024 || newPort > 65535) {

					JOptionPane.showMessageDialog(
							mainWindow,
							"Port must be between 1024 and 65535",
							"Invalid Port",
							JOptionPane.ERROR_MESSAGE);
					return;
				}

				if (newPort != currentPort) {

					currentPort = newPort;

					prefs.putInt(PREF_KEY_PORT, currentPort);

					JOptionPane.showMessageDialog(
							mainWindow,
							"Port updated to " + currentPort + ". Server will restart automatically.",
							"Port Updated",
							JOptionPane.INFORMATION_MESSAGE);

					restartServer();
				}

			} catch (NumberFormatException e) {

				JOptionPane.showMessageDialog(
						mainWindow,
						"Please enter a valid port number",
						"Invalid Port",
						JOptionPane.ERROR_MESSAGE);
			}
		}
	}

	private void showThresholdConfigDialog() {

		JPanel panel = new JPanel(new GridBagLayout());
		GridBagConstraints gbc = new GridBagConstraints();

		gbc.gridx = 0;
		gbc.gridy = 0;
		gbc.anchor = GridBagConstraints.WEST;
		gbc.insets = new Insets(5, 5, 5, 5);
		panel.add(new JLabel("threshold:"), gbc);

		JTextField field = new JTextField(String.valueOf(currentthreshold), 10);
		gbc.gridx = 1;
		panel.add(field, gbc);

		gbc.gridx = 0;
		gbc.gridy = 1;
		gbc.gridwidth = 2;  // 横跨两列
		gbc.fill = GridBagConstraints.HORIZONTAL;
		panel.add(new JLabel("<html><i>Valid range: 0-10000000(set 0 to disable auto-page)</i></html>"), gbc);

		gbc.gridy = 2;
		panel.add(new JLabel("<html><i>Current threshold: " + currentthreshold + "</i></html>"), gbc);

		int result = JOptionPane.showConfirmDialog(
				mainWindow,
				panel,
				"Configure AI MCP Server auto-page threshold",
				JOptionPane.OK_CANCEL_OPTION,
				JOptionPane.PLAIN_MESSAGE);

		if (result == JOptionPane.OK_OPTION) {
			try {

				int newValue = Integer.parseInt(field.getText().trim());

				if (newValue < 0 || newValue > 10000000) {

					JOptionPane.showMessageDialog(
							mainWindow,
							"value must be between 0 and 10000000",
							"Invalid value",
							JOptionPane.ERROR_MESSAGE);
					return;
				}

				if (newValue != currentthreshold) {

					currentthreshold = newValue;

					prefs.putInt(PREF_KEY_THRESHOLD, currentthreshold);

					JOptionPane.showMessageDialog(
							mainWindow,
							"threshold updated to " + currentthreshold + ". Server will restart automatically.",
							"threshold Updated",
							JOptionPane.INFORMATION_MESSAGE);

					restartServer();
				}

			} catch (NumberFormatException e) {

				JOptionPane.showMessageDialog(
						mainWindow,
						"Please enter a valid threshold number",
						"Invalid threshold",
						JOptionPane.ERROR_MESSAGE);
			}
		}
	}

	private void showPageSizeConfigDialog() {

		JPanel panel = new JPanel(new GridBagLayout());
		GridBagConstraints gbc = new GridBagConstraints();

		gbc.gridx = 0;
		gbc.gridy = 0;
		gbc.anchor = GridBagConstraints.WEST;
		gbc.insets = new Insets(5, 5, 5, 5);
		panel.add(new JLabel("page size:"), gbc);

		JTextField field = new JTextField(String.valueOf(currentthreshold), 10);
		gbc.gridx = 1;
		panel.add(field, gbc);

		gbc.gridx = 0;
		gbc.gridy = 1;
		gbc.gridwidth = 2;  // 横跨两列
		gbc.fill = GridBagConstraints.HORIZONTAL;
		panel.add(new JLabel("<html><i>Valid range: 0-10000000(set 0 to disable auto-page)</i></html>"), gbc);

		gbc.gridy = 2;
		panel.add(new JLabel("<html><i>Current page size: " + currentthreshold + "</i></html>"), gbc);

		int result = JOptionPane.showConfirmDialog(
				mainWindow,
				panel,
				"Configure AI MCP Server auto-page page size",
				JOptionPane.OK_CANCEL_OPTION,
				JOptionPane.PLAIN_MESSAGE);

		if (result == JOptionPane.OK_OPTION) {
			try {

				int newValue = Integer.parseInt(field.getText().trim());

				if (newValue < 0 || newValue > 10000000) {

					JOptionPane.showMessageDialog(
							mainWindow,
							"value must be between 0 and 10000000",
							"Invalid value",
							JOptionPane.ERROR_MESSAGE);
					return;
				}

				if (newValue != currentPageSize) {

					currentPageSize = newValue;

					prefs.putInt(PREF_KEY_PAGESIZE, currentPageSize);

					JOptionPane.showMessageDialog(
							mainWindow,
							"page size updated to " + currentPageSize + ". Server will restart automatically.",
							"page size Updated",
							JOptionPane.INFORMATION_MESSAGE);

					restartServer();
				}

			} catch (NumberFormatException e) {

				JOptionPane.showMessageDialog(
						mainWindow,
						"Please enter a valid page size number",
						"Invalid page size",
						JOptionPane.ERROR_MESSAGE);
			}
		}
	}


	private void restartServer() {

		new Thread(() -> {
			try {

				logger.info("JADX-AI-MCP Plugin: Restarting server on port " + currentPort);

				if (app != null) {
					app.stop();            // 停止Javalin HTTP服务器
					app = null;            // 清除服务器引用
					serverStarted = false;  // 重置服务器启动状态标志
				}

				Thread.sleep(1000);

				start();

				SwingUtilities.invokeLater(() -> {
					JOptionPane.showMessageDialog(
							mainWindow,
							"AI MCP Server restarted successfully on port " + currentPort,
							"Server Restarted",
							JOptionPane.INFORMATION_MESSAGE);
				});

			} catch (Exception e) {

				logger.error("JADX-AI-MCP Plugin: Error restarting server: " + e.getMessage(), e);

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


	private void showServerStatus() {

		String status = serverStarted && app != null ? "Running" : "Stopped";

		String url = serverStarted ? "http://127.0.0.1:" + currentPort + "/" : "N/A";

		JPanel panel = new JPanel(new GridBagLayout());
		GridBagConstraints gbc = new GridBagConstraints();

		gbc.anchor = GridBagConstraints.WEST;
		gbc.insets = new Insets(5, 5, 5, 5);

		gbc.gridx = 0;  // 第一列
		gbc.gridy = 0;  // 第一行
		panel.add(new JLabel("Status:"), gbc);  // 状态标签
		gbc.gridx = 1;  // 第二列
		panel.add(new JLabel(status), gbc);      // 状态值

		gbc.gridx = 0;  // 第一列
		gbc.gridy = 1;  // 第二行
		panel.add(new JLabel("Port:"), gbc);     // 端口标签
		gbc.gridx = 1;  // 第二列
		panel.add(new JLabel(String.valueOf(currentPort)), gbc);  // 端口值

		gbc.gridx = 0;  // 第一列
		gbc.gridy = 2;  // 第三行
		panel.add(new JLabel("URL:"), gbc);      // URL标签
		gbc.gridx = 1;  // 第二列
		panel.add(new JLabel(url), gbc);          // URL值

		JOptionPane.showMessageDialog(
				mainWindow, // 父窗口为JADX主窗口
				panel, // 自定义面板内容
				"AI MCP Server Status", // 对话框标题
				JOptionPane.INFORMATION_MESSAGE); // 对话框类型（信息提示）
	}


	public void handleHealth(Context ctx) {
		try {

			String status = serverStarted && app != null ? "Running" : "Stopped";

			String url = serverStarted ? "http://127.0.0.1:" + currentPort + "/" : "N/A";

			Map<String, Object> result = new HashMap<>();
			result.put("status", status);  // 服务器状态
			result.put("url", url);        // 访问地址

			logger.info("JADX AI MCP Plugin: GOT HEALTH PING");

			ctx.json(result);

		} catch (Exception e) {

			logger.error("JADX AI MCP Error: " + e.getMessage(), e);

			ctx.status(500)
					.json(Map.of("error",
							"Internal Error while trying to handle health ping request: " + e.getMessage()));
		}
	}


	public void handleCurrentClass(Context ctx) {
		try {

			String className = getSelectedTabTitle();

			String code = extractTextFromCurrentTab();

			Map<String, Object> result = new HashMap<>();
			result.put("name", className != null ? className.replace(".java", "") : "unknown");  // 清理类名
			result.put("type", "code/java");  // 设置数据类型
			result.put("content", code != null ? code : "");  // 设置源代码内容

			ctx.json(result);

		} catch (Exception e) {

			logger.error("JADX AI MCP Error: " + e.getMessage(), e);

			ctx.status(500)
					.json(Map.of("error", "Internal Error while trying to fetch current class: " + e.getMessage()));
		}
	}


	private void handleAllClasses(Context ctx) {
		try {
			String pageIndex = getRenameParameter(ctx, "index", "pageIndex", "page_index");
			String pageSize = getRenameParameter(ctx, "pageSize", "page_size", "limit");

			JadxWrapper wrapper = mainWindow.getWrapper();
			List<JavaClass> classes = wrapper.getIncludedClassesWithInners();

			if (pageIndex != null && !pageIndex.isEmpty()) {
				int index = Integer.parseInt(pageIndex);
				int size = (pageSize != null && !pageSize.isEmpty()) ? Integer.parseInt(pageSize) : currentPageSize;

				Map<String, Object> result = paginationHelper.paginateList(
						classes,
						index,
						size,
						cls -> cls.getFullName()
				);
				ctx.json(result);

			} else {

				Map<String, Object> result = paginationHelper.handlePagination(
						classes,
						"class-list",
						"classes",
						cls -> cls.getFullName()
				);
				ctx.json(result);
			}

		} catch (NumberFormatException e) {
			logger.error("JADX AI MCP Pagination Parameter Error: " + e.getMessage());
			ctx.status(400).json(Map.of("error", "Invalid pagination parameter: " + e.getMessage()));
		} catch (Exception e) {
			logger.error("JADX AI MCP Error: " + e.getMessage(), e);
			ctx.status(500).json(Map.of("error", "Failed to load class list: " + e.getMessage()));
		}
	}


	private void handleSelectedText(Context ctx) {
		try {
			String strIndex = getRenameParameter(ctx, "index", "pageIndex", "page_index");
			String strSize = getRenameParameter(ctx, "pageSize", "page_size", "limit");

			JTextArea textArea = findTextArea(mainWindow.getTabbedPane().getSelectedComponent());

			String selectedText = textArea != null ? textArea.getSelectedText() : "";

			Map<String, Object> result;

			if (strIndex != null && !strIndex.isEmpty()) {
				int index = Integer.parseInt(strIndex);
				int size = (strSize != null && !strSize.isEmpty()) ? Integer.parseInt(strSize) : currentPageSize;

				result = paginationHelper.paginateLongString(
						selectedText,
						index,
						size
				);
			} else {

				result = paginationHelper.autoPaginateLongString(selectedText);
			}

			ctx.json(result);

		} catch (NumberFormatException e) {
			logger.error("JADX AI MCP Pagination Parameter Error: " + e.getMessage());
			ctx.status(400).json(Map.of("error", "Invalid pagination parameter: " + e.getMessage()));
		} catch (Exception e) {

			logger.error("JADX AI MCP Error: " + e.getMessage(), e);

			ctx.status(500)
					.json(Map.of("error", "Internal error while trying to fetch selected text: " + e.getMessage()));
		}
	}


	private void handleMethodSource(Context ctx) {
		try {
			String strIndex = getRenameParameter(ctx, "index", "pageIndex", "page_index");
			String strSize = getRenameParameter(ctx, "pageSize", "page_size", "limit");

			JavaMethod method = findMethod(ctx);

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

			String code = method.getCodeStr();
			Map<String, Object> codePage;

			if (strIndex != null && !strIndex.isEmpty()) {
				int index = Integer.parseInt(strIndex);
				int size = (strSize != null && !strSize.isEmpty()) ? Integer.parseInt(strSize) : currentPageSize;

				codePage = paginationHelper.paginateLongString(
						code,
						index,
						size
				);
			} else {

				codePage = paginationHelper.autoPaginateLongString(code);
			}
			result.put("code", codePage);

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

			result.put("codeLength", method.getCodeStr().length());                       // 方法的完整源代码

			ctx.json(result);

		} catch (NoSuchElementException e) {
			ctx.status(404).json(Map.of("error", e.getMessage()));
		} catch (IllegalArgumentException e) {
			ctx.status(400).json(Map.of("error", e.getMessage()));
		} catch (Exception e) {
			ctx.status(500).json(Map.of("error", "Internal server error: " + e.getMessage()));
		}
	}


	private void handleClassSource(Context ctx) {

		try {
			String strIndex = getRenameParameter(ctx, "index", "pageIndex", "page_index");
			String strSize = getRenameParameter(ctx, "pageSize", "page_size", "limit");

			JavaClass cls = findClass(ctx);
			String code = cls.getCode();

			Map<String, Object> result;

			if (strIndex != null && !strIndex.isEmpty()) {
				int index = Integer.parseInt(strIndex);
				int size = (strSize != null && !strSize.isEmpty()) ? Integer.parseInt(strSize) : currentPageSize;

				result = paginationHelper.paginateLongString(
						code,
						index,
						size
				);
			} else {

				result = paginationHelper.autoPaginateLongString(code);
			}

			ctx.json(result);


		} catch (NoSuchElementException e) {
			ctx.status(404).json(Map.of("error", e.getMessage()));
		} catch (NumberFormatException e) {
			logger.error("JADX AI MCP Pagination Parameter Error: " + e.getMessage());
			ctx.status(400).json(Map.of("error", "Invalid pagination parameter: " + e.getMessage()));
		} catch (IllegalArgumentException e) {
			ctx.status(400).json(Map.of("error", e.getMessage()));
		} catch (Exception e) {
			ctx.status(500).json(Map.of("error", "Internal server error: " + e.getMessage()));
		}

	}


	private void handleSearchMethod(Context ctx) {
		try {
			String methodName = getParameter(ctx, "method", "method_name", "methodName");
			String originalName = getParameter(ctx, "original_name", "originalName", "method_original_name");
			String methodSignature = getParameter(ctx, "method_signature", "signature");
			String className = getParameter(ctx, "name", "class_name", "class");
			String rawName = getParameter(ctx, "rawName", "raw_name", "class_raw_name", "classRawName");

			boolean hasClassName = className != null && !className.isEmpty();
			boolean hasClassRawName = rawName != null && !rawName.isEmpty();
			boolean hasMethodName = methodName != null && !methodName.isEmpty();
			boolean hasOriginalName = originalName != null && !originalName.isEmpty();


			if (!hasMethodName && !hasOriginalName) {
				throw new IllegalArgumentException("Missing 'method_name' or 'original_name' parameter");
			}

			JadxWrapper wrapper = mainWindow.getWrapper();
			if (wrapper == null) {
				logger.error("JADX AI MCP Error: JadxWrapper not initialized");
				ctx.status(500).json(Map.of("error", "JadxWrapper not initialized"));
				return;
			}
			List<JavaMethod> methodMatches = new ArrayList<>();
			if (hasClassName || hasClassRawName) {
				JavaMethod method = findMethod(ctx);


				methodMatches.add(method);
			} else {
				for (JavaClass cls : wrapper.getIncludedClassesWithInners()) {
					try {
						JavaMethod method = findMethod(cls, methodName, originalName, methodSignature);


						methodMatches.add(method);
					} catch (Exception e) {
					}

				}
			}

			Map<String, Object> result = new HashMap<>();
			result.put("class_filter", className != null ? className : "all");
			result.put("match_count", methodMatches.size());


			String pageIndex = getRenameParameter(ctx, "index", "pageIndex", "page_index");
			String pageSize = getRenameParameter(ctx, "pageSize", "page_size", "limit");

			Map<String, Object> methods;

			if (pageIndex != null && !pageIndex.isEmpty()) {
				int index = Integer.parseInt(pageIndex);
				int size = (pageSize != null && !pageSize.isEmpty()) ? Integer.parseInt(pageSize) : currentPageSize;

				methods = paginationHelper.<JavaMethod>paginateList(
						methodMatches,
						index,
						size,
						method -> {
							Map<String, Object> methodInfo = new HashMap<>();
							methodInfo.put("method", method.getName());
							methodInfo.put("fullName", method.getFullName());
							methodInfo.put("signature", method.getMethodNode().getMethodInfo().getShortId());
							return methodInfo;
						}
				);

			} else {

				methods = paginationHelper.<JavaMethod>handlePagination(
						methodMatches,
						"method-list",
						"methods",
						method -> {
							Map<String, Object> methodInfo = new HashMap<>();
							methodInfo.put("method", method.getName());
							methodInfo.put("fullName", method.getFullName());
							methodInfo.put("signature", method.getMethodNode().getMethodInfo().getShortId());
							return methodInfo;
						}
				);
			}
			result.put("methods", methods);


			ctx.status(200).json(result);
		} catch (Exception e) {
			logger.error("JADX AI MCP Error: " + e.getMessage(), e);
			ctx.status(500).json(Map.of("error", "Internal error during method search: " + e.getMessage()));
		}

	}


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


	private void handleMethodsOfClass(Context ctx) {

		try {
			JavaClass cls = findClass(ctx);

			List<Map<String, Object>> methodInfos = new ArrayList<>();
			for (JavaMethod method : cls.getMethods()) {
				Map<String, Object> methodInfo = new HashMap<>();

				jadx.core.dex.info.MethodInfo methodInfoDetail = method.getMethodNode().getMethodInfo();
				methodInfo.put("signature", methodInfoDetail.getShortId());
				methodInfo.put("originalName", methodInfoDetail.getName());

				methodInfo.put("name", method.getName());
				methodInfo.put("isPublic", method.getAccessFlags().isPublic());
				methodInfo.put("isStatic", method.getAccessFlags().isStatic());
				methodInfo.put("isConstructor", method.isConstructor());

				methodInfos.add(methodInfo);
			}


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


	private void handleFieldsOfClass(Context ctx) {

		try {
			JavaClass cls = findClass(ctx);

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


	private void handleRenameClass(Context ctx) {

		String newName = getParameter(ctx, "newName", "new_name");

		if (newName == null) {
			logger.error("JADX AI MCP Error: Missing  'newName' parameter.'newName' can be empty to reset,but not null");
			ctx.status(400).json(Map.of("error", "Missing 'newName' parameter.'newName' can be empty to reset,but not null"));
			return;
		}

		try {
			JavaClass cls = findClass(ctx);

			ICodeNodeRef nodeRef = cls.getCodeNodeRef();

			NodeRenamedByUser event = new NodeRenamedByUser(nodeRef, cls.getName(), newName);

			event.setRenameNode(cls.getClassNode());

			event.setResetName(newName.isEmpty());

			mainWindow.events().send(event);

			logger.info("rename Class " + cls.getName() + " to " + newName);

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


	private void handleRenameMethod(Context ctx) {
		String newName = getParameter(ctx, "newName", "new_name");

		if (newName == null) {
			logger.error("JADX AI MCP Error: Missing 'method' or 'original_name' or 'newName' parameter.'newName' can be empty,but not null");
			ctx.status(400).json(Map.of("error", "Missing 'method' or 'original_name' or 'newName' parameter.'newName' can be empty,but not null"));
			return;
		}

		try {

			JavaMethod method = findMethod(ctx);

			ICodeNodeRef nodeRef = method.getCodeNodeRef();

			NodeRenamedByUser event = new NodeRenamedByUser(nodeRef, method.getName(), newName);

			event.setRenameNode(method.getMethodNode());

			event.setResetName(newName.isEmpty());

			mainWindow.events().send(event);

			logger.info("rename method " + method.getName() + " to " + newName);

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

			ICodeNodeRef nodeRef = field.getCodeNodeRef();
			NodeRenamedByUser event = new NodeRenamedByUser(nodeRef, field.getName(), newFieldName);
			event.setRenameNode(field.getFieldNode());
			event.setResetName(newFieldName.isEmpty());

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


	private void handleSmaliOfClass(Context ctx) {

		try {
			String strIndex = getRenameParameter(ctx, "index", "pageIndex", "page_index");
			String strSize = getRenameParameter(ctx, "pageSize", "page_size", "limit");

			JavaClass cls = findClass(ctx);
			String code = cls.getSmali();

			Map<String, Object> result;

			if (strIndex != null && !strIndex.isEmpty()) {
				int index = Integer.parseInt(strIndex);
				int size = (strSize != null && !strSize.isEmpty()) ? Integer.parseInt(strSize) : currentPageSize;

				result = paginationHelper.paginateLongString(
						code,
						index,
						size
				);
			} else {

				result = paginationHelper.autoPaginateLongString(code);
			}

			ctx.json(result);


		} catch (NoSuchElementException e) {
			ctx.status(404).json(Map.of("error", e.getMessage()));
		} catch (NumberFormatException e) {
			logger.error("JADX AI MCP Pagination Parameter Error: " + e.getMessage());
			ctx.status(400).json(Map.of("error", "Invalid pagination parameter: " + e.getMessage()));
		} catch (IllegalArgumentException e) {
			ctx.status(400).json(Map.of("error", e.getMessage()));
		} catch (Exception e) {
			ctx.status(500).json(Map.of("error", "Internal server error: " + e.getMessage()));
		}

	}


	private void handleManifest(Context ctx) {
		try {
			String strIndex = getRenameParameter(ctx, "index", "pageIndex", "page_index");
			String strSize = getRenameParameter(ctx, "pageSize", "page_size", "limit");

			JadxWrapper wrapper = mainWindow.getWrapper();
			List<ResourceFile> resources = wrapper.getResources();
			ResourceFile manifest = AndroidManifestParser.getAndroidManifest(resources);

			if (manifest == null) {
				logger.error("JADX AI MCP Error: AndroidManifest.xml not found.");
				ctx.status(404).json(Map.of("error", "AndroidManifest.xml not found."));
				return;
			}

			ResContainer container = manifest.loadContent();
			String manifestContent = container.getText().getCodeStr();

			Map<String, Object> result;
			if (strIndex != null && !strIndex.isEmpty()) {
				int index = Integer.parseInt(strIndex);
				int size = (strSize != null && !strSize.isEmpty()) ? Integer.parseInt(strSize) : currentPageSize;

				result = paginationHelper.paginateLongString(
						manifestContent,
						index,
						size
				);
			} else {
				result = paginationHelper.autoPaginateLongString(manifestContent);
			}

			result.put("name", manifest.getOriginalName());
			result.put("type", "manifest/xml");

			ctx.json(result);
		} catch (Exception e) {
			logger.error("JADX AI MCP Error: " + e.getMessage(), e);
			ctx.status(500).json(Map.of("error", "Internal error retrieving AndroidManifest.xml: " + e.getMessage()));
		}


	}


	private void handleMainApplicationClassesNames(Context ctx) {
		try {
			JadxWrapper wrapper = mainWindow.getWrapper();
			List<ResourceFile> resources = wrapper.getResources();

			ResourceFile manifestRes = AndroidManifestParser.getAndroidManifest(resources);
			if (manifestRes == null) {
				logger.error("JADX AI MCP Error: AndroidManifest.xml not found.");
				ctx.status(404).json(Map.of("error", "AndroidManifest.xml not found."));
				return;
			}

			String manifestXml = manifestRes.loadContent().getText().getCodeStr();
			Document manifestDoc = parseManifestXml(manifestXml, wrapper.getArgs().getSecurity());

			Element manifestElement = (Element) manifestDoc.getElementsByTagName("manifest").item(0);
			String packageName = manifestElement.getAttribute("package");

			if (packageName.isEmpty()) {
				logger.error("JADX AI MCP Error: Package name not found in manifest");
				ctx.status(404).json(Map.of("error", "Package name not found in manifest."));
				return;
			}

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

	private void handleMainApplicationClassesCode(Context ctx) {
		try {
			String strIndex = getRenameParameter(ctx, "index", "pageIndex", "page_index");
			String strSize = getRenameParameter(ctx, "pageSize", "page_size", "limit");

			JadxWrapper wrapper = mainWindow.getWrapper();
			List<ResourceFile> resources = wrapper.getResources();

			ResourceFile manifestRes = AndroidManifestParser.getAndroidManifest(resources);
			if (manifestRes == null) {
				logger.error("JADX AI MCP Error: AndroidManifest.xml not found.");
				ctx.status(404).json(Map.of("error", "AndroidManifest.xml not found."));
				return;
			}

			String manifestXml = manifestRes.loadContent().getText().getCodeStr();
			Document manifestDoc = parseManifestXml(manifestXml, wrapper.getArgs().getSecurity());

			Element manifestElement = (Element) manifestDoc.getElementsByTagName("manifest").item(0);
			String packageName = manifestElement.getAttribute("package");

			if (packageName.isEmpty()) {
				logger.error("JADX AI MCP Error: Package name not found manifest.");
				ctx.status(404).json(Map.of("error", "Package name not found manifest."));
				return;
			}

			List<JavaClass> matchedClasses = wrapper.getDecompiler()
					.getClasses()
					.stream()
					.filter(cls -> cls.getFullName().startsWith(packageName))
					.collect(Collectors.toList());

			Map<String, Object> result;
			if (strIndex != null && !strIndex.isEmpty()) {
				int index = Integer.parseInt(strIndex);
				int size = (strSize != null && !strSize.isEmpty()) ? Integer.parseInt(strSize) : currentPageSize;

				result = paginationHelper.paginateList(
						matchedClasses,
						index,
						size,
						cls -> {
							Map<String, Object> classInfo = new HashMap<>();
							classInfo.put("name", cls.getFullName());
							classInfo.put("type", "code/java");
							classInfo.put("content", cls.getCode());
							return classInfo;
						}
				);
			} else {
				result = paginationHelper.handlePagination(
						matchedClasses,
						"application-classes",
						"classes",
						cls -> {
							Map<String, Object> classInfo = new HashMap<>();
							classInfo.put("name", cls.getFullName());
							classInfo.put("type", "code/java");
							classInfo.put("content", cls.getCode());
							return classInfo;
						}
				);
			}

			ctx.json(result);
		} catch (Exception e) {
			logger.error("JADX AI MCP Error: " + e.getMessage(), e);
			ctx.status(500).json(Map.of("error", "Internal error retrieving AndroidManifest.xml: " + e.getMessage()));
		}


	}


	private void handleMainActivity(Context ctx) {
		try {
			String strIndex = getRenameParameter(ctx, "index", "pageIndex", "page_index");
			String strSize = getRenameParameter(ctx, "pageSize", "page_size", "limit");

			JadxWrapper wrapper = mainWindow.getWrapper();
			List<ResourceFile> resources = wrapper.getResources();

			AndroidManifestParser parser = new AndroidManifestParser(
					AndroidManifestParser.getAndroidManifest(resources),
					EnumSet.of(AppAttribute.MAIN_ACTIVITY),
					wrapper.getArgs().getSecurity());

			if (!parser.isManifestFound()) {
				logger.error("JADX AI MCP Error: AndroidManifest.xml not found.");
				ctx.status(404).json(Map.of("error", "AndroidManifest.xml not found."));
				return;
			}

			ApplicationParams results = parser.parse();
			if (results.getMainActivity() == null) {
				logger.error("JADX AI MCP Error: Failed to get main activity from manifest.");
				ctx.status(404).json(Map.of("error", "Failed to get main activity from manifest."));
				return;
			}

			JavaClass mainActivityClass = results.getMainActivityJavaClass(wrapper.getDecompiler());
			if (mainActivityClass == null) {
				logger.error("JADX AI MCP Error: Failed to get activity class: " + results.getApplication());
				ctx.status(404).json(Map.of("error", "Failed to get activity class: " + results.getApplication()));
				return;
			}

			String activityCode = mainActivityClass.getCode();
			Map<String, Object> result;

			if (strIndex != null && !strIndex.isEmpty()) {
				int index = Integer.parseInt(strIndex);
				int size = (strSize != null && !strSize.isEmpty()) ? Integer.parseInt(strSize) : currentPageSize;

				result = paginationHelper.paginateLongString(
						activityCode,
						index,
						size
				);
			} else {
				result = paginationHelper.autoPaginateLongString(activityCode);
			}

			result.put("name", mainActivityClass.getFullName());
			result.put("type", "code/java");

			ctx.json(result);
		} catch (Exception e) {
			logger.error("JADX AI MCP Error: " + e.getMessage(), e);
			ctx.status(500).json(Map.of("error", "Internal error retrieving AndroidManifest.xml: " + e.getMessage()));
		}


	}


	private void handleStrings(Context ctx) {
		try {
			JadxWrapper wrapper = mainWindow.getWrapper();
			List<ResourceFile> resourceFiles = wrapper.getResources();

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

			Map<String, Object> result = paginationHelper.handlePagination(
					allStringEntries,
					"resource/strings-xml",
					"strings",
					(java.util.function.Function<Map<String, Object>, Object>) item -> {

						return item;
					}
			);

			ctx.json(result);
		} catch (Exception e) {
			logger.error("JADX AI MCP Pagination Error: {}", e.getMessage());
			ctx.status(400).json(Map.of("error", "Pagination error: " + e.getMessage()));
		}
	}


	private void handleListAllResourceFilesNames(Context ctx) {
		try {
			String strIndex = getRenameParameter(ctx, "index", "pageIndex", "page_index");
			String strSize = getRenameParameter(ctx, "pageSize", "page_size", "limit");

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

			Map<String, Object> result;
			if (strIndex != null && !strIndex.isEmpty()) {
				int index = Integer.parseInt(strIndex);
				int size = (strSize != null && !strSize.isEmpty()) ? Integer.parseInt(strSize) : currentPageSize;

				result = paginationHelper.<String>paginateList(
						resourceFileNames,
						index,
						size,
						name -> name
				);
			} else {
				result = paginationHelper.<String>handlePagination(
						resourceFileNames,
						"resource-files",
						"files",
						name -> name
				);
			}

			ctx.json(result);
		} catch (Exception e) {
			logger.error("JADX AI MCP Error: " + e.getMessage(), e);
			ctx.status(500).json(
					Map.of("error", "Internal error while retrieving list of resource files names: " + e.getMessage()));
		}


	}


	private void handleGetResourceFile(Context ctx) {
		try {
			String strIndex = getRenameParameter(ctx, "index", "pageIndex", "page_index");
			String strSize = getRenameParameter(ctx, "pageSize", "page_size", "limit");

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

			String content = (String) resFileContent.get("content");
			Map<String, Object> result;

			if (strIndex != null && !strIndex.isEmpty()) {
				int index = Integer.parseInt(strIndex);
				int size = (strSize != null && !strSize.isEmpty()) ? Integer.parseInt(strSize) : currentPageSize;

				result = paginationHelper.paginateLongString(
						content,
						index,
						size
				);
			} else {
				result = paginationHelper.autoPaginateLongString(content);
			}

			result.put("type", "resource/text");
			result.put("file_name", resFileContent.get("file"));

			ctx.json(result);
		} catch (Exception e) {
			logger.error("JADX AI MCP Error: " + e.getMessage(), e);
			ctx.status(500).json(Map.of("error", "Internal error while retrieving resource file: " + e.getMessage()));
		}
	}


	private String getSelectedTabTitle() {

		JTabbedPane tabs = mainWindow.getTabbedPane();

		int index = tabs.getSelectedIndex();

		return (index != -1) ? tabs.getTitleAt(index) : null;
	}


	private String extractTextFromCurrentTab() {

		Component selectedComponent = mainWindow.getTabbedPane().getSelectedComponent();

		JTextArea textArea = findTextArea(selectedComponent);

		return textArea != null ? textArea.getText() : null;
	}


	private JTextArea findTextArea(Component component) {

		if (component instanceof JTextArea) {
			return (JTextArea) component;
		}

		if (component instanceof Container) {

			for (Component child : ((Container) component).getComponents()) {

				JTextArea result = findTextArea(child);

				if (result != null) {
					return result;
				}
			}
		}

		return null;
	}


	private Document parseManifestXml(String xmlContent, IJadxSecurity security) {

		try (InputStream xmlStream = new ByteArrayInputStream(xmlContent.getBytes(StandardCharsets.UTF_8))) {

			Document doc = security.parseXml(xmlStream);

			doc.getDocumentElement().normalize();

			return doc;
		} catch (Exception e) {

			throw new JadxRuntimeException("Failed to parse AndroidManifest.xml", e);
		}
	}


	private void handleRenamePackage(Context ctx) {
		String packageName = ctx.queryParam("package");
		String newName = ctx.queryParam("newName");

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

			JRenamePackage renamePackage = new JRenamePackage(
					javaPackage,
					javaPackage.getRawFullName(),
					javaPackage.getFullName(),
					javaPackage.getName()
			);

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

			ICodeNodeRef nodeRef = javaPackage.getCodeNodeRef();
			NodeRenamedByUser renameEvent = new NodeRenamedByUser(nodeRef, oldName, newNodeName);
			renameEvent.setRenameNode(renamePackage);
			renameEvent.setResetName(reset);

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


	private void handleRenameMethodParameter(Context ctx) {
		try {

			JavaMethod method = findMethod(ctx);

			String paramIndexStr = getParameter(ctx, "param_index", "paramIndex");
			String newName = getRenameParameter(ctx, "newName", "new_name");

			int paramIndex = Integer.parseInt(paramIndexStr);

			MethodNode methodNode = method.getMethodNode();
			List<VarNode> argNodes = methodNode.collectArgNodes();

			if (paramIndex < 0 || paramIndex >= argNodes.size()) {
				ctx.status(400).json(Map.of("error", "Invalid parameter index. Method has "
						+ argNodes.size() + " parameters (index 0-" + (argNodes.size() - 1) + ")"));
				return;
			}

			VarNode targetParamNode = argNodes.get(paramIndex);

			String oldParameterName = targetParamNode.getName();
			if (oldParameterName == null) {
				oldParameterName = "param" + paramIndex;
			}

			NodeRenamedByUser event = new NodeRenamedByUser(
					targetParamNode, // 变量节点的引用
					oldParameterName, // 旧名称
					newName // 新名称
			);
			event.setRenameNode(targetParamNode);
			event.setResetName(newName.isEmpty());

			mainWindow.events().send(event);

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


	private void handleAddClassComment(Context ctx) {

		String comment = getParameter(ctx, "comment");
		String style = getRenameParameter(ctx, "style");

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

			JadxCodeComment codeComment = new JadxCodeComment(jadxNodeRef, null, comment, commentStyle);

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


	private void handleAddMethodComment(Context ctx) {

		String comment = getParameter(ctx, "comment");
		String style = getRenameParameter(ctx, "style");

		if (comment == null) {
			logger.error("JADX AI MCP Error: Missing  'comment',it can be empty to reset,but not null");
			ctx.status(400).json(Map.of("error", "Missing  'comment',it can be empty to reset,but not null"));
			return;
		}

		try {

			JavaMethod method = findMethod(ctx);
			JadxNodeRef jadxNodeRef = JadxNodeRef.forMth(method);
			CommentStyle commentStyle = CommentStyle.valueOf(style != null && style.isEmpty() ? style.toUpperCase() : "JAVADOC");

			JadxCodeComment codeComment = new JadxCodeComment(jadxNodeRef, null, comment, commentStyle);

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


	private void handleAddFieldComment(Context ctx) {

		String comment = getParameter(ctx, "comment");
		String style = getRenameParameter(ctx, "style");

		if (comment == null) {
			logger.error("JADX AI MCP Error: Missing  'comment',it can be empty to reset,but not null");
			ctx.status(400).json(Map.of("error", "Missing  'comment',it can be empty to reset,but not null"));
			return;
		}

		try {

			JavaField field = findField(ctx);

			JadxNodeRef jadxNodeRef = JadxNodeRef.forFld(field);
			CommentStyle commentStyle = CommentStyle.valueOf(style != null && style.isEmpty() ? style.toUpperCase() : "JAVADOC");

			JadxCodeComment codeComment = new JadxCodeComment(jadxNodeRef, null, comment, commentStyle);

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




	private boolean isValidNodeType(String nodeType) {
		return Arrays.asList("class", "method", "field", "instruction").contains(nodeType.toLowerCase());
	}


	private boolean isValidCommentStyle(String style) {
		return Arrays.asList("LINE", "BLOCK", "JAVADOC").contains(style.toUpperCase());
	}


	private JavaClass findClass(Context ctx) {

		String className = getParameter(ctx, "name", "class_name", "class");
		String rawName = getParameter(ctx, "rawName", "raw_name", "class_raw_name", "classRawName");

		return findClassByName(className, rawName);
	}


	private String getParameter(Context ctx, String... paramNames) {

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

		if ((className == null || className.isEmpty()) && (rawName == null || rawName.isEmpty())) {
			logger.error("JADX AI MCP Error: Missing class name parameters");
			throw new IllegalArgumentException("Missing 'className' or 'rawName' parameter.");
		}

		if (rawName != null && !rawName.isEmpty()) {
			for (JavaClass cls : wrapper.getIncludedClassesWithInners()) {
				if (cls.getClassNode().getRawName().equals(rawName)) {
					return cls;
				}
			}
		}

		if (className != null && !className.isEmpty()) {
			String searchName = className.replace('$', '.');
			for (JavaClass cls : wrapper.getIncludedClassesWithInners()) {
				if (cls.getFullName().equals(searchName)) {
					return cls;
				}
			}
		}

		String searchName = className != null ? className : rawName;
		logger.error("JADX AI MCP Error: Class not found: " + searchName);
		throw new NoSuchElementException("Class not found: " + searchName);
	}

	private JavaField findField(Context ctx) {

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

				if (hasFieldRawName && field.getRawName().equals(fieldRawName)) {
					return field;
				}

				if (hasFieldName && field.getName().equals(fieldName)) {
					return field;
				}
			}
		} catch (Exception e) {
			logger.error("Error finding field: " + e.getMessage(), e);
		}
		throw new NoSuchElementException("Field not found: " + (hasFieldRawName ? fieldRawName : fieldName));

	}


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

				if (hasOriginalName && methodInfo.getName().equals(originalName)) {
					nameMatch = true;
					searchTarget = originalName;
				} else if (hasMethodName && method.getName().equals(methodName)) {
					nameMatch = true;
					searchTarget = methodName;
				}

				if (nameMatch) {
					if (hasSignature) {

						if (methodInfo.getShortId().equals(methodSignature) ||
								methodInfo.getShortId().contains(methodSignature)) {
							return method;
						}

						continue;
					}

					return method;
				}
			}
		} catch (Exception e) {
			logger.error("Error searching methods in class {}: {}", cls.getFullName(), e.getMessage());
			throw new RuntimeException("Failed to search methods: " + e.getMessage(), e);
		}

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


	private int estimateInstructionSize(String line) {


		return line.length() + 2; // 简单估算
	}

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

	private void addCommentToProject(ICodeComment comment) {
		try {
			JadxProject project = mainWindow.getProject();
			JadxCodeData codeData = project.getCodeData();
			if (codeData == null) {
				codeData = new JadxCodeData();
			}

			List<ICodeComment> commentList = new ArrayList<>(codeData.getComments());

			commentList.removeIf(c -> Objects.equals(c.getNodeRef(), comment.getNodeRef())
					&& Objects.equals(c.getCodeRef(), comment.getCodeRef()));

			commentList.add(comment);
			Collections.sort(commentList);

			codeData.setComments(commentList);
			project.setCodeData(codeData);

			mainWindow.getWrapper().reloadCodeData();


		} catch (Exception e) {
			logger.error("Failed to add comment to project", e);
			throw new RuntimeException("Failed to add comment to project: " + e.getMessage());
		}


	}


	private void handleGetMethodInstructions(Context ctx) {
		try {
			String strIndex = getRenameParameter(ctx, "index", "pageIndex", "page_index");
			String strSize = getRenameParameter(ctx, "pageSize", "page_size", "limit");

			JavaMethod method = findMethod(ctx);
			MethodNode methodNode = method.getMethodNode();

			if (!methodNode.isLoaded()) {
				logger.warn(methodNode.getName() + " 需要重新加载");
				methodNode.reload();
			}

			List<String> insS = new ArrayList<>();
			if (methodNode.getInstructions() != null) {
				for (InsnNode ins : methodNode.getInstructions()) {
					if (ins == null) continue;
					insS.add(ins.toString());
				}
			}

			Map<String, Object> baseInfo = new HashMap<>();
			baseInfo.put("method", method.getName());
			baseInfo.put("signature", method.getMethodNode().getMethodInfo().getShortId());
			baseInfo.put("instructionCount", methodNode.getInstructions() != null ? methodNode.getInstructions().length : 0);

			Map<String, Object> result;
			if (strIndex != null && !strIndex.isEmpty()) {
				int index = Integer.parseInt(strIndex);
				int size = (strSize != null && !strSize.isEmpty()) ? Integer.parseInt(strSize) : currentPageSize;

				result = paginationHelper.<String>paginateList(
						insS,
						index,
						size,
						str -> str
				);
			} else {
				result = paginationHelper.<String>handlePagination(
						insS,
						"method-instructions",
						"instructions",
						str -> str

				);
			}

			result.putAll(baseInfo);
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

	// todo 未完成
	private void handleGetMethodCodeRefsByLine(Context ctx) {

	}

}
