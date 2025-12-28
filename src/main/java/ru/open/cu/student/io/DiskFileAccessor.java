package ru.open.cu.student.io;

import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;

public class DiskFileAccessor implements FileAccessor {
    @Override
    public void ensureFileExistsWithEmptyPage(Path path, int pageSize) throws IOException {
        if (!Files.exists(path)) {
            Files.createFile(path);
            try (RandomAccessFile raf = new RandomAccessFile(path.toFile(), "rw")) {
                raf.setLength(0);
                raf.write(new byte[pageSize]);
            }
        } else {
            // if exists but empty, initialize with one page
            try (RandomAccessFile raf = new RandomAccessFile(path.toFile(), "rw")) {
                if (raf.length() == 0) {
                    raf.write(new byte[pageSize]);
                }
            }
        }
    }

    @Override
    public byte[] readPage(Path path, int pageId, int pageSize) throws IOException {
        if (!Files.exists(path)) {
            return new byte[pageSize];
        }
        try (RandomAccessFile raf = new RandomAccessFile(path.toFile(), "r")) {
            long offset = ((long) pageId) * pageSize;
            if (offset >= raf.length()) {
                return new byte[pageSize];
            }
            raf.seek(offset);
            byte[] page = new byte[pageSize];
            int read = raf.read(page);
            if (read < pageSize) {
                Arrays.fill(page, read < 0 ? 0 : read, pageSize, (byte) 0);
            }
            return page;
        }
    }

    @Override
    public void writePage(Path path, int pageId, byte[] page) throws IOException {
        try (RandomAccessFile raf = new RandomAccessFile(path.toFile(), "rw")) {
            long offset = ((long) pageId) * page.length;
            raf.seek(offset);
            raf.write(page);
        }
    }

    @Override
    public int getPageCount(Path path, int pageSize) throws IOException {
        if (!Files.exists(path)) return 0;
        long len = Files.size(path);
        return (int) (len / pageSize);
    }

    @Override
    public int appendNewPage(Path path, byte[] page) throws IOException {
        try (RandomAccessFile raf = new RandomAccessFile(path.toFile(), "rw")) {
            long pages = raf.length() / page.length;
            raf.seek(raf.length());
            raf.write(page);
            return (int) pages;
        }
    }

    @Override
    public boolean deleteIfExists(Path path) throws IOException {
        return Files.deleteIfExists(path);
    }
}

