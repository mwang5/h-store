package edu.mit.hstore;

import java.io.IOException;
import java.io.UnsupportedEncodingException;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.SelectableChannel;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;

import org.apache.log4j.Logger;
import org.voltdb.ClientResponseImpl;
import org.voltdb.StoredProcedureInvocation;
import org.voltdb.VoltTable;
import org.voltdb.client.ClientResponse;
import org.voltdb.messaging.FastDeserializer;
import org.voltdb.messaging.FastSerializer;

import ca.evanjones.protorpc.AbstractEventHandler;
import ca.evanjones.protorpc.EventLoop;
import ca.evanjones.protorpc.NIOEventLoop;

import com.google.protobuf.RpcCallback;

import edu.mit.net.MessageConnection;
import edu.mit.net.NIOMessageConnection;

/** Listens and responds to Volt client stored procedure requests. */
public class VoltProcedureListener extends AbstractEventHandler {
    private static final Logger LOG = Logger.getLogger(VoltProcedureListener.class.getName());
    
    private final EventLoop eventLoop;
    private final Handler handler;
    private ServerSocketChannel serverSocket;

    public VoltProcedureListener(EventLoop eventLoop, Handler handler) {
        this.eventLoop = eventLoop;
        this.handler = handler;
        assert this.eventLoop != null;
        assert this.handler != null;
    }

    public void acceptCallback(SelectableChannel channel) {
        // accept the connection
        assert channel == serverSocket;
        SocketChannel client;
        try {
            client = serverSocket.accept();
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
        assert client != null;

        // wrap it in a message connection and register with event loop
        NIOMessageConnection connection = new NIOMessageConnection(client);
        connection.setBigEndian();

        eventLoop.registerRead(client, new ClientConnectionHandler(connection));
    }

    // Not private so it can be used in a JUnit test. Gross, but it makes the test a bit easier
    class ClientConnectionHandler extends AbstractEventHandler implements RpcCallback<byte[]> {
        public ClientConnectionHandler(MessageConnection connection) {
            this.connection = connection;
        }

        @Override
        public void readCallback(SelectableChannel channel) {
            read(this);
        }

        @Override
        public boolean writeCallback(SelectableChannel channel) {
            connectionBlocked = connection.tryWrite();
            return connectionBlocked;
        }

//        public void registerWrite() {
//            eventLoop.registerWrite(connection.getChannel(), this);
//        }

        public void hackWritePasswordOk() {
            // Write the "connection ok" message
            ByteBuffer output = ByteBuffer.allocate(100);
            output.put((byte) 0x0);  // unknown. ignored in ConnectionUtil.java
            output.put((byte) 0x0);  // login response code. 0 = OK
            output.putInt(0x0);  // hostId
            output.putLong(0x0);  // connectionId
            output.putLong(0x0);  // timestamp (part of instanceId)
            output.putInt(0x0);  // leaderAddress (part of instanceId)
            final String BUILD_STRING = "hstore";
            output.putInt(BUILD_STRING.length());
            try {
                output.put(BUILD_STRING.getBytes("UTF-8"));
            } catch (UnsupportedEncodingException e) {
                throw new RuntimeException(e);
            }
            output.flip();

            // Copy to a new array for MessageConnection
            byte[] message = new byte[output.remaining()];
            output.get(message);
            assert output.remaining() == 0;

            boolean blocked = connection.write(message);
            assert !blocked;
        }

        @Override
        public void run(byte[] serializedResult) {
            boolean blocked = connection.write(serializedResult);
            // Only register the write if being blocked is "new"
            // TODO: Use NonBlockingConnection which avoids attempting to write when blocked
            if (blocked && !connectionBlocked) {
                eventLoop.registerWrite(connection.getChannel(), this);
                connectionBlocked = true;
            }
            assert blocked == connectionBlocked;
        }

        private final MessageConnection connection;
        boolean connectionBlocked = false;

        public String user = null;
        public byte[] passwordHash = null;
    }

    private void read(ClientConnectionHandler eventLoopCallback) {
        byte[] output;
        while ((output = eventLoopCallback.connection.tryRead()) != null) {
            if (output.length == 0) {
                // connection closed
                LOG.debug("Connection closed");
                eventLoopCallback.connection.close();
                return;
            }

            if (eventLoopCallback.user == null) {
                ByteBuffer input = ByteBuffer.wrap(output);
                input.order(ByteOrder.BIG_ENDIAN);
                try {
                    @SuppressWarnings("unused")
                    byte version = input.get();
                    int length = input.getInt();
                    byte[] m = new byte[length];
                    input.get(m);
                    @SuppressWarnings("unused")
                    String dataService = new String(m, "UTF-8");
                    length = input.getInt();
                    m = new byte[length];
                    eventLoopCallback.user = new String(m, "UTF-8");
                    eventLoopCallback.passwordHash = new byte[input.remaining()];
                    input.get(eventLoopCallback.passwordHash);
                } catch (UnsupportedEncodingException e) {
                    throw new RuntimeException(e);
                }

                // write to say "okay": BIG HACK
                eventLoopCallback.hackWritePasswordOk();
            } else {
                LOG.debug("got request " + output.length);

                handler.procedureInvocation(output, eventLoopCallback);
            }
        }
    }

    public static StoredProcedureInvocation decodeRequest(byte[] bytes) {
        final FastDeserializer fds = new FastDeserializer(ByteBuffer.wrap(bytes));
        StoredProcedureInvocation task;
        try {
            task = fds.readObject(StoredProcedureInvocation.class);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }

        task.buildParameterSet();
        return task;
    }

    public static byte[] serializeResponse(VoltTable[] results, long clientHandle) {
        // Serialize the results
        byte status = ClientResponse.SUCCESS;
        String extra = null;
        ClientResponseImpl response = new ClientResponseImpl(-1, status, results, extra);
        response.setClientHandle(clientHandle);
        FastSerializer out = new FastSerializer();
        try {
            out.writeObject(response);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }

        return out.getBytes();
    }

    public void bind(int port) {
        try {
            serverSocket = ServerSocketChannel.open();
            // Mac OS X: Must bind() before calling Selector.register, or you don't get accept() events
            serverSocket.socket().bind(new InetSocketAddress(port));
            eventLoop.registerAccept(serverSocket, this);
        } catch (IOException e) { throw new RuntimeException(e); }
    }

    public void close() {
        try {
            serverSocket.close();
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    public void bind() {
        bind(org.voltdb.client.Client.VOLTDB_SERVER_PORT);
    }

    public void setServerSocketForTest(ServerSocketChannel serverSocket) {
        this.serverSocket = serverSocket;
    }

    public static interface Handler {
        public void procedureInvocation(byte[] serializedRequest,
                RpcCallback<byte[]> done);
    }

    public static void main(String[] vargs) throws Exception {
        // Example of using VoltProcedureListener: prints procedure name, returns empty array
        NIOEventLoop eventLoop = new NIOEventLoop();
        class PrintHandler implements Handler {
            public void procedureInvocation(byte[] serializedRequest, RpcCallback<byte[]> done) {
                StoredProcedureInvocation invocation = decodeRequest(serializedRequest);

                LOG.debug("request: " + invocation.getProcName() + " " +
                        invocation.getParams().toArray().length);
                done.run(serializeResponse(new VoltTable[0], invocation.getClientHandle()));
            }
        }
        PrintHandler printer = new PrintHandler();
        VoltProcedureListener listener = new VoltProcedureListener(eventLoop, printer);
        listener.bind();

        eventLoop.run();
    }
}