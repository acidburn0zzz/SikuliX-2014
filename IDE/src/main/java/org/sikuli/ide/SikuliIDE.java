/*
 * Copyright 2010-2013, Sikuli.org
 * Released under the MIT License.
 *
 * modified RaiMan 2013
 */
package org.sikuli.ide;

//TODO
// dirty pane handling: on individual tab
import com.explodingpixels.macwidgets.MacUtils;
import java.awt.*;
import java.awt.event.*;
import java.io.*;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.net.URL;
import java.util.*;
import javax.swing.*;
import javax.swing.event.*;
import javax.swing.text.*;
import javax.swing.undo.*;
import org.apache.commons.cli.CommandLine;
import org.jdesktop.swingx.JXCollapsiblePane;
import org.jdesktop.swingx.JXSearchField;
import org.jdesktop.swingx.JXTaskPane;
import org.jdesktop.swingx.JXTaskPaneContainer;
import org.sikuli.basics.CommandArgs;
import org.sikuli.script.EventObserver;
import org.sikuli.script.EventSubject;
import org.sikuli.basics.HotkeyEvent;
import org.sikuli.basics.HotkeyListener;
import org.sikuli.basics.HotkeyManager;
import org.sikuli.script.Location;
import org.sikuli.script.OverlayCapturePrompt;
import org.sikuli.script.Screen;
import org.sikuli.script.ScreenImage;
import org.sikuli.basics.Debug;
import org.sikuli.basics.FileManager;
import org.sikuli.basics.IScriptRunner;
import org.sikuli.basics.PreferencesUser;
import org.sikuli.basics.Settings;
import org.sikuli.basics.AutoUpdater;
import org.sikuli.basics.CommandArgsEnum;
import org.sikuli.basics.IDESupport;
import org.sikuli.basics.IResourceLoader;
import org.sikuli.script.ImagePath;
import org.sikuli.basics.MultiFrame;
import org.sikuli.basics.RunSetup;
import org.sikuli.basics.SikuliScript;
import org.sikuli.basics.SikuliX;
import org.sikuli.script.Key;

public class SikuliIDE extends JFrame {

  private static String me = "IDE";
  private static int lvl = 3;

  private static void log(int level, String message, Object... args) {
    Debug.logx(level, "", me + ": " + message, args);
  }
  // RaiMan not used final static boolean ENABLE_RECORDING = false;
  final static boolean ENABLE_UNIFIED_TOOLBAR = true;
  final static Color COLOR_SEARCH_FAILED = Color.red;
  final static Color COLOR_SEARCH_NORMAL = Color.black;
  final static int WARNING_CANCEL = 2;
  final static int WARNING_ACCEPTED = 1;
  final static int WARNING_DO_NOTHING = 0;
  final static int IS_SAVE_ALL = 3;
  static boolean _runningSkl = false;
  private static NativeSupport nativeSupport;
  private Dimension _windowSize = null;
  private Point _windowLocation = null;
  private boolean smallScreen = false;
  private int commandBarHeight = 800;
  private CloseableTabbedPane _mainPane;
  private EditorLineNumberView lineNumberColumn;
  private JSplitPane _mainSplitPane;
  private JTabbedPane msgPane;
  private boolean msgPaneCollapsed = false;
  private EditorConsolePane _console;
  private JXCollapsiblePane _cmdList;
  private SikuliIDEStatusBar _status = null;
  private ButtonCapture _btnCapture;
  private ButtonRun _btnRun, _btnRunViz;
  private boolean ideIsRunningScript = false;
  private JXSearchField _searchField;
  private JMenuBar _menuBar = new JMenuBar();
  private JMenu _fileMenu = new JMenu(_I("menuFile"));
  private JMenu _editMenu = new JMenu(_I("menuEdit"));
  private UndoAction _undoAction = new UndoAction();
  private RedoAction _redoAction = new RedoAction();
  private FindAction _findHelper;
  private JMenu _runMenu = new JMenu(_I("menuRun"));
  private JMenu _viewMenu = new JMenu(_I("menuView"));
  private JMenu _toolMenu = new JMenu(_I("menuTool"));
  private JMenu _helpMenu = new JMenu(_I("menuHelp"));
  private JXCollapsiblePane _sidePane;
  private JCheckBoxMenuItem _chkShowUnitTest;
  private JMenuItem chkShowCmdList = null;
  private JCheckBoxMenuItem chkShowThumbs;
  //private UnitTestRunner _testRunner;
  private static CommandLine cmdLine;
  private static String cmdValue;
  private static String[] loadScript = null;
  private static SikuliIDE _instance = null;
  private boolean _inited = false;
  private static boolean runMe = false;
  private int restoredScripts = 0;
  private int alreadyOpenedTab = -1;
  private PreferencesUser prefs;
  private boolean ACCESSING_AS_FOLDER = false;
  private static JFrame splash;
  private boolean firstRun = true;
  private static long start;
  private static File isRunning;
  private static FileOutputStream isRunningFile = null;

  static {
  }

  public static IDESupport getIDESupport(String ending) {
    return Settings.ideSupporter.get(ending);
  }

  public static String _I(String key, Object... args) {
    try {
      return SikuliIDEI18N._I(key, args);
    } catch (Exception e) {
      Debug.log(3, "[I18N] " + key);
      return key;
    }
  }

  public static ImageIcon getIconResource(String name) {
    URL url = SikuliIDE.class.getResource(name);
    if (url == null) {
      Debug.error("Warning: could not load \"" + name + "\" icon");
      return null;
    }
    return new ImageIcon(url);
  }

//TODO run only one windowed instance of IDE
  public static void main(String[] args) {
    String[] splashArgs = new String[]{
      "splash", "#", "#" + Settings.SikuliVersionIDE, "", "#", "#... starting - please wait ..."};

    new File(Settings.BaseTempPath).mkdirs();
    isRunning = new File(Settings.BaseTempPath, "sikuli-ide-isrunning");
    try {
      isRunning.createNewFile();
      isRunningFile = new FileOutputStream(isRunning);
      if (null == isRunningFile.getChannel().tryLock()) {
        splashArgs[5] = "Terminating on FatalError: IDE already running";
        splash = new MultiFrame(splashArgs);
        log(-1, splashArgs[5]);
        SikuliX.pause(3);
        System.exit(1);
      }
    } catch (Exception ex) {
      splashArgs[5] = "Terminating on FatalError: cannot access IDE lock ";
      splash = new MultiFrame(splashArgs);
      log(-1, splashArgs[5] + "\n" + isRunning.getAbsolutePath());
      SikuliX.pause(3);
      System.exit(1);
    }

    Runtime.getRuntime().addShutdownHook(new Thread() {
        @Override
        public void run() {
          log(lvl, "final cleanup");
          try {
            isRunningFile.close();
          } catch (IOException ex) {
          }
          isRunning.delete();
          FileManager.cleanTemp();
        }
    });

    if (System.getProperty("sikuli.FromCommandLine") == null) {
      String[] userOptions = SikuliX.collectOptions("IDE", args);
      if (userOptions == null) {
        System.exit(0);
      }
      if (userOptions.length > 0) {
        for (String e : userOptions) {
          log(lvl, "arg: " + e);
        }
        args = userOptions;
      }
    }

    start = (new Date()).getTime();

    Settings.initScriptingSupport();

    for (String e : args) {
      splashArgs[3] += e + " ";
    }
    splashArgs[3] = splashArgs[3].trim();
    splash = new MultiFrame(splashArgs);

    CommandArgs cmdArgs = new CommandArgs("IDE");
    cmdLine = cmdArgs.getCommandLine(CommandArgs.scanArgs(args));

    if (cmdLine == null) {
      Debug.error("Did not find any valid option on command line!");
      System.exit(1);
    }

    if (cmdLine.hasOption("h")) {
      cmdArgs.printHelp();
      System.exit(0);
    }

    if (cmdLine.hasOption("c")) {
      System.setProperty("sikuli.console", "false");
    }

    if (cmdLine.hasOption(CommandArgsEnum.LOGFILE.shortname())) {
      cmdValue = cmdLine.getOptionValue(CommandArgsEnum.LOGFILE.longname());
      if (!Debug.setLogFile(cmdValue == null ? "" : cmdValue)) {
        System.exit(1);
      }
    }

    if (cmdLine.hasOption(CommandArgsEnum.USERLOGFILE.shortname())) {
      cmdValue = cmdLine.getOptionValue(CommandArgsEnum.USERLOGFILE.longname());
      if (!Debug.setUserLogFile(cmdValue == null ? "" : cmdValue)) {
        System.exit(1);
      }
    }

    if (cmdLine.hasOption(CommandArgsEnum.DEBUG.shortname())) {
      cmdValue = cmdLine.getOptionValue(CommandArgsEnum.DEBUG.longname());
      if (cmdValue == null) {
        Debug.setDebugLevel(3);
        Debug.setLogFile("");
        Settings.LogTime = true;
      } else {
        Debug.setDebugLevel(cmdValue);
      }
    }

    if (cmdLine.hasOption(CommandArgsEnum.LOAD.shortname())) {
      loadScript = cmdLine.getOptionValues(CommandArgsEnum.LOAD.longname());
      log(lvl, "requested to load: " + loadScript);
      if (loadScript[0].endsWith(".skl")) {
        log(lvl, "Switching to SikuliScript to run " + loadScript);
        splash.dispose();
        SikuliScript.main(args);
      }
    }

    if (cmdLine.hasOption(CommandArgsEnum.RUN.shortname())
            || cmdLine.hasOption(CommandArgsEnum.TEST.shortname())
            || cmdLine.hasOption(CommandArgsEnum.INTERACTIVE.shortname())) {
      log(lvl, "Switching to SikuliScript with option -r, -t or -i");
      splash.dispose();
      SikuliScript.main(args);
    }

    Settings.setArgs(cmdArgs.getUserArgs(), cmdArgs.getSikuliArgs());
    Settings.showJavaInfo();
    Settings.printArgs();

    initNativeSupport();
    try {
      UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName());
      //UIManager.setLookAndFeel(UIManager.getCrossPlatformLookAndFeelClassName());
    } catch (Exception e) {
      log(-1, "Problem loading UIManager!\nError: %s", e.getMessage());
    }

