# BCI_MQ_ACTIVITY_v1.0.0
A mobile BCI Android App for real-time study of continuous EEG brain signals and activity recognition

IDE
- I used Android Studio 4.0.1 as IDE but IntelliJ should also work fine since one is based on the other

The main files to look at are
- AndroidManifest.xml (Configs)
- MainActivity (Starting point and control of the app)
- BciService (This is where the real work is to get the EEG signals and send them to the Cloud Message Broker)
- OpenBci (Worker class that convert incoming EEG and accelerometer data into standard 32-bit signed integers)
- ActivityRecognizedService (Works but very much still in progress - gets the Phone Activity Recognition data and send it to the Cloud Message Broker) 

Libs
- d2xx.jar (FTDI Driver to communicate with the OpenBCI Dongle)
- json-simple-1.1.1.jar (simple Java toolkit to encode or decode JSON text)
- amqp-client-5.8.0.jar (RabbitMQ Java client)
- slf4j-api-1.7.26.jar and slf4j-simple-1.7.26.jar (Simple Logging Facade for Java)
