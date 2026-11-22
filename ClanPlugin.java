package me.clan;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Color;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.OfflinePlayer;
import org.bukkit.Sound;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabExecutor;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.player.AsyncPlayerChatEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.LeatherArmorMeta;
import org.bukkit.inventory.meta.SkullMeta;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.projectiles.ProjectileSource;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.scoreboard.Scoreboard;
import org.bukkit.scoreboard.Team;

import java.io.File;
import java.io.IOException;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

public class ClanPlugin extends JavaPlugin implements TabExecutor, Listener {

    // ---------- Модели ----------

    private static class Clan {
        final String name;
        UUID leader;
        final Set<UUID> members = new LinkedHashSet<>();
        Location home;
        int kills, deaths;
        boolean friendlyFire = false;
        Clan(String name, UUID leader) {
            this.name = name;
            this.leader = leader;
            this.members.add(leader);
        }
    }

    private record ColorOpt(String name, int rgb) {}

    private enum Type { MAIN, MEMBERS, COLORS, INVITE, TOP, CONFIRM }

    private static class Menu implements InventoryHolder {
        final Type type;
        final List<String> ids = new ArrayList<>();
        Inventory inv;
        Menu(Type type) { this.type = type; }
        @Override public Inventory getInventory() { return inv; }
    }

    // ---------- Константы ----------

    private static final EquipmentSlot[] ARMOR_SLOTS = {
            EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS, EquipmentSlot.FEET};
    private static final Material[] LEATHER = {
            Material.LEATHER_HELMET, Material.LEATHER_CHESTPLATE, Material.LEATHER_LEGGINGS, Material.LEATHER_BOOTS};
    private static final double ARMOR_RANGE_SQ = 64 * 64;
    private static final long HOME_WARMUP_TICKS = 20L * 3;
    private static final int RAINBOW = -1;
    private static final int DEFAULT_COLOR = 0x00FF00;

    private static final List<ColorOpt> COLORS = List.of(
            new ColorOpt("Красный", 0xFF0000), new ColorOpt("Оранжевый", 0xFF8000),
            new ColorOpt("Жёлтый", 0xFFFF00), new ColorOpt("Лаймовый", 0x00FF00),
            new ColorOpt("Зелёный", 0x008000), new ColorOpt("Бирюзовый", 0x00FFFF),
            new ColorOpt("Голубой", 0x55AAFF), new ColorOpt("Синий", 0x0000FF),
            new ColorOpt("Фиолетовый", 0x8000FF), new ColorOpt("Пурпурный", 0xFF00FF),
            new ColorOpt("Розовый", 0xFF8FC0), new ColorOpt("Белый", 0xFFFFFF),
            new ColorOpt("Серый", 0x808080), new ColorOpt("Чёрный", 0x101010));
    private static final int[] COLOR_SLOTS = {10, 11, 12, 13, 14, 15, 16, 19, 20, 21, 22, 23, 24, 25};
    private static final int[] TOP_SLOTS = {4, 12, 14, 19, 20, 21, 22, 23, 24, 25};

    private static final List<String> SUB = List.of(
            "menu", "create", "invite", "accept", "leave", "kick", "disband", "info", "list",
            "chat", "sethome", "home", "glow", "top", "reload", "help");

    // ---------- Состояние ----------

    private final Map<String, Clan> clans = new ConcurrentHashMap<>();       // ключ: имя в нижнем регистре
    private final Map<UUID, String> playerClan = new ConcurrentHashMap<>();  // игрок -> ключ клана
    private final Map<UUID, String> invites = new HashMap<>();               // приглашённый -> ключ клана
    private final Set<UUID> chatMode = ConcurrentHashMap.newKeySet();        // включён клановый чат
    private final Set<UUID> armorMode = new HashSet<>();                     // включена подсветка
    private final Map<UUID, Integer> glowColor = new ConcurrentHashMap<>();  // цвет подсветки игрока
    private final Map<UUID, BukkitTask> teleports = new HashMap<>();         // ожидают телепорта домой
    private final Map<Integer, Map<EquipmentSlot, ItemStack>> armorCache = new HashMap<>();
    private ItemStack filler;
    private boolean dirty;
    private File dataFile;

    // ---------- Жизненный цикл ----------

    @Override
    public void onEnable() {
        saveDefaultConfig();
        dataFile = new File(getDataFolder(), "clans.yml");
        filler = item(Material.GRAY_STAINED_GLASS_PANE, " ");
        load();
        Objects.requireNonNull(getCommand("clan")).setExecutor(this);
        Objects.requireNonNull(getCommand("clan")).setTabCompleter(this);
        Objects.requireNonNull(getCommand("cc")).setExecutor(this);
        Bukkit.getPluginManager().registerEvents(this, this);
        Bukkit.getScheduler().runTaskTimer(this, this::tickArmor, 20L, 5L);
        Bukkit.getScheduler().runTaskTimer(this, () -> { if (dirty) save(); }, 6000L, 6000L);
        for (Player p : Bukkit.getOnlinePlayers()) applyPrefix(p);
        getLogger().info("Загружено кланов: " + clans.size());
    }

    @Override
    public void onDisable() {
        for (UUID u : new ArrayList<>(armorMode)) {
            Player p = Bukkit.getPlayer(u);
            if (p != null) restoreArmor(p);
        }
        save();
    }

    // ---------- Хранение ----------

    private void load() {
        if (!dataFile.exists()) return;
        YamlConfiguration yml = YamlConfiguration.loadConfiguration(dataFile);
        ConfigurationSection sec = yml.getConfigurationSection("clans");
        if (sec != null) {
            for (String key : sec.getKeys(false)) {
                ConfigurationSection c = sec.getConfigurationSection(key);
                if (c == null) continue;
                Clan clan = new Clan(c.getString("name", key), UUID.fromString(c.getString("leader")));
                for (String m : c.getStringList("members")) clan.members.add(UUID.fromString(m));
                clan.kills = c.getInt("kills");
                clan.deaths = c.getInt("deaths");
                clan.friendlyFire = c.getBoolean("friendly-fire", false);
                ConfigurationSection h = c.getConfigurationSection("home");
                if (h != null && Bukkit.getWorld(h.getString("world", "")) != null) {
                    clan.home = new Location(Bukkit.getWorld(h.getString("world")),
                            h.getDouble("x"), h.getDouble("y"), h.getDouble("z"),
                            (float) h.getDouble("yaw"), (float) h.getDouble("pitch"));
                }
                clans.put(key, clan);
                for (UUID u : clan.members) playerClan.put(u, key);
            }
        }
        ConfigurationSection cs = yml.getConfigurationSection("colors");
        if (cs != null) {
            for (String k : cs.getKeys(false)) glowColor.put(UUID.fromString(k), cs.getInt(k));
        }
    }