    if (nativeSupport != null) {
      nativeSupport.initApp(Debug.getDebugLevel() > 2 ? true : false);
      try {
        Thread.sleep(1000);
      } catch (InterruptedException ie) {
      }
    }
    SikuliIDE.getInstance(args);
  }

  //<editor-fold defaultstate="collapsed" desc="IDE setup and general">
  private SikuliIDE() {
    super("Sikuli IDE");
  }

  private void initSikuliIDE(String[] args) {
    prefs = PreferencesUser.getInstance();
    if (prefs.getUserType() < 0) {
      prefs.setUserType(PreferencesUser.NEWBEE);
      prefs.setIdeSession("");
      prefs.setDefaults(prefs.getUserType());
    }

    if (nativeSupport != null) {
      nativeSupport.initIDE(this);
    }

    IResourceLoader loader = FileManager.getNativeLoader("basic", args);
    loader.check(Settings.SIKULI_LIB);

    _windowSize = prefs.getIdeSize();
    _windowLocation = prefs.getIdeLocation();
    Screen m = (new Location(_windowLocation)).getScreen();
    if (m == null) {
      Debug.error("IDE: remembered window not valid - going to primary screen");
      m = Screen.getPrimaryScreen();
      _windowSize.width = 0;
    }
    Rectangle s = m.getBounds();
    if (_windowSize.width == 0 || _windowSize.width > s.width
            || _windowSize.height > s.height) {
      if (s.width < 1025) {
        _windowSize = new Dimension(1024, 700);
        _windowLocation = new Point(0, 0);
      } else {
        _windowSize = new Dimension(s.width - 150, s.height - 100);
        _windowLocation = new Point(75, 0);
      }
    }
    if (_windowSize.getHeight() < commandBarHeight) {
      smallScreen = true;
    }
    setSize(_windowSize);
    setLocation(_windowLocation);

    initMenuBars(this);
    final Container c = getContentPane();
    c.setLayout(new BorderLayout());
    initTabPane();
    initMsgPane(prefs.getPrefMoreMessage() == PreferencesUser.HORIZONTAL);
// RaiMan not used		initSidePane(); // IDE UnitTest

    JPanel codeAndUnitPane = new JPanel(new BorderLayout(10, 10));
    codeAndUnitPane.setBorder(BorderFactory.createEmptyBorder(0, 8, 0, 0));
    codeAndUnitPane.add(_mainPane, BorderLayout.CENTER);
// RaiMan not used		codeAndUnitPane.add(_sidePane, BorderLayout.EAST);
    if (prefs.getPrefMoreMessage() == PreferencesUser.VERTICAL) {
      _mainSplitPane = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, codeAndUnitPane, msgPane);
    } else {
      _mainSplitPane = new JSplitPane(JSplitPane.VERTICAL_SPLIT, codeAndUnitPane, msgPane);
    }
    _mainSplitPane.setResizeWeight(0.6);
    _mainSplitPane.setBorder(BorderFactory.createEmptyBorder(0, 0, 0, 0));

    JPanel editPane = new JPanel(new BorderLayout(0, 0));

    JComponent cp = createCommandPane();

    if (PreferencesUser.getInstance().getPrefMoreCommandBar()) {
      editPane.add(cp, BorderLayout.WEST);
    }

    editPane.add(_mainSplitPane, BorderLayout.CENTER);
    c.add(editPane, BorderLayout.CENTER);

    JToolBar tb = initToolbar();
    c.add(tb, BorderLayout.NORTH); // the buttons

    c.add(initStatusbar(), BorderLayout.SOUTH);
    c.doLayout();

    initShortcutKeys();
    initHotkeys();
    setDefaultCloseOperation(WindowConstants.EXIT_ON_CLOSE);
    initWindowListener();
    initTooltip();

    autoCheckUpdate();

    _inited = true;
    try {
      getCurrentCodePane().requestFocus();
    } catch (Exception e) {
    }
    splash.dispose();
