package com.michael.bci;

import android.util.Log;

import com.rabbitmq.client.Channel;
import com.rabbitmq.client.Connection;
import com.rabbitmq.client.ConnectionFactory;

import java.io.IOException;
import java.io.Serializable;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;

import org.json.simple.JSONObject;
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
    public static final int TRANSFER_SIZE = 528; //528 | 512 | 1024 | 256
    /* One byte header 0x41 then 31 bytes of data followed by 0xCX where X is 0-F in hex */
    public static final byte readData_SIZE = (byte) 33;
    /* Header Byte 1: 0xA0 */
    public static final byte START_BYTE = (byte) 0xA0;

    public byte[] remainingBytes;

    /* Ensure startReceiverThread block is synchronized on "private final" field */
    private final Object lockObj = new Object();

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

                    synchronized (lockObj) {
                        /* Retrieves the number of bytes available to read from the FT_Device driver Rx buffer */
                        int bytesAvailable = bciService.ftDevice.getQueueStatus();

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

    /* Create RabbitMQ connection to Broker */
    private Connection getConnection() throws IOException, TimeoutException {
        ConnectionFactory factory;
        factory = RabbitmqConnection.getConnectionFactory();
        return factory.newConnection();
    }

    /* Create RabbitMQ Channel on Broker */
    private Channel getChannel(Connection connection) throws IOException {
        return connection.createChannel();
    }

    /* Declare the RabbitMQ Queue on Broker */
    private void declareQueue(Channel channel) throws IOException {
        channel.queueDeclare("bci_data", false, false, false, null);
    }

    /* ReadQueue Method reads data from the ftDevice device queue
     * Connects to the RabbitMQ broker and loops through the data */
    public void ReadQueue(byte[] readData, int bytesAvailable, BciService bciService) {
        /* Ensure bytes available are not greater than TRANSFER_SIZE */
        if (bytesAvailable > 0) {
            if (bytesAvailable > TRANSFER_SIZE) {
                bytesAvailable = TRANSFER_SIZE;
            }
            Log.d(TAG, "PROCESS_DATA 1a - queStatus: " + bytesAvailable);

            /*
            Read bytes from device.
            A call to read(byte[], int) requesting up to available bytes will return with
            the data immediately (negative number for error)
            @param readData = Bytes array to store read bytes
            @param length = Amount of bytes to read
             */
            bciService.ftDevice.read(readData, bytesAvailable,1);

            Log.w(TAG, "PROCESS_DATA 1d - Broadcast Intent:" + Arrays.toString(readData));
            if (bytesAvailable % readData_SIZE == 0 && readData[0] == START_BYTE) {
                Log.d(TAG, "received data (" + bytesAvailable + " bytes (" + ((float) bytesAvailable/ readData_SIZE));
            } else {
                Log.d(TAG, "received data (" + bytesAvailable + " bytes ("+((float)bytesAvailable / readData_SIZE)+", but readData size/start byte (" + readData[0] + ") is incorrect");
            }

            //Work we want completed
            try {
                Log.w(TAG, "PROCESS_DATA 1e - Broadcast Intent:" + Arrays.toString(readData));

                /* Placed RabbitMQ connection, channel, queue into their own Methods */
                String QUEUE_NAME = "bci_data"; //RabbitMQ Queue Name

                Connection connection = getConnection();
                Channel channel = getChannel(connection);
                declareQueue(channel);


                if (readData[0] != START_BYTE) {
                    if (remainingBytes != null) {
                        byte[] dataTmp = new byte[remainingBytes.length + readData.length];
                        System.arraycopy(remainingBytes, 0, dataTmp, 0, remainingBytes.length);
                        System.arraycopy(readData, 0, dataTmp, remainingBytes.length, readData.length);
                        remainingBytes = null;
                        readData = dataTmp;
                    }
                }

                /* Synchronise - Add a pre-loop to scan data stream based on START_BYTE locations */
                int iStart = 0;
                for (; iStart < readData.length; iStart++) {
                    if (readData[iStart] == START_BYTE && readData[iStart + 33] == START_BYTE && readData[iStart + 66] == START_BYTE) {
                        break;
                    }
                }

                if (iStart > (readData.length - 1))
                {
                    Log.w(TAG, "Error - we couldn't find a START_BYTE sync");
                    return; // Error - we couldn't find a sync
                }


                for (int i = 0; i < readData.length; i++) {

                    //Add END_BYTE testing
                    if (readData[i] == START_BYTE && readData[i + 33] == START_BYTE && readData[iStart + 66] == START_BYTE) {
                        if (readData.length > i + 32) {
                            Log.w(TAG, "PROCESS_DATA 5:" + readData.length +"|" + i );


                            /*Encode a JSON object using LinkedHashMap so order of the entries is preserved
                            and moved JSON construction is in its own object */
                            LinkedHashMap<String, Serializable> obj = BciReceiver.jsonBciConstructor(readData, i);

                            i = i + 32;

                            //Log.w(TAG, "PROCESS_DATA 7: " + SampleNumber + ", " + ch1 + ", " + ch2 + "," + ch3 + ", " + ch4 + "," + ch5 + "," + ch6 + "," + ch7 +"," + ch8);
                            //Log.w(TAG, "PROCESS_DATA 8 - DATA: Loop" + i );

                            channel.basicPublish("", QUEUE_NAME, null, JSONValue.toJSONString(obj).getBytes(StandardCharsets.UTF_8));
                        } else {
                            if (readData.length < TRANSFER_SIZE) {
                                if (readData.length % readData_SIZE != 0) {
                                   remainingBytes = Arrays.copyOfRange(readData, i, readData.length - 1);
                                    Log.w(TAG, "PROCESS_DATA 9: " + Arrays.toString(remainingBytes));
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


    /*
    Suppress due to an unchecked 'put(K,V)' warning because org.json.simple.JSONObject uses raw type
    collections internally - need to change to a library which supports generics to be more type safe.
     */
    @SuppressWarnings(value = "unchecked")

    /* BCI EEG Data JSON construction */
    private static LinkedHashMap<String, Serializable> jsonBciConstructor(byte[] readData, int i)
    {
        LinkedHashMap<String, java.io.Serializable> obj = new LinkedHashMap<>();

        Calendar calendar = Calendar.getInstance(); //Get calendar using current time zone and locale of the system.
        SimpleDateFormat simpleDateFormat = new SimpleDateFormat("yyyy-MM-dd-HH-mm-ss-SSS"); //format and parse date-time
        String dateString = simpleDateFormat.format(calendar.getTime()); //Create a new String using the date-time format
        obj.put("TS", dateString); // Create a Timestamp
        /*
        Byte 1 is reserving to use as a readData check-sum by OpenBCI protocol
        Byte 2 is EEG readData Sample Number cast to an int and masked off the sign bits
        */
        Integer SampleNumber = readData[i + 2] & 0xFF;
        Log.w(TAG, "PROCESS_DATA 6:" + SampleNumber );
        obj.put("SN", SampleNumber); //readData[i + 2] & 0xFF
         /*
         Bytes 3-5: Data value for EEG channel 1 and convert Byte To MicroVolts
         float ch1 = OpenBci.convertByteToMicroVolts(Arrays.copyOfRange(readData, i + 2, i + 5));
         */
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
        obj.put("accelX", (float) OpenBci.interpret16bitAsInt32(Arrays.copyOfRange(readData, i + 26, i + 28)));
        //Bytes 29-30: Data value for accelerometer channel Y AY1-AY0
        //float accelY = OpenBci.interpret16bitAsInt32(Arrays.copyOfRange(readData, i + 28, i + 30));
        obj.put("accelY", (float) OpenBci.interpret16bitAsInt32(Arrays.copyOfRange(readData, i + 28, i + 30)));
        //Bytes 31-32: Data value for accelerometer channel Z AZ1-AZ0
        //float accelZ = OpenBci.interpret16bitAsInt32(Arrays.copyOfRange(readData, i + 30, i + 32));
        obj.put("accelZ", (float) OpenBci.interpret16bitAsInt32(Arrays.copyOfRange(readData, i + 30, i + 32)));
        return obj;
    }
}
