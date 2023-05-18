package com.android.server.ext;

import android.Manifest;
import android.annotation.Nullable;
import android.annotation.UserIdInt;
import android.app.AppBindArgs;
import android.content.pm.GosPackageState;
import android.content.pm.PackageManager;
import android.os.Binder;
import android.os.Bundle;
import android.os.UserHandle;
import android.util.ArraySet;

import com.android.server.pm.GosPackageStatePmHooks;
import com.android.server.pm.PackageManagerService;
import com.android.server.pm.parsing.pkg.AndroidPackage;
import com.android.server.pm.pkg.PackageStateInternal;
import com.android.server.pm.pkg.parsing.ParsingPackage;

public class PackageManagerHooks {

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

        GosPackageState gosPs = GosPackageStatePmHooks.get(pm, callingUid, packageName, userId);

        int[] flagsArr = new int[AppBindArgs.FLAGS_ARRAY_LEN];

        var b = new Bundle();
        b.putParcelable(AppBindArgs.KEY_GOS_PACKAGE_STATE, gosPs);
        b.putIntArray(AppBindArgs.KEY_FLAGS_ARRAY, flagsArr);

        return b;
    }
}
