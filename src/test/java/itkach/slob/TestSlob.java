package itkach.slob;

import static org.testng.AssertJUnit.assertEquals;
import static org.testng.AssertJUnit.assertTrue;
import static org.testng.AssertJUnit.fail;

import org.testng.annotations.Test;

import java.io.IOException;
import java.io.RandomAccessFile;
import java.net.URL;
import java.nio.ByteBuffer;
import java.nio.MappedByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.WritableByteChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.text.Collator;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

public class TestSlob {

    private static final int HEADER_FIXED_PREFIX_SIZE = 8 + 16;

    @Test
    public void signedToUnsignedByteConversion() {
        assertEquals(0, Slob.toUnsignedByte((byte)0));
        assertEquals(127, Slob.toUnsignedByte((byte)127));
        assertEquals(255, Slob.toUnsignedByte((byte)-1));
        assertEquals(128, Slob.toUnsignedByte((byte)-128));
    }

    @Test
    public void signedToUnsignedShortConversion() {
        assertEquals(0, Slob.toUnsignedShort(new byte[]{0, 0}));
        assertEquals(Short.MAX_VALUE, Slob.toUnsignedShort(new byte[]{127, -1}));
        assertEquals((int)Short.MAX_VALUE + 1, Slob.toUnsignedShort(new byte[]{-128, 0}));
        assertEquals(2*Short.MAX_VALUE + 1, Slob.toUnsignedShort(new byte[]{-1, -1}));
    }

    @Test
    public void signedToUnsignedIntConversion() {
        assertEquals(0, Slob.toUnsignedInt(new byte[]{0, 0, 0, 0}, 0));
        assertEquals(0, Slob.toUnsignedInt(new byte[]{1, 0, 0, 0, 0}, 1));
        assertEquals(Integer.MAX_VALUE, Slob.toUnsignedInt(new byte[]{127, -1, -1, -1}, 0));
        assertEquals((long)Integer.MAX_VALUE + 1L, Slob.toUnsignedInt(new byte[]{-128, 0, 0, 0}, 0));
        assertEquals(2L*Integer.MAX_VALUE + 1L, Slob.toUnsignedInt(new byte[]{-1, -1, -1, -1}, 0));
    }

    @Test
    public void uuidConversion() {
        byte[] bytes = {-122, -72, -118, -93, 13, 121, 68, 3, -81, 97, -14, 17, 123, 65, 82, 12};
        UUID uuid = UUID.fromString("86b88aa3-0d79-4403-af61-f2117b41520c");
        assertEquals(uuid, Slob.uuid(bytes));
    }

    @Test
    public void binarySearch() {
        List<String> list = Arrays.asList(new String[]{"a", "b", "c", "x", "y"});
        Collator usCollator = Collator.getInstance(Locale.US);
        assertEquals(0, Slob.binarySearch(list, "a", usCollator));
        assertEquals(0, Slob.binarySearch(list, "9", usCollator));
        assertEquals(5, Slob.binarySearch(list, "z", usCollator));
        assertEquals(4, Slob.binarySearch(list, "y", usCollator));
        assertEquals(2, Slob.binarySearch(list, "c", usCollator));
    }

    @Test
    public void readSlob() throws IOException {
        String testSlobName = "test.slob";
        ClassLoader classLoader = getClass().getClassLoader();
        URL resource = classLoader.getResource(testSlobName);
        RandomAccessFile f = new RandomAccessFile(resource.getFile(), "r");
        Slob s = new Slob(f.getChannel(), testSlobName);

        assertEquals(2, s.getBlobCount());
        assertEquals(4, s.size());

        Slob.Blob earthBlob = Slob.find("earth", s).next();
        assertEquals("text/plain; charset=utf-8", earthBlob.getContentType());

        Slob.Content content = earthBlob.getContent();
        assertEquals(earthBlob.getContentType(), content.type);

        byte[] contentBytes = new byte[content.data.remaining()];
        content.data.get(contentBytes);
        String contentAsText = new String(contentBytes, s.header.encoding);
        assertEquals("Hello, Earth!", contentAsText);

        Map<String, String> tags = s.getTags();
        assertEquals("xyz", tags.get("sometag"));
        assertEquals("abc", tags.get("some.other.tag"));
    }