//TODO restore selected tab
    restoreSession();
    makeTabNull();
    Debug.log(3, "Sikuli-IDE startup: " + ((new Date()).getTime() - start));
    setVisible(true);
    _mainSplitPane.setDividerLocation(0.6);
    return; // as breakpoint
  }

  public static synchronized SikuliIDE getInstance(String args[]) {
    if (_instance == null) {
      _instance = new SikuliIDE();
      _instance.initSikuliIDE(args);
    }
    return _instance;
  }

  public static synchronized SikuliIDE getInstance() {
    return getInstance(null);
  }

  public void makeTabNull() {
    if (_mainPane.getTabCount() == 0) {
      (new FileAction()).doNew(null);
    }
    _mainPane.setSelectedIndex(0);
  }

  @Override
  public void setTitle(String title) {
    super.setTitle(SikuliIDESettings.SikuliVersion + " - " + title);
  }

  static private void initNativeSupport() {
//TODO IDE native Support
//    String jb = FileManager.getJarName();
//    ClassPool pool  = ClassPool.getDefault();
//    try {
//      pool.appendClassPath(jb);
//      pool.find("resources.NativeSupportMac");
//      CtClass nsm = pool.get("resources.NativeSupportMac");
//      nativeSupport = (NativeSupport) nsm.toClass().newInstance();
//    } catch (Exception ex) {
//      log(-1, "JavAssist: problem:%s", ex.getMessage());
//    }
    String os = "unknown";
    if (Settings.isWindows()) {
      os = "Windows";
    } else if (Settings.isMac()) {
      os = "Mac";
    } else if (Settings.isLinux()) {
      os = "Linux";
    }
    String className = "org.sikuli.ide.NativeSupport" + os;
    try {
      Class c = Class.forName(className);
      Constructor constr = c.getConstructor();
      nativeSupport = (NativeSupport) constr.newInstance();
      log(lvl, "Native support found for " + os);
    } catch (Exception e) {
      log(-1, "No native support for %s\n%s", os, e.getMessage());
    }
  }

  private boolean saveSession(int action, boolean quitting) {
    int nTab = _mainPane.getTabCount();
    StringBuilder sbuf = new StringBuilder();
    for (int tabIndex = 0; tabIndex < nTab; tabIndex++) {
      try {
        JScrollPane scrPane = (JScrollPane) _mainPane.getComponentAt(tabIndex);
        EditorPane codePane = (EditorPane) scrPane.getViewport().getView();
        if (action == WARNING_DO_NOTHING) {
          if (quitting) {
            codePane.setDirty(false);
            if (codePane.isSourceBundleTemp()) {
              FileManager.deleteTempDir(codePane.getSrcBundle());
            }
          }
          if (codePane.getCurrentFilename() == null) {
            continue;
          }
        } else if (codePane.isDirty()) {
          if (!(new FileAction()).doSaveIntern(tabIndex)) {
            if (quitting) {
              codePane.setDirty(false);
            }
            continue;
          }
        }
        if (action == IS_SAVE_ALL) {
          continue;
        }
        File f = codePane.getCurrentFile();
        if (f != null) {
          String bundlePath = codePane.getSrcBundle();
          Debug.log(5, "save session: " + bundlePath);
          if (tabIndex != 0) {
            sbuf.append(";");
          }
          sbuf.append(bundlePath);
        }
      } catch (Exception e) {
        log(-1, "Problem while trying to save all changed-not-saved scripts!\nError: %s", e.getMessage());
        return false;
      }
    }
    PreferencesUser.getInstance().setIdeSession(sbuf.toString());
    return true;
  }

  private void restoreSession() {
    String session_str = prefs.getIdeSession();
    String[] filenames = null;
    if (session_str == null && loadScript == null) {
      return;
    }
    filenames = session_str.split(";");
    for (int i = 0; i < filenames.length; i++) {
      if (filenames[i].isEmpty()) {
        continue;
      }
      Debug.log(3, "restore session at %d: " + filenames[i], restoredScripts + 1);
      File f = new File(filenames[i]);
      if (f.exists()) {
        if (restoreScriptFromSession(filenames[i])) {
          restoredScripts += 1;
        }
      }
    }
    if (loadScript == null) {
      return;
    }
    int ao;
    for (int i = 0; i < loadScript.length; i++) {
      if (loadScript[i].isEmpty()) {
        continue;
      }
      File f = new File(loadScript[i]);
      if (f.exists()) {
        if (restoreScriptFromSession(loadScript[i])) {
          ao = isAlreadyOpen(getCurrentCodePane().getCurrentSrcDir());
          if (ao < 0) {
            restoredScripts += 1;
            Debug.log(3, "preload script at %d: " + loadScript[i], restoredScripts + 1);
          } else {
            _mainPane.remove(ao);
            Debug.log(3, "preload session script at %d: " + loadScript[i], restoredScripts + 1);
          }
        }
      }
    }
  }

  public boolean restoreScriptFromSession(String file) {
    (new FileAction()).doNew(null);
    getCurrentCodePane().loadFile(file);
    if (getCurrentCodePane().hasEditingFile()) {
      setCurrentFileTabTitle(file);
      return true;
    }
    Debug.error("Can't load file " + file + " --- check available runners!");
//    (new FileAction()).doCloseTab(null);
    return false;
  }

  //</editor-fold>
  //<editor-fold defaultstate="collapsed" desc="Support SikuliIDE">
  public JMenu getFileMenu() {
    return _fileMenu;
  }

  public JMenu getRunMenu() {
    return _runMenu;
  }

  public CloseableTabbedPane getTabPane() {
    return _mainPane;
  }

  public EditorPane getCurrentCodePane() {
    if (_mainPane.getSelectedIndex() == -1) {
      return null;
    }
    JScrollPane scrPane = (JScrollPane) _mainPane.getSelectedComponent();
    EditorPane ret = (EditorPane) scrPane.getViewport().getView();
    return ret;
  }

  public void setCurrentFileTabTitle(String fname) {
    int tabIndex = _mainPane.getSelectedIndex();
    setFileTabTitle(fname, tabIndex);
  }

  public String getCurrentFileTabTitle() {
    String fname = _mainPane.getTitleAt(_mainPane.getSelectedIndex());
    if (fname.startsWith("*")) {
      return fname.substring(1);
    } else {
      return fname;
    }
  }

  public void setFileTabTitle(String fname, int tabIndex) {
    String fullName = fname;
    if (fname.endsWith("/")) {
      fname = fname.substring(0, fname.length() - 1);
    }
    fname = fname.substring(fname.lastIndexOf("/") + 1);
    fname = fname.substring(0, fname.lastIndexOf("."));
    _mainPane.setTitleAt(tabIndex, fname);
    this.setTitle(fullName);
  }

  public void setCurrentFileTabTitleDirty(boolean isDirty) {
    int i = _mainPane.getSelectedIndex();
    String title = _mainPane.getTitleAt(i);
    if (!isDirty && title.startsWith("*")) {
      title = title.substring(1);
      _mainPane.setTitleAt(i, title);
    } else if (isDirty && !title.startsWith("*")) {
      title = "*" + title;
      _mainPane.setTitleAt(i, title);
    }
  }

  public ArrayList<String> getOpenedFilenames() {
    int nTab = _mainPane.getTabCount();
    File file = null;
    String filePath;
    ArrayList<String> filenames = new ArrayList<String>(0);
    if (nTab > 0) {
      for (int i = 0; i < nTab; i++) {
        JScrollPane scrPane = (JScrollPane) _mainPane.getComponentAt(i);
        EditorPane codePane = (EditorPane) scrPane.getViewport().getView();
        file = codePane.getCurrentFile(false);
        if (file != null) {
          filePath = FileManager.slashify(file.getAbsolutePath(), false);
          filePath = filePath.substring(0, filePath.lastIndexOf("/"));
          filenames.add(filePath);
        } else {
          filenames.add("");
        }
      }
    }
    return filenames;
  }

  public int isAlreadyOpen(String filename) {
    int aot = getOpenedFilenames().indexOf(filename);
    if (aot > -1 && aot < (_mainPane.getTabCount() - 1)) {
      alreadyOpenedTab = aot;
      return aot;
    }
    return -1;
  }

  private void autoCheckUpdate() {
    PreferencesUser pref = PreferencesUser.getInstance();
    if (!pref.getCheckUpdate()) {
      return;
    }
    long last_check = pref.getCheckUpdateTime();
    long now = (new Date()).getTime();
    if (now - last_check > 1000 * 604800) {
      Debug.log(3, "autocheck update");
      (new HelpAction()).checkUpdate(true);
    }
    pref.setCheckUpdateTime();
  }

  public boolean isRunningScript() {
    return ideIsRunningScript;
  }

  public void setIsRunningScript(boolean state) {
    ideIsRunningScript = state;
  }

  protected boolean doBeforeRun() {
    int action;
    if (checkDirtyPanes()) {
      if (prefs.getPrefMoreRunSave()) {
        action = WARNING_ACCEPTED;
      } else {
        action = askForSaveAll("Run");
        if (action < 0) {
          return false;
        }
      }
      saveSession(action, false);
    }
    Settings.ActionLogs = prefs.getPrefMoreLogActions();
    Settings.DebugLogs = prefs.getPrefMoreLogDebug();
    Settings.InfoLogs = prefs.getPrefMoreLogInfo();
    Settings.Highlight = prefs.getPrefMoreHighlight();
    Settings.OcrTextSearch = prefs.getPrefMoreTextSearch();
    Settings.OcrTextRead = prefs.getPrefMoreTextOCR();
    return true;
  }

  protected boolean doBeforeQuit() {
    if (checkDirtyPanes()) {
      int action = askForSaveAll("Quit");
      if (action < 0) {
        return false;
      }
      return saveSession(action, true);
    }
    return saveSession(WARNING_DO_NOTHING, true);
  }

  private int askForSaveAll(String typ) {
//TODO I18N
    String warn = "Some scripts are not saved yet!";
    String title = SikuliIDEI18N._I("dlgAskCloseTab");
    String[] options = new String[3];
    options[WARNING_DO_NOTHING] = typ + " immediately";
    options[WARNING_ACCEPTED] = "Save all and " + typ;
    options[WARNING_CANCEL] = SikuliIDEI18N._I("cancel");
    int ret = JOptionPane.showOptionDialog(this, warn, title, 0, JOptionPane.WARNING_MESSAGE, null, options, options[2]);
    if (ret == WARNING_CANCEL || ret == JOptionPane.CLOSED_OPTION) {
      return -1;
    }
    return ret;
  }

  public void doAbout() {
    //TODO full featured About
    String info = "You are running " + Settings.SikuliVersionIDE
            + "\n\nNeed help? -> start with Help Menu\n\n"
            + "*** Have fun ;-)\n\n"
            + "Tsung-Hsiang Chang aka vgod\n"
            + "Tom Yeh\n"
            + "Raimund Hocke aka RaiMan\n\n"
            + Settings.versionMonth
            + "\n\nBuild: " + RunSetup.timestampBuilt;
    JOptionPane.showMessageDialog(this, info,
            "Sikuli About", JOptionPane.PLAIN_MESSAGE);
  }
  //</editor-fold>

  //<editor-fold defaultstate="collapsed" desc="isInited --- RaiMan not used">
  public boolean isInited() {
    return _inited;
  }
  //</editor-fold>

  private JMenuItem createMenuItem(JMenuItem item, KeyStroke shortcut, ActionListener listener) {
    if (shortcut != null) {
      item.setAccelerator(shortcut);
    }
    item.addActionListener(listener);
    return item;
  }

  private JMenuItem createMenuItem(String name, KeyStroke shortcut, ActionListener listener) {
    JMenuItem item = new JMenuItem(name);
    return createMenuItem(item, shortcut, listener);
  }

  class MenuAction implements ActionListener {

    protected Method actMethod = null;
    protected String action;

    public MenuAction() {
    }

    public MenuAction(String item) throws NoSuchMethodException {
      Class[] paramsWithEvent = new Class[1];
      try {
        paramsWithEvent[0] = Class.forName("java.awt.event.ActionEvent");
        actMethod = this.getClass().getMethod(item, paramsWithEvent);
        action = item;
      } catch (ClassNotFoundException cnfe) {
        log(-1, "Can't find menu action: " + cnfe);
      }
    }

    @Override
    public void actionPerformed(ActionEvent e) {
      if (actMethod != null) {
        try {
          Debug.log(3, "MenuAction." + action);
          Object[] params = new Object[1];
          params[0] = e;
          actMethod.invoke(this, params);
        } catch (Exception ex) {
          log(-1, "Problem when trying to invoke menu action %s\nError: %s",
                  action, ex.getMessage());
        }
      }
    }
  }

  //<editor-fold defaultstate="collapsed" desc="Init FileMenu">
  private void initFileMenu() throws NoSuchMethodException {
    JMenuItem jmi;
    int scMask = Toolkit.getDefaultToolkit().getMenuShortcutKeyMask();
    _fileMenu.setMnemonic(java.awt.event.KeyEvent.VK_F);

    if (!Settings.isMac()) {
      _fileMenu.add(createMenuItem("About SikuliX",
              KeyStroke.getKeyStroke(java.awt.event.KeyEvent.VK_A, scMask),
              new FileAction(FileAction.ABOUT)));
      _fileMenu.addSeparator();
    }

    _fileMenu.add(createMenuItem(_I("menuFileNew"),
            KeyStroke.getKeyStroke(java.awt.event.KeyEvent.VK_N, scMask),
            new FileAction(FileAction.NEW)));

    jmi = _fileMenu.add(createMenuItem(_I("menuFileOpen"),
            KeyStroke.getKeyStroke(java.awt.event.KeyEvent.VK_O, scMask),
            new FileAction(FileAction.OPEN)));
    jmi.setName("OPEN");

    if (Settings.isMac() && !Settings.handlesMacBundles) {
      _fileMenu.add(createMenuItem("Open folder.sikuli ...",
              null,
              //            KeyStroke.getKeyStroke(java.awt.event.KeyEvent.VK_O, scMask),
              new FileAction(FileAction.OPEN_FOLDER)));
    }

    jmi = _fileMenu.add(createMenuItem(_I("menuFileSave"),
            KeyStroke.getKeyStroke(java.awt.event.KeyEvent.VK_S, scMask),
            new FileAction(FileAction.SAVE)));
    jmi.setName("SAVE");

    jmi = _fileMenu.add(createMenuItem(_I("menuFileSaveAs"),
            KeyStroke.getKeyStroke(java.awt.event.KeyEvent.VK_S,
                    InputEvent.SHIFT_MASK | scMask),
            new FileAction(FileAction.SAVE_AS)));
    jmi.setName("SAVE_AS");

    if (Settings.isMac() && !Settings.handlesMacBundles) {
      _fileMenu.add(createMenuItem(_I("Save as folder.sikuli ..."),
              //            KeyStroke.getKeyStroke(java.awt.event.KeyEvent.VK_S,
              //            InputEvent.SHIFT_MASK | scMask),
              null,
              new FileAction(FileAction.SAVE_AS_FOLDER)));
    }

//TODO    _fileMenu.add(createMenuItem(_I("menuFileSaveAll"),
    _fileMenu.add(createMenuItem("Save all",
            KeyStroke.getKeyStroke(java.awt.event.KeyEvent.VK_S,
                    InputEvent.CTRL_MASK | scMask),
            new FileAction(FileAction.SAVE_ALL)));

    _fileMenu.add(createMenuItem(_I("menuFileExport"),
            KeyStroke.getKeyStroke(java.awt.event.KeyEvent.VK_E,
                    InputEvent.SHIFT_MASK | scMask),
            new FileAction(FileAction.EXPORT)));

    jmi = _fileMenu.add(createMenuItem(_I("menuFileCloseTab"),
            KeyStroke.getKeyStroke(java.awt.event.KeyEvent.VK_W, scMask),
            new FileAction(FileAction.CLOSE_TAB)));
    jmi.setName("CLOSE_TAB");

    if (!Settings.isMac()) {
      _fileMenu.addSeparator();
      _fileMenu.add(createMenuItem(_I("menuFilePreferences"),
              KeyStroke.getKeyStroke(java.awt.event.KeyEvent.VK_P, scMask),
              new FileAction(FileAction.PREFERENCES)));
    }

    if (!Settings.isMac()) {
      _fileMenu.addSeparator();
      _fileMenu.add(createMenuItem(_I("menuFileQuit"),
              null, new FileAction(FileAction.QUIT)));
    }
  }

  public FileAction getFileAction(int tabIndex) {
    return new FileAction(tabIndex);
  }

  class FileAction extends MenuAction {

    static final String ABOUT = "doAbout";
    static final String NEW = "doNew";
    static final String INSERT = "doInsert";
    static final String OPEN = "doLoad";
    static final String OPEN_FOLDER = "doLoadFolder";
    static final String SAVE = "doSave";
    static final String SAVE_AS = "doSaveAs";
    static final String SAVE_AS_FOLDER = "doSaveAsFolder";
    static final String SAVE_ALL = "doSaveAll";
    static final String EXPORT = "doExport";
    static final String CLOSE_TAB = "doCloseTab";
    static final String PREFERENCES = "doPreferences";
    static final String QUIT = "doQuit";
    private int targetTab = -1;

    public FileAction() {
      super();
    }

    public FileAction(int tabIndex) {
      super();
      targetTab = tabIndex;
    }

    public FileAction(String item) throws NoSuchMethodException {
      super(item);
    }

    public void doAbout(ActionEvent ae) {
      SikuliIDE.getInstance().doAbout();
    }

    public void doQuit(ActionEvent ae) {
      SikuliIDE ide = SikuliIDE.getInstance();
      if (!doBeforeQuit()) {
        return;
      }
      while (true) {
        EditorPane codePane = ide.getCurrentCodePane();
        if (codePane == null) {
          break;
        }
        if (!ide.closeCurrentTab()) {
          return;
        }
      }
      SikuliX.cleanUp(0);
      HotkeyManager.getInstance().cleanUp();
      System.exit(0);
    }

    public void doPreferences(ActionEvent ae) {
      SikuliIDE.getInstance().showPreferencesWindow();
    }

    public void doNew(ActionEvent ae) {
      doNew(ae, -1).initBeforeLoad(null);
    }

    public EditorPane doNew(ActionEvent ae, int tabIndex) {
      EditorPane codePane = new EditorPane(SikuliIDE.getInstance());
      JScrollPane scrPane = new JScrollPane(codePane);
      lineNumberColumn = new EditorLineNumberView(codePane);
      scrPane.setRowHeaderView(lineNumberColumn);
      if (ae == null) {
        if (tabIndex < 0 || tabIndex >= _mainPane.getTabCount()) {
          _mainPane.addTab(_I("tabUntitled"), scrPane);
        } else {
          _mainPane.addTab(_I("tabUntitled"), scrPane, tabIndex);
        }
        _mainPane.setSelectedIndex(tabIndex < 0 ? _mainPane.getTabCount() - 1 : tabIndex);
      } else {
        _mainPane.addTab(_I("tabUntitled"), scrPane, 0);
        _mainPane.setSelectedIndex(0);
      }
      codePane.getSrcBundle();
      codePane.requestFocus();
      return codePane;
    }

    public void doInsert(ActionEvent ae) {
      doLoad(null);
    }

    public void doLoad(ActionEvent ae) {
      boolean accessingAsFile = false;
      if (Settings.isMac()) {
        accessingAsFile = !ACCESSING_AS_FOLDER;
        ACCESSING_AS_FOLDER = false;
      }
      alreadyOpenedTab = _mainPane.getSelectedIndex();
      String fname = _mainPane.getLastClosed();
      try {
        doNew(null, targetTab);
        EditorPane codePane = SikuliIDE.getInstance().getCurrentCodePane();
        if (ae != null || fname == null) {
          codePane.isSourceBundleTemp();
          fname = codePane.loadFile(accessingAsFile);
        } else {
          codePane.loadFile(fname);
          if (codePane.hasEditingFile()) {
            setCurrentFileTabTitle(fname);
          } else {
            fname = null;
          }
        }
        if (fname != null) {
          SikuliIDE.getInstance().setCurrentFileTabTitle(fname);
        } else {
          if (ae != null) {
            doCloseTab(null);
          }
          _mainPane.setSelectedIndex(alreadyOpenedTab);
        }
      } catch (IOException eio) {
        log(-1, "Problem when trying to load %s\nError: %s",
                fname, eio.getMessage());
      }
    }

    public void doLoadFolder(ActionEvent ae) {
      Debug.log(3, "IDE: doLoadFolder requested");
      ACCESSING_AS_FOLDER = true;
      doLoad(ae);
    }

    public void doSave(ActionEvent ae) {
      String fname = null;
      try {
        EditorPane codePane = SikuliIDE.getInstance().getCurrentCodePane();
        fname = codePane.saveFile();
        if (fname != null) {
          fname = codePane.getSrcBundle();
          SikuliIDE.getInstance().setCurrentFileTabTitle(fname);
          _mainPane.setLastClosed(fname);
        }
      } catch (IOException eio) {
        log(-1, "Problem when trying to save %s\nError: %s",
                fname, eio.getMessage());
      }
    }

    public boolean doSaveIntern(int tabIndex) {
      int currentTab = _mainPane.getSelectedIndex();
      _mainPane.setSelectedIndex(tabIndex);
      boolean retval = true;
      JScrollPane scrPane = (JScrollPane) _mainPane.getComponentAt(tabIndex);
      EditorPane codePane = (EditorPane) scrPane.getViewport().getView();
      String fname = null;
      try {
        fname = codePane.saveFile();
        if (fname != null) {
          SikuliIDE.getInstance().setFileTabTitle(fname, tabIndex);
        } else {
          retval = false;
        }
      } catch (IOException eio) {
        log(-1, "Problem when trying to save %s\nError: %s",
                fname, eio.getMessage());
        retval = false;
      }
      _mainPane.setSelectedIndex(currentTab);
      return retval;
    }

    public void doSaveAs(ActionEvent ae) {
      boolean accessingAsFile = false;
      if (Settings.isMac()) {
        accessingAsFile = !ACCESSING_AS_FOLDER;
        ACCESSING_AS_FOLDER = false;
      }
      String fname = null;
      try {
        EditorPane codePane = SikuliIDE.getInstance().getCurrentCodePane();
        fname = codePane.saveAsFile(accessingAsFile);
        if (fname != null) {
          SikuliIDE.getInstance().setCurrentFileTabTitle(fname);
        }
      } catch (IOException eio) {
        log(-1, "Problem when trying to save %s\nError: %s",
                fname, eio.getMessage());
      }
    }

    public void doSaveAsFolder(ActionEvent ae) {
      Debug.log(3, "IDE: doSaveAsFolder requested");
      ACCESSING_AS_FOLDER = true;
      doSaveAs(ae);
    }

    public void doSaveAll(ActionEvent ae) {
      Debug.log(3, "IDE: doSaveAll requested");
      if (!checkDirtyPanes()) {
        return;
      }
      saveSession(IS_SAVE_ALL, false);
    }

    public void doExport(ActionEvent ae) {
      String fname = null;
      try {
        EditorPane codePane = SikuliIDE.getInstance().getCurrentCodePane();
        fname = codePane.exportAsZip();
      } catch (Exception ex) {
        log(-1, "Problem when trying to save %s\nError: %s",
                fname, ex.getMessage());
      }
    }

    public void doCloseTab(ActionEvent ae) {
      EditorPane codePane = SikuliIDE.getInstance().getCurrentCodePane();
      if (ae == null) {
        _mainPane.remove(_mainPane.getSelectedIndex());
        return;
      }
      try {
        if (codePane.close()) {
          _mainPane.remove(_mainPane.getSelectedIndex());
        }
      } catch (IOException e) {
        Debug.info("Can't close this tab: " + e.getStackTrace());
      }
      codePane = SikuliIDE.getInstance().getCurrentCodePane();
      if (codePane != null) {
        codePane.requestFocus();
      } else if (ae != null) {
        (new FileAction()).doNew(null);
      }
    }
  }

  public void showPreferencesWindow() {
    PreferencesWin pwin = new PreferencesWin();
    pwin.setAlwaysOnTop(true);
    pwin.setDefaultCloseOperation(DO_NOTHING_ON_CLOSE);
    if (!Settings.isJava7()) {
      pwin.setLocation(SikuliIDE.getInstance().getLocation());
    }
    pwin.setVisible(true);
  }

  public boolean closeCurrentTab() {
    EditorPane pane = getCurrentCodePane();
    (new FileAction()).doCloseTab(null);
    if (pane == getCurrentCodePane()) {
      return false;
    }
    return true;
  }

  protected boolean quit() {
    SikuliIDE ide = SikuliIDE.getInstance();
    (new FileAction()).doQuit(null);
    if (ide.getCurrentCodePane() == null) {
      return true;
    } else {
      return false;
    }
  }

  protected boolean checkDirtyPanes() {
    for (int i = 0; i < _mainPane.getTabCount(); i++) {
      try {
        JScrollPane scrPane = (JScrollPane) _mainPane.getComponentAt(i);
        EditorPane codePane = (EditorPane) scrPane.getViewport().getView();
        if (codePane.isDirty()) {
          //RaiMan not used: getRootPane().putClientProperty("Window.documentModified", true);
          return true;
        }
      } catch (Exception e) {
        Debug.error("checkDirtyPanes: " + e.getMessage());
      }
    }
    //RaiMan not used: getRootPane().putClientProperty("Window.documentModified", false);
    return false;
  }
  //</editor-fold>

  //<editor-fold defaultstate="collapsed" desc="Init EditMenu">
  private void initEditMenu() throws NoSuchMethodException {
    int scMask = Toolkit.getDefaultToolkit().getMenuShortcutKeyMask();
    _editMenu.setMnemonic(java.awt.event.KeyEvent.VK_E);
    JMenuItem undoItem = _editMenu.add(_undoAction);
    undoItem.setAccelerator(
            KeyStroke.getKeyStroke(java.awt.event.KeyEvent.VK_Z, scMask));
    JMenuItem redoItem = _editMenu.add(_redoAction);
    redoItem.setAccelerator(
            KeyStroke.getKeyStroke(java.awt.event.KeyEvent.VK_Z, scMask | InputEvent.SHIFT_MASK));
    _editMenu.addSeparator();

    _editMenu.add(createMenuItem(_I("menuEditCut"),
            KeyStroke.getKeyStroke(java.awt.event.KeyEvent.VK_X, scMask),
            new EditAction(EditAction.CUT)));
    _editMenu.add(createMenuItem(_I("menuEditCopy"),
            KeyStroke.getKeyStroke(java.awt.event.KeyEvent.VK_C, scMask),
            new EditAction(EditAction.COPY)));
    _editMenu.add(createMenuItem(_I("menuEditPaste"),
            KeyStroke.getKeyStroke(java.awt.event.KeyEvent.VK_V, scMask),
            new EditAction(EditAction.PASTE)));
    _editMenu.add(createMenuItem(_I("menuEditSelectAll"),
            KeyStroke.getKeyStroke(java.awt.event.KeyEvent.VK_A, scMask),
            new EditAction(EditAction.SELECT_ALL)));
    _editMenu.addSeparator();

    JMenu findMenu = new JMenu(_I("menuFind"));
    _findHelper = new FindAction();
    findMenu.setMnemonic(KeyEvent.VK_F);
    findMenu.add(createMenuItem(_I("menuFindFind"),
            KeyStroke.getKeyStroke(java.awt.event.KeyEvent.VK_F, scMask),
            new FindAction(FindAction.FIND)));
    findMenu.add(createMenuItem(_I("menuFindFindNext"),
            KeyStroke.getKeyStroke(java.awt.event.KeyEvent.VK_G, scMask),
            new FindAction(FindAction.FIND_NEXT)));
    findMenu.add(createMenuItem(_I("menuFindFindPrev"),
            KeyStroke.getKeyStroke(java.awt.event.KeyEvent.VK_G, scMask | InputEvent.SHIFT_MASK),
            new FindAction(FindAction.FIND_PREV)));
    _editMenu.add(findMenu);
    _editMenu.addSeparator();
    _editMenu.add(createMenuItem(_I("menuEditIndent"),
            KeyStroke.getKeyStroke(java.awt.event.KeyEvent.VK_TAB, 0),
            new EditAction(EditAction.INDENT)));
    _editMenu.add(createMenuItem(_I("menuEditUnIndent"),
            KeyStroke.getKeyStroke(java.awt.event.KeyEvent.VK_TAB, InputEvent.SHIFT_MASK),
            new EditAction(EditAction.UNINDENT)));
  }

  class EditAction extends MenuAction {

    static final String CUT = "doCut";
    static final String COPY = "doCopy";
    static final String PASTE = "doPaste";
    static final String SELECT_ALL = "doSelectAll";
    static final String INDENT = "doIndent";
    static final String UNINDENT = "doUnindent";

    public EditAction() {
      super();
    }

    public EditAction(String item) throws NoSuchMethodException {
      super(item);
    }

    private void performEditorAction(String action, ActionEvent ae) {
      SikuliIDE ide = SikuliIDE.getInstance();
      EditorPane pane = ide.getCurrentCodePane();
      pane.getActionMap().get(action).actionPerformed(ae);
    }

    public void doCut(ActionEvent ae) {
//TODO delete current line if no selection
      performEditorAction(DefaultEditorKit.cutAction, ae);
    }

    public void doCopy(ActionEvent ae) {
//TODO copy current line if no selection
      performEditorAction(DefaultEditorKit.copyAction, ae);
    }

    public void doPaste(ActionEvent ae) {
      performEditorAction(DefaultEditorKit.pasteAction, ae);
    }

    public void doSelectAll(ActionEvent ae) {
      performEditorAction(DefaultEditorKit.selectAllAction, ae);
    }

    public void doIndent(ActionEvent ae) {
      SikuliIDE ide = SikuliIDE.getInstance();
      EditorPane pane = ide.getCurrentCodePane();
      (new SikuliEditorKit.InsertTabAction()).actionPerformed(pane);
    }

    public void doUnindent(ActionEvent ae) {
      SikuliIDE ide = SikuliIDE.getInstance();
      EditorPane pane = ide.getCurrentCodePane();
      (new SikuliEditorKit.DeindentAction()).actionPerformed(pane);
    }
  }

  class FindAction extends MenuAction {

    static final String FIND = "doFind";
    static final String FIND_NEXT = "doFindNext";
    static final String FIND_PREV = "doFindPrev";

    public FindAction() {
      super();
    }

    public FindAction(String item) throws NoSuchMethodException {
      super(item);
    }

    public void doFind(ActionEvent ae) {
      _searchField.selectAll();
      _searchField.requestFocus();
    }

    public void doFindNext(ActionEvent ae) {
      findNext(_searchField.getText());
    }

    public void doFindPrev(ActionEvent ae) {
      findPrev(_searchField.getText());
    }

    private boolean _find(String str, int begin, boolean forward) {
      if (str == "!") {
        return false;
      }
      EditorPane codePane = getCurrentCodePane();
      int pos = codePane.search(str, begin, forward);
      Debug.log(7, "find \"" + str + "\" at " + begin + ", found: " + pos);
      if (pos < 0) {
        return false;
      }
      return true;
    }

    public boolean findStr(String str) {
      if (getCurrentCodePane() != null) {
        return _find(str, getCurrentCodePane().getCaretPosition(), true);
      }
      return false;
    }

    public boolean findPrev(String str) {
      if (getCurrentCodePane() != null) {
        return _find(str, getCurrentCodePane().getCaretPosition(), false);
      }
      return false;
    }

    public boolean findNext(String str) {
      if (getCurrentCodePane() != null) {
        return _find(str,
                getCurrentCodePane().getCaretPosition() + str.length(),
                true);
      }
      return false;
    }

    public void setFailed(boolean failed) {
      Debug.log(7, "search failed: " + failed);
      _searchField.setBackground(Color.white);
      if (failed) {
        _searchField.setForeground(COLOR_SEARCH_FAILED);
      } else {
        _searchField.setForeground(COLOR_SEARCH_NORMAL);
      }
    }
  }

  class UndoAction extends AbstractAction {

    public UndoAction() {
      super(_I("menuEditUndo"));
      setEnabled(false);
    }

    public void updateUndoState() {
      if (getCurrentCodePane() != null
              && getCurrentCodePane().getUndoManager().canUndo()) {
        setEnabled(true);
      } else {
        setEnabled(false);
      }
    }

    @Override
    public void actionPerformed(ActionEvent e) {
      EditorUndoManager undo = getCurrentCodePane().getUndoManager();
      try {
        undo.undo();
      } catch (CannotUndoException ex) {
      }
      updateUndoState();
      _redoAction.updateRedoState();
    }
  }

  class RedoAction extends AbstractAction {

    public RedoAction() {
      super(_I("menuEditRedo"));
      setEnabled(false);
    }

    @Override
    public void actionPerformed(ActionEvent e) {
      EditorUndoManager undo = getCurrentCodePane().getUndoManager();
      try {
        undo.redo();
      } catch (CannotRedoException ex) {
      }
      updateRedoState();
      _undoAction.updateUndoState();
    }

    protected void updateRedoState() {
      if (getCurrentCodePane() != null
              && getCurrentCodePane().getUndoManager().canRedo()) {
        setEnabled(true);
      } else {
        setEnabled(false);
      }
    }
  }

  public void updateUndoRedoStates() {
    _undoAction.updateUndoState();
    _redoAction.updateRedoState();
  }
  //</editor-fold>

  //<editor-fold defaultstate="collapsed" desc="Init Run Menu">
  private void initRunMenu() throws NoSuchMethodException {
    JMenuItem item;
    int scMask = Toolkit.getDefaultToolkit().getMenuShortcutKeyMask();
    _runMenu.setMnemonic(java.awt.event.KeyEvent.VK_R);
    item = _runMenu.add(createMenuItem(_I("menuRunRun"),
            KeyStroke.getKeyStroke(java.awt.event.KeyEvent.VK_R, scMask),
            new RunAction(RunAction.RUN)));
    item.setName("RUN");
    item = _runMenu.add(createMenuItem(_I("menuRunRunAndShowActions"),
            KeyStroke.getKeyStroke(java.awt.event.KeyEvent.VK_R,
                    InputEvent.ALT_MASK | scMask),
            new RunAction(RunAction.RUN_SHOW_ACTIONS)));
    item.setName("RUN_SLOWLY");

    PreferencesUser pref = PreferencesUser.getInstance();
    item = createMenuItem(_I("menuRunStop"),
            KeyStroke.getKeyStroke(
                    pref.getStopHotkey(), pref.getStopHotkeyModifiers()),
            new RunAction(RunAction.RUN_SHOW_ACTIONS));
    item.setEnabled(false);
    _runMenu.add(item);
  }

  class RunAction extends MenuAction {

    static final String RUN = "run";
    static final String RUN_SHOW_ACTIONS = "runShowActions";

    public RunAction() {
      super();
    }

    public RunAction(String item) throws NoSuchMethodException {
      super(item);
    }

    public void run(ActionEvent ae) {
      doRun(_btnRun);
    }

    public void runShowActions(ActionEvent ae) {
      doRun(_btnRunViz);
    }

    private void doRun(ButtonRun btn) {
      btn.runCurrentScript();
    }
  }
  //</editor-fold>

  //<editor-fold defaultstate="collapsed" desc="Init View Menu">
  private void initViewMenu() throws NoSuchMethodException {
    int scMask = Toolkit.getDefaultToolkit().getMenuShortcutKeyMask();
    _viewMenu.setMnemonic(java.awt.event.KeyEvent.VK_V);

    if (prefs.getPrefMoreCommandBar()) {
      chkShowCmdList = new JCheckBoxMenuItem(_I("menuViewCommandList"), true);
      _viewMenu.add(createMenuItem(chkShowCmdList,
              KeyStroke.getKeyStroke(java.awt.event.KeyEvent.VK_L, scMask),
              new ViewAction(ViewAction.CMD_LIST)));
    }

    chkShowThumbs = new JCheckBoxMenuItem(_I("menuShowThumbs"), false);
    _viewMenu.add(createMenuItem(chkShowThumbs,
            KeyStroke.getKeyStroke(java.awt.event.KeyEvent.VK_T, scMask),
            new ViewAction(ViewAction.SHOW_THUMBS)));

//TODO Message Area clear
//TODO Message Area LineBreak
  }

  class ViewAction extends MenuAction {

    static final String UNIT_TEST = "toggleUnitTest";
    static final String CMD_LIST = "toggleCmdList";
    static final String SHOW_THUMBS = "toggleShowThumbs";

    public ViewAction() {
      super();
    }

    public ViewAction(String item) throws NoSuchMethodException {
      super(item);
    }

    public void toggleCmdList(ActionEvent ae) {
      _cmdList.setCollapsed(!_cmdList.isCollapsed());
    }

    public void toggleShowThumbs(ActionEvent ae) {
      getCurrentCodePane().showThumbs = chkShowThumbs.getState();
      if (!getCurrentCodePane().reparse()) {
        chkShowThumbs.setState(!chkShowThumbs.getState());
        getCurrentCodePane().showThumbs = chkShowThumbs.getState();
      }
    }

    public void toggleUnitTest(ActionEvent ae) {
      if (_chkShowUnitTest.getState()) {
        _sidePane.setCollapsed(false);
      } else {
        _sidePane.setCollapsed(true);
      }
    }
  }
  //</editor-fold>

  //<editor-fold defaultstate="collapsed" desc="Init ToolMenu">
  private void initToolMenu() throws NoSuchMethodException {
    int scMask = Toolkit.getDefaultToolkit().getMenuShortcutKeyMask();
    _toolMenu.setMnemonic(java.awt.event.KeyEvent.VK_T);

    if (Settings.SikuliRepo != null) {
      _toolMenu.add(createMenuItem(_I("menuToolExtensions"),
              null,
              new ToolAction(ToolAction.EXTENSIONS)));
    }
  }

  class ToolAction extends MenuAction {

    static final String EXTENSIONS = "extensions";

    public ToolAction() {
      super();
    }

    public ToolAction(String item) throws NoSuchMethodException {
      super(item);
    }

    public void extensions(ActionEvent ae) {
      showExtensionsFrame();
    }
  }

  public void showExtensionsFrame() {
    String warn = "You might proceed, if you\n"
            + "- have some programming skills\n"
            + "- read the docs about extensions\n"
            + "- know what you are doing\n\n"
            + "Otherwise you should press Cancel!";
    String title = "Need your attention!";
    String[] options = new String[3];
    options[WARNING_DO_NOTHING] = "OK";
    options[WARNING_ACCEPTED] = "Be quiet!";
    options[WARNING_CANCEL] = "Cancel";
    int ret = JOptionPane.showOptionDialog(this, warn, title, 0, JOptionPane.WARNING_MESSAGE, null, options, options[2]);
    if (ret == WARNING_CANCEL || ret == JOptionPane.CLOSED_OPTION) {
      return;
    }
    if (ret == WARNING_ACCEPTED) {
      //TODO set prefs to be quiet on extensions warning
    };
    ExtensionManagerFrame extmg = ExtensionManagerFrame.getInstance();
    extmg.setVisible(true);
  }
  //</editor-fold>

  //<editor-fold defaultstate="collapsed" desc="Init Help Menu">
  private void initHelpMenu() throws NoSuchMethodException {
    int scMask = Toolkit.getDefaultToolkit().getMenuShortcutKeyMask();
    _helpMenu.setMnemonic(java.awt.event.KeyEvent.VK_H);

    _helpMenu.add(createMenuItem(_I("menuHelpQuickStart"),
            null, new HelpAction(HelpAction.QUICK_START)));
    _helpMenu.addSeparator();

    _helpMenu.add(createMenuItem(_I("menuHelpGuide"),
            null, new HelpAction(HelpAction.OPEN_DOC)));
    _helpMenu.add(createMenuItem(_I("menuHelpDocumentations"),
            null, new HelpAction(HelpAction.OPEN_GUIDE)));
    _helpMenu.add(createMenuItem(_I("menuHelpFAQ"),
            null, new HelpAction(HelpAction.OPEN_FAQ)));
    _helpMenu.add(createMenuItem(_I("menuHelpAsk"),
            null, new HelpAction(HelpAction.OPEN_ASK)));
    _helpMenu.add(createMenuItem(_I("menuHelpBugReport"),
            null, new HelpAction(HelpAction.OPEN_BUG_REPORT)));

//    _helpMenu.add(createMenuItem(_I("menuHelpTranslation"),
//            null, new HelpAction(HelpAction.OPEN_TRANSLATION)));
    _helpMenu.addSeparator();
    _helpMenu.add(createMenuItem(_I("menuHelpHomepage"),
            null, new HelpAction(HelpAction.OPEN_HOMEPAGE)));

    _helpMenu.addSeparator();
    _helpMenu.add(createMenuItem(_I("menuHelpCheckUpdate"),
            null, new HelpAction(HelpAction.CHECK_UPDATE)));
  }

  class HelpAction extends MenuAction {

    static final String CHECK_UPDATE = "doCheckUpdate";
    static final String QUICK_START = "openQuickStart";
    static final String OPEN_DOC = "openDoc";
    static final String OPEN_GUIDE = "openTutor";
    static final String OPEN_FAQ = "openFAQ";
    static final String OPEN_ASK = "openAsk";
    static final String OPEN_BUG_REPORT = "openBugReport";
    static final String OPEN_TRANSLATION = "openTranslation";
    static final String OPEN_HOMEPAGE = "openHomepage";

    public HelpAction() {
      super();
    }

    public HelpAction(String item) throws NoSuchMethodException {
      super(item);
    }

    public void openQuickStart(ActionEvent ae) {
      FileManager.openURL("http://sikulix.com");
    }

    public void openDoc(ActionEvent ae) {
      FileManager.openURL("http://doc.sikuli.org");
    }

    public void openTutor(ActionEvent ae) {
      FileManager.openURL("http://www.sikuli.org/videos.html");
    }

    public void openFAQ(ActionEvent ae) {
      FileManager.openURL("https://answers.launchpad.net/sikuli/+faqs");
    }

    public void openAsk(ActionEvent ae) {
      FileManager.openURL("https://answers.launchpad.net/sikuli");
    }

    public void openBugReport(ActionEvent ae) {
      FileManager.openURL("https://bugs.launchpad.net/sikuli/+filebug");
    }

    public void openTranslation(ActionEvent ae) {
      FileManager.openURL("https://translations.launchpad.net/sikuli/sikuli-x/+translations");
    }

    public void openHomepage(ActionEvent ae) {
      FileManager.openURL("http://sikulix.com");
    }

    public void doCheckUpdate(ActionEvent ae) {
      if (!checkUpdate(false)) {
        JOptionPane.showMessageDialog(null,
                _I("msgNoUpdate"), SikuliIDESettings.SikuliVersion,
                JOptionPane.INFORMATION_MESSAGE);
      }
    }

    public boolean checkUpdate(boolean isAutoCheck) {
      JFrame f = null;
      String ver = "";
      String details;
      AutoUpdater au = new AutoUpdater();
      if (!isAutoCheck) {
//TODO replace this hack: wait update check
        f = au.showUpdateFrame("Checking for new version ... please wait!",
                "Checking for new version ... please wait! Checking for new version ... please wait!", -1);
      }
      PreferencesUser pref = PreferencesUser.getInstance();
      Debug.log(3, "being asked to check update");
      int whatUpdate = au.checkUpdate();
      if (f != null) {
        f.dispose();
      }
      if (whatUpdate >= AutoUpdater.SOMEBETA) {
//TODO add Prefs wantBeta check
        whatUpdate -= AutoUpdater.SOMEBETA;
      }
      if (whatUpdate > 0) {
        if (whatUpdate == AutoUpdater.BETA) {
          ver = au.getBeta();
          details = au.getBetaDetails();
        } else {
          ver = au.getVersion();
          details = au.getDetails();
        }
        if (isAutoCheck && pref.getLastSeenUpdate().equals(ver)) {
          return false;
        }
        au.showUpdateFrame(_I("dlgUpdateAvailable", ver), details, whatUpdate);
        PreferencesUser.getInstance().setLastSeenUpdate(ver);
        return true;
      }
      return false;
    }
  }

  private void initMenuBars(JFrame frame) {
    try {
      initFileMenu();
      initEditMenu();
      initRunMenu();
      initViewMenu();
      initToolMenu();
      initHelpMenu();
    } catch (NoSuchMethodException e) {
      log(-1, "Problem when initializing menues\nError: %s", e.getMessage());
    }

    _menuBar.add(_fileMenu);
    _menuBar.add(_editMenu);
    _menuBar.add(_runMenu);
    _menuBar.add(_viewMenu);
    _menuBar.add(_toolMenu);
    _menuBar.add(_helpMenu);
    frame.setJMenuBar(_menuBar);
  }

  //<editor-fold defaultstate="collapsed" desc="Init LeftBar Commands">
  private String[] getCommandCategories() {
    String[] CommandCategories = {
      _I("cmdListFind"),
      _I("cmdListMouse"),
      _I("cmdListKeyboard"),
      _I("cmdListObserver")
    };
    return CommandCategories;
  }

  private String[][] getCommandsOnToolbar() {
    String[][] CommandsOnToolbar = {
      {"find"}, {"PATTERN"},
      {_I("cmdFind")},
      {"findAll"}, {"PATTERN"},
      {_I("cmdFindAll")},
      {"wait"}, {"PATTERN", "[timeout]"},
      {_I("cmdWait")},
      {"waitVanish"}, {"PATTERN", "[timeout]"},
      {_I("cmdWaitVanish")},
      {"exists"}, {"PATTERN", "[timeout]"},
      {_I("cmdExists")},
      {"----"}, {}, {},
      {"click"}, {"PATTERN", "[modifiers]"},
      {_I("cmdClick")},
      {"doubleClick"}, {"PATTERN", "[modifiers]"},
      {_I("cmdDoubleClick")},
      {"rightClick"}, {"PATTERN", "[modifiers]"},
      {_I("cmdRightClick")},
      {"hover"}, {"PATTERN"},
      {_I("cmdHover")},
      {"dragDrop"}, {"PATTERN", "PATTERN", "[modifiers]"},
      {_I("cmdDragDrop")},
      /* RaiMan not used
       * {"drag"}, {"PATTERN"},
       * {"dropAt"}, {"PATTERN", "[delay]"},
       * RaiMan not used */
      {"----"}, {}, {},
      {"type"}, {"_text", "[modifiers]"},
      {_I("cmdType")},
      {"type"}, {"PATTERN", "_text", "[modifiers]"},
      {_I("cmdType2")},
      {"paste"}, {"_text", "[modifiers]"},
      {_I("cmdPaste")},
      {"paste"}, {"PATTERN", "_text", "[modifiers]"},
      {_I("cmdPaste2")},
      {"----"}, {}, {},
      {"onAppear"}, {"PATTERN", "_hnd"},
      {_I("cmdOnAppear")},
      {"onVanish"}, {"PATTERN", "_hnd"},
      {_I("cmdOnVanish")},
      {"onChange"}, {"_hnd"},
      {_I("cmdOnChange")},
      {"observe"}, {"[time]", "[background]"},
      {_I("cmdObserve")},};
    return CommandsOnToolbar;
  }

  private JComponent createCommandPane() {
    JXTaskPaneContainer con = new JXTaskPaneContainer();

    PreferencesUser pref = PreferencesUser.getInstance();
    JCheckBox chkAutoCapture
            = new JCheckBox(_I("cmdListAutoCapture"),
                    pref.getAutoCaptureForCmdButtons());
    chkAutoCapture.addChangeListener(new ChangeListener() {
      @Override
      public void stateChanged(javax.swing.event.ChangeEvent e) {
        boolean flag = ((JCheckBox) e.getSource()).isSelected();
        PreferencesUser pref = PreferencesUser.getInstance();
        pref.setAutoCaptureForCmdButtons(flag);
      }
    });
    JXTaskPane setPane = new JXTaskPane();
    setPane.setTitle(_I("cmdListSettings"));
    setPane.add(chkAutoCapture);
    setPane.setCollapsed(true);
    con.add(setPane);
    int cat = 0;
    JXTaskPane taskPane = new JXTaskPane();
    taskPane.setTitle(getCommandCategories()[cat++]);
    con.add(taskPane);
    String[][] CommandsOnToolbar = getCommandsOnToolbar();
    boolean collapsed;
    for (int i = 0; i < CommandsOnToolbar.length; i++) {
      String cmd = CommandsOnToolbar[i++][0];
      String[] params = CommandsOnToolbar[i++];
      String[] desc = CommandsOnToolbar[i];
//TODO: more elegeant way, to handle special cases
      if (cmd.equals("----")) {
        if (cat == 2) {
          collapsed = true;
        } else {
          collapsed = false;
        }
        if (cat == 3) {
          if (prefs.getUserType() == PreferencesUser.NEWBEE) {
            break;
          } else {
            collapsed = true;
          }
        }
        taskPane = new JXTaskPane();
        taskPane.setTitle(getCommandCategories()[cat++]);
        con.add(taskPane);
        taskPane.setCollapsed(collapsed);
      } else {
        taskPane.add(new ButtonGenCommand(cmd, desc[0], params));
      }
    }
    Dimension conDim = con.getSize();
    con.setPreferredSize(new Dimension(250, 1000));
    _cmdList = new JXCollapsiblePane(JXCollapsiblePane.Direction.LEFT);
    _cmdList.setMinimumSize(new Dimension(0, 0));
    _cmdList.add(new JScrollPane(con));
    _cmdList.setCollapsed(false);
    return _cmdList;
  }

  //<editor-fold defaultstate="collapsed" desc="RaiMan obsolete">
  private JToolBar initCmdToolbar() {
    JToolBar toolbar = new JToolBar(JToolBar.VERTICAL);
    toolbar.add(createCommandPane());
    return toolbar;
  }
  //</editor-fold>
  //</editor-fold>

  //<editor-fold defaultstate="collapsed" desc="Init ToolBar Buttons">
  private JToolBar initToolbar() {
    if (ENABLE_UNIFIED_TOOLBAR) {
      MacUtils.makeWindowLeopardStyle(this.getRootPane());
    }

    JToolBar toolbar = new JToolBar();
    JButton btnInsertImage = new ButtonInsertImage();
    _btnCapture = new ButtonCapture();
    JButton btnSubregion = new ButtonSubregion();
    toolbar.add(_btnCapture);
    toolbar.add(btnInsertImage);
    toolbar.add(btnSubregion);
    toolbar.add(Box.createHorizontalGlue());
    /* RaiMan not used
     * if( ENABLE_RECORDING ){
     * JToggleButton btnRecord = new ButtonRecord();
     * toolbar.add(btnRecord);
     * }
     * RaiMan not used */
    _btnRun = new ButtonRun();
    toolbar.add(_btnRun);
    _btnRunViz = new ButtonRunViz();
    toolbar.add(_btnRunViz);
    toolbar.add(Box.createHorizontalGlue());
    toolbar.add(createSearchField());
    toolbar.add(Box.createRigidArea(new Dimension(7, 0)));
    toolbar.setFloatable(false);
    //toolbar.setMargin(new Insets(0, 0, 0, 5));
    return toolbar;
  }

  class ButtonInsertImage extends ButtonOnToolbar implements ActionListener {

    public ButtonInsertImage() {
      super();
      URL imageURL = SikuliIDE.class.getResource("/icons/insert-image-icon.png");
      setIcon(new ImageIcon(imageURL));
      setText(SikuliIDE._I("btnInsertImageLabel"));
      //setMaximumSize(new Dimension(26,26));
      setToolTipText(SikuliIDE._I("btnInsertImageHint"));
      addActionListener(this);
    }

    @Override
    public void actionPerformed(ActionEvent ae) {
      EditorPane codePane = SikuliIDE.getInstance().getCurrentCodePane();
      File file = new SikuliIDEFileChooser(SikuliIDE.getInstance()).loadImage();
      if (file == null) {
        return;
      }
      String path = FileManager.slashify(file.getAbsolutePath(), false);
      Debug.info("load image: " + path);
      EditorPatternButton icon;
      String img = codePane.copyFileToBundle(path).getAbsolutePath();
      if (prefs.getDefaultThumbHeight() > 0) {
        icon = new EditorPatternButton(codePane, img);
        codePane.insertComponent(icon);
      } else {
        codePane.insertString("\"" + (new File(img)).getName() + "\"");
      }
    }
  }

  class ButtonSubregion extends ButtonOnToolbar implements ActionListener, EventObserver {

    public ButtonSubregion() {
      super();
      URL imageURL = SikuliIDE.class.getResource("/icons/region-icon.png");
      setIcon(new ImageIcon(imageURL));
      setText(SikuliIDE._I("btnRegionLabel"));
      //setMaximumSize(new Dimension(26,26));
      setToolTipText(SikuliIDE._I("btnRegionHint"));
      addActionListener(this);
    }

    @Override
    public void actionPerformed(ActionEvent ae) {
      SikuliIDE ide = SikuliIDE.getInstance();
      EditorPane codePane = ide.getCurrentCodePane();
      ide.setVisible(false);
      OverlayCapturePrompt prompt = new OverlayCapturePrompt(null, this);
      prompt.prompt(SikuliIDE._I("msgCapturePrompt"), 500);
    }

    @Override
    public void update(EventSubject s) {
      if (s instanceof OverlayCapturePrompt) {
        complete((OverlayCapturePrompt) s);
      }
    }

    public void complete(OverlayCapturePrompt cp) {
      int x, y, w, h;
      SikuliIDE ide = SikuliIDE.getInstance();
      EditorPane codePane = ide.getCurrentCodePane();
      ScreenImage r = cp.getSelection();
      cp.close();
      if (r != null) {
        Rectangle roi = r.getROI();
        x = (int) roi.getX();
        y = (int) roi.getY();
        w = (int) roi.getWidth();
        h = (int) roi.getHeight();
        ide.setVisible(false);
        if (codePane.showThumbs) {
          if (prefs.getPrefMoreImageThumbs()) {
            codePane.insertComponent(new EditorRegionButton(codePane, x, y, w, h));
          } else {
            codePane.insertComponent(new EditorRegionLabel(codePane,
                    new EditorRegionButton(codePane, x, y, w, h).toString()));
          }
        } else {
          codePane.insertString(codePane.getRegionString(x, y, w, h));
        }
      }
      ide.setVisible(true);
      codePane.requestFocus();
    }
  }

  class ButtonRun extends ButtonOnToolbar implements ActionListener {

    private Thread _runningThread = null;

    public ButtonRun() {
      super();

      URL imageURL = SikuliIDE.class.getResource("/icons/run_big_green.png");
      setIcon(new ImageIcon(imageURL));
      initTooltip();
      addActionListener(this);
      setText(_I("btnRunLabel"));
      //setMaximumSize(new Dimension(45,45));
    }

    @Override
    public void actionPerformed(ActionEvent ae) {
      runCurrentScript();
    }

    public void runCurrentScript() {
      SikuliIDE.getStatusbar().setMessage("... PLEASE WAIT ... checking IDE state before running script");
      SikuliIDE ide = SikuliIDE.getInstance();
      if (ideIsRunningScript || !ide.doBeforeRun()) {
        return;
      }
      SikuliIDE.getStatusbar().resetMessage();
      ide.setVisible(false);
      if (ide.firstRun) {
        SikuliX.displaySplashFirstTime(new String[0]);
        ide.firstRun = false;
      }
      ide.setIsRunningScript(true);
      _runningThread = new Thread() {
        @Override
        public void run() {
          EditorPane codePane = SikuliIDE.getInstance().getCurrentCodePane();
          File tmpFile;
          tmpFile = FileManager.createTempFile(Settings.EndingTypes.get(codePane.getSikuliContentType()));
          if (tmpFile == null) {
            return;
          }
          try {
            BufferedWriter bw = new BufferedWriter(
                    new OutputStreamWriter(
                            new FileOutputStream(tmpFile),
                            "UTF8"));
            codePane.write(bw);
            //SikuliIDE.getInstance().setVisible(false);
            _console.clear();
            resetErrorMark();
            runScript(tmpFile, codePane);
          } catch (Exception e) {
          } finally {
            SikuliIDE.getInstance().setIsRunningScript(false);
            SikuliIDE.getInstance().setVisible(true);
            _runningThread = null;
            SikuliX.cleanUp(0);
          }
        }
      };
      _runningThread.start();
    }

    private void runScript(File sfile, EditorPane pane) throws Exception {
      File path = new File(SikuliIDE.getInstance().getCurrentBundlePath());
      File parent = path.getParentFile();
      //TODO implement alternative script types
      String runnerType = null;
      String cType = pane.getContentType();
      runnerType = cType.equals(Settings.CPYTHON) ? Settings.RPYTHON : Settings.RRUBY;
      IScriptRunner srunner = SikuliX.getScriptRunner(runnerType, null, Settings.getArgs());
      if (srunner == null) {
        Debug.error("Could not load a script runner for: %s (%s)", cType, runnerType);
        return;
      }
      addScriptCode(srunner);
      try {
        ImagePath.resetBundlePath(path.getAbsolutePath());
        int ret = srunner.runScript(sfile, path,
                Settings.getArgs(),
                new String[]{parent.getAbsolutePath(),
                  _mainPane.getTitleAt(_mainPane.getSelectedIndex())});
        addErrorMark(ret);
        srunner.close();
      } catch (Exception e) {
        srunner.close();
        throw e;
      }
    }

    protected void addScriptCode(IScriptRunner srunner) {
      srunner.execBefore(null);
      srunner.execBefore(new String[]{"Settings.setShowActions(Settings.FALSE)"});
    }

    public void stopRunning() {
      if (_runningThread != null) {
        _runningThread.interrupt();
        _runningThread.stop();
      }
    }

    private void initTooltip() {
      PreferencesUser pref = PreferencesUser.getInstance();
      String strHotkey = Key.convertKeyToText(
              pref.getStopHotkey(), pref.getStopHotkeyModifiers());
      String stopHint = _I("btnRunStopHint", strHotkey);
      setToolTipText(_I("btnRun", stopHint));
    }

    public void addErrorMark(int line) {
      if (line < 0) {
        line *= -1;
      } else {
        return;
      }
      JScrollPane scrPane = (JScrollPane) _mainPane.getSelectedComponent();
      EditorLineNumberView lnview = (EditorLineNumberView) (scrPane.getRowHeader().getView());
      lnview.addErrorMark(line);
      EditorPane codePane = SikuliIDE.this.getCurrentCodePane();
      codePane.jumpTo(line);
      codePane.requestFocus();
    }

    public void resetErrorMark() {
      JScrollPane scrPane = (JScrollPane) _mainPane.getSelectedComponent();
      EditorLineNumberView lnview = (EditorLineNumberView) (scrPane.getRowHeader().getView());
      lnview.resetErrorMark();
    }
  }

  class ButtonRunViz extends ButtonRun {

    public ButtonRunViz() {
      super();
      URL imageURL = SikuliIDE.class.getResource("/icons/run_big_yl.png");
      setIcon(new ImageIcon(imageURL));
      setToolTipText(_I("menuRunRunAndShowActions"));
      setText(_I("btnRunSlowMotionLabel"));
    }

    @Override
    protected void addScriptCode(IScriptRunner srunner) {
      srunner.execBefore(null);
      srunner.execBefore(new String[]{"Settings.setShowActions(Settings.TRUE)"});
    }
  }

  public String getCurrentBundlePath() {
    EditorPane pane = getCurrentCodePane();
    return pane.getSrcBundle();
  }

  private JComponent createSearchField() {
    _searchField = new JXSearchField("Find");
    _searchField.setUseNativeSearchFieldIfPossible(true);
    //_searchField.setLayoutStyle(JXSearchField.LayoutStyle.MAC);
    _searchField.setMinimumSize(new Dimension(220, 30));
    _searchField.setPreferredSize(new Dimension(220, 30));
    _searchField.setMaximumSize(new Dimension(380, 30));
    _searchField.setMargin(new Insets(0, 3, 0, 3));
    _searchField.setToolTipText("Search is case sensitive - "
            + "start with ! to make search not case sensitive");

    _searchField.setCancelAction(new ActionListener() {
      @Override
      public void actionPerformed(ActionEvent evt) {
        getCurrentCodePane().requestFocus();
        _findHelper.setFailed(false);
      }
    });
    _searchField.setFindAction(new ActionListener() {
      @Override
      public void actionPerformed(ActionEvent evt) {
        //FIXME: On Linux the found selection disappears somehow
        if (!Settings.isLinux()) //HACK
        {
          _searchField.selectAll();
        }
        boolean ret = _findHelper.findNext(_searchField.getText());
        _findHelper.setFailed(!ret);
      }
    });
    _searchField.addKeyListener(new KeyAdapter() {
      @Override
      public void keyReleased(java.awt.event.KeyEvent ke) {
        boolean ret;
        if (ke.getKeyCode() == java.awt.event.KeyEvent.VK_ENTER) {
          //FIXME: On Linux the found selection disappears somehow
          if (!Settings.isLinux()) //HACK
          {
            _searchField.selectAll();
          }
          ret = _findHelper.findNext(_searchField.getText());
        } else {
          ret = _findHelper.findStr(_searchField.getText());
        }
        _findHelper.setFailed(!ret);
      }
    });
    return _searchField;
  }
  //</editor-fold>

  private void initTabPane() {
    _mainPane = new CloseableTabbedPane();
    _mainPane.setUI(new AquaCloseableTabbedPaneUI());
    _mainPane.addCloseableTabbedPaneListener(
            new CloseableTabbedPaneListener() {
              @Override
              public boolean closeTab(int i) {
                EditorPane codePane;
                try {
                  JScrollPane scrPane = (JScrollPane) _mainPane.getComponentAt(i);
                  codePane = (EditorPane) scrPane.getViewport().getView();
                  _mainPane.setLastClosed(codePane.getSrcBundle());
                  Debug.log(4, "close tab " + i + " n:" + _mainPane.getComponentCount());
                  boolean ret = codePane.close();
                  Debug.log(4, "after close tab n:" + _mainPane.getComponentCount());
                  if (ret && _mainPane.getTabCount() < 2) {
                    (new FileAction()).doNew(null);
                  }
                  return ret;
                } catch (Exception e) {
                  log(-1, "Problem closing tab %d\nError: %s", i, e.getMessage());
                  return false;
                }
              }
            });

    _mainPane.addChangeListener(new ChangeListener() {
      @Override
      public void stateChanged(javax.swing.event.ChangeEvent e) {
        EditorPane codePane = null;
        JTabbedPane tab = (JTabbedPane) e.getSource();
        int i = tab.getSelectedIndex();
        if (i >= 0) {
          JScrollPane scrPane = (JScrollPane) _mainPane.getComponentAt(i);
          codePane = (EditorPane) scrPane.getViewport().getView();
          String fname = codePane.getCurrentSrcDir();
          if (fname == null) {
            SikuliIDE.this.setTitle(tab.getTitleAt(i));
          } else {
            ImagePath.setBundlePath(fname);
            SikuliIDE.this.setTitle(fname);
          }
          SikuliIDE.this.chkShowThumbs.setState(SikuliIDE.this.getCurrentCodePane().showThumbs);
        }
        updateUndoRedoStates();
        if (codePane != null) {
          SikuliIDE.getStatusbar().setCurrentContentType(
                  SikuliIDE.this.getCurrentCodePane().getSikuliContentType());
        }
      }
    });

  }

  private void initMsgPane(boolean atBottom) {
    msgPane = new JTabbedPane();
    _console = new EditorConsolePane();
    msgPane.addTab(_I("paneMessage"), null, _console, "DoubleClick to hide/unhide");
    if (Settings.isWindows() || Settings.isLinux()) {
      msgPane.setBorder(BorderFactory.createEmptyBorder(5, 8, 5, 8));
    }
    msgPane.addMouseListener(new MouseListener() {
      @Override
      public void mouseClicked(MouseEvent me) {
        if (me.getClickCount() < 2) {
          return;
        }
        if (msgPaneCollapsed) {
          _mainSplitPane.setDividerLocation(_mainSplitPane.getLastDividerLocation());
          msgPaneCollapsed = false;
        } else {
          int pos = _mainSplitPane.getWidth() - 35;
          if (prefs.getPrefMoreMessage() == PreferencesUser.HORIZONTAL) {
            pos = _mainSplitPane.getHeight() - 35;
          }
          _mainSplitPane.setDividerLocation(pos);
          msgPaneCollapsed = true;
        }
      }
      //<editor-fold defaultstate="collapsed" desc="mouse events not used">

      @Override
      public void mousePressed(MouseEvent me) {
      }

      @Override
      public void mouseReleased(MouseEvent me) {
      }

      @Override
      public void mouseEntered(MouseEvent me) {
      }

      @Override
      public void mouseExited(MouseEvent me) {
      }
      //</editor-fold>
    });
  }

  public Container getMsgPane() {
    return msgPane;
  }

  private SikuliIDEStatusBar initStatusbar() {
    _status = new SikuliIDEStatusBar();
    return _status;
  }

  public static SikuliIDEStatusBar getStatusbar() {
    if (_instance == null) {
      return null;
    } else {
      return _instance._status;
    }
  }

  private void initWindowListener() {
    setDefaultCloseOperation(WindowConstants.DO_NOTHING_ON_CLOSE);
    addWindowListener(new WindowAdapter() {
      @Override
      public void windowClosing(WindowEvent e) {
        SikuliIDE.this.quit();
      }
    });
    addComponentListener(new ComponentAdapter() {
      @Override
      public void componentResized(ComponentEvent e) {
        PreferencesUser.getInstance().setIdeSize(SikuliIDE.this.getSize());
      }

      @Override
      public void componentMoved(ComponentEvent e) {
        PreferencesUser.getInstance().setIdeLocation(SikuliIDE.this.getLocation());
      }
    });
  }

  private void initTooltip() {
    ToolTipManager tm = ToolTipManager.sharedInstance();
    tm.setDismissDelay(30000);
  }

  //<editor-fold defaultstate="collapsed" desc="Init ShortCuts HotKeys">
  private void nextTab() {
    int i = _mainPane.getSelectedIndex();
    int next = (i + 1) % _mainPane.getTabCount();
    _mainPane.setSelectedIndex(next);
  }

  private void prevTab() {
    int i = _mainPane.getSelectedIndex();
    int prev = (i - 1 + _mainPane.getTabCount()) % _mainPane.getTabCount();
    _mainPane.setSelectedIndex(prev);
  }

  private void initShortcutKeys() {
    final int scMask = Toolkit.getDefaultToolkit().getMenuShortcutKeyMask();
    Toolkit.getDefaultToolkit().addAWTEventListener(new AWTEventListener() {
      private boolean isKeyNextTab(java.awt.event.KeyEvent ke) {
        if (ke.getKeyCode() == java.awt.event.KeyEvent.VK_TAB
                && ke.getModifiers() == InputEvent.CTRL_MASK) {
          return true;
        }
        if (ke.getKeyCode() == java.awt.event.KeyEvent.VK_CLOSE_BRACKET
                && ke.getModifiers() == (InputEvent.META_MASK | InputEvent.SHIFT_MASK)) {
          return true;
        }
        return false;
      }

      private boolean isKeyPrevTab(java.awt.event.KeyEvent ke) {
        if (ke.getKeyCode() == java.awt.event.KeyEvent.VK_TAB
                && ke.getModifiers() == (InputEvent.CTRL_MASK | InputEvent.SHIFT_MASK)) {
          return true;
        }
        if (ke.getKeyCode() == java.awt.event.KeyEvent.VK_OPEN_BRACKET
                && ke.getModifiers() == (InputEvent.META_MASK | InputEvent.SHIFT_MASK)) {
          return true;
        }
        return false;
      }

      public void eventDispatched(AWTEvent e) {
        java.awt.event.KeyEvent ke = (java.awt.event.KeyEvent) e;
        //Debug.log(ke.toString());
        if (ke.getID() == java.awt.event.KeyEvent.KEY_PRESSED) {
          if (isKeyNextTab(ke)) {
            nextTab();
          } else if (isKeyPrevTab(ke)) {
            prevTab();
          }
        }
      }
    }, AWTEvent.KEY_EVENT_MASK);

  }

  public void removeCaptureHotkey(int key, int mod) {
    HotkeyManager.getInstance()._removeHotkey(key, mod);
  }

  public void installCaptureHotkey(int key, int mod) {
    HotkeyManager.getInstance()._addHotkey(key, mod, new HotkeyListener() {
      @Override
      public void hotkeyPressed(HotkeyEvent e) {
        if (!SikuliIDE.getInstance().isRunningScript()) {
          onQuickCapture();
        }
      }
    });
  }

  public void onQuickCapture() {
    onQuickCapture(null);
  }

  public void onQuickCapture(String arg) {
    Debug.log(3, "QuickCapture");
    _btnCapture.capture(0);
  }

  public void removeStopHotkey(int key, int mod) {
    HotkeyManager.getInstance()._removeHotkey(key, mod);
  }

  public void installStopHotkey(int key, int mod) {
    HotkeyManager.getInstance()._addHotkey(key, mod, new HotkeyListener() {
      @Override
      public void hotkeyPressed(HotkeyEvent e) {
        onStopRunning();
      }
    });
  }

  public void onStopRunning() {
    Debug.log(3, "StopRunning after AbortKey");
    _btnRun.stopRunning();
    _btnRunViz.stopRunning();
    org.sikuli.script.SikuliX.cleanUp(-1);
    this.setVisible(true);
  }

  private void initHotkeys() {
    PreferencesUser pref = PreferencesUser.getInstance();
    int key = pref.getCaptureHotkey();
    int mod = pref.getCaptureHotkeyModifiers();
    installCaptureHotkey(key, mod);

    key = pref.getStopHotkey();
    mod = pref.getStopHotkeyModifiers();
    installStopHotkey(key, mod);
  }
  //</editor-fold>

	//<editor-fold defaultstate="collapsed" desc="IDE Unit Testing --- RaiMan not used">
  /*
   private void initSidePane() {
   initUnitPane();
   _sidePane = new JXCollapsiblePane(JXCollapsiblePane.Direction.RIGHT);
   _sidePane.setMinimumSize(new Dimension(0, 0));
   CloseableTabbedPane tabPane = new CloseableTabbedPane();
   _sidePane.getContentPane().add(tabPane);
   tabPane.setMinimumSize(new Dimension(0, 0));
   tabPane.addTab(_I("tabUnitTest"), _unitPane);
   tabPane.addCloseableTabbedPaneListener(new CloseableTabbedPaneListener() {
   @Override
   public boolean closeTab(int tabIndexToClose) {
   _sidePane.setCollapsed(true);
   _chkShowUnitTest.setState(false);
   return false;
   }
   });
   _sidePane.setCollapsed(true);
   }

   private void initUnitPane() {
   _testRunner = new UnitTestRunner();
   _unitPane = _testRunner.getPanel();
   _chkShowUnitTest.setState(false);
   addAuxTab(_I("paneTestTrace"), _testRunner.getTracePane());
   }
   */
  public void addAuxTab(String tabName, JComponent com) {
    msgPane.addTab(tabName, com);
  }

  public void jumpTo(String funcName) throws BadLocationException {
    EditorPane pane = SikuliIDE.getInstance().getCurrentCodePane();
    pane.jumpTo(funcName);
    pane.grabFocus();
  }

  public void jumpTo(int lineNo) throws BadLocationException {
    EditorPane pane = SikuliIDE.getInstance().getCurrentCodePane();
    pane.jumpTo(lineNo);
    pane.grabFocus();
  }
  //</editor-fold>
}
