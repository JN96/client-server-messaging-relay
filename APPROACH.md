## Acceptance criteria
* Users can connect, register, and reconnect to their existing sessions over standard TCP.
* Online messages are delivered instantly, and the sender gets a confirmation receipt.
* Offline messages are safely stored in memory (up to 100 per person) and pushed out as soon as the user reconnects.
* Messages won't get lost in transit (at-least-once delivery) by holding onto them until the receiver explicitly replies with an `ACK`.
* The server handles bad data, full mailboxes, and out-of-order commands gracefully without crashing or dropping the connection.

## AI-tool usage
I used an LLM (Primarily Gemini, with some Claude Code) as a technical sounding board while building this project.
* **Design:** the pros and cons of raw TCP versus WebSockets were discussed; I chose TCP as it was lightweight, didn't require external libraries and I could handle the message framing. The AI also helped design the two-stage mailbox (a queue for offline messages and an ordered map for in-flight messages) to handle FIFO ordering and unacknowledged messages.
* **Build & Docker:** Gemini helped identifiy a suitable relatively low footprint Docker images with Java 8 and assisted in fixing a missing dependency error by setting up the `maven-shade-plugin` to build a JAR file which has all required dependencies bundled in. It also was able to assist with troubleshooting some Docker port-binding issues encountered when trying to download images.
* **Testing:** Every suggestion was manually verified against the exercise constraints, ensuring thread safety and a minimal Docker footprint. The AI tools, Claude Code especially were a big help especially in identifying edge cases scenarios and proposing fixes.

## Architecture, protocol, state, and concurrency models
The server is built on standard Java TCP sockets. To keep things simple and robust, every active connection gets its own dedicated thread from a fixed-size thread pool. If one user's connection lags or drops, it only affects their specific thread while everyone else on the server keeps chatting happily.

The user state is stored in a central `ConcurrentHashMap` that links a user's ID to their `ClientSession`. Each session holds their current socket connection, a bounded queue for offline messages, and a map of messages that are currently in-flight.

The protocol relies on line-delimited JSON.

### Concurrency Tool Justifications
Because multiple threads are reading and writing to the server's memory at the exact same time, specific tools were used to prevent data corruption:
* **Threads & Blocking I/O:** Reading from a network socket pauses (blocks) the thread until data arrives. A thread pool is used so one quiet user doesn't freeze the whole server.
* **ConcurrentHashMap:** This holds our main user directory. It allows multiple users to register at the exact same millisecond without the server crashing or overwriting data.
* **ArrayBlockingQueue:** Used for the offline mailbox. It naturally handles multiple threads trying to deliver messages at once and mathematically guarantees that the 100 message limit is never exceeded.
* **Synchronized LinkedHashMap:** Used for in-flight messages. The `LinkedHashMap` remembers the exact chronological order (FIFO) of messages. Using a synchronized block keeps it safe if the server adds a new message at the exact moment the user acknowledges an old one.
* **Synchronized Blocks:** The `synchronized` keyword is used to lock objects when trying to access them. For example, it can prevent two connections from trying to claim the same user ID at the exact same time, which would corrupt the connection state.

### Resource Limits & Error Reporting
* **Connection Limits:** The server allows up to 100 registered users at once and caps active threads at 50. If the server gets completely overwhelmed, it politely rejects new connections rather than hanging indefinitely.
* **Mailbox Limits:** Offline users can queue up to 100 messages. If someone tries to send them a 101st message, the server catches it and replies with an `ERROR` letting the sender know the recipient's mailbox is full.
* **Message Size:** Message sizes over 1024 bytes and log the incident. This saves memory without punishing the user by killing their connection.
* **Invalid Input:** If a user sends broken JSON, forgets a required field, or tries to send a message before registering, the server responds with a clear `ERROR` payload but keeps the socket open so they can try again.
* **Sender Verification:** You cannot impersonate someone else. The server always stamps outgoing messages with the actual verified ID the sender registered with, ignoring whatever `senderId` the client tried to put in the payload.

### Connection Lifecycle & Reconnection
* **Identifying & Connecting:** A client connects via TCP and must immediately identify themselves by sending a `REGISTER` JSON message containing their unique ID. The server then creates a new session or binds them to their existing one.
* **Disconnections:** If a TCP connection drops or is closed by the client, the server clears the active socket reference but preserves the user's `ClientSession` (and their offline mailbox) in the `ConcurrentHashMap`.
* **Reconnection:** When a user reconnects and sends a new `REGISTER` command, the server takes their new socket, attaches it to their existing preserved session, and immediately pushes any missed offline or unacknowledged messages down the pipe. I also added a quick safety check (compare-and-clear) to ensure that a lagging disconnect from an old connection won't accidentally wipe out a brand-new connection for the same user.

### Packaging and Deployment
* **JAR:** A JAR is built using Maven, meaning all external libraries (like Jackson for JSON parsing) are compiled into the file.
* **Docker:** A lightweight Docker image (`eclipse-temurin:8-jre-alpine`) with a JRE is used to keep the container relatively small (around 150MB) and fast to boot.

### Testing Strategy
We wrote the automated tests using JUnit 5 and Mockito.
* **Isolated Handlers:** Instead of writing slower integration tests, I mocked the TCP streams instead for unit tests. This allowed us to easily test things like max capacity rejections, full mailboxes, and bad JSON by just feeding strings directly into the handler and checking what it printed out.
* **Serialization and State:** Dedicated tests were added to ensure the JSON mapper correctly ignored null fields (to save bandwidth) and that the queue limits and FIFO ordering functioned exactly as expected.

## Delivery semantics
* **FIFO Delivery:** A two-stage system. Messages move from the pending queue to an "in-flight" map when they are sent down the TCP stream. They only get deleted when the receiver actually replies with an `ACK`. If the connection drops before the `ACK` arrives, those messages stay in exact order and are resent the next time the user connects.
* **Receipts:** When you send a message, the server explicitly replies with a `RECEIPT` telling you if it was delivered instantly or queued for later.
* **Duplicates & Stale ACKs:** Deduplication of messages does not happen on the server; if a user sends the exact same message twice, it is queued it twice. If the server receives an `ACK` for a message it doesn't recognize (like a delayed duplicate `ACK`), it just silently ignores it to prevent unnecessary errors.

## Trade-offs
* **Thread-per-connection:** Assigning a thread to every user is fine for this kind of application implementation, but it will not scale to tens of thousands of concurrent users.

## Known limitations
* **In-Memory Storage:** If the server restarts, all pending messages and registered sessions are lost.

## Next steps
* For a massive production app, the use of a non non-blocking I/O (like Netty) may be better suited.
* In a real environment, ideally state should be saved to a database such as Redis.
