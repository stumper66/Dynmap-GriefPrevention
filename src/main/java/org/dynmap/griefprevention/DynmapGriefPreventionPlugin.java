package org.dynmap.griefprevention;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import me.ryanhamshire.GriefPrevention.Claim;
import me.ryanhamshire.GriefPrevention.DataStore;
import me.ryanhamshire.GriefPrevention.GriefPrevention;
import org.bukkit.Location;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitRunnable;
import org.dynmap.DynmapAPI;
import org.dynmap.markers.AreaMarker;
import org.dynmap.markers.MarkerAPI;
import org.dynmap.markers.MarkerSet;
import org.jetbrains.annotations.NotNull;

public class DynmapGriefPreventionPlugin extends JavaPlugin {

    private static final long TWO_SECONDS_IN_TICKS = 20L * 2L;
    private static final String DEF_INFOWINDOW = "div class=\"infowindow\">Claim Owner: <span style=\"font-weight:bold;\">%owner%</span><br/>Permission Trust: <span style=\"font-weight:bold;\">%managers%</span><br/>Trust: <span style=\"font-weight:bold;\">%builders%</span><br/>Container Trust: <span style=\"font-weight:bold;\">%containers%</span><br/>Access Trust: <span style=\"font-weight:bold;\">%accessors%</span></div>";
    private static final String DEF_ADMININFOWINDOW = "<div class=\"infowindow\"><span style=\"font-weight:bold;\">Administrator Claim</span><br/>Permission Trust: <span style=\"font-weight:bold;\">%managers%</span><br/>Trust: <span style=\"font-weight:bold;\">%builders%</span><br/>Container Trust: <span style=\"font-weight:bold;\">%containers%</span><br/>Access Trust: <span style=\"font-weight:bold;\">%accessors%</span></div>";
    private static final String ADMIN_ID = "administrator";
    Plugin dynmap;
    DynmapAPI api;
    MarkerAPI markerapi;
    GriefPrevention griefPrevention;

    MarkerSet set;
    boolean use3d;
    String infowindow;
    String admininfowindow;
    AreaStyle defstyle;
    Map<String, AreaStyle> ownerstyle;
    Set<String> visible;
    Set<String> hidden;
    int maxdepth;

    private static class AreaStyle {

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

    private Map<String, AreaMarker> resareas = new HashMap<>();

    @NotNull
    private String formatInfoWindow(@NotNull Claim claim) {
        String v;
        if(claim.isAdminClaim()) {
            v = "<div class=\"regioninfo\">" + admininfowindow + "</div>";
        } else {
            v = "<div class=\"regioninfo\">" + infowindow + "</div>";
        }
        v = v.replace("%owner%", claim.isAdminClaim() ? ADMIN_ID : claim.getOwnerName());
        v = v.replace("%area%", Integer.toString(claim.getArea()));
        ArrayList<String> builders = new ArrayList<>();
        ArrayList<String> containers = new ArrayList<>();
        ArrayList<String> accessors = new ArrayList<>();
        ArrayList<String> managers = new ArrayList<>();
        claim.getPermissions(builders, containers, accessors, managers);
        /* Build builders list */
        final StringBuilder accum = new StringBuilder();
        for(int i = 0; i < builders.size(); i++) {
            if(i > 0) {
                accum.append(", ");
            }
            accum.append(builders.get(i));
        }
        v = v.replace("%builders%", accum.toString());
        /* Build containers list */
        accum.setLength(0);
        for(int i = 0; i < containers.size(); i++) {
            if(i > 0) {
                accum.append(", ");
            }
            accum.append(containers.get(i));
        }
        v = v.replace("%containers%", accum.toString());
        /* Build accessors list */
        accum.setLength(1);
        for(int i = 0; i < accessors.size(); i++) {
            if(i > 0) {
                accum.append(", ");
            }
            accum.append(accessors.get(i));
        }
        v = v.replace("%accessors%", accum.toString());
        /* Build managers list */
        accum.setLength(1);
        for(int i = 0; i < managers.size(); i++) {
            if(i > 0) {
                accum.append(", ");
            }
            accum.append(managers.get(i));
        }
        v = v.replace("%managers%", accum.toString());

        return v;
    }

