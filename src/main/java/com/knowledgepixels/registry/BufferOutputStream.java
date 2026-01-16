package com.knowledgepixels.registry;

import io.vertx.core.buffer.Buffer;

import java.io.OutputStream;

/**
 * A custom implementation of {@link OutputStream} that writes data to a Vert.x {@link Buffer}.
 */
public class BufferOutputStream extends OutputStream {

    private final Buffer buffer;

    /**
     * Constructs a new BufferOutputStream with an empty buffer.
     */
    public BufferOutputStream() {
        buffer = Buffer.buffer();
    }

    /**
     * Returns a copy of the internal buffer.
     * Modifications to the returned buffer will not affect the original buffer.
     *
     * @return a copy of the internal {@link Buffer}.
     */
    public Buffer getBuffer() {
        return this.buffer.copy();
    }

    /**
     * Writes a single byte to the buffer.
     *
     * @param b the byte to write. Only the lower 8 bits of the integer are written.
     */
    @Override
    public void write(int b) {
        buffer.appendByte((byte) (b & 0xFF));
    }

    /**
     * Writes an array of bytes to the buffer.
     *
     * @param bytes the byte array to write.
     */
    @Override
    public void write(byte[] bytes) {
        buffer.appendBytes(bytes);
    }

    /**
     * Writes a portion of a byte array to the buffer.
     *
     * @param bytes  the byte array to write from.
     * @param offset the starting offset in the array.
     * @param len    the number of bytes to write.
     */
    @Override
    public void write(byte[] bytes, int offset, int len) {
        buffer.appendBytes(bytes, offset, len);
    }

}