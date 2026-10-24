package com.yodesla.omniverse.player;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.zip.DataFormatException;
import java.util.zip.Inflater;

/** Expands Matroska frame compression in memory; never changes file offsets or stored media. */
final class MatroskaZlibBlocks {
  static final int MAX_BLOCK_BYTES = 32 * 1024 * 1024;

  static long trackNumber(byte[] bytes) throws IOException {
    return new Cursor(bytes).vint();
  }

  static byte[] expand(byte[] block) throws IOException {
    Cursor cursor = new Cursor(block);
    cursor.vint();
    int trackBytes = cursor.position;
    cursor.next(); // Relative timestamp, high byte.
    cursor.next();
    int flags = cursor.next();
    int lacing = (flags & 6) >> 1;
    int count = lacing == 0 ? 1 : cursor.next() + 1;
    int[] sizes = new int[count];
    long total = 0;
    if (lacing == 2) {
      int remaining = block.length - cursor.position;
      if (remaining % count != 0) throw new IOException("Invalid fixed Matroska lacing");
      for (int i = 0; i < count; i++) sizes[i] = remaining / count;
    } else {
      for (int i = 0; i < count - 1; i++) {
        long size;
        if (lacing == 1) {
          size = 0;
          int value;
          do { value = cursor.next(); size += value; } while (value == 255);
        } else if (lacing == 3) {
          int start = cursor.position;
          size = cursor.vint();
          if (i > 0) {
            int width = cursor.position - start;
            size += sizes[i - 1] - ((1L << (7 * width - 1)) - 1);
          }
        } else {
          throw new IOException("Invalid Matroska lacing");
        }
        if (size < 0 || size > block.length) throw new IOException("Invalid Matroska frame size");
        sizes[i] = (int) size;
        total += size;
      }
      long last = block.length - cursor.position - total;
      if (last < 0) throw new IOException("Matroska lacing exceeds block");
      sizes[count - 1] = (int) last;
    }
    byte[][] frames = new byte[count][];
    int expandedSize = trackBytes + 3 + (lacing == 0 ? 0 : 1);
    for (int i = 0; i < count; i++) {
      frames[i] = inflate(block, cursor.position, sizes[i], MAX_BLOCK_BYTES - expandedSize);
      cursor.position += sizes[i];
      expandedSize += frames[i].length;
      if (lacing != 0 && i < count - 1) expandedSize += frames[i].length / 255 + 1;
      if (expandedSize > MAX_BLOCK_BYTES) throw new IOException("Expanded Matroska block too large");
    }
    ByteArrayOutputStream out = new ByteArrayOutputStream(expandedSize);
    out.write(block, 0, trackBytes + 2);
    // Re-express lacing as Xiph, since inflated frames need not have equal sizes.
    out.write((flags & ~6) | (lacing == 0 ? 0 : 2));
    if (lacing != 0) {
      out.write(count - 1);
      for (int i = 0; i < count - 1; i++) {
        int size = frames[i].length;
        while (size >= 255) { out.write(255); size -= 255; }
        out.write(size);
      }
    }
    for (byte[] frame : frames) out.write(frame, 0, frame.length);
    return out.toByteArray();
  }

  private static byte[] inflate(byte[] data, int offset, int length, int limit) throws IOException {
    if (limit < 0) throw new IOException("Expanded Matroska block too large");
    Inflater inflater = new Inflater();
    try {
      inflater.setInput(data, offset, length);
      ByteArrayOutputStream out = new ByteArrayOutputStream(Math.min(length, 8192));
      byte[] buffer = new byte[8192];
      while (!inflater.finished()) {
        int read = inflater.inflate(buffer);
        if (read > limit - out.size()) throw new IOException("Expanded Matroska block too large");
        out.write(buffer, 0, read);
        if (read == 0 && !inflater.finished()) throw new IOException("Incomplete or dictionary-based Matroska zlib frame");
      }
      if (inflater.getRemaining() != 0) throw new IOException("Trailing bytes in Matroska zlib frame");
      return out.toByteArray();
    } catch (DataFormatException error) {
      throw new IOException("Invalid Matroska zlib frame", error);
    } finally {
      inflater.end();
    }
  }

  private static final class Cursor {
    final byte[] bytes;
    int position;
    Cursor(byte[] bytes) { this.bytes = bytes; }
    int next() throws IOException {
      if (position >= bytes.length) throw new IOException("Truncated Matroska block");
      return bytes[position++] & 255;
    }
    long vint() throws IOException {
      int first = next();
      int mask = 128;
      int width = 1;
      while (mask != 0 && (first & mask) == 0) { mask >>= 1; width++; }
      if (mask == 0) throw new IOException("Invalid Matroska vint");
      long value = first & ~mask;
      for (int i = 1; i < width; i++) value = (value << 8) | next();
      return value;
    }
  }
}
