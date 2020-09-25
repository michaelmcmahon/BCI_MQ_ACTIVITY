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

import androidx.core.app.NotificationCompat;

import com.ftdi.j2xx.D2xxManager; //management class for connected FTDI devices.
import com.ftdi.j2xx.FT_Device; // provides APIs for the host to communicate and operate FTDI devices
import com.rabbitmq.client.Channel;
import com.rabbitmq.client.Connection;
import com.rabbitmq.client.ConnectionFactory;

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

    private final static String TAG = "BCI_SERVICE 2";
    private static final String CHANNEL_ID = "BCI_1"; //Foreground Service Channel
    private byte[] remainingBytes;

    private boolean mIsRunning = false;
    private SenderThread mSenderThread;
    private static D2xxManager ftD2xx = null;
    private FT_Device ftDevice = null;

    //static final int TRANSFER_SIZE = 1024; // ...
    //static final int TRANSFER_SIZE = 528; // initial transfer size
    //static final int TRANSFER_SIZE = 512;
    //private static final int TRANSFER_SIZE = 64;
    public static final int TRANSFER_SIZE = 256; //512 | 1024
    public static final long SLEEP = 200;
    public static final int VENDOR_ID = 1027;
    public static final int BAUD_RATE = 115200;
    public static final byte PACKET_SIZE = (byte) 33; //One byte header 0x41 then 31 bytes of data followed by 0xCX where X is 0-F in hex
    public static final byte START_BYTE = (byte) 0xA0; //Header Byte 1: 0xA0
    byte mStopBit = D2xxManager.FT_STOP_BITS_1;
    byte mDataBit = D2xxManager.FT_DATA_BITS_8;
    byte mParity = D2xxManager.FT_PARITY_NONE;
    short mFlowControl = D2xxManager.FT_FLOW_NONE; //FT_FLOW_NONE / FT_FLOW_RTS_CTS / FT_FLOW_DTR_DSR / FT_FLOW_XON_XOFF
    byte[] overflowBuffer = new byte[TRANSFER_SIZE*2]; //Do I need a larger buffer size

    private volatile boolean receiverThreadRunning;


    //Define the Custom Action Intents for BroadcastReceiver
    final static String DATA_RECEIVED_INTENT = "bci.intent.action.DATA_RECEIVED";
    final static String DATA_EXTRA = "bci.intent.extra.DATA";
    final static String SEND_COMMAND = "bci.intent.action.SEND_COMMAND";
    final static String COMMAND_EXTRA = "bci.intent.extra.COMMAND_EXTRA";
    final static String SEND_DATA_INTENT = "bci.intent.action.SEND_DATA";
    final static String DATA_SENT_INTERNAL_INTENT = "bci.internal.intent.action.DATA_SENT";


    private D2xxManager.DriverParameters mDriverParameters;

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

        //Set the Foreground notification's tap action
        createNotificationChannel();
        Intent notificationIntent = new Intent(this, MainActivity.class);
        notificationIntent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
        PendingIntent pendingIntent = PendingIntent.getActivity(this, 0, notificationIntent, 0);

        //Set the Foreground notification content
        Notification notification = new NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle("BCI_1")
                .setContentText("This ia a test foreground message for BCI Service 1")
