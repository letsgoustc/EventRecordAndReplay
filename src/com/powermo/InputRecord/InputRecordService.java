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

import java.io.FileDescriptor;
import java.io.PrintWriter;

import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Handler;
import android.os.IBinder;
import android.util.Log;
import android.view.IRotationWatcher;
import android.view.Surface;
import android.view.IWindowManager;
import android.view.InputDevice;
import android.view.InputEvent;
import android.view.InputFilter;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.os.RemoteException;
import android.os.ServiceManager;
import android.view.WindowManagerGlobal;
import android.os.HandlerThread;
import android.os.Message;
import android.os.Looper;
import android.os.Parcel;
import android.os.SystemProperties;
import android.os.SystemClock;
import android.hardware.input.InputManager;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.MotionEvent.PointerCoords;
import android.view.MotionEvent.PointerProperties;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.EOFException;
import java.io.InputStream;
import java.io.ByteArrayOutputStream;

public class InputRecordService extends Service {
    public static final String TAG_PREFIX = SystemProperties.get("debug.powermo.tag_prefix", "");
    public static final String DEBUG_COMPONENT = SystemProperties.get("debug.powermo.component", "");
    public static final boolean DEBUG = DEBUG_COMPONENT.contains("input-record");
    private static final String TAG = TAG_PREFIX + "InputRecordService";
    private static final String CMDSTART = "start";
    private static final String CMDSTOP = "stop";
    private static final String CMDREPLAY = "replay";
    private static final String WORK_ACTION = "com.powermo.inputrecord.action";
    private static final int TYPE_INPUT_EVENT = 1;
    private static final int TYPE_ROTATION_EVENT = 2;

    private boolean mStarted = false;
    private IWindowManager mWindowManagerService;
    private InputFilter mInputFilter = null;
    private HandlerThread mHandlerThread = null;
    private MH mHandler = null;
    private String DEFAULT_RECORD_FILE = "/sdcard/powermo.rec";
    private File mRecordFile = null;
    private FileOutputStream mOut = null;
    private FileInputStream mIn = null;
    private DataOutputStream mDataOut = null;
    private DataInputStream mDataIn = null;
    private long mLastEventTime = 0;
    private long mLastInjectedEventTime = 0;
    private PointerCoords[] mTempPointerCoords;
    private PointerProperties[] mTempPointerProperties;
    private int mRotation = Surface.ROTATION_0;
    private IRotationWatcher mRotationWatcher = new IRotationWatcher.Stub() {
        public void onRotationChanged(int rotation) {
            if (DEBUG) {
                Log.d(TAG, "onRotationChanged " + rotation);
            }
            mRotation = rotation;
            MyRotationEvent mre = new MyRotationEvent();
            mre.rotation = rotation;
            mHandler.sendMessage(mHandler.obtainMessage(MH.MSG_SAVE_EVENT, mre));
        }
    };

    private String printHexString(byte[] b) {
        String a = "";
        for (int i = 0; i < b.length; i++) { 
            String hex = Integer.toHexString(b[i] & 0xFF);
            if (hex.length() == 1) {
                hex = '0' + hex;
            }
            a = a+hex;
        }
        return a;
    }

    private PointerCoords[] getTempPointerCoordsWithMinSize(int size) {
        final int oldSize = (mTempPointerCoords != null) ? mTempPointerCoords.length : 0;
        if (oldSize < size) {
            PointerCoords[] oldTempPointerCoords = mTempPointerCoords;
            mTempPointerCoords = new PointerCoords[size];
            if (oldTempPointerCoords != null) {
                System.arraycopy(oldTempPointerCoords, 0, mTempPointerCoords, 0, oldSize);
            }
        }
        for (int i = oldSize; i < size; i++) {
            mTempPointerCoords[i] = new PointerCoords();
        }
        return mTempPointerCoords;
    }

    private PointerProperties[] getTempPointerPropertiesWithMinSize(int size) {
        final int oldSize = (mTempPointerProperties != null) ? mTempPointerProperties.length : 0;
        if (oldSize < size) {
            PointerProperties[] oldTempPointerProperties = mTempPointerProperties;
            mTempPointerProperties = new PointerProperties[size];
            if (oldTempPointerProperties != null) {
                System.arraycopy(oldTempPointerProperties, 0, mTempPointerProperties, 0, oldSize);
            }
        }
        for (int i = oldSize; i < size; i++) {
            mTempPointerProperties[i] = new PointerProperties();
        }
        return mTempPointerProperties;
    }

