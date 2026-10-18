package com.viitube.proxy.proto;

/** Protocol buffers wire format encoder (the subset the generated messages use). */
public final class ProtoWriter {
    private byte[] buffer;
    private int length;

    public ProtoWriter() {
        this(256);
    }

    public ProtoWriter(int capacity) {
        buffer = new byte[Math.max(16, capacity)];
    }

    public byte[] toByteArray() {
        byte[] result = new byte[length];
        System.arraycopy(buffer, 0, result, 0, length);
        return result;
    }

    private void ensure(int extra) {
        if (length + extra <= buffer.length) return;
        byte[] grown = new byte[Math.max(buffer.length * 2, length + extra)];
        System.arraycopy(buffer, 0, grown, 0, length);
        buffer = grown;
    }

    private void rawByte(int value) {
        ensure(1);
        buffer[length++] = (byte) value;
    }

    private void rawVarint(long value) {
        ensure(10);
        while ((value & ~0x7fL) != 0) {
            buffer[length++] = (byte) ((value & 0x7f) | 0x80);
            value >>>= 7;
        }
        buffer[length++] = (byte) value;
    }

    private void tag(int field, int wireType) {
        rawVarint(((long) field << 3) | wireType);
    }

    private static int varintSize(long value) {
        int size = 1;
        while ((value & ~0x7fL) != 0) {
            size++;
            value >>>= 7;
        }
        return size;
    }

    private static byte[] utf8(String value) {
        try {
            return value.getBytes("UTF-8");
        } catch (java.io.UnsupportedEncodingException e) {
            throw new IllegalStateException(e);
        }
    }

    public void writeString(int field, String value) {
        writeBytes(field, utf8(value));
    }

    public void writeBytes(int field, byte[] value) {
        tag(field, 2);
        rawVarint(value.length);
        ensure(value.length);
        System.arraycopy(value, 0, buffer, length, value.length);
        length += value.length;
    }

    /** Nested message: encoded in place, then moved right by the size of its length prefix. */
    public void writeMessage(int field, ProtoMessage message) {
        tag(field, 2);
        int start = length;
        message.writeTo(this);
        int size = length - start;
        int prefix = varintSize(size);
        ensure(prefix);
        System.arraycopy(buffer, start, buffer, start + prefix, size);
        int end = length + prefix;
        length = start;
        rawVarint(size);
        length = end;
    }

    // Negative int32 values are sign-extended to ten bytes, like every protobuf encoder.
    public void writeInt32(int field, int value) { tag(field, 0); rawVarint(value); }
    public void writeUInt32(int field, int value) { tag(field, 0); rawVarint(value & 0xffffffffL); }
    public void writeSInt32(int field, int value) { tag(field, 0); rawVarint((value << 1) ^ (value >> 31)); }
    public void writeInt64(int field, long value) { tag(field, 0); rawVarint(value); }
    public void writeSInt64(int field, long value) { tag(field, 0); rawVarint((value << 1) ^ (value >> 63)); }
    public void writeBool(int field, boolean value) { tag(field, 0); rawByte(value ? 1 : 0); }
    public void writeFixed32(int field, int value) { tag(field, 5); writeFixed32NoTag(value); }
    public void writeFixed64(int field, long value) { tag(field, 1); writeFixed64NoTag(value); }

    // Packed repeated values.
    public void writeInt32NoTag(int value) { rawVarint(value); }
    public void writeUInt32NoTag(int value) { rawVarint(value & 0xffffffffL); }
    public void writeSInt32NoTag(int value) { rawVarint((value << 1) ^ (value >> 31)); }
    public void writeInt64NoTag(long value) { rawVarint(value); }
    public void writeSInt64NoTag(long value) { rawVarint((value << 1) ^ (value >> 63)); }
    public void writeBoolNoTag(boolean value) { rawByte(value ? 1 : 0); }

    public void writeFixed32NoTag(int value) {
        ensure(4);
        for (int i = 0; i < 4; i++) buffer[length++] = (byte) (value >>> (8 * i));
    }

    public void writeFixed64NoTag(long value) {
        ensure(8);
        for (int i = 0; i < 8; i++) buffer[length++] = (byte) (value >>> (8 * i));
    }
}
