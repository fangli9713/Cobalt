package it.auties.whatsapp.crypto;

import it.auties.bytes.Bytes;
import it.auties.whatsapp.controller.WhatsappKeys;
import it.auties.whatsapp.model.request.Node;
import it.auties.whatsapp.model.signal.keypair.SignalKeyPair;
import it.auties.whatsapp.model.signal.keypair.SignalPreKeyPair;
import it.auties.whatsapp.model.signal.message.SignalMessage;
import it.auties.whatsapp.model.signal.message.SignalPreKeyMessage;
import it.auties.whatsapp.model.signal.session.Session;
import it.auties.whatsapp.model.signal.session.SessionAddress;
import it.auties.whatsapp.model.signal.session.SessionChain;
import it.auties.whatsapp.model.signal.session.SessionState;
import it.auties.whatsapp.util.Keys;
import it.auties.whatsapp.util.SignalSpecification;
import it.auties.whatsapp.util.Validate;
import lombok.NonNull;
import lombok.SneakyThrows;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.Semaphore;
import java.util.function.Supplier;

import static it.auties.curve25519.Curve25519.calculateAgreement;
import static it.auties.whatsapp.model.request.Node.with;
import static java.util.Map.of;
import static java.util.Objects.requireNonNull;

public record SessionCipher(@NonNull SessionAddress address, @NonNull WhatsappKeys keys) implements SignalSpecification {
    public Node encrypt(byte @NonNull [] data){
        try {
            SEMAPHORE.acquire();
            var session = loadSession();
            var currentState = session.currentState();

            Validate.isTrue(keys.hasTrust(address, session.currentState().remoteIdentityKey()),
                    "Untrusted key", SecurityException.class);

            var chain = currentState.findChain(currentState.ephemeralKeyPair().encodedPublicKey())
                    .orElseThrow(() -> new NoSuchElementException("Missing chain for %s".formatted(address)));
            fillMessageKeys(chain, chain.counter() + 1);

            System.out.printf("Keys: %s%nCounter: %s%nResult: %s%n%n", chain.messageKeys(), chain.counter(), chain.messageKeys().get(chain.counter()));
            var currentKey = chain.messageKeys()
                    .get(chain.counter())
                    .publicKey();

            var secrets = Hkdf.deriveSecrets(currentKey,
                    "WhisperMessageKeys".getBytes(StandardCharsets.UTF_8));
            Objects.requireNonNull(chain.messageKeys().remove(chain.counter()),
                    "Cannot remove chain");

            var iv = Bytes.of(secrets[2])
                    .cut(IV_LENGTH)
                    .toByteArray();
            var encrypted = AesCbc.encrypt(iv, data, secrets[0]);

            var encryptedMessage = encrypt(currentState, chain, secrets, encrypted);
            keys.addSession(address, session);

            return with("enc",
                    of("v", "2", "type", currentState.hasPreKey() ? "pkmsg" : "msg"), encryptedMessage);
        }catch (Throwable throwable){
            throw new RuntimeException("Cannot encrypt message: an exception occured", throwable);
        }finally {
            SEMAPHORE.release();
        }
    }

    private byte[] encrypt(SessionState state, SessionChain chain, byte[][] whisperKeys, byte[] encrypted) {
        var message = new SignalMessage(
                state.ephemeralKeyPair().encodedPublicKey(),
                chain.counter(),
                state.previousCounter(),
                encrypted,
                encodedMessage -> createMessageSignature(state, whisperKeys, encodedMessage)
        );

        return state.hasPreKey() ? createPreKeyMessage(state, message)
                : message.serialized();
    }

    private byte[] createPreKeyMessage(SessionState state, SignalMessage message) {
        return SignalPreKeyMessage.builder()
                .version(CURRENT_VERSION)
                .identityKey(keys.identityKeyPair().encodedPublicKey())
                .registrationId(keys.id())
                .baseKey(state.pendingPreKey().baseKey())
                .signedPreKeyId(state.pendingPreKey().signedKeyId())
                .serializedSignalMessage(message.serialized())
                .preKeyId(state.pendingPreKey().preKeyId())
                .build()
                .serialized();
    }

    private byte[] createMessageSignature(SessionState state, byte[][] whisperKeys, byte[] encodedMessage) {
        var macInput = Bytes.of(keys.identityKeyPair().encodedPublicKey())
                .append(state.remoteIdentityKey())
                .append(encodedMessage)
                .assertSize(encodedMessage.length + 33 + 33)
                .toByteArray();
        return Bytes.of(Hmac.calculateSha256(macInput, whisperKeys[1]))
                .cut(MAC_LENGTH)
                .toByteArray();
    }

    private void fillMessageKeys(SessionChain chain, int counter) {
        if (chain.counter() >= counter) {
            return;
        }

        Validate.isTrue(counter - chain.counter() <= 2000,
                "Message overflow: expected <= 2000, got %s", counter - chain.counter());
        Validate.isTrue(chain.key() != null,
                "Closed chain");

        var messagesHmac = Hmac.calculateSha256(new byte[]{1}, chain.key());
        var keyPair = new SignalPreKeyPair(chain.counter() + 1, messagesHmac, null);
        chain.messageKeys().put(chain.counter() + 1, keyPair);

        var keyHmac = Hmac.calculateSha256(new byte[]{2}, chain.key());
        chain.key(keyHmac);
        chain.incrementCounter();
        fillMessageKeys(chain, counter);
    }