    @Test
    public void readUncompressedSlob() throws Exception {
        Path path = makeUncompressedCopy();
        try (RandomAccessFile f = new RandomAccessFile(path.toFile(), "r")) {
            Slob s = new Slob(f.getChannel(), path.toString());
            Slob.Blob earthBlob = Slob.find("earth", s).next();
            assertEquals("Hello, Earth!", readText(earthBlob.getContent(), s.header.encoding));
        } finally {
            Files.deleteIfExists(path);
        }
    }

    @Test
    public void readsCompatibilityCorpus() throws Exception {
        String[] fixtures = {"empty", "uncompressed", "zlib", "lzma2", "unicode", "aliases",
                "duplicate-keys", "multiple-content-types", "fragments", "large-bin"};
        for (String fixture : fixtures) {
            URL resource = getClass().getClassLoader().getResource("fixtures/" + fixture + ".slob");
            try (RandomAccessFile f = new RandomAccessFile(resource.getFile(), "r")) {
                Slob slob = new Slob(f.getChannel(), fixture);
                for (Slob.Blob blob : slob) {
                    assertTrue("Missing key in " + fixture, blob.key.length() > 0);
                    assertTrue("Missing fragment in " + fixture, blob.fragment != null);
                    assertTrue("Missing content type in " + fixture,
                            blob.getContentType().length() > 0);
                    Slob.Content content = blob.getContent();
                    assertEquals(blob.getContentType(), content.type);
                    assertTrue("Unreadable payload in " + fixture, content.data.remaining() >= 0);
                }
            }
        }
    }

    @Test
    public void compatibilityCorpusPreservesAliasesAndFragments() throws Exception {
        URL aliases = getClass().getClassLoader().getResource("fixtures/aliases.slob");
        try (RandomAccessFile f = new RandomAccessFile(aliases.getFile(), "r")) {
            Slob slob = new Slob(f.getChannel(), "aliases");
            assertEquals(3, slob.size());
            assertEquals(slob.get(0).id, slob.get(1).id);
            assertEquals(slob.get(1).id, slob.get(2).id);
        }
        URL fragments = getClass().getClassLoader().getResource("fixtures/fragments.slob");
        try (RandomAccessFile f = new RandomAccessFile(fragments.getFile(), "r")) {
            Slob slob = new Slob(f.getChannel(), "fragments");
            assertEquals("section", slob.get(0).fragment);
        }
    }

    @Test
    public void corruptedCompatibilityCorpusFailsWithExpectedType() throws Exception {
        assertCorruptedFixture("invalid-magic.slob", Slob.UnknownFileFormatException.class);
        assertCorruptedFixture("truncated-header.slob", Slob.TruncatedFileException.class);
        assertCorruptedFixture("truncated-ref-table.slob", Slob.TruncatedFileException.class);
        assertCorruptedFixture("truncated-store.slob", Slob.TruncatedFileException.class);
        assertCorruptedFixture("unknown-compression.slob", Slob.UnknownCompressionException.class);
        assertCorruptedFixture("invalid-bin-index.slob", IndexOutOfBoundsException.class);
        assertCorruptedFixture("invalid-item-index.slob", IndexOutOfBoundsException.class);
        assertCorruptedFixture("invalid-content-type.slob", RuntimeException.class);
        assertCorruptedFixture("corrupted-zlib.slob", RuntimeException.class);
        assertCorruptedFixture("corrupted-lzma2.slob", RuntimeException.class);
    }

    @Test
    public void unknownCompressionIsReportedWhenOpeningFile() throws Exception {
        Path path = Files.createTempFile("unknown-compression", ".slob");
        try {
            byte[] bytes = testSlobBytes();
            int compressionStart = compressionStart(bytes);
            System.arraycopy("abcde".getBytes("UTF-8"), 0, bytes, compressionStart + 1, 5);
            Files.write(path, bytes);

            try (RandomAccessFile f = new RandomAccessFile(path.toFile(), "r")) {
                try {
                    new Slob(f.getChannel(), path.toString());
                } catch (Slob.UnknownCompressionException e) {
                    assertEquals("Unsupported compression: abcde", e.getMessage());
                    return;
                }
            }
            throw new AssertionError("Expected UnknownCompressionException");
        } finally {
            Files.deleteIfExists(path);
        }
    }