    class MyEvent {
        int type;
        long time;
        MyEvent() {
            time = SystemClock.uptimeMillis();
        }
    }

    class MyRotationEvent extends MyEvent {
        int rotation;
        MyRotationEvent() {
            type = TYPE_ROTATION_EVENT;
        }
    }

    class MyInputEvent extends MyEvent {
        InputEvent event;
        MyInputEvent() {
            type = TYPE_INPUT_EVENT;
        }
    }

    class MH extends Handler {
        public static final int MSG_SAVE_EVENT = 1;
        public static final int MSG_READ_INPUT_EVENT = 2;
        public static final int MSG_INJECT_INPUT_EVENT = 3;
        public static final int MSG_INJECT_ROTATION_EVENT = 4;

        public MH(Looper looper) {
            super(looper);
        }

        @Override
        public void handleMessage(Message msg) {
            switch (msg.what) {
                case MSG_SAVE_EVENT: {
                    MyEvent event = (MyEvent)msg.obj;
                    if (DEBUG) {
                        Log.d(TAG, "Process event: " + event);
                    }
                    saveOneEvent(event);
                    break;
                }

                case MSG_READ_INPUT_EVENT: {
                    MyEvent mev = readOneEvent();
                    if (mev == null) {
                        try {
                            mDataIn.close();
                            mIn.close();
                        } catch (Exception e) {
                            e.printStackTrace();
                        }
                        return;
                    }
                    switch (mev.type) {
                        case TYPE_ROTATION_EVENT: {
                            MyRotationEvent mre = (MyRotationEvent)mev;
                            long deltaMs = 0;
                            if (mLastEventTime == 0) {
                                mLastInjectedEventTime = SystemClock.uptimeMillis();
                            } else {
                                deltaMs = (mev.time - mLastEventTime);
                            }
                            mLastInjectedEventTime += deltaMs;
                            mLastEventTime = mev.time;
                            mHandler.sendMessageAtTime(mHandler.obtainMessage(MH.MSG_INJECT_ROTATION_EVENT, mre.rotation, 0), mLastInjectedEventTime);
                            break;
                        }

                        case TYPE_INPUT_EVENT: {
                            MyInputEvent mie = (MyInputEvent)mev;
                            InputEvent event = mie.event;
                            if (event != null) {
                                InputEvent ev = null;
                                long deltaMs = 0;
                                if (mLastEventTime == 0) {
                                    mLastInjectedEventTime = SystemClock.uptimeMillis();
                                } else {
                                    deltaMs = (mev.time - mLastEventTime);
                                }
                                mLastInjectedEventTime += deltaMs;
                                mLastEventTime = mev.time;
                                if (event instanceof MotionEvent
                                        && event.isFromSource(InputDevice.SOURCE_TOUCHSCREEN)) {
                                    MotionEvent motionEvent = (MotionEvent) event;
                                    int pointerCount = motionEvent.getPointerCount();
                                    PointerCoords[] coords = getTempPointerCoordsWithMinSize(pointerCount);
                                    PointerProperties[] properties = getTempPointerPropertiesWithMinSize(pointerCount);
                                    for (int i = 0; i < pointerCount; i++) {
                                        coords[i] = new PointerCoords();
                                        motionEvent.getPointerCoords(i, coords[i]);
                                        properties[i] = new PointerProperties();
                                        motionEvent.getPointerProperties(i, properties[i]);
                                    }
                                    ev = MotionEvent.obtain(mLastInjectedEventTime, mLastInjectedEventTime,
                                            motionEvent.getAction(), pointerCount, properties,
                                            coords, motionEvent.getMetaState(), motionEvent.getButtonState(),
                                            1.0f, 1.0f, -1,
                                            motionEvent.getEdgeFlags(), motionEvent.getSource(), motionEvent.getFlags());
                                } else if (event instanceof KeyEvent
                                        && event.isFromSource(InputDevice.SOURCE_KEYBOARD)) {
                                    KeyEvent keyEvent = (KeyEvent) event;
                                    ev = new KeyEvent(mLastInjectedEventTime, mLastInjectedEventTime, keyEvent.getAction(),
                                            keyEvent.getKeyCode(), keyEvent.getRepeatCount(), keyEvent.getMetaState(), -1, keyEvent.getScanCode(),
                                            keyEvent.getFlags(), keyEvent.getSource());
                                } else {
                                    Log.w(TAG, "ignore not keyboard or touch event");
                                    mHandler.sendMessage(mHandler.obtainMessage(MH.MSG_READ_INPUT_EVENT));
                                    return;
                                }
                                mHandler.sendMessageAtTime(mHandler.obtainMessage(MH.MSG_INJECT_INPUT_EVENT, ev), mLastInjectedEventTime);
                            }
                            break;
                        }
                    }
                    break;
                }

                case MSG_INJECT_INPUT_EVENT: {
                    InputEvent ev = (InputEvent)msg.obj;
                    if (DEBUG) {
                        Log.d(TAG, "Inject one event " + ev);
                    }
                    InputManager.getInstance().injectInputEvent(ev, InputManager.INJECT_INPUT_EVENT_MODE_ASYNC);
                    mHandler.sendMessage(mHandler.obtainMessage(MH.MSG_READ_INPUT_EVENT));
                    break;
                }

                case MSG_INJECT_ROTATION_EVENT: {
                    int rotation = msg.arg1;
                    if (DEBUG) {
                        Log.d(TAG, "Inject rotation event " + rotation);
                    }
                    try {
                        mWindowManagerService.freezeRotation(rotation);
                    } catch (RemoteException re) {
                        Log.e(TAG, "freezeRotation exception", re);
                    }
                    mHandler.sendMessage(mHandler.obtainMessage(MH.MSG_READ_INPUT_EVENT));
                    break;
                }

                default:
                    break;
            }
        }
    }

