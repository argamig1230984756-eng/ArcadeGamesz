package me.arcade.games.game.impl;

import me.arcade.games.ArcadePlugin;
import me.arcade.games.arena.Arena;
import me.arcade.games.game.Game;
import me.arcade.games.util.Msg;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.block.data.BlockData;
import org.bukkit.entity.Player;
import org.bukkit.entity.Item;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.event.entity.EntityPickupItemEvent;
import org.bukkit.entity.Projectile;
import org.bukkit.entity.Snowball;
import org.bukkit.entity.Egg;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.util.Vector;
import java.util.*;

/** Shared implementation for the additional arcade modes. */
public final class ArcadeModeGame extends Game {
    private final Map<Block, BlockData> changed = new LinkedHashMap<>();
    private final Set<UUID> tagged = new HashSet<>();
    private final Map<UUID, Long> tagTimes = new HashMap<>();
    private final Map<UUID, ItemStack> oldHelmets = new HashMap<>();
    private final Random random = new Random();
    private final Map<UUID, Location> checkpoints = new HashMap<>();
    private final Map<UUID, Integer> coins = new HashMap<>();
    private int secondsLeft = 180;
    private int templePlace = 0;

    public ArcadeModeGame(ArcadePlugin plugin, Arena arena) { super(plugin, arena); }

    @Override protected void onStart() {
        int i = 0; secondsLeft = Math.max(30, cfg().getInt("round-seconds", 180));
        for (Player p : allPlayers()) {
            p.teleport(spawnFor(i++)); p.setGameMode(GameMode.SURVIVAL);
            switch (arena.type) {
                case TNT_TAG -> { p.getInventory().clear(); p.getInventory().setHelmet(new ItemStack(Material.TNT)); }
                case SPLEGG -> { p.getInventory().addItem(new ItemStack(Material.IRON_SHOVEL)); p.getInventory().addItem(new ItemStack(Material.EGG, 64)); }
                case BOMBLOBBERS -> p.getInventory().addItem(new ItemStack(Material.SNOWBALL, 32));
                case QUAKE -> { p.getInventory().addItem(new ItemStack(Material.BOW)); p.getInventory().addItem(new ItemStack(Material.ARROW, 64)); }
                case TEMPLE_RUN -> { p.addPotionEffect(new PotionEffect(PotionEffectType.SPEED, 20 * secondsLeft, 0, false, false)); checkpoints.put(p.getUniqueId(), p.getLocation().clone()); coins.put(p.getUniqueId(), 0); }
                default -> {}
            }
        }
        if (arena.type == me.arcade.games.arena.GameType.TNT_TAG && !alive.isEmpty()) {
            UUID first = new ArrayList<>(alive).get(random.nextInt(alive.size())); tagged.add(first); tagTimes.put(first, ticks + 20L * cfg().getInt("tag-fuse-seconds", 20)); setTaggedHelmet(first, true);
            Player p = org.bukkit.Bukkit.getPlayer(first); if (p != null) Msg.send(p, "&cYou are IT! Tag another player!");
        }
        broadcast("&a" + arena.type.display + " started! Survive and be the last player standing.");
    }

