package crypt.ssl.connection;

import crypt.ssl.CipherSuite;
import crypt.ssl.encoding.TlsEncoder;
import crypt.ssl.exceptions.NoCloseNotifyException;
import crypt.ssl.exceptions.TlsAlertException;
import crypt.ssl.exceptions.TlsException;
import crypt.ssl.exceptions.TlsUnexpectedMessageException;
import crypt.ssl.messages.*;
import crypt.ssl.messages.alert.Alert;
import crypt.ssl.messages.alert.AlertDescription;
import crypt.ssl.messages.alert.AlertLevel;
import crypt.ssl.messages.handshake.CertificateMessage;
import crypt.ssl.messages.handshake.ClientHello;
import crypt.ssl.messages.handshake.HandshakeMessage;
import crypt.ssl.messages.handshake.ServerHello;
import crypt.ssl.utils.Assert;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.util.Collections;
import java.util.List;
import java.util.Random;


public class TlsConnection implements Connection {

    private static final List<CompressionMethod> NO_COMPRESSION = Collections.singletonList(CompressionMethod.NULL);

    // We don't support other TLS versions.
    private final ProtocolVersion version = ProtocolVersion.TLSv12;
    private final Random random = new Random();

    private Socket socket;
    private MessageStream messageStream;

    private SecurityParameters parameters = new SecurityParameters();

    private ConnectionState state = ConnectionState.NEW;
    private HandshakeState handshakeState = null;
    private boolean fullHandshake = true;

    private final Buffer applicationDateBuffer = new Buffer();

    /* -------------------------------------------------- */

    //TODO: provide other extension points (extensions, ...)
    private final List<CipherSuite> supportedCipherSuites;

    //TODO: add possibility to specify parameters of the previous connection for session resume

    public TlsConnection(List<CipherSuite> supportedCipherSuites) {
        this.supportedCipherSuites = supportedCipherSuites;
    }

    @Override
    public void connect(InetSocketAddress address) throws IOException {
        this.socket = new Socket();
        this.socket.connect(address);

        InputStream in = this.socket.getInputStream();
        OutputStream out = this.socket.getOutputStream();

        this.messageStream = new MessageStream(in, out);
        this.messageStream.setRecordVersion(this.version);

        performHandshake();
    }

    private void performHandshake() throws IOException {
        sendClientHello();

        this.state = ConnectionState.HANDSHAKE;
        this.handshakeState = HandshakeState.CLIENT_HELLO_SENT;

        while (this.state != ConnectionState.ESTABLISHED) {
            //TODO: connection may become closed during handshake, handle that
            readAndHandleMessage();
        }
    }

    private void sendClientHello() throws IOException {
        RandomValue randomValue = generateRandom();

        ClientHello clientHello = ClientHello.builder()
                .clientVersion(this.version)
                .random(randomValue)
                .sessionId(SessionId.EMPTY)
                .cipherSuites(this.supportedCipherSuites)
                .compressionMethods(NO_COMPRESSION)
                .extensions(Extensions.empty())
                .build();

        this.parameters.setClientRandom(randomValue);

        Message message = new Message();
        TlsEncoder.writeClientHello(message, clientHello);

        this.messageStream.writeMessage(ContentType.HANDSHAKE, message.toBuffer());
    }

    private RandomValue generateRandom() {
        byte[] randomBytes = new byte[32];
        random.nextBytes(randomBytes);

        return new RandomValue(randomBytes);
    }

    private void readAndHandleMessage() throws IOException {
        TlsMessage message = this.messageStream.readMessage();
        if (message == null) {
            throw new NoCloseNotifyException();
        }

        try {
            handleMessage(message);
        } catch (TlsAlertException alert) {
            sendAlert(alert.getLevel(), alert.getDescription());
            //TODO: close the connection
        }
    }

    private void handleMessage(TlsMessage message) throws IOException {
        switch (message.getContentType()) {
            case HANDSHAKE:
                handleHandshakeMessage((HandshakeMessage) message);
                break;

            case CHANGE_CIPHER_SPEC:
                handleChangeCipherSpec((ChangeCipherSpec) message);
                break;

            case APPLICATION_DATA:
                handleApplicationData((ApplicationData) message);
                break;

            case ALERT:
                handleAlertMessage((Alert) message);
                break;
        }
    }

