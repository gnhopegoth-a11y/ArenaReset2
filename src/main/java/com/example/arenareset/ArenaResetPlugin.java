package com.example.arenareset;

import com.sk89q.worldedit.IncompleteRegionException;
import com.sk89q.worldedit.LocalSession;
import com.sk89q.worldedit.WorldEdit;
import com.sk89q.worldedit.bukkit.BukkitAdapter;
import com.sk89q.worldedit.math.BlockVector3;
import com.sk89q.worldedit.regions.Region;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.World;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class ArenaResetPlugin extends JavaPlugin {

    // Messages (edit here)
    private static final String HEADER = "&#9863E7ᴜɴѕᴛᴀʙʟᴇᴘᴠᴘ";
    private static final String COUNTDOWN_PREFIX = "&#9863E7ᴍᴀᴘ ʀᴏᴛᴀᴛɪᴏɴ ɪɴ ";
    private static final int WARN_FROM = 5;

    private static final Pattern HEX = Pattern.compile("&#([A-Fa-f0-9]{6})");

    private final Map<String, Arena> arenas = new LinkedHashMap<>();
    private File arenaDir;
    private long budgetNanos;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        budgetNanos = (long) (getConfig().getDouble("tick-budget-ms", 2.0) * 1_000_000);

        arenaDir = new File(getDataFolder(), "arenas");
        arenaDir.mkdirs();

        File[] files = arenaDir.listFiles((d, n) -> n.endsWith(".snap"));
        if (files != null) {
            for (File f : files) {
                try {
                    Arena a = Arena.load(f);
                    arenas.put(a.name, a);
                } catch (IOException e) {
                    getLogger().warning("Could not load " + f.getName() + ": " + e.getMessage());
                }
            }
        }

        getCommand("arena").setExecutor(this);
        Bukkit.getScheduler().runTaskTimer(this, this::secondTick, 20L, 20L);
        Bukkit.getScheduler().runTaskTimer(this, this::resetTick, 1L, 1L);
        getLogger().info("Loaded " + arenas.size() + " arena(s).");
    }

    // ---------- timing ----------

    /** Runs once per second: counts down and starts resets. */
    private void secondTick() {
        for (Arena a : arenas.values()) {
            a.secondsLeft--;
            if (a.secondsLeft > 0 && a.secondsLeft <= WARN_FROM) {
                announce(a.secondsLeft);
            }
            if (a.secondsLeft <= 0) {
                a.begin();
                a.secondsLeft = a.intervalSeconds;
            }
        }
    }

    /** Runs every tick: restores blocks within the time budget. */
    private void resetTick() {
        long deadline = System.nanoTime() + budgetNanos;
        for (Arena a : arenas.values()) {
            if (!a.isResetting()) continue;
            World w = Bukkit.getWorld(a.worldName);
            if (w == null) {
                a.cancel();
                continue;
            }
            a.step(w, deadline);
            if (System.nanoTime() >= deadline) break;
        }
    }

    private void announce(int n) {
        String countdown = COUNTDOWN_PREFIX + n;
        for (Player p : Bukkit.getOnlinePlayers()) {
            p.sendTitle(color(HEADER), color(countdown), 5, 25, 5);
        }
        if (n == WARN_FROM) {
            Bukkit.broadcastMessage(color(HEADER));
            Bukkit.broadcastMessage(color(countdown));
        }
    }

    // ---------- commands ----------

    @Override
    public boolean onCommand(CommandSender sender, Command cmd, String label, String[] args) {
        if (args.length == 0) {
            usage(sender);
            return true;
        }
        switch (args[0].toLowerCase()) {
            case "create" -> create(sender, args);
            case "reset" -> reset(sender, args);
            case "delete" -> delete(sender, args);
            case "list" -> list(sender);
            default -> usage(sender);
        }
        return true;
    }

    private void usage(CommandSender s) {
        s.sendMessage(ChatColor.YELLOW + "/arena create <name> <minutes>  - arena from your WE selection");
        s.sendMessage(ChatColor.YELLOW + "/arena reset <name>  - reset now");
        s.sendMessage(ChatColor.YELLOW + "/arena delete <name>");
        s.sendMessage(ChatColor.YELLOW + "/arena list");
    }

    private void create(CommandSender s, String[] args) {
        if (!(s instanceof Player p)) {
            s.sendMessage("Players only.");
            return;
        }
        if (args.length < 3) {
            usage(s);
            return;
        }
        String name = args[1].toLowerCase();
        int minutes;
        try {
            minutes = Math.max(1, Integer.parseInt(args[2]));
        } catch (NumberFormatException e) {
            s.sendMessage(ChatColor.RED + "Minutes must be a number.");
            return;
        }
        if (arenas.containsKey(name)) {
            s.sendMessage(ChatColor.RED + "An arena named " + name + " already exists.");
            return;
        }

        Region region;
        try {
            LocalSession session = WorldEdit.getInstance().getSessionManager().get(BukkitAdapter.adapt(p));
            region = session.getSelection(BukkitAdapter.adapt(p.getWorld()));
        } catch (IncompleteRegionException e) {
            s.sendMessage(ChatColor.RED + "Make a WorldEdit selection first (//wand).");
            return;
        }

        BlockVector3 min = region.getMinimumPoint();
        BlockVector3 max = region.getMaximumPoint();
        try {
            Arena a = Arena.capture(name, p.getWorld(),
                    min.getX(), min.getY(), min.getZ(),
                    max.getX(), max.getY(), max.getZ(),
                    minutes * 60);
            a.save(arenaDir);
            arenas.put(name, a);
            s.sendMessage(ChatColor.GREEN + "Arena '" + name + "' created. Resets every " + minutes + " min.");
        } catch (IllegalArgumentException | IOException e) {
            s.sendMessage(ChatColor.RED + "Failed: " + e.getMessage());
        }
    }

    private void reset(CommandSender s, String[] args) {
        if (args.length < 2 || !arenas.containsKey(args[1].toLowerCase())) {
            s.sendMessage(ChatColor.RED + "Unknown arena.");
            return;
        }
        arenas.get(args[1].toLowerCase()).begin();
        s.sendMessage(ChatColor.GREEN + "Resetting " + args[1].toLowerCase() + "...");
    }

    private void delete(CommandSender s, String[] args) {
        if (args.length < 2 || arenas.remove(args[1].toLowerCase()) == null) {
            s.sendMessage(ChatColor.RED + "Unknown arena.");
            return;
        }
        new File(arenaDir, args[1].toLowerCase() + ".snap").delete();
        s.sendMessage(ChatColor.GREEN + "Arena deleted.");
    }

    private void list(CommandSender s) {
        if (arenas.isEmpty()) {
            s.sendMessage(ChatColor.GRAY + "No arenas.");
            return;
        }
        for (Arena a : arenas.values()) {
            s.sendMessage(ChatColor.AQUA + a.name + ChatColor.GRAY + " - world " + a.worldName
                    + ", every " + (a.intervalSeconds / 60) + " min");
        }
    }

    // ---------- helpers ----------

    /** Converts &#RRGGBB and & codes into Minecraft color codes. */
    static String color(String s) {
        Matcher m = HEX.matcher(s);
        StringBuilder sb = new StringBuilder();
        while (m.find()) {
            StringBuilder rep = new StringBuilder("§x");
            for (char c : m.group(1).toCharArray()) rep.append('§').append(c);
            m.appendReplacement(sb, Matcher.quoteReplacement(rep.toString()));
        }
        m.appendTail(sb);
        return ChatColor.translateAlternateColorCodes('&', sb.toString());
    }
}
