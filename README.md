# client-server-messaging-relay

# Java TCP Relay Server

A multi-threaded TCP server built in Java to handle real-time message routing between clients. It supports:
 - persistent connections
 - offline message queuing
 - guaranteed delivery via an ACK mechanism.

---
## Architectural Decisions

This system was designed with thread safety, scalability, and memory efficiency in mind:

* **Concurrency:** The server uses a fixed thread pool (`ExecutorService`) to cap resource usage and prevent OutOfMemory errors during traffic spikes.
* **Thread-Safe State:** Client sessions and network sockets are tracked using a `ConcurrentHashMap` to allow lock-free, thread-safe reads and writes across multiple client threads.
* **Offline Queuing:** Undelivered messages are stored in a bounded `ArrayBlockingQueue`. If a recipient is offline, the server holds their messages in memory and flushes them down the socket the moment they reconnect.
* **Guaranteed Delivery:** The server maintains an `unacknowledgedMessages` map. If a socket drops after the server sends a message but before the client can send an `ACK`, the server will redeliver the message upon the next connection.
* **Wire Protocol:** Messages are serialized to newline-delimited JSON using Jackson. This ensures cross-platform compatibility, allowing any TCP-capable client (Java, Python, C++, or raw netcat) to interact with the server.

---
## Prerequisites

* Java 8 or higher
* Maven 3.6+

---
## Building and Running

You can run the server using either your IDE, command line or Docker. The default port is 8080.

### Option 1: Running via IDE
1. Open the project in IntelliJ IDEA or Eclipse. The IDE will automatically resolve Maven dependencies.
2. Navigate to the `RelayServer` class.
3. Click the "Run" (Play) button next to the `main` method.

### Option 2: Running via Command Line
If you prefer the terminal, you must compile the project first using Maven.
1. **Build the project:**
   ```
   mvn clean package
   ```

2. **Start the server**
   ```
   mvn exec:java -Dexec.mainClass="com.relay.network.RelayServer"
   ```

### Option 3: Running with Docker
You can run the server inside a lightweight Java 8 Alpine Linux container.
1. **Build the JAR (locally):**
   ```bash
   mvn clean package
   ```
2. Build the Docker image:
   ```bash
   docker build -t relay-server .
   ```
3. Start the container and map it to local port 8080:
   ```bash
   docker run -p 8080:8080 -it relay-server
   ```

#### Alternative: Single-Command Maven Deployment
Alternatively, you can package the JAR, build the image, and start the container
in one step using the Maven Docker profile:
   ```bash
   mvn clean verify -Pdocker
   ```

---
## Testing the Server from the Terminal (Netcat)
The Relay Server communicates using raw TCP sockets and plain text JSON payloads. You can fully test the system's routing, offline queuing, and error handling without a dedicated client application using `netcat` (`nc`).

### Testing Prerequisites
* The Java server must be currently running in your terminal or IDE (listening on port 8080).
* `netcat` (`nc`) must be installed on your system. It is included by default on macOS and most Linux distributions. Windows users can use `telnet` or run `nc` via Windows Subsystem for Linux (WSL).

---
### Test 1: Connection & Registration
Open two separate terminal windows.

**Terminal 1 (James):**
1. Connect to the server: `nc localhost 8080`
2. Register James by pasting this JSON and pressing Enter:
   ```json
   {"type":"REGISTER", "messageId":"1", "senderId":"James"}
   ```

**Terminal 2 (Ruth):**
1. Connect to the server: `nc localhost 8080`
2. Register Ruth by pasting this JSON and pressing Enter:
   ```json
   {"type":"REGISTER", "messageId":"2", "senderId":"Ruth"}
   ```
---
### Test 2: Real-Time Message Routing

In Terminal 1 (James), send a message to Ruth:
   ```json
   {"type":"SEND", "messageId":"3", "senderId":"James", "recipientId":"Ruth", "payload":"Hi, Ruth!"}
   ```
Expected Result: Terminal 2 (Ruth) should instantly display the DELIVER message.

---
### Test 3: Offline Queuing & Reconnection (Flush)
1. In Terminal 2 (Ruth), use `Ctrl+C` to terminate the connection.
2. In Terminal 1 (James), send two consecutive messages to Ruth (now offline):
   ```json
   {"type":"SEND", "messageId":"4", "senderId":"James", "recipientId":"Ruth", "payload":"Are you offline?"}
   ```
   ```json
   {"type":"SEND", "messageId":"5", "senderId":"James", "recipientId":"Ruth", "payload":"Second queued message"}
   ```
Expected Result: The server accepts both messages without error and queues them in order.
3. Bring Ruth back online in Terminal 2:
```
nc localhost 8080
```
4. Re-register Ruth:
   ```json
   {"type":"REGISTER", "messageId":"6", "senderId":"Ruth"}
   ```
Expected Result: The server immediately flushes the offline queue. 
Ruth will receive the messages in FIFO order: first message 4, 
followed immediately by message 5 (along with a redelivery of message 3, since it was never acknowledged).

---
### Test 4: Message Acknowledgements (ACK)
To prevent the server from redelivering messages every time a client reconnects, 
the client must acknowledge receipt.
1. In Terminal 2 (Ruth), acknowledge the three messages:
   ```json
   {"type":"ACK", "messageId":"3", "senderId":"Ruth"}
   ```
   ```json
   {"type":"ACK", "messageId":"4", "senderId":"Ruth"}
   ```
   ```json
   {"type":"ACK", "messageId":"5", "senderId":"Ruth"}
   ```
2. Use `Ctrl+C` to kill Ruth's terminal.
3. Run nc localhost 8080, and register her again.
   ```json
   {"type":"REGISTER", "messageId":"5", "senderId":"Ruth"}
   ```
Expected Result: Ruth receives no messages upon reconnecting. The server has permanently cleared them from her mailbox.

---
### Test 5: Error Handling (Unknown Recipient)
In Terminal 1 (James), try messaging a user who has never registered:
   ```json
   {"type":"SEND", "messageId":"8", "senderId":"James", "recipientId":"Donal", "payload":"Does Donal exist?"}
   ```

## Automated Testing
The project includes a suite of automated unit tests built with JUnit 5 and Mockito to verify core state management, mailbox boundaries, and protocol serialization.

To execute the test suite via the command line:
   ```bash
   mvn clean test
   ```
Once the tests finish, open the newly generated file `target/site/jacoco/index.html` in your browser
to see a breakdown of the test coverage.