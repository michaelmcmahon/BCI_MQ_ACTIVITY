package com.michael.bci;

import android.util.Log;

import com.rabbitmq.client.Channel;
import com.rabbitmq.client.Connection;
import com.rabbitmq.client.ConnectionFactory;

import java.io.IOException;
import java.io.Serializable;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;

import org.json.simple.JSONValue;

import java.text.SimpleDateFormat;
import java.util.Arrays;
import java.util.Calendar;
import java.util.concurrent.TimeoutException;


/* BciReceiver class groups methods for receiving data from the OpenBCI Board
* The EEG data streams back from the Board to the App continuously (once started).*/
public class BciReceiver {

    public final static String TAG = "BCI_RECEIVER";
    /* Set the data transfer size */
    public static final int TRANSFER_SIZE = 528; //528 = 33 x 16
    /* One byte header 0x41 then 31 bytes of data followed by where X is 0-F in hex */
    public static final byte readData_SIZE = (byte)33;
    /* Header and Footer Bytes for each packet */
    final static byte START_BYTE = (byte)0xA0;
    final static byte END_BYTE = (byte)0xC0;

    public byte[] remainingBytes;

    /* Ensure startReceiverThread block is synchronized on "private final" field */
    private final Object lockObj = new Object();
    private Connection connection;
    private Channel channel_1;

    /* Method to create thread which will receive and process data from the OpenBCI Cyton Board */
    void startReceiverThread(BciService bciService) {

        bciService.receiverThreadRunning = true;

        //CONSIDER Runnable runnable = new Runnable()
        new Thread("receiver") {
            public void run() {
                //Create BCIReceiver Object Instance
                BciReceiver BciReceiverMain = new BciReceiver();

               byte[] readData = new byte[TRANSFER_SIZE];

                while (bciService.receiverThreadRunning) {

                    try {
                        Thread.sleep(50);
                    } catch (InterruptedException e) {
                        // Say something?
                    }

                    synchronized (lockObj) {
                        /* Retrieves the number of bytes available to read from the FT_Device driver Rx buffer */
                        int bytesAvailable = bciService.ftDevice.getQueueStatus();
                        //Log.d(TAG, "bytesAvailable 1 " + bytesAvailable);

                        /* If bytes available then pass the bytes available info to the ReadQueue Method */
                        if (bytesAvailable > 0) {
                            BciReceiverMain.ReadQueue(readData, bytesAvailable, bciService);
                        }
                    }
                }

                Log.d(TAG, "receiver thread stopped.");
            }
        }.start();
    }

    /* Create RabbitMQ Channel on Broker */
    private Channel getChannel() throws IOException, TimeoutException {
        // Only create the channel if it doesn't already exist
        if (channel_1 == null)
        {
            try
            {
                channel_1 = RabbitmqConnection.getConnection().createChannel();
                channel_1.queueDeclare("bci_data", false, false, false, null);
            }
            catch(Exception e)
            {
                // Clean up
                if (channel_1 != null)
                {
                    channel_1.close();
                    channel_1 = null;
                }

                throw e;
            }
        }
        Log.d(TAG, "RMQ: Open Channel 1 for EEG" +channel_1);
        return channel_1;
    }

    // Cleanup the channel and leave it in an uninitialized state
    private void CloseChannel() throws IOException, TimeoutException {
        if (channel_1 != null)
        {
            channel_1.close();
            channel_1 = null;
            Log.d(TAG, "RMQ: Close Channel 1 for EEG");
        }
    }

