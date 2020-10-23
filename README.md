# BCI_MQ_ACTIVITY_v1.0.0
A mobile BCI Android App for real-time study of continuous EEG brain signals and activity recognition.  

Android 
The minSdkVersion for this App is set to level 26 (Android Version 8.0.0 - Oreo) and the targetSdkVersion level 29. 

IDE
- I used Android Studio 4.1 as IDE but IntelliJ should also work fine since one is based on the other

The main files are
- AndroidManifest.xml (Configs)
- MainActivity (Starting point and control of the app)
- BciService (This class configures and manages the connection to the OpenBCI Cytron Board)
- BciSender (This class contains sender thread to send commands to the OpenBCI Cyton Board)
- BciReceiver (This class groups methods for receiving data from the OpenBCI Board)
- OpenBci (Worker class that convert incoming EEG and accelerometer data into standard 32-bit signed integers)
- ActivityRecognizedService (Gets the Phone Activity Recognition data and send it to the Cloud Message Broker) 
- RabbitmqConnection: (Manages the connection details to the AWS RabbitMQ Broker)

Libs
- d2xx.jar (FTDI Driver to communicate with the OpenBCI Dongle)
- json-simple-1.1.1.jar (simple Java toolkit to encode or decode JSON text)
- amqp-client-5.8.0.jar (RabbitMQ Java client)
- slf4j-api-1.7.26.jar and slf4j-simple-1.7.26.jar (Simple Logging Facade for Java)

RabbitMQ_Receiver_JSON Folder
- You will need to download this folder to your local system and build the receiver files with your RabbitMQ connection details - see commands.txt for details

HowTo
- HowTo Step-by-Step doc - very much in progress at the moment.

Main ToDo
- Activity Recognition needs some refinement
- FTDI Buffer RX/TX working but needs more analysis to run much more efficiently  
