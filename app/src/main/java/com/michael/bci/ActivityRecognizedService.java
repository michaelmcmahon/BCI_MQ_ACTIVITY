package com.michael.bci;

import android.app.IntentService;
import android.content.Intent;
import android.util.Log;

import com.google.android.gms.location.ActivityRecognitionResult;
import com.google.android.gms.location.DetectedActivity;
import com.rabbitmq.client.Channel;
import com.rabbitmq.client.Connection;
import com.rabbitmq.client.ConnectionFactory;

import org.json.simple.JSONObject;

import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.List;
import java.util.concurrent.TimeoutException;

public class ActivityRecognizedService extends IntentService {

    public ActivityRecognizedService() {
        super("ActivityRecognizedService");
    }

    public ActivityRecognizedService(String name) {
        super(name);
    }

    @Override
    protected void onHandleIntent(Intent intent) {
        if(ActivityRecognitionResult.hasResult(intent)) {
            ActivityRecognitionResult result = ActivityRecognitionResult.extractResult(intent);
            handleDetectedActivities( result.getProbableActivities() );
        }
    }

    private void handleDetectedActivities(List<DetectedActivity> probableActivities) {

        try {
            String QUEUE_NAME = "activity"; //RabbitMQ Queue Name
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

            Calendar calendar = Calendar.getInstance(); //Get calendar using current time zone and locale of the system.
            SimpleDateFormat simpleDateFormat = new SimpleDateFormat("yyyy-MM-dd-HH-mm-ss-SSS"); //format and parse date-time
            for (DetectedActivity activity : probableActivities) {
                JSONObject obj = new JSONObject();

                String dateString = simpleDateFormat.format(calendar.getTime()); //Create a new String using the date-time format
                obj.put("TS", dateString); // Create a Timestamp

                if (activity.getType() == DetectedActivity.IN_VEHICLE && activity.getConfidence() >= 0) {
                   obj.put("In Vehicle: ", +activity.getConfidence());
                   Log.e("ActivityRecogition", "In Vehicle: " + activity.getConfidence());
               } else {
                   obj.put("In Vehicle:", 0);
               }
                if (activity.getType() == DetectedActivity.ON_BICYCLE && activity.getConfidence() >= 0) {
                    obj.put("On Bicycle: ", +activity.getConfidence());
                    Log.e("ActivityRecogition", "On Bicycle: " + activity.getConfidence());
                } else {
                    obj.put("On Bicycle:", 0);
                }
                if (activity.getType() == DetectedActivity.ON_FOOT && activity.getConfidence() >= 0) {
                    obj.put("On Foot: ", +activity.getConfidence());
                    Log.e("ActivityRecogition", "On Foot: " + activity.getConfidence());
                } else {
                    obj.put("On Foot:", 0);
                }
                if (activity.getType() == DetectedActivity.RUNNING && activity.getConfidence() >= 0) {
                    obj.put("Running: ", +activity.getConfidence());
                    Log.e("ActivityRecogition", "On Foot: " + activity.getConfidence());
                } else {
                    obj.put("Running:", 0);
                }
                if (activity.getType() == DetectedActivity.STILL && activity.getConfidence() >= 0) {
                    obj.put("Still: ", +activity.getConfidence());
                    Log.e("ActivityRecogition", "On Foot: " + activity.getConfidence());
                } else {
                    obj.put("Still:", 0);
                }
                if (activity.getType() == DetectedActivity.TILTING && activity.getConfidence() >= 0) {
                    obj.put("Tilting: ", +activity.getConfidence());
                    Log.e("ActivityRecogition", "On Foot: " + activity.getConfidence());
                } else {
                    obj.put("Tilting:", 0);
                }
                if (activity.getType() == DetectedActivity.WALKING && activity.getConfidence() >= 0) {
                    obj.put("Walking: ", +activity.getConfidence());
                    Log.e("ActivityRecogition", "On Foot: " + activity.getConfidence());
                } else {
                    obj.put("Walking:", 0);
                }
                if (activity.getType() == DetectedActivity.UNKNOWN && activity.getConfidence() >= 0) {
                    obj.put("Unknown: ", +activity.getConfidence());
                    Log.e("ActivityRecogition", "On Foot: " + activity.getConfidence());
                } else {
                    obj.put("Unknown:", 0);
                }
                channel.basicPublish("", QUEUE_NAME, null, obj.toJSONString().getBytes());
            }
        } catch (TimeoutException e) {
            e.printStackTrace();
        } catch (IOException e) {
            e.printStackTrace();
        }
    }
}

