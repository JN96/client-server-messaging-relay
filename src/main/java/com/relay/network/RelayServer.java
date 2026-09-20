package com.relay.network;

import com.relay.config.Constants;
import com.relay.state.ClientSession;

import java.io.IOException;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Core server for message relay system which is responsible for listening for incoming TCP connections,
 * managing the HashMap of registered clients.
 */
public class RelayServer {

    private static final Logger logger = LoggerFactory.getLogger(RelayServer.class.getName());

    private final ConcurrentHashMap<String, ClientSession> registeredClients;
    private final ExecutorService threadPool;

    public RelayServer() {
        this.registeredClients = new ConcurrentHashMap<>();
        this.threadPool = Executors.newFixedThreadPool(Constants.MAX_THREADS);
    }

    /**
     * Starts the server loop to accept TCP connections.
     */
    public void start() {
        // shutdown hook to ensure the server shuts down predictably
        Runtime.getRuntime().addShutdownHook(new Thread(this::shutdown));


        try (ServerSocket serverSocket = new ServerSocket(Constants.SERVER_PORT)) {
            logger.info("RelayServer started on port {}", serverSocket.getLocalPort());

            while (!Thread.currentThread().isInterrupted()) {
                // block until a new client connects
                Socket clientSocket = serverSocket.accept();
                logger.info("RelayServer accepted connection from {}", clientSocket.getRemoteSocketAddress());

                // hand off the connection to a dedicated thread so it doesn't block others
                this.threadPool.submit(new ClientHandler(clientSocket, this.registeredClients));
            }
        } catch (final IOException exception) {
            logger.error("RelayServer start failed on port {}", Constants.SERVER_PORT, exception);
        }
    }

    /**
     * Gracefully shuts down the server while halting the thread pool, preventing new connections and
     * interrupted active connections.
     */
    private void shutdown() {
        logger.info("RelayServer shutting down...");
        this.threadPool.shutdown();
        logger.info("RelayServer finished shutting down.");
    }

    public static void main(final String[] args) {
        new RelayServer().start();
    }
}
