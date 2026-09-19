## AI Tool Usage
During both the initial design phase and final deployment setup, an LLM was used as a collaborative technical assistant to evaluate architectural trade-offs, refine configuration files, and troubleshoot containerization issues.

* **Prompting & Design:**
  * Evaluated transport layer options (raw TCP vs. WebSockets) and concurrency models suitable for a two-hour implementation without introducing heavy frameworks.
  * Brainstormed a two-stage mailbox data model (`ArrayBlockingQueue` for pending offline messages and a synchronized `LinkedHashMap` for in-flight messages) to satisfy bounded resource limits, at-least-once delivery semantics, and FIFO message ordering.
* **Packaging & Dependency Optimization:**
  * Identified the cause of `NoClassDefFoundError` (missing SLF4J/Jackson classes at runtime) and configured `maven-shade-plugin` to generate a self-contained Fat JAR.
  * Streamlined `pom.xml` by removing redundant transitive dependencies (`jackson-annotations`) and unnecessary build plugins (`docker-maven-plugin`) to keep the build process clean and minimal.
* **Containerization & Troubleshooting:**
  * Diagnosed Docker CLI execution issues with `exec-maven-plugin` and resolved container startup/port binding failures.
* **Verification:**
  * All AI suggestions were verified against exercise constraints, ensuring thread safety, minimal container footprint (~150MB), and deterministic message ordering. All final source code, unit tests, and manual Netcat verification steps were executed and validated locally.

## Architecture and Concurrency Model
The relay is built using raw Java TCP sockets to maintain full control over framing and state. The concurrency model relies on a Thread-per-Client architecture managed by a fixed-size `ExecutorService`. This isolates clients; if one client sends malformed data or hangs, it only crashes its own thread and does not block unrelated clients.

Global state is managed via a `ConcurrentHashMap` linking a unique Client ID to a `ClientSession`. Each session holds:
* An active socket reference (nullable if disconnected).
* A bounded `ArrayBlockingQueue` for pending offline messages.
* A synchronized `LinkedHashMap` for in-flight (unacknowledged) messages.

### Reconnection Dynamics
When a client registers with an existing `clientId`, the server re-binds the session to the new TCP socket connection and immediately flushes any unacknowledged or offline queued messages down the socket.

## Protocol and Delivery Semantics
The protocol uses line-delimited JSON over TCP. The server guarantees **at-least-once** delivery.
To satisfy the FIFO bonus requirement, the system uses a two-stage mailbox. When a message is sent down the TCP stream, it moves from the queue to the `LinkedHashMap`. It is only deleted when a specific `ACK` command is received. If a socket drops, the unacknowledged messages remain in the ordered map and are immediately flushed down the new socket upon re-registration, preserving their original sequence.

## Packaging and Deployment Architecture
* **Fat JAR Strategy:** Used `maven-shade-plugin` to bundle external libraries (Jackson, SLF4J/Log4j) into a single self-contained executable artifact. This eliminates runtime classpath configuration requirements in deployment environments.
* **Minimal Runtime Containerization:** Adopted a separation of concerns pattern where compilation occurs on the host system via Maven, while the container image (`eclipse-temurin:8-jre-alpine`) provides a slim, JRE-only execution environment. This minimizes container footprint (~150MB) and avoids unnecessary build utility overhead or in-container dependency downloads during image assembly.
* **Automated Deployment Profile:** Integrated a Maven profile (`-Pdocker`) using `exec-maven-plugin` to automate container stopping, Docker image building, and container execution during the `verify` lifecycle phase.

## Trade-offs and Limitations
* **Thread-per-Connection:** This is easy to reason about and cleanly manages bounded limits, but it does not scale to tens of thousands of concurrent connections (the C10k problem) compared to non-blocking I/O (Java NIO or Netty).
* **In-Memory State:** As permitted by the specification, all session state and queues are volatile. A server crash or restart will wipe undelivered messages, which could be mitigated in production by backing queues with an external data store (e.g., Redis or disk-backed persistence).