    @Override protected void tick() {
        if (ticks % 20 == 0) {
            secondsLeft--;
            if (secondsLeft <= 0) { finishByScore(); return; }
            for (Player p : alivePlayers()) {
                p.setLevel(0); p.setExp(0f);
                Msg.bar(p, "&e" + arena.type.display + " &8| &f" + secondsLeft + "s" + (arena.type == me.arcade.games.arena.GameType.TEMPLE_RUN ? " &6| Coins: " + coins.getOrDefault(p.getUniqueId(), 0) : ""));
            }
        }
        for (Player p : alivePlayers()) {
            if (arena.type == me.arcade.games.arena.GameType.TEMPLE_RUN) {
                if (p.getLocation().getY() < arena.minY() - 2) {
                    Location cp = checkpoints.getOrDefault(p.getUniqueId(), spawnFor(0));
                    p.teleport(cp); p.setFallDistance(0); p.setHealth(20.0); p.setFoodLevel(20);
                    Msg.send(p, "&eYou fell! Returned to your latest checkpoint.");
                    continue;
                }
                Material under = p.getLocation().subtract(0, 1, 0).getBlock().getType();
                if (under == Material.GOLD_BLOCK || under == Material.EMERALD_BLOCK || under == Material.DIAMOND_BLOCK) {
                    Location cp = p.getLocation().getBlock().getLocation().add(0.5, 1, 0.5);
                    Location previous = checkpoints.get(p.getUniqueId());
                    if (previous == null || previous.distanceSquared(cp) > 1.0) {
                        checkpoints.put(p.getUniqueId(), cp);
                        Msg.bar(p, "&aCheckpoint saved!");
                    }
                }
            } else if (p.getLocation().getY() < arena.minY() - 2) { eliminate(p); continue; }
            if (arena.type == me.arcade.games.arena.GameType.TNT_TAG && tagged.contains(p.getUniqueId()) && ticks >= tagTimes.getOrDefault(p.getUniqueId(), Long.MAX_VALUE)) {
                broadcast("&c" + p.getName() + " exploded!"); eliminate(p);
            }
        }
        if (arena.type == me.arcade.games.arena.GameType.TEMPLE_RUN && ticks % 100 == 0 && !alive.isEmpty()) {
            List<Player> runners = alivePlayers();
            Player target = runners.get(random.nextInt(runners.size()));
            Location loc = target.getLocation().clone().add(random.nextInt(9) - 4, 1, random.nextInt(9) - 4);
            if (arena.contains(loc.getBlock())) {
                Item coin = loc.getWorld().dropItem(loc, new ItemStack(Material.GOLD_NUGGET));
                coin.setPickupDelay(0); coin.setUnlimitedLifetime(true); coin.addScoreboardTag("ag_temple_coin");
                ItemMeta meta = coin.getItemStack().getItemMeta(); meta.displayName(Msg.c("&6Temple Coin")); coin.getItemStack().setItemMeta(meta);
            }
        }
        if (arena.type == me.arcade.games.arena.GameType.TEMPLE_RUN && ticks % 100 == 0) {
            for (Player p : alivePlayers()) p.addPotionEffect(new PotionEffect(PotionEffectType.SPEED, 120, Math.min(2, (int)(ticks / 1200)), false, false));
        }
    }

    private void setTaggedHelmet(UUID id, boolean enabled) {
        Player p = org.bukkit.Bukkit.getPlayer(id);
        if (p == null) return;
        if (enabled) {
            oldHelmets.putIfAbsent(id, p.getInventory().getHelmet() == null ? null : p.getInventory().getHelmet().clone());
            p.getInventory().setHelmet(new ItemStack(Material.TNT));
        } else {
            p.getInventory().setHelmet(oldHelmets.remove(id));
        }
    }

    /** Called by the central pickup listener for Temple Run's tagged coin entities. */
    public boolean onCoinPickup(Player player, Item item) {
        if (arena.type != me.arcade.games.arena.GameType.TEMPLE_RUN || !item.getScoreboardTags().contains("ag_temple_coin") || !alive.contains(player.getUniqueId())) return false;
        item.remove();
        coins.merge(player.getUniqueId(), 1, Integer::sum);
        Msg.bar(player, "&6+1 coin &8| &fTotal: &e" + coins.get(player.getUniqueId()));
        return true;
    }

