/*
 * Copyright (C) 2021 The Android Open Source Project
 *           (C) 2022 Paranoid Android
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
 *
 * Changes from Qualcomm Innovation Center are provided under the following license:
 *
 * Copyright (c) 2022 Qualcomm Innovation Center, Inc. All rights reserved.
 * SPDX-License-Identifier: BSD-3-Clause-Clear
 */

package com.android.systemui.qs.tiles.dialog;

import android.content.Context;
import android.content.Intent;
import android.content.res.TypedArray;
import android.graphics.drawable.Drawable;
import android.os.Bundle;
import android.os.Handler;
import android.provider.Settings;
import android.text.TextUtils;
import android.util.Log;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.Switch;
import android.widget.TextView;

import androidx.annotation.MainThread;
import androidx.annotation.Nullable;
import androidx.annotation.VisibleForTesting;
import androidx.annotation.WorkerThread;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.android.internal.util.aicp.OmniJawsClient;
import com.android.settingslib.Utils;
import com.android.systemui.Prefs;
import com.android.systemui.R;
import com.android.systemui.animation.ActivityLaunchAnimator;
import com.android.systemui.animation.DialogLaunchAnimator;
import com.android.systemui.dagger.SysUISingleton;
import com.android.systemui.dagger.qualifiers.Background;
import com.android.systemui.dagger.qualifiers.Main;
import com.android.systemui.omni.DetailedWeatherView;
import com.android.systemui.plugins.ActivityStarter;
import com.android.systemui.statusbar.phone.SystemUIDialog;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.concurrent.Executor;

/**
 * Dialog for weather
 */
@SysUISingleton
public class WeatherDialog extends SystemUIDialog implements Window.Callback, OmniJawsClient.OmniJawsObserver  {
    private static final String TAG = "WeatherDialog";
    private static final boolean DEBUG = true;

    private WeatherDialogFactory mWeatherDialogFactory;
    private Context mContext;
    private Handler mHandler;
    private View mDialogView;
    private boolean mEnabled;
    private TextView mWeatherDialogTitle;
    private TextView mWeatherDialogSubTitle;
    private DetailedWeatherView mDetailedView;
    private OmniJawsClient mWeatherClient;
    private OmniJawsClient.WeatherInfo mWeatherData;
    private Button mDoneButton;
    private Button mSettingsButton;
    private DialogLaunchAnimator mDialogLaunchAnimator;
    private ActivityStarter mActivityStarter;

    public WeatherDialog(Context context, WeatherDialogFactory weatherDialogFactory,
            boolean aboveStatusBar, @Main Handler handler, ActivityStarter activityStarter,
            DialogLaunchAnimator dialogLaunchAnimator) {
        super(context);
        if (DEBUG) {
            Log.d(TAG, "Init WeatherDialog");
        }

        // Save the context that is wrapped with our theme.
        mContext = getContext();
        mHandler = handler;
        mWeatherClient = new OmniJawsClient(mContext);
        mWeatherDialogFactory = weatherDialogFactory;
        mActivityStarter = activityStarter;
        mDialogLaunchAnimator = dialogLaunchAnimator;
        mEnabled = mWeatherClient.isOmniJawsEnabled();

        if (!aboveStatusBar) {
            getWindow().setType(WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY);
        }
    }

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        if (DEBUG) {
            Log.d(TAG, "onCreate");
        }
        mDialogView = LayoutInflater.from(mContext).inflate(R.layout.weather_dialog, null);
        final Window window = getWindow();
        window.setContentView(mDialogView);
        window.setWindowAnimations(R.style.Animation_InternetDialog);
        mWeatherData = null;
        mWeatherClient.setOmniJawsEnabled(true);
        mDetailedView = (DetailedWeatherView) mDialogView.requireViewById(R.id.detailed_weather_view);

        mWeatherDialogTitle = mDialogView.requireViewById(R.id.weather_dialog_title);
        mWeatherDialogSubTitle = mDialogView.requireViewById(R.id.weather_dialog_subtitle);
        mDoneButton = mDialogView.requireViewById(R.id.done_button);
        mSettingsButton = mDialogView.requireViewById(R.id.settings_button);

        // Should we?
        //updateDialog();

        mDoneButton.setOnClickListener(v -> dismissDialog());
        mSettingsButton.setOnClickListener(v -> {
            startActivity(mWeatherClient.getSettingsIntent(),v);
        });
    }

    @Override
    public void onStart() {
        super.onStart();
        if (DEBUG) {
            Log.d(TAG, "onStart");
        }
        mDetailedView.startProgress();
    }

    @Override
    public void onStop() {
        super.onStop();
        if (DEBUG) {
            Log.d(TAG, "onStop");
        }
        mDetailedView.stopProgress();
        mDetailedView.post(() -> {
            mDetailedView.updateWeatherData(null);
        });
        // make sure we dont left one
        mWeatherClient.removeObserver(this);
        mWeatherClient.cleanupObserver();
        mDoneButton.setOnClickListener(null);
        mSettingsButton.setOnClickListener(null);
        mWeatherDialogFactory.destroyDialog();
    }

    public void dismissDialog() {
        if (DEBUG) {
            Log.d(TAG, "dismissDialog");
        }
        mWeatherDialogFactory.destroyDialog();
        dismiss();
    }

    @Override
    public void weatherUpdated() {
        if (DEBUG) Log.d(TAG, "weatherUpdated");
        updateDialog();
    }

    @Override
    public void weatherError(int errorReason) {
        if (DEBUG) Log.d(TAG, "weatherError " + errorReason);
        if (errorReason != OmniJawsClient.EXTRA_ERROR_DISABLED) {
            mWeatherDialogSubTitle.setText(mContext.getResources()
                    .getString(R.string.omnijaws_service_error));
            mDetailedView.weatherError(errorReason);
        }
    }

    void startActivity(Intent intent, View view) {
        ActivityLaunchAnimator.Controller controller =
                mDialogLaunchAnimator.createActivityLaunchController(view);

        if (controller == null) {
            dismissDialog();
        }

        mActivityStarter.postStartActivityDismissingKeyguard(intent, 0, controller);
    }

    /**
     * Update the weather dialog when receiving the callback.
     */
    void updateDialog() {
        if (DEBUG) {
            Log.d(TAG, "updateDialog");
        }
        mDetailedView.updateWeatherData(mWeatherData);
        String weatherSubTitle = mContext.getResources().getString(R.string.omnijaws_service_unkown);
        try {
            if (mEnabled) {
                mWeatherClient.queryWeather();
                mWeatherData = mWeatherClient.getWeatherInfo();
                if (mWeatherData != null) {
                    weatherSubTitle = mWeatherData.temp + mWeatherData.tempUnits;
                }
            }
        } catch(Exception e) {
            Log.d(TAG, "updateDialog: Weather info is unavailable");
        }
        // subtitle
        mWeatherDialogSubTitle.setText(weatherSubTitle);
    }
}

