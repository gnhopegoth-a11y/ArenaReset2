package com.example.arenareset;

import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.data.BlockData;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

final class Arena {

    static final int MAX_BLOCKS = 50_000_000;

    final String name;
    final String worldName;
    final int minX, minY, minZ;
    final int sizeX, sizeY, sizeZ;
    final int intervalSeconds;

    /** Unique block states from the snapshot. */
    private final List<BlockData> palette;
    /** One palette index per block. Order: x fastest, then z, then y. */
    private final char[] data;

    int secondsLeft;
    private int cursor = -1; // -1 = idle, otherwise next block index to restore

    private Arena(String name, String worldName, int minX, int minY, int minZ,
                  int sx, int sy, int sz, int intervalSeconds,
                  List<BlockData> palette, char[] data) {
        this.name = name;
        this.worldName = worldName;
        this.minX = minX;
        this.minY = minY;
        this.minZ = minZ;
        this.sizeX = sx;
        this.sizeY = sy;
        this.sizeZ = sz;
        this.intervalSeconds = intervalSeconds;
        this.secondsLeft = intervalSeconds;
        this.palette = palette;
        this.data = data;
    }

    // ---------- snapshot ----------

    static Arena capture(String name, World world,
                         int minX, int minY, int minZ,
                         int maxX, int maxY, int maxZ, int intervalSeconds) {
        int sx = maxX - minX + 1, sy = maxY - minY + 1, sz = maxZ - minZ + 1;
        long total = (long) sx * sy * sz;
        if (total > MAX_BLOCKS) {
            throw new IllegalArgumentException("Selection too big (" + total + " blocks, max " + MAX_BLOCKS + ").");
        }

        List<BlockData> palette = new ArrayList<>();
        Map<String, Integer> lookup = new HashMap<>();
        char[] data = new char[(int) total];

        int i = 0;
        for (int y = minY; y <= maxY; y++) {
            for (int z = minZ; z <= maxZ; z++) {
                for (int x = minX; x <= maxX; x++) {
                    BlockData bd = world.getBlockAt(x, y, z).getBlockData();
                    String key = bd.getAsString();
                    Integer idx = lookup.get(key);
                    if (idx == null) {
                        idx = palette.size();
                        palette.add(bd);
                        lookup.put(key, idx);
                    }
                    data[i++] = (char) idx.intValue();
                }
            }
        }
        return new Arena(name, world.getName(), minX, minY, minZ, sx, sy, sz, intervalSeconds, palette, data);
    }

    void save(File dir) throws IOException {
        File f = new File(dir, name + ".snap");
        try (DataOutputStream out = new DataOutputStream(new BufferedOutputStream(
                new GZIPOutputStream(new FileOutputStream(f))))) {
            out.writeUTF(worldName);
            out.writeInt(minX);
            out.writeInt(minY);
            out.writeInt(minZ);
            out.writeInt(sizeX);
            out.writeInt(sizeY);
            out.writeInt(sizeZ);
            out.writeInt(intervalSeconds);
            out.writeInt(palette.size());
            for (BlockData bd : palette) out.writeUTF(bd.getAsString());
            for (char c : data) out.writeChar(c);
        }
    }

    static Arena load(File f) throws IOException {
        String fileName = f.getName();
        String name = fileName.substring(0, fileName.length() - ".snap".length());
        try (DataInputStream in = new DataInputStream(new BufferedInputStream(
                new GZIPInputStream(new FileInputStream(f))))) {
            String world = in.readUTF();
            int mx = in.readInt(), my = in.readInt(), mz = in.readInt();
            int sx = in.readInt(), sy = in.readInt(), sz = in.readInt();
            int interval = in.readInt();
            int n = in.readInt();

            List<BlockData> palette = new ArrayList<>(n);
            for (int i = 0; i < n; i++) {
                palette.add(Bukkit.createBlockData(in.readUTF()));
            }
            char[] data = new char[sx * sy * sz];
            for (int i = 0; i < data.length; i++) {
                data[i] = in.readChar();
            }
            return new Arena(name, world, mx, my, mz, sx, sy, sz, interval, palette, data);
        }
    }

    // ---------- reset ----------

    boolean isResetting() {
        return cursor >= 0;
    }

    void begin() {
        if (cursor < 0) cursor = 0;
    }

    void cancel() {
        cursor = -1;
    }

    /** Restores blocks until the deadline passes or the arena is finished. */
    void step(World w, long deadline) {
        int total = sizeX * sizeY * sizeZ;
        int work = 0;
        while (cursor < total) {
            int i = cursor++;
            int x = i % sizeX;
            int rest = i / sizeX;
            int z = rest % sizeZ;
            int y = rest / sizeZ;

            Block b = w.getBlockAt(minX + x, minY + y, minZ + z);
            BlockData want = palette.get(data[i]);
            if (!b.getBlockData().equals(want)) {
                b.setBlockData(want, false); // false = no physics, so nothing cascades
            }

            if ((++work & 255) == 0 && System.nanoTime() >= deadline) {
                return; // continue next tick
            }
        }
        cursor = -1; // done
    }
}
