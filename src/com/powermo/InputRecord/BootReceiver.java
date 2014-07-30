/*
* All Rights Reserved
*
* PowerMo Confidential
* Copyright 2013 PowerMo Information Technology Ltd. all rights reserved.
* The source code contained or described herein and all documents related to
* the source code ("material") are owned by PowerMo Information Technology Ltd. or its
* suppliers or licensed customers.
*
* Title to the material remains with PowerMo Information Technology Ltd. or its
* suppliers and licensed customers. the material contains trade secrets and proprietary
* and confidential information of PowerMo or its suppliers and licensed customers.
* The material is protected by worldwide copyright and trade secret
* laws and treaty provisions. no part of the material may be used, copied,
* reproduced, modified, published, uploaded, posted, transmitted, distributed,
* or disclosed in any way without PowerMo's prior express written permission.
*
* No license under any patent, copyright, trade secret or other intellectual
* property right is granted to or conferred upon you by disclosure or delivery
* of the materials, either expressly, by implication, inducement, estoppel or
* otherwise. Any license under such intellectual property rights must be
* express and approved by PowerMo in writing.
*/

package com.powermo.InputRecord;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Handler;
import android.os.Message;
import android.util.Log;

public class BootReceiver extends BroadcastReceiver {
    private static boolean DEBUG = true;
    private static final String TAG = "InputRecordBootReceiver";
    private Handler mHandler = null;
    private Context mContext = null;

    @Override
    public void onReceive(final Context context, Intent intent) {
        try {
            if (DEBUG) Log.d(TAG, "Receive boot complete");
            mContext = context;
            mHandler = new Handler(context.getMainLooper());
            mHandler.postDelayed(new Runnable() {
                @Override
                public void run() {
                    Intent hybrid = new Intent(mContext, com.powermo.InputRecord.InputRecordService.class);
                    mContext.startService(hybrid);
                }
            }, 1000);
        } catch (Exception e) {
            Log.e(TAG, "Can't start input record service", e);
        }
    }
}
