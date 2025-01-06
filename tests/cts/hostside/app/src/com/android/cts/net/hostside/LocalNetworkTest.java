package com.android.cts.net.hostside;

import static com.google.common.truth.Truth.assertThat;

import android.Manifest;
import android.app.compat.CompatChanges;
import android.content.Context;
import android.platform.test.annotations.RequiresFlagsEnabled;
import android.platform.test.flag.junit.CheckFlagsRule;
import android.platform.test.flag.junit.DeviceFlagsValueProvider;
import android.util.Log;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;

@RunWith(AndroidJUnit4.class)
public class LocalNetworkTest {

    @Rule
    public final CheckFlagsRule mCheckFlagsRule =
            DeviceFlagsValueProvider.createCheckFlagsRule();

//    private final Context mContext = InstrumentationRegistry.getInstrumentation().getContext();

    @Before
    public void setUp() {
        InstrumentationRegistry.getInstrumentation().getUiAutomation()
                .adoptShellPermissionIdentity(Manifest.permission.LOG_COMPAT_CHANGE,
                        Manifest.permission.READ_COMPAT_CHANGE_CONFIG);
    }

    @Test
    @RequiresFlagsEnabled("com.android.net.flags.restrict_local_network")
    public void permissionChanged_checkLocalNetworkRestrictions() {
//        Context mContext = InstrumentationRegistry.getInstrumentation().getTargetContext();
//        Log.d("LocalNetworkTest", "package name is " + mContext.getPackageName());
//        assertThat(CompatChanges.isChangeEnabled(365139289L, mContext.getPackageName(),
//                mContext.getUser())).isTrue();
    }
}
