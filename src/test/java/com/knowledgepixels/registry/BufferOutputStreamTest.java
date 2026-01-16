package com.knowledgepixels.registry;

import io.vertx.core.buffer.Buffer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

class BufferOutputStreamTest {

    private BufferOutputStream outputStream;

    @BeforeEach
    void setUp() {
        outputStream = new BufferOutputStream();
    }

    @Test
    void construct() {
        assertNotNull(outputStream.getBuffer());
        assertEquals(0, outputStream.getBuffer().length());
    }

    @Test
    void writeSingleByteToBuffer() {
        outputStream.write(65); // ASCII for 'A'
        assertEquals("A", outputStream.getBuffer().toString());
    }

    @Test
    void writeByteArrayToBuffer() {
        byte[] data = "Hello".getBytes();
        outputStream.write(data);
        assertEquals("Hello", outputStream.getBuffer().toString());
    }

    @Test
    void writeByteArrayWithOffsetAndLengthToBuffer() {
        byte[] data = "HelloWorld".getBytes();
        outputStream.write(data, 5, 5); // Write "World"
        assertEquals("World", outputStream.getBuffer().toString());
    }

    @Test
    void getBufferReturnsCopyNotOriginal() {
        outputStream.write(65); // ASCII for 'A'
        Buffer bufferCopy = outputStream.getBuffer();
        bufferCopy.appendString("B");
        assertEquals("A", outputStream.getBuffer().toString());
    }

    @Test
    void writeEmptyByteArrayDoesNotChangeBuffer() {
        byte[] emptyData = new byte[0];
        outputStream.write(emptyData);
        assertEquals("", outputStream.getBuffer().toString());
    }

}