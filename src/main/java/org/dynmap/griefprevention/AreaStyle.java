package org.dynmap.griefprevention;

import org.bukkit.configuration.file.FileConfiguration;
import org.jetbrains.annotations.NotNull;

public class AreaStyle {

    String strokecolor;
    double strokeopacity;
    int strokeweight;
    String fillcolor;
    double fillopacity;
    String label;

    AreaStyle(@NotNull FileConfiguration cfg, String path, @NotNull AreaStyle def) {
        strokecolor = cfg.getString(path + ".strokeColor", def.strokecolor);
        strokeopacity = cfg.getDouble(path + ".strokeOpacity", def.strokeopacity);
        strokeweight = cfg.getInt(path + ".strokeWeight", def.strokeweight);
        fillcolor = cfg.getString(path + ".fillColor", def.fillcolor);
        fillopacity = cfg.getDouble(path + ".fillOpacity", def.fillopacity);
        label = cfg.getString(path + ".label", null);
    }

    AreaStyle(@NotNull FileConfiguration cfg, String path) {
        strokecolor = cfg.getString(path + ".strokeColor", "#FF0000");
        strokeopacity = cfg.getDouble(path + ".strokeOpacity", 0.8);
        strokeweight = cfg.getInt(path + ".strokeWeight", 3);
        fillcolor = cfg.getString(path + ".fillColor", "#FF0000");
        fillopacity = cfg.getDouble(path + ".fillOpacity", 0.35);
    }
}
