## Acceptance Criteria
* Clients can connect, register, and seamlessly reconnect to the same logical session using TCP.
* Real-time online messages are delivered immediately and confirmed to the sender.
* Offline messages are queued safely in memory (up to 100 messages) and flushed upon reconnection.
* The server guarantees at-least-once delivery, retaining in-flight messages until an explicit `ACK` is received.
* Invalid input, full mailboxes, and unregistered socket commands are handled gracefully without crashing the server.

## AI Tool Usage
During both the initial design phase and final deployment setup, an LLM was used as a collaborative technical assistant to evaluate architectural trade-offs, refine configuration files, and troubleshoot containerization issues.

* **Prompting & Design:**
  * Evaluated transport layer options (raw TCP vs. WebSockets) and concurrency models suitable for a two-hour implementation without introducing heavy frameworks.
  * Brainstormed a two-stage mailbox data model (`ArrayBlockingQueue` for pending offline messages and a synchronized `LinkedHashMap` for in-flight messages) to satisfy bounded resource limits, at-least-once delivery semantics, and FIFO message ordering.
* **Packaging & Dependency Optimization:**
  * Identified the cause of `NoClassDefFoundError` (missing SLF4J/Jackson classes at runtime) and configured `maven-shade-plugin` to generate a self-contained JAR.
  * Streamlined `pom.xml` by removing redundant transitive dependencies (`jackson-annotations`) and unnecessary build plugins (`docker-maven-plugin`) to keep the build process clean and minimal.
* **Containerization & Troubleshooting:**
  * Diagnosed Docker CLI execution issues with `exec-maven-plugin` and resolved container startup/port binding failures.
* **Verification:**
  * All AI suggestions were verified against exercise constraints, ensuring thread safety, minimal container footprint (~150MB), and deterministic message ordering. All final source code, unit tests, and manual Netcat verification steps were executed and validated locally.

## Architecture and Concurrency Model
The relay is built using raw Java TCP sockets to maintain full control over framing and state. The concurrency model relies on a thread per connection architecture managed by a fixed-size `ExecutorService`. This isolates clients; if one client hangs or experiences a severe I/O disruption, it only drops its own thread and does not block unrelated clients. Application-level errors (such as malformed JSON payloads) are intercepted gracefully without dropping the TCP connection.

Global state is managed via a `ConcurrentHashMap` linking a unique Client ID to a `ClientSession`. Each session holds:
* An active socket reference (nullable if disconnected).
* A bounded `ArrayBlockingQueue` for pending offline messages.
* A synchronized `LinkedHashMap` for in-flight (unacknowledged) messages.

### Resource Limits & Error Reporting
* **Mailbox Limits:** Bounded to 100 messages per offline client. If a sender exceeds this, the server explicitly rejects the operation by returning an `ERROR` JSON payload to the sender.
* **Message Size:** TCP payloads exceeding 1024 bytes are silently dropped and logged by the server to prevent buffer overflow attacks without allocating additional stream resources to reply.
* **Invalid Input:** Malformed JSON or missing fields result in an `ERROR` response being sent back down the TCP stream without severing the connection. A `SEND` from a socket that has not yet registered is explicitly rejected with an `ERROR` ("Must register first") rather than silently processed.
* **Connection Capacity:** Concurrent connections are bounded by a fixed-size thread pool (`MAX_THREADS`) backed by a bounded backlog queue (`MAX_PENDING_CONNECTIONS`). Once both are full, new TCP connections are accepted and then closed immediately rather than left to hang indefinitely waiting for a free handler thread. Total distinct registered identities are separately bounded by `MAX_REGISTERED_USERS`.
* **Sender Identity:** The `senderId` on a delivered message and on receipts/errors is always the server-verified identity bound to that socket (the id it registered with), never the client-supplied `senderId` field on the `SEND` payload. This prevents one client from impersonating another as the sender of a relayed message.
* **Unexpected Errors:** Any unforeseen exception while handling a single message is caught, logged, and turned into an `ERROR` reply instead of terminating the connection, so one malformed message cannot silently kill an otherwise healthy session.

### Reconnection Dynamics
When a client registers with an existing `clientId`, the server re-binds the session to the new TCP socket connection and immediately flushes any unacknowledged or offline queued messages down the socket. Disconnect cleanup only clears a session's active connection if it still matches the connection that handler was responsible for (a compare-and-clear), so a delayed cleanup from an old, already-superseded socket cannot clobber a newer connection that reconnected in the meantime.

