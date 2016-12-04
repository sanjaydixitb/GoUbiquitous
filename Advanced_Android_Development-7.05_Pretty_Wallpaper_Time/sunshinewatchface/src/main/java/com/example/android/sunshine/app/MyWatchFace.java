/*
 * Copyright (C) 2014 The Android Open Source Project
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

package com.example.android.sunshine.app;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.IntentSender;
import android.content.res.Resources;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.Typeface;
import android.os.Bundle;
import android.os.Handler;
import android.os.Message;
import android.support.annotation.NonNull;
import android.support.annotation.Nullable;
import android.support.wearable.watchface.CanvasWatchFaceService;
import android.support.wearable.watchface.WatchFaceStyle;
import android.util.Log;
import android.view.SurfaceHolder;
import android.view.WindowInsets;
import android.widget.Toast;

import com.google.android.gms.common.ConnectionResult;
import com.google.android.gms.common.GooglePlayServicesUtil;
import com.google.android.gms.common.api.GoogleApiClient;
import com.google.android.gms.wearable.DataApi;
import com.google.android.gms.wearable.DataEvent;
import com.google.android.gms.wearable.DataEventBuffer;
import com.google.android.gms.wearable.DataItem;
import com.google.android.gms.wearable.DataMap;
import com.google.android.gms.wearable.DataMapItem;
import com.google.android.gms.wearable.Wearable;

import java.lang.ref.WeakReference;
import java.util.Calendar;
import java.util.Locale;
import java.util.TimeZone;
import java.util.concurrent.TimeUnit;

import static android.R.attr.value;

/**
 * Digital watch face with seconds. In ambient mode, the seconds aren't displayed. On devices with
 * low-bit ambient mode, the text is drawn without anti-aliasing in ambient mode.
 */
public class MyWatchFace extends CanvasWatchFaceService {
    private static final Typeface NORMAL_TYPEFACE =
            Typeface.create(Typeface.SANS_SERIF, Typeface.NORMAL);

    /**
     * Update rate in milliseconds for interactive mode. We update once a second since seconds are
     * displayed in interactive mode.
     */
    private static final long INTERACTIVE_UPDATE_RATE_MS = TimeUnit.SECONDS.toMillis(1);

    /**
     * Handler message id for updating the time periodically in interactive mode.
     */
    private static final int MSG_UPDATE_TIME = 0;

    @Override
    public Engine onCreateEngine() {
        return new Engine();
    }

    private static class EngineHandler extends Handler {
        private final WeakReference<MyWatchFace.Engine> mWeakReference;

        public EngineHandler(MyWatchFace.Engine reference) {
            mWeakReference = new WeakReference<>(reference);
        }

        @Override
        public void handleMessage(Message msg) {
            MyWatchFace.Engine engine = mWeakReference.get();
            if (engine != null) {
                switch (msg.what) {
                    case MSG_UPDATE_TIME:
                        engine.handleUpdateTimeMessage();
                        break;
                }
            }
        }
    }

