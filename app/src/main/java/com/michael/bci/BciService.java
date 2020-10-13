package com.michael.bci;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbManager;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.Message;
import android.util.Log;
import android.widget.Toast;

import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;

import com.ftdi.j2xx.D2xxManager; //management class for connected FTDI devices.
import com.ftdi.j2xx.FT_Device; // provides APIs for the host to communicate and operate FTDI devices

import org.json.simple.JSONObject;

import java.text.SimpleDateFormat;
import java.util.Arrays;
import java.util.Calendar;
import java.util.Objects;

/*
 * Michael McMahon
 * This class configures and manages the connection to the OpenBCI Cytron Board.
 * The connection is implemented via a Serial connection using FTDI driver
 * running at 115200 baud using typical 8-N-1.The OpenBCI is configured using
 * single letter text commands sent from the App to the Board.
 * The EEG data streams back from the Board to the App continuously (once started).
 * REFERENCES
 * OpenBCI_Processing by Joel Murphy and Conor Russomanno
 * https://github.com/openbci-archive/-DEPRECATED-OpenBCI_Processing/blob/master/OpenBCI_GUI/OpenBCI_ADS1299.pde
 * Braindroid by Florian Friemel
 * https://github.com/florian-f/braindroid/blob/master/src/main/java/org/florian/eeg/braindroid/OpenBci.java
 */

public class BciService extends Service {

    public final static String TAG = "BCI_SERVICE 2";
    private static final String CHANNEL_ID = "BCI_1"; //Foreground Service Channel
    public byte[] remainingBytes;

    private boolean mIsRunning = false;
    private SenderThread mSenderThread;
    private static D2xxManager ftD2xx = null;
    public FT_Device ftDevice = null;
    /* Ensure startReceiverThread block is synchronized on "private final" field */
    public final Object lockObj = new Object();
    
    public static final int TRANSFER_SIZE = 256; //512 | 1024
    public static final long SLEEP = 200;
    public static final int VENDOR_ID = 1027;
    public static final int BAUD_RATE = 115200;
    /* One byte header 0x41 then 31 bytes of data followed by 0xCX where X is 0-F in hex */
    public static final byte PACKET_SIZE = (byte) 33;
    /* Header Byte 1: 0xA0 */
    public static final byte START_BYTE = (byte) 0xA0;
    byte mStopBit = D2xxManager.FT_STOP_BITS_1;
    byte mDataBit = D2xxManager.FT_DATA_BITS_8;
    byte mParity = D2xxManager.FT_PARITY_NONE;
    /* FT_FLOW_NONE / FT_FLOW_RTS_CTS / FT_FLOW_DTR_DSR / FT_FLOW_XON_XOFF */
    short mFlowControl = D2xxManager.FT_FLOW_NONE;
    byte[] overflowBuffer = new byte[TRANSFER_SIZE*2]; //Do I need a larger buffer size

    public volatile boolean receiverThreadRunning;


    /* Define the Custom Action Intents for BroadcastReceiver */
    final static String DATA_RECEIVED_INTENT = "bci.intent.action.DATA_RECEIVED";
    final static String DATA_EXTRA = "bci.intent.extra.DATA";
    final static String SEND_COMMAND = "bci.intent.action.SEND_COMMAND";
    final static String COMMAND_EXTRA = "bci.intent.extra.COMMAND_EXTRA";
    final static String SEND_DATA_INTENT = "bci.intent.action.SEND_DATA";
    final static String DATA_SENT_INTERNAL_INTENT = "bci.internal.intent.action.DATA_SENT";


    public D2xxManager.DriverParameters mDriverParameters;