    @Override public void onDamage(Player victim, EntityDamageEvent e) {
        if (arena.type == me.arcade.games.arena.GameType.TNT_TAG && e instanceof EntityDamageByEntityEvent by) {
            Player attacker = by.getDamager() instanceof Player p ? p : (by.getDamager() instanceof Projectile pr && pr.getShooter() instanceof Player p ? p : null);
            if (attacker != null && alive.contains(attacker.getUniqueId()) && alive.contains(victim.getUniqueId()) && tagged.contains(attacker.getUniqueId()) && !tagged.contains(victim.getUniqueId())) {
                tagged.remove(attacker.getUniqueId()); tagTimes.remove(attacker.getUniqueId()); setTaggedHelmet(attacker.getUniqueId(), false);
                tagged.add(victim.getUniqueId()); tagTimes.put(victim.getUniqueId(), ticks + 20L * cfg().getInt("tag-fuse-seconds", 20)); setTaggedHelmet(victim.getUniqueId(), true);
                Msg.send(victim, "&cYou are IT! Run!"); Msg.send(attacker, "&aYou passed the tag!");
            }
            e.setCancelled(true); return;
        }
        if (arena.type == me.arcade.games.arena.GameType.QUAKE && e instanceof EntityDamageByEntityEvent by) {
            Player attacker = by.getDamager() instanceof Player p ? p : (by.getDamager() instanceof Projectile pr && pr.getShooter() instanceof Player p ? p : null);
            if (attacker != null && attacker != victim && alive.contains(attacker.getUniqueId())) {
                e.setCancelled(true); broadcast("&e" + victim.getName() + " was blasted by " + attacker.getName() + "!"); eliminate(victim); return;
            }
        }
        e.setCancelled(true);
    }

    @Override public void onInteract(Player p, PlayerInteractEvent e) {
        if (arena.type != me.arcade.games.arena.GameType.SPLEGG || !alive.contains(p.getUniqueId())) return;
        if (e.getItem() == null || e.getItem().getType() != Material.EGG) return;
        e.setCancelled(true);
        Egg egg = p.launchProjectile(Egg.class); egg.setVelocity(p.getLocation().getDirection().multiply(1.6)); egg.addScoreboardTag("ag_splegg");
    }

    @Override public boolean allowBreak(Player p, Block b) {
        if (arena.type != me.arcade.games.arena.GameType.SPLEGG || !alive.contains(p.getUniqueId()) || !arena.contains(b)) return false;
        changed.putIfAbsent(b, b.getBlockData()); b.setType(Material.AIR, false); return true;
    }

    @Override public boolean allowPlace(Player p, Block b) { return false; }

    @Override protected void checkEnd() {
        if (alive.size() > 1) return;
        String winner = alive.isEmpty() ? "Nobody" : nameOf(alive.iterator().next());
        end("&6" + arena.type.display + " winner: &e" + winner);
    }

    private void finishByScore() {
        if (arena.type == me.arcade.games.arena.GameType.TEMPLE_RUN) {
            List<Player> ranking = alivePlayers();
            ranking.sort((a, b) -> Integer.compare(coins.getOrDefault(b.getUniqueId(), 0), coins.getOrDefault(a.getUniqueId(), 0)));
            List<String> result = new ArrayList<>();
            result.add("&6Temple Run results:");
            String[] places = {"&6🥇 1st", "&7🥈 2nd", "&c🥉 3rd"};
            for (int i = 0; i < Math.min(3, ranking.size()); i++) result.add(places[i] + " &f" + ranking.get(i).getName() + " &8- &e" + coins.getOrDefault(ranking.get(i).getUniqueId(), 0) + " coins");
            end(result.toArray(new String[0]));
            return;
        }
        String winner = "Nobody";
        if (!alive.isEmpty()) winner = nameOf(alive.iterator().next());
        end("&6" + arena.type.display + " time is up! Winner: &e" + winner);
    }

    @Override protected void onEnd() {
        for (Map.Entry<Block, BlockData> entry : changed.entrySet()) entry.getKey().setBlockData(entry.getValue(), false);
        for (UUID id : new HashSet<>(tagged)) setTaggedHelmet(id, false);
        changed.clear(); tagged.clear(); tagTimes.clear(); oldHelmets.clear(); checkpoints.clear(); coins.clear();
        for (org.bukkit.entity.Entity entity : arena.world().getEntities()) if (entity.getScoreboardTags().contains("ag_temple_coin") && arena.contains(entity.getLocation().getBlock())) entity.remove();
    }
}
