package com.pairforge.worker.sandbox;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.*;

/** One shared byte budget across both streams and all execution phases. */
public final class BoundedOutputCollector {
    private final int limit;
    private final ByteArrayOutputStream out = new ByteArrayOutputStream(), err = new ByteArrayOutputStream();
    private boolean overflow;
    public BoundedOutputCollector(int limit) {
        if (limit < 1 || limit > 262144) throw new IllegalArgumentException("Invalid capture limit");
        this.limit = limit;
    }
    public synchronized void append(boolean stderr, byte[] bytes, int length) {
        int retain = Math.min(length, limit - out.size() - err.size());
        (stderr ? err : out).write(bytes, 0, retain);
        if (retain < length) overflow = true;
    }
    public synchronized boolean overflow() { return overflow; }
    public synchronized String stdout() { return text(out.toByteArray()); }
    public synchronized String stderr() { return text(err.toByteArray()); }
    // Invalid UTF-8 and partial trailing codepoints are discarded, not expanded past the byte budget.
    private static String text(byte[] bytes) {
        try {
            return StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.IGNORE)
                    .onUnmappableCharacter(CodingErrorAction.IGNORE).decode(ByteBuffer.wrap(bytes)).toString().replace("\0", "");
        } catch (CharacterCodingException impossible) { throw new IllegalStateException(impossible); }
    }
}
