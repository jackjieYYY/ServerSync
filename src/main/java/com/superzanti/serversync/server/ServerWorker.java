package com.superzanti.serversync.server;

import com.superzanti.serversync.ServerSync;
import com.superzanti.serversync.util.Logger;
import com.superzanti.serversync.util.LoggerNG;
import com.superzanti.serversync.util.enums.EBinaryAnswer;
import com.superzanti.serversync.util.enums.EServerMessage;
import com.superzanti.serversync.util.errors.UnknownMessageError;

import java.io.*;
import java.net.Socket;
import java.net.SocketException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.text.DateFormat;
import java.util.*;

/**
 * This worker handles requests from the client continuously until told to exit
 * using SECURE_EXIT These workers are assigned per socket connection i.e. one
 * per client
 *
 * @author superzanti
 */
public class ServerWorker implements Runnable {
    private static final int DEFAULT_CLIENT_TIMEOUT_MS = 120000; // 2 minutes
    private static final int FILE_SYNC_CLIENT_TIMEOUT_MS = 600000; // 10 minutes

    private Socket clientSocket;
    private ObjectInputStream ois;
    private ObjectOutputStream oos;

    private EnumMap<EServerMessage, String> messages;
    private List<String> directories;
    private Map<String, String> files;

    private Timer timeout;
    private TimerTask timeoutTask;
    
    private LoggerNG clientLogger;

    ServerWorker(
        Socket socket,
        EnumMap<EServerMessage, String> comsMessages,
        Timer timeoutScheduler,
        List<String> managedDirectories,
        Map<String, String> serverFiles
    ) {
        clientLogger = new LoggerNG(String.format("server-connection-from-%s", socket.getInetAddress().toString().replaceAll("[/\\.:@?|\\*\"]", "-")));
        clientSocket = socket;
        messages = comsMessages;
        directories = managedDirectories;
        files = serverFiles;
        timeout = timeoutScheduler;
        Date clientConnectionStarted = new Date();
        DateFormat dateFormatter = DateFormat.getDateTimeInstance();

        clientLogger.log("Connection established with " + clientSocket + dateFormatter.format(clientConnectionStarted));
    }

    @Override
    public void run() {
        try {
            ois = new ObjectInputStream(clientSocket.getInputStream());
            oos = new ObjectOutputStream(clientSocket.getOutputStream());
        } catch (IOException e) {
            clientLogger.log("Failed to create client streams");
            e.printStackTrace();
        }

        while (!clientSocket.isClosed()) {
            String message = null;
            try {
                setTimeout(ServerWorker.DEFAULT_CLIENT_TIMEOUT_MS);
                message = (String) ois.readObject();
                clientLogger.log(
                    String.format("Received message: %s, from client: %s", message, clientSocket.getInetAddress()));
            } catch (SocketException e) {
                // Client timed out
                break;
            } catch (ClassNotFoundException | IOException e) {
                clientLogger.debug(e);
            }

            if (message == null) {
                clientLogger.debug("Received null message, this should not happen.");
                continue;
            }

            try {
                // <---->
                // always called first
                if (message.equals(ServerSync.HANDSHAKE)) {
                    clientLogger.log("Sending coms messages");
                    oos.writeObject(messages);
                    oos.flush();
                    continue;
                }

                // <---->
                // fallback if I don't know what this message is
                if (!messages.containsValue(message)) {
                    try {
                        clientLogger.log("Unknown message received from: " + clientSocket.getInetAddress());
                        oos.writeObject(new UnknownMessageError(message));
                        oos.flush();
                    } catch (IOException e) {
                        clientLogger.log("Failed to write error to client " + clientSocket);
                        clientLogger.debug(e);
                    }

                    // There should not be unknown messages being sent to ServerSync, disconnect from the client.
                    break;
                }

                // <---->
                // the actual file sync
                if (matchMessage(message, EServerMessage.SYNC_FILES)) {
                    // Server: Do you have this file?
                    // - String: path
                    // - String: hash
                    // Client: yes | no
                    // -- (yes) - skip to next file
                    // -- (no) - send filesize -> send file
                    if (files.size() > 0) {
                        for (Map.Entry<String, String> entry :  files.entrySet()) {
                            try {
                                clientLogger.debug(String.format("Asking client if the have file: %s", entry.getKey()));
                                oos.writeBoolean(true); // There are files left
                                oos.writeUTF(entry.getKey()); // The path
                                oos.writeUTF(entry.getValue()); // The hash
                                oos.flush();


                                // Client: Nope, don't have it joe!
                                if (EBinaryAnswer.NO.getValue() == ois.readInt()) {
                                    clientLogger.debug("Client said they don't have the file");
                                    setTimeout(ServerWorker.FILE_SYNC_CLIENT_TIMEOUT_MS);
                                    transferFile(entry.getKey());
                                } else {
                                    clientLogger.debug("Client said they have the file already");
                                    setTimeout(ServerWorker.DEFAULT_CLIENT_TIMEOUT_MS);
                                }
                            } catch (IOException ex) {
                                clientLogger.debug(ex);
                                clientLogger
                                    .log(String.format("Encountered error during sync with %s, killing sync process", clientSocket.getInetAddress()));
                                break;
                            }
                        }

                        clientLogger.debug("Finished sync");
                        oos.writeBoolean(false); // No files left
                    } else {
                        clientLogger.debug("No files on the server?");
                        oos.writeBoolean(false); // No files at all?
                    }
                    oos.flush();
                    continue;
                }

                // <---->
                // the directories that I am managing / sync'ing
                // needed by the client to know what it should delete
                if (matchMessage(message, EServerMessage.GET_MANAGED_DIRECTORIES)) {
                    oos.writeObject(directories);
                    oos.flush();
                    continue;
                }

                // <---->
                // how many files are managed by the server?
                if (matchMessage(message, EServerMessage.GET_NUMBER_OF_MANAGED_FILES)) {
                    oos.writeInt(files.size());
                    oos.flush();
                    continue;
                }
            } catch (SocketException e) {
                clientLogger.log("Client " + clientSocket + " closed by timeout");
                break;
            } catch (IOException e) {
                clientLogger.log("Failed to write to " + clientSocket + " client stream");
                e.printStackTrace();
                break;
            }

            // <---->
            if (matchMessage(message, EServerMessage.EXIT)) {
                clientLogger.log(String.format(
                    "Client requested exit, sync process complete for: %s",
                    clientSocket.getInetAddress()
                ));
                break;
            }
        }

        clientLogger.log("Closing connection with: " + clientSocket);
        teardown();
    }

