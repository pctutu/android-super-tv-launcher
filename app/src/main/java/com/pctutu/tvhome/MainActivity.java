package com.pctutu.tvhome;

import android.app.Activity;
import android.app.ActivityManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.PackageInstaller;
import android.content.pm.ResolveInfo;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.provider.Settings;
import android.view.KeyEvent;
import android.view.View;
import android.util.Log;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.HashSet;
import java.util.Locale;

public class MainActivity extends Activity implements HomeSurface.Actions {
    private HomeSurface home;
    private WeatherProvider weatherProvider;
    private static final int STORAGE_PERMISSION_REQUEST = 80;
    private static final String WEATHER_CACHE = "weather_cache";

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        getWindow().setFlags(1024, 1024);
        int systemUi = View.SYSTEM_UI_FLAG_FULLSCREEN | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION;
        if (Build.VERSION.SDK_INT >= 19) systemUi |= 0x1000 | 0x0200 | 0x0400 | 0x0100;
        getWindow().getDecorView().setSystemUiVisibility(systemUi);
        home = new HomeSurface(this, this);
        setContentView(home);
        setAppLanguage(getSharedPreferences("surface_state", 0).getString("language", "system"));
        String city = getPreferences(0).getString("weather_city", "北京");
        android.content.SharedPreferences cached = getSharedPreferences(WEATHER_CACHE, 0);
        if (cached.contains("temperature")) {
            home.setWeather(new WeatherProvider.WeatherData(cached.getString("city", city), cached.getString("condition", "天气已更新"), cached.getInt("temperature", 0), cached.getInt("humidity", 0)), getPreferences(0).getBoolean("weather_fahrenheit", false));
        }
        requestWeather(city);
        handleInstallResult(getIntent());
    }

    @Override public void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        handleInstallResult(intent);
    }

    @Override public void onBackPressed() {
        if (home != null) home.back();
    }

    @Override public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus && home != null) {
            int flags = View.SYSTEM_UI_FLAG_FULLSCREEN | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION;
            if (Build.VERSION.SDK_INT >= 19) flags |= 0x1000 | 0x0200 | 0x0400 | 0x0100;
            getWindow().getDecorView().setSystemUiVisibility(flags);
        }
    }

    @Override public boolean dispatchKeyEvent(KeyEvent event) {
        if (event.getAction() == KeyEvent.ACTION_DOWN && home != null && home.handleKey(event)) return true;
        return super.dispatchKeyEvent(event);
    }

    @Override public void setDefaultLauncher() {
        if (Build.VERSION.SDK_INT >= 29) {
            try {
                Object manager = getSystemService("role");
                Class<?> type = Class.forName("android.app.role.RoleManager");
                String role = (String) type.getField("ROLE_HOME").get(null);
                if (Boolean.TRUE.equals(type.getMethod("isRoleAvailable", String.class).invoke(manager, role))) {
                    startActivityForResult((Intent) type.getMethod("createRequestRoleIntent", String.class).invoke(manager, role), 40);
                    return;
                }
            } catch (Exception ignored) { }
        }
        try { startActivity(new Intent("android.settings.HOME_SETTINGS")); }
        catch (Exception e) { startActivity(new Intent(Settings.ACTION_SETTINGS)); }
    }

    @Override public void openSystemFeature(String feature) {
        String action = "network".equals(feature) ? Settings.ACTION_WIFI_SETTINGS : "source".equals(feature) ? "android.settings.TV_INPUT_SETTINGS" : "android.settings.INTERNAL_STORAGE_SETTINGS";
        try { startActivity(new Intent(action)); return; }
        catch (Exception ignored) { }
        String fallback = "storage".equals(feature) ? "android.settings.STORAGE_SETTINGS" : Settings.ACTION_SETTINGS;
        try { startActivity(new Intent(fallback)); }
        catch (Exception e) { if (home != null) home.setNotice("此电视未提供该系统设置页面"); }
    }

    @Override public void cleanMemory() {
        ActivityManager manager = (ActivityManager) getSystemService(ACTIVITY_SERVICE);
        HashSet<String> packages = new HashSet<String>();
        Intent query = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER);
        for (ResolveInfo info : getPackageManager().queryIntentActivities(query, 0)) {
            if (info.activityInfo != null && !getPackageName().equals(info.activityInfo.packageName)) packages.add(info.activityInfo.packageName);
        }
        for (String name : packages) {
            try { manager.killBackgroundProcesses(name); } catch (Exception ignored) { }
        }
        if (home != null) home.setNotice("已向 " + packages.size() + " 个应用发送后台清理请求");
    }

    @Override public boolean isDeviceOwner() {
        if (Build.VERSION.SDK_INT < 21) return false;
        try {
            android.app.admin.DevicePolicyManager manager = (android.app.admin.DevicePolicyManager) getSystemService(Context.DEVICE_POLICY_SERVICE);
            return manager.isDeviceOwnerApp(getPackageName());
        } catch (Exception e) { return false; }
    }

    @Override public boolean ensureStorageAccess() {
        if (Build.VERSION.SDK_INT >= 30) {
            try {
                boolean allowed = (Boolean) Environment.class.getMethod("isExternalStorageManager").invoke(null);
                if (allowed) return true;
                Intent access = new Intent("android.settings.MANAGE_APP_ALL_FILES_ACCESS_PERMISSION", Uri.parse("package:" + getPackageName()));
                startActivity(access);
            } catch (Exception e) {
                try { startActivity(new Intent("android.settings.MANAGE_ALL_FILES_ACCESS_PERMISSION")); }
                catch (Exception ignored) { if (home != null) home.setNotice("此系统未提供全文件访问设置"); }
            }
            return false;
        }
        if (Build.VERSION.SDK_INT >= 23) {
            int read = checkSelfPermission(android.Manifest.permission.READ_EXTERNAL_STORAGE);
            int write = checkSelfPermission(android.Manifest.permission.WRITE_EXTERNAL_STORAGE);
            if (read != PackageManager.PERMISSION_GRANTED || write != PackageManager.PERMISSION_GRANTED) {
                requestPermissions(new String[]{android.Manifest.permission.READ_EXTERNAL_STORAGE, android.Manifest.permission.WRITE_EXTERNAL_STORAGE}, STORAGE_PERMISSION_REQUEST);
                return false;
            }
        }
        return true;
    }

    @Override public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] results) {
        super.onRequestPermissionsResult(requestCode, permissions, results);
        if (requestCode == STORAGE_PERMISSION_REQUEST && home != null) {
            home.setNotice(results.length > 0 && results[0] == PackageManager.PERMISSION_GRANTED ? "文件访问权限已授予，可重新进入文件管理" : "未授予文件访问权限");
        }
    }

    @Override public void startLockMode() {
        if (!isDeviceOwner()) { home.setNotice("需先配置设备所有者"); return; }
        try { getClass().getMethod("startLockTask").invoke(this); }
        catch (Exception e) { home.setNotice("无法进入锁定模式"); }
    }

    @Override public void setAutoStart(boolean enabled) {
        getSharedPreferences("settings", 0).edit().putBoolean("autostart", enabled).apply();
    }

    @Override public void setAppLanguage(String mode) {
        boolean english = "en".equals(mode) || ("system".equals(mode) && !Locale.getDefault().getLanguage().toLowerCase(Locale.US).startsWith("zh"));
        PackageManager pm = getPackageManager();
        try {
            pm.setComponentEnabledSetting(new android.content.ComponentName(this, "com.pctutu.tvhome.ChineseLauncher"), english ? PackageManager.COMPONENT_ENABLED_STATE_DISABLED : PackageManager.COMPONENT_ENABLED_STATE_ENABLED, PackageManager.DONT_KILL_APP);
            pm.setComponentEnabledSetting(new android.content.ComponentName(this, "com.pctutu.tvhome.EnglishLauncher"), english ? PackageManager.COMPONENT_ENABLED_STATE_ENABLED : PackageManager.COMPONENT_ENABLED_STATE_DISABLED, PackageManager.DONT_KILL_APP);
        } catch (Exception ignored) { }
    }

    @Override public boolean isAutoStartEnabled() {
        return getSharedPreferences("settings", 0).getBoolean("autostart", false);
    }

    @Override public void requestWeather(String city) {
        if (city == null || city.trim().length() == 0) return;
        getPreferences(0).edit().putString("weather_city", city.trim()).apply();
        weatherProvider = new MetNorwayWeatherProvider(city.trim());
        weatherProvider.request(new WeatherProvider.Callback() {
            @Override public void onWeather(WeatherProvider.WeatherData data) {
                getSharedPreferences(WEATHER_CACHE, 0).edit().putString("city", data.city).putString("condition", data.condition).putInt("temperature", data.temperatureC).putInt("humidity", data.humidity).apply();
                runOnUiThread(() -> { if (home != null) home.setWeather(data, getPreferences(0).getBoolean("weather_fahrenheit", false)); });
            }
            @Override public void onError(String message) {
                Log.w("SuperHomeWeather", "MET Norway request failed: " + message);
                runOnUiThread(() -> {
                    if (home != null) {
                        android.content.SharedPreferences cached = getSharedPreferences(WEATHER_CACHE, 0);
                        if (cached.contains("temperature")) home.setNotice("天气网络暂不可用，已显示最近数据"); else home.setNotice("天气网络暂不可用，保留当前天气显示");
                    }
                });
            }
        });
    }

    @Override public String getWeatherCity() { return getPreferences(0).getString("weather_city", "北京"); }
    @Override public boolean isFahrenheit() { return getPreferences(0).getBoolean("weather_fahrenheit", false); }
    @Override public void setFahrenheit(boolean value) {
        getPreferences(0).edit().putBoolean("weather_fahrenheit", value).apply();
        requestWeather(getWeatherCity());
    }

    @Override public File getStorageRoot() { return Environment.getExternalStorageDirectory(); }

    @Override public void openFile(File file) {
        Intent intent = new Intent(Intent.ACTION_VIEW);
        Uri uri = new Uri.Builder().scheme("content").authority("com.pctutu.tvhome.files").appendPath("file").appendPath(file.getAbsolutePath()).build();
        String type = getContentResolver().getType(uri);
        intent.setDataAndType(uri, type == null ? "application/octet-stream" : type);
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_GRANT_READ_URI_PERMISSION);
        try { startActivity(intent); }
        catch (Exception e) { home.setNotice("没有可打开此文件的应用"); }
    }

    @Override public void installApk(File file) {
        if (Build.VERSION.SDK_INT >= 21) {
            try {
                PackageInstaller installer = getPackageManager().getPackageInstaller();
                PackageInstaller.SessionParams params = new PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL);
                int sessionId = installer.createSession(params);
                PackageInstaller.Session session = installer.openSession(sessionId);
                OutputStream output = session.openWrite("base.apk", 0, file.length());
                InputStream input = new FileInputStream(file);
                byte[] buffer = new byte[32768]; int count;
                while ((count = input.read(buffer)) > 0) output.write(buffer, 0, count);
                input.close(); output.close();
                Intent done = new Intent(this, MainActivity.class).setAction("com.pctutu.tvhome.INSTALL_RESULT");
                int flags = PendingIntent.FLAG_UPDATE_CURRENT;
                if (Build.VERSION.SDK_INT >= 31) flags |= 0x04000000;
                session.commit(PendingIntent.getActivity(this, sessionId, done, flags).getIntentSender());
                session.close();
                home.setNotice("已提交安装");
                return;
            } catch (Exception e) { home.setNotice("应用内安装失败，尝试系统安装"); }
        }
        Intent intent = new Intent(Intent.ACTION_VIEW);
        intent.setDataAndType(Uri.fromFile(file), "application/vnd.android.package-archive");
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        try { startActivity(intent); }
        catch (Exception e) { home.setNotice("Android 4 需要系统安装确认"); }
    }

    @Override public void onUserInteraction() { super.onUserInteraction(); if (home != null) home.resetIdle(); }

    private void handleInstallResult(Intent intent) {
        if (intent == null || !"com.pctutu.tvhome.INSTALL_RESULT".equals(intent.getAction())) return;
        int status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, Integer.MIN_VALUE);
        if (status == PackageInstaller.STATUS_SUCCESS) home.setNotice("安装成功");
        else if (status == PackageInstaller.STATUS_PENDING_USER_ACTION) {
            try {
                Intent confirmation = intent.getParcelableExtra(Intent.EXTRA_INTENT);
                if (confirmation != null) { confirmation.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK); startActivity(confirmation); }
                else home.setNotice("请完成系统安装确认");
            } catch (Exception e) { home.setNotice("请完成系统安装确认"); }
        }
        else if (status != Integer.MIN_VALUE) home.setNotice("安装失败：" + intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE));
    }
}
