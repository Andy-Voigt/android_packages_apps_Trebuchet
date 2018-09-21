/*
 * Copyright (C) 2016 The Android Open Source Project
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

package com.android.launcher3.shortcuts;

import android.annotation.TargetApi;
import android.content.ComponentName;
import android.content.Context;
import android.content.pm.LauncherApps;
import android.content.pm.LauncherApps.ShortcutQuery;
import android.content.pm.PackageManager;
import android.content.pm.ShortcutInfo;
import android.content.res.Resources;
import android.graphics.Rect;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.Icon;
import android.os.Build;
import android.os.Bundle;
import android.os.UserHandle;
import android.util.Log;

import com.android.launcher3.LauncherAppState;
import com.android.launcher3.Utilities;
import com.android.launcher3.icons.CustomIconsProvider;
import com.android.launcher3.shortcuts.DeepShortcutManager;
import com.android.launcher3.shortcuts.ShortcutInfoCompat;
import com.android.launcher3.shortcuts.ShortcutKey;
import com.android.launcher3.shortcuts.backport.ShortcutPackage;
import com.android.launcher3.shortcuts.backport.ShortcutParser;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * Performs operations related to deep shortcuts, such as querying for them, pinning them, etc.
 */
@TargetApi(25)
public class DeepShortcutManagerNative extends DeepShortcutManager {
    private static final String TAG = "DeepShortcutManager";

    private static final int FLAG_GET_ALL = ShortcutQuery.FLAG_MATCH_DYNAMIC
            | ShortcutQuery.FLAG_MATCH_MANIFEST | ShortcutQuery.FLAG_MATCH_PINNED;

    private final LauncherApps mLauncherApps;
    private boolean mWasLastCallSuccess;

    protected DeepShortcutManagerNative(Context context) {
        mLauncherApps = (LauncherApps) context.getSystemService(Context.LAUNCHER_APPS_SERVICE);
    }

    public boolean wasLastCallSuccess() {
        return mWasLastCallSuccess;
    }

    public void onShortcutsChanged(List<ShortcutInfoCompat> shortcuts) {
        // mShortcutCache.removeShortcuts(shortcuts);
    }

    /**
     * Queries for the shortcuts with the package name and provided ids.
     *
     * This method is intended to get the full details for shortcuts when they are added or updated,
     * because we only get "key" fields in onShortcutsChanged().
     */
    public List<ShortcutInfoCompat> queryForFullDetails(String packageName,
                                                        List<String> shortcutIds, UserHandle user) {
        return query(FLAG_GET_ALL, packageName, null, shortcutIds, user);
    }

    /**
     * Gets all the manifest and dynamic shortcuts associated with the given package and user,
     * to be displayed in the shortcuts container on long press.
     */
    public List<ShortcutInfoCompat> queryForShortcutsContainer(ComponentName activity,
                                                               List<String> ids, UserHandle user) {
        return query(ShortcutQuery.FLAG_MATCH_MANIFEST | ShortcutQuery.FLAG_MATCH_DYNAMIC,
                activity.getPackageName(), activity, ids, user);
    }

    /**
     * Removes the given shortcut from the current list of pinned shortcuts.
     * (Runs on background thread)
     */
    public void unpinShortcut(final ShortcutKey key) {
        if (Utilities.ATLEAST_NOUGAT_MR1) {
            String packageName = key.componentName.getPackageName();
            String id = key.getId();
            UserHandle user = key.user;
            List<String> pinnedIds = extractIds(queryForPinnedShortcuts(packageName, user));
            pinnedIds.remove(id);
            try {
                mLauncherApps.pinShortcuts(packageName, pinnedIds, user);
                mWasLastCallSuccess = true;
            } catch (SecurityException|IllegalStateException e) {
                Log.w(TAG, "Failed to unpin shortcut", e);
                mWasLastCallSuccess = false;
            }
        }
    }

    /**
     * Adds the given shortcut to the current list of pinned shortcuts.
     * (Runs on background thread)
     */
    public void pinShortcut(final ShortcutKey key) {
        if (Utilities.ATLEAST_NOUGAT_MR1) {
            String packageName = key.componentName.getPackageName();
            String id = key.getId();
            UserHandle user = key.user;
            List<String> pinnedIds = extractIds(queryForPinnedShortcuts(packageName, user));
            pinnedIds.add(id);
            try {
                mLauncherApps.pinShortcuts(packageName, pinnedIds, user);
                mWasLastCallSuccess = true;
            } catch (SecurityException|IllegalStateException e) {
                Log.w(TAG, "Failed to pin shortcut", e);
                mWasLastCallSuccess = false;
            }
        }
    }

    public void startShortcut(String packageName, String id, Rect sourceBounds,
                              Bundle startActivityOptions, UserHandle user) {
        if (Utilities.ATLEAST_NOUGAT_MR1) {
            try {
                mLauncherApps.startShortcut(packageName, id, sourceBounds,
                        startActivityOptions, user);
                mWasLastCallSuccess = true;
            } catch (SecurityException|IllegalStateException e) {
                Log.e(TAG, "Failed to start shortcut", e);
                mWasLastCallSuccess = false;
            }
        }
    }