    private boolean isVisible(String owner, String worldname) {
        if((visible != null) && (visible.size() > 0)) {
            if((!visible.contains(owner)) && (!visible.contains("world:" + worldname)) &&
                (!visible.contains(worldname + "/" + owner))) {
                return false;
            }
        }

        if((hidden != null) && (hidden.size() > 0)) {
            return !hidden.contains(owner) && !hidden.contains("world:" + worldname)
                && !hidden.contains(worldname + "/" + owner);
        }

        return true;
    }

    private void addStyle(String owner, AreaMarker m) {
        AreaStyle as = null;

        if(!ownerstyle.isEmpty()) {
            as = ownerstyle.get(owner.toLowerCase());
        }
        if(as == null) {
            as = defstyle;
        }

        int sc = 0xFF0000;
        int fc = 0xFF0000;
        try {
            sc = Integer.parseInt(as.strokecolor.substring(1), 16);
            fc = Integer.parseInt(as.fillcolor.substring(1), 16);
        } catch(NumberFormatException ignored) {
        }
        m.setLineStyle(as.strokeweight, as.strokeopacity, sc);
        m.setFillStyle(as.fillopacity, fc);
        if(as.label != null) {
            m.setLabel(as.label);
        }
    }

    /* Handle specific claim */
    private void handleClaim(@NotNull Claim claim, Map<String, AreaMarker> newmap) {
        double[] x;
        double[] z;
        Location l0 = claim.getLesserBoundaryCorner();
        Location l1 = claim.getGreaterBoundaryCorner();
        if(l0 == null) {
            return;
        }
        String wname = l0.getWorld().getName();
        String owner = claim.isAdminClaim() ? ADMIN_ID : claim.getOwnerName();
        /* Handle areas */
        if(isVisible(owner, wname)) {
            /* Make outline */
            x = new double[4];
            z = new double[4];
            x[0] = l0.getX();
            z[0] = l0.getZ();
            x[1] = l0.getX();
            z[1] = l1.getZ() + 1.0;
            x[2] = l1.getX() + 1.0;
            z[2] = l1.getZ() + 1.0;
            x[3] = l1.getX() + 1.0;
            z[3] = l0.getZ();
            Long id = claim.getID();
            String markerid = "GP_" + Long.toHexString(id);
            AreaMarker m = resareas.remove(markerid); /* Existing area? */
            if(m == null) {
                m = set.createAreaMarker(markerid, owner, false, wname, x, z, false);
                if(m == null) {
                    return;
                }
            } else {
                m.setCornerLocations(x, z); /* Replace corner locations */
                m.setLabel(owner);   /* Update label */
            }
            if(use3d) { /* If 3D? */
                m.setRangeY(l1.getY() + 1.0, l0.getY());
            }
            /* Set line and fill properties */
            addStyle(owner, m);

            /* Build popup */
            String desc = formatInfoWindow(claim);

            m.setDescription(desc); /* Set popup */

            /* Add to map */
            newmap.put(markerid, m);
        }
    }

    /* Update grief prevention region information */
    @SuppressWarnings("unchecked")
    private void updateClaims() {
        final Map<String, AreaMarker> newmap = new HashMap<>(); /* Build new map */

        final DataStore ds = griefPrevention.dataStore;

        ArrayList<Claim> claims = null;
        try {
            Field fld = DataStore.class.getDeclaredField("claims");
            fld.setAccessible(true);
            Object o = fld.get(ds);
            claims = (ArrayList<Claim>) o;
        } catch(NoSuchFieldException | IllegalArgumentException | IllegalAccessException ignored) {
        }
        /* If claims, process them */
        if(claims != null) {
            for(final Claim claim : claims) {
                handleClaim(claim, newmap);
            }

            for(final Claim claim : claims) {
                if((claim.children != null) && (claim.children.size() > 0)) {
                    for(int j = 0; j < claim.children.size(); j++) {
                        handleClaim(claim.children.get(j), newmap);
                    }
                }
            }
        }
        /* Now, review old map - anything left is gone */
        for(final AreaMarker oldm : resareas.values()) {
            oldm.deleteMarker();
        }

        /* And replace with new map */
        resareas = newmap;
    }

