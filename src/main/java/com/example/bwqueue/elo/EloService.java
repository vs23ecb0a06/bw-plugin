package com.example.bwqueue.elo;

import com.example.bwqueue.config.PluginConfig;
import org.bukkit.configuration.ConfigurationSection;

import java.util.ArrayList;
import java.util.List;

public class EloService {

    public static class Tier {
        public final String name;
        public final int min;
        public final int max; // inclusive; use Integer.MAX_VALUE for open-ended
        public final int win;
        public final int lose;
        public final int mvp;
        public Tier(String name, int min, int max, int win, int lose, int mvp) {
            this.name = name; this.min = min; this.max = max; this.win = win; this.lose = lose; this.mvp = mvp;
        }
        public boolean contains(int elo) { return elo >= min && elo <= max; }
    }

    private final List<Tier> tiers = new ArrayList<>();
    private final int defaultElo;

    public EloService(org.bukkit.configuration.file.FileConfiguration cfg) {
        this.defaultElo = cfg.getInt("elo.default", 0);
        ConfigurationSection sec = cfg.getConfigurationSection("elo.tiers");
        if (sec != null) {
            // When represented as a list, iterate keys numerically
            List<?> list = cfg.getList("elo.tiers");
            if (list != null) {
                for (int i = 0; i < list.size(); i++) {
                    ConfigurationSection t = cfg.getConfigurationSection("elo.tiers." + i);
                    if (t == null) continue;
                    String name = t.getString("name", "Tier" + i);
                    String range = t.getString("range", "0-999999");
                    int[] mm = parseRange(range);
                    int win = t.getInt("win", 0);
                    int lose = t.getInt("lose", 0);
                    int mvp = t.getInt("mvp", 0);
                    tiers.add(new Tier(name, mm[0], mm[1], win, lose, mvp));
                }
            }
        }
        if (tiers.isEmpty()) {
            tiers.add(new Tier("Default", 0, Integer.MAX_VALUE, 10, -5, 5));
        }
    }

    public int getDefaultElo() { return defaultElo; }

    public Tier tierFor(int elo) {
        for (Tier t : tiers) if (t.contains(elo)) return t;
        return tiers.get(tiers.size() - 1);
    }

    public int winGain(int elo, boolean mvp) {
        Tier t = tierFor(elo);
        return t.win + (mvp ? t.mvp : 0);
    }

    public int lossChange(int elo) {
        Tier t = tierFor(elo);
        return t.lose;
    }

    private static int[] parseRange(String s) {
        s = s.trim();
        if (s.startsWith(">=")) {
            int min = Integer.parseInt(s.substring(2));
            return new int[]{min, Integer.MAX_VALUE};
        }
        String[] p = s.split("-");
        int min = Integer.parseInt(p[0]);
        int max = (p.length > 1) ? Integer.parseInt(p[1]) : Integer.MAX_VALUE;
        return new int[]{min, max};
    }
}