    private void handleHandshakeMessage(HandshakeMessage handshake) throws IOException {
        // Actually, there is a scenario where handshake message can be received after connection has been established.
        // It a case when server sends a HELLO_REQUEST message to renegotiate connection parameters.
        // But this part of the protocol in not supported in current implementation.
        checkConnectionState(ConnectionState.HANDSHAKE);

        Assert.assertNotNull(this.handshakeState);

        switch (this.handshakeState) {
            case CLIENT_HELLO_SENT:
                ServerHello serverHello = safeCast(handshake, ServerHello.class);

                Assert.assertEquals(CompressionMethod.NULL, serverHello.getCompressionMethod());
                Assert.assertEquals(this.version, serverHello.getServerVersion());

                parameters.setServerRandom(serverHello.getRandom());
                parameters.setCipherSuite(serverHello.getCipherSuite());

                //TODO: For abbreviated handshake:
                //TODO: if this SSID is equal to the one specified in the ClientHello (if any),
                //TODO: then the server is ready to perform an abbreviated handshake.
                //TODO: if we didn't specify any SSID Id or if the received SSID isn't equal to our SSID
                //TODO: then perform a full handshake and store received SSID for future uses
                //serverHello.getSessionId();

                //TODO: do something useful with extensions
                //serverHello.getExtensions();

                this.handshakeState = HandshakeState.SERVER_HELLO_RECEIVED;
                return;

            case SERVER_HELLO_RECEIVED:
                // In case of abbreviated handshake we should receive ChangeCipherSpec message after the ServerHello
                Assert.assertTrue(this.fullHandshake);

                // Here we can receive any of the Certificate, ServerKeyExchange, CertificateRequest, ServerHelloDone
                // messages depending on circumstances. But there are several assumptions at this point:
                //  1. We don't support anonymous negotiation, thus Certificate MUST be present.
                //  2. We don't support client authentication, thus we don't expect to receive CertificateRequest.

                CertificateMessage certificate = safeCast(handshake, CertificateMessage.class);

                //TODO: check certificate and store it's necessary parameters

                this.handshakeState = HandshakeState.CERTIFICATE_RECEIVED;
                return;

            case CERTIFICATE_RECEIVED:
                // Here we should receive either ServerKeyExchange if our key exchange protocol requires that
                // or ServerHelloDone otherwise

                /*
                if (keyExchange.requiresServerData()) {
                    ServerKeyExchange serveKeyExchange = safeCast(handshake, ServerKeyExchange.class);

                    ... process it ...

                    this.handshakeState = HandshakeState.SERVER_KEY_EXCHANGE_RECEIVED;
                    return;
                }

                otherwise we just fall through to the next section
                (also, probably it is a good idea to execute the following assignment
                this.handshakeState = HandshakeState.SERVER_KEY_EXCHANGE_RECEIVED
                in order to indicate that we don't need this step at all)
                */
            case SERVER_KEY_EXCHANGE_RECEIVED:
        }
    }

    private void handleChangeCipherSpec(ChangeCipherSpec changeCipherSpec) throws IOException {
        // We expect to receive ChangeCipherSpec message in two cases:
        //   1. After we sent a Finished message in case of a full handshake
        //   2. After the server sent us a ServerHello message in case of an abbreviated handshake
        // Anyway, current connection state should be HANDSHAKE.

        checkConnectionState(ConnectionState.HANDSHAKE);

        if (this.fullHandshake) {
            checkHandshakeState(HandshakeState.FINISHED_SENT);
        } else {
            checkHandshakeState(HandshakeState.SERVER_HELLO_RECEIVED);
        }

        Assert.assertEquals(changeCipherSpec.getType(), 1);

        //TODO: mark current connection as 'encrypted'

        this.handshakeState = HandshakeState.CHANGE_CIPHER_SPEC_RECEIVED;
    }

    private void handleApplicationData(ApplicationData applicationData) throws IOException {
    }

    private void handleAlertMessage(Alert alert) throws IOException {
    }

    private void closeInternal() throws IOException {
        //TODO: check
        sendAlert(AlertLevel.FATAL, AlertDescription.CLOSE_NOTIFY);
        this.socket.close();
    }

    private void sendAlert(AlertLevel level, AlertDescription description) throws IOException {
        Message message = new Message();

        Alert alert = new Alert(level, description);
        TlsEncoder.writeAlert(message, alert);

        this.messageStream.writeMessage(ContentType.ALERT, message.toBuffer());
    }

    @Override
    public InputStream getInput() throws IOException {
        return null;
    }

    @Override
    public OutputStream getOutput() throws IOException {
        return null;
    }

    @Override
    public void close() throws IOException {
        closeInternal();
    }

    private static <T> T safeCast(Object object, Class<T> clazz) throws TlsException {
        if (clazz.isInstance(object)) {
            return clazz.cast(object);
        }

        throw new TlsUnexpectedMessageException();
    }

    private void checkConnectionState(ConnectionState expectedState) throws TlsException {
        if (this.state != expectedState) {
            throw new TlsUnexpectedMessageException();
        }
    }

    private void checkHandshakeState(HandshakeState expectedState) throws TlsException {
        if (this.handshakeState != expectedState) {
            throw new TlsUnexpectedMessageException();
        }
    }

    private enum ConnectionState {
        NEW,
        HANDSHAKE,
        ABBREVIATED_HANDSHAKE,
        ESTABLISHED,
        CLOSED
    }

    private enum HandshakeState {
        CLIENT_HELLO_SENT,
        SERVER_HELLO_RECEIVED,
        CHANGE_CIPHER_SPEC_RECEIVED,
        CERTIFICATE_RECEIVED, SERVER_KEY_EXCHANGE_RECEIVED, FINISHED_SENT
    }

    private static class Message extends ByteArrayOutputStream {

        public ByteBuffer toBuffer() {
            return ByteBuffer.wrap(this.toByteArray());
        }
    }

    private class TlsInputStream extends InputStream {

        @Override
        public int read() throws IOException {
            return 0;
        }
    }
}