    public byte[] decrypt(SignalPreKeyMessage message) {
        var session = loadSession(() -> {
            Validate.isTrue(message.registrationId() != 0, "Missing registration jid");
            return new Session();
        });

        var builder = new SessionBuilder(address, keys);
        builder.createIncoming(session, message);
        var state = session.findState(message.version(), message.baseKey())
                .orElseThrow(() -> new NoSuchElementException("Missing state"));
        var plaintext = decrypt(message.signalMessage(), state);
        keys.addSession(address, session);
        return plaintext;
    }

    public byte[] decrypt(SignalMessage message) {
        var session = loadSession();
        return session.states()
                .stream()
                .map(state -> decrypt(message, session, state))
                .flatMap(Optional::stream)
                .findFirst()
                .orElseThrow(() -> new NoSuchElementException("Cannot decrypt message: no suitable session found"));
    }

    private Optional<byte[]> decrypt(SignalMessage message, Session session, SessionState state) {
        try {
            Validate.isTrue(keys.hasTrust(address, state.remoteIdentityKey()),
                    "Untrusted key");
            var result = decrypt(message, state);
            keys.addSession(address, session);
            return Optional.of(result);
        }catch (Throwable ignored){
            return Optional.empty();
        }
    }

    @SneakyThrows
    private byte[] decrypt(SignalMessage message, SessionState state) {
        maybeStepRatchet(message, state);

        var chain = state.findChain(message.ephemeralPublicKey())
                .orElseThrow(() -> new NoSuchElementException("Invalid chain"));
        fillMessageKeys(chain, message.counter());

        Validate.isTrue(chain.hasMessageKey(message.counter()),
                "Key used already or never filled");
        var messageKey = chain.messageKeys().get(message.counter());
        chain.messageKeys().remove(message.counter());

        var secrets = Hkdf.deriveSecrets(messageKey.publicKey(),
                "WhisperMessageKeys".getBytes(StandardCharsets.UTF_8));

        var hmacInput = Bytes.of(state.remoteIdentityKey())
                .append(keys.identityKeyPair().encodedPublicKey())
                .append(message.serialized())
                .cut(-SignalMessage.MAC_LENGTH)
                .toByteArray();
        var hmac = Bytes.of(Hmac.calculateSha256(hmacInput, secrets[1]))
                .cut(SignalMessage.MAC_LENGTH)
                .toByteArray();
        Validate.isTrue(Arrays.equals(message.signature(), hmac),
                "Cannot decode message: Hmac validation failed", SecurityException.class);

        var iv = Bytes.of(secrets[2])
                .cut(IV_LENGTH)
                .toByteArray();
        var plaintext = AesCbc.decrypt(iv, message.ciphertext(), secrets[0]);
        state.pendingPreKey(null);
        return plaintext;
    }

    private void maybeStepRatchet(SignalMessage message, SessionState state) {
        if (state.hasChain(message.ephemeralPublicKey())) {
            return;
        }

        var previousRatchet = state.findChain(state.lastRemoteEphemeralKey());
        previousRatchet.ifPresent(chain -> {
            fillMessageKeys(chain, state.previousCounter());
            chain.key(null);
        });

        calculateRatchet(message, state, false);
        var previousCounter = state.findChain(state.ephemeralKeyPair().encodedPublicKey());
        previousCounter.ifPresent(chain -> {
            state.previousCounter(chain.counter());
            state.removeChain(state.ephemeralKeyPair().encodedPublicKey());
        });

        state.ephemeralKeyPair(SignalKeyPair.random());
        calculateRatchet(message, state, true);
        state.lastRemoteEphemeralKey(message.ephemeralPublicKey());
    }

    private void calculateRatchet(SignalMessage message, SessionState state, boolean sending) {
        var sharedSecret = calculateAgreement(Keys.withoutHeader(message.ephemeralPublicKey()),
                state.ephemeralKeyPair().privateKey());
        var masterKey = Hkdf.deriveSecrets(sharedSecret, state.rootKey(),
                "WhisperRatchet".getBytes(StandardCharsets.UTF_8), 2);
        var chainKey = sending ? state.ephemeralKeyPair().encodedPublicKey() : message.ephemeralPublicKey();
        state.addChain(chainKey, new SessionChain(-1, masterKey[1]));
        state.rootKey(masterKey[0]);
    }

    private Session loadSession() {
        return loadSession(() -> null);
    }

    private Session loadSession(Supplier<Session> defaultSupplier) {
        return keys.findSessionByAddress(address)
                .orElseGet(() -> requireNonNull(defaultSupplier.get(), "Missing session for %s. Known sessions: %s".formatted(address, keys.sessions())));
    }
}