    /*public Drawable getShortcutAdaptiveIconForNougatMR1(ShortcutInfoCompat shortcutInfo) {
        String packageName = shortcutInfo.getActivity().getPackageName();
        Context context = LauncherAppState.getInstanceNoCreate().getContext();
        try {
            ShortcutPackage shortcutPackage = new ShortcutPackage(context, packageName);
            ArrayList<ShortcutInfoCompat> shortcuts = shortcutPackage.getAllShortcuts();
            Map<ComponentName, ShortcutParser> shortcutMap = shortcutPackage.getShortcutsMap();
            for (Map.Entry<ComponentName, ShortcutParser> entry : shortcutMap.entrySet()) {
                if (entry.getValue().i)
                addToDB(packageName, entry.getKey(), entry.getValue().getResId());
            }
        }catch (Exception e){}
    }*/

    private int getResId(ShortcutInfoCompat shortcutInfo) {
        int resId = 0;
        try {
            Method getIconResourceId = ShortcutInfo.class.getDeclaredMethod("getIconResourceId");
            getIconResourceId.setAccessible(true);
            resId = (int)getIconResourceId.invoke(shortcutInfo.getShortcutInfo());
            if (resId != 0) return resId;
            Method getIcon = ShortcutInfo.class.getDeclaredMethod("getIcon");
            Method getIconId = Icon.class.getDeclaredMethod("getResId");
            getIcon.setAccessible(true);
            getIconId.setAccessible(true);
            Icon icon = (Icon) getIcon.invoke(shortcutInfo.getShortcutInfo());
            if (icon!=null) resId = (int) getIconId.invoke(icon);
        }
        catch (Exception e){}
        return resId;
    }

    public Drawable getShortcutIconDrawable(ShortcutInfoCompat shortcutInfo, int density) {
        if (Utilities.ATLEAST_NOUGAT_MR1) {
            Context context = LauncherAppState.getInstanceNoCreate().getContext();
            if (!Utilities.isAdaptiveIconDisabled(context) && Build.VERSION.SDK_INT == Build.VERSION_CODES.N_MR1) {
                int resId = getResId(shortcutInfo);
                PackageManager mPackageManager = LauncherAppState.getInstanceNoCreate().getContext().getPackageManager();
                Resources resources = null;
                try {resources = mPackageManager.getResourcesForApplication(shortcutInfo.getPackage());}catch (Exception e){}
                Drawable icon = null;
                if (resId!=0 && resources!=null) icon = CustomIconsProvider.getDeepShortcutIconBackport(resId, resources);
                if (icon!=null) {mWasLastCallSuccess = true;return icon;}
            }
            try {
                Drawable icon = mLauncherApps.getShortcutIconDrawable(
                        shortcutInfo.getShortcutInfo(), density);
                mWasLastCallSuccess = true;
                return icon;
            } catch (SecurityException|IllegalStateException e) {
                Log.e(TAG, "Failed to get shortcut icon", e);
                mWasLastCallSuccess = false;
            }
        }
        return null;
    }

    protected List<String> extractIds(List<ShortcutInfoCompat> shortcuts) {
        List<String> shortcutIds = new ArrayList<>(shortcuts.size());
        for (ShortcutInfoCompat shortcut : shortcuts) {
            shortcutIds.add(shortcut.getId());
        }
        return shortcutIds;
    }

    /**
     * Query the system server for all the shortcuts matching the given parameters.
     * If packageName == null, we query for all shortcuts with the passed flags, regardless of app.
     *
     * TODO: Use the cache to optimize this so we don't make an RPC every time.
     */
    protected List<ShortcutInfoCompat> query(int flags, String packageName,
                                             ComponentName activity, List<String> shortcutIds, UserHandle user) {
        if (Utilities.ATLEAST_NOUGAT_MR1) {
            ShortcutQuery q = new ShortcutQuery();
            q.setQueryFlags(flags);
            if (packageName != null) {
                q.setPackage(packageName);
                q.setActivity(activity);
                q.setShortcutIds(shortcutIds);
            }
            List<ShortcutInfo> shortcutInfos = null;
            try {
                shortcutInfos = mLauncherApps.getShortcuts(q, user);
                mWasLastCallSuccess = true;
            } catch (SecurityException|IllegalStateException e) {
                Log.e(TAG, "Failed to query for shortcuts", e);
                mWasLastCallSuccess = false;
            }
            if (shortcutInfos == null) {
                return Collections.EMPTY_LIST;
            }
            List<ShortcutInfoCompat> shortcutInfoCompats = new ArrayList<>(shortcutInfos.size());
            for (ShortcutInfo shortcutInfo : shortcutInfos) {
                shortcutInfoCompats.add(new ShortcutInfoCompat(shortcutInfo));
            }
            return shortcutInfoCompats;
        } else {
            return Collections.EMPTY_LIST;
        }
    }

    public boolean hasHostPermission() {
        if (Utilities.ATLEAST_NOUGAT_MR1) {
            try {
                return mLauncherApps.hasShortcutHostPermission();
            } catch (SecurityException|IllegalStateException e) {
                Log.e(TAG, "Failed to make shortcut manager call", e);
            }
        }
        return false;
    }
}