    private void save() {
        dirty = false;
        YamlConfiguration yml = new YamlConfiguration();
        for (Map.Entry<String, Clan> e : clans.entrySet()) {
            Clan c = e.getValue();
            String p = "clans." + e.getKey();
            yml.set(p + ".name", c.name);
            yml.set(p + ".leader", c.leader.toString());
            yml.set(p + ".members", c.members.stream().map(UUID::toString).collect(Collectors.toList()));
            yml.set(p + ".kills", c.kills);
            yml.set(p + ".deaths", c.deaths);
            yml.set(p + ".friendly-fire", c.friendlyFire);
            if (c.home != null && c.home.getWorld() != null) {
                yml.set(p + ".home.world", c.home.getWorld().getName());
                yml.set(p + ".home.x", c.home.getX());
                yml.set(p + ".home.y", c.home.getY());
                yml.set(p + ".home.z", c.home.getZ());
                yml.set(p + ".home.yaw", c.home.getYaw());
                yml.set(p + ".home.pitch", c.home.getPitch());
            }
        }
        for (Map.Entry<UUID, Integer> e : glowColor.entrySet()) {
            yml.set("colors." + e.getKey(), e.getValue());
        }
        try {
            getDataFolder().mkdirs();
            yml.save(dataFile);
        } catch (IOException ex) {
            getLogger().severe("Не удалось сохранить кланы: " + ex.getMessage());
        }
    }

    // ---------- Команды ----------

    @Override
    public boolean onCommand(CommandSender sender, Command cmd, String label, String[] args) {
        if (!(sender instanceof Player p)) {
            sender.sendMessage("Команда только для игроков.");
            return true;
        }
        if (cmd.getName().equalsIgnoreCase("cc")) {
            chat(p, args, 0);
            return true;
        }
        if (args.length == 0) {
            openMain(p);
            return true;
        }
        switch (args[0].toLowerCase()) {
            case "menu" -> openMain(p);
            case "create" -> create(p, args);
            case "invite" -> invite(p, args);
            case "accept" -> accept(p);
            case "leave" -> leave(p);
            case "kick" -> kick(p, args);
            case "disband" -> disband(p);
            case "info" -> info(p, args);
            case "list" -> list(p);
            case "chat" -> chat(p, args, 1);
            case "sethome" -> setHome(p);
            case "home" -> home(p);
            case "glow", "armor" -> glow(p, args);
            case "top" -> topChat(p);
            case "reload" -> reload(p);
            default -> help(p);
        }
        return true;
    }

    private void help(Player p) {
        msg(p, ChatColor.GOLD + "=== Кланы ===");
        helpLine(p, "/clan", "открыть меню клана");
        helpLine(p, "/clan create <название>", "создать клан");
        helpLine(p, "/clan invite <игрок>", "пригласить игрока");
        helpLine(p, "/clan accept", "принять приглашение");
        helpLine(p, "/clan leave", "покинуть клан");
        helpLine(p, "/clan kick <игрок>", "выгнать участника");
        helpLine(p, "/clan disband", "распустить клан");
        helpLine(p, "/clan info [клан]", "информация о клане");
        helpLine(p, "/clan list", "список кланов");
        helpLine(p, "/clan chat [текст]", "клановый чат (без текста — вкл/выкл), также /cc");
        helpLine(p, "/clan sethome | home", "дом клана");
        helpLine(p, "/clan glow [#RRGGBB|rainbow]", "подсветка соклановцев / смена цвета");
        helpLine(p, "/clan top", "рейтинг сильнейших кланов");
    }

    private void helpLine(Player p, String cmd, String desc) {
        p.sendMessage(ChatColor.YELLOW + cmd + ChatColor.GRAY + " — " + desc);
    }

    private void create(Player p, String[] args) {
        if (args.length < 2) { err(p, "Использование: /clan create <название>"); return; }
        if (playerClan.containsKey(p.getUniqueId())) { err(p, "Вы уже состоите в клане."); return; }
        String name = args[1];
        if (!name.matches("[A-Za-z0-9_]{3,12}")) {
            err(p, "Название: 3–12 символов, латиница, цифры и _.");
            return;
        }
        String key = name.toLowerCase();
        if (clans.containsKey(key)) { err(p, "Клан с таким названием уже существует."); return; }
        clans.put(key, new Clan(name, p.getUniqueId()));
        playerClan.put(p.getUniqueId(), key);
        applyPrefix(p);
        save();
        msg(p, ChatColor.GREEN + "Клан " + name + " создан! Меню клана: /clan");
    }

    private void invite(Player p, String[] args) {
        Clan clan = leaderClan(p);
        if (clan == null) return;
        if (args.length < 2) { err(p, "Использование: /clan invite <игрок>"); return; }
        Player target = Bukkit.getPlayerExact(args[1]);
        if (target == null) { err(p, "Игрок не в сети."); return; }
        doInvite(p, clan, target);
    }

    private void doInvite(Player leader, Clan clan, Player target) {
        if (playerClan.containsKey(target.getUniqueId())) { err(leader, "Игрок уже состоит в клане."); return; }
        String key = clan.name.toLowerCase();
        invites.put(target.getUniqueId(), key);
        Bukkit.getScheduler().runTaskLater(this,
                () -> invites.remove(target.getUniqueId(), key), 20L * 60);
        msg(leader, ChatColor.GREEN + "Приглашение отправлено игроку " + target.getName() + ".");
        msg(target, ChatColor.YELLOW + leader.getName() + " приглашает вас в клан " + clan.name
                + ". Введите /clan accept (60 сек) или откройте /clan.");
    }

    private void accept(Player p) {
        String key = invites.remove(p.getUniqueId());
        if (key == null) { err(p, "У вас нет приглашений."); return; }
        Clan clan = clans.get(key);
        if (clan == null) { err(p, "Этот клан больше не существует."); return; }
        if (playerClan.containsKey(p.getUniqueId())) { err(p, "Вы уже состоите в клане."); return; }
        clan.members.add(p.getUniqueId());
        playerClan.put(p.getUniqueId(), key);
        applyPrefix(p);
        save();
        msg(p, ChatColor.GREEN + "Вы вступили в клан " + clan.name + "!");
        broadcast(clan, ChatColor.YELLOW + p.getName() + " вступил в клан.", p.getUniqueId());
    }

