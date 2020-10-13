package com.michael.bci;

import android.util.Log;

import com.rabbitmq.client.Channel;
import com.rabbitmq.client.Connection;
import com.rabbitmq.client.ConnectionFactory;

import org.json.simple.JSONObject;

import java.text.SimpleDateFormat;
import java.util.Arrays;
import java.util.Calendar;

public class BciReceiver {

    /*
        Method to create thread which will receive and process data from the OpenBCI Cyton Board
        */
    void startReceiverThread(BciService bciService) {

        bciService.receiverThreadRunning = true;

        //CONSIDER Runnable runnable = new Runnable()
        new Thread("receiver") {
            public void run() {
                byte[] readData = new byte[BciService.TRANSFER_SIZE];

                // todo receiverThreadRunning == true
                while (bciService.receiverThreadRunning) {
                    try {
                        Thread.sleep(1);
                    } catch (InterruptedException e) {
                        // Say something?
                    }

                    synchronized (bciService.lockObj) {
                        /* Retrieves the number of bytes available to read from the FT_Device driver Rx buffer */
                        int bytesAvailable = bciService.ftDevice.getQueueStatus();

                        /* Pass the bytes available info to the ReadQueue Method */
                        new BciReceiver().ReadQueue(readData, bytesAvailable, bciService);
                    }
                }

                Log.d(BciService.TAG, "receiver thread stopped.");
            }
        }.start();
    }