    @Test
    public void readerHandlesPartialPositionalReads() throws Exception {
        URL resource = getClass().getClassLoader().getResource("test.slob");
        try (RandomAccessFile f = new RandomAccessFile(resource.getFile(), "r");
             FileChannel partial = new PartialFileChannel(f.getChannel(), 2)) {
            Slob s = new Slob(partial, "partial-read-test");
            Slob.Blob earthBlob = Slob.find("earth", s).next();
            assertEquals("Hello, Earth!", readText(earthBlob.getContent(), s.header.encoding));
        }
    }

    private byte[] testSlobBytes() throws IOException {
        URL resource = getClass().getClassLoader().getResource("test.slob");
        return Files.readAllBytes(new java.io.File(resource.getFile()).toPath());
    }

    private void assertCorruptedFixture(String name, Class expected) throws Exception {
        URL resource = getClass().getClassLoader().getResource("fixtures/corrupted/" + name);
        try (RandomAccessFile f = new RandomAccessFile(resource.getFile(), "r")) {
            Slob slob = new Slob(f.getChannel(), name);
            for (Slob.Blob blob : slob) {
                blob.getContentType();
                blob.getContent();
            }
        } catch (Throwable error) {
            assertTrue(name + " raised " + error.getClass().getName(), expected.isInstance(error));
            return;
        }
        fail(name + " should not be readable");
    }

    private Path makeUncompressedCopy() throws Exception {
        byte[] source = testSlobBytes();
        int compressionStart = compressionStart(source);
        int compressionLength = source[compressionStart] & 0xff;
        int compressionEnd = compressionStart + 1 + compressionLength;
        long storeOffset = readLong(source, offsets(source).storeOffset);
        int store = (int) storeOffset;
        int storeCount = readInt(source, store);
        int storeDataOffset = store + 4 + storeCount * 8;
        long firstStoreItemOffset = readLong(source, store + 4);
        int storeItem = storeDataOffset + (int) firstStoreItemOffset;
        int itemCount = readInt(source, storeItem);
        int compressedLengthOffset = storeItem + 4 + itemCount;
        int compressedLength = readInt(source, compressedLengthOffset);
        int compressedContentOffset = compressedLengthOffset + 4;
        byte[] compressed = Arrays.copyOfRange(source, compressedContentOffset,
                compressedContentOffset + compressedLength);
        byte[] uncompressed = Slob.COMPRESSORS.get("lzma2").decompress(compressed);

        int headerDelta = 1 - (1 + compressionLength);
        int contentDelta = uncompressed.length - compressedLength;
        byte[] target = new byte[source.length + headerDelta + contentDelta];
        System.arraycopy(source, 0, target, 0, compressionStart);
        target[compressionStart] = 0;
        int beforeContentLength = compressedLengthOffset - compressionEnd;
        System.arraycopy(source, compressionEnd, target, compressionStart + 1, beforeContentLength);
        int targetCompressedLengthOffset = compressedLengthOffset + headerDelta;
        writeInt(target, targetCompressedLengthOffset, uncompressed.length);
        int targetContentOffset = targetCompressedLengthOffset + 4;
        System.arraycopy(uncompressed, 0, target, targetContentOffset, uncompressed.length);
        System.arraycopy(source, compressedContentOffset + compressedLength, target,
                targetContentOffset + uncompressed.length,
                source.length - compressedContentOffset - compressedLength);

        HeaderOffsets sourceOffsets = offsets(source);
        int targetStoreOffset = sourceOffsets.storeOffset + headerDelta;
        int targetSizeOffset = sourceOffsets.size + headerDelta;
        writeLong(target, targetStoreOffset, storeOffset + headerDelta);
        writeLong(target, targetSizeOffset, source.length + headerDelta + contentDelta);

        Path path = Files.createTempFile("uncompressed", ".slob");
        Files.write(path, target);
        return path;
    }