    private class Engine extends CanvasWatchFaceService.Engine implements DataApi.DataListener,
            GoogleApiClient.ConnectionCallbacks, GoogleApiClient.OnConnectionFailedListener {
        final Handler mUpdateTimeHandler = new EngineHandler(this);
        boolean mRegisteredTimeZoneReceiver = false;
        Paint mBackgroundPaint;
        Paint mTextPaint, mDatePaint;
        boolean mAmbient;
        Calendar mCalendar;
        String mDateString;
        int mHighTemp, mLowTemp;
        int mWeatherId;

        Bitmap mWeatherBitmap, mWeatherBitmapAmb;

        private static final String WATCH_TAG = "SunshineSyncAdapter";
        private static final int CLEAR  = 1, CLOUDS = 2, FOG = 3, LIGHT_CLOUDS = 4, LIGHT_RAIN = 5, RAIN = 6, SNOW = 7, STORM = 8;

        final BroadcastReceiver mTimeZoneReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                mCalendar.setTimeZone(TimeZone.getDefault());
                invalidate();
            }
        };
        float mXOffset;
        float mYOffset;

        /**
         * Whether the display supports fewer bits for each color in ambient mode. When true, we
         * disable anti-aliasing in ambient mode.
         */
        boolean mLowBitAmbient;

        GoogleApiClient mGoogleApiClient = new GoogleApiClient.Builder(MyWatchFace.this)
                .addConnectionCallbacks(this)
                .addOnConnectionFailedListener(this)
                .addApi(Wearable.API)
                .build();

        @Override
        public void onCreate(SurfaceHolder holder) {
            super.onCreate(holder);

            setWatchFaceStyle(new WatchFaceStyle.Builder(MyWatchFace.this)
                    .setCardPeekMode(WatchFaceStyle.PEEK_MODE_VARIABLE)
                    .setBackgroundVisibility(WatchFaceStyle.BACKGROUND_VISIBILITY_INTERRUPTIVE)
                    .setShowSystemUiTime(false)
                    .setAcceptsTapEvents(true)
                    .build());
            Resources resources = MyWatchFace.this.getResources();
            mYOffset = resources.getDimension(R.dimen.digital_y_offset);

            mBackgroundPaint = new Paint();
            mBackgroundPaint.setColor(resources.getColor(R.color.background));

            mTextPaint = new Paint();
            mDatePaint = new Paint();
            mTextPaint = createTextPaint(resources.getColor(R.color.digital_text));
            mDatePaint = createTextPaint(resources.getColor(R.color.digital_text));

            mCalendar = Calendar.getInstance();
            setDateString(null);
            mHighTemp = -1;
            mLowTemp = -1;
            mWeatherId = -1;
            createWeatherBitmap();
        }

        @Override
        public void onDestroy() {
            mUpdateTimeHandler.removeMessages(MSG_UPDATE_TIME);
            super.onDestroy();
        }

        private Paint createTextPaint(int textColor) {
            Paint paint = new Paint();
            paint.setColor(textColor);
            paint.setTypeface(NORMAL_TYPEFACE);
            paint.setAntiAlias(true);
            return paint;
        }

        private void createWeatherBitmap() {
            boolean isInAmbientMode = isInAmbientMode();

            int bitmapResourceAmb = R.drawable.ic_clear;
            if (mWeatherId >= 200 && mWeatherId <= 232) {
                bitmapResourceAmb = R.drawable.ic_storm;
            } else if (mWeatherId >= 300 && mWeatherId <= 321) {
                bitmapResourceAmb = R.drawable.ic_light_rain;
            } else if (mWeatherId >= 500 && mWeatherId <= 504) {
                bitmapResourceAmb = R.drawable.ic_rain;
            } else if (mWeatherId == 511) {
                bitmapResourceAmb = R.drawable.ic_snow;
            } else if (mWeatherId >= 520 && mWeatherId <= 531) {
                bitmapResourceAmb = R.drawable.ic_rain;
            } else if (mWeatherId >= 600 && mWeatherId <= 622) {
                bitmapResourceAmb = R.drawable.ic_snow;
            } else if (mWeatherId >= 701 && mWeatherId <= 761) {
                bitmapResourceAmb = R.drawable.ic_fog;
            } else if (mWeatherId == 761 || mWeatherId == 781) {
                bitmapResourceAmb = R.drawable.ic_storm;
            } else if (mWeatherId == 800) {
                bitmapResourceAmb = R.drawable.ic_clear;
            } else if (mWeatherId == 801) {
                bitmapResourceAmb = R.drawable.ic_light_clouds;
            } else if (mWeatherId >= 802 && mWeatherId <= 804) {
                bitmapResourceAmb = R.drawable.ic_cloudy;
            }

            int bitmapResource = R.drawable.art_clear;
            if (mWeatherId >= 200 && mWeatherId <= 232) {
                bitmapResource = R.drawable.art_storm;
            } else if (mWeatherId >= 300 && mWeatherId <= 321) {
                bitmapResource = R.drawable.art_light_rain;
            } else if (mWeatherId >= 500 && mWeatherId <= 504) {
                bitmapResource = R.drawable.art_rain;
            } else if (mWeatherId == 511) {
                bitmapResource = R.drawable.art_snow;
            } else if (mWeatherId >= 520 && mWeatherId <= 531) {
                bitmapResource = R.drawable.art_rain;
            } else if (mWeatherId >= 600 && mWeatherId <= 622) {
                bitmapResource = R.drawable.art_snow;
            } else if (mWeatherId >= 701 && mWeatherId <= 761) {
                bitmapResource = R.drawable.art_fog;
            } else if (mWeatherId == 761 || mWeatherId == 781) {
                bitmapResource = R.drawable.art_storm;
            } else if (mWeatherId == 800) {
                bitmapResource = R.drawable.art_clear;
            } else if (mWeatherId == 801) {
                bitmapResource = R.drawable.art_light_clouds;
            } else if (mWeatherId >= 802 && mWeatherId <= 804) {
                bitmapResource = R.drawable.art_clouds;
            }

            mWeatherBitmapAmb = BitmapFactory.decodeResource(getResources(),bitmapResourceAmb);
            mWeatherBitmap = BitmapFactory.decodeResource(getResources(),bitmapResource);

        }

        private void updateDataOnStart() {
            DigitalWatchFaceUtil.fetchConfigDataMap(mGoogleApiClient,
                    new DigitalWatchFaceUtil.FetchConfigDataMapCallback() {
                        @Override
                        public void onConfigDataMapFetched(DataMap startupConfig) {
                            // If the DataItem hasn't been created yet or some keys are missing,
                            // use the default values.
                            setDefaultValuesForMissingConfigKeys(startupConfig);
                            DigitalWatchFaceUtil.putConfigDataItem(mGoogleApiClient, startupConfig);

                            updateUiForConfigDataMap(startupConfig);
                        }
                    }
            );
        }

        private void setDefaultValuesForMissingConfigKeys(DataMap config) {
            addIntKeyIfMissing(config, DigitalWatchFaceUtil.KEY_TEMP_HI,
                    25);
            addIntKeyIfMissing(config, DigitalWatchFaceUtil.KEY_TEMP_LOW,
                    16);
            addIntKeyIfMissing(config, DigitalWatchFaceUtil.KEY_WEATHER_ID,
                    1);
        }

        private void addIntKeyIfMissing(DataMap config, String key, int color) {
            if (!config.containsKey(key)) {
                config.putInt(key, color);
            }
        }

        @Override
        public void onVisibilityChanged(boolean visible) {
            super.onVisibilityChanged(visible);

            if (visible) {
                mGoogleApiClient.connect();

                registerReceiver();

                // Update time zone in case it changed while we weren't visible.
                mCalendar.setTimeZone(TimeZone.getDefault());
                invalidate();
            } else {
                unregisterReceiver();
                if (mGoogleApiClient != null && mGoogleApiClient.isConnected()) {
                    Wearable.DataApi.removeListener(mGoogleApiClient, this);
                    mGoogleApiClient.disconnect();
                }
            }

            // Whether the timer should be running depends on whether we're visible (as well as
            // whether we're in ambient mode), so we may need to start or stop the timer.
            updateTimer();
        }

        private void registerReceiver() {
            if (mRegisteredTimeZoneReceiver) {
                return;
            }
            mRegisteredTimeZoneReceiver = true;
            IntentFilter filter = new IntentFilter(Intent.ACTION_TIMEZONE_CHANGED);
            MyWatchFace.this.registerReceiver(mTimeZoneReceiver, filter);
        }

        private void unregisterReceiver() {
            if (!mRegisteredTimeZoneReceiver) {
                return;
            }
            mRegisteredTimeZoneReceiver = false;
            MyWatchFace.this.unregisterReceiver(mTimeZoneReceiver);
        }

        @Override
        public void onApplyWindowInsets(WindowInsets insets) {
            super.onApplyWindowInsets(insets);

            // Load resources that have alternate values for round watches.
            Resources resources = MyWatchFace.this.getResources();
            boolean isRound = insets.isRound();
            mXOffset = resources.getDimension(isRound
                    ? R.dimen.digital_x_offset_round : R.dimen.digital_x_offset);
            float textSize = resources.getDimension(isRound
                    ? R.dimen.digital_text_size_round : R.dimen.digital_text_size);

            mTextPaint.setTextSize(textSize);
            mDatePaint.setTextSize(resources.getDimension(R.dimen.text_size_small));
        }

        @Override
        public void onPropertiesChanged(Bundle properties) {
            super.onPropertiesChanged(properties);
            mLowBitAmbient = properties.getBoolean(PROPERTY_LOW_BIT_AMBIENT, false);
        }

        @Override
        public void onTimeTick() {
            super.onTimeTick();
            invalidate();
        }

        @Override
        public void onAmbientModeChanged(boolean inAmbientMode) {
            super.onAmbientModeChanged(inAmbientMode);
            if (mAmbient != inAmbientMode) {
                mAmbient = inAmbientMode;
                if (mLowBitAmbient) {
                    mTextPaint.setAntiAlias(!inAmbientMode);
                    mDatePaint.setAntiAlias(!inAmbientMode);
                }
                invalidate();
            }

            // Whether the timer should be running depends on whether we're visible (as well as
            // whether we're in ambient mode), so we may need to start or stop the timer.
            updateTimer();
        }

        /**
         * Captures tap event (and tap type) and toggles the background color if the user finishes
         * a tap.
         */
        @Override
        public void onTapCommand(int tapType, int x, int y, long eventTime) {
            switch (tapType) {
                case TAP_TYPE_TOUCH:
                    // The user has started touching the screen.
                    break;
                case TAP_TYPE_TOUCH_CANCEL:
                    // The user has started a different gesture or otherwise cancelled the tap.
                    break;
                case TAP_TYPE_TAP:
                    // The user has completed the tap gesture.
                    // TODO: Add code to handle the tap gesture.
                    Toast.makeText(getApplicationContext(), R.string.message, Toast.LENGTH_SHORT)
                            .show();
                    break;
            }
            invalidate();
        }

        @Override
        public void onDraw(Canvas canvas, Rect bounds) {
            // Draw the background.
            if (isInAmbientMode()) {
                canvas.drawColor(Color.BLACK);
            } else {
                canvas.drawRect(0, 0, bounds.width(), bounds.height(), mBackgroundPaint);
            }

            // Draw H:MM in ambient mode or H:MM:SS in interactive mode.
            long now = System.currentTimeMillis();
            mCalendar.setTimeInMillis(now);

            String text = mAmbient
                    ? String.format("%d:%02d", mCalendar.get(Calendar.HOUR),
                    mCalendar.get(Calendar.MINUTE))
                    : String.format("%d:%02d:%02d", mCalendar.get(Calendar.HOUR),
                    mCalendar.get(Calendar.MINUTE), mCalendar.get(Calendar.SECOND));

            canvas.drawText(text, mXOffset, mYOffset, mTextPaint);
            canvas.drawText(mDateString, mXOffset, mYOffset + mTextPaint.getTextSize(), mDatePaint);
            if(mAmbient)
                canvas.drawBitmap(mWeatherBitmapAmb,mXOffset, mYOffset + mTextPaint.getTextSize() + mDatePaint.getTextSize(), null);
            else
                canvas.drawBitmap(mWeatherBitmap,mXOffset, mYOffset + mTextPaint.getTextSize() + mDatePaint.getTextSize(), null);
            String temp = String.valueOf(mHighTemp) + " \u00B0   " + String.valueOf(mLowTemp) + " \u00B0";
            int imageSize = mWeatherBitmap.getWidth() + 10;
            canvas.drawText(temp, mXOffset + imageSize, mYOffset + mTextPaint.getTextSize()+ mDatePaint.getTextSize() + imageSize/2, mDatePaint);
        }

        /**
         * Starts the {@link #mUpdateTimeHandler} timer if it should be running and isn't currently
         * or stops it if it shouldn't be running but currently is.
         */
        private void updateTimer() {
            mUpdateTimeHandler.removeMessages(MSG_UPDATE_TIME);
            if (shouldTimerBeRunning()) {
                mUpdateTimeHandler.sendEmptyMessage(MSG_UPDATE_TIME);
            }
        }

        /**
         * Returns whether the {@link #mUpdateTimeHandler} timer should be running. The timer should
         * only run when we're visible and in interactive mode.
         */
        private boolean shouldTimerBeRunning() {
            return isVisible() && !isInAmbientMode();
        }

        /**
         * Handle updating the time periodically in interactive mode.
         */
        private void handleUpdateTimeMessage() {
            invalidate();
            if (shouldTimerBeRunning()) {
                long timeMs = System.currentTimeMillis();
                long delayMs = INTERACTIVE_UPDATE_RATE_MS
                        - (timeMs % INTERACTIVE_UPDATE_RATE_MS);
                mUpdateTimeHandler.sendEmptyMessageDelayed(MSG_UPDATE_TIME, delayMs);
            }
        }

        public void setDateString(String inpString) {
            if(inpString == null) {
                String dayOfTheWeek = mCalendar.getDisplayName(Calendar.DAY_OF_WEEK,Calendar.SHORT, Locale.ENGLISH);
                String date = mCalendar.getDisplayName(Calendar.MONTH, Calendar.SHORT, Locale.ENGLISH) + " " + mCalendar.get(Calendar.DATE) + " " + mCalendar.get(Calendar.YEAR) ;
                mDateString = dayOfTheWeek + ", " + date;
            } else {
                mDateString = inpString;
            }
        }

        @Override
        public void onConnected(@Nullable Bundle bundle) {
            Log.d(WATCH_TAG,"Connected");
            Wearable.DataApi.addListener(mGoogleApiClient, Engine.this);
            updateDataOnStart();
        }

        @Override
        public void onConnectionSuspended(int i) {
            Log.d(WATCH_TAG,"Connection suspended");
        }

        @Override
        public void onConnectionFailed(@NonNull ConnectionResult connectionResult) {
            Log.d(WATCH_TAG,"Connection failed!" + connectionResult.getErrorMessage());
            Log.d(WATCH_TAG, connectionResult.getErrorCode() + " Unable to connect to Google Service, sorry >_<");
        }

        @Override
        public void onDataChanged(DataEventBuffer dataEventBuffer) {
            Log.d(WATCH_TAG,"Data changed");
            for (DataEvent dataEvent : dataEventBuffer) {
                if (dataEvent.getType() != DataEvent.TYPE_CHANGED) {
                    continue;
                }

                DataItem dataItem = dataEvent.getDataItem();
                Log.d(WATCH_TAG, "Received data at location : " + dataItem.getUri().getPath());
                if (!dataItem.getUri().getPath().equals(
                        DigitalWatchFaceUtil.PATH)) {
                    continue;
                }

                DataMapItem dataMapItem = DataMapItem.fromDataItem(dataItem);
                DataMap config = dataMapItem.getDataMap();
                updateUiForConfigDataMap(config);
            }
        }

        private void updateUiForConfigDataMap(final DataMap config) {
            boolean uiUpdated = false;
            for (String configKey : config.keySet()) {
                Log.d(WATCH_TAG, "Key : " + configKey);
                if (!config.containsKey(configKey)) {
                    continue;
                }
                int value = config.getInt(configKey);
                Log.d(WATCH_TAG, "Value : " + value);
                if (updateUiForKey(configKey, value)) {
                    uiUpdated = true;
                }
            }
            if (uiUpdated) {
                invalidate();
            }
        }

        private boolean updateUiForKey(String configKey, int val) {
            if (configKey.equals(DigitalWatchFaceUtil.KEY_TEMP_HI)) {
                mHighTemp = val;
            } else if (configKey.equals(DigitalWatchFaceUtil.KEY_TEMP_LOW)) {
                mLowTemp = val;
            } else if (configKey.equals(DigitalWatchFaceUtil.KEY_WEATHER_ID)) {
                if(val != mWeatherId) {
                    mWeatherId = val;
                    createWeatherBitmap();
                }
            } else {
                return false;
            }
            return true;
        }


    }
}
