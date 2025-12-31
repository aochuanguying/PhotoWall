package com.hxfssc.activity;

import android.animation.Animator;
import android.animation.AnimatorSet;
import android.animation.ObjectAnimator;
import android.app.Activity;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils; // Restore this import
import android.util.Log;
import android.view.KeyEvent;
import android.view.View;
import android.view.WindowManager;
import android.view.animation.AccelerateDecelerateInterpolator;
import android.widget.AdapterView; // Add this
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.util.DisplayMetrics;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.multidex.MultiDex;
import android.content.Context;
import android.content.pm.PackageManager; // Add this
import android.os.Environment; // Add this

import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import jcifs.CIFSContext;
import jcifs.config.PropertyConfiguration;
import jcifs.context.BaseContext;
import jcifs.smb.NtlmPasswordAuthenticator;
import jcifs.smb.SmbFile;

/**
 * MainActivity
 * 
 * Android TV PhotoWall 主程序
 * 核心功能：
 * 1. 支持 SMB (局域网共享) 和 本地存储 图片源
 * 2. 双缓冲机制实现无缝图片切换
 * 3. 多种展示风格：Ken Burns (平移缩放)、淡入淡出、滑动切换
 * 4. 针对 Android TV 遥控器优化的交互 (Dialog 模式选择框)
 * 5. 自动缓存管理与容错机制 (自动跳过损坏图片)
 */
public class MainActivity extends Activity {

    @Override
    protected void attachBaseContext(Context base) {
        super.attachBaseContext(base);
        MultiDex.install(this);
    }

    private static final String TAG = "PhotoWallApp";
    private static final String PREFS_NAME = "PhotoWallPrefs";
    private static final String KEY_SETTINGS = "settings_json";
    private static final List<String> SUPPORTED_EXTENSIONS = Arrays.asList(
            ".jpg", ".jpeg", ".png", ".gif", ".bmp", ".webp"
    );

    // UI Components
    private ImageView photoView1, photoView2;
    private TextView infoText;
    private ScrollView settingsOverlay;
    private LinearLayout refreshMenuOverlay; // 新增刷新菜单
    
    // Refresh Menu UI
    private Button btnIncrementalRefresh, btnFullRefresh, btnOpenSettings, btnCloseMenu;

    // Settings UI
    private EditText inputHost, inputUser, inputPass, inputInterval;
    private Button btnStyleSelect, btnSourceTypeSelect;
    private LinearLayout containerSmbConfig;
    private TextView textCurrentPath, textDirStatus, textConnTitle;
    private LinearLayout dirListContainer;
    private Button btnConnect, btnSave, btnExit;

    // Data for Selection Dialogs
    private final String[] styles = new String[]{"Ken Burns (推荐)", "仅淡入淡出", "滑动切换"};
    private final String[] sourceTypes = new String[]{"SMB 网络共享", "本地存储"};

    // State
    private final ExecutorService executor = Executors.newFixedThreadPool(4);
    private final Handler handler = new Handler(Looper.getMainLooper());
    private Future<?> refreshFuture = null; // 用于追踪和取消当前的刷新任务
    private volatile boolean isRefreshCancelled = false; // 标志位，用于在任务内部检查是否被取消
    private volatile boolean isLoading = false; // 防止 loadNextPhoto 并发调用
    private AnimatorSet currentAnimator = null; // 追踪当前动画，用于取消
    private JSONObject currentSettings = null;
    private long lastBackPressTime = 0; // 记录上次按返回键的时间
    
    private List<String> allPhotoFiles = new ArrayList<>();
    private List<Bitmap> loadedBitmaps = new ArrayList<>();
    private int currentPhotoIndex = 0;
    private boolean isPlaying = false;
    private boolean isSettingsOpen = false;
    private ImageView activeView, inactiveView;
    private Runnable playbackRunnable;
    private long playbackInterval = 5000;
    private boolean isInitialLoad = true;
    private int currentStyle = 0; // 0: Ken Burns, 1: Fade Only, 2: Slide
    private int currentSourceType = 0; // 0: SMB, 1: Local

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        
        // Android 4.4 Immersive Mode
        if (Build.VERSION.SDK_INT >= 19) {
            getWindow().getDecorView().setSystemUiVisibility(
                    View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY |
                    View.SYSTEM_UI_FLAG_FULLSCREEN |
                    View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
            );
        }

        // 使用正确的包名引用 R 类
        setContentView(com.hxfssc.activity.R.layout.activity_main);
        
