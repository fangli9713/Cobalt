package it.auties.whatsapp.binary;

import io.netty.buffer.ByteBuf;
import it.auties.whatsapp.model.jid.Jid;
import it.auties.whatsapp.model.jid.JidServer;
import it.auties.whatsapp.model.node.Node;
import it.auties.whatsapp.util.BytesHelper;
import it.auties.whatsapp.util.Validate;

import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.IntStream;

import static it.auties.whatsapp.binary.BinaryTag.*;

public final class BinaryDecoder {
    private ByteBuf buffer;

    public Node decode(byte[] input) {
        var buffer = BytesHelper.newBuffer(input);
        var token = buffer.readByte() & 2;
        allocateBuffer(token, buffer);
        return readNode();
    }

    private void allocateBuffer(int token, ByteBuf input) {
        if (token == 0) {
            this.buffer = input;
            return;
        }

        var bytes = BytesHelper.readBuffer(input);
        this.buffer = BytesHelper.newBuffer();
        buffer.writeBytes(BytesHelper.decompress(bytes));
    }

    private Node readNode() {
        var token = buffer.readUnsignedByte();
        var size = readSize(token);
        Validate.isTrue(size != 0, "Cannot decode node with empty body");
        var description = readString();
        var attrs = readAttributes(size);
        return size % 2 != 0 ? Node.of(description, attrs)
                : Node.of(description, attrs, read(false));
    }

    private String readString() {
        var read = read(true);
        if (read instanceof String string) {
            return string;
        }

        throw new IllegalArgumentException("Strict decoding failed: expected string, got %s with type %s"
                .formatted(read, read == null ? null : read.getClass().getName()));
    }

    private List<Node> readList(int size) {
        return IntStream.range(0, size)
                .mapToObj(index -> readNode())
                .toList();
    }

    private String readString(List<Character> permitted, int start, int end) {
        var string = new char[2 * end - start];
        IntStream.iterate(0, index -> index < string.length - 1, n -> n + 2)
                .forEach(index -> readChar(permitted, string, index));
        if (start != 0) {
            string[string.length - 1] = permitted.get(buffer.readUnsignedByte() >>> 4);
        }

        return String.valueOf(string);
    }

    private void readChar(List<Character> permitted, char[] string, int index) {
        var token = buffer.readUnsignedByte();
        string[index] = permitted.get(token >>> 4);
        string[index + 1] = permitted.get(15 & token);
    }

    private Object read(boolean parseBytes) {
        var tag = buffer.readUnsignedByte();
        return switch (of(tag)) {
            case LIST_EMPTY -> null;
            case COMPANION_JID -> readCompanionJid();
            case LIST_8 -> readList(buffer.readUnsignedByte());
            case LIST_16 -> readList(buffer.readUnsignedShort());
            case JID_PAIR -> readJidPair();
            case HEX_8 -> readHexString();
            case BINARY_8 -> readString(buffer.readUnsignedByte(), parseBytes);
            case BINARY_20 -> readString(readString20Length(), parseBytes);
            case BINARY_32 -> readString(buffer.readUnsignedShort(), parseBytes);
            case NIBBLE_8 -> readNibble();
            default -> readStringFromToken(tag);
        };
    }

    private int readString20Length() {
        return ((15 & buffer.readUnsignedByte()) << 16)
                + (buffer.readUnsignedByte() << 8)
                + buffer.readUnsignedByte();
    }

    private String readStringFromToken(int token) {
        if (token < DICTIONARY_0.data() || token > DICTIONARY_3.data()) {
            return BinaryTokens.SINGLE_BYTE.get(token - 1);
        }

        var delta = (BinaryTokens.DOUBLE_BYTE.size() / 4) * (token - DICTIONARY_0.data());
        return BinaryTokens.DOUBLE_BYTE.get(buffer.readUnsignedByte() + delta);
    }

    private String readNibble() {
        var number = buffer.readUnsignedByte();
        return readString(BinaryTokens.NUMBERS, number >>> 7, 127 & number);
    }

    private Object readString(int size, boolean parseBytes) {
        var data = BytesHelper.readBuffer(buffer, size);
        return parseBytes ? new String(data, StandardCharsets.UTF_8) : data;
    }

    private String readHexString() {
        var number = buffer.readUnsignedByte();
        return readString(BinaryTokens.HEX, number >>> 7, 127 & number);
    }

    private Jid readJidPair() {
        var read = read(true);
        if (read instanceof String encoded) {
            return Jid.of(encoded, JidServer.of(readString()));
        } else if (read == null) {
            return Jid.ofServer(JidServer.of(readString()));
        } else {
            throw new RuntimeException("Invalid jid type");
        }
    }

    private Jid readCompanionJid() {
        var agent = buffer.readUnsignedByte();
        var device = buffer.readUnsignedByte();
        var user = readString();
        return Jid.ofDevice(user, device, agent);
    }

    private int readSize(int token) {
        return LIST_8.contentEquals(token) ? buffer.readUnsignedByte() : buffer.readUnsignedShort();
    }

    private Map<String, Object> readAttributes(int size) {
        var map = new HashMap<String, Object>();
        for (var pair = size - 1; pair > 1; pair -= 2) {
            var key = readString();
            var value = read(true);
            map.put(key, value);
        }
        return map;
    }
}