//                .setSmallIcon(R.drawable.icon)
                .setPriority(NotificationCompat.PRIORITY_DEFAULT)
                .setContentIntent(pendingIntent)
                .build();

        //Start Foreground Service
        startForeground(1, notification);


        if (mIsRunning) {
            Log.i(TAG, "Service already running.");
            return Service.START_REDELIVER_INTENT;
        }
        //obtain the UsbDevice that represents the attached device from the intent-filter set in AndroidManifest.xml
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

        // connect
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

        // reset to UART mode for 232 devices
        ftDevice.setBitMode((byte) 0, D2xxManager.FT_BITMODE_RESET); // Reset FT Device
        ftDevice.setBaudRate(BAUD_RATE); //Set BaudRat

        ftDevice.setLatencyTimer((byte) 16); //The default FTDI 16 latency may be too large for EEG apps, making the incoming signal "choppy", May change from 16ms to 1ms

        //FTDI USB configured as serial port running at 115200 baud using typical 8-N-1.
        byte dataBit = mDataBit; /*Individual bits representing the data character. Fewer bits reduces the range of data, but can increase the effective data transfer rate - 8:8bit, 7: 7bit etc*/
        byte stopBit = mStopBit; /*time interval to indicate the end of that character - can be configured to remain idle for 1- or 2-bit durations.*/
        byte parity = mParity; /*Optional error checking value to indicate if the contents of the data bits sum to an even or odd value - 0: none, 1: odd, 2: even, 3: mark, 4: space*/
        ftDevice.setDataCharacteristics(dataBit, stopBit, parity);

        short flowControl = mFlowControl; /*0:none, 1: flow control(CTS,RTS)*/
        ftDevice.setFlowControl(flowControl, (byte) 0x0b, (byte) 0x0d);

        ftDevice.purge(D2xxManager.FT_PURGE_TX);
        ftDevice.purge(D2xxManager.FT_PURGE_RX); //Added
        ftDevice.restartInTask();


        mIsRunning = true;
        mDriverParameters = new D2xxManager.DriverParameters();
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
        startReceiverThread();
        startSenderThread();

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

    private byte[] getLineEncoding(int baudRate) {
        final byte[] lineEncodingRequest = {(byte) 0x80, 0x25, 0x00, 0x00, 0x00, 0x00, 0x08};
        //Get the least significant byte of baudRate,
        //and put it in first byte of the array being sent
        lineEncodingRequest[0] = (byte) (baudRate & 0xFF);

        //Get the 2nd byte of baudRate,
        //and put it in second byte of the array being sent
        lineEncodingRequest[1] = (byte) ((baudRate >> 8) & 0xFF);

        //ibid, for 3rd byte (my guess, because you need at least 3 bytes
        //to encode your 115200+ settings)
        lineEncodingRequest[2] = (byte) ((baudRate >> 16) & 0xFF);

        return lineEncodingRequest;

    }

    //SEND COMMAND TO OPENBCI BOARD
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

    //
    private void startSenderThread() {
        mSenderThread = new SenderThread("arduino_sender");
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
                    Log.i(TAG, "USB handleMessage() 10 or 11?" + msg.what);
                    if (msg.what == 10) {
//                        final byte[] dataToSend = (byte[]) msg.obj;
                        final String writeData = (String) msg.obj;
                        SendMessage(writeData);
                        Log.d(TAG, "USB calling bulkTransfer() out: "+writeData);
//                        final int len = mUsbConnection.bulkTransfer(mOutUsbEndpoint, dataToSend, dataToSend.length, 0);
//                        Log.d(TAG, len + " of " + dataToSend.length + " sent.");
//                        Intent sendIntent = new Intent(DATA_SENT_INTERNAL_INTENT);
//                        sendIntent.putExtra(DATA_EXTRA, dataToSend);
//                        sendBroadcast(sendIntent);
                    } else if (msg.what == 11) {
                        Looper.myLooper().quit();
                    }
                }
            };
            Looper.loop();
            Log.i(TAG, "sender thread stopped");
        }
    }


    //RECEIVE DATA FROM OPENBCI BOARD
    private void startReceiverThread() {

        receiverThreadRunning = true;

        //CONSIDER Runnable runnable = new Runnable()
        new Thread("receiver") {
            public void run() {
                byte[] readData = new byte[TRANSFER_SIZE];

                // todo receiverThreadRunning == true
                while (receiverThreadRunning) {
                    try {
                        Thread.sleep(1);
                    } catch (InterruptedException e) {
                        // ignore
                    }

                    synchronized (ftDevice) {
                        int queueStatus = ftDevice.getQueueStatus(); //Get the bytes Available Queue Status ftDevice.
                        int available = queueStatus; // Set available to bytes Available
                        if (queueStatus > 0) {
                            if (available > TRANSFER_SIZE) {
                                available = TRANSFER_SIZE; //ensure bytes available are not greater than TRANSFER_SIZE
                            }
                            Log.d(TAG, "PROCESS_DATA 1a - queStatus: " + queueStatus);
                            Log.d(TAG, "PROCESS_DATA 1b - available: " + available);

                            ftDevice.read(readData, available); //A call to read(byte[], int) requesting up to available bytes will return with the data immediately (negative number for error)
                            byte[] buffer = new byte[available]; //Create empty buffer array
                            //arraycopy copies a source array from a specific beginning position to the destination array from the mentioned position.
                            //No. of arguments to be copied are decided by len argument.
                            //arraycopy(Object source_arr, int sourcePos, Object dest_arr, int destPos, int len)
                            System.arraycopy(readData, 0, buffer, 0, available); //copy from readData array to buffer array
                            Log.w(TAG, "PROCESS_DATA 1c - Broadcast Intent:" + Arrays.toString(readData));
                            Log.w(TAG, "PROCESS_DATA 1d - Broadcast Intent:" + Arrays.toString(buffer));

                            if (available % PACKET_SIZE == 0 && readData[0] == START_BYTE) {
                                Log.d(TAG, "received data (" + available + " bytes (" + ((float) available / PACKET_SIZE));
                            } else {
                                Log.d(TAG, "received data (" + available + " bytes ("+((float)available / PACKET_SIZE)+", but packet size/start byte (" + readData[0] + ") is incorrect");
                            }
                            //ENTER LOOP HERE

                            //android ftdi usb loop buffer example


                            //Work we want completed
                            try {
                                byte[] packet = buffer; // Store packet data
                                Log.w(TAG, "PROCESS_DATA 1e - Broadcast Intent:" + Arrays.toString(packet));
                                String QUEUE_NAME = "json-example"; //RabbitMQ Queue Name
                                ConnectionFactory factory;
                                factory = new ConnectionFactory();
                                factory.setHost("34.244.234.79"); //IP of the RabbitMQ Message Broker
                                factory.setUsername("user"); //RabbitMQ Username
                                factory.setPassword("VIIu8eoVRYrH"); //RabbitMQ Password
                                factory.setVirtualHost("/"); //RabbitMQ Virtual Host
                                factory.setPort(5672); //RabbitMQ Message Broker Port
                                Connection connection = factory.newConnection();
                                Channel channel = connection.createChannel();
                                channel.queueDeclare(QUEUE_NAME, false, false, false, null);


                                if (packet[0] != START_BYTE) {
                                    if (remainingBytes != null) {
                                        byte[] dataTmp = new byte[remainingBytes.length + packet.length];
                                        System.arraycopy(remainingBytes, 0, dataTmp, 0, remainingBytes.length);
                                        System.arraycopy(packet, 0, dataTmp, remainingBytes.length, packet.length);
                                        remainingBytes = null;
                                        packet = dataTmp;
                                    }
                                }



                                for (int i = 0; i < packet.length; i++) {
                                    //Log.w(TAG, "PROCESS_DATA 3:" + i +"|" + packet.length);
                                    JSONObject obj = new JSONObject();
                                    if (packet[i] == START_BYTE) {
                                        //Log.w(TAG, "PROCESS_DATA 4:" + packet[i] +"|" + OpenBci.START_BYTE);
                                        if (packet.length > i + 32) {
                                            Log.w(TAG, "PROCESS_DATA 5:" + packet.length +"|" + i );
                                            Calendar calendar = Calendar.getInstance(); //Get calendar using current time zone and locale of the system.
                                            SimpleDateFormat simpleDateFormat = new SimpleDateFormat("yyyy-MM-dd-HH-mm-ss-SSS"); //format and parse date-time
                                            String dateString = simpleDateFormat.format(calendar.getTime()); //Create a new String using the date-time format
                                            obj.put("TS", dateString); // Create a Timestamp
                                            //Byte 1 is reserving to use as a packet check-sum by OpenBCI protocol
                                            //Byte 2 is EEG Packet Sample Number cast to an int and masked off the sign bits
                                            Integer SampleNumber = new Integer(packet[i + 2] & 0xFF);
                                            Log.w(TAG, "PROCESS_DATA 6:" + SampleNumber );
                                            obj.put("c", SampleNumber); //packet[i + 2] & 0xFF
                                            //Bytes 3-5: Data value for EEG channel 1 and convert Byte To MicroVolts
                                            //float ch1 = OpenBci.convertByteToMicroVolts(Arrays.copyOfRange(packet, i + 2, i + 5));
                                            obj.put("ch1", new Float(OpenBci.convertByteToMicroVolts(Arrays.copyOfRange(packet, i + 2, i + 5))));
                                            //Bytes 6-8: Data value for EEG channel 2 and convert Byte To MicroVolts
                                            //float ch2 = OpenBci.convertByteToMicroVolts(Arrays.copyOfRange(packet, i + 5, i + 8));
                                            obj.put("ch2", new Float(OpenBci.convertByteToMicroVolts(Arrays.copyOfRange(packet, i + 5, i + 8))));
                                            //Bytes 9-11: Data value for EEG channel 3 and convert Byte To MicroVolts
                                            //float ch3 = OpenBci.convertByteToMicroVolts(Arrays.copyOfRange(packet, i + 8, i + 11));
                                            obj.put("ch3", new Float(OpenBci.convertByteToMicroVolts(Arrays.copyOfRange(packet, i + 8, i + 11))));
                                            //Bytes 12-14: Data value for EEG channel 4 and convert Byte To MicroVolts
                                            //float ch4 = OpenBci.convertByteToMicroVolts(Arrays.copyOfRange(packet, i + 11, i + 14));
                                            obj.put("ch4", new Float(OpenBci.convertByteToMicroVolts(Arrays.copyOfRange(packet, i + 11, i + 14))));
                                            //Bytes 15-17: Data value for EEG channel 5 and convert Byte To MicroVolts
                                            //float ch5 = OpenBci.convertByteToMicroVolts(Arrays.copyOfRange(packet, i + 14, i + 17));
                                            obj.put("ch5", new Float(OpenBci.convertByteToMicroVolts(Arrays.copyOfRange(packet, i + 14, i + 17))));
                                            //Bytes 18-20: Data value for EEG channel 6 and convert Byte To MicroVolts
                                            //float ch6 = OpenBci.convertByteToMicroVolts(Arrays.copyOfRange(packet, i + 17, i + 20));
                                            obj.put("ch6", new Float(OpenBci.convertByteToMicroVolts(Arrays.copyOfRange(packet, i + 17, i + 20))));
                                            //Bytes 21-23: Data value for EEG channel 7 and convert Byte To MicroVolts
                                            //float ch7 = OpenBci.convertByteToMicroVolts(Arrays.copyOfRange(packet, i + 20, i + 23));
                                            obj.put("ch7", new Float(OpenBci.convertByteToMicroVolts(Arrays.copyOfRange(packet, i + 20, i + 23))));
                                            //Bytes 24-26: Data value for EEG channel 8 and convert Byte To MicroVolts
                                            //float ch8 = OpenBci.convertByteToMicroVolts(Arrays.copyOfRange(packet, i + 23, i + 26));
                                            obj.put("ch8", new Float(OpenBci.convertByteToMicroVolts(Arrays.copyOfRange(packet, i + 23, i + 26))));
                                            //Bytes 27-28: Data value for accelerometer channel X AY1-AY0
                                            //float accelX = OpenBci.interpret16bitAsInt32(Arrays.copyOfRange(packet, i + 26, i + 28));
                                            obj.put("accelX", new Float(OpenBci.interpret16bitAsInt32(Arrays.copyOfRange(packet, i + 26, i + 28))));
                                            //Bytes 29-30: Data value for accelerometer channel Y AY1-AY0
                                            //float accelY = OpenBci.interpret16bitAsInt32(Arrays.copyOfRange(packet, i + 28, i + 30));
                                            obj.put("accelY", new Float(OpenBci.interpret16bitAsInt32(Arrays.copyOfRange(packet, i + 28, i + 30))));
                                            //Bytes 31-32: Data value for accelerometer channel Z AZ1-AZ0
                                            //float accelZ = OpenBci.interpret16bitAsInt32(Arrays.copyOfRange(packet, i + 30, i + 32));
                                            obj.put("accelZ", new Float(OpenBci.interpret16bitAsInt32(Arrays.copyOfRange(packet, i + 30, i + 32))));

                                            i = i + 32;

                                            //Log.w(TAG, "PROCESS_DATA 7: " + SampleNumber + ", " + ch1 + ", " + ch2 + "," + ch3 + ", " + ch4 + "," + ch5 + "," + ch6 + "," + ch7 +"," + ch8);
                                            //Log.w(TAG, "PROCESS_DATA 8 - DATA: Loop" + i );

                                            channel.basicPublish("", QUEUE_NAME, null, obj.toJSONString().getBytes());


                                        } else {
                                            if (packet.length < BciService.TRANSFER_SIZE) {
                                                if (packet.length % PACKET_SIZE != 0) {
                                                    remainingBytes = Arrays.copyOfRange(packet, i, packet.length - 1);
                                                    Log.w(TAG, "PROCESS_DATA 9: " + remainingBytes);
                                                    Log.w(TAG, "PROCESS_DATA 10: " + Arrays.toString(remainingBytes));
                                                }
                                            }
                                        }
                                    }
                                }
                                channel.close();
                                connection.close();
                            } catch (Exception e) {
                                e.printStackTrace();
                            }

                            //END LOOP HERE
                        }
                    }
                }

                Log.d(TAG, "receiver thread stopped.");
            }
        }.start();
    }


    //Create a Notification channel and set the importance
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
