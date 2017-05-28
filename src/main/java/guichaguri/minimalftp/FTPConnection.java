package guichaguri.minimalftp;

import guichaguri.minimalftp.api.IFileSystem;
import guichaguri.minimalftp.api.ResponseException;
import guichaguri.minimalftp.api.CommandInfo;
import guichaguri.minimalftp.api.CommandInfo.Command;
import guichaguri.minimalftp.api.CommandInfo.NoArgsCommand;
import guichaguri.minimalftp.api.CommandInfo.SingleArgCommand;
import guichaguri.minimalftp.handler.ConnectionHandler;
import guichaguri.minimalftp.handler.FileHandler;
import java.io.*;
import java.net.InetAddress;
import java.net.Socket;
import java.util.HashMap;
import java.util.Map;

/**
 * Represents a FTP user connected to the server
 * @author Guilherme Chaguri
 */
public class FTPConnection implements Closeable {

    protected final Map<String, CommandInfo> commands = new HashMap<>();
    protected final Map<String, CommandInfo> siteCommands = new HashMap<>();

    protected final FTPServer server;
    protected final Socket con;
    protected final BufferedReader reader;
    protected final BufferedWriter writer;
    protected final ConnectionThread thread;

    protected ConnectionHandler conHandler;
    protected FileHandler fileHandler;

    protected long bytesTransferred = 0;
    protected boolean responseSent = true;

    /**
     * Creates a new FTP connection.
     * Usually initialized by a {@link FTPServer}
     *
     * @param server The server which received the connection
     * @param con The connection socket
     * @throws IOException When an I/O error occurs
     */
    public FTPConnection(FTPServer server, Socket con) throws IOException {
        this.server = server;
        this.con = con;
        this.reader = new BufferedReader(new InputStreamReader(con.getInputStream()));
        this.writer = new BufferedWriter(new OutputStreamWriter(con.getOutputStream()));

        this.conHandler = new ConnectionHandler(this);
        this.fileHandler = new FileHandler(this);

        this.thread = new ConnectionThread();
        this.thread.start();

        registerCommand("SITE", "SITE <command>", this::site);

        this.conHandler.registerCommands();
        this.fileHandler.registerCommands();
        this.conHandler.onConnected();
    }

    /**
     * The server which the connection belongs to
     * @return The {@link FTPServer} that received this connection
     */
    public FTPServer getServer() {
        return server;
    }

    /**
     * Gets the connection address
     * @return The {@link InetAddress} of this connection
     */
    public InetAddress getAddress() {
        return con.getInetAddress();
    }

    /**
     * Gets the amount of bytes sent or received
     * @return The number of bytes
     */
    public long getBytesTransferred() {
        return bytesTransferred;
    }

    /**
     * Whether the connection is authenticated
     * @return Whether the connection is authenticated
     */
    public boolean isAuthenticated() {
        return conHandler.isAuthenticated();
    }

    /**
     * Gets the username of the connection.
     * @return The username or {@code null}
     */
    public String getUsername() {
        return conHandler.getUsername();
    }

    /**
     * The file system of the connection. May be {@code null} when it's still authenticating
     * @return The current file system
     */
    public IFileSystem getFileSystem() {
        return fileHandler.getFileSystem();
    }

    /**
     * Sets the new file system for this connection.
     * Calling this method can result into desynchronization for the connection.
     * Please, if you want to change the file system, use a {@link guichaguri.minimalftp.api.IUserAuthenticator}
     *
     * @param fs The new file system
     */
    public void setFileSystem(IFileSystem fs) {
        fileHandler.setFileSystem(fs);
    }

    /**
     * Sends a response to the connection
     * @param code The response code
     * @param response The response message
     */
    public void sendResponse(int code, String response) {
        if(con.isClosed()) return;

        try {
            writer.write(code + " " + response + "\r\n");
            writer.flush();
        } catch(IOException ex) {
            Utils.closeQuietly(this);
        }
        responseSent = true;
    }

    /**
     * Sends an array of bytes through a data connection
     * @param data The data to be sent
     * @throws ResponseException When an error occurs
     */
    public void sendData(byte[] data) throws ResponseException {
        if(con.isClosed()) return;

        try {
            Socket socket = conHandler.createDataSocket();
            OutputStream out = socket.getOutputStream();

            Utils.write(out, data, data.length, conHandler.isAsciiMode());
            bytesTransferred += data.length;

            out.flush();
            Utils.closeQuietly(out);
            Utils.closeQuietly(socket);
        } catch(IOException ex) {
            throw new ResponseException(425, "An error occurred while opening the data connection");
        }
    }

    /**
     * Sends a stream through a data connection
     * @param in The input stream
     * @throws ResponseException When an error occurs
     */
    public void sendData(InputStream in) throws ResponseException {
        if(con.isClosed()) return;

        try {
            Socket socket = conHandler.createDataSocket();
            OutputStream out = socket.getOutputStream();

            byte[] buffer = new byte[1024];
            int len;
            while((len = in.read(buffer)) != -1) {
                Utils.write(out, buffer, len, conHandler.isAsciiMode());
                bytesTransferred += len;
            }

            out.flush();
            Utils.closeQuietly(out);
            Utils.closeQuietly(in);
            Utils.closeQuietly(socket);
        } catch(IOException ex) {
            throw new ResponseException(425, "An error occurred while opening the data connection");
        }
    }

