package com.knowledgepixels.registry;

import io.vertx.core.buffer.Buffer;

import java.io.IOException;
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
    public void write(int b) throws IOException {
        buffer.appendByte((byte) (b & 0xFF));
    }

    @Override
    public void write(byte[] bytes) throws IOException {
        buffer.appendBytes(bytes);
    }

    @Override
    public void write(byte[] bytes, int offset, int len) throws IOException {
        buffer.appendBytes(bytes, offset, len);
    }

}