    private void transferFile(String path) throws IOException {
        Path file = Paths.get(path);

        // Not checking if the file exists as this is coming from a list of
        // files that we already know exist.

        clientLogger.log("Writing " + file.toString() + " to client " + clientSocket.getInetAddress() + "...");

        // -- Size, for client GUI progress tracking
        long size = 0L;
        try {
            size = Files.size(file);
        } catch (IOException e) {
            clientLogger.debug(e);
            String error = String.format(ServerSync.strings.getString("server_message_file_missing"), file);
            clientLogger.error(error);
            Logger.error(error);
        } catch (SecurityException se) {
            clientLogger.debug(se);
            clientLogger.error(String.format(ServerSync.strings.getString("server_message_file_permission_denied"), file));
        }
        clientLogger.debug(String.format("File size is: %d", size));
        oos.writeLong(size);
        oos.flush();
        // --

        if(size > 0) {
            int bytesRead;
            byte[] buffer = new byte[clientSocket.getSendBufferSize()];

            try (BufferedInputStream fis = new BufferedInputStream(Files.newInputStream(file))) {
                while ((bytesRead = fis.read(buffer)) > 0) {
                    oos.write(buffer, 0, bytesRead);
                }
            } catch(IOException e) {
                clientLogger.debug(String.format("Failed to write file: %s", file));
                clientLogger.debug(e);
            } finally {
                oos.flush();
            }
        }

        clientLogger.log(String.format(
            "Finished writing: %s, to client: %s",
            file.toString(),
            clientSocket.getInetAddress()
        ));
    }

    private boolean matchMessage(String incomingMessage, EServerMessage message) {
        return incomingMessage.equals(messages.get(message));
    }

    private void clearTimeout() {
        if (timeoutTask != null) {
            timeoutTask.cancel();
            timeout.purge();
        }
    }

    private void setTimeout(int durationMs) {
        clearTimeout();
        timeoutTask = new ServerTimeout(this);
        timeout.schedule(timeoutTask, durationMs);
        clientLogger.debug(String.format(
            "Reset timeout for client: %s, with a timeout of: %s",
            clientSocket.getInetAddress(),
            durationMs
        ));
    }

    private void teardown() {
        try {
            clearTimeout();
            timeout = null;

            if (!clientSocket.isClosed()) {
                clientSocket.close();
            }
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    public void timeoutShutdown() {
        try {
            clientLogger.log("Client connection timed out, closing " + clientSocket);

            if (!clientSocket.isClosed()) {
                clientSocket.close();
            }
        } catch (IOException e) {
            e.printStackTrace();
        }
    }
}