    private void leave(Player p) {
        Clan clan = clanOf(p.getUniqueId());
        if (clan == null) { err(p, "Вы не состоите в клане."); return; }
        if (clan.leader.equals(p.getUniqueId())) {
            err(p, "Лидер не может выйти. Используйте /clan disband.");
            return;
        }
        removeMember(clan, p.getUniqueId());
        save();
        msg(p, ChatColor.GREEN + "Вы покинули клан " + clan.name + ".");
        broadcast(clan, ChatColor.YELLOW + p.getName() + " покинул клан.", null);
    }

    private void kick(Player p, String[] args) {
        Clan clan = leaderClan(p);
        if (clan == null) return;
        if (args.length < 2) { err(p, "Использование: /clan kick <игрок>"); return; }
        UUID target = null;
        for (UUID u : clan.members) {
            String n = Bukkit.getOfflinePlayer(u).getName();
            if (n != null && n.equalsIgnoreCase(args[1])) { target = u; break; }
        }
        if (target == null) { err(p, "Такого игрока нет в вашем клане."); return; }
        kickUuid(p, clan, target);
    }

    private void kickUuid(Player leader, Clan clan, UUID target) {
        if (target.equals(leader.getUniqueId())) { err(leader, "Нельзя выгнать самого себя."); return; }
        String name = Optional.ofNullable(Bukkit.getOfflinePlayer(target).getName()).orElse("игрок");
        removeMember(clan, target);
        save();
        msg(leader, ChatColor.GREEN + name + " исключён из клана.");
        Player online = Bukkit.getPlayer(target);
        if (online != null) msg(online, ChatColor.RED + "Вас исключили из клана " + clan.name + ".");
    }

    private void disband(Player p) {
        Clan clan = leaderClan(p);
        if (clan == null) return;
        broadcast(clan, ChatColor.RED + "Клан " + clan.name + " распущен.", null);
        for (UUID u : new ArrayList<>(clan.members)) removeMember(clan, u);
        clans.remove(clan.name.toLowerCase());
        Team t = Bukkit.getScoreboardManager().getMainScoreboard().getTeam("c_" + clan.name.toLowerCase());
        if (t != null) t.unregister();
        save();
    }

    private void info(Player p, String[] args) {
        Clan clan = args.length >= 2 ? clans.get(args[1].toLowerCase()) : clanOf(p.getUniqueId());
        if (clan == null) {
            err(p, args.length >= 2 ? "Клан не найден." : "Вы не состоите в клане.");
            return;
        }
        String members = clan.members.stream()
                .map(u -> {
                    String n = Bukkit.getOfflinePlayer(u).getName();
                    boolean on = Bukkit.getPlayer(u) != null;
                    return (on ? ChatColor.GREEN : ChatColor.GRAY) + (n == null ? "?" : n);
                })
                .collect(Collectors.joining(ChatColor.WHITE + ", "));
        msg(p, ChatColor.GOLD + "Клан " + clan.name);
        p.sendMessage(ChatColor.YELLOW + "Лидер: " + ChatColor.WHITE + nameOf(clan.leader));
        p.sendMessage(ChatColor.YELLOW + "Убийств/смертей: " + ChatColor.WHITE + clan.kills + "/" + clan.deaths);
        p.sendMessage(ChatColor.YELLOW + "Дом: " + ChatColor.WHITE + (clan.home == null ? "не установлен" : "установлен"));
        p.sendMessage(ChatColor.YELLOW + "Участники (" + clan.members.size() + "): " + members);
    }

    private void list(Player p) {
        if (clans.isEmpty()) { msg(p, ChatColor.GRAY + "Кланов пока нет."); return; }
        msg(p, ChatColor.GOLD + "Кланы:");
        clans.values().stream()
                .sorted(Comparator.comparingInt((Clan c) -> c.members.size()).reversed())
                .forEach(c -> p.sendMessage(ChatColor.YELLOW + c.name + ChatColor.GRAY
                        + " — " + c.members.size() + " уч."));
    }

    // ---------- Клановый чат ----------

    private void chat(Player p, String[] args, int from) {
        Clan clan = clanOf(p.getUniqueId());
        if (clan == null) { err(p, "Вы не состоите в клане."); return; }
        if (args.length <= from) {
            if (chatMode.remove(p.getUniqueId())) {
                msg(p, ChatColor.YELLOW + "Клановый чат выключен — вы пишете в общий чат.");
            } else {
                chatMode.add(p.getUniqueId());
                msg(p, ChatColor.GREEN + "Клановый чат включён — все сообщения видит только клан.");
            }
            return;
        }
        sendClanChat(clan, p.getName(), String.join(" ", Arrays.copyOfRange(args, from, args.length)));
    }

