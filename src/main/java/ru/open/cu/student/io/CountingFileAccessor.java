package ru.open.cu.student.io;

import java.io.IOException;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Простая оболочка над FileAccessor, считающая обращения для тестов.
 */
public class CountingFileAccessor implements FileAccessor {
    private final FileAccessor delegate;
    private final AtomicInteger readPageCount = new AtomicInteger(0);
    private final AtomicInteger writePageCount = new AtomicInteger(0);
    private final AtomicInteger appendPageCount = new AtomicInteger(0);

    public CountingFileAccessor() {
        this(new DiskFileAccessor());
    }

    public CountingFileAccessor(FileAccessor delegate) {
        this.delegate = delegate;
    }

    @Override
    public void ensureFileExistsWithEmptyPage(Path path, int pageSize) throws IOException {
        delegate.ensureFileExistsWithEmptyPage(path, pageSize);
    }

    @Override
    public byte[] readPage(Path path, int pageId, int pageSize) throws IOException {
        readPageCount.incrementAndGet();
        return delegate.readPage(path, pageId, pageSize);
    }

    @Override
    public void writePage(Path path, int pageId, byte[] page) throws IOException {
        writePageCount.incrementAndGet();
        delegate.writePage(path, pageId, page);
    }

    @Override
    public int getPageCount(Path path, int pageSize) throws IOException {
        return delegate.getPageCount(path, pageSize);
    }

    @Override
    public int appendNewPage(Path path, byte[] page) throws IOException {
        appendPageCount.incrementAndGet();
        return delegate.appendNewPage(path, page);
    }

    @Override
    public boolean deleteIfExists(Path path) throws IOException {
        return delegate.deleteIfExists(path);
    }

    public int getReadCount() { return readPageCount.get(); }

}
