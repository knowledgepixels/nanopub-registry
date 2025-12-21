package com.knowledgepixels.registry;

import io.vertx.core.buffer.Buffer;

import java.io.OutputStream;

public class BufferOutputStream extends OutputStream {

    private Buffer buffer;

    public BufferOutputStream() {
        buffer = Buffer.buffer();
    }

    public Buffer getBuffer() {
        return this.buffer.copy();
    }

    @Override
    public void write(int b) {
        buffer.appendByte((byte) (b & 0xFF));
    }

    @Override
    public void write(byte[] bytes) {
        buffer.appendBytes(bytes);
    }

    @Override
    public void write(byte[] bytes, int offset, int len) {
        buffer.appendBytes(bytes, offset, len);
    }

}