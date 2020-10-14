package com.michael.bci;

import com.rabbitmq.client.ConnectionFactory;

/* RabbitMQ connection detaile */
public class RabbitmqConnection {
    /* Method for connection details to RabbitMQ Broker */
    public static ConnectionFactory getConnectionFactory() {
        ConnectionFactory factory;
        factory = new ConnectionFactory();
        factory.setHost("63.33.208.71"); //IP of the RabbitMQ Message Broker
        factory.setUsername("user"); //RabbitMQ Username
        factory.setPassword("VIIu8eoVRYrH"); //RabbitMQ Password
        factory.setVirtualHost("/"); //RabbitMQ Virtual Host
        factory.setPort(5672); //RabbitMQ Message Broker Port
        return factory;
    }
}
