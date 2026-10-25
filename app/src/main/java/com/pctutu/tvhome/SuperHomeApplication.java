package com.pctutu.tvhome;

import android.app.Application;
import android.os.Environment;
import android.util.Log;
import java.io.File;
import java.io.FileOutputStream;
import java.io.PrintWriter;
import java.io.StringWriter;

/** Keeps a readable crash report on devices where adb is unavailable. */
public final class SuperHomeApplication extends Application {
    @Override public void onCreate() {
        super.onCreate();
        final Thread.UncaughtExceptionHandler previous = Thread.getDefaultUncaughtExceptionHandler();
        Thread.setDefaultUncaughtExceptionHandler(new Thread.UncaughtExceptionHandler() {
            @Override public void uncaughtException(Thread thread, Throwable error) {
                StringWriter sw = new StringWriter();
                error.printStackTrace(new PrintWriter(sw));
                String report = "SuperHome crash\n" + sw.toString();
                Log.e("SuperHome", report);
                try {
                    File dir = new File(Environment.getExternalStorageDirectory(), "SuperHome");
                    if (!dir.exists()) dir.mkdirs();
                    FileOutputStream out = new FileOutputStream(new File(dir, "crash.log"), false);
                    out.write(report.getBytes("UTF-8"));
                    out.close();
                } catch (Throwable ignored) { }
                if (previous != null) previous.uncaughtException(thread, error);
                else android.os.Process.killProcess(android.os.Process.myPid());
            }
        });
    }
}