    /* ReadQueue Method reads data from the ftDevice device queue
     * Connects to the RabbitMQ broker and loops through the data */
    public void ReadQueue(byte[] readData, int bytesAvailable, BciService bciService) {
        /* Ensure bytes available are not greater than TRANSFER_SIZE */
        if (bytesAvailable > 0)
            //Log.d(TAG, "bytesAvailable 2 " + bytesAvailable);
        {
            if (bytesAvailable > TRANSFER_SIZE) {
                bytesAvailable = TRANSFER_SIZE;
            }
            Log.d(TAG, "PROCESS_DATA 1a - queStatus: " + bytesAvailable);

            /*
            Read bytes from device.
            A call to read(byte[] data, int length) requesting up to available bytes will return with
            the data immediately (negative number for error)
            @param readData = Bytes array to store read bytes
            @param length = Amount of bytes to read
             */
            bciService.ftDevice.read(readData, bytesAvailable);
            Log.d(TAG, "readData_SIZE " + readData_SIZE);

            Log.w(TAG, "PROCESS_DATA 1d - Broadcast Intent:" + Arrays.toString(readData));
            if (bytesAvailable % readData_SIZE == 0 && readData[0] == START_BYTE) {
                Log.d(TAG, "received data " + bytesAvailable + " bytes " + ((float)bytesAvailable/ readData_SIZE));
            } else {
                Log.d(TAG, "received data " + bytesAvailable + " bytes ("+((float)bytesAvailable / readData_SIZE)+"), but readData size/start byte (" + readData[0] + ") is incorrect");
            }

            //Work we want completed
            try {
                Log.w(TAG, "PROCESS_DATA 1e - Broadcast Intent:" + Arrays.toString(readData));

                /* Placed RabbitMQ connection, channel, queue into their own Methods */
                String QUEUE_NAME = "bci_data"; //RabbitMQ Queue Name

                //Connection connection = getConnection();
                Channel channel = getChannel();
                Log.d(TAG, "RMQ: Connection/Channel 1 for EEG" +channel);


                if (readData[0] != START_BYTE) {
                    if (remainingBytes != null) {
                        byte[] dataTmp = new byte[remainingBytes.length + (readData.length - 1)];
                        System.arraycopy(remainingBytes, 0, dataTmp, 0, remainingBytes.length);
                        System.arraycopy(readData, 0, dataTmp, remainingBytes.length, readData.length -1);
                        remainingBytes = null;
                        readData = dataTmp;
                    }
                }

                /* Synchronise - Add a pre-loop to scan data stream based on START_BYTE locations */
                int iStart = 0;
                boolean found_start_byte = false;
                for (; iStart < readData.length -1; iStart++) {
                    if (readData[iStart] == START_BYTE && readData[iStart + 33] == START_BYTE && readData[iStart + 66] == START_BYTE) {
                        found_start_byte = true;
                        break;
                    }
                }

                if (iStart > (readData.length - 1))
                {
                    Log.w(TAG, "Error - we couldn't find a START_BYTE sync");
                    return; // Error - we couldn't find a sync
                }


                Log.w(TAG, "Start Byte" + found_start_byte );
                if (found_start_byte) {
                    for (int i = 0; i < readData.length - 1; i++) {
                            if (readData.length -1 > i + 32 && readData[i + 33] == START_BYTE && readData[i + 32] == END_BYTE) {
                                Log.w(TAG, "PROCESS_DATA 5:" + readData.length + "|" + i);


                            /*Encode a JSON object using LinkedHashMap so order of the entries is preserved
                            and moved JSON construction is in its own object */
                                LinkedHashMap<String, Serializable> obj = BciReceiver.jsonBciConstructor(readData, i);


                                i = i + 32;

                                //Log.w(TAG, "PROCESS_DATA 7: " + SampleNumber + ", " + ch1 + ", " + ch2 + "," + ch3 + ", " + ch4 + "," + ch5 + "," + ch6 + "," + ch7 +"," + ch8);
                                //Log.w(TAG, "PROCESS_DATA 8 - DATA: Loop" + i );

                                channel.basicPublish("", QUEUE_NAME, null, JSONValue.toJSONString(obj).getBytes(StandardCharsets.UTF_8));
                                Log.w(TAG, "PROCESS_DATA_JSON: Loop" + JSONValue.toJSONString(obj) );
                            } else {
                                if (readData.length - 1 < TRANSFER_SIZE) {
                                    if (readData.length -1 % readData_SIZE != 0) {
                                        remainingBytes = Arrays.copyOfRange(readData, i, (readData.length - 1));
                                        //Log.w(TAG, "PROCESS_DATA 9: " + Arrays.toString(remainingBytes));
                                        //Log.w(TAG, "PROCESS_DATA 10: " + Arrays.toString(remainingBytes));
                                    }
                                }
                            }
                        }
                    }
            } catch (Exception e) {
                e.printStackTrace();
                // If we hit any issues, close the RabbitMQ channel, so that it is re-created on the next read.
                try {
                    //RabbitmqConnection.CloseConnection();
                    CloseChannel();
                    Log.d(TAG, "RMQ: Close Loop Channel for EEG");
                } catch (IOException | TimeoutException ioException) {
                    ioException.printStackTrace();
                }
            }

            //END LOOP HERE
        }
    }

