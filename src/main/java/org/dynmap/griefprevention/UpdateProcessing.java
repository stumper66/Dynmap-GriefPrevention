package org.dynmap.griefprevention;

import me.ryanhamshire.GriefPrevention.Claim;
import me.ryanhamshire.GriefPrevention.DataStore;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitRunnable;
import org.dynmap.markers.AreaMarker;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.ConcurrentModificationException;
import java.util.HashMap;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.regex.Pattern;

import static org.dynmap.griefprevention.DynmapGriefPreventionPlugin.ADMIN_ID;

@SuppressWarnings("unchecked")
public class UpdateProcessing {
    public UpdateProcessing(@NotNull DynmapGriefPreventionPlugin main){
        this.main = main;
        showDebug = main.getConfig().getBoolean("debug", false);
        this.playerNameCache = new TreeMap<>();
        this.idPattern = Pattern.compile("^[0-9a-f]{8}-[0-9a-f]{4}-[0-5][0-9a-f]{3}-[089ab][0-9a-f]{3}-[0-9a-f]{12}$");
    }

    private final DynmapGriefPreventionPlugin main;
    private final Map<UUID, String> playerNameCache;
    private final Pattern idPattern;
    private boolean showDebug;

    @Nullable ArrayList<Claim> getClaims(){
        ArrayList<Claim> claims;
        try {
            Field fld = DataStore.class.getDeclaredField("claims");
            fld.setAccessible(true);
            Object o = fld.get(main.griefPrevention.dataStore);
            claims = (ArrayList<Claim>) o;
        } catch(NoSuchFieldException | IllegalArgumentException | IllegalAccessException e) {
            main.getLogger().warning("Error getting claims from reflection: " + e.getMessage());
            return null;
        }

        return claims;
    }

    private @Nullable ArrayList<Claim> getClaimsNonAsync(){
        final CompletableFuture<ArrayList<Claim>> completableFuture = new CompletableFuture<>();

        final BukkitRunnable runnable = new BukkitRunnable() {
            @Override
            public void run() {
                final ArrayList<Claim> claims = getClaims();
                completableFuture.complete(claims);
            }
        };

        long startTime = System.currentTimeMillis();
        runnable.runTask(main);

        try {
            ArrayList<Claim> result = completableFuture.get(500L, TimeUnit.MILLISECONDS);
            if (showDebug) {
                long timeTaken = System.currentTimeMillis() - startTime;
                main.getLogger().info("getClaims() time taken: " + timeTaken);
            }
            return result;
        }
        catch (InterruptedException | ConcurrentModificationException | ExecutionException | TimeoutException e){
            e.printStackTrace();
        }

        return null;
    }

    void updateClaims() {
        final Map<String, AreaMarker> newmap = new HashMap<>(); /* Build new map */
        final ArrayList<Claim> claims = getClaimsNonAsync();

        int parentClaims = 0;
        int childClaims = 0;
        int deletions = 0;

        /* If claims, process them */
        if(claims != null) {
            for(final Claim claim : claims) {
                handleClaim(claim, newmap);
                parentClaims++;

                if (claim.children != null) {
                    for (Claim childClaim : claim.children) {
                        handleClaim(childClaim, newmap);
                        childClaims++;
                    }
                }
            }
        }
        /* Now, review old map - anything left is gone */
        for(final AreaMarker oldm : main.resareas.values()) {
            oldm.deleteMarker();
            deletions++;
        }

        /* And replace with new map */
        main.resareas = newmap;

        if (showDebug)
            main.getLogger().info(String.format("claims: %s, child claims: %s, deletions: %s", parentClaims, childClaims, deletions));
    }

    private void handleClaim(@NotNull Claim claim, Map<String, AreaMarker> newmap) {
        double[] x;
        double[] z;
        Location l0 = claim.getLesserBoundaryCorner();
        Location l1 = claim.getGreaterBoundaryCorner();
        if(l0 == null) {
            return;
        }
        String wname = l0.getWorld() != null ?
                l0.getWorld().getName() : "";
        String owner = claim.isAdminClaim() ? ADMIN_ID : claim.getOwnerName();
        /* Handle areas */
        if(!isVisible(owner, wname)) return;

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
        AreaMarker m = main.resareas.remove(markerid); /* Existing area? */
        if(m == null) {
            m = main.set.createAreaMarker(markerid, owner, false, wname, x, z, false);
            if(m == null) {
                return;
            }
        } else {
            m.setCornerLocations(x, z); /* Replace corner locations */
            m.setLabel(owner);   /* Update label */
        }
        if(main.use3d) { /* If 3D? */
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

    private void addStyle(String owner, AreaMarker m) {
        if (owner == null) return;
        AreaStyle as = null;

        if(!main.ownerstyle.isEmpty()) {
            as = main.ownerstyle.get(owner.toLowerCase());
        }
        if(as == null) {
            as = main.defstyle;
        }

        int sc = 0xFF0000;
        int fc = 0xFF0000;
        try {
            sc = Integer.parseInt(as.strokecolor.substring(1), 16);
            fc = Integer.parseInt(as.fillcolor.substring(1), 16);
        } catch(NumberFormatException ignored) { }

        m.setLineStyle(as.strokeweight, as.strokeopacity, sc);
        m.setFillStyle(as.fillopacity, fc);
        if(as.label != null) {
            m.setLabel(as.label);
        }
    }
    private boolean isVisible(String owner, String worldname) {
        if((main.visible != null) && (main.visible.size() > 0)) {
            if((!main.visible.contains(owner)) && (!main.visible.contains("world:" + worldname)) &&
                    (!main.visible.contains(worldname + "/" + owner))) {
                return false;
            }
        }

        if((main.hidden != null) && (main.hidden.size() > 0)) {
            return !main.hidden.contains(owner) && !main.hidden.contains("world:" + worldname)
                    && !main.hidden.contains(worldname + "/" + owner);
        }

        return true;
    }

    @NotNull
    private String formatInfoWindow(@NotNull Claim claim) {
        String v;
        if(claim.isAdminClaim()) {
            v = "<div class=\"regioninfo\">" + main.admininfowindow + "</div>";
        } else {
            v = "<div class=\"regioninfo\">" + main.infowindow + "</div>";
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
            String claimName = builders.get(i);
            if (isStringUUID(claimName)) {
                claimName = resolvePlayernameFromId(claimName);
            }
            accum.append(claimName);
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

    private boolean isStringUUID(final String input){
        return this.idPattern.matcher(input).matches();
    }

    @NotNull
    private String resolvePlayernameFromId(final @NotNull String playerId){
        final UUID id = UUID.fromString(playerId);
        if (playerNameCache.containsKey(id)){
            return playerNameCache.get(id);
        }
        else {
            final Player player = Bukkit.getPlayer(playerId);
            String playerName;
            if (player != null) {
                playerName = player.getName();
                playerNameCache.put(id, playerName);
            }
            else {
                final OfflinePlayer offlinePlayer = Bukkit.getOfflinePlayer(id);
                playerName = offlinePlayer.getName();
                playerNameCache.put(id, playerName);
            }

            return playerName != null ?
                    playerName : playerId;
        }
    }
}
