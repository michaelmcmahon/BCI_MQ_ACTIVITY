package com.michael.bci;

import com.rabbitmq.client.ConnectionFactory;

/* RabbitMQ connection detaile */
public class RabbitmqConnection {
    /* Method for connection details to RabbitMQ Broker */
    public static ConnectionFactory getConnectionFactory() {
        ConnectionFactory factory;
        factory = new ConnectionFactory();
        factory.setHost("IP Address"); //IP of the RabbitMQ Message Broker
        factory.setUsername("user name"); //RabbitMQ Username
        factory.setPassword("Password"); //RabbitMQ Password
        factory.setVirtualHost("/"); //RabbitMQ Virtual Host
        factory.setPort(5672); //RabbitMQ Message Broker Port
        return factory;
    }
}