        initViews();
        loadSettings();
        cleanCache();
        checkPermissions();
    }

    private void checkPermissions() {
        if (Build.VERSION.SDK_INT >= 23) {
            if (checkSelfPermission(android.Manifest.permission.READ_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
                requestPermissions(new String[]{android.Manifest.permission.READ_EXTERNAL_STORAGE, android.Manifest.permission.WRITE_EXTERNAL_STORAGE}, 100);
            }
        }
    }

    private void initViews() {
        photoView1 = findViewById(R.id.photo_view_1);
        photoView2 = findViewById(R.id.photo_view_2);
        infoText = findViewById(com.hxfssc.activity.R.id.info_text);
        settingsOverlay = findViewById(com.hxfssc.activity.R.id.settings_overlay);
        refreshMenuOverlay = findViewById(com.hxfssc.activity.R.id.refresh_menu_overlay);

        // 动态计算并设置自适应宽度
        DisplayMetrics metrics = new DisplayMetrics();
        getWindowManager().getDefaultDisplay().getMetrics(metrics);
        int screenWidth = metrics.widthPixels;
        
        // 菜单宽度：屏幕宽度的 35%，最小 300dp，最大 500dp
        int menuWidth = (int)(screenWidth * 0.35f);
        if (menuWidth < dpToPx(300)) menuWidth = dpToPx(300);
        if (menuWidth > dpToPx(500)) menuWidth = dpToPx(500);
        
        ViewGroup.LayoutParams menuParams = refreshMenuOverlay.getLayoutParams();
        menuParams.width = menuWidth;
        refreshMenuOverlay.setLayoutParams(menuParams);
        
        // 设置宽度：屏幕宽度的 45%，最小 400dp，最大 600dp
        int settingsWidth = (int)(screenWidth * 0.45f);
        if (settingsWidth < dpToPx(400)) settingsWidth = dpToPx(400);
        if (settingsWidth > dpToPx(600)) settingsWidth = dpToPx(600);
        
        ViewGroup.LayoutParams settingsParams = settingsOverlay.getLayoutParams();
        settingsParams.width = settingsWidth;
        // 高度自适应，但留出边距
        if (settingsParams instanceof FrameLayout.LayoutParams) {
            ((FrameLayout.LayoutParams)settingsParams).topMargin = dpToPx(20);
            ((FrameLayout.LayoutParams)settingsParams).bottomMargin = dpToPx(20);
        }
        settingsOverlay.setLayoutParams(settingsParams);
        
        // 刷新菜单按钮
        btnIncrementalRefresh = findViewById(com.hxfssc.activity.R.id.btn_incremental_refresh);
        btnFullRefresh = findViewById(com.hxfssc.activity.R.id.btn_full_refresh);
        btnOpenSettings = findViewById(com.hxfssc.activity.R.id.btn_open_settings);
        btnCloseMenu = findViewById(com.hxfssc.activity.R.id.btn_close_menu);

        inputHost = findViewById(com.hxfssc.activity.R.id.input_host);
        inputUser = findViewById(R.id.input_user);
        inputPass = findViewById(R.id.input_pass);
        inputInterval = findViewById(R.id.input_interval);
        btnStyleSelect = findViewById(R.id.btn_style_select);
        btnSourceTypeSelect = findViewById(R.id.btn_source_type_select);
        containerSmbConfig = findViewById(R.id.container_smb_config);
        textConnTitle = findViewById(R.id.text_conn_title);
        
        textCurrentPath = findViewById(R.id.text_current_path);
        textDirStatus = findViewById(R.id.text_dir_status);
        dirListContainer = findViewById(R.id.dir_list_container);
        
        btnConnect = findViewById(R.id.btn_connect);
        btnSave = findViewById(R.id.btn_save);
        btnExit = findViewById(R.id.btn_exit);
        
        // Init Source Type Selection
        btnSourceTypeSelect.setOnClickListener(v -> {
            new android.app.AlertDialog.Builder(this)
                .setTitle("选择数据来源")
                .setSingleChoiceItems(sourceTypes, currentSourceType, (dialog, which) -> {
                    currentSourceType = which;
                    btnSourceTypeSelect.setText(sourceTypes[which]);
                    
                    // Update UI based on selection
                    if (which == 0) {
                        // SMB
                        containerSmbConfig.setVisibility(View.VISIBLE);
                        textConnTitle.setText("2. SMB 配置");
                        btnConnect.setText("测试连接 / 列出目录");
                    } else {
                        // Local
                        containerSmbConfig.setVisibility(View.GONE);
                        textConnTitle.setText("2. 本地配置 (无需额外设置)");
                        btnConnect.setText("列出目录");
                    }
                    dialog.dismiss();
                })
                .setNegativeButton("取消", null)
                .show();
        });

        // Init Style Selection
        btnStyleSelect.setOnClickListener(v -> {
            new android.app.AlertDialog.Builder(this)
                .setTitle("选择展示风格")
                .setSingleChoiceItems(styles, currentStyle, (dialog, which) -> {
                    currentStyle = which;
                    btnStyleSelect.setText(styles[which]);
                    dialog.dismiss();
                })
                .setNegativeButton("取消", null)
                .show();
        });

        activeView = photoView1;
        inactiveView = photoView2;

        btnConnect.setOnClickListener(v -> listPath(""));
        btnSave.setOnClickListener(v -> saveSettings());
        
        // 关键修复：显式定义 OnClickListener 行为，确保只关闭设置而不退出 App
        btnExit.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                closeSettings();
            }
        });
    }

    private int dpToPx(int dp) {
        float density = getResources().getDisplayMetrics().density;
        return Math.round(dp * density);
    }

    private void loadSettings() {
        String json = getSharedPreferences(PREFS_NAME, MODE_PRIVATE).getString(KEY_SETTINGS, null);
        if (json != null) {
            try {
                currentSettings = new JSONObject(json);
                
                // Source Type
                currentSourceType = currentSettings.optInt("sourceType", 0);
                if (currentSourceType >= 0 && currentSourceType < sourceTypes.length) {
                    btnSourceTypeSelect.setText(sourceTypes[currentSourceType]);
                } else {
                    currentSourceType = 0;
                    btnSourceTypeSelect.setText(sourceTypes[0]);
                }

                // 使用 optString 并提供默认值
                String host = currentSettings.optString("smbHost", "");
                inputHost.setText(host);

                String user = currentSettings.optString("smbUser", "");
                inputUser.setText(user);

                String pass = currentSettings.optString("smbPass", "");
                inputPass.setText(pass);
                
                String savedPath = currentSettings.optString("smbPath", "");
                if (TextUtils.isEmpty(savedPath)) {
                    // Default path depends on source type? 
                    // For SMB: photo/PhotoWall
                    // For Local: /storage/emulated/0/DCIM ?
                    // Let's keep existing logic but maybe adapt if empty
                    if (currentSourceType == 1) {
                         textCurrentPath.setText(Environment.getExternalStorageDirectory().getAbsolutePath() + "/DCIM/Camera");
                    } else {
                         textCurrentPath.setText("photo/PhotoWall");
                    }
                } else {
                    textCurrentPath.setText(savedPath);
                }
                
                // 转换毫秒到秒显示，默认 5 秒
                int intervalMs = currentSettings.optInt("autoplayInterval", 5000);
                if (intervalMs < 3000) intervalMs = 5000; // 强制修正不合法的值
                inputInterval.setText(String.valueOf(intervalMs / 1000));
                playbackInterval = intervalMs;
                
                // 设置 Spinner 选中项
                currentStyle = currentSettings.optInt("displayStyle", 0);
                if (currentStyle >= 0 && currentStyle < styles.length) {
                    btnStyleSelect.setText(styles[currentStyle]);
                } else {
                    currentStyle = 0;
                    btnStyleSelect.setText(styles[0]);
                }
                
                startPhotoWall();
            } catch (JSONException e) {
                setDefaultSettings();
                openSettings();
            }
        } else {
            setDefaultSettings();
            openSettings();
        }
    }

    private void setDefaultSettings() {
        // 设置默认值
        inputHost.setText("");
        inputUser.setText("");
        inputPass.setText("");
        textCurrentPath.setText("photo/PhotoWall");
        inputInterval.setText("5");
        
        currentStyle = 0;
        btnStyleSelect.setText(styles[0]);
        
        currentSourceType = 0; // Default SMB
        btnSourceTypeSelect.setText(sourceTypes[0]);
        
        playbackInterval = 5000;
        
        // Update visibility based on default (SMB)
        containerSmbConfig.setVisibility(View.VISIBLE);
        textConnTitle.setText("2. SMB 配置");
        btnConnect.setText("测试连接 / 列出目录");
    }

    private void openSettings() {
        isSettingsOpen = true;
        settingsOverlay.setVisibility(View.VISIBLE);
        refreshMenuOverlay.setVisibility(View.GONE); // 确保菜单关闭
        isPlaying = false;
        if (playbackRunnable != null) handler.removeCallbacks(playbackRunnable);
        inputHost.requestFocus();
    }

    private void toggleMenu() {
        if (refreshMenuOverlay.getVisibility() == View.VISIBLE) {
            closeMenu();
        } else {
            openMenu();
        }
    }

    private int selectedMenuIndex = 0;

    private void openMenu() {
        refreshMenuOverlay.setVisibility(View.VISIBLE);
        selectedMenuIndex = 0;
        updateMenuFocus();
    }
    
    private void updateMenuFocus() {
        btnIncrementalRefresh.setBackgroundResource(selectedMenuIndex == 0 ? com.hxfssc.activity.R.drawable.btn_primary : com.hxfssc.activity.R.drawable.btn_secondary);
        btnFullRefresh.setBackgroundResource(selectedMenuIndex == 1 ? com.hxfssc.activity.R.drawable.btn_primary : com.hxfssc.activity.R.drawable.btn_secondary);
        btnOpenSettings.setBackgroundResource(selectedMenuIndex == 2 ? com.hxfssc.activity.R.drawable.btn_primary : com.hxfssc.activity.R.drawable.btn_secondary);
        btnCloseMenu.setBackgroundResource(selectedMenuIndex == 3 ? com.hxfssc.activity.R.drawable.btn_danger : com.hxfssc.activity.R.drawable.btn_secondary); // Close uses danger color when selected? Or maybe just keep it simple.
        // Let's use primary for selection to indicate "Active", and secondary for inactive.
        // For Close button, maybe we want it red?
        // Let's stick to: Selected = Primary (Blue), Unselected = Secondary (Gray)
        // But for Close button, maybe Selected = Danger (Red), Unselected = Secondary
        if (selectedMenuIndex == 3) {
             btnCloseMenu.setBackgroundResource(com.hxfssc.activity.R.drawable.btn_danger);
        } else {
             btnCloseMenu.setBackgroundResource(com.hxfssc.activity.R.drawable.btn_secondary);
        }
    }

    private void closeMenu() {
        refreshMenuOverlay.setVisibility(View.GONE);
    }

    private void closeSettings() {
        isSettingsOpen = false;
        settingsOverlay.setVisibility(View.GONE);
        startPhotoWall();
    }

    private void saveSettings() {
        try {
            JSONObject s = new JSONObject();
            s.put("sourceType", currentSourceType); // Save source type
            s.put("smbHost", inputHost.getText().toString().trim());
            s.put("smbUser", inputUser.getText().toString().trim());
            s.put("smbPass", inputPass.getText().toString());
            s.put("smbPath", textCurrentPath.getText().toString());
            s.put("autoplayInterval", Integer.parseInt(inputInterval.getText().toString().trim()));
            s.put("displayStyle", currentStyle); // Save display style
            
            getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
                    .edit()
                    .putString(KEY_SETTINGS, s.toString())
                    .apply();
            
            currentSettings = s;
            // currentSourceType and currentStyle are already updated by dialog selection
            
            // 确保更新内存中的变量，并转换单位（秒 -> 毫秒）
            int sec = s.optInt("autoplayInterval", 5); // 默认为 5 秒
            playbackInterval = sec * 1000L; 
            
            Toast.makeText(this, "设置已保存: " + sec + "秒", Toast.LENGTH_SHORT).show();
            closeSettings();
        } catch (Exception e) {
            Toast.makeText(this, "保存失败: " + e.getMessage(), Toast.LENGTH_SHORT).show();
        }
    }

    private void handleRefresh(boolean isFull) {
        closeMenu();
        stopPlayback(); // 无论何种模式，先停止播放
        
        // 终止上一个动作
        if (refreshFuture != null && !refreshFuture.isDone()) {
            isRefreshCancelled = true;
            refreshFuture.cancel(true); // 尝试中断
        }
        isRefreshCancelled = false; // 重置标志位
        
        infoText.setVisibility(View.VISIBLE);
        
        if (isFull) {
            refreshFuture = executor.submit(this::performFullRefresh);
        } else {
            refreshFuture = executor.submit(this::performIncrementalRefresh);
        }
    }

    private void performIncrementalRefresh() {
        handler.post(() -> infoText.setText("增量检查中..."));
        
        // 1. 获取远程列表
        List<String> remoteFiles = fetchFileList();
        if (isRefreshCancelled) return; // 检查取消
        
        if (remoteFiles == null) {
            showTextAndHide("增量刷新失败：无法连接服务器", 3000);
            return;
        }

        // 2. Diff Calculation
        List<String> currentFiles = new ArrayList<>(allPhotoFiles);
        List<String> toAdd = new ArrayList<>();
        List<String> toRemove = new ArrayList<>();
        
        Set<String> remoteSet = new HashSet<>(remoteFiles);
        Set<String> currentSet = new HashSet<>(currentFiles);
        
        for (String s : remoteFiles) {
            if (!currentSet.contains(s)) toAdd.add(s);
        }
        for (String s : currentFiles) {
            if (!remoteSet.contains(s)) toRemove.add(s);
        }

        if (isRefreshCancelled) return;

        // 3. 状态机逻辑
        if (toAdd.isEmpty() && toRemove.isEmpty()) {
            // Case A: 无变化
            handler.post(() -> infoText.setText("没有变化"));
            try { Thread.sleep(3000); } catch (InterruptedException e) { return; }
            if (!isRefreshCancelled) handler.post(() -> {
                infoText.setVisibility(View.GONE);
                startPhotoWall();
            });
        } else {
            // Case B: 有变化 (删除 -> 新增)
            processChanges(toRemove, toAdd);
        }
    }

    private void processChanges(List<String> toRemove, List<String> toAdd) {
        // Step 1: 处理删除
        if (!toRemove.isEmpty()) {
            long startTime = System.currentTimeMillis();
            int total = toRemove.size();
            int count = 0;
            
            for (String name : toRemove) {
                if (isRefreshCancelled) return;
                deleteCacheForFile(name);
                count++;
                final int c = count;
                handler.post(() -> infoText.setText(String.format("删除照片...(%d/%d)", c, total)));
                try { Thread.sleep(50); } catch (InterruptedException e) { return; }
            }
            
            // 移除内存数据
            handler.post(() -> allPhotoFiles.removeAll(toRemove));

            // 保证至少显示 3 秒
            long elapsed = System.currentTimeMillis() - startTime;
            if (elapsed < 3000) {
                try { Thread.sleep(3000 - elapsed); } catch (InterruptedException e) { return; }
            }
        }

        if (isRefreshCancelled) return;

        // Step 2: 处理新增
        if (!toAdd.isEmpty()) {
            long startTime = System.currentTimeMillis();
            int total = toAdd.size();
            
            // 将新文件加入内存列表
            handler.post(() -> {
                allPhotoFiles.addAll(toAdd);
                Collections.shuffle(allPhotoFiles);
            });
            
            // 模拟进度展示
            for (int i = 1; i <= total; i++) {
                if (isRefreshCancelled) return;
                final int c = i;
                handler.post(() -> infoText.setText(String.format("新增照片...(%d/%d)", c, total)));
                try { Thread.sleep(Math.min(50, 3000 / total)); } catch (InterruptedException e) { return; }
            }
            
            // 保证至少显示 3 秒
            long elapsed = System.currentTimeMillis() - startTime;
            if (elapsed < 3000) {
                try { Thread.sleep(3000 - elapsed); } catch (InterruptedException e) { return; }
            }
        }
        
        if (isRefreshCancelled) return;
        
        // 完成，重新开始播放
        handler.post(this::startPhotoWall);
    }

    private void performFullRefresh() {
        // Step 1: 删除缓存
        long startTime = System.currentTimeMillis();
        File cacheDir = new File(getCacheDir(), "thumbs");
        if (cacheDir.exists()) {
             File[] files = cacheDir.listFiles();
             if (files != null && files.length > 0) {
                 int total = files.length;
                 int count = 0;
                 for (File f : files) {
                     if (isRefreshCancelled) return;
                     f.delete();
                     count++;
                     final int c = count;
                     handler.post(() -> infoText.setText(String.format("删除缓存...(%d/%d)", c, total)));
                     try { Thread.sleep(20); } catch (InterruptedException e) { return; }
                 }
             } else {
                 handler.post(() -> infoText.setText("删除缓存...(0/0)"));
             }
        } else {
            handler.post(() -> infoText.setText("删除缓存...(0/0)"));
        }
        
        // 保证至少显示 3 秒
        long elapsed = System.currentTimeMillis() - startTime;
        if (elapsed < 3000) {
            try { Thread.sleep(3000 - elapsed); } catch (InterruptedException e) { return; }
        }

        if (isRefreshCancelled) return;

        // Step 2: 抓取最新数据
        handler.post(() -> infoText.setText("正在连接服务器..."));
        List<String> remoteFiles = fetchFileList();
        
        if (remoteFiles == null) {
            showTextAndHide("刷新失败：无法连接服务器", 3000);
            return;
        }

        if (isRefreshCancelled) return;

        // Step 3: 新增照片提示
        startTime = System.currentTimeMillis();
        int total = remoteFiles.size();
        
        handler.post(() -> {
            allPhotoFiles.clear();
            allPhotoFiles.addAll(remoteFiles);
            Collections.shuffle(allPhotoFiles);
            loadedBitmaps.clear();
            currentPhotoIndex = 0;
        });

        // 模拟进度展示
        for (int i = 1; i <= total; i++) {
            if (isRefreshCancelled) return;
            final int c = i;
            handler.post(() -> infoText.setText(String.format("新增照片...(%d/%d)", c, total)));
            try { Thread.sleep(Math.min(20, 3000 / Math.max(1, total))); } catch (InterruptedException e) { return; }
        }

        // 保证至少显示 3 秒
        elapsed = System.currentTimeMillis() - startTime;
        if (elapsed < 3000) {
            try { Thread.sleep(3000 - elapsed); } catch (InterruptedException e) { return; }
        }

        if (isRefreshCancelled) return;

        // 完成
        handler.post(this::startPhotoWall);
    }

    private void showTextAndHide(String text, long delay) {
        handler.post(() -> {
            infoText.setText(text);
            infoText.setVisibility(View.VISIBLE);
            handler.postDelayed(() -> infoText.setVisibility(View.GONE), delay);
        });
    }

    private void deleteCacheForFile(String fileName) {
        if (currentSettings == null) return;
        int type = currentSettings.optInt("sourceType", 0);
        if (type == 1) return; // 本地文件不使用磁盘缓存，直接读取
        
        try {
            String host = currentSettings.optString("smbHost");
            String path = currentSettings.optString("smbPath");
            String smbUrl = "smb://" + host + "/" + path + "/" + fileName;
            String cacheKey = getMD5(smbUrl);
            File cacheFile = new File(new File(getCacheDir(), "thumbs"), cacheKey + ".jpg");
            if (cacheFile.exists()) cacheFile.delete();
        } catch (Exception e) {}
    }

    private List<String> fetchFileList() {
        if (currentSettings == null) return null;
        int type = currentSettings.optInt("sourceType", 0);
        if (type == 1) {
            return fetchLocalFileList();
        } else {
            return fetchRemoteFileList();
        }
    }

    private List<String> fetchLocalFileList() {
        List<String> photoNames = new ArrayList<>();
        String path = currentSettings.optString("smbPath");
        try {
            handler.post(() -> infoText.setText("正在读取本地目录..."));
            File dir = new File(path);
            if (!dir.exists() || !dir.isDirectory()) return null;
            
            File[] files = dir.listFiles();
            if (files == null) return null;
            
            int total = files.length;
            int count = 0;
            int processed = 0;
            
            handler.post(() -> infoText.setText("正在分析文件... (0/" + total + ")"));
            
            for (File f : files) {
                processed++;
                String name = f.getName().toLowerCase();
                if (f.isFile()) {
                    for (String ext : SUPPORTED_EXTENSIONS) {
                        if (name.endsWith(ext)) {
                            photoNames.add(f.getAbsolutePath()); // Store absolute path
                            count++;
                            break;
                        }
                    }
                }
                
                if (processed % 10 == 0 || processed == total) {
                    final int c = count;
                    final int p = processed;
                    handler.post(() -> infoText.setText("正在分析文件... (" + p + "/" + total + ") 已发现 " + c + " 张"));
                }
            }
            return photoNames;
        } catch (Exception e) {
            return null;
        }
    }

    private List<String> fetchRemoteFileList() {
        if (currentSettings == null) return null;
        List<String> photoNames = new ArrayList<>();
        String host = currentSettings.optString("smbHost");
        String path = currentSettings.optString("smbPath");
        String user = currentSettings.optString("smbUser");
        String pass = currentSettings.optString("smbPass");
        try {
            String smbUrl = "smb://" + host + "/" + path + "/";
            CIFSContext context = new BaseContext(new PropertyConfiguration(System.getProperties()));
            NtlmPasswordAuthenticator auth = new NtlmPasswordAuthenticator(null, user, pass);
            SmbFile dir = new SmbFile(smbUrl, context.withCredentials(auth));
            
            // 获取文件列表（这一步可能耗时，取决于网络）
            handler.post(() -> infoText.setText("正在读取目录结构..."));
            SmbFile[] files = dir.listFiles();
            
            int total = files.length;
            int count = 0;
            int processed = 0;
            
            handler.post(() -> infoText.setText("正在分析文件... (0/" + total + ")"));
            
            for (SmbFile f : files) {
                processed++;
                String name = f.getName().toLowerCase();
                if (!f.isDirectory()) {
                    for (String ext : SUPPORTED_EXTENSIONS) {
                        if (name.endsWith(ext)) {
                            photoNames.add(f.getName());
                            count++;
                            break;
                        }
                    }
                }
                
                // 每处理 10 个文件或最后一个文件时更新 UI
                if (processed % 10 == 0 || processed == total) {
                    final int c = count;
                    final int p = processed;
                    handler.post(() -> infoText.setText("正在分析文件... (" + p + "/" + total + ") 已发现 " + c + " 张"));
                }
            }
            return photoNames;
        } catch (Exception e) {
            return null;
        }
    }

    private void stopPlayback() {
        isPlaying = false;
        if (playbackRunnable != null) {
            handler.removeCallbacks(playbackRunnable);
            playbackRunnable = null; // 彻底断开引用
        }
        // 停止当前动画 (如果有办法获取当前 AnimatorSet 最好，这里简单重置 View 状态)
        if (activeView != null) activeView.clearAnimation();
        if (inactiveView != null) inactiveView.clearAnimation();
    }

    // 复用之前的 startPhotoWall，但去掉里面的 requestFileList，因为它会重置流程
    // 我们需要一个不带 requestFileList 的启动方法，或者让 startPhotoWall 变聪明
    // 这里我们重构一下 startPhotoWall，让它只负责 UI 启动
    
    private void startPhotoWall() {
        isInitialLoad = true;
        isPlaying = true; // 确保标记为播放状态
        infoText.setVisibility(View.VISIBLE);
        infoText.setText("准备播放...");
        
        // 如果列表为空，说明还没数据，需要请求
        if (allPhotoFiles.isEmpty()) {
            requestFileList();
        } else {
            // 已有数据（增量/全量刷新后），直接开始加载图片
            loadedBitmaps.clear();
            currentPhotoIndex = 0;
            preloadPhotos();
        }
    }

    private void requestFileList() {
        if (currentSettings == null) return;
        executor.execute(() -> {
            handler.post(() -> infoText.setText("正在连接服务器获取列表..."));
            List<String> photoNames = fetchFileList();
            
            if (photoNames == null) {
                handler.post(() -> infoText.setText("连接失败，请检查网络或配置"));
                return;
            }

            handler.post(() -> {
                allPhotoFiles.clear();
                allPhotoFiles.addAll(photoNames);
                Collections.shuffle(allPhotoFiles);
                
                if (allPhotoFiles.isEmpty()) {
                    infoText.setText("目录为空");
                } else {
                    infoText.setText("发现 " + allPhotoFiles.size() + " 张照片，准备播放...");
                    preloadPhotos();
                }
            });
        });
    }

    private void preloadPhotos() {
        // 只启动一次，后续由 loadNextPhoto 递归触发缓冲
        loadNextPhoto();
    }

    /**
     * 异步预加载图片
     * 策略：
     * - 维护一个较小的内存缓冲队列 (Max 3~5 张)，防止 OOM
     * - 每次播放消耗一张后，自动补充下一张
     * - 包含错误重试机制：如果加载失败，尝试跳过并加载下一张
     */
    private synchronized void loadNextPhoto() {
        if (allPhotoFiles.isEmpty()) return;
        if (isLoading) return; // 防止并发加载
        if (loadedBitmaps.size() >= 3) return; // 缓冲满了就不加载了，防止内存溢出

        // 索引越界保护
        if (currentPhotoIndex >= allPhotoFiles.size()) currentPhotoIndex = 0;
        if (currentPhotoIndex < 0) currentPhotoIndex = 0;

        String fileName;
        try {
            fileName = allPhotoFiles.get(currentPhotoIndex);
        } catch (IndexOutOfBoundsException e) {
            currentPhotoIndex = 0;
            return;
        }
        
        // 只有真正开始加载了才移动指针
        isLoading = true;
        currentPhotoIndex++;

        executor.execute(() -> {
            try {
                Bitmap bitmap = loadBitmap(fileName);
                if (bitmap != null) {
                    handler.post(() -> {
                        loadedBitmaps.add(bitmap);
                        // 再次检查队列大小
                        while (loadedBitmaps.size() > 5) {
                            Bitmap old = loadedBitmaps.remove(0);
                            if (old != null && !old.isRecycled()) old.recycle();
                        }
                        
                        // 更新进度提示
                        if (isInitialLoad) {
                            infoText.setText("正在缓冲照片... (" + loadedBitmaps.size() + "/2)");
                        }
                        
                        // 缓冲至少 2 张才开始播放，展示加载过程
                        if (isInitialLoad && loadedBitmaps.size() >= 2) { 
                            isInitialLoad = false;
                            isPlaying = true;
                            infoText.setVisibility(View.GONE);
                            showNextPhoto();
                        }
                    });
                }
            } catch (Throwable e) {
                e.printStackTrace();
            } finally {
                // 无论成功失败，必须重置标志位
                handler.post(() -> {
                    isLoading = false;
                    // 如果还在初始化阶段且缓冲不足，继续加载下一张
                    // 或者如果正在播放但队列过低（说明加载失败了），也尝试继续加载
                    if ((isInitialLoad && loadedBitmaps.size() < 2) || (isPlaying && loadedBitmaps.size() < 2)) {
                         if (!allPhotoFiles.isEmpty()) {
                             loadNextPhoto();
                         }
                    }
                });
            }
        });
    }

    private Bitmap loadBitmap(String fileName) {
        if (currentSettings == null) return null;
        int type = currentSettings.optInt("sourceType", 0);
        if (type == 1) {
            return loadBitmapFromLocal(fileName);
        } else {
            return loadBitmapFromSmb(fileName);
        }
    }

    private Bitmap loadBitmapFromLocal(String filePath) {
        try {
            BitmapFactory.Options options = new BitmapFactory.Options();
            options.inJustDecodeBounds = true;
            BitmapFactory.decodeFile(filePath, options);
            
            options.inSampleSize = 1;
            if (options.outHeight > 720 || options.outWidth > 1280) {
                final int halfHeight = options.outHeight / 2;
                final int halfWidth = options.outWidth / 2;
                while ((halfHeight / options.inSampleSize) >= 720 && (halfWidth / options.inSampleSize) >= 1280) {
                    options.inSampleSize *= 2;
                }
            }
            options.inJustDecodeBounds = false;
            options.inPreferredConfig = Bitmap.Config.RGB_565;
            
            return BitmapFactory.decodeFile(filePath, options);
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * 从 SMB 网络加载图片
     * 包含本地磁盘缓存逻辑：
     * 1. 优先读取本地缓存 (cacheDir/thumbs)
     * 2. 如果缓存读取失败（文件损坏），自动删除并重新下载
     * 3. 下载时自动进行采样压缩 (Target 720p)，减少内存占用
     */
    private Bitmap loadBitmapFromSmb(String fileName) {
        if (currentSettings == null) return null;
        String host = currentSettings.optString("smbHost");
        String path = currentSettings.optString("smbPath");
        String user = currentSettings.optString("smbUser");
        String pass = currentSettings.optString("smbPass");
        
        try {
            String smbUrl = "smb://" + host + "/" + path + "/" + fileName;
            File cacheDir = new File(getCacheDir(), "thumbs");
            if (!cacheDir.exists()) cacheDir.mkdirs();
            String cacheKey = getMD5(smbUrl);
            File cacheFile = new File(cacheDir, cacheKey + ".jpg");

            if (cacheFile.exists() && cacheFile.length() > 0) {
                cacheFile.setLastModified(System.currentTimeMillis());
                Bitmap b = BitmapFactory.decodeFile(cacheFile.getAbsolutePath());
                if (b != null) return b;
                // If decode fails, delete corrupt file
                cacheFile.delete();
            }
            
            // If we are here, either cache didn't exist or was corrupt (and deleted)
            {
                CIFSContext context = new BaseContext(new PropertyConfiguration(System.getProperties()));
                NtlmPasswordAuthenticator auth = new NtlmPasswordAuthenticator(null, user, pass);
                SmbFile file = new SmbFile(smbUrl, context.withCredentials(auth));

                BitmapFactory.Options options = new BitmapFactory.Options();
                options.inJustDecodeBounds = true;
                try (InputStream in = file.openInputStream()) {
                    BitmapFactory.decodeStream(in, null, options);
                }

                options.inSampleSize = 1;
                // Target 720p for performance on Android 4.4
                if (options.outHeight > 720 || options.outWidth > 1280) {
                    final int halfHeight = options.outHeight / 2;
                    final int halfWidth = options.outWidth / 2;
                    while ((halfHeight / options.inSampleSize) >= 720 && (halfWidth / options.inSampleSize) >= 1280) {
                        options.inSampleSize *= 2;
                    }
                }
                options.inJustDecodeBounds = false;
                options.inPreferredConfig = Bitmap.Config.RGB_565;

                Bitmap bitmap;
                try (InputStream in = file.openInputStream()) {
                    bitmap = BitmapFactory.decodeStream(in, null, options);
                }

                if (bitmap != null) {
                    try (FileOutputStream out = new FileOutputStream(cacheFile)) {
                        bitmap.compress(Bitmap.CompressFormat.JPEG, 60, out);
                    } catch (Exception e) {
                        // Ignore cache write failure
                    }
                    return bitmap;
                }
            }
        } catch (Throwable e) {
            Log.e(TAG, "Load photo failed", e);
        }
        return null;
    }

    /**
     * 核心播放逻辑：切换并显示下一张图片
     * 实现步骤：
     * 1. 检查缓冲队列，如果为空则尝试加载并等待
     * 2. 取出下一张 Bitmap
     * 3. 根据当前风格 (currentStyle) 构建 AnimatorSet 动画
     *    - Ken Burns: 透明度渐变 + 随机平移/缩放
     *    - Fade: 纯透明度渐变
     *    - Slide: 左右平移动画
     * 4. 启动动画并调度下一次播放
     */
    private void showNextPhoto() {
        if (loadedBitmaps.isEmpty()) {
            // 如果没有缓存图片，尝试加载
            if (!allPhotoFiles.isEmpty()) {
                loadNextPhoto();
                // 关键修复：即使当前没有图片显示，也要保持定时器循环！
                if (isPlaying) {
                    if (playbackRunnable != null) handler.removeCallbacks(playbackRunnable);
                    playbackRunnable = this::showNextPhoto;
                    handler.postDelayed(playbackRunnable, 1000);
                }
            }
            return;
        }
        
        Bitmap nextBitmap = loadedBitmaps.remove(0);
        
        // 1. 准备阶段
        // 取消之前的动画，让 activeView (即将变成 inactiveView) 停在当前状态
        if (currentAnimator != null) {
            currentAnimator.removeAllListeners();
            currentAnimator.cancel();
            currentAnimator = null;
        }

        // 仅重置 inactiveView (即将上场的新图) 为初始状态
        inactiveView.setAlpha(0f); 
        inactiveView.setScaleX(1f); inactiveView.setScaleY(1f);
        inactiveView.setTranslationX(0f); inactiveView.setTranslationY(0f);
        inactiveView.setVisibility(View.VISIBLE);
        inactiveView.setImageBitmap(nextBitmap);

        // Swap 引用：activeView 变为旧图，inactiveView 变为新图
        ImageView temp = activeView;
        activeView = inactiveView; // 指向新图
        inactiveView = temp;       // 指向旧图

        // 2. 动画构建
        currentAnimator = new AnimatorSet();
        List<Animator> animators = new ArrayList<>();

        if (currentStyle == 2) { // Slide 风格
            // 新图从右侧进入
            activeView.setTranslationX(activeView.getWidth());
            activeView.setAlpha(1f); // Slide 模式不透明
            animators.add(ObjectAnimator.ofFloat(activeView, "translationX", activeView.getWidth(), 0f).setDuration(800));
            
            // 旧图向左侧退出
            animators.add(ObjectAnimator.ofFloat(inactiveView, "translationX", 0f, -activeView.getWidth()).setDuration(800));
            
        } else {
            // Ken Burns 和 Fade 风格，旧图统一 Fade Out
            // 针对 Fade Only 风格，增加过渡时长至 2500ms，更加柔和
            long commonDuration = (currentStyle == 1) ? 2500 : 1000;
            
            // 注意：这里不重置旧图的 Scale/Translation，让它停在当前位置慢慢消失，避免跳变
            animators.add(ObjectAnimator.ofFloat(inactiveView, "alpha", inactiveView.getAlpha(), 0f).setDuration(commonDuration));

            if (currentStyle == 1) { // Fade Only
                animators.add(ObjectAnimator.ofFloat(activeView, "alpha", 0f, 1f).setDuration(commonDuration));
            } else { // Ken Burns (Default)
                // 新图 Fade In
                animators.add(ObjectAnimator.ofFloat(activeView, "alpha", 0f, 1f).setDuration(1000));
                
                // Ken Burns 随机参数优化：
                // 1. 基础缩放必须 > 1.0，否则位移会漏出黑边
                // 2. 只有当缩放比例足够大时，才允许较大的位移
                
                Random r = new Random();
                boolean zoomIn = r.nextBoolean(); // 随机放大或缩小
                
                // 设定最小缩放为 1.1 倍，确保有 10% 的余量用于位移
                float startScale = zoomIn ? 1.1f : 1.35f;
                float endScale = zoomIn ? 1.35f : 1.1f;
                
                activeView.setScaleX(startScale);
                activeView.setScaleY(startScale);
                
                // 动画时长比间隔略长，保证流畅
                long duration = playbackInterval + 3000; 
                
                animators.add(ObjectAnimator.ofFloat(activeView, "scaleX", startScale, endScale).setDuration(duration));
                animators.add(ObjectAnimator.ofFloat(activeView, "scaleY", startScale, endScale).setDuration(duration));
                
                // 计算安全位移范围：(Scale - 1.0) * ScreenSize / 2
                // 这里我们估算一个安全的相对值。假设 View 填满屏幕，
                // Scale 1.1 时，允许单向位移 5% 的宽高。
                // 为了简化，我们限制位移幅度在 50px 以内（对于 1080p 屏幕，50px 约等于 5%）
                // 如果 Scale 更大，位移可以更大，但为了安全起见，保守一点。
                
                float maxTranslation = 40f; 
                float tx = (r.nextFloat() - 0.5f) * 2 * maxTranslation; 
                float ty = (r.nextFloat() - 0.5f) * 2 * maxTranslation;
                
                animators.add(ObjectAnimator.ofFloat(activeView, "translationX", 0f, tx).setDuration(duration));
                animators.add(ObjectAnimator.ofFloat(activeView, "translationY", 0f, ty).setDuration(duration));
            }
        }
        
        currentAnimator.playTogether(animators);
        // 使用 LinearInterpolator 或者更加平滑的插值器
        currentAnimator.setInterpolator(new AccelerateDecelerateInterpolator());
        
        currentAnimator.addListener(new android.animation.AnimatorListenerAdapter() {
            @Override
            public void onAnimationEnd(Animator animation) {
                if (inactiveView != null) {
                    inactiveView.setVisibility(View.INVISIBLE);
                    // 动画结束后再重置旧图，为下一次做准备
                    inactiveView.setTranslationX(0f); 
                    inactiveView.setScaleX(1f);
                    inactiveView.setScaleY(1f);
                }
            }
        });
        currentAnimator.start();

        // Queue next load
        loadNextPhoto();

        // Schedule next play
        if (isPlaying) {
            if (playbackRunnable != null) handler.removeCallbacks(playbackRunnable);
            playbackRunnable = this::showNextPhoto;
            long delay = Math.max(3000, playbackInterval);
            handler.postDelayed(playbackRunnable, delay);
        }
    }

    private void listPath(String subPath) {
        if (currentSourceType == 1) {
            listLocalPath(subPath);
        } else {
            listSmbPath(subPath);
        }
    }

    private void listLocalPath(String subPath) {
        executor.execute(() -> {
            List<String> dirNames = new ArrayList<>();
            // 如果 subPath 为空，默认从外部存储根目录开始
            String basePath = subPath.isEmpty() ? Environment.getExternalStorageDirectory().getAbsolutePath() : subPath;
            
            try {
                File dir = new File(basePath);
                if (dir.exists() && dir.isDirectory()) {
                    File[] files = dir.listFiles();
                    if (files != null) {
                         Arrays.sort(files, (f1, f2) -> f1.getName().compareToIgnoreCase(f2.getName()));
                         for (File f : files) {
                             if (f.isDirectory() && !f.getName().startsWith(".")) {
                                 dirNames.add(f.getName());
                             }
                         }
                    }
                }
            } catch (Exception e) {
                handler.post(() -> textDirStatus.setText("读取失败: " + e.getMessage()));
                return;
            }

            final String finalPath = basePath;
            handler.post(() -> {
                textDirStatus.setText("");
                dirListContainer.removeAllViews();
                textCurrentPath.setText(finalPath);
                
                if (dirNames.isEmpty()) {
                    TextView tv = new TextView(this);
                    tv.setText("无子目录");
                    tv.setTextColor(0xFFAAAAAA);
                    dirListContainer.addView(tv);
                } else {
                    for (String dirName : dirNames) {
                        TextView tv = new TextView(this);
                        tv.setText(dirName);
                        tv.setTextSize(18);
                        tv.setTextColor(0xFFFFFFFF);
                        tv.setPadding(10, 10, 10, 10);
                        tv.setFocusable(true);
                        tv.setBackgroundResource(android.R.drawable.list_selector_background);
                        tv.setOnClickListener(v -> listPath(new File(finalPath, dirName).getAbsolutePath()));
                        dirListContainer.addView(tv);
                    }
                }
            });
        });
    }

    private void listSmbPath(String subPath) {
        if (currentSettings == null && subPath.isEmpty()) {
            // First connect attempt from inputs
            currentSettings = new JSONObject(); // Temporary container
        }
        
        executor.execute(() -> {
            List<String> dirNames = new ArrayList<>();
            String host = inputHost.getText().toString().trim();
            String user = inputUser.getText().toString().trim();
            String pass = inputPass.getText().toString();
            String basePath = subPath.isEmpty() ? "" : subPath;
            if (!basePath.isEmpty() && !basePath.endsWith("/")) basePath += "/";
            
            try {
                String smbUrl = "smb://" + host + "/" + basePath;
                CIFSContext context = new BaseContext(new PropertyConfiguration(System.getProperties()));
                NtlmPasswordAuthenticator auth = new NtlmPasswordAuthenticator(null, user, pass);
                SmbFile dir = new SmbFile(smbUrl, context.withCredentials(auth));
                for (SmbFile f : dir.listFiles()) {
                    if (f.isDirectory() && !f.getName().endsWith("$")) {
                        dirNames.add(f.getName().replace("/", ""));
                    }
                }
            } catch (Exception e) {
                handler.post(() -> textDirStatus.setText("连接失败: " + e.getMessage()));
                return;
            }

            final String finalPath = basePath;
            handler.post(() -> {
                textDirStatus.setText("");
                dirListContainer.removeAllViews();
                if (finalPath.length() > 0) textCurrentPath.setText(finalPath.substring(0, finalPath.length() - 1));
                else textCurrentPath.setText("");
                
                if (dirNames.isEmpty()) {
                    TextView tv = new TextView(this);
                    tv.setText("无子目录");
                    tv.setTextColor(0xFFAAAAAA);
                    dirListContainer.addView(tv);
                } else {
                    for (String dirName : dirNames) {
                        TextView tv = new TextView(this);
                        tv.setText(dirName);
                        tv.setTextSize(18);
                        tv.setTextColor(0xFFFFFFFF);
                        tv.setPadding(10, 10, 10, 10);
                        tv.setFocusable(true);
                        tv.setBackgroundResource(android.R.drawable.list_selector_background);
                        tv.setOnClickListener(v -> listPath(finalPath + dirName));
                        dirListContainer.addView(tv);
                    }
                }
            });
        });
    }

    private void cleanCache() {
        executor.execute(() -> {
            File cacheDir = new File(getCacheDir(), "thumbs");
            if (cacheDir.exists()) {
                File[] files = cacheDir.listFiles();
                if (files != null && files.length > 0) {
                     long totalSize = 0;
                     for (File f : files) totalSize += f.length();
                     if (totalSize > 500 * 1024 * 1024) {
                         Arrays.sort(files, (f1, f2) -> Long.compare(f1.lastModified(), f2.lastModified()));
                         for (File f : files) {
                             totalSize -= f.length();
                             f.delete();
                             if (totalSize < 400 * 1024 * 1024) break;
                         }
                     }
                }
            }
        });
    }
    
    private String getMD5(String s) {
        try {
            MessageDigest digest = MessageDigest.getInstance("MD5");
            digest.update(s.getBytes());
            byte messageDigest[] = digest.digest();
            StringBuilder hexString = new StringBuilder();
            for (byte b : messageDigest) {
                String h = Integer.toHexString(0xFF & b);
                while (h.length() < 2) h = "0" + h;
                hexString.append(h);
            }
            return hexString.toString();
        } catch (Exception e) { return ""; }
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        if (event.getAction() == KeyEvent.ACTION_DOWN) {
            int keyCode = event.getKeyCode();
            
            if (isSettingsOpen) {
                if (keyCode == KeyEvent.KEYCODE_BACK) {
                    closeSettings();
                    return true;
                }
                return super.dispatchKeyEvent(event);
            }
            
            if (refreshMenuOverlay.getVisibility() == View.VISIBLE) {
                if (keyCode == KeyEvent.KEYCODE_BACK) {
                    closeMenu();
                    return true;
                }
                if (keyCode == KeyEvent.KEYCODE_DPAD_UP) {
                    selectedMenuIndex = (selectedMenuIndex - 1 + 4) % 4;
                    updateMenuFocus();
                    return true;
                }
                if (keyCode == KeyEvent.KEYCODE_DPAD_DOWN) {
                    selectedMenuIndex = (selectedMenuIndex + 1) % 4;
                    updateMenuFocus();
                    return true;
                }
                if (keyCode == KeyEvent.KEYCODE_DPAD_CENTER || keyCode == KeyEvent.KEYCODE_ENTER) {
                    // 直接调用业务逻辑，不依赖 performClick，规避系统级点击事件被吞
                    handler.post(() -> {
                        switch (selectedMenuIndex) {
                            case 0: handleRefresh(false); break; // 增量刷新
                            case 1: handleRefresh(true); break;  // 全量刷新
                            case 2: closeMenu(); openSettings(); break;
                            case 3: closeMenu(); break;
                        }
                    });
                    return true;
                }
                return true; // 拦截所有其他按键，防止菜单显示时操作到底层
            }

            // 全局快捷键
            switch (keyCode) {
                case KeyEvent.KEYCODE_DPAD_UP:
                case KeyEvent.KEYCODE_MENU:
                    toggleMenu();
                    return true;
                case KeyEvent.KEYCODE_BACK:
                    long currentTime = System.currentTimeMillis();
                    if (currentTime - lastBackPressTime < 2000) {
                        super.onBackPressed(); // 或者 finish();
                    } else {
                        lastBackPressTime = currentTime;
                        Toast.makeText(this, "再按一次退出应用", Toast.LENGTH_SHORT).show();
                    }
                    return true;
                case KeyEvent.KEYCODE_DPAD_CENTER:
                case KeyEvent.KEYCODE_ENTER:
                    togglePlay();
                    return true;
                case KeyEvent.KEYCODE_DPAD_RIGHT:
                    // 手动切图：暂停自动播放，切换到下一张
                    stopPlayback();
                    showNextPhoto();
                    return true;
                case KeyEvent.KEYCODE_DPAD_LEFT:
                    // 手动切图：暂停自动播放，(简单起见这里也是下一张，或者可以做上一张逻辑)
                    stopPlayback();
                    showNextPhoto();
                    return true;
            }
        }
        return super.dispatchKeyEvent(event);
    }
    
    private void togglePlay() {
        if (isPlaying) {
            isPlaying = false;
            handler.removeCallbacks(playbackRunnable);
            Toast.makeText(this, "暂停播放", Toast.LENGTH_SHORT).show();
        } else {
            isPlaying = true;
            showNextPhoto();
            Toast.makeText(this, "开始播放", Toast.LENGTH_SHORT).show();
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        executor.shutdown();
        handler.removeCallbacksAndMessages(null);
    }
}
