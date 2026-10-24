package com.yodesla.omniverse.player;

import androidx.media3.common.ParserException;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.extractor.DefaultExtractorInput;
import androidx.media3.extractor.ExtractorInput;
import androidx.media3.extractor.mkv.MatroskaExtractor;
import androidx.media3.extractor.text.SubtitleParser;
import java.io.IOException;
import java.util.HashSet;
import java.util.Set;

/** Media3 1.11.1 plus Matroska ContentCompAlgo=0 (zlib), notably Blu-ray PGS tracks. */
@UnstableApi
public final class ZlibMatroskaExtractor extends MatroskaExtractor {
  private static final int TRACK_ENTRY = 0xAE;
  private static final int CONTENT_COMPRESSION = 0x5034;
  private static final int COMPRESSION_ALGORITHM = 0x4254;
  private static final int COMPRESSION_SETTINGS = 0x4255;
  private static final int SIMPLE_BLOCK = 0xA3;
  private static final int BLOCK = 0xA1;
  private final Set<Long> zlibTracks = new HashSet<>();
  private int compression = -1;

  public ZlibMatroskaExtractor(SubtitleParser.Factory parserFactory, int flags) {
    super(parserFactory, flags);
  }

  @Override
  protected void startMasterElement(int id, long position, long size) throws ParserException {
    super.startMasterElement(id, position, size);
    if (id == TRACK_ENTRY) compression = -1;
    // Matroska defines zlib as the default when ContentCompAlgo is omitted.
    if (id == CONTENT_COMPRESSION) compression = 0;
  }

  @Override
  protected void integerElement(int id, long value) throws ParserException {
    if (id == COMPRESSION_ALGORITHM && value == 0) {
      getCurrentTrack(id); // Keep Media3's track-entry validation.
      compression = 0;
      return;
    }
    super.integerElement(id, value);
    if (id == COMPRESSION_ALGORITHM) compression = (int) value;
  }

  @Override
  protected void endMasterElement(int id) throws ParserException {
    if (id == TRACK_ENTRY) {
      Track track = getCurrentTrack(id);
      zlibTracks.remove((long) track.number);
      if (compression == 0) {
        if (track.hasContentEncryption || track.sampleStrippedBytes != null) {
          throw ParserException.createForMalformedContainer("Unsupported combined Matroska compression", null);
        }
        zlibTracks.add((long) track.number);
      }
    }
    super.endMasterElement(id);
  }

  @Override
  protected void binaryElement(int id, int size, ExtractorInput input) throws IOException {
    if (id == COMPRESSION_SETTINGS && compression == 0) {
      getCurrentTrack(id);
      if (size != 0) throw ParserException.createForMalformedContainer("Unexpected Matroska zlib settings", null);
      return;
    }
    if ((id != SIMPLE_BLOCK && id != BLOCK) || zlibTracks.isEmpty()) {
      super.binaryElement(id, size, input);
      return;
    }
    byte[] prefix = new byte[Math.min(size, 8)];
    input.peekFully(prefix, 0, prefix.length);
    input.resetPeekPosition();
    if (!zlibTracks.contains(MatroskaZlibBlocks.trackNumber(prefix))) {
      super.binaryElement(id, size, input);
      return;
    }
    if (size > MatroskaZlibBlocks.MAX_BLOCK_BYTES) {
      throw ParserException.createForMalformedContainer("Compressed Matroska block too large", null);
    }
    byte[] block = new byte[size];
    input.readFully(block, 0, size); // Preserve network IO failures for the normal retry policy.
    byte[] expanded;
    try {
      expanded = MatroskaZlibBlocks.expand(block);
    } catch (IOException error) {
      throw ParserException.createForMalformedContainer("Cannot expand Matroska zlib block", error);
    }
    int[] offset = {0};
    DefaultExtractorInput frameInput = new DefaultExtractorInput((target, start, length) -> {
      if (length == 0) return 0;
      int count = Math.min(length, expanded.length - offset[0]);
      if (count == 0) return -1;
      System.arraycopy(expanded, offset[0], target, start, count);
      offset[0] += count;
      return count;
    }, 0, expanded.length);
    // Only this block is virtual. The EBML reader still sees original file positions,
    // so cues, HTTP range requests, BlockGroup metadata and seeking remain intact.
    super.binaryElement(id, expanded.length, frameInput);
  }
}
