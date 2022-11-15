package it.auties.whatsapp.socket;

import it.auties.curve25519.Curve25519;
import it.auties.whatsapp.api.HistoryLength;
import it.auties.whatsapp.crypto.Handshake;
import it.auties.whatsapp.model.request.Request;
import it.auties.whatsapp.model.signal.auth.*;
import it.auties.whatsapp.util.BytesHelper;
import it.auties.whatsapp.util.JacksonProvider;
import it.auties.whatsapp.util.SignalSpecification;
import jakarta.websocket.Session;
import lombok.RequiredArgsConstructor;
import lombok.SneakyThrows;

import java.util.concurrent.CompletableFuture;

import static java.lang.Long.parseLong;

@RequiredArgsConstructor
class AuthHandler implements JacksonProvider {
    private final SocketHandler socketHandler;
    private Handshake handshake;
    private CompletableFuture<Void> future;

    protected void createHandshake() {
        this.handshake = new Handshake(socketHandler.keys());
        handshake.updateHash(socketHandler.keys()
                .ephemeralKeyPair()
                .publicKey());
    }

    @SneakyThrows
    protected CompletableFuture<Void> login(Session session, byte[] message) {
        var serverHello = PROTOBUF.readMessage(message, HandshakeMessage.class)
                .serverHello();
        handshake.updateHash(serverHello.ephemeral());
        var sharedEphemeral = Curve25519.sharedKey(serverHello.ephemeral(), socketHandler.keys()
                .ephemeralKeyPair()
                .privateKey());
        handshake.mixIntoKey(sharedEphemeral);

        var decodedStaticText = handshake.cipher(serverHello.staticText(), false);
        var sharedStatic = Curve25519.sharedKey(decodedStaticText, socketHandler.keys()
                .ephemeralKeyPair()
                .privateKey());
        handshake.mixIntoKey(sharedStatic);
        handshake.cipher(serverHello.payload(), false);

        var encodedKey = handshake.cipher(socketHandler.keys()
                .noiseKeyPair()
                .publicKey(), true);
        var sharedPrivate = Curve25519.sharedKey(serverHello.ephemeral(), socketHandler.keys()
                .noiseKeyPair()
                .privateKey());
        handshake.mixIntoKey(sharedPrivate);

        var encodedPayload = handshake.cipher(createUserPayload(), true);
        var clientFinish = new ClientFinish(encodedKey, encodedPayload);
        var handshakeMessage = new HandshakeMessage(clientFinish);
        return Request.of(handshakeMessage)
                .sendWithNoResponse(session, socketHandler.keys(), socketHandler.store())
                .thenRunAsync(socketHandler.keys()::clear)
                .thenRunAsync(handshake::finish);
    }

    @SneakyThrows
    private byte[] createUserPayload() {
        var builder = ClientPayload.builder()
                .connectReason(ClientPayload.ClientPayloadConnectReason.USER_ACTIVATED)
                .connectType(ClientPayload.ClientPayloadConnectType.WIFI_UNKNOWN)
                .userAgent(createUserAgent())
                .passive(true)
                .webInfo(new WebInfo(WebInfo.WebInfoWebSubPlatform.WEB_BROWSER));
        return PROTOBUF.writeValueAsBytes(finishUserPayload(builder));
    }

    private ClientPayload finishUserPayload(ClientPayload.ClientPayloadBuilder builder) {
        if (socketHandler.store().userCompanionJid() != null) {
            return builder.username(parseLong(socketHandler.store().userCompanionJid().user()))
                    .device(socketHandler.store().userCompanionJid().device())
                    .build();
        }

        return builder.regData(createRegisterData())
                .build();
    }

    private UserAgent createUserAgent() {
        return UserAgent.builder()
                .appVersion(socketHandler.options()
                        .version())
                .platform(UserAgent.UserAgentPlatform.WEB)
                .releaseChannel(UserAgent.UserAgentReleaseChannel.RELEASE)
                .build();
    }

    @SneakyThrows
    private CompanionData createRegisterData() {
        return CompanionData.builder()
                .buildHash(socketHandler.options()
                        .version()
                        .toHash())
                .companion(PROTOBUF.writeValueAsBytes(createCompanionProps()))
                .id(BytesHelper.intToBytes(socketHandler.keys()
                        .id(), 4))
                .keyType(BytesHelper.intToBytes(SignalSpecification.KEY_TYPE, 1))
                .identifier(socketHandler.keys()
                        .identityKeyPair()
                        .publicKey())
                .signatureId(socketHandler.keys()
                        .signedKeyPair()
                        .encodedId())
                .signaturePublicKey(socketHandler.keys()
                        .signedKeyPair()
                        .keyPair()
                        .publicKey())
                .signature(socketHandler.keys()
                        .signedKeyPair()
                        .signature())
                .build();
    }

    private Companion createCompanionProps() {
        return Companion.builder()
                .os(socketHandler.options()
                        .description())
                .platformType(Companion.CompanionPropsPlatformType.DESKTOP)
                .requireFullSync(socketHandler.options()
                        .historyLength() == HistoryLength.ONE_YEAR)
                .build();
    }

    protected void createFuture() {
        this.future = new CompletableFuture<>();
    }

    protected CompletableFuture<Void> future() {
        return future;
    }
}
