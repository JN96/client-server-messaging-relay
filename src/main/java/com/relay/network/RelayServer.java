package com.relay.network;

import com.relay.config.Constants;
import com.relay.state.ClientSession;

import java.io.IOException;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.Set;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Core server for message relay system which is responsible for listening for incoming TCP connections,
 * managing the HashMap of registered clients.
 */
public class RelayServer {

    private static final Logger logger = LoggerFactory.getLogger(RelayServer.class.getName());

    private final ConcurrentHashMap<String, ClientSession> registeredClients;
    private final Set<Socket> activeSockets;
    private final ExecutorService threadPool;
    private volatile ServerSocket serverSocket;
    private volatile boolean shuttingDown;

    public RelayServer() {
        this.registeredClients = new ConcurrentHashMap<>();
        this.activeSockets = ConcurrentHashMap.newKeySet();
        this.threadPool = new ThreadPoolExecutor(
                Constants.MAX_THREADS,
                Constants.MAX_THREADS,
                0L, TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(Constants.MAX_PENDING_CONNECTIONS));
    }

    /**
     * Starts the server loop to accept TCP connections.
     */
    public void start() {
        // shutdown hook to ensure the server shuts down predictably
        Runtime.getRuntime().addShutdownHook(new Thread(this::shutdown));

        try {
            this.serverSocket = new ServerSocket(Constants.SERVER_PORT);
            logger.info("RelayServer started on port {}", this.serverSocket.getLocalPort());

            while (!Thread.currentThread().isInterrupted()) {
                // block until a new client connects
                Socket clientSocket = this.serverSocket.accept();
                logger.info("RelayServer accepted connection from {}", clientSocket.getRemoteSocketAddress());

                this.activeSockets.add(clientSocket);
                try {
                    // hand off the connection to a dedicated thread so it doesn't block others
                    this.threadPool.submit(new ClientHandler(clientSocket, this.registeredClients, this.activeSockets));
                } catch (final RejectedExecutionException exception) {
                    this.activeSockets.remove(clientSocket);
                    logger.warn("Connection from {} rejected: server at capacity", clientSocket.getRemoteSocketAddress());
                    closeQuietly(clientSocket);
                }
            }
        } catch (final IOException exception) {
            if (this.shuttingDown) {
                logger.info("RelayServer stopped accepting connections.");
            } else {
                logger.error("RelayServer start failed on port {}", Constants.SERVER_PORT, exception);
            }
        }
    }

    /**
     * Gracefully shuts down the server: stops accepting new connections, closes every currently
     * connected client socket (which unblocks their handler threads' blocking reads), then waits
     * a bounded amount of time for the thread pool to drain before forcing a shutdown.
     */
    private void shutdown() {
        logger.info("RelayServer shutting down...");
        this.shuttingDown = true;

        if (this.serverSocket != null) {
            closeQuietly(this.serverSocket);
        }

        for (final Socket socket : this.activeSockets) {
            closeQuietly(socket);
        }

        this.threadPool.shutdown();
        try {
            if (!this.threadPool.awaitTermination(5, TimeUnit.SECONDS)) {
                logger.warn("Thread pool did not drain within the timeout, forcing shutdown");
                this.threadPool.shutdownNow();
            }
        } catch (final InterruptedException exception) {
            this.threadPool.shutdownNow();
            Thread.currentThread().interrupt();
        }
        logger.info("RelayServer finished shutting down.");
    }

    /**
     * Safely closes a resource while logging any caught exceptions.
     * @param closeable the resource to close.
     */
    private static void closeQuietly(final AutoCloseable closeable) {
        try {
            closeable.close();
        } catch (final Exception exception) {
            logger.warn("Error closing socket during shutdown", exception);
        }
    }

    public static void main(final String[] args) {
        new RelayServer().start();
    }
}
