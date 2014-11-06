/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package org.mozilla.mozstumbler.service.stumblerthread.scanners;

import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.location.Location;
import android.os.BatteryManager;
import android.support.v4.content.LocalBroadcastManager;
import android.telephony.TelephonyManager;
import android.util.Log;

import org.mozilla.mozstumbler.service.AppGlobals;
import org.mozilla.mozstumbler.service.AppGlobals.ActiveOrPassiveStumbling;
import org.mozilla.mozstumbler.service.stumblerthread.Reporter;
import org.mozilla.mozstumbler.service.stumblerthread.blocklist.WifiBlockListInterface;
import org.mozilla.mozstumbler.service.stumblerthread.scanners.cellscanner.CellScanner;

import java.util.Date;
import java.util.Timer;
import java.util.TimerTask;

public class ScanManager {
    private static final String LOG_TAG = AppGlobals.LOG_PREFIX + ScanManager.class.getSimpleName();
    private Timer mPassiveModeFlushTimer;
    private Context mContext;
    private boolean mIsScanning;
    private GPSScanner mGPSScanner;
    private WifiScanner mWifiScanner;
    private CellScanner mCellScanner;
    private ActiveOrPassiveStumbling mStumblingMode = ActiveOrPassiveStumbling.ACTIVE_STUMBLING;

    public ScanManager() {
    }

    private boolean isBatteryLow() {
        Intent intent = mContext.registerReceiver(null, new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
        if (intent == null) {
            return false;
        }

        int rawLevel = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1);
        int scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, -1);
        int status = intent.getIntExtra(BatteryManager.EXTRA_STATUS, -1);
        boolean isCharging = (status == BatteryManager.BATTERY_STATUS_CHARGING);
        int level = Math.round(rawLevel * scale / 100.0f);

        final int kMinBatteryPct = 15;
        return !isCharging && level < kMinBatteryPct;
    }

    public void newPassiveGpsLocation() {
        if (isBatteryLow()) {
            return;
        }

        if (AppGlobals.isDebug) {
            Log.d(LOG_TAG, "New passive location");
        }

        mWifiScanner.start(ActiveOrPassiveStumbling.PASSIVE_STUMBLING);

        if (mCellScanner != null) {
            mCellScanner.start(ActiveOrPassiveStumbling.PASSIVE_STUMBLING);
        }

        // how often to flush a leftover bundle to the reports table
        // If there is a bundle, and nothing happens for 10sec, then flush it
        final int flushRate_ms = 10000;

        if (mPassiveModeFlushTimer != null) {
            mPassiveModeFlushTimer.cancel();
        }

        Date when = new Date();
        when.setTime(when.getTime() + flushRate_ms);
        mPassiveModeFlushTimer = new Timer();
        mPassiveModeFlushTimer.schedule(new TimerTask() {
            @Override
            public void run() {
                Intent flush = new Intent(Reporter.ACTION_FLUSH_TO_BUNDLE);
                LocalBroadcastManager.getInstance(mContext).sendBroadcastSync(flush);
            }
        }, when);
    }

    public synchronized boolean isPassiveMode() {
        return ActiveOrPassiveStumbling.PASSIVE_STUMBLING == mStumblingMode;
    }

    public synchronized void setPassiveMode(boolean on) {
        mStumblingMode = (on) ? ActiveOrPassiveStumbling.PASSIVE_STUMBLING :
                ActiveOrPassiveStumbling.ACTIVE_STUMBLING;
    }

    public synchronized void startScanning(Context context) {
        if (this.isScanning()) {
            return;
        }

        mContext = context.getApplicationContext();
        if (mContext == null) {
            Log.w(LOG_TAG, "No app context available.");
            return;
        }
        if (mGPSScanner == null) {
            mGPSScanner = new GPSScanner(context, this);
            mWifiScanner = new WifiScanner(context);

            TelephonyManager telephonyManager = (TelephonyManager) mContext.getSystemService(Context.TELEPHONY_SERVICE);
            if (telephonyManager != null &&
                    (telephonyManager.getPhoneType() == TelephonyManager.PHONE_TYPE_CDMA ||
                            telephonyManager.getPhoneType() == TelephonyManager.PHONE_TYPE_GSM)) {
                mCellScanner = new CellScanner(context);
            }
        }

        if (AppGlobals.isDebug) {
            Log.d(LOG_TAG, "Scanning started...");
        }

        mGPSScanner.start(mStumblingMode);
        if (mStumblingMode == ActiveOrPassiveStumbling.ACTIVE_STUMBLING) {
            mWifiScanner.start(mStumblingMode);

            if (mCellScanner != null) {
                mCellScanner.start(mStumblingMode);
            }

            // in passive mode, these scans are started by passive gps notifications
        }
        mIsScanning = true;
    }

    public synchronized boolean stopScanning() {
        if (!this.isScanning()) {
            return false;
        }

        if (AppGlobals.isDebug) {
            Log.d(LOG_TAG, "Scanning stopped");
        }

        mGPSScanner.stop();
        mWifiScanner.stop();
        if (mCellScanner != null) {
            mCellScanner.stop();
        }

        mIsScanning = false;
        return true;
    }

    public void setWifiBlockList(WifiBlockListInterface list) {
        WifiScanner.setWifiBlockList(list);
    }

    public synchronized boolean isScanning() {
        return mIsScanning;
    }

    public int getAPCount() {
        return (mWifiScanner == null) ? 0 : mWifiScanner.getAPCount();
    }

    public int getVisibleAPCount() {
        return (mWifiScanner == null) ? 0 : mWifiScanner.getVisibleAPCount();
    }

    public int getWifiStatus() {
        return (mWifiScanner == null) ? 0 : mWifiScanner.getStatus();
    }

    public int getVisibleCellInfoCount() {
        return (mCellScanner == null) ? 0 : mCellScanner.getVisibleCellInfoCount();
    }

    public int getLocationCount() {
        return (mGPSScanner == null) ? 0 : mGPSScanner.getLocationCount();
    }

    public Location getLocation() {
        return (mGPSScanner == null) ? new Location("null") : mGPSScanner.getLocation();
    }

}