    private MyEvent readOneEvent() {
        MyEvent event = null;
        try {
            int type = mDataIn.readInt();
            switch (type) {
                case TYPE_ROTATION_EVENT: {
                    event = new MyRotationEvent();
                    event.type = type;
                    event.time = mDataIn.readLong();
                    ((MyRotationEvent)event).rotation = mDataIn.readInt();
                    break;
                }
                case TYPE_INPUT_EVENT: {
                    event = new MyInputEvent();
                    event.type = type;
                    event.time = mDataIn.readLong();
                    ((MyInputEvent)event).event = readInputEvent();
                    break;
                }
            }
        } catch (EOFException e) {
            // EOF
            try {
                mWindowManagerService.thawRotation();
            } catch (RemoteException re) {
                re.printStackTrace();
            }
        } catch (Exception e) {
            Log.e(TAG, "read event", e);
        }
        return event;
    }

    private void saveOneEvent(MyEvent event) {
        try {
            switch (event.type) {
                case TYPE_ROTATION_EVENT: {
                    mDataOut.writeInt(TYPE_ROTATION_EVENT);
                    mDataOut.writeLong(event.time);
                    mDataOut.writeInt(((MyRotationEvent)event).rotation);
                    break;
                }
                case TYPE_INPUT_EVENT: {
                    mDataOut.writeInt(TYPE_INPUT_EVENT);
                    mDataOut.writeLong(event.time);
                    saveToFile(((MyInputEvent)event).event);
                    break;
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "write event", e);
        }
    }

    private void saveToFile(InputEvent event) {
        Parcel parcel = Parcel.obtain();
        event.writeToParcel(parcel, 0);
        final byte[] data = parcel.marshall();
        try {
            //Write length first
            mDataOut.writeInt(data.length);
            mDataOut.write(data);
        } catch (Exception e) {
            Log.e(TAG, "write input event exception", e);
        }
    }

    private InputEvent readInputEvent() {
        try {
            //Read length first
            int len = mDataIn.readInt();
            if (len <= 0) {
                Log.w(TAG, "should not read zero here!");
                return null;
            }
            byte[] buffer = new byte[len];
            int read = 0;
            int total = 0;
            ByteArrayOutputStream ous = new ByteArrayOutputStream();
            total = 0;
            //Read one block
            while (total < len) {
                read = mDataIn.read(buffer);
                if (read == -1) {
                    break;
                }
                ous.write(buffer, 0, read);
                total += read;
            }
            byte[] data = ous.toByteArray();
            Parcel parcel = Parcel.obtain();
            parcel.unmarshall(data, 0, len);
            parcel.setDataPosition(0);
            ous.close();
            return InputEvent.CREATOR.createFromParcel(parcel);
        } catch (Exception e) {
            Log.e(TAG, "read input event exception", e);
            return null;
        }
    }

    private BroadcastReceiver mIntentReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            String action = intent.getAction();

            if (DEBUG) {
                Log.d(TAG, "Receive Intent Action : " + action);
            }

            if (action == null || !action.equals(WORK_ACTION)) {
                return;
            }

            String cmd = intent.getStringExtra("command");
            if (cmd != null) {
                if (cmd.equals(CMDSTART)) {
                    String file = intent.getStringExtra("file");
                    if (file == null) {
                        file = DEFAULT_RECORD_FILE;
                    }
                    handleStart(file);
                } else if (cmd.equals(CMDSTOP)) {
                    handleStop();
                } else if (cmd.equals(CMDREPLAY)) {
                    String file = intent.getStringExtra("file");
                    if (file == null) {
                        file = DEFAULT_RECORD_FILE;
                    }
                    handleReplay(file);
                }
            }
        }
    };

    @Override
    public void onCreate() {
        if (DEBUG) Log.d(TAG, "onCreate");

        IntentFilter commandFilter = new IntentFilter();
        commandFilter.addAction(WORK_ACTION);
        registerReceiver(mIntentReceiver, commandFilter);
        mWindowManagerService = WindowManagerGlobal.getInstance().getWindowManagerService();
        mInputFilter = new InputFilter(getApplication().getMainLooper()) {
            @Override
            public void onInputEvent(InputEvent event, int policyFlags) {
                if (DEBUG) {
                    Log.d(TAG, "onInputEvent " + event);
                }
                MyInputEvent mie = new MyInputEvent();
                mie.event = event;
                mHandler.sendMessage(mHandler.obtainMessage(MH.MSG_SAVE_EVENT, mie));
                super.onInputEvent(event, policyFlags);
            }
        };
        mHandlerThread = new HandlerThread("Worker");
        mHandlerThread.start();
        mHandler = new MH(mHandlerThread.getLooper());
    }

    @Override
    public void onDestroy() {
        if (DEBUG) Log.d(TAG, "onDestroy");
        unregisterReceiver(mIntentReceiver);
    }

    /**
     * Nobody binds to us.
     */
    @Override
    public IBinder onBind(Intent intent) {
        if (DEBUG) Log.d(TAG, "onBind");
        return null;
    }

    @Override
    protected void dump(FileDescriptor fd, PrintWriter pw, String[] args) {
        if (DEBUG) Log.d(TAG, "dump");
    }

    private void handleStart(String file) {
        if (mStarted) {
            return;
        }
        if (DEBUG) Log.d(TAG, "handleStart " + file);
        // register input filter
        try {
            mWindowManagerService.setInputFilter(mInputFilter);
        } catch (RemoteException re) {
            re.printStackTrace();
            return;
        }
        // register rotation watcher
        try {
            mRotation = mWindowManagerService.watchRotation(mRotationWatcher);
        } catch (RemoteException re) {
            re.printStackTrace();
            return;
        }

        try {
            mRecordFile = new File(file);
            mOut = new FileOutputStream(mRecordFile);
            mDataOut = new DataOutputStream(mOut);
        } catch (Exception e) {
            Log.e(TAG, "open record file exception", e);
            return;
        }
        mStarted = true;
    }

    private void handleStop() {
        if (!mStarted) {
            return;
        }
        if (DEBUG) Log.d(TAG, "handleStop");
        // stop input filter
        try {
            mWindowManagerService.setInputFilter(null);
        } catch (RemoteException re) {
            re.printStackTrace();
            return;
        }
        // stop rotation watcher
        try {
            mWindowManagerService.removeRotationWatcher(mRotationWatcher);
            mWindowManagerService.thawRotation();
        } catch (RemoteException re) {
            re.printStackTrace();
            return;
        }
        // TODO: Wait until handler idle, means no message
        try {
            mOut.flush();
            mDataOut.close();
            mOut.close();
        } catch (Exception e) {
            return;
        }
        mStarted = false;
    }

    private void handleReplay(String file) {
        if (mStarted) {
            return;
        }
        if (DEBUG) Log.d(TAG, "handleReplay " + file);
        try {
            mRecordFile = new File(file);
            mIn = new FileInputStream(mRecordFile);
            mDataIn = new DataInputStream(mIn);
        } catch (Exception e) {
            Log.e(TAG, "open " + file + " met exception", e);
            return;
        }
        mLastEventTime = 0;
        mLastInjectedEventTime = 0;
        mHandler.sendMessage(mHandler.obtainMessage(MH.MSG_READ_INPUT_EVENT));
    }
}