    private int compressionStart(byte[] bytes) {
        return HEADER_FIXED_PREFIX_SIZE + 1 + (bytes[HEADER_FIXED_PREFIX_SIZE] & 0xff);
    }

    private HeaderOffsets offsets(byte[] bytes) {
        int position = compressionStart(bytes);
        position += 1 + (bytes[position] & 0xff);
        int tagCount = bytes[position++] & 0xff;
        for (int i = 0; i < tagCount * 2; i++) {
            position += 1 + (bytes[position] & 0xff);
        }
        int contentTypeCount = bytes[position++] & 0xff;
        for (int i = 0; i < contentTypeCount; i++) {
            position += 2 + readShort(bytes, position);
        }
        position += 4;
        return new HeaderOffsets(position, position + 8);
    }

    private static int readShort(byte[] bytes, int offset) {
        return ((bytes[offset] & 0xff) << 8) | (bytes[offset + 1] & 0xff);
    }

    private static int readInt(byte[] bytes, int offset) {
        return (int) Slob.toUnsignedInt(bytes, offset);
    }

    private static long readLong(byte[] bytes, int offset) {
        return ByteBuffer.wrap(bytes, offset, 8).getLong();
    }

    private static void writeInt(byte[] bytes, int offset, int value) {
        ByteBuffer.wrap(bytes, offset, 4).putInt(value);
    }

    private static void writeLong(byte[] bytes, int offset, long value) {
        ByteBuffer.wrap(bytes, offset, 8).putLong(value);
    }

    private static String readText(Slob.Content content, String encoding) throws Exception {
        byte[] bytes = new byte[content.data.remaining()];
        content.data.get(bytes);
        return new String(bytes, encoding);
    }

    private static final class HeaderOffsets {
        final int storeOffset;
        final int size;

        HeaderOffsets(int storeOffset, int size) {
            this.storeOffset = storeOffset;
            this.size = size;
        }
    }

    private static final class PartialFileChannel extends FileChannel {
        private final FileChannel delegate;
        private final int maxRead;

        PartialFileChannel(FileChannel delegate, int maxRead) {
            this.delegate = delegate;
            this.maxRead = maxRead;
        }

        @Override public int read(ByteBuffer dst, long position) throws IOException {
            ByteBuffer buffer = ByteBuffer.allocate(Math.min(dst.remaining(), maxRead));
            int count = delegate.read(buffer, position);
            if (count > 0) {
                buffer.flip();
                dst.put(buffer);
            }
            return count;
        }
        @Override public int read(ByteBuffer dst) throws IOException { return read(dst, delegate.position()); }
        @Override public long read(ByteBuffer[] dsts, int offset, int length) throws IOException { return delegate.read(dsts, offset, length); }
        @Override public int write(ByteBuffer src) throws IOException { return delegate.write(src); }
        @Override public int write(ByteBuffer src, long position) throws IOException { return delegate.write(src, position); }
        @Override public long write(ByteBuffer[] srcs, int offset, int length) throws IOException { return delegate.write(srcs, offset, length); }
        @Override public long position() throws IOException { return delegate.position(); }
        @Override public FileChannel position(long newPosition) throws IOException { delegate.position(newPosition); return this; }
        @Override public long size() throws IOException { return delegate.size(); }
        @Override public FileChannel truncate(long size) throws IOException { delegate.truncate(size); return this; }
        @Override public void force(boolean metaData) throws IOException { delegate.force(metaData); }
        @Override public long transferTo(long position, long count, WritableByteChannel target) throws IOException { return delegate.transferTo(position, count, target); }
        @Override public long transferFrom(java.nio.channels.ReadableByteChannel src, long position, long count) throws IOException { return delegate.transferFrom(src, position, count); }
        @Override public MappedByteBuffer map(MapMode mode, long position, long size) throws IOException { return delegate.map(mode, position, size); }
        @Override public FileLock lock(long position, long size, boolean shared) throws IOException { return delegate.lock(position, size, shared); }
        @Override public FileLock tryLock(long position, long size, boolean shared) throws IOException { return delegate.tryLock(position, size, shared); }
        @Override protected void implCloseChannel() throws IOException { delegate.close(); }
    }
}
