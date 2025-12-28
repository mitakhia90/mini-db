package ru.open.cu.student.memory.buffer;

import ru.open.cu.student.memory.manager.PageFileManager;
import ru.open.cu.student.memory.model.BufferSlot;
import ru.open.cu.student.memory.page.Page;
import ru.open.cu.student.memory.replacer.Replacer;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

public class DefaultBufferPoolManager implements BufferPoolManager {

    private final int poolSize;
    private final PageFileManager pgManager;
    private final Path dataPath;


    private final Replacer primaryReplacer;
    private final Replacer secondaryReplacer;

    private final Map<Integer, BufferSlot> store = new HashMap<>();

    public DefaultBufferPoolManager(int poolSize, PageFileManager pgManager,
                                    Replacer primaryReplacer, Replacer secondaryReplacer,
                                    Path dataPath) {
        this.poolSize = poolSize;
        this.pgManager = pgManager;
        this.primaryReplacer = primaryReplacer;
        this.secondaryReplacer = secondaryReplacer;
        this.dataPath = dataPath;
    }

    @Override
    public BufferSlot getPage(int pageId) {

        if (store.containsKey(pageId)) {
            BufferSlot slot = store.get(pageId);
            slot.incrementUsage();

            updateReplacers(slot);
            return slot;
        }

        if (store.size() >= poolSize) {
            evictPage();
        }

        Page page;
        try {
            // Предварительная проверка: если файла нет или запрашиваемая страница выходит за пределы файла — вернуть null
            try {
                if (dataPath == null || !Files.exists(dataPath)) {
                    return null;
                }
                long fileSize = Files.size(dataPath);
                long position = ((long) pageId) * ru.open.cu.student.memory.page.HeapPage.PAGE_SIZE;
                if (position >= fileSize) {
                    return null;
                }
            } catch (Exception ignore) {
                // если не удалось проверить — продолжим и позволим pgManager.read бросить исключение
            }

            page = pgManager.read(pageId, dataPath);
        } catch (IllegalArgumentException e) {
            return null;
        }
        BufferSlot newSlot = new BufferSlot(pageId, page);
        store.put(pageId, newSlot);

        if (!newSlot.isPinned()) {
            primaryReplacer.push(newSlot);
            secondaryReplacer.push(newSlot);
        }

        return newSlot;
    }

    @Override
    public void updatePage(int pageId, Page page) {
        if (!store.containsKey(pageId)) {
            throw new IllegalArgumentException("Page not in buffer: " + pageId);
        }

        BufferSlot slot = store.get(pageId);
        slot.setPage(page);
        slot.setDirty(true);

        updateReplacers(slot);
    }

    @Override
    public void pinPage(int pageId) {
        BufferSlot slot = store.get(pageId);
        if (slot == null) {
            throw new IllegalArgumentException("Page not found in buffer: " + pageId);
        }

        slot.setPinned(true);
        primaryReplacer.delete(pageId);
        secondaryReplacer.delete(pageId);
    }

    public void unpinPage(int pageId) {
        BufferSlot slot = store.get(pageId);
        if (slot == null) {
            throw new IllegalArgumentException("Page not found in buffer: " + pageId);
        }

        slot.setPinned(false);
        primaryReplacer.push(slot);
        secondaryReplacer.push(slot);
    }

    @Override
    public void flushPage(int pageId) {
        BufferSlot slot = store.get(pageId);
        if (slot == null) {
            return;
        }

        if (slot.isDirty()) {
            pgManager.write(slot.getPage(), dataPath);
            slot.setDirty(false);
        }
    }

    @Override
    public void flushAllPages() {
        for (BufferSlot slot : store.values()) {
            if (slot.isDirty()) {
                pgManager.write(slot.getPage(), dataPath);
                slot.setDirty(false);
            }
        }
    }

    @Override
    public List<BufferSlot> getDirtyPages() {
        return store.values().stream()
                .filter(BufferSlot::isDirty)
                .collect(Collectors.toList());
    }

    private void evictPage() {
        BufferSlot victim = primaryReplacer.pickVictim();
        if (victim == null) {
            victim = secondaryReplacer.pickVictim();
        }

        if (victim == null) {
            throw new IllegalStateException("No victim found and buffer is full");
        }

        if (victim.isDirty()) {
            pgManager.write(victim.getPage(), dataPath);
        }

        store.remove(victim.getPageId());
        primaryReplacer.delete(victim.getPageId());
        secondaryReplacer.delete(victim.getPageId());
    }

    private void updateReplacers(BufferSlot slot) {
        if (!slot.isPinned()) {
            primaryReplacer.push(slot);
            secondaryReplacer.push(slot);
        }
    }
}