    /* BCI EEG Data JSON construction */
    private static LinkedHashMap<String, Serializable> jsonBciConstructor(byte[] readData, int i)
    {
        LinkedHashMap<String, java.io.Serializable> obj = new LinkedHashMap<>();

        Calendar calendar = Calendar.getInstance(); //Get calendar using current time zone and locale of the system.
        SimpleDateFormat simpleDateFormat = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS"); //format and parse date-time
        String dateString = simpleDateFormat.format(calendar.getTime()); //Create a new String using the date-time format
        long unixTime = System.currentTimeMillis() / 1000L;

        /* Check header file */
        obj.put("Header", readData[i]); //readData[i + 2] & 0xFF

        Integer SampleNumber = readData[i + 1] & 0xFF;
        Log.w(TAG, "PROCESS_DATA 6:" + SampleNumber );
        obj.put("SN", SampleNumber); //readData[i + 2] & 0xFF
         //Bytes 3-5: Data value for EEG channel 1 and convert Byte To MicroVolts
        //float ch1 = OpenBci.convertByteToMicroVolts(Arrays.copyOfRange(readData, i + 2, i + 5));
        obj.put("ch1", OpenBci.convertByteToMicroVolts(Arrays.copyOfRange(readData, i + 2, i + 5)));
        //Bytes 6-8: Data value for EEG channel 2 and convert Byte To MicroVolts
        //float ch2 = OpenBci.convertByteToMicroVolts(Arrays.copyOfRange(readData, i + 5, i + 8));
        obj.put("ch2", OpenBci.convertByteToMicroVolts(Arrays.copyOfRange(readData, i + 5, i + 8)));
        //Bytes 9-11: Data value for EEG channel 3 and convert Byte To MicroVolts
        //float ch3 = OpenBci.convertByteToMicroVolts(Arrays.copyOfRange(readData, i + 8, i + 11));
        obj.put("ch3", OpenBci.convertByteToMicroVolts(Arrays.copyOfRange(readData, i + 8, i + 11)));
        //Bytes 12-14: Data value for EEG channel 4 and convert Byte To MicroVolts
        //float ch4 = OpenBci.convertByteToMicroVolts(Arrays.copyOfRange(readData, i + 11, i + 14));
        obj.put("ch4", OpenBci.convertByteToMicroVolts(Arrays.copyOfRange(readData, i + 11, i + 14)));
        //Bytes 15-17: Data value for EEG channel 5 and convert Byte To MicroVolts
        //float ch5 = OpenBci.convertByteToMicroVolts(Arrays.copyOfRange(readData, i + 14, i + 17));
        obj.put("ch5", OpenBci.convertByteToMicroVolts(Arrays.copyOfRange(readData, i + 14, i + 17)));
        //Bytes 18-20: Data value for EEG channel 6 and convert Byte To MicroVolts
        //float ch6 = OpenBci.convertByteToMicroVolts(Arrays.copyOfRange(readData, i + 17, i + 20));
        obj.put("ch6", OpenBci.convertByteToMicroVolts(Arrays.copyOfRange(readData, i + 17, i + 20)));
        //Bytes 21-23: Data value for EEG channel 7 and convert Byte To MicroVolts
        //float ch7 = OpenBci.convertByteToMicroVolts(Arrays.copyOfRange(readData, i + 20, i + 23));
        obj.put("ch7", OpenBci.convertByteToMicroVolts(Arrays.copyOfRange(readData, i + 20, i + 23)));
        //Bytes 24-26: Data value for EEG channel 8 and convert Byte To MicroVolts
        //float ch8 = OpenBci.convertByteToMicroVolts(Arrays.copyOfRange(readData, i + 23, i + 26));
        obj.put("ch8", OpenBci.convertByteToMicroVolts(Arrays.copyOfRange(readData, i + 23, i + 26)));
        //Bytes 27-28: Data value for accelerometer channel X AY1-AY0
        //float accelX = OpenBci.interpret16bitAsInt32(Arrays.copyOfRange(readData, i + 26, i + 28));
        obj.put("accelX", OpenBci.convertAccelData(Arrays.copyOfRange(readData, i + 26, i + 28)));
        //Bytes 29-30: Data value for accelerometer channel Y AY1-AY0
        //float accelY = OpenBci.interpret16bitAsInt32(Arrays.copyOfRange(readData, i + 28, i + 30));
        obj.put("accelY", OpenBci.convertAccelData(Arrays.copyOfRange(readData, i + 28, i + 30)));
        //Bytes 31-32: Data value for accelerometer channel Z AZ1-AZ0
        //float accelZ = OpenBci.interpret16bitAsInt32(Arrays.copyOfRange(readData, i + 30, i + 32));
        obj.put("accelZ", OpenBci.convertAccelData(Arrays.copyOfRange(readData, i + 30, i + 32)));
        /* Confirm footer */
        /*Start fields needed for OpenBCI GUI - just using 0.0*/
        obj.put("other1", 0.0);
        obj.put("other2", 0.0);
        obj.put("other3", 0.0);
        obj.put("other4", 0.0);
        obj.put("other5", 0.0);
        obj.put("other6", 0.0);
        obj.put("other7", 0.0);
        obj.put("analog1", 0.0);
        obj.put("analog2", 0.0);
        obj.put("analog3", 0.0);
        obj.put("UnixTS", unixTime); // Create a Unix Timestamp
        /*End fields needed for OpenBCI GUI - just using 0.0*/
        obj.put("TS", dateString); // Create a Formatted Timestamp
        obj.put("Footer", readData[i + 32]); // Create a Timestamp
        return obj;
    }
}
