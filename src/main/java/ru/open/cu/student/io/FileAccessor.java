package ru.open.cu.student.io;

import java.io.IOException;
import java.nio.file.Path;

public interface FileAccessor {
    void ensureFileExistsWithEmptyPage(Path path, int pageSize) throws IOException;
    byte[] readPage(Path path, int pageId, int pageSize) throws IOException;
    void writePage(Path path, int pageId, byte[] page) throws IOException;
    int getPageCount(Path path, int pageSize) throws IOException;
    int appendNewPage(Path path, byte[] page) throws IOException; // returns new page id
    boolean deleteIfExists(Path path) throws IOException;
}