    private void sendClanChat(Clan clan, String sender, String text) {
        String line = ChatColor.DARK_GREEN + "[Клан] " + ChatColor.GREEN + sender
                + ChatColor.GRAY + ": " + ChatColor.WHITE + text;
        for (UUID u : clan.members) {
            Player pl = Bukkit.getPlayer(u);
            if (pl != null) pl.sendMessage(line);
        }
        getLogger().info("[ClanChat " + clan.name + "] " + sender + ": " + text);
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onChat(AsyncPlayerChatEvent e) {
        Player p = e.getPlayer();
        Clan clan = clanOf(p.getUniqueId());
        if (clan == null) return;
        if (chatMode.contains(p.getUniqueId())) {
            e.setCancelled(true);
            String text = e.getMessage();
            Bukkit.getScheduler().runTask(this, () -> sendClanChat(clan, p.getName(), text));
        } else {
            e.setFormat(ChatColor.GRAY + "[" + ChatColor.GOLD + clan.name + ChatColor.GRAY + "] "
                    + ChatColor.RESET + e.getFormat());
        }
    }

    // ---------- Дом клана ----------

    private void setHome(Player p) {
        Clan clan = leaderClan(p);
        if (clan == null) return;
        clan.home = p.getLocation();
        save();
        msg(p, ChatColor.GREEN + "Дом клана установлен.");
    }

    private void home(Player p) {
        Clan clan = clanOf(p.getUniqueId());
        if (clan == null) { err(p, "Вы не состоите в клане."); return; }
        if (clan.home == null) { err(p, "Дом клана не установлен."); return; }
        if (teleports.containsKey(p.getUniqueId())) { err(p, "Вы уже телепортируетесь."); return; }
        Location dest = clan.home.clone();
        msg(p, ChatColor.YELLOW + "Телепорт через 3 секунды, не двигайтесь.");
        UUID id = p.getUniqueId();
        teleports.put(id, Bukkit.getScheduler().runTaskLater(this, () -> {
            teleports.remove(id);
            p.teleport(dest);
            msg(p, ChatColor.GREEN + "Вы в доме клана.");
        }, HOME_WARMUP_TICKS));
    }

    private void cancelTeleport(Player p, String reason) {
        BukkitTask t = teleports.remove(p.getUniqueId());
        if (t != null) {
            t.cancel();
            err(p, reason);
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onMove(PlayerMoveEvent e) {
        if (teleports.isEmpty() || e.getTo() == null) return;
        Location a = e.getFrom(), b = e.getTo();
        if (a.getBlockX() != b.getBlockX() || a.getBlockY() != b.getBlockY() || a.getBlockZ() != b.getBlockZ()) {
            cancelTeleport(e.getPlayer(), "Телепорт отменён: вы подвигались.");
        }
    }

    // ---------- Урон, статистика ----------

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onDamage(EntityDamageByEntityEvent e) {
        if (!(e.getEntity() instanceof Player victim)) return;
        Player attacker = null;
        Entity d = e.getDamager();
        if (d instanceof Player pl) {
            attacker = pl;
        } else if (d instanceof Projectile proj) {
            ProjectileSource src = proj.getShooter();
            if (src instanceof Player pl) attacker = pl;
        }
        if (attacker == null || attacker.equals(victim)) return;
        String c1 = playerClan.get(attacker.getUniqueId());
        if (c1 != null && c1.equals(playerClan.get(victim.getUniqueId()))) {
            Clan clan = clans.get(c1);
            if (clan != null && !clan.friendlyFire) {
                e.setCancelled(true);
                attacker.sendActionBar(ChatColor.RED + "Нельзя бить соклановца!");
            }
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onAnyDamage(EntityDamageEvent e) {
        if (e.getEntity() instanceof Player p && !teleports.isEmpty()) {
            cancelTeleport(p, "Телепорт отменён: вы получили урон.");
        }
    }

    @EventHandler
    public void onDeath(PlayerDeathEvent e) {
        Player victim = e.getEntity();
        Player killer = victim.getKiller();
        if (killer == null || killer.equals(victim)) return;
        Clan vc = clanOf(victim.getUniqueId());
        Clan kc = clanOf(killer.getUniqueId());
        if (vc == kc) return;
        if (vc != null) { vc.deaths++; dirty = true; }
        if (kc != null) { kc.kills++; dirty = true; }
    }

    // ---------- Подсветка соклановцев ----------

    private int colorOf(UUID u) {
        return glowColor.getOrDefault(u, DEFAULT_COLOR);
    }

    private int currentRgb(UUID u) {
        int c = colorOf(u);
        if (c != RAINBOW) return c;
        float h = (System.currentTimeMillis() % 6000L) / 6000f;
        h = (Math.round(h * 36) / 36f) % 1f;
        return hsv(h);
    }

    private static int hsv(float h) {
        float x = h * 6, f = x - (int) x, q = 1 - f, r, g, b;
        switch (((int) x) % 6) {
            case 0 -> { r = 1; g = f; b = 0; }
            case 1 -> { r = q; g = 1; b = 0; }
            case 2 -> { r = 0; g = 1; b = f; }
            case 3 -> { r = 0; g = q; b = 1; }
            case 4 -> { r = f; g = 0; b = 1; }
            default -> { r = 1; g = 0; b = q; }
        }
        return ((int) (r * 255) << 16) | ((int) (g * 255) << 8) | (int) (b * 255);
    }

    private Map<EquipmentSlot, ItemStack> armorFor(int rgb) {
        return armorCache.computeIfAbsent(rgb, c -> {
            Map<EquipmentSlot, ItemStack> m = new EnumMap<>(EquipmentSlot.class);
            for (int i = 0; i < ARMOR_SLOTS.length; i++) {
                ItemStack it = new ItemStack(LEATHER[i]);
                LeatherArmorMeta meta = (LeatherArmorMeta) it.getItemMeta();
                meta.setColor(Color.fromRGB(c));
                it.setItemMeta(meta);
                m.put(ARMOR_SLOTS[i], it);
            }
            return m;
        });
    }

    private void glow(Player p, String[] args) {
        if (clanOf(p.getUniqueId()) == null) { err(p, "Вы не состоите в клане."); return; }
        if (args.length >= 2) {
            String a = args[1].toLowerCase();
            if (a.equals("rainbow") || a.equals("радуга")) { setColor(p, RAINBOW); return; }
            String hex = a.startsWith("#") ? a.substring(1) : a;
            if (hex.matches("[0-9a-f]{6}")) { setColor(p, Integer.parseInt(hex, 16)); return; }
            err(p, "Использование: /clan glow [#RRGGBB|rainbow]");
            return;
        }
        toggleGlow(p);
    }

    private void toggleGlow(Player p) {
        if (clanOf(p.getUniqueId()) == null) { err(p, "Вы не состоите в клане."); return; }
        if (armorMode.remove(p.getUniqueId())) {
            restoreArmor(p);
            msg(p, ChatColor.YELLOW + "Подсветка соклановцев выключена.");
        } else {
            armorMode.add(p.getUniqueId());
            msg(p, ChatColor.GREEN + "Подсветка включена (видите только вы).");
        }
    }

    private void setColor(Player p, int rgb) {
        glowColor.put(p.getUniqueId(), rgb);
        armorMode.add(p.getUniqueId());
        save();
        msg(p, ChatColor.GREEN + "Цвет подсветки: " + colorName(p.getUniqueId()) + ChatColor.GREEN + ". Подсветка включена.");
    }

    private String colorName(UUID u) {
        int c = colorOf(u);
        if (c == RAINBOW) return ChatColor.LIGHT_PURPLE + "Радуга";
        for (ColorOpt o : COLORS) if (o.rgb() == c) return hexColor(c) + o.name();
        return hexColor(c) + String.format("#%06X", c);
    }

    private static String hexColor(int rgb) {
        return net.md_5.bungee.api.ChatColor.of(String.format("#%06X", rgb)).toString();
    }

    /** Показывает включившим режим соклановцев в цветной коже (только на их клиенте). */
    private void tickArmor() {
        if (armorMode.isEmpty()) return;
        for (UUID u : new ArrayList<>(armorMode)) {
            Player viewer = Bukkit.getPlayer(u);
            if (viewer == null) continue;
            Clan clan = clanOf(u);
            if (clan == null) { armorMode.remove(u); continue; }
            Map<EquipmentSlot, ItemStack> items = armorFor(currentRgb(u));
            for (UUID m : clan.members) {
                if (m.equals(u)) continue;
                Player t = Bukkit.getPlayer(m);
                if (t == null || !t.getWorld().equals(viewer.getWorld())) continue;
                if (t.getLocation().distanceSquared(viewer.getLocation()) > ARMOR_RANGE_SQ) continue;
                for (EquipmentSlot slot : ARMOR_SLOTS) viewer.sendEquipmentChange(t, slot, items.get(slot));
            }
        }
    }

    private void restoreArmor(Player viewer) {
        for (Player t : Bukkit.getOnlinePlayers()) {
            if (t.equals(viewer) || !t.getWorld().equals(viewer.getWorld())) continue;
            for (EquipmentSlot slot : ARMOR_SLOTS) {
                viewer.sendEquipmentChange(t, slot, t.getInventory().getItem(slot));
            }
        }
    }

    // ---------- Меню: общее ----------

    private ItemStack item(Material mat, String name, List<String> lore) {
        ItemStack it = new ItemStack(mat);
        ItemMeta meta = it.getItemMeta();
        meta.setDisplayName(name);
        if (lore != null && !lore.isEmpty()) meta.setLore(lore);
        meta.addItemFlags(ItemFlag.values());
        it.setItemMeta(meta);
        return it;
    }

    private ItemStack item(Material mat, String name, String... lore) {
        return item(mat, name, Arrays.asList(lore));
    }

    private ItemStack glint(ItemStack it) {
        Enchantment en = Enchantment.getByKey(NamespacedKey.minecraft("unbreaking"));
        if (en != null) {
            ItemMeta meta = it.getItemMeta();
            meta.addEnchant(en, 1, true);
            it.setItemMeta(meta);
        }
        return it;
    }

    private ItemStack dyed(int rgb, String name, String... lore) {
        ItemStack it = item(Material.LEATHER_CHESTPLATE, name, lore);
        LeatherArmorMeta meta = (LeatherArmorMeta) it.getItemMeta();
        meta.setColor(Color.fromRGB(rgb));
        it.setItemMeta(meta);
        return it;
    }

    private ItemStack head(OfflinePlayer pl, String name, List<String> lore) {
        ItemStack it = item(Material.PLAYER_HEAD, name, lore);
        SkullMeta meta = (SkullMeta) it.getItemMeta();
        meta.setOwningPlayer(pl);
        it.setItemMeta(meta);
        return it;
    }

    private ItemStack head(OfflinePlayer pl, String name) {
        return head(pl, name, List.of());
    }

    private void fill(Inventory inv) {
        for (int i = 0; i < inv.getSize(); i++) if (inv.getItem(i) == null) inv.setItem(i, filler);
    }

    private ItemStack backItem() {
        return item(Material.ARROW, ChatColor.YELLOW + "← Назад");
    }

    private void later(Runnable r) {
        Bukkit.getScheduler().runTask(this, r);
    }

    private Menu newMenu(Type type, int size, String title) {
        Menu m = new Menu(type);
        m.inv = Bukkit.createInventory(m, size, title);
        return m;
    }

    private String state(boolean on) {
        return on ? ChatColor.GREEN + "ВКЛ" : ChatColor.RED + "ВЫКЛ";
    }

    private String kd(Clan c) {
        return String.format(Locale.US, "%.2f", c.deaths == 0 ? (double) c.kills : (double) c.kills / c.deaths);
    }

    private String nameOf(UUID u) {
        return Optional.ofNullable(Bukkit.getOfflinePlayer(u).getName()).orElse("?");
    }

    private ItemStack topItem() {
        return item(Material.GOLDEN_HELMET, ChatColor.GOLD + "Топ кланов",
                ChatColor.GRAY + "Сильнейшие кланы сервера", ChatColor.YELLOW + "Нажмите, чтобы открыть");
    }

    // ---------- Меню: главное ----------

    private void openMain(Player p) {
        UUID uid = p.getUniqueId();
        Clan c = clanOf(uid);
        Menu m = newMenu(Type.MAIN, 45, c == null
                ? ChatColor.DARK_GRAY + "Кланы"
                : ChatColor.DARK_GRAY + "Клан " + ChatColor.GOLD + c.name);
        Inventory inv = m.inv;

        if (c == null) {
            inv.setItem(13, item(Material.BOOK, ChatColor.YELLOW + "У вас нет клана",
                    ChatColor.GRAY + "Создайте свой: " + ChatColor.WHITE + "/clan create <название>",
                    ChatColor.GRAY + "или попросите лидера пригласить вас."));
            inv.setItem(22, topItem());
            String key = invites.get(uid);
            Clan invClan = key == null ? null : clans.get(key);
            if (invClan != null) {
                inv.setItem(24, glint(item(Material.LIME_WOOL, ChatColor.GREEN + "Принять приглашение",
                        ChatColor.GRAY + "Клан: " + ChatColor.GOLD + invClan.name,
                        ChatColor.YELLOW + "Нажмите, чтобы вступить")));
            }
            inv.setItem(44, item(Material.BARRIER, ChatColor.RED + "Закрыть"));
        } else {
            boolean leader = c.leader.equals(uid);
            long online = c.members.stream().filter(u -> Bukkit.getPlayer(u) != null).count();
            inv.setItem(4, item(Material.SHIELD, ChatColor.GOLD + "Клан " + c.name,
                    ChatColor.GRAY + "Лидер: " + ChatColor.WHITE + nameOf(c.leader),
                    ChatColor.GRAY + "Участники: " + ChatColor.WHITE + c.members.size()
                            + ChatColor.GRAY + " (онлайн " + ChatColor.GREEN + online + ChatColor.GRAY + ")",
                    ChatColor.GRAY + "Убийств: " + ChatColor.WHITE + c.kills
                            + ChatColor.GRAY + "  Смертей: " + ChatColor.WHITE + c.deaths,
                    ChatColor.GRAY + "K/D: " + ChatColor.WHITE + kd(c)));

            inv.setItem(20, head(p, ChatColor.AQUA + "Участники",
                    List.of(ChatColor.GRAY + "Всего: " + ChatColor.WHITE + c.members.size(),
                            ChatColor.YELLOW + "Нажмите, чтобы открыть")));

            boolean on = armorMode.contains(uid);
            ItemStack glow = dyed(currentRgb(uid), ChatColor.WHITE + "Подсветка соклановцев: " + state(on),
                    ChatColor.GRAY + "Союзники выделяются цветной кожей",
                    ChatColor.GRAY + "(видите только вы)",
                    ChatColor.GRAY + "Цвет: " + colorName(uid),
                    ChatColor.YELLOW + "Нажмите, чтобы переключить");
            inv.setItem(22, on ? glint(glow) : glow);

            inv.setItem(24, item(Material.CYAN_DYE, ChatColor.LIGHT_PURPLE + "Цвет подсветки",
                    ChatColor.GRAY + "Сейчас: " + colorName(uid),
                    ChatColor.YELLOW + "Нажмите, чтобы выбрать"));

            inv.setItem(29, item(Material.RED_BED, ChatColor.RED + "Дом клана",
                    ChatColor.GRAY + "Статус: " + (c.home == null ? ChatColor.RED + "не установлен" : ChatColor.GREEN + "установлен"),
                    ChatColor.YELLOW + "Нажмите, чтобы телепортироваться"));

            inv.setItem(30, item(Material.COMPASS, ChatColor.GOLD + "Поставить дом здесь",
                    leader ? ChatColor.YELLOW + "Нажмите, чтобы установить в вашей точке"
                           : ChatColor.DARK_GRAY + "Только лидер"));

            inv.setItem(31, item(Material.WRITABLE_BOOK, ChatColor.GREEN + "Клановый чат: " + state(chatMode.contains(uid)),
                    ChatColor.GRAY + "Сообщения видит только клан",
                    ChatColor.GRAY + "Быстро: " + ChatColor.WHITE + "/cc <текст>",
                    ChatColor.YELLOW + "Нажмите, чтобы переключить"));

            inv.setItem(33, item(Material.EMERALD, ChatColor.GREEN + "Пригласить игрока",
                    leader ? ChatColor.YELLOW + "Нажмите, чтобы выбрать из онлайна"
                           : ChatColor.DARK_GRAY + "Только лидер"));

            inv.setItem(38, topItem());

            inv.setItem(40, item(Material.IRON_SWORD, ChatColor.WHITE + "Огонь по своим: " + state(c.friendlyFire),
                    ChatColor.GRAY + "Могут ли соклановцы бить друг друга",
                    leader ? ChatColor.YELLOW + "Нажмите, чтобы переключить"
                           : ChatColor.DARK_GRAY + "Менять может только лидер"));

            inv.setItem(44, item(Material.BARRIER, ChatColor.RED + "Закрыть"));
        }
        fill(inv);
        p.openInventory(inv);
    }

    private void clickMain(Player p, int slot, ClickType click) {
        Clan c = clanOf(p.getUniqueId());
        if (c == null) {
            switch (slot) {
                case 22 -> later(() -> openTop(p));
                case 24 -> { accept(p); later(() -> openMain(p)); }
                case 44 -> later(p::closeInventory);
                default -> { }
            }
            return;
        }
        switch (slot) {
            case 20 -> later(() -> openMembers(p));
            case 22 -> { toggleGlow(p); later(() -> openMain(p)); }
            case 24 -> later(() -> openColors(p));
            case 29 -> later(() -> { p.closeInventory(); home(p); });
            case 30 -> { setHome(p); later(() -> openMain(p)); }
            case 31 -> { chat(p, new String[0], 0); later(() -> openMain(p)); }
            case 33 -> { if (leaderClan(p) != null) later(() -> openInvite(p)); }
            case 38 -> later(() -> openTop(p));
            case 40 -> {
                if (leaderClan(p) != null) {
                    c.friendlyFire = !c.friendlyFire;
                    save();
                    broadcast(c, ChatColor.YELLOW + "Огонь по своим теперь " + state(c.friendlyFire) + ChatColor.YELLOW + ".", null);
                    later(() -> openMain(p));
                }
            }
            case 44 -> later(p::closeInventory);
            default -> { }
        }
    }

    // ---------- Меню: участники ----------

    private void openMembers(Player p) {
        Clan c = clanOf(p.getUniqueId());
        if (c == null) return;
        boolean leader = c.leader.equals(p.getUniqueId());
        Menu m = newMenu(Type.MEMBERS, 54, ChatColor.DARK_GRAY + "Участники клана");
        int i = 0;
        for (UUID u : c.members) {
            if (i >= 45) break;
            m.ids.add(u.toString());
            OfflinePlayer off = Bukkit.getOfflinePlayer(u);
            boolean on = Bukkit.getPlayer(u) != null;
            List<String> lore = new ArrayList<>();
            lore.add(ChatColor.GRAY + "Роль: " + (u.equals(c.leader) ? ChatColor.GOLD + "Лидер" : ChatColor.WHITE + "Участник"));
            lore.add(ChatColor.GRAY + "Статус: " + (on ? ChatColor.GREEN + "в сети" : ChatColor.DARK_GRAY + "не в сети"));
            if (leader && !u.equals(p.getUniqueId())) lore.add(ChatColor.RED + "Нажмите, чтобы исключить");
            m.inv.setItem(i++, head(off, (on ? ChatColor.GREEN : ChatColor.GRAY) + nameOf(u), lore));
        }
        m.inv.setItem(49, backItem());
        fill(m.inv);
        p.openInventory(m.inv);
    }

    private void clickMembers(Player p, Menu m, int slot, ClickType click) {
        if (slot == 49) { later(() -> openMain(p)); return; }
        Clan c = clanOf(p.getUniqueId());
        if (c == null || slot >= m.ids.size()) return;
        UUID target = UUID.fromString(m.ids.get(slot));
        if (c.leader.equals(p.getUniqueId()) && !target.equals(p.getUniqueId()) && c.members.contains(target)) {
            later(() -> openKickConfirm(p, target));
        }
    }

    // ---------- Меню: приглашение ----------

    private void openInvite(Player p) {
        if (leaderClan(p) == null) return;
        Menu m = newMenu(Type.INVITE, 54, ChatColor.DARK_GRAY + "Пригласить игрока");
        int i = 0;
        for (Player t : Bukkit.getOnlinePlayers()) {
            if (i >= 45) break;
            if (t.equals(p) || playerClan.containsKey(t.getUniqueId())) continue;
            m.ids.add(t.getUniqueId().toString());
            m.inv.setItem(i++, head(t, ChatColor.GREEN + t.getName(),
                    List.of(ChatColor.YELLOW + "Нажмите, чтобы пригласить")));
        }
        if (i == 0) {
            m.inv.setItem(22, item(Material.BARRIER, ChatColor.GRAY + "Нет игроков без клана онлайн"));
        }
        m.inv.setItem(49, backItem());
        fill(m.inv);
        p.openInventory(m.inv);
    }

    private void clickInvite(Player p, Menu m, int slot) {
        if (slot == 49) { later(() -> openMain(p)); return; }
        Clan c = leaderClan(p);
        if (c == null || slot >= m.ids.size()) return;
        Player t = Bukkit.getPlayer(UUID.fromString(m.ids.get(slot)));
        if (t == null) { err(p, "Игрок вышел из сети."); later(() -> openInvite(p)); return; }
        doInvite(p, c, t);
    }

    // ---------- Меню: цвет ----------

    private void openColors(Player p) {
        UUID uid = p.getUniqueId();
        int cur = colorOf(uid);
        Menu m = newMenu(Type.COLORS, 36, ChatColor.DARK_GRAY + "Цвет подсветки");
        for (int i = 0; i < COLORS.size(); i++) {
            ColorOpt o = COLORS.get(i);
            ItemStack it = dyed(o.rgb(), hexColor(o.rgb()) + o.name(),
                    cur == o.rgb() ? ChatColor.GREEN + "✔ Выбран" : ChatColor.GRAY + "Нажмите, чтобы выбрать");
            m.inv.setItem(COLOR_SLOTS[i], cur == o.rgb() ? glint(it) : it);
        }
        ItemStack rainbow = item(Material.NETHER_STAR, ChatColor.LIGHT_PURPLE + "Радуга",
                ChatColor.GRAY + "Цвет плавно меняется",
                cur == RAINBOW ? ChatColor.GREEN + "✔ Выбран" : ChatColor.GRAY + "Нажмите, чтобы выбрать");
        m.inv.setItem(31, cur == RAINBOW ? glint(rainbow) : rainbow);
        m.inv.setItem(33, item(Material.PAPER, ChatColor.WHITE + "Свой цвет",
                ChatColor.GRAY + "Любой цвет командой:", ChatColor.YELLOW + "/clan glow #RRGGBB"));
        m.inv.setItem(27, backItem());
        fill(m.inv);
        p.openInventory(m.inv);
    }

    private void clickColors(Player p, int slot) {
        if (slot == 27) { later(() -> openMain(p)); return; }
        if (slot == 31) { setColor(p, RAINBOW); later(() -> openColors(p)); return; }
        for (int i = 0; i < COLOR_SLOTS.length; i++) {
            if (COLOR_SLOTS[i] == slot) {
                setColor(p, COLORS.get(i).rgb());
                later(() -> openColors(p));
                return;
            }
        }
    }

    // ---------- Рейтинг силы ----------

    private String topMode() {
        return getConfig().getString("top.mode", "power").toLowerCase();
    }

    private double score(Clan c) {
        switch (topMode()) {
            case "kills": return c.kills;
            case "kd": return c.deaths == 0 ? c.kills : (double) c.kills / c.deaths;
            case "members": return c.members.size();
            default:
                return c.kills * getConfig().getDouble("top.power.kill", 3)
                        - c.deaths * getConfig().getDouble("top.power.death", 1)
                        + c.members.size() * getConfig().getDouble("top.power.member", 5);
        }
    }

    private String scoreText(Clan c) {
        double s = score(c);
        return s == Math.floor(s) ? String.valueOf((long) s) : String.format(Locale.US, "%.2f", s);
    }

    private List<String> formulaLines() {
        return switch (topMode()) {
            case "kills" -> List.of(ChatColor.GRAY + "Сила = число убийств");
            case "kd" -> List.of(ChatColor.GRAY + "Сила = убийства / смерти");
            case "members" -> List.of(ChatColor.GRAY + "Сила = число участников");
            default -> List.of(
                    ChatColor.GRAY + "Сила = убийства × " + getConfig().getDouble("top.power.kill", 3),
                    ChatColor.GRAY + "− смерти × " + getConfig().getDouble("top.power.death", 1),
                    ChatColor.GRAY + "+ участники × " + getConfig().getDouble("top.power.member", 5));
        };
    }

    private List<Clan> sortedClans(int limit) {
        return clans.values().stream()
                .sorted(Comparator.comparingDouble((Clan c) -> score(c)).reversed()
                        .thenComparing(Comparator.comparingInt((Clan c) -> c.kills).reversed())
                        .thenComparing(Comparator.comparingInt((Clan c) -> c.members.size()).reversed()))
                .limit(limit)
                .collect(Collectors.toList());
    }

    private void topChat(Player p) {
        List<Clan> top = sortedClans(10);
        if (top.isEmpty()) { msg(p, ChatColor.GRAY + "Кланов пока нет."); return; }
        msg(p, ChatColor.GOLD + "=== Топ кланов ===");
        int i = 1;
        for (Clan c : top) {
            String place = i == 1 ? ChatColor.GOLD + "1" : i == 2 ? ChatColor.GRAY + "2"
                    : i == 3 ? ChatColor.RED + "3" : ChatColor.WHITE + String.valueOf(i);
            p.sendMessage(place + ". " + ChatColor.YELLOW + c.name + ChatColor.GRAY + " — сила "
                    + ChatColor.GOLD + scoreText(c) + ChatColor.GRAY + " (уб. " + c.kills + ", см. " + c.deaths
                    + ", уч. " + c.members.size() + ")");
            i++;
        }
        for (String line : formulaLines()) p.sendMessage(line);
    }

    private void reload(Player p) {
        if (!p.hasPermission("clan.admin")) { err(p, "Нет прав."); return; }
        reloadConfig();
        msg(p, ChatColor.GREEN + "Настройки перезагружены.");
    }

    // ---------- Меню: подтверждение исключения ----------

    private void openKickConfirm(Player p, UUID target) {
        Clan c = leaderClan(p);
        if (c == null || !c.members.contains(target)) return;
        Menu m = newMenu(Type.CONFIRM, 27, ChatColor.DARK_GRAY + "Исключить игрока?");
        m.ids.add(target.toString());
        m.inv.setItem(13, head(Bukkit.getOfflinePlayer(target), ChatColor.WHITE + nameOf(target)));
        m.inv.setItem(11, item(Material.RED_WOOL, ChatColor.RED + "Исключить", ChatColor.GRAY + "Игрок покинет клан"));
        m.inv.setItem(15, item(Material.LIME_WOOL, ChatColor.GREEN + "Отмена"));
        fill(m.inv);
        p.openInventory(m.inv);
    }

    private void clickConfirm(Player p, Menu m, int slot) {
        if (slot == 15) { later(() -> openMembers(p)); return; }
        if (slot != 11) return;
        Clan c = leaderClan(p);
        if (c != null) {
            UUID target = UUID.fromString(m.ids.get(0));
            if (c.members.contains(target)) kickUuid(p, c, target);
        }
        later(() -> openMembers(p));
    }

    // ---------- Меню: топ кланов ----------

    private void openTop(Player p) {
        Clan mine = clanOf(p.getUniqueId());
        Menu m = newMenu(Type.TOP, 36, ChatColor.DARK_GRAY + "Топ кланов");
        List<Clan> sorted = sortedClans(TOP_SLOTS.length);
        for (int i = 0; i < sorted.size(); i++) {
            Clan c = sorted.get(i);
            Material mat = switch (i) {
                case 0 -> Material.GOLD_BLOCK;
                case 1 -> Material.IRON_BLOCK;
                case 2 -> Material.COPPER_BLOCK;
                default -> Material.WHITE_BANNER;
            };
            String color = i == 0 ? ChatColor.GOLD.toString() : i == 1 ? ChatColor.GRAY.toString()
                    : i == 2 ? ChatColor.RED.toString() : ChatColor.WHITE.toString();
            List<String> lore = new ArrayList<>(List.of(
                    ChatColor.GRAY + "Лидер: " + ChatColor.WHITE + nameOf(c.leader),
                    ChatColor.GRAY + "Участники: " + ChatColor.WHITE + c.members.size(),
                    ChatColor.GRAY + "Убийств: " + ChatColor.WHITE + c.kills
                            + ChatColor.GRAY + "  Смертей: " + ChatColor.WHITE + c.deaths,
                    ChatColor.GRAY + "K/D: " + ChatColor.WHITE + kd(c),
                    ChatColor.GRAY + "Сила: " + ChatColor.GOLD + scoreText(c)));
            boolean isMine = c == mine;
            if (isMine) lore.add(ChatColor.GREEN + "← ваш клан");
            ItemStack it = item(mat, color + "#" + (i + 1) + " " + c.name, lore);
            m.inv.setItem(TOP_SLOTS[i], isMine ? glint(it) : it);
        }
        if (sorted.isEmpty()) m.inv.setItem(13, item(Material.BARRIER, ChatColor.GRAY + "Кланов пока нет"));
        m.inv.setItem(35, item(Material.PAPER, ChatColor.WHITE + "Как считается сила", formulaLines()));
        m.inv.setItem(31, backItem());
        fill(m.inv);
        p.openInventory(m.inv);
    }

    // ---------- Меню: обработка кликов ----------

    @EventHandler
    public void onMenuClick(InventoryClickEvent e) {
        if (!(e.getInventory().getHolder() instanceof Menu m)) return;
        e.setCancelled(true);
        if (!(e.getWhoClicked() instanceof Player p)) return;
        if (e.getClickedInventory() == null || !e.getClickedInventory().equals(e.getInventory())) return;
        ItemStack cur = e.getCurrentItem();
        if (cur == null || cur.getType().isAir() || cur.getType() == Material.GRAY_STAINED_GLASS_PANE) return;
        int slot = e.getSlot();
        p.playSound(p.getLocation(), Sound.UI_BUTTON_CLICK, 0.6f, 1f);
        switch (m.type) {
            case MAIN -> clickMain(p, slot, e.getClick());
            case MEMBERS -> clickMembers(p, m, slot, e.getClick());
            case INVITE -> clickInvite(p, m, slot);
            case COLORS -> clickColors(p, slot);
            case TOP -> { if (slot == 31) later(() -> openMain(p)); }
            case CONFIRM -> clickConfirm(p, m, slot);
        }
    }

    @EventHandler
    public void onMenuDrag(InventoryDragEvent e) {
        if (e.getInventory().getHolder() instanceof Menu) e.setCancelled(true);
    }

    // ---------- Вспомогательное ----------

    /** Убирает игрока из клана вместе с префиксом, чатом и подсветкой. */
    private void removeMember(Clan clan, UUID id) {
        for (UUID m : clan.members) {
            Player pl = Bukkit.getPlayer(m);
            if (pl != null && armorMode.contains(m)) restoreArmor(pl);
        }
        OfflinePlayer off = Bukkit.getOfflinePlayer(id);
        clan.members.remove(id);
        playerClan.remove(id);
        chatMode.remove(id);
        armorMode.remove(id);
        applyPrefix(off);
    }

    private void applyPrefix(OfflinePlayer pl) {
        String name = pl.getName();
        if (name == null) return;
        Scoreboard sb = Bukkit.getScoreboardManager().getMainScoreboard();
        for (Team t : sb.getTeams()) {
            if (t.getName().startsWith("c_")) t.removeEntry(name);
        }
        Clan c = clanOf(pl.getUniqueId());
        if (c == null) return;
        String tn = "c_" + c.name.toLowerCase();
        Team t = sb.getTeam(tn);
        if (t == null) t = sb.registerNewTeam(tn);
        t.setPrefix(ChatColor.GRAY + "[" + ChatColor.GOLD + c.name + ChatColor.GRAY + "] " + ChatColor.RESET);
        t.addEntry(name);
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent e) {
        applyPrefix(e.getPlayer());
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) {
        UUID id = e.getPlayer().getUniqueId();
        chatMode.remove(id);
        armorMode.remove(id);
        BukkitTask t = teleports.remove(id);
        if (t != null) t.cancel();
    }

    private Clan clanOf(UUID u) {
        String key = playerClan.get(u);
        return key == null ? null : clans.get(key);
    }

    private Clan leaderClan(Player p) {
        Clan clan = clanOf(p.getUniqueId());
        if (clan == null) { err(p, "Вы не состоите в клане."); return null; }
        if (!clan.leader.equals(p.getUniqueId())) { err(p, "Это может только лидер клана."); return null; }
        return clan;
    }

    private void broadcast(Clan clan, String text, UUID except) {
        for (UUID u : clan.members) {
            if (u.equals(except)) continue;
            Player pl = Bukkit.getPlayer(u);
            if (pl != null) msg(pl, text);
        }
    }

    private void msg(Player p, String text) {
        p.sendMessage(ChatColor.DARK_AQUA + "[Клан] " + ChatColor.RESET + text);
    }

    private void err(Player p, String text) {
        msg(p, ChatColor.RED + text);
    }

    // ---------- Автодополнение ----------

    @Override
    public List<String> onTabComplete(CommandSender s, Command c, String a, String[] args) {
        if (c.getName().equalsIgnoreCase("cc")) return Collections.emptyList();
        if (args.length == 1) {
            return SUB.stream().filter(x -> x.startsWith(args[0].toLowerCase())).collect(Collectors.toList());
        }
        if (args.length == 2) {
            String sub = args[0].toLowerCase();
            String pref = args[1].toLowerCase();
            if (sub.equals("invite")) {
                return Bukkit.getOnlinePlayers().stream().map(Player::getName)
                        .filter(n -> n.toLowerCase().startsWith(pref)).collect(Collectors.toList());
            }
            if (sub.equals("info")) {
                return clans.values().stream().map(cl -> cl.name)
                        .filter(n -> n.toLowerCase().startsWith(pref)).collect(Collectors.toList());
            }
            if (sub.equals("glow") && "rainbow".startsWith(pref)) return List.of("rainbow");
            if (sub.equals("kick") && s instanceof Player p) {
                Clan clan = clanOf(p.getUniqueId());
                if (clan != null) {
                    return clan.members.stream().map(u -> Bukkit.getOfflinePlayer(u).getName())
                            .filter(n -> n != null && n.toLowerCase().startsWith(pref))
                            .collect(Collectors.toList());
                }
            }
        }
        return Collections.emptyList();
    }
}