## Protocol and Delivery Semantics
The protocol uses line-delimited JSON over TCP. The server guarantees **at-least-once** delivery.

* **Delivery and Acknowledgement (FIFO):** To satisfy the FIFO bonus requirement, the system uses a two-stage mailbox. When a message is transmitted down the TCP stream, it moves from the bounded pending queue to a synchronized `LinkedHashMap`. It is only deleted when an explicit `ACK` command is received from the recipient. If a socket drops, the unacknowledged messages remain in the ordered map and are immediately flushed down the new socket upon re-registration, preserving their original chronological sequence.
* **Sender Confirmation:** When a client issues a `SEND` command, the server explicitly responds to the sender with a `RECEIPT` payload confirming if the message was immediately delivered to an online recipient or safely queued for an offline recipient.
* **Duplicate Sends:** The server treats every incoming `SEND` command as a unique operation. If a client transmits the exact same payload multiple times, the server will queue and deliver them as distinct messages. Deduplication is delegated to the client application.
* **Stale Acknowledgements:** If the server receives an `ACK` for a `messageId` that does not exist in the unacknowledged map (e.g., because it was already acknowledged or the ID was invalid), the server silently ignores it to gracefully handle network delays and duplicate client ACKs.

## Packaging and Deployment Architecture
* **JAR Strategy:** Used `maven-shade-plugin` to bundle external libraries (Jackson, SLF4J/Log4j) into a single self-contained executable artifact. This eliminates runtime classpath configuration requirements in deployment environments.
* **Minimal Runtime Containerization:** Adopted a separation of concerns pattern where compilation occurs on the host system via Maven, while the container image (`eclipse-temurin:8-jre-alpine`) provides a slim, JRE-only execution environment. This minimizes container footprint (~150MB) and avoids unnecessary build utility overhead or in-container dependency downloads during image assembly.
* **Automated Deployment Profile:** Integrated a Maven profile (`-Pdocker`) using `exec-maven-plugin` to automate container stopping, Docker image building, and container execution during the `verify` lifecycle phase.

## Trade-offs and Limitations
* **Thread per connection:** This is easy to reason about and cleanly manages bounded limits, but it does not scale to tens of thousands of concurrent connections (the C10k problem) compared to non-blocking I/O (Java NIO or Netty).
* **In-Memory State:** As permitted by the specification, all session state and queues are volatile. A server crash or restart will wipe undelivered messages, which could be mitigated in production by backing queues with an external data store (e.g., Redis or disk-backed persistence).
* **Shutdown:** On shutdown, the server stops accepting new connections, force-closes every currently tracked client socket (which unblocks each handler thread's blocking read so it exits its loop and cleans up its session), and then waits up to 5 seconds for the thread pool to drain before forcing a shutdown. A client that has gone completely unresponsive at the OS/network level (e.g. a dead peer with no RST/FIN) may still take the OS's own TCP timeout to fully release, but the server process itself is not blocked waiting on it beyond the 5-second grace period.

## Testing Strategy
The automated test suite utilizes **JUnit 5** and **Mockito** to validate core system logic.

* **State and Boundary Constraints:** Unit tests verify the `ClientSession` data structures directly, ensuring the offline mailbox strictly enforces its 100-message capacity limit and that the unacknowledged map preserves exact FIFO insertion order during redelivery cycles.
* **Protocol Serialization:** Dedicated tests validate that the Jackson `ObjectMapper` accurately parses the specific `MessageType` enums, completely ignores null properties to save bandwidth via `@JsonInclude(NON_NULL)`, and gracefully handles malformed JSON payloads without terminating the thread.
* **Isolated Handler Logic:** Instead of executing full end-to-end integration tests, Mockito is used to mock the TCP `Socket`, `InputStream`, and `OutputStream` instances. This allows the test suite to inject simulated string-based JSON streams directly into the `ClientHandler` loop. This approach rapidly verifies complex state transitions, such as rejecting operations from unregistered sockets, queueing messages for offline users, and clearing acknowledged messages by capturing and asserting against the handler's raw `PrintWriter` output.
* **Test Boundaries:** The tests intentionally isolate the core routing logic, state boundaries, and serialization mechanisms. They **do not cover** raw socket network integration (e.g., spinning up a real `ServerSocket` and connecting via `java.net.Socket` in the test phase) or heavy concurrency load testing. These boundaries were chosen to keep the test suite fast, deterministic, and free of the port-binding conflicts that often make CI environments flaky, fulfilling the exercise constraints within the suggested time box.