    /* ReadQueue Method reads data from the ftDevice device queue
    * Connects to the RabbitMQ broker and loops through the data */
    public void ReadQueue(byte[] readData, int bytesAvailable, BciService bciService) {
        /* Ensure bytes available are not greater than TRANSFER_SIZE */
        if (bytesAvailable > 0) {
            if (bytesAvailable > BciService.TRANSFER_SIZE) {
                bytesAvailable = BciService.TRANSFER_SIZE;
            }
            Log.d(BciService.TAG, "PROCESS_DATA 1a - queStatus: " + bytesAvailable);

            /* A call to read(byte[], int) requesting up to available bytes will return with
            the data immediately (negative number for error) */
            bciService.ftDevice.read(readData, bytesAvailable);
            /* Create empty buffer array */
            byte[] buffer = new byte[bytesAvailable];
            /*
            arraycopy copies a source 'readData' array from a specific beginning position to the
            destination 'buffer' array from the mentioned position. No. of arguments to be copied
            are decided by len argument.
            arraycopy(Object source_arr, int sourcePos, Object dest_arr, int destPos, int len)
             */
            System.arraycopy(readData, 0, buffer, 0, bytesAvailable);

            Log.w(BciService.TAG, "PROCESS_DATA 1c - Broadcast Intent:" + Arrays.toString(readData));
            Log.w(BciService.TAG, "PROCESS_DATA 1d - Broadcast Intent:" + Arrays.toString(buffer));
            if (bytesAvailable % BciService.PACKET_SIZE == 0 && readData[0] == BciService.START_BYTE) {
                Log.d(BciService.TAG, "received data (" + bytesAvailable + " bytes (" + ((float) bytesAvailable/ BciService.PACKET_SIZE));
            } else {
                Log.d(BciService.TAG, "received data (" + bytesAvailable + " bytes ("+((float)bytesAvailable / BciService.PACKET_SIZE)+", but packet size/start byte (" + readData[0] + ") is incorrect");
            }
            // ENTER LOOP HERE OR BROADCAST INTENT & SETUP RECEIVER
            // Maybe an android ftdi usb loop buffer


            //Work we want completed
            try {
                /* Store packet data */
                byte[] packet = buffer;
                Log.w(BciService.TAG, "PROCESS_DATA 1e - Broadcast Intent:" + Arrays.toString(packet));
                String QUEUE_NAME = "bci_data"; //RabbitMQ Queue Name
                ConnectionFactory factory;
                factory = RabbitmqConnection.getConnectionFactory();
                Connection connection = factory.newConnection();
                Channel channel = connection.createChannel();
                channel.queueDeclare(QUEUE_NAME, false, false, false, null);


                if (packet[0] != BciService.START_BYTE) {
                    if (bciService.remainingBytes != null) {
                        byte[] dataTmp = new byte[bciService.remainingBytes.length + packet.length];
                        System.arraycopy(bciService.remainingBytes, 0, dataTmp, 0, bciService.remainingBytes.length);
                        System.arraycopy(packet, 0, dataTmp, bciService.remainingBytes.length, packet.length);
                        bciService.remainingBytes = null;
                        packet = dataTmp;
                    }
                }



                for (int i = 0; i < packet.length; i++) {
                    JSONObject obj = new JSONObject();
                    if (packet[i] == BciService.START_BYTE) {
                        if (packet.length > i + 32) {
                            Log.w(BciService.TAG, "PROCESS_DATA 5:" + packet.length +"|" + i );

                            /* JSON construction is in its own jsonBciConstructor method */
                            new BciReceiver().jsonBciConstructor(packet, i, obj);

                            i = i + 32;

                            //Log.w(TAG, "PROCESS_DATA 7: " + SampleNumber + ", " + ch1 + ", " + ch2 + "," + ch3 + ", " + ch4 + "," + ch5 + "," + ch6 + "," + ch7 +"," + ch8);
                            //Log.w(TAG, "PROCESS_DATA 8 - DATA: Loop" + i );

                            channel.basicPublish("", QUEUE_NAME, null, obj.toJSONString().getBytes());
                        } else {
                            if (packet.length < BciService.TRANSFER_SIZE) {
                                if (packet.length % BciService.PACKET_SIZE != 0) {
                                    bciService.remainingBytes = Arrays.copyOfRange(packet, i, packet.length - 1);
                                    Log.w(BciService.TAG, "PROCESS_DATA 9: " + Arrays.toString(bciService.remainingBytes));
                                    Log.w(BciService.TAG, "PROCESS_DATA 10: " + Arrays.toString(bciService.remainingBytes));
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

    /* BCI EEG Data JSON construction method */
    public void jsonBciConstructor(byte[] packet, int i, JSONObject obj) {
        Calendar calendar = Calendar.getInstance(); //Get calendar using current time zone and locale of the system.
        SimpleDateFormat simpleDateFormat = new SimpleDateFormat("yyyy-MM-dd-HH-mm-ss-SSS"); //format and parse date-time
        String dateString = simpleDateFormat.format(calendar.getTime()); //Create a new String using the date-time format
        obj.put("TS", dateString); // Create a Timestamp
        /*
        Byte 1 is reserving to use as a packet check-sum by OpenBCI protocol
        Byte 2 is EEG Packet Sample Number cast to an int and masked off the sign bits
        */
        Integer SampleNumber = packet[i + 2] & 0xFF;
        Log.w(BciService.TAG, "PROCESS_DATA 6:" + SampleNumber );
        obj.put("c", SampleNumber); //packet[i + 2] & 0xFF
         /*
         Bytes 3-5: Data value for EEG channel 1 and convert Byte To MicroVolts
         float ch1 = OpenBci.convertByteToMicroVolts(Arrays.copyOfRange(packet, i + 2, i + 5));
         */
        obj.put("ch1", OpenBci.convertByteToMicroVolts(Arrays.copyOfRange(packet, i + 2, i + 5)));
        //Bytes 6-8: Data value for EEG channel 2 and convert Byte To MicroVolts
        //float ch2 = OpenBci.convertByteToMicroVolts(Arrays.copyOfRange(packet, i + 5, i + 8));
        obj.put("ch2", OpenBci.convertByteToMicroVolts(Arrays.copyOfRange(packet, i + 5, i + 8)));
        //Bytes 9-11: Data value for EEG channel 3 and convert Byte To MicroVolts
        //float ch3 = OpenBci.convertByteToMicroVolts(Arrays.copyOfRange(packet, i + 8, i + 11));
        obj.put("ch3", OpenBci.convertByteToMicroVolts(Arrays.copyOfRange(packet, i + 8, i + 11)));
        //Bytes 12-14: Data value for EEG channel 4 and convert Byte To MicroVolts
        //float ch4 = OpenBci.convertByteToMicroVolts(Arrays.copyOfRange(packet, i + 11, i + 14));
        obj.put("ch4", OpenBci.convertByteToMicroVolts(Arrays.copyOfRange(packet, i + 11, i + 14)));
        //Bytes 15-17: Data value for EEG channel 5 and convert Byte To MicroVolts
        //float ch5 = OpenBci.convertByteToMicroVolts(Arrays.copyOfRange(packet, i + 14, i + 17));
        obj.put("ch5", OpenBci.convertByteToMicroVolts(Arrays.copyOfRange(packet, i + 14, i + 17)));
        //Bytes 18-20: Data value for EEG channel 6 and convert Byte To MicroVolts
        //float ch6 = OpenBci.convertByteToMicroVolts(Arrays.copyOfRange(packet, i + 17, i + 20));
        obj.put("ch6", OpenBci.convertByteToMicroVolts(Arrays.copyOfRange(packet, i + 17, i + 20)));
        //Bytes 21-23: Data value for EEG channel 7 and convert Byte To MicroVolts
        //float ch7 = OpenBci.convertByteToMicroVolts(Arrays.copyOfRange(packet, i + 20, i + 23));
        obj.put("ch7", OpenBci.convertByteToMicroVolts(Arrays.copyOfRange(packet, i + 20, i + 23)));
        //Bytes 24-26: Data value for EEG channel 8 and convert Byte To MicroVolts
        //float ch8 = OpenBci.convertByteToMicroVolts(Arrays.copyOfRange(packet, i + 23, i + 26));
        obj.put("ch8", OpenBci.convertByteToMicroVolts(Arrays.copyOfRange(packet, i + 23, i + 26)));
        //Bytes 27-28: Data value for accelerometer channel X AY1-AY0
        //float accelX = OpenBci.interpret16bitAsInt32(Arrays.copyOfRange(packet, i + 26, i + 28));
        obj.put("accelX", (float) OpenBci.interpret16bitAsInt32(Arrays.copyOfRange(packet, i + 26, i + 28)));
        //Bytes 29-30: Data value for accelerometer channel Y AY1-AY0
        //float accelY = OpenBci.interpret16bitAsInt32(Arrays.copyOfRange(packet, i + 28, i + 30));
        obj.put("accelY", (float) OpenBci.interpret16bitAsInt32(Arrays.copyOfRange(packet, i + 28, i + 30)));
        //Bytes 31-32: Data value for accelerometer channel Z AZ1-AZ0
        //float accelZ = OpenBci.interpret16bitAsInt32(Arrays.copyOfRange(packet, i + 30, i + 32));
        obj.put("accelZ", (float) OpenBci.interpret16bitAsInt32(Arrays.copyOfRange(packet, i + 30, i + 32)));
    }
}