    /**
     * Receives a stream through the data connection
     * @param out The output stream
     * @throws ResponseException When an error occurs
     */
    public void receiveData(OutputStream out) throws ResponseException {
        if(con.isClosed()) return;

        try {
            Socket socket = conHandler.createDataSocket();
            InputStream in = socket.getInputStream();

            byte[] buffer = new byte[1024];
            int len;
            while((len = in.read(buffer)) != -1) {
                out.write(buffer, 0, len);
                bytesTransferred += len;
            }

            out.flush();
            Utils.closeQuietly(out);
            Utils.closeQuietly(in);
            Utils.closeQuietly(socket);
        } catch(IOException ex) {
            throw new ResponseException(425, "An error occurred while opening the data connection");
        }
    }

    public void registerSiteCommand(String label, String help, Command cmd) {
        addSiteCommand(label, help, cmd);
    }

    public void registerSiteCommand(String label, String help, NoArgsCommand cmd) {
        addSiteCommand(label, help, cmd);
    }

    public void registerSiteCommand(String label, String help, SingleArgCommand cmd) {
        addSiteCommand(label, help, cmd);
    }

    public void registerCommand(String label, String help, Command cmd) {
        addCommand(label, help, cmd, true);
    }

    public void registerCommand(String label, String help, NoArgsCommand cmd) {
        addCommand(label, help, cmd, true);
    }

    public void registerCommand(String label, String help, SingleArgCommand cmd) {
        addCommand(label, help, cmd, true);
    }

    public void registerCommand(String label, String help, Command cmd, boolean needsAuth) {
        addCommand(label, help, cmd, needsAuth);
    }

    public void registerCommand(String label, String help, NoArgsCommand cmd, boolean needsAuth) {
        addCommand(label, help, cmd, needsAuth);
    }

    public void registerCommand(String label, String help, SingleArgCommand cmd, boolean needsAuth) {
        addCommand(label, help, cmd, needsAuth);
    }

    /**
     * Internally registers a SITE command
     * @param label The command name
     * @param help The help message
     * @param cmd The command function
     */
    protected void addSiteCommand(String label, String help, Command cmd) {
        siteCommands.put(label.toUpperCase(), new CommandInfo(cmd, help, true));
    }

    /**
     * Internally registers a command
     * @param label The command name
     * @param help The help message
     * @param cmd The command function
     * @param needsAuth Whether authentication is required to run this command
     */
    protected void addCommand(String label, String help, Command cmd, boolean needsAuth) {
        commands.put(label.toUpperCase(), new CommandInfo(cmd, help, needsAuth));
    }

    /**
     * Gets the help message from a SITE command
     * @param label The command name
     * @return The help message or {@code null} if the command was not found
     */
    public String getSiteHelpMessage(String label) {
        CommandInfo info = siteCommands.get(label);
        return info != null ? info.help : null;
    }

    /**
     * Gets the help message from a command
     * @param label The command name
     * @return The help message or {@code null} if the command was not found
     */
    public String getHelpMessage(String label) {
        CommandInfo info = commands.get(label);
        return info != null ? info.help : null;
    }

    /**
     * Processes commands
     * @param cmd The command and its arguments
     */
    protected void process(String[] cmd) {
        CommandInfo info = commands.get(cmd[0]);

        if(info == null) {
            sendResponse(502, "Unknown command");
            return;
        }

        processCommand(info, cmd);
    }

    /**
     * SITE command
     * @param cmd The command and its arguments
     */
    protected void site(String[] cmd) {
        if(cmd.length <= 1) {
            sendResponse(500, "Missing the command name");
            return;
        }

        CommandInfo info = siteCommands.get(cmd[1]);

        if(info == null) {
            sendResponse(504, "Unknown site command");
            return;
        }

        processCommand(info, cmd);
    }

    protected void processCommand(CommandInfo info, String[] cmd) {
        if(info.needsAuth && !conHandler.isAuthenticated()) {
            sendResponse(530, "Needs authentication");
            return;
        }

        responseSent = false;

        try {
            info.command.run(cmd);
        } catch(ResponseException ex) {
            sendResponse(ex.getCode(), ex.getMessage());
        } catch(FileNotFoundException ex) {
            sendResponse(550, ex.getMessage());
        } catch(IOException ex) {
            sendResponse(450, ex.getMessage());
        } catch(Exception ex) {
            sendResponse(451, ex.getMessage());
            ex.printStackTrace();
        }

        if(!responseSent) sendResponse(200, "Done");
    }

    /**
     * Updates the connection
     */
    protected void update() {
        if(conHandler.shouldStop()) {
            Utils.closeQuietly(this);
            return;
        }

        String line;

        try {
            line = reader.readLine();
        } catch(IOException ex) {
            return;
        }

        if(line == null) {
            Utils.closeQuietly(this);
            return;
        }

        if(line.isEmpty()) return;

        String[] cmd = line.split("\\s+");
        cmd[0] = cmd[0].toUpperCase();
        process(cmd);
    }

    /**
     * Stops the connection, but does not removes it from the list.
     * For a complete cleanup, use {@link #close()} instead
     * @throws IOException When an I/O error occurs
     */
    protected void stop() throws IOException {
        if(!thread.isInterrupted()) {
            thread.interrupt();
        }

        conHandler.onDisconnected();

        con.close();
    }

    @Override
    public void close() throws IOException {
        stop();

        server.removeConnection(this);
    }

    /**
     * Thread that processes this connection when multithreading is enabled
     */
    private class ConnectionThread extends Thread {
        @Override
        public void run() {
            while(!con.isClosed()) {
                update();
            }
        }
    }
}