    /*
    Return the communication channel to the service.  May return null if clients can not bind to the
    service. A bound service is an implementation of the Service class that allows other applications
    to bind to it and interact with it. The onBind() callback method returns an IBinder object that
    defines the programming interface that clients can use to interact with the service.
    https://developer.android.com/guide/components/bound-services
     */
    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public void onCreate() {
        super.onCreate();
        IntentFilter filter = new IntentFilter();
//      filter.addAction(SEND_DATA_INTENT);
        filter.addAction(UsbManager.ACTION_USB_DEVICE_DETACHED);
        filter.addAction(SEND_COMMAND);
        registerReceiver(mReceiver, filter);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {

        /* Set the Foreground notification's tap action */
        createNotificationChannel();
        Intent notificationIntent = new Intent(this, MainActivity.class);
        notificationIntent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
        PendingIntent pendingIntent = PendingIntent.getActivity(this, 0, notificationIntent, 0);

        /* Set the Foreground notification content */
        Notification notification = new NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle("BCI_1")
                .setContentText("This ia a test foreground message for BCI Service 1")
//                .setSmallIcon(R.drawable.icon)
                .setPriority(NotificationCompat.PRIORITY_DEFAULT)
                .setContentIntent(pendingIntent)
                .build();

        /* Start Foreground Service */
        startForeground(1, notification);


        if (mIsRunning) {
            Log.i(TAG, "Service already running.");
            return Service.START_REDELIVER_INTENT;
        }
        /* obtain the UsbDevice that represents the attached device from the intent-filter set in AndroidManifest.xml */
        UsbDevice mUsbDevice = intent.getParcelableExtra(UsbManager.EXTRA_DEVICE);

        if (null == ftD2xx) {
            try {
                ftD2xx = D2xxManager.getInstance(this);
                ftD2xx.createDeviceInfoList(this);

            } catch (D2xxManager.D2xxException ex) {
                Log.e(TAG, "USB: Catch exception");
                Log.e(TAG, ex.getMessage(), ex);
            }

        }

        /* connect */
        if (null == ftDevice)
        {
            ftDevice = ftD2xx.openByUsbDevice(this, mUsbDevice);
            Log.e(TAG, "USB: Connect to ftDevice");
        }


        if (ftDevice == null || !ftDevice.isOpen()) {
            Log.e(TAG, "USB: Opening ftDevice Failed");
            stopSelf();
            return Service.START_REDELIVER_INTENT;
        }

        /* reset to UART mode for 232 devices */
        ftDevice.setBitMode((byte) 0, D2xxManager.FT_BITMODE_RESET); /* Reset FT Device */
        ftDevice.setBaudRate(BAUD_RATE); /* Set BaudRate */
        /* The default FTDI 16 latency may be too large for EEG apps, making the incoming signal "choppy", May change from 16ms to 1ms */
        ftDevice.setLatencyTimer((byte) 16);

        /* FTDI USB configured as serial port running at 115200 baud using typical 8-N-1. */

       /* Individual bits representing the data character. Fewer bits reduces the range of data,
          but can increase the effective data transfer rate - 8:8bit, 7: 7bit etc */
        byte dataBit = mDataBit;
       /* time interval to indicate the end of that character - can be configured to remain
          idle for 1- or 2-bit durations.*/
        byte stopBit = mStopBit;
       /*Optional error checking value to indicate if the contents of the data bits sum to an
         even or odd value - 0: none, 1: odd, 2: even, 3: mark, 4: space*/
        byte parity = mParity;
        /* Pull all above together and set the Data Characteristics */
        ftDevice.setDataCharacteristics(dataBit, stopBit, parity);
        /*0:none, 1: flow control(CTS,RTS)*/
        short flowControl = mFlowControl;
        ftDevice.setFlowControl(flowControl, (byte) 0x0b, (byte) 0x0d);
        ftDevice.purge(D2xxManager.FT_PURGE_TX);
        ftDevice.purge(D2xxManager.FT_PURGE_RX);
        ftDevice.restartInTask();


        mIsRunning = true;
        mDriverParameters = new D2xxManager.DriverParameters();

        /* Get some logging output to see what is going on */
        Log.d(TAG, "USB: Buffer Number: " + mDriverParameters.getBufferNumber()); //Return Buffer number for Rx in user space application
        Log.d(TAG, "USB: Rx Buffer size: " + mDriverParameters.getMaxTransferSize()); //Return Rx buffer size of user space application
        Log.d(TAG, "USB: Max Transfer size for Rx: " + mDriverParameters.getMaxBufferSize()); //Return Max Transfer size for Rx in the user space application.
        Log.d(TAG, "USB: Timeout values for read: " + mDriverParameters.getReadTimeout()); //Return timeout values to be used for read operations
        Log.d(TAG, "USB: Bytes available to read from the Rx driver buffer: " + ftDevice.getQueueStatus()); //Retrieves the number of bytes available to read from the Rx driver buffer.
        Log.d(TAG, "USB: open status of the device: " + ftDevice.isOpen()); //Returns the open status of the device
        Log.d(TAG, "USB: buffer is full -> Rx pending until read: " + ftDevice.readBufferFull()); //Returns if the Rx buffer was full with data, if true, Rx would be pending until the data is read by user.
        Log.d(TAG, "USB: LATENCY_TIMER: " + ftDevice.getLatencyTimer()); //Retrieves the current latency timer value from the device
        Log.i(TAG, "USB: Receiving data!");

        Toast.makeText(getBaseContext(), getString(R.string.receiving), Toast.LENGTH_SHORT).show();

        /* Start the sender thread to send command to OpenBCI Board */
        startSenderThread();
        /* Start the receiver thread to get data from OpenBCI Board */
        new BciReceiver().startReceiverThread(this);

        return Service.START_REDELIVER_INTENT;
        //return Service.START_STICKY;
    }

    @Override
    public void onDestroy() {
        Log.i(TAG, "onDestroy()");
        super.onDestroy();

        ftDevice.purge(D2xxManager.FT_PURGE_RX);
        receiverThreadRunning = false;

        try {
            Thread.sleep(SLEEP);
        } catch (InterruptedException e) {
            // ignore
        }

        if (null != ftDevice) {
            try {
                ftDevice.close();
            } catch (Exception e) {
                Log.e(TAG, "failed to close device", e);
            }
            ftDevice = null;
        }
        unregisterReceiver(mReceiver);
        stopForeground(true);
        Toast.makeText(this, "BCI Service Stopped.", Toast.LENGTH_SHORT).show();
    }



    /*
    SEND COMMAND TO OPENBCI BOARD - The BroadcastReceiver mReceiver method receives
    and handles broadcast intents sent by sendBroadcast(Intent) from the Toggle Streaming Button
    to start/stop streaming EEG data in the MainActivity Class OnCreate() Method.
    */
    private final BroadcastReceiver mReceiver = new BroadcastReceiver()  {
        //        private SenderThread mSenderThread;
        @Override
        public void onReceive(Context context, Intent intent) {
            final String action = intent.getAction();
            Log.d(TAG, "BciService onReceive() " + action);

            if  (Objects.equals(intent.getAction(), SEND_COMMAND)){
//                final byte[] dataToSend = intent.getByteArrayExtra(COMMAND_EXTRA);
                final String dataToSend = intent.getStringExtra(COMMAND_EXTRA);
                if (dataToSend == null) {
                    Log.i(TAG, "No " + DATA_EXTRA + " extra in intent!");
                    Toast.makeText(context, "No extra in Intent", Toast.LENGTH_LONG).show();
                    return;
                }
                     /* mHandler is a Handler to deliver messages to the mSenderThread Looper's message
                        queue and execute them on that Looper's thread.
                        obtainMessage(), sets the what and obj members of the returned Message.
                        what = int: Value of 10 assigned to the returned Message.what field.
                        obj	= Object: Value to dataToSend assigned to the returned Message.obj field. This value may be null */
                mSenderThread.mHandler.obtainMessage(10, dataToSend).sendToTarget();
            }
        }
    };

    private void SendMessage(String writeData) {
        if (ftDevice.isOpen() == false) {
            Log.e("j2xx", "SendMessage: device not open");
            return;
        }

        //MAY NOT NEED THIS
        ftDevice.setLatencyTimer((byte) 1); //The default FTDI latency is too large for EEG apps, making the incoming signal "choppy", Change from 16ms to 1ms

        //MAY NEED THIS
//        ftDevice.purge((byte) (D2xxManager.FT_PURGE_TX | D2xxManager.FT_PURGE_RX));
//        String writeData = "v";//writeText.getText().toString();

        byte[] OutData = writeData.getBytes();
        Log.w("PROCESS_DATA:", "OutData 0:" + OutData);
        Log.w("PROCESS_DATA:", "OutData 1:" + Arrays.toString(OutData));
        ftDevice.write(OutData, writeData.length());

    }

    /* Start the sender thread to send messages to the OpenBCI Cyton Board */
    private void startSenderThread() {
        mSenderThread = new SenderThread("OpenBCI_sender");
        mSenderThread.start();
    }

    private class SenderThread extends Thread {
        Handler mHandler;
        SenderThread(String string) {
            super(string);
        }
        public void run() {
            Looper.prepare();
            mHandler = new Handler() {
                public void handleMessage(Message msg) {
                    Log.i(TAG, "USB handleMessage() 10 or not?" + msg.what);
                    if (msg.what == 10) {
                        final String writeData = (String) msg.obj;
                        SendMessage(writeData);
                        Log.d(TAG, "USB calling bulkTransfer() out: "+writeData);
                    } else if (msg.what != 10) {
                        Looper.myLooper().quit();
                    }
                }
            };
            Looper.loop();
            Log.i(TAG, "sender thread stopped");
        }
    }


    /* Create a Notification channel and set the importance */
    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel serviceChannel = new NotificationChannel(
                    CHANNEL_ID,
                    "BCI_1",
                    NotificationManager.IMPORTANCE_LOW
            );
            NotificationManager manager = getSystemService(NotificationManager.class);
            manager.createNotificationChannel(serviceChannel);
        }
    }
}
