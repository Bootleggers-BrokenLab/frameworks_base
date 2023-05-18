package com.android.server.ext;

import android.Manifest;
import android.annotation.Nullable;
import android.annotation.UserIdInt;
import android.app.AppBindArgs;
import android.content.pm.PackageManager;
import android.os.Binder;
import android.os.Bundle;
import android.os.UserHandle;
import android.util.ArraySet;

import com.android.internal.util.GoogleEuicc;
import com.android.server.pm.PackageManagerService;
import com.android.server.pm.parsing.pkg.AndroidPackage;
import com.android.server.pm.pkg.PackageStateInternal;
import com.android.server.pm.pkg.parsing.ParsingPackage;

public class PackageManagerHooks {

    // Called when package enabled setting is deserialized from storage
    @Nullable
    public static Integer maybeOverridePackageEnabledSetting(String pkgName, @UserIdInt int userId) {
        switch (pkgName) {
            case GoogleEuicc.EUICC_SUPPORT_PIXEL_PKG_NAME:
                if (userId == UserHandle.USER_SYSTEM) {
                    // EuiccSupportPixel handles firmware updates and should always be enabled.
                    // It was previously unconditionally disabled after reboot.
                    return PackageManager.COMPONENT_ENABLED_STATE_DEFAULT;
                } else {
                    // one of the previous OS versions enabled EuiccSupportPixel in all users
                    return PackageManager.COMPONENT_ENABLED_STATE_DISABLED;
                }
            case GoogleEuicc.LPA_PKG_NAME:
                // Google's LPA should be always disabled after reboot
                return PackageManager.COMPONENT_ENABLED_STATE_DISABLED;
            default:
                return null;
        }
    }

    // Called when package parsing is completed
    public static void amendParsedPackage(ParsingPackage pkg) {
        String pkgName = pkg.getPackageName();

        switch (pkgName) {
            case GoogleEuicc.EUICC_SUPPORT_PIXEL_PKG_NAME:
                // EuiccSupportPixel uses INTERNET perm only as part of its dev mode
                removeUsesPermissions(pkg, Manifest.permission.INTERNET);
                return;
            case GoogleEuicc.LPA_PKG_NAME:
                // this is the same as android:enabled="false" in <application> AndroidManifest tag,
                // it makes the package disabled by default on first boot, when there's no
                // serialized package state
                pkg.setEnabled(false);
                return;
            default:
                return;
        }
    }

    public static void removeUsesPermissions(ParsingPackage pkg, String... perms) {
        var set = new ArraySet<>(perms);
        pkg.getRequestedPermissions().removeAll(set);
        pkg.getUsesPermissions().removeIf(p -> set.contains(p.getName()));
    }

    @Nullable
    public static Bundle getExtraAppBindArgs(PackageManagerService pm, String packageName) {
        final int callingUid = Binder.getCallingUid();
        final int appId = UserHandle.getAppId(callingUid);
        final int userId = UserHandle.getUserId(callingUid);

        AndroidPackage pkg = pm.snapshotComputer().getPackage(packageName);
        if (pkg == null) {
            return null;
        }

        if (pkg.getUid() != appId) { // getUid() actually returns appId, it has a historic name
            return null;
        }

        int[] flagsArr = new int[AppBindArgs.FLAGS_ARRAY_LEN];

        var b = new Bundle();
        b.putIntArray(AppBindArgs.KEY_FLAGS_ARRAY, flagsArr);

        return b;
    }

    // Called when AppsFilter decides whether to restrict package visibility
    public static boolean shouldFilterAccess(@Nullable PackageStateInternal callingPkgSetting,
                                             ArraySet<PackageStateInternal> callingSharedPkgSettings,
                                             PackageStateInternal targetPkgSetting) {
        if (callingPkgSetting != null && restrictedVisibilityPackages.contains(callingPkgSetting.getPackageName())) {
            if (!targetPkgSetting.isSystem()) {
                return true;
            }
        }

        if (restrictedVisibilityPackages.contains(targetPkgSetting.getPackageName())) {
            if (callingPkgSetting != null) {
                return !callingPkgSetting.isSystem();
            } else {
                for (int i = callingSharedPkgSettings.size() - 1; i >= 0; i--) {
                    if (!callingSharedPkgSettings.valueAt(i).isSystem()) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    // Packages in this array are restricted from interacting with and being interacted by non-system apps
    private static final ArraySet<String> restrictedVisibilityPackages = new ArraySet<>(new String[] {
        GoogleEuicc.EUICC_SUPPORT_PIXEL_PKG_NAME,
    });
}
