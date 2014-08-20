/*
 * Created by Sven Dawitz; Copyright (C) 2011 CyanogenMod Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.systemui.statusbar;

import java.net.URISyntaxException;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;

import android.app.ActivityManager;
import android.app.ActivityManager.RunningAppProcessInfo;
import android.content.ActivityNotFoundException;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.database.ContentObserver;
import android.os.Handler;
import android.os.Process;
import android.os.RemoteException;
import android.os.ServiceManager;
import android.provider.CmSystem;
import android.provider.Settings;
import android.util.AttributeSet;
import android.util.Slog;
import android.view.IWindowManager;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageButton;

import com.android.systemui.R;

public class CmStatusBarView extends StatusBarView {
    private static final boolean DEBUG = false;
    private static final String TAG = "CmStatusBarView";

    ViewGroup mIcons;
    boolean mIsBottom;

    // used for fullscreen handling and broadcasts
    ActivityManager mActivityManager;
    RunningAppProcessInfo mFsCallerProcess;
    ComponentName mFsCallerActivity;
    Intent mFsForceIntent;
    Intent mFsOffIntent;

    Handler mHandler;

    class FullscreenReceiver extends BroadcastReceiver {
        public void onReceive(Context context, Intent intent){
            onFullscreenAttempt();
        }
    }

    class SettingsObserver extends ContentObserver {
        SettingsObserver(Handler handler) {
            super(handler);
        }

        void observe() {
            ContentResolver resolver = mContext.getContentResolver();
            resolver.registerContentObserver(
                    Settings.System.getUriFor(Settings.System.STATUS_BAR_BOTTOM), false, this);
            onChange(true);
        }

        @Override
        public void onChange(boolean selfChange) {
            ContentResolver resolver = mContext.getContentResolver();
            int defValue;

            defValue=(CmSystem.getDefaultBool(mContext, CmSystem.CM_DEFAULT_BOTTOM_STATUS_BAR) ? 1 : 0);
            mIsBottom = (Settings.System.getInt(resolver,
                    Settings.System.STATUS_BAR_BOTTOM, defValue) == 1);
        }
    }

    public CmStatusBarView(Context context, AttributeSet attrs) {
        super(context, attrs);
    }

    @Override
    protected void onFinishInflate(){
        super.onFinishInflate();
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
    }

    private boolean isStillActive(RunningAppProcessInfo process, ComponentName activity)
    {
        // activity can be null in cases, where one app starts another. for example, astro
        // starting rock player when a movie file was clicked. we dont have an activity then,
        // but the package exits as soon as back is hit. so we can ignore the activity
        // in this case
        if(process==null)
            return false;

        RunningAppProcessInfo currentFg=getForegroundApp();
        ComponentName currentActivity=getActivityForApp(currentFg);

        // in some cases, we cannot get any foreground app - we ignore that and return still active, since
        // its kinda impossible and is a hint to the system being busy or bad stuff happens
        if(currentFg==null){
            if(DEBUG) Slog.i(TAG, "isStillActive() returned no foreground activity. this is impossible and so ignored");
            return true;
        }

        if(currentFg!=null && currentFg.processName.equals(process.processName) &&
                (activity==null || currentActivity.compareTo(activity)==0))
            return true;

        Slog.i(TAG, "isStillActive returns false - CallerProcess: " + process.processName + " CurrentProcess: "
                + (currentFg==null ? "null" : currentFg.processName) + " CallerActivity:" + (activity==null ? "null" : activity.toString())
                + " CurrentActivity: " + (currentActivity==null ? "null" : currentActivity.toString()));
        return false;
    }

    private ComponentName getActivityForApp(RunningAppProcessInfo target){
        ComponentName result=null;
        ActivityManager.RunningTaskInfo info;

        if(target==null)
            return null;

        if(mActivityManager==null)
            mActivityManager = (ActivityManager)mContext.getSystemService(Context.ACTIVITY_SERVICE);
        List <ActivityManager.RunningTaskInfo> l = mActivityManager.getRunningTasks(9999);
        Iterator <ActivityManager.RunningTaskInfo> i = l.iterator();

        while(i.hasNext()){
            info=i.next();
            if(target.processName.contains(info.baseActivity.getPackageName())){
                if(DEBUG) Slog.i(TAG, "getActivityForApp(" + target.processName + ") found the following activity (topActivity /// baseActivity): "
                        + info.topActivity.toString() + " /// " + info.baseActivity.toString());
                result=info.topActivity;
                break;
            }
        }

        return result;
    }

    private RunningAppProcessInfo getForegroundApp() {
        RunningAppProcessInfo result=null, info=null;

        if(mActivityManager==null)
            mActivityManager = (ActivityManager)mContext.getSystemService(Context.ACTIVITY_SERVICE);
        List <RunningAppProcessInfo> l = mActivityManager.getRunningAppProcesses();
        Iterator <RunningAppProcessInfo> i = l.iterator();
        while(i.hasNext()){
            info = i.next();
            if(info.uid >= Process.FIRST_APPLICATION_UID && info.uid <= Process.LAST_APPLICATION_UID
                    && info.importance == RunningAppProcessInfo.IMPORTANCE_FOREGROUND){
                result=info;
                break;
            }
        }
        return result;
    }

    private void onFullscreenAttempt()
    {
        mFsCallerProcess=null;
        mFsCallerActivity=null;
    }

    private void runCustomApp(String uri) {
        if (uri != null) {
            try {
                Intent i = Intent.parseUri(uri, 0);
                i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                        | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
                getContext().startActivity(i);
            } catch (URISyntaxException e) {

            } catch (ActivityNotFoundException e) {

            }
        }
    }

    /**
     * Runnable to hold simulate a keypress.
     *
     * This is executed in a separate Thread to avoid blocking
     */
    private void simulateKeypress(final int keyCode) {
        new Thread( new KeyEventInjector( keyCode ) ).start();
    }

    private class KeyEventInjector implements Runnable {
        private int keyCode;

        KeyEventInjector(final int keyCode) {
            this.keyCode = keyCode;
        }

        public void run() {
            try {
                if(! (IWindowManager.Stub
                    .asInterface(ServiceManager.getService("window")))
                         .injectKeyEvent(
                              new KeyEvent(KeyEvent.ACTION_DOWN, keyCode), true) ) {
                                   Slog.w(TAG, "Key down event not injected");
                                   return;
                              }
                if(! (IWindowManager.Stub
                    .asInterface(ServiceManager.getService("window")))
                         .injectKeyEvent(
                             new KeyEvent(KeyEvent.ACTION_UP, keyCode), true) ) {
                                  Slog.w(TAG, "Key up event not injected");
                             }
           } catch(RemoteException ex) {
               Slog.w(TAG, "Error injecting key event", ex);
           }
        }
    }
}
