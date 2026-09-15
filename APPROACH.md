## AI Tool Usage
During the initial planning phase, I used an LLM (Google 3.1 Pro - Advanced Reasoning) as a sounding board to discuss architectural trade-offs and clarify the requirements.
* **Prompting & Design:** I asked the AI to compare transport layers (like WebSockets vs. raw TCP) and brainstorm concurrency models that would fit within the two-hour limit without using heavy frameworks.
* **Suggestions:** I discussed using a Two-Collection approach (`ArrayBlockingQueue` for pending messages and a synchronized `LinkedHashMap` for in-flight messages) to satisfy both the bounded resource limits and the FIFO ordering bonus.
* **Verification:** I verified these suggestions against the exercise constraints, ensuring that the chosen data structures natively provided the necessary thread safety and explicitly supported the required at-least-once delivery semantics. All final code, implementation details, and test executions were driven and verified by me.

## Architecture and Concurrency Model
The relay is built using raw Java TCP sockets to maintain full control over framing and state. The concurrency model relies on a Thread-per-Client architecture managed by a fixed-size `ExecutorService`. This isolates clients; if one client sends malformed data or hangs, it only crashes its own thread and does not block unrelated clients.

Global state is managed via a `ConcurrentHashMap` linking a unique Client ID to a `ClientSession`. Each session holds:
* An active socket reference (nullable if disconnected).
* A bounded `ArrayBlockingQueue` for pending offline messages.
* A synchronized `LinkedHashMap` for in-flight (unacknowledged) messages.

## Protocol and Delivery Semantics
The protocol uses line-delimited JSON over TCP. The server guarantees **at-least-once** delivery.
To satisfy the FIFO bonus requirement, the system uses a two-stage mailbox. When a message is sent down the TCP stream, it moves from the queue to the `LinkedHashMap`. It is only deleted when a specific `ACK` command is received. If a socket drops, the unacknowledged messages remain in the ordered map and are immediately flushed down the new socket upon re-registration, preserving their original sequence.

## Trade-offs and Limitations
* **Thread-per-Connection:** This is easy to reason about and perfectly handles bounded limits, but it does not scale to millions of concurrent connections (the C10k problem) compared to non-blocking NIO.
* **In-Memory State:** As permitted, all state is volatile. A server restart wipes all
