package com.michael.bci;

import android.util.Log;

import com.rabbitmq.client.Connection;
import com.rabbitmq.client.ConnectionFactory;

import java.io.IOException;
import java.util.concurrent.TimeoutException;

/* RabbitMQ connection detaile */
public class RabbitmqConnection {
    public final static String TAG = "RMQ";
    private static Connection connection;

    /* Method for connection details to RabbitMQ Broker */
    public static ConnectionFactory getConnectionFactory() throws IOException, TimeoutException {
        ConnectionFactory factory = new ConnectionFactory();
        factory.setHost("IP_Address"); //IP of the RabbitMQ Message Broker
        factory.setUsername("username"); //RabbitMQ Username
        factory.setPassword("password"); //RabbitMQ Password
        factory.setVirtualHost("/"); //RabbitMQ Virtual Host
        factory.setPort(5672); //RabbitMQ Message Broker Port
        Log.d(TAG, "RMQ: Returned RMQ Connection Details");
        return factory;
    }

    /* Create RabbitMQ Connection to the Broker */
    /*
    public static Connection getConnection() throws IOException, TimeoutException {
        // Only create the connection if it doesn't already exist
        if (connection == null)
        {
            try
            {
                ConnectionFactory factory = RabbitmqConnection.getConnectionFactory();
                connection =  factory.newConnection();
            }
            catch(Exception e)
            {
                // Clean up
                if (connection != null)
                {
                    connection.close();
                    connection = null;
                }
                throw e;
            }
        }
        Log.d(TAG, "RMQ: Open Connection for EEG" +connection);
        return connection;
    }
     */

    // Cleanup the channel and leave it in an uninitialized state
    public static void CloseConnection() throws IOException, TimeoutException {
        // Only close the connection if it already exists
        if (connection != null)
        {
            connection.close();
            connection = null;
            Log.d(TAG, "RMQ: Close Connection for EEG");
        }
    }

}
