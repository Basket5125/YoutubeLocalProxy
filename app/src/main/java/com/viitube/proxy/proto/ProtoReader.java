package com.viitube.proxy.proto;

import java.io.IOException;

/** Protocol buffers wire format decoder over a byte range. */
public final class ProtoReader {
    private final byte[] data;
    private int position;
    private final int end;

    public ProtoReader(byte[] data, int offset, int length) {
        if (offset < 0 || length < 0 || offset + length > data.length) {
            throw new IllegalArgumentException("bad range");
        }
        this.data = data;
        this.position = offset;
        this.end = offset + length;
    }

    public boolean hasMore() {
        return position < end;
    }

    public int readTag() throws IOException {
        long tag = readVarint();
        if ((tag >>> 3) == 0 || tag > 0xffffffffL) throw new IOException("invalid tag " + tag);
        return (int) tag;
    }

    private long readVarint() throws IOException {
        long result = 0;
        for (int shift = 0; shift < 64; shift += 7) {
            if (position >= end) throw new IOException("truncated varint");
            int b = data[position++] & 0xff;
            result |= (long) (b & 0x7f) << shift;
            if ((b & 0x80) == 0) return result;
        }
        throw new IOException("malformed varint");
    }

    private int readLength() throws IOException {
        long size = readVarint();
        if (size < 0 || size > end - position) throw new IOException("truncated field");
        return (int) size;
    }

    public ProtoReader readMessage() throws IOException {
        int size = readLength();
        ProtoReader nested = new ProtoReader(data, position, size);
        position += size;
        return nested;
    }

    public byte[] readBytes() throws IOException {
        int size = readLength();
        byte[] value = new byte[size];
        System.arraycopy(data, position, value, 0, size);
        position += size;
        return value;
    }

    public String readString() throws IOException {
        int size = readLength();
        String value = new String(data, position, size, "UTF-8");
        position += size;
        return value;
    }

    public int readInt32() throws IOException { return (int) readVarint(); }
    public long readInt64() throws IOException { return readVarint(); }
    public boolean readBool() throws IOException { return readVarint() != 0; }

    public int readSInt32() throws IOException {
        int value = (int) readVarint();
        return (value >>> 1) ^ -(value & 1);
    }

    public long readSInt64() throws IOException {
        long value = readVarint();
        return (value >>> 1) ^ -(value & 1);
    }

    public int readFixed32() throws IOException {
        if (end - position < 4) throw new IOException("truncated fixed32");
        int value = 0;
        for (int i = 0; i < 4; i++) value |= (data[position++] & 0xff) << (8 * i);
        return value;
    }

    public long readFixed64() throws IOException {
        if (end - position < 8) throw new IOException("truncated fixed64");
        long value = 0;
        for (int i = 0; i < 8; i++) value |= (long) (data[position++] & 0xff) << (8 * i);
        return value;
    }

    /** Skips the value of a field whose tag was just read. */
    public void skip(int tag) throws IOException {
        switch (tag & 7) {
            case 0: readVarint(); break;
            case 1: if (end - position < 8) throw new IOException("truncated"); position += 8; break;
            case 2: {
                // Not "position += readLength()": Java would read position before the call.
                int size = readLength();
                position += size;
                break;
            }
            case 5: if (end - position < 4) throw new IOException("truncated"); position += 4; break;
            case 3: // group start: skip until the matching end
                int field = tag >>> 3;
                while (true) {
                    int inner = readTag();
                    if ((inner & 7) == 4) {
                        if ((inner >>> 3) != field) throw new IOException("mismatched group");
                        return;
                    }
                    skip(inner);
                }
            default: throw new IOException("unsupported wire type " + (tag & 7));
        }
    }
}