    public void onEnable() {

        /* Get dynmap */
        dynmap = getServer().getPluginManager().getPlugin("dynmap");
        if(dynmap == null || !dynmap.isEnabled()) {
            getLogger().severe("Unable to find Dynmap! The plugin will shut down.");
            disablePlugin();
            return;
        }
        api = (DynmapAPI) dynmap;

        /* Get GriefPrevention */
        var gpPlugin = getServer().getPluginManager().getPlugin("GriefPrevention");
        if(gpPlugin == null || !gpPlugin.isEnabled()) {
            getLogger().severe("Unable to find GriefPrevention! The plugin will shut down.");
            disablePlugin();
            return;
        }
        griefPrevention = (GriefPrevention) gpPlugin;

        activate();

        getLogger().info("Enabled successfully.");
    }

    private boolean reload = false;

    private void activate() {
        /* Get markers API */
        markerapi = api.getMarkerAPI();
        if(markerapi == null) {
            getLogger().severe("Unable to load dynmap marker API!");
            disablePlugin();
            return;
        }

        /* Load configuration */
        if(reload) {
            reloadConfig();
            if(set != null) {
                set.deleteMarkerSet();
                set = null;
            }
            resareas.clear();
        } else {
            reload = true;
        }

        getConfig().options().copyDefaults(true);   /* Load defaults, if needed */
        saveConfig();  /* Save updates, if needed */

        /* Add marker set for mobs (make it transient) */
        set = markerapi.getMarkerSet("griefprevention.markerset");
        if(set == null) {
            set = markerapi.createMarkerSet(
                "griefprevention.markerset",
                getConfig().getString("layer.name", "GriefPrevention"),
                null,
                false);
        } else {
            set.setMarkerSetLabel(getConfig().getString("layer.name", "GriefPrevention"));
        }

        if(set == null) {
            getLogger().severe("Unable to create marker set!");
            disablePlugin();
            return;
        }

        int minzoom = getConfig().getInt("layer.minzoom", 0);
        if(minzoom > 0) {
            set.setMinZoom(minzoom);
        }
        set.setLayerPriority(getConfig().getInt("layer.layerprio", 10));
        set.setHideByDefault(getConfig().getBoolean("layer.hidebydefault", false));
        use3d = getConfig().getBoolean("use3dregions", false);
        infowindow = getConfig().getString("infowindow", DEF_INFOWINDOW);
        admininfowindow = getConfig().getString("adminclaiminfowindow", DEF_ADMININFOWINDOW);
        maxdepth = getConfig().getInt("maxdepth", 16);

        /* Get style information */
        defstyle = new AreaStyle(getConfig(), "regionstyle");
        ownerstyle = new HashMap<>();
        ConfigurationSection sect = getConfig().getConfigurationSection("ownerstyle");
        if(sect != null) {
            Set<String> ids = sect.getKeys(false);

            for(String id : ids) {
                ownerstyle.put(id.toLowerCase(),
                    new AreaStyle(getConfig(), "ownerstyle." + id, defstyle));
            }
        }
        List<String> vis = getConfig().getStringList("visibleregions");
        if(vis != null) {
            visible = new HashSet<>(vis);
        }
        List<String> hid = getConfig().getStringList("hiddenregions");
        if(hid != null) {
            hidden = new HashSet<>(hid);
        }

        startUpdateTask();

        getLogger().info("Activated successfully.");
    }

    /*
    Repeatedly calls updateClaims (with a delay of course).
    This task cancels when onDisable() is called.
     */
    private void startUpdateTask() {
        final var updatePeriod = 20L * Math.max(15L,
            getConfig().getLong("update.period", 300L));

        new BukkitRunnable() {
            @Override
            public void run() {
                updateClaims();
            }
        }.runTaskTimer(this, TWO_SECONDS_IN_TICKS, updatePeriod);
    }

    public void onDisable() {
        getLogger().info("Cancelling tasks...");
        getServer().getScheduler().cancelTasks(this);

        if(set != null) {
            getLogger().info("Deleting marker set...");
            set.deleteMarkerSet();
            set = null;
        }

        getLogger().info("Clearing areas...");
        resareas.clear();

        getLogger().info("Disabled successfully.");
    }

    private void disablePlugin() {
        getServer().getPluginManager().disablePlugin(this);
    }

}
