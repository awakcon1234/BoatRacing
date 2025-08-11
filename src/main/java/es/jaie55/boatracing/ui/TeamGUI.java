package es.jaie55.boatracing.ui;

import es.jaie55.boatracing.BoatRacingPlugin;
import es.jaie55.boatracing.team.Team;
import es.jaie55.boatracing.team.TeamManager;
import org.bukkit.Bukkit;
import es.jaie55.boatracing.util.Text;
import org.bukkit.DyeColor;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.HumanEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.EventPriority;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.BannerMeta;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.SkullMeta;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import net.kyori.adventure.text.Component;
import net.wesjd.anvilgui.AnvilGUI;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

public class TeamGUI implements Listener {
    private static final String BLANK = " "; // single space to keep anvil preview blank without symbols
    private static final Component TITLE = Text.title("Teams");
    private static final Component TITLE_TEAM = Text.title("Team");
    private static final String TEAM_TITLE_PREFIX = "Team • ";
    private static final Component TITLE_MEMBER = Text.title("Your profile");
    private static final Component TITLE_COLOR_PICKER = Text.title("Choose team color");
    private static final Component TITLE_BOAT_PICKER = Text.title("Choose your boat");
    private static final Component TITLE_DISBAND_CONFIRM = Text.title("Disband team?");
    private static final Component TITLE_LEAVE_CONFIRM = Text.title("Leave team?");
    private static final Component TITLE_MEMBER_ACTIONS = Text.title("Member actions");
    private static final Component TITLE_TRANSFER_CONFIRM = Text.title("Transfer leadership?");
    private static final Component TITLE_KICK_CONFIRM = Text.title("Kick member?");
    // Allowed boats (including chest variants and pale oak)
    private static final Material[] ALLOWED_BOATS = new Material[] {
        Material.OAK_BOAT, Material.OAK_CHEST_BOAT,
        Material.SPRUCE_BOAT, Material.SPRUCE_CHEST_BOAT,
        Material.BIRCH_BOAT, Material.BIRCH_CHEST_BOAT,
        Material.JUNGLE_BOAT, Material.JUNGLE_CHEST_BOAT,
        Material.ACACIA_BOAT, Material.ACACIA_CHEST_BOAT,
        Material.DARK_OAK_BOAT, Material.DARK_OAK_CHEST_BOAT,
        Material.MANGROVE_BOAT, Material.MANGROVE_CHEST_BOAT,
        Material.CHERRY_BOAT, Material.CHERRY_CHEST_BOAT,
        Material.PALE_OAK_BOAT, Material.PALE_OAK_CHEST_BOAT
    };
    private final BoatRacingPlugin plugin;
    private final NamespacedKey KEY_TEAM_ID;
    private final NamespacedKey KEY_TARGET_ID;
    // Text input handled via AnvilGUI

    public TeamGUI(BoatRacingPlugin plugin) {
        this.plugin = plugin;
    this.KEY_TEAM_ID = new NamespacedKey(plugin, "team-id");
    this.KEY_TARGET_ID = new NamespacedKey(plugin, "target-id");
    }

    public void openMain(Player p) {
        TeamManager tm = plugin.getTeamManager();
        int size = 54;
    Inventory inv = Bukkit.createInventory(null, size, TITLE);

        int slot = 0;
        for (Team t : tm.getTeamsSnapshot()) {
            ItemStack item = new ItemStack(bannerForColor(t.getColor()));
            BannerMeta meta = (BannerMeta) item.getItemMeta();
            if (meta != null) {
                // Styled team name with team color
                meta.displayName(Text.item("&l" + t.getName()));
                List<String> lore = new ArrayList<>();
                // Members count
                lore.add("&7Members: &f" + t.getMembers().size() + "/" + plugin.getTeamManager().getMaxMembers());
                // Members list (dropdown-like on hover)
                if (!t.getMembers().isEmpty()) {
                    lore.add(" ");
                    lore.add("&8Members:");
                    for (UUID uid : t.getMembers()) {
                        String name = Bukkit.getOfflinePlayer(uid).getName();
                        lore.add("&7- &f" + (name != null ? name : uid.toString().substring(0, 8)));
                    }
                }
                lore.add(" ");
                lore.add("&eClick: &fOpen");
                meta.lore(Text.lore(lore));
                PersistentDataContainer pdc = meta.getPersistentDataContainer();
                pdc.set(KEY_TEAM_ID, PersistentDataType.STRING, t.getId().toString());
                item.setItemMeta(meta);
            }
            inv.setItem(slot++, item);
            if (slot >= size - 9) break;
        }

    int base = size - 9;
    // Decorate footer with light gray panes
    fillRow(inv, base, pane(Material.LIGHT_GRAY_STAINED_GLASS_PANE));
    inv.setItem(base + 4, button(Material.ANVIL, Text.item("&a&lCreate team")));
    inv.setItem(base + 8, button(Material.BARRIER, Text.item("&c&lClose")));

    p.openInventory(inv);
    p.playSound(p.getLocation(), org.bukkit.Sound.UI_BUTTON_CLICK, 0.8f, 1.2f);
    }

    private static ItemStack button(Material mat, Component name) {
        ItemStack it = new ItemStack(mat);
        ItemMeta im = it.getItemMeta();
        if (im != null) {
            im.displayName(name);
            im.addItemFlags(ItemFlag.values());
            it.setItemMeta(im);
        }
        return it;
    }

    private static Material bannerForColor(DyeColor color) {
        try {
            return Material.valueOf(color.name() + "_BANNER");
        } catch (IllegalArgumentException ex) {
            return Material.WHITE_BANNER;
        }
    }

    // UI helpers for colored accents and fillers
    private static Material paneForColor(DyeColor color) {
        if (color == null) return Material.WHITE_STAINED_GLASS_PANE;
        try {
            return Material.valueOf(color.name() + "_STAINED_GLASS_PANE");
        } catch (IllegalArgumentException ex) {
            return Material.WHITE_STAINED_GLASS_PANE;
        }
    }

    private static ItemStack pane(Material mat) {
        ItemStack it = new ItemStack(mat);
        ItemMeta im = it.getItemMeta();
        if (im != null) {
            im.displayName(Component.text(" "));
            im.addItemFlags(ItemFlag.values());
            it.setItemMeta(im);
        }
        return it;
    }

    // colorCodeFor removed: using vanilla titles for team names

    private static boolean isEmpty(ItemStack it) { return it == null || it.getType() == Material.AIR; }

    private static void fillEmptyWith(Inventory inv, ItemStack filler) {
        for (int i = 0; i < inv.getSize(); i++) {
            if (isEmpty(inv.getItem(i))) inv.setItem(i, filler);
        }
    }

    private static void fillRow(Inventory inv, int startIndex, ItemStack filler) {
        for (int i = 0; i < 9; i++) {
            if (isEmpty(inv.getItem(startIndex + i))) inv.setItem(startIndex + i, filler);
        }
    }

    // nextColor removed; color selection now uses a picker GUI

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onClick(InventoryClickEvent e) {
    if (e.getView().getTopInventory() == null) return;
    Component title = e.getView().title();
    String plainTitle = Text.plain(title);
    boolean inMain = plainTitle.equals(Text.plain(TITLE));
    boolean inTeam = plainTitle.startsWith(TEAM_TITLE_PREFIX) || plainTitle.equals(Text.plain(TITLE_TEAM));
    boolean inMember = plainTitle.equals(Text.plain(TITLE_MEMBER));
    boolean inColorPicker = plainTitle.equals(Text.plain(TITLE_COLOR_PICKER));
    boolean inBoatPicker = plainTitle.equals(Text.plain(TITLE_BOAT_PICKER));
    boolean inDisbandConfirm = plainTitle.equals(Text.plain(TITLE_DISBAND_CONFIRM));
    boolean inLeaveConfirm = plainTitle.equals(Text.plain(TITLE_LEAVE_CONFIRM));
    boolean inMemberActions = plainTitle.equals(Text.plain(TITLE_MEMBER_ACTIONS));
    boolean inTransferConfirm = plainTitle.equals(Text.plain(TITLE_TRANSFER_CONFIRM));
    boolean inKickConfirm = plainTitle.equals(Text.plain(TITLE_KICK_CONFIRM));
    if (!inMain && !inTeam && !inMember && !inColorPicker && !inBoatPicker && !inDisbandConfirm && !inLeaveConfirm && !inMemberActions && !inTransferConfirm && !inKickConfirm) return;
        // Cancel clicks in both top and bottom to prevent item pickup/moves while GUI is open
        e.setCancelled(true);
        // Only handle interactions from the top inventory GUI
        if (e.getClickedInventory() == null || e.getClickedInventory() != e.getView().getTopInventory()) return;
        HumanEntity who = e.getWhoClicked();
        if (!(who instanceof Player)) return;
        Player p = (Player) who;
        int slot = e.getSlot();
        int size = e.getInventory().getSize();
        int base = size - 9;
        // Close button only in main menu at base+8
        if (inMain && slot == base + 8) {
            p.closeInventory();
            p.playSound(p.getLocation(), org.bukkit.Sound.UI_BUTTON_CLICK, 0.8f, 0.9f);
            return;
        }
    if (inMain && slot == base + 4) {
            TeamManager tm = plugin.getTeamManager();
            if (tm.getTeamByMember(p.getUniqueId()).isPresent()) {
                p.sendMessage(Text.colorize(plugin.pref() + "&cYou are already in a team."));
                p.playSound(p.getLocation(), org.bukkit.Sound.BLOCK_NOTE_BLOCK_BASS, 0.7f, 0.7f);
                return;
            }
            openAnvilForCreate(p);
            p.playSound(p.getLocation(), org.bukkit.Sound.UI_BUTTON_CLICK, 0.9f, 1.2f);
            return;
        }

    ItemStack clicked = e.getCurrentItem();
    if (clicked == null) return;
    ItemMeta meta = clicked.getItemMeta();
    if (meta == null) return;
    if (inMain) {
            // Main list interactions: left open, right join/leave, shift manage
            // uid not needed here since we only open the team view from the list
            ClickType click = e.getClick();

            // Any click opens team view; join/leave handled inside team view via buttons
            if (click.isLeftClick() || click.isRightClick() || click.isCreativeAction() || click.isKeyboardClick()) {
        String idStr = meta.getPersistentDataContainer().get(KEY_TEAM_ID, PersistentDataType.STRING);
        if (idStr == null) return;
        UUID teamId; try { teamId = UUID.fromString(idStr); } catch (Exception ex) { return; }
        Team target = plugin.getTeamManager().getTeams().stream().filter(t -> t.getId().equals(teamId)).findFirst().orElse(null);
        if (target == null) return;
        openTeamView(p, target);
                p.playSound(p.getLocation(), org.bukkit.Sound.UI_BUTTON_CLICK, 0.9f, 1.3f);
                return;
            }
    } else if (inTeam) {
            // In team view: buttons
            if (slot == base) {
                // Back
                openMain(p);
                p.playSound(p.getLocation(), org.bukkit.Sound.UI_BUTTON_CLICK, 0.9f, 1.1f);
                return;
            }

            ItemStack it = e.getCurrentItem();
            if (it == null) return;
            ItemMeta im = it.getItemMeta();
            if (im == null) return;
            String tid = im.getPersistentDataContainer().get(KEY_TEAM_ID, PersistentDataType.STRING);
            Team team = null;
            if (tid != null) {
                team = plugin.getTeamManager().getTeams().stream().filter(t -> t.getId().toString().equals(tid)).findFirst().orElse(null);
            } else {
                team = plugin.getTeamManager().getTeamByMember(p.getUniqueId()).orElse(null);
            }
            if (team == null) return;

            // Identify buttons by material
            if (it.getType() == Material.PAPER) {
                // Rename team (leader only)
                if (!team.isLeader(p.getUniqueId())) {
                    p.sendMessage(Text.colorize(plugin.pref() + "&cOnly the team leader can rename the team."));
                    p.playSound(p.getLocation(), org.bukkit.Sound.BLOCK_NOTE_BLOCK_BASS, 0.8f, 0.6f);
                    return;
                }
                openAnvilForName(p, team);
                p.playSound(p.getLocation(), org.bukkit.Sound.UI_BUTTON_CLICK, 0.9f, 1.25f);
            } else if (isDye(it.getType())) {
                // Open color picker (leader only)
                if (!team.isLeader(p.getUniqueId())) {
                    p.sendMessage(Text.colorize(plugin.pref() + "&cOnly the team leader can change the color."));
                    p.playSound(p.getLocation(), org.bukkit.Sound.BLOCK_NOTE_BLOCK_BASS, 0.8f, 0.6f);
                    return;
                }
                openColorPicker(p, team);
                p.playSound(p.getLocation(), org.bukkit.Sound.UI_BUTTON_CLICK, 0.9f, 1.2f);
            } else if (it.getType() == Material.GREEN_CONCRETE) {
                // Join team
                if (team.getMembers().size() >= plugin.getTeamManager().getMaxMembers()) {
                    p.sendMessage(Text.colorize(plugin.pref() + "&cThis team is full."));
                    p.playSound(p.getLocation(), org.bukkit.Sound.BLOCK_NOTE_BLOCK_BASS, 0.8f, 0.6f);
                    return;
                }
                if (plugin.getTeamManager().getTeamByMember(p.getUniqueId()).isPresent()) {
                    p.sendMessage(Text.colorize(plugin.pref() + "&cYou are already in a team. Leave it first."));
                    p.playSound(p.getLocation(), org.bukkit.Sound.BLOCK_NOTE_BLOCK_BASS, 0.8f, 0.6f);
                    return;
                }
                team.addMember(p.getUniqueId());
                plugin.getTeamManager().save();
                p.sendMessage(Text.colorize(plugin.pref() + "&aYou joined " + team.getName()));
                // Notify other team members
                for (java.util.UUID m : team.getMembers()) {
                    if (m.equals(p.getUniqueId())) continue;
                    org.bukkit.OfflinePlayer op = Bukkit.getOfflinePlayer(m);
                    if (op.isOnline() && op.getPlayer() != null) {
                        op.getPlayer().sendMessage(Text.colorize(plugin.pref() + "&e" + p.getName() + " joined the team."));
                    }
                }
                p.playSound(p.getLocation(), org.bukkit.Sound.ENTITY_EXPERIENCE_ORB_PICKUP, 0.8f, 1.3f);
                openTeamView(p, team);
            } else if (it.getType() == Material.RED_CONCRETE) {
                // Leave team (non-leader) -> open confirmation menu instead of immediate leave
                if (!team.isMember(p.getUniqueId())) return;
                if (team.isLeader(p.getUniqueId())) {
                    p.sendMessage(Text.colorize(plugin.pref() + "&cLeaders cannot leave here. Transfer leadership first."));
                    p.playSound(p.getLocation(), org.bukkit.Sound.BLOCK_NOTE_BLOCK_BASS, 0.8f, 0.6f);
                    return;
                }
                if (team.getMembers().size() <= 1) {
                    p.sendMessage(Text.colorize(plugin.pref() + "&cYou can't leave if the team would be empty."));
                    p.playSound(p.getLocation(), org.bukkit.Sound.BLOCK_NOTE_BLOCK_BASS, 0.8f, 0.6f);
                    return;
                }
                openLeaveConfirm(p, team);
                p.playSound(p.getLocation(), org.bukkit.Sound.UI_BUTTON_CLICK, 0.9f, 1.1f);
            } else if (it.getType() == Material.PLAYER_HEAD) {
                // Player head: own profile; or member actions if leader clicking someone else
                if (im instanceof SkullMeta) {
                    SkullMeta sm = (SkullMeta) im;
                    if (sm.getOwningPlayer() != null) {
                        java.util.UUID memberId = sm.getOwningPlayer().getUniqueId();
                        if (memberId.equals(p.getUniqueId())) {
                            openMemberProfile(p, team);
                            p.playSound(p.getLocation(), org.bukkit.Sound.UI_BUTTON_CLICK, 0.9f, 1.2f);
                        } else if (team.isLeader(p.getUniqueId())) {
                            openMemberActions(p, team, memberId);
                            p.playSound(p.getLocation(), org.bukkit.Sound.UI_BUTTON_CLICK, 0.9f, 1.2f);
                        }
                    }
                }
            } else if (it.getType() == Material.TNT) {
                // Open disband confirmation (leader only)
                if (!team.isLeader(p.getUniqueId())) {
                    p.sendMessage(Text.colorize(plugin.pref() + "&cOnly the team leader can disband the team."));
                    p.playSound(p.getLocation(), org.bukkit.Sound.BLOCK_NOTE_BLOCK_BASS, 0.8f, 0.6f);
                    return;
                }
                openDisbandConfirm(p, team);
                p.playSound(p.getLocation(), org.bukkit.Sound.UI_BUTTON_CLICK, 0.9f, 1.1f);
            }
    } else if (inMember) {
            // Member profile view
            if (slot == e.getInventory().getSize() - 9) {
                // Back to team view
                Team team = plugin.getTeamManager().getTeamByMember(p.getUniqueId()).orElse(null);
                if (team != null) {
                    openTeamView(p, team);
                    p.playSound(p.getLocation(), org.bukkit.Sound.UI_BUTTON_CLICK, 0.9f, 1.1f);
                }
                return;
            }
            ItemStack it = e.getCurrentItem();
            if (it == null) return;
            Team team = plugin.getTeamManager().getTeamByMember(p.getUniqueId()).orElse(null);
            if (team == null) { p.closeInventory(); return; }
            if (it.getType() == Material.NAME_TAG) {
                openAnvilForRacer(p, team);
                p.playSound(p.getLocation(), org.bukkit.Sound.BLOCK_NOTE_BLOCK_PLING, 0.9f, 1.6f);
            } else if (isBoatItem(it.getType())) {
                openBoatPicker(p, team);
                p.playSound(p.getLocation(), org.bukkit.Sound.UI_BUTTON_CLICK, 0.9f, 1.2f);
            }
        } else if (inColorPicker) {
            // Choose color
            ItemStack it = e.getCurrentItem();
            if (it == null) return;
            // Back arrow handling
            if (slot == e.getInventory().getSize() - 9 || it.getType() == Material.ARROW) {
                Team backTeam = plugin.getTeamManager().getTeamByMember(p.getUniqueId()).orElse(null);
                if (backTeam != null) openTeamView(p, backTeam);
                p.playSound(p.getLocation(), org.bukkit.Sound.UI_BUTTON_CLICK, 0.9f, 1.1f);
                return;
            }
            ItemMeta im = it.getItemMeta();
            if (im == null) return;
            String tid = im.getPersistentDataContainer().get(KEY_TEAM_ID, PersistentDataType.STRING);
            if (tid == null) return;
            Team team = plugin.getTeamManager().getTeams().stream().filter(t -> t.getId().toString().equals(tid)).findFirst().orElse(null);
            if (team == null) { p.closeInventory(); return; }
            if (!team.isLeader(p.getUniqueId())) {
                p.sendMessage(Text.colorize(plugin.pref() + "&cOnly the team leader can change the color."));
                p.playSound(p.getLocation(), org.bukkit.Sound.BLOCK_NOTE_BLOCK_BASS, 0.8f, 0.6f);
                return;
            }
            // Derive color from item type name prefix
            DyeColor chosen = dyeFromBanner(it.getType());
            if (chosen == null) return;
            team.setColor(chosen);
            plugin.getTeamManager().save();
            p.sendMessage(Text.colorize(plugin.pref() + "&aTeam color set to " + chosen.name() + "."));
            p.playSound(p.getLocation(), org.bukkit.Sound.ENTITY_EXPERIENCE_ORB_PICKUP, 0.8f, 1.3f);
            openTeamView(p, team);
    } else if (inBoatPicker) {
            // Choose boat for member
            ItemStack it = e.getCurrentItem();
            if (it == null) return;
            // Back arrow handling
            if (slot == e.getInventory().getSize() - 9 || it.getType() == Material.ARROW) {
                Team backTeam = plugin.getTeamManager().getTeamByMember(p.getUniqueId()).orElse(null);
                if (backTeam != null) openMemberProfile(p, backTeam);
                p.playSound(p.getLocation(), org.bukkit.Sound.UI_BUTTON_CLICK, 0.9f, 1.1f);
                return;
            }
            ItemMeta im = it.getItemMeta();
            if (im == null) return;
            String tid = im.getPersistentDataContainer().get(KEY_TEAM_ID, PersistentDataType.STRING);
            if (tid == null) return;
            Team team = plugin.getTeamManager().getTeams().stream().filter(t -> t.getId().toString().equals(tid)).findFirst().orElse(null);
            if (team == null) { p.closeInventory(); return; }
            Material chosen = it.getType();
            if (!isBoatItem(chosen)) return;
            team.setBoatType(p.getUniqueId(), chosen.name());
            plugin.getTeamManager().save();
            p.sendMessage(Text.colorize(plugin.pref() + "&aBoat type set to " + chosen.name() + "."));
            p.playSound(p.getLocation(), org.bukkit.Sound.ENTITY_EXPERIENCE_ORB_PICKUP, 0.8f, 1.3f);
            openMemberProfile(p, team);
    } else if (inDisbandConfirm) {
            // Confirm disband flow
            ItemStack it = e.getCurrentItem();
            if (it == null) return;
            ItemMeta im = it.getItemMeta();
            if (im == null) return;
            String tid = im.getPersistentDataContainer().get(KEY_TEAM_ID, PersistentDataType.STRING);
            Team team = null;
            if (tid != null) {
                team = plugin.getTeamManager().getTeams().stream().filter(t -> t.getId().toString().equals(tid)).findFirst().orElse(null);
            }
            if (slot == e.getInventory().getSize() - 9 || it.getType() == Material.ARROW) {
                // Back to team view
                if (team != null) openTeamView(p, team);
                p.playSound(p.getLocation(), org.bukkit.Sound.UI_BUTTON_CLICK, 0.9f, 1.1f);
                return;
            }
            if (it.getType() == Material.RED_CONCRETE && team != null) {
                // Disband if leader
                if (!team.isLeader(p.getUniqueId())) {
                    p.sendMessage(Text.colorize(plugin.pref() + "&cOnly the team leader can disband the team."));
                    p.playSound(p.getLocation(), org.bukkit.Sound.BLOCK_NOTE_BLOCK_BASS, 0.8f, 0.6f);
                    return;
                }
                // Notify all members (except leader) and remove team
                java.util.Set<java.util.UUID> members = new java.util.HashSet<>(team.getMembers());
                members.remove(p.getUniqueId());
                for (java.util.UUID m : members) {
                    org.bukkit.OfflinePlayer op = Bukkit.getOfflinePlayer(m);
                    if (op.isOnline()) {
                        Player mp = op.getPlayer();
                        if (mp != null) {
                            mp.sendMessage(Text.colorize(plugin.pref() + "&eYour team was disbanded by the leader."));
                            mp.playSound(mp.getLocation(), org.bukkit.Sound.ENTITY_VILLAGER_NO, 0.8f, 0.8f);
                        }
                    }
                }
                plugin.getTeamManager().deleteTeam(team);
                p.sendMessage(Text.colorize(plugin.pref() + "&aTeam disbanded."));
                p.playSound(p.getLocation(), org.bukkit.Sound.ENTITY_GENERIC_EXPLODE, 0.7f, 1.2f);
                openMain(p);
            }
    } else if (inLeaveConfirm) {
            // Confirm leave flow
            ItemStack it = e.getCurrentItem();
            if (it == null) return;
            // Back handling
            if (slot == e.getInventory().getSize() - 9 || it.getType() == Material.ARROW) {
                Team team = plugin.getTeamManager().getTeamByMember(p.getUniqueId()).orElse(null);
                if (team != null) openTeamView(p, team);
                p.playSound(p.getLocation(), org.bukkit.Sound.UI_BUTTON_CLICK, 0.9f, 1.1f);
                return;
            }
            if (it.getType() == Material.RED_CONCRETE) {
                Team team = plugin.getTeamManager().getTeamByMember(p.getUniqueId()).orElse(null);
                if (team == null) { p.closeInventory(); return; }
                if (team.isLeader(p.getUniqueId())) {
                    p.sendMessage(Text.colorize(plugin.pref() + "&cLeaders cannot leave here. Transfer leadership first."));
                    p.playSound(p.getLocation(), org.bukkit.Sound.BLOCK_NOTE_BLOCK_BASS, 0.8f, 0.6f);
                    return;
                }
                if (team.getMembers().size() <= 1) {
                    p.sendMessage(Text.colorize(plugin.pref() + "&cYou can't leave if the team would be empty."));
                    p.playSound(p.getLocation(), org.bukkit.Sound.BLOCK_NOTE_BLOCK_BASS, 0.8f, 0.6f);
                    return;
                }
                team.removeMember(p.getUniqueId());
                plugin.getTeamManager().save();
                p.sendMessage(Text.colorize(plugin.pref() + "&aYou left the team."));
                // Notify remaining members
                for (java.util.UUID m : team.getMembers()) {
                    org.bukkit.OfflinePlayer op = Bukkit.getOfflinePlayer(m);
                    if (op.isOnline() && op.getPlayer() != null) {
                        op.getPlayer().sendMessage(Text.colorize(plugin.pref() + "&e" + p.getName() + " left the team."));
                    }
                }
                p.playSound(p.getLocation(), org.bukkit.Sound.ENTITY_EXPERIENCE_ORB_PICKUP, 0.8f, 1.2f);
                openMain(p);
            }
        } else if (inMemberActions) {
            ItemStack it = e.getCurrentItem(); if (it == null) return;
            // Back
            if (slot == e.getInventory().getSize() - 9 || it.getType() == Material.ARROW) {
                Team team = plugin.getTeamManager().getTeamByMember(p.getUniqueId()).orElse(null);
                if (team != null) openTeamView(p, team);
                p.playSound(p.getLocation(), org.bukkit.Sound.UI_BUTTON_CLICK, 0.9f, 1.1f);
                return;
            }
            ItemMeta im = it.getItemMeta(); if (im == null) return;
            String tid = im.getPersistentDataContainer().get(KEY_TEAM_ID, PersistentDataType.STRING);
            String mid = im.getPersistentDataContainer().get(KEY_TARGET_ID, PersistentDataType.STRING);
            if (tid == null || mid == null) return;
            Team team = plugin.getTeamManager().getTeams().stream().filter(t -> t.getId().toString().equals(tid)).findFirst().orElse(null);
            if (team == null) return;
            UUID memberId; try { memberId = UUID.fromString(mid); } catch (Exception ex) { return; }
            if (!team.isLeader(p.getUniqueId())) {
                p.sendMessage(Text.colorize(plugin.pref() + "&cOnly the team leader can manage members."));
                p.playSound(p.getLocation(), org.bukkit.Sound.BLOCK_NOTE_BLOCK_BASS, 0.8f, 0.6f);
                return;
            }
            if (it.getType() == Material.GOLDEN_HELMET) {
                openTransferConfirm(p, team, memberId);
                p.playSound(p.getLocation(), org.bukkit.Sound.UI_BUTTON_CLICK, 0.9f, 1.1f);
            } else if (it.getType() == Material.BARRIER) {
                openKickConfirm(p, team, memberId);
                p.playSound(p.getLocation(), org.bukkit.Sound.UI_BUTTON_CLICK, 0.9f, 1.1f);
            }
        } else if (inTransferConfirm) {
            ItemStack it = e.getCurrentItem(); if (it == null) return;
            // Back
            if (slot == e.getInventory().getSize() - 9 || it.getType() == Material.ARROW) {
                Team team = plugin.getTeamManager().getTeamByMember(p.getUniqueId()).orElse(null);
                if (team != null) openTeamView(p, team);
                p.playSound(p.getLocation(), org.bukkit.Sound.UI_BUTTON_CLICK, 0.9f, 1.1f);
                return;
            }
            ItemMeta im = it.getItemMeta(); if (im == null) return;
            String tid = im.getPersistentDataContainer().get(KEY_TEAM_ID, PersistentDataType.STRING);
            String mid = im.getPersistentDataContainer().get(KEY_TARGET_ID, PersistentDataType.STRING);
            if (tid == null || mid == null) return;
            Team team = plugin.getTeamManager().getTeams().stream().filter(t -> t.getId().toString().equals(tid)).findFirst().orElse(null);
            if (team == null) return;
            UUID newLeader; try { newLeader = UUID.fromString(mid); } catch (Exception ex) { return; }
            if (!team.isLeader(p.getUniqueId())) {
                p.sendMessage(Text.colorize(plugin.pref() + "&cOnly the team leader can transfer the leadership."));
                p.playSound(p.getLocation(), org.bukkit.Sound.BLOCK_NOTE_BLOCK_BASS, 0.8f, 0.6f);
                return;
            }
            if (it.getType() == Material.RED_CONCRETE) {
                if (!team.isMember(newLeader)) {
                    p.sendMessage(Text.colorize(plugin.pref() + "&cTarget is not a member of the team."));
                    p.playSound(p.getLocation(), org.bukkit.Sound.BLOCK_NOTE_BLOCK_BASS, 0.8f, 0.6f);
                    return;
                }
                if (p.getUniqueId().equals(newLeader)) {
                    p.sendMessage(Text.colorize(plugin.pref() + "&cYou are already the leader."));
                    p.playSound(p.getLocation(), org.bukkit.Sound.BLOCK_NOTE_BLOCK_BASS, 0.8f, 0.6f);
                    return;
                }
                UUID oldLeader = team.getLeader();
                team.setLeader(newLeader);
                plugin.getTeamManager().save();
                org.bukkit.OfflinePlayer np = Bukkit.getOfflinePlayer(newLeader);
                String newName = np.getName() != null ? np.getName() : newLeader.toString().substring(0,8);
                p.sendMessage(Text.colorize(plugin.pref() + "&aYou transferred the leadership to &e" + newName + "&a."));
                p.playSound(p.getLocation(), org.bukkit.Sound.UI_TOAST_CHALLENGE_COMPLETE, 0.8f, 1.2f);
                if (np.isOnline() && np.getPlayer() != null) {
                    np.getPlayer().sendMessage(Text.colorize(plugin.pref() + "&aYou are now the team leader."));
                    np.getPlayer().playSound(np.getPlayer().getLocation(), org.bukkit.Sound.ENTITY_PLAYER_LEVELUP, 0.8f, 1.4f);
                }
                for (java.util.UUID m : team.getMembers()) {
                    if (m.equals(oldLeader) || m.equals(newLeader)) continue;
                    org.bukkit.OfflinePlayer op = Bukkit.getOfflinePlayer(m);
                    if (op.isOnline() && op.getPlayer() != null) {
                        String oldName = Bukkit.getOfflinePlayer(oldLeader).getName();
                        op.getPlayer().sendMessage(Text.colorize(plugin.pref() + "&e" + (oldName != null ? oldName : oldLeader.toString().substring(0,8)) + " transferred the leadership to " + newName + "."));
                    }
                }
                openTeamView(p, team);
            }
        } else if (inKickConfirm) {
            ItemStack it = e.getCurrentItem(); if (it == null) return;
            // Back
            if (slot == e.getInventory().getSize() - 9 || it.getType() == Material.ARROW) {
                Team team = plugin.getTeamManager().getTeamByMember(p.getUniqueId()).orElse(null);
                if (team != null) openTeamView(p, team);
                p.playSound(p.getLocation(), org.bukkit.Sound.UI_BUTTON_CLICK, 0.9f, 1.1f);
                return;
            }
            ItemMeta im = it.getItemMeta(); if (im == null) return;
            String tid = im.getPersistentDataContainer().get(KEY_TEAM_ID, PersistentDataType.STRING);
            String mid = im.getPersistentDataContainer().get(KEY_TARGET_ID, PersistentDataType.STRING);
            if (tid == null || mid == null) return;
            Team team = plugin.getTeamManager().getTeams().stream().filter(t -> t.getId().toString().equals(tid)).findFirst().orElse(null);
            if (team == null) return;
            UUID targetId; try { targetId = UUID.fromString(mid); } catch (Exception ex) { return; }
            if (!team.isLeader(p.getUniqueId())) {
                p.sendMessage(Text.colorize(plugin.pref() + "&cOnly the team leader can kick members."));
                p.playSound(p.getLocation(), org.bukkit.Sound.BLOCK_NOTE_BLOCK_BASS, 0.8f, 0.6f);
                return;
            }
            if (it.getType() == Material.RED_CONCRETE) {
                if (!team.isMember(targetId)) {
                    p.sendMessage(Text.colorize(plugin.pref() + "&cTarget is not a member of the team."));
                    p.playSound(p.getLocation(), org.bukkit.Sound.BLOCK_NOTE_BLOCK_BASS, 0.8f, 0.6f);
                    return;
                }
                if (team.isLeader(targetId)) {
                    p.sendMessage(Text.colorize(plugin.pref() + "&cYou cannot kick the leader."));
                    p.playSound(p.getLocation(), org.bukkit.Sound.BLOCK_NOTE_BLOCK_BASS, 0.8f, 0.6f);
                    return;
                }
                boolean removed = team.removeMember(targetId);
                if (removed) {
                    plugin.getTeamManager().save();
                    org.bukkit.OfflinePlayer op = Bukkit.getOfflinePlayer(targetId);
                    if (op.isOnline() && op.getPlayer() != null) {
                        op.getPlayer().sendMessage(Text.colorize(plugin.pref() + "&cYou have been kicked from " + team.getName() + " by the leader."));
                    }
                    for (java.util.UUID m : team.getMembers()) {
                        if (m.equals(targetId)) continue;
                        org.bukkit.OfflinePlayer mp = Bukkit.getOfflinePlayer(m);
                        if (mp.isOnline() && mp.getPlayer() != null) {
                            mp.getPlayer().sendMessage(Text.colorize(plugin.pref() + "&e" + (op.getName() != null ? op.getName() : targetId.toString().substring(0,8)) + " was kicked from the team."));
                        }
                    }
                    p.sendMessage(Text.colorize(plugin.pref() + "&aYou kicked &e" + (op.getName() != null ? op.getName() : targetId.toString().substring(0,8)) + "&a."));
                    p.playSound(p.getLocation(), org.bukkit.Sound.ENTITY_VILLAGER_NO, 0.8f, 1.2f);
                    openTeamView(p, team);
                }
            }
        }
    }

    private void openKickConfirm(Player p, Team team, UUID memberId) {
        int size = 27;
        Inventory inv = Bukkit.createInventory(null, size, TITLE_KICK_CONFIRM);

        // Confirm button
        ItemStack confirm = new ItemStack(Material.RED_CONCRETE);
        ItemMeta cm = confirm.getItemMeta();
        if (cm != null) {
            cm.displayName(Text.item("&c&lKick now"));
            List<String> lore = new ArrayList<>();
            lore.add("&7This will remove the player from the team.");
            cm.lore(Text.lore(lore));
            cm.getPersistentDataContainer().set(KEY_TEAM_ID, PersistentDataType.STRING, team.getId().toString());
            cm.getPersistentDataContainer().set(KEY_TARGET_ID, PersistentDataType.STRING, memberId.toString());
            confirm.setItemMeta(cm);
        }
        inv.setItem(13, confirm);

        // Back
        ItemStack back = button(Material.ARROW, Text.item("&7« Back"));
        inv.setItem(size - 9, back);

        // Background
        fillEmptyWith(inv, pane(paneForColor(team.getColor())));

        p.openInventory(inv);
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onClose(InventoryCloseEvent e) {
        // No-op
    }

    private void openTeamView(Player p, Team team) {
    int size = 36; // more space to arrange buttons clearly
    Inventory inv = Bukkit.createInventory(null, size, Text.title("Team • " + team.getName()));

        // Header banner with team info
        ItemStack header = new ItemStack(bannerForColor(team.getColor()));
        BannerMeta bm = (BannerMeta) header.getItemMeta();
        if (bm != null) {
            // Team name in bold with team color
            bm.displayName(Text.item("&l" + team.getName()));
            List<String> lore = new ArrayList<>();
            String leaderName = Bukkit.getOfflinePlayer(team.getLeader()).getName();
            int leaderNum = team.getRacerNumber(team.getLeader());
            String tag = leaderNum > 0 ? ("[#" + leaderNum + "]") : "[#?]";
            lore.add(Text.colorize("&7Leader: &f" + (leaderName != null ? leaderName : team.getLeader().toString().substring(0, 8)) + " &7" + tag));
            lore.add(Text.colorize("&7Members: &f" + team.getMembers().size() + "/" + plugin.getTeamManager().getMaxMembers()));
            // Members list similar to main menu
            lore.add(" ");
            lore.add("&8Members:");
            for (UUID uid : team.getMembers()) {
                String name = Bukkit.getOfflinePlayer(uid).getName();
                int num = team.getRacerNumber(uid);
                String numTag = num > 0 ? ("[#" + num + "]") : "[#?]";
                lore.add("&7- &f" + (name != null ? name : uid.toString().substring(0, 8)) + " &7" + numTag);
            }
            bm.lore(Text.lore(lore));
            PersistentDataContainer pdc = bm.getPersistentDataContainer();
            pdc.set(KEY_TEAM_ID, PersistentDataType.STRING, team.getId().toString());
            header.setItemMeta(bm);
        }
    inv.setItem(4, header);

    // Member heads with number in lore
    int[] memberSlots = {10, 12, 14, 16}; // fila superior central
        int idx = 0;
        for (UUID m : team.getMembers()) {
            if (idx >= memberSlots.length) break;
            ItemStack skull = new ItemStack(Material.PLAYER_HEAD);
            SkullMeta sm = (SkullMeta) skull.getItemMeta();
            if (sm != null) {
                String name = Bukkit.getOfflinePlayer(m).getName();
                sm.setOwningPlayer(Bukkit.getOfflinePlayer(m));
                sm.displayName(Text.item((name != null ? name : m.toString())));
                List<String> lore = new ArrayList<>();
                lore.add(Text.colorize("&7Racer #: &f" + (team.getRacerNumber(m) == 0 ? "(unset)" : team.getRacerNumber(m))));
                lore.add(Text.colorize("&7Boat: &f" + team.getBoatType(m)));
                if (m.equals(p.getUniqueId())) lore.add(Text.colorize("&eClick: &fEdit your profile"));
                sm.lore(Text.lore(lore));
                // Link back to team id for handling
                sm.getPersistentDataContainer().set(KEY_TEAM_ID, PersistentDataType.STRING, team.getId().toString());
                skull.setItemMeta(sm);
            }
            inv.setItem(memberSlots[idx++], skull);
        }

    // Action buttons (bottom row): Back | (conditional) Rename | (conditional) Change Color
            int base = size - 9;
            ItemStack back = button(Material.ARROW, Text.item("&7« Back"));
            ItemMeta backMeta = back.getItemMeta();
            if (backMeta != null) {
                backMeta.getPersistentDataContainer().set(KEY_TEAM_ID, PersistentDataType.STRING, team.getId().toString());
                back.setItemMeta(backMeta);
            }
            inv.setItem(base, back);

    boolean isMember = team.isMember(p.getUniqueId());
    boolean isLeader = team.isLeader(p.getUniqueId());
    if (isLeader) {
            ItemStack rename = new ItemStack(Material.PAPER);
            ItemMeta rim = rename.getItemMeta();
            if (rim != null) {
                rim.displayName(Text.item("&e&lRename team"));
                List<String> rl = new ArrayList<>();
                rl.add(Text.colorize("&8Leader only"));
                rim.lore(Text.lore(rl));
                rim.getPersistentDataContainer().set(KEY_TEAM_ID, PersistentDataType.STRING, team.getId().toString());
                rename.setItemMeta(rim);
            }
            inv.setItem((size - 9) + 2, rename); // base+2

            ItemStack color = new ItemStack(dyeFor(team.getColor()));
            ItemMeta cim = color.getItemMeta();
            if (cim != null) {
                cim.displayName(Text.item("&b&lChange color"));
                List<String> cl = new ArrayList<>();
                cl.add(Text.colorize("&8Leader only"));
                cim.lore(Text.lore(cl));
                cim.getPersistentDataContainer().set(KEY_TEAM_ID, PersistentDataType.STRING, team.getId().toString());
                color.setItemMeta(cim);
            }
            inv.setItem((size - 9) + 4, color); // base+4

            // Disband button (leader only)
            ItemStack disband = new ItemStack(Material.TNT);
            ItemMeta dim = disband.getItemMeta();
            if (dim != null) {
                dim.displayName(Text.item("&c&lDisband team"));
                List<String> dl = new ArrayList<>();
                dl.add(Text.colorize("&cThis cannot be undone"));
                dim.lore(Text.lore(dl));
                dim.getPersistentDataContainer().set(KEY_TEAM_ID, PersistentDataType.STRING, team.getId().toString());
                disband.setItemMeta(dim);
            }
            inv.setItem((size - 9) + 8, disband); // base+8
        }

        // Join button (if not member and not full)
    if (!isMember && team.getMembers().size() < plugin.getTeamManager().getMaxMembers()) {
            ItemStack join = new ItemStack(Material.GREEN_CONCRETE);
            ItemMeta jim = join.getItemMeta();
            if (jim != null) {
                jim.displayName(Text.item("&a&lJoin team"));
                jim.getPersistentDataContainer().set(KEY_TEAM_ID, PersistentDataType.STRING, team.getId().toString());
                join.setItemMeta(jim);
            }
            inv.setItem((size - 9) + 6, join); // base+6
        }

    // Leave button (if member and not leader)
    if (isMember && !isLeader) {
            ItemStack leave = new ItemStack(Material.RED_CONCRETE);
            ItemMeta lim = leave.getItemMeta();
            if (lim != null) {
                lim.displayName(Text.item("&c&lLeave team"));
                lim.getPersistentDataContainer().set(KEY_TEAM_ID, PersistentDataType.STRING, team.getId().toString());
                leave.setItemMeta(lim);
            }
            inv.setItem((size - 9) + 6, leave); // base+6
        }

    // Fill remaining with team-colored panes for a friendly accent
    fillEmptyWith(inv, pane(paneForColor(team.getColor())));
    p.openInventory(inv);
    }

    private void openMemberProfile(Player p, Team team) {
        int size = 27;
    Inventory inv = Bukkit.createInventory(null, size, TITLE_MEMBER);

        // Summary
        ItemStack profile = new ItemStack(Material.BOOK);
        ItemMeta pim = profile.getItemMeta();
        if (pim != null) {
            String teamName = team.getName();
            String playerName = p.getName();
            pim.displayName(Text.item("&6&lYour profile"));
            List<String> lore = new ArrayList<>();
            lore.add(Text.colorize("&7Player: &f" + playerName));
            lore.add(Text.colorize("&7Team: &f" + teamName));
            lore.add(Text.colorize("&7Racer #: &f" + (team.getRacerNumber(p.getUniqueId()) == 0 ? "(unset)" : team.getRacerNumber(p.getUniqueId()))));
            lore.add(Text.colorize("&7Boat: &f" + team.getBoatType(p.getUniqueId())));
            pim.lore(Text.lore(lore));
            profile.setItemMeta(pim);
        }
        ItemMeta pim2 = profile.getItemMeta();
        if (pim2 != null) {
            pim2.getPersistentDataContainer().set(KEY_TEAM_ID, PersistentDataType.STRING, team.getId().toString());
            profile.setItemMeta(pim2);
        }
        inv.setItem(4, profile);

        // Set racer number
        ItemStack racer = new ItemStack(Material.NAME_TAG);
        ItemMeta rim = racer.getItemMeta();
        if (rim != null) {
            rim.displayName(Text.item("&eSet your racer #"));
            rim.getPersistentDataContainer().set(KEY_TEAM_ID, PersistentDataType.STRING, team.getId().toString());
            racer.setItemMeta(rim);
        }
        ItemMeta rim2 = racer.getItemMeta();
        if (rim2 != null) {
            rim2.getPersistentDataContainer().set(KEY_TEAM_ID, PersistentDataType.STRING, team.getId().toString());
            racer.setItemMeta(rim2);
        }
        inv.setItem(11, racer);

        // Change boat type (opens picker)
        Material current = materialOr(Material.matchMaterial(team.getBoatType(p.getUniqueId())), Material.OAK_BOAT);
        ItemStack boat = new ItemStack(current);
        ItemMeta bim = boat.getItemMeta();
        if (bim != null) {
            bim.displayName(Text.item("&bChange boat type &7(Click)"));
            bim.getPersistentDataContainer().set(KEY_TEAM_ID, PersistentDataType.STRING, team.getId().toString());
            boat.setItemMeta(bim);
        }
        ItemMeta bim2 = boat.getItemMeta();
        if (bim2 != null) {
            bim2.getPersistentDataContainer().set(KEY_TEAM_ID, PersistentDataType.STRING, team.getId().toString());
            boat.setItemMeta(bim2);
        }
        inv.setItem(15, boat);

        // Back
    ItemStack backBtn = button(Material.ARROW, Text.item("&7« Back"));
        ItemMeta backMeta = backBtn.getItemMeta();
        if (backMeta != null) {
            backMeta.getPersistentDataContainer().set(KEY_TEAM_ID, PersistentDataType.STRING, team.getId().toString());
            backBtn.setItemMeta(backMeta);
        }
        inv.setItem(size - 9, backBtn);

    // Team-colored background accent
    fillEmptyWith(inv, pane(paneForColor(team.getColor())));
    p.openInventory(inv);
    }

    private void openColorPicker(Player p, Team team) {
        // Use banners for each DyeColor
        DyeColor[] colors = DyeColor.values();
        int rows = ((colors.length - 1) / 9) + 2; // at least one row plus back row
        int size = Math.min(54, Math.max(18, rows * 9));
    Inventory inv = Bukkit.createInventory(null, size, TITLE_COLOR_PICKER);

    int slot = 0;
        for (DyeColor dc : colors) {
            if (slot >= size - 9) break; // leave last row for back
            ItemStack banner = new ItemStack(bannerForColor(dc));
            ItemMeta im = banner.getItemMeta();
            if (im != null) {
                im.displayName(Text.item("&f" + dc.name()));
        List<String> lore = new ArrayList<>();
                lore.add(Text.colorize("&eClick: &fSet this color"));
                im.lore(Text.lore(lore));
                im.getPersistentDataContainer().set(KEY_TEAM_ID, PersistentDataType.STRING, team.getId().toString());
                banner.setItemMeta(im);
            }
            inv.setItem(slot++, banner);
        }
    // Footer decoration neutral
    fillRow(inv, size - 9, pane(Material.LIGHT_GRAY_STAINED_GLASS_PANE));
    ItemStack back = button(Material.ARROW, Text.item("&7« Back"));
        ItemMeta bm = back.getItemMeta();
        if (bm != null) {
            bm.getPersistentDataContainer().set(KEY_TEAM_ID, PersistentDataType.STRING, team.getId().toString());
            back.setItemMeta(bm);
        }
        inv.setItem(size - 9, back);
        p.openInventory(inv);
    }

    private void openBoatPicker(Player p, Team team) {
        int rows = ((ALLOWED_BOATS.length - 1) / 9) + 2;
        int size = Math.min(54, Math.max(18, rows * 9));
    Inventory inv = Bukkit.createInventory(null, size, TITLE_BOAT_PICKER);

    int slot = 0;
        for (Material m : ALLOWED_BOATS) {
            if (slot >= size - 9) break;
            ItemStack it = new ItemStack(m);
            ItemMeta im = it.getItemMeta();
            if (im != null) {
                im.displayName(Text.item("&f" + niceMaterialName(m)));
        List<String> lore = new ArrayList<>();
                lore.add(Text.colorize("&eClick: &fSelect"));
                im.lore(Text.lore(lore));
                im.getPersistentDataContainer().set(KEY_TEAM_ID, PersistentDataType.STRING, team.getId().toString());
                it.setItemMeta(im);
            }
            inv.setItem(slot++, it);
        }
    // Footer decoration neutral
    fillRow(inv, size - 9, pane(Material.LIGHT_GRAY_STAINED_GLASS_PANE));
    ItemStack back = button(Material.ARROW, Text.item("&7« Back"));
        ItemMeta bm = back.getItemMeta();
        if (bm != null) {
            bm.getPersistentDataContainer().set(KEY_TEAM_ID, PersistentDataType.STRING, team.getId().toString());
            back.setItemMeta(bm);
        }
        inv.setItem(size - 9, back);
        p.openInventory(inv);
    }

    private void openDisbandConfirm(Player p, Team team) {
        int size = 27;
        Inventory inv = Bukkit.createInventory(null, size, TITLE_DISBAND_CONFIRM);

        // Warning item
        ItemStack warn = new ItemStack(Material.BOOK);
        ItemMeta wim = warn.getItemMeta();
        if (wim != null) {
            wim.displayName(Text.item("&c&lConfirm disband"));
            java.util.List<String> lore = new java.util.ArrayList<>();
            lore.add(Text.colorize("&7This will delete the team."));
            lore.add(Text.colorize("&7Other member(s) will be kicked."));
            wim.lore(Text.lore(lore));
            wim.getPersistentDataContainer().set(KEY_TEAM_ID, PersistentDataType.STRING, team.getId().toString());
            warn.setItemMeta(wim);
        }
        inv.setItem(4, warn);

        // Confirm button
        ItemStack yes = new ItemStack(Material.RED_CONCRETE);
        ItemMeta yim = yes.getItemMeta();
        if (yim != null) {
            yim.displayName(Text.item("&c&lDisband now"));
            yim.getPersistentDataContainer().set(KEY_TEAM_ID, PersistentDataType.STRING, team.getId().toString());
            yes.setItemMeta(yim);
        }
        inv.setItem(13, yes);

        // Back
        ItemStack back = button(Material.ARROW, Text.item("&7« Back"));
        ItemMeta bm = back.getItemMeta();
        if (bm != null) {
            bm.getPersistentDataContainer().set(KEY_TEAM_ID, PersistentDataType.STRING, team.getId().toString());
            back.setItemMeta(bm);
        }
        inv.setItem(size - 9, back);
        // Team-colored background accent
        fillEmptyWith(inv, pane(paneForColor(team.getColor())));
        p.openInventory(inv);
    }

    private void openLeaveConfirm(Player p, Team team) {
        int size = 27;
        Inventory inv = Bukkit.createInventory(null, size, TITLE_LEAVE_CONFIRM);

        // Warning item
        ItemStack warn = new ItemStack(Material.BOOK);
        ItemMeta wim = warn.getItemMeta();
        if (wim != null) {
            wim.displayName(Text.item("&c&lConfirm leave"));
            java.util.List<String> lore = new java.util.ArrayList<>();
            lore.add(Text.colorize("&7You will leave your team."));
            lore.add(Text.colorize("&7You can join again if there is a slot."));
            wim.lore(Text.lore(lore));
            warn.setItemMeta(wim);
        }
        inv.setItem(4, warn);

        // Confirm button
        ItemStack yes = new ItemStack(Material.RED_CONCRETE);
        ItemMeta yim = yes.getItemMeta();
        if (yim != null) {
            yim.displayName(Text.item("&c&lLeave now"));
            yes.setItemMeta(yim);
        }
        inv.setItem(13, yes);

        // Back
        ItemStack back = button(Material.ARROW, Text.item("&7« Back"));
        inv.setItem(size - 9, back);
        fillEmptyWith(inv, pane(paneForColor(team.getColor())));
        p.openInventory(inv);
    }

    // openDisbandConfirm removed: disband happens immediately from the team view

    // Text input via AnvilGUI
    private void openAnvilForName(Player p, Team team) {
        openAnvil(p, "name", team.getId(), "", "Team name");
    }
    private void openAnvilForRacer(Player p, Team team) {
        openAnvil(p, "racer", team.getId(), "", "Racer number");
    }
    private void openAnvilForCreate(Player p) {
        openAnvil(p, "create", null, "", "Team name");
    }
    private void openAnvil(Player p, String action, UUID teamId, String initialText, String title) {
        ItemStack left = new ItemStack(Material.PAPER); // blank display name to avoid showing "Paper"
        ItemMeta leftMeta = left.getItemMeta();
        if (leftMeta != null) {
            leftMeta.displayName(Component.empty());
            left.setItemMeta(leftMeta);
        }
        new AnvilGUI.Builder()
            .title(title)
            .text((initialText == null || initialText.isEmpty()) ? BLANK : initialText)
            .itemLeft(left)
            .interactableSlots() // deny item interactions
            .onClick((slot, state) -> {
                if (slot != AnvilGUI.Slot.OUTPUT) return java.util.Collections.emptyList();
                String input = state.getText() == null ? "" : state.getText().trim();
                return handleAnvilInput(state.getPlayer(), action, teamId, input);
            })
            .plugin(plugin)
            .open(p);
    }
    private java.util.List<AnvilGUI.ResponseAction> handleAnvilInput(Player p, String action, UUID teamId, String input) {
        if ("name".equals(action)) {
            Team team = getTeam(teamId);
            if (team == null) { p.sendMessage(Text.colorize(plugin.pref() + "&cTeam not found.")); return java.util.Collections.emptyList(); }
            if (!team.isLeader(p.getUniqueId())) { p.sendMessage(Text.colorize(plugin.pref() + "&cOnly the team leader can rename.")); return java.util.Collections.emptyList(); }
            String err = validateNameMessage(input);
            if (err != null) {
                p.sendMessage(Text.colorize(plugin.pref() + "&c" + err));
                String retry = input.isEmpty() ? BLANK : input;
                p.playSound(p.getLocation(), org.bukkit.Sound.BLOCK_NOTE_BLOCK_BASS, 0.8f, 0.6f);
                return java.util.Arrays.asList(AnvilGUI.ResponseAction.replaceInputText(retry));
            }
            boolean exists = plugin.getTeamManager().getTeams().stream().anyMatch(t -> t != team && t.getName().equalsIgnoreCase(input));
            if (exists) { 
                p.sendMessage(Text.colorize(plugin.pref() + "&cA team with that name already exists.")); 
                String retry = input.isEmpty() ? BLANK : input;
                p.playSound(p.getLocation(), org.bukkit.Sound.BLOCK_NOTE_BLOCK_BASS, 0.8f, 0.6f);
                return java.util.Arrays.asList(AnvilGUI.ResponseAction.replaceInputText(retry));
            }
            team.setName(sanitizeName(input)); plugin.getTeamManager().save();
            p.sendMessage(Text.colorize(plugin.pref() + "&aTeam renamed to &e" + input + "&a."));
            p.playSound(p.getLocation(), org.bukkit.Sound.ENTITY_PLAYER_LEVELUP, 0.8f, 1.4f);
            return java.util.Arrays.asList(
                AnvilGUI.ResponseAction.close(),
                AnvilGUI.ResponseAction.run(() -> openTeamView(p, team))
            );
        } else if ("racer".equals(action)) {
            Team team = getTeam(teamId);
            if (team == null) { p.sendMessage(Text.colorize(plugin.pref() + "&cTeam not found.")); return java.util.Collections.emptyList(); }
            String err = validateNumberFormatRange(input);
            if (err != null) { 
                p.sendMessage(Text.colorize(plugin.pref() + "&c" + err));
                String retry = input.isEmpty() ? BLANK : input;
                p.playSound(p.getLocation(), org.bukkit.Sound.BLOCK_NOTE_BLOCK_BASS, 0.8f, 0.6f);
                return java.util.Arrays.asList(AnvilGUI.ResponseAction.replaceInputText(retry));
            }
            int num = Integer.parseInt(input);
            team.setRacerNumber(p.getUniqueId(), num); plugin.getTeamManager().save();
            p.sendMessage(Text.colorize(plugin.pref() + "&aYour racer # set to " + num + "."));
            p.playSound(p.getLocation(), org.bukkit.Sound.ENTITY_EXPERIENCE_ORB_PICKUP, 0.9f, 1.5f);
            return java.util.Arrays.asList(
                AnvilGUI.ResponseAction.close(),
                AnvilGUI.ResponseAction.run(() -> openMemberProfile(p, team))
            );
        } else if ("create".equals(action)) {
            if (plugin.getTeamManager().getTeamByMember(p.getUniqueId()).isPresent()) { 
                p.sendMessage(Text.colorize(plugin.pref() + "&cYou are already in a team. Leave it first.")); 
                return null; 
            }
            String err = validateNameMessage(input);
            if (err != null) { 
                p.sendMessage(Text.colorize(plugin.pref() + "&c" + err));
                String retry = input.isEmpty() ? BLANK : input;
                p.playSound(p.getLocation(), org.bukkit.Sound.BLOCK_NOTE_BLOCK_BASS, 0.8f, 0.6f);
                return java.util.Arrays.asList(AnvilGUI.ResponseAction.replaceInputText(retry));
            }
            boolean exists = plugin.getTeamManager().getTeams().stream().anyMatch(t -> t.getName().equalsIgnoreCase(input));
            if (exists) { 
                p.sendMessage(Text.colorize(plugin.pref() + "&cA team with that name already exists.")); 
                p.playSound(p.getLocation(), org.bukkit.Sound.BLOCK_NOTE_BLOCK_BASS, 0.8f, 0.6f);
                return java.util.Arrays.asList(AnvilGUI.ResponseAction.replaceInputText(input));
            }
            Team team = plugin.getTeamManager().createTeam(p, sanitizeName(input), DyeColor.WHITE);
            p.playSound(p.getLocation(), org.bukkit.Sound.UI_TOAST_CHALLENGE_COMPLETE, 0.7f, 1.2f);
            return java.util.Arrays.asList(
                AnvilGUI.ResponseAction.close(),
                AnvilGUI.ResponseAction.run(() -> openTeamView(p, team))
            );
        }
        return java.util.Collections.emptyList();
    }
    // Sign input removed; using AnvilGUI now
    private Team getTeam(UUID id) {
        if (id == null) return null;
        return plugin.getTeamManager().getTeams().stream().filter(t -> t.getId().equals(id)).findFirst().orElse(null);
    }

    private static boolean isBoatItem(Material m) {
        if (m == null) return false;
        for (Material x : ALLOWED_BOATS) if (x == m) return true;
        return false;
    }
    private static Material materialOr(Material a, Material fallback) { return a == null ? fallback : a; }
    private static String niceMaterialName(Material m) {
        String s = m.name().toLowerCase(java.util.Locale.ROOT).replace('_', ' ');
        return Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }
    private static DyeColor dyeFromBanner(Material m) {
        if (m == null || !m.name().endsWith("_BANNER")) return null;
        String prefix = m.name().substring(0, m.name().length() - "_BANNER".length());
        try { return DyeColor.valueOf(prefix); } catch (IllegalArgumentException ex) { return null; }
    }

    private static boolean isDye(Material m) {
        return m != null && m.name().endsWith("_DYE");
    }

    private static Material dyeFor(DyeColor color) {
        if (color == null) return Material.WHITE_DYE;
        try {
            return Material.valueOf(color.name() + "_DYE");
        } catch (IllegalArgumentException ex) {
            return Material.WHITE_DYE;
        }
    }

    // Removed anvil event handlers; sign UI replaces them

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onGuiDrag(InventoryDragEvent e) {
        if (e.getView().getTopInventory() == null) return;
        Component title = e.getView().title();
        String plain = Text.plain(title);
        boolean block = plain.equals(Text.plain(TITLE))
            || plain.startsWith(TEAM_TITLE_PREFIX) || plain.equals(Text.plain(TITLE_TEAM))
            || plain.equals(Text.plain(TITLE_MEMBER))
            || plain.equals(Text.plain(TITLE_COLOR_PICKER))
            || plain.equals(Text.plain(TITLE_BOAT_PICKER))
            || plain.equals(Text.plain(TITLE_DISBAND_CONFIRM))
            || plain.equals(Text.plain(TITLE_LEAVE_CONFIRM))
            || plain.equals(Text.plain(TITLE_MEMBER_ACTIONS))
            || plain.equals(Text.plain(TITLE_TRANSFER_CONFIRM));
        if (block) {
            e.setCancelled(true);
        }
    }

    private static String sanitizeName(String raw) {
        String s = raw.replace("§", "").replace("&", "").trim();
        if (s.length() > 20) s = s.substring(0, 20);
        return s;
    }

    // Member actions menu (leader managing another member)
    private void openMemberActions(Player p, Team team, UUID memberId) {
        int size = 27;
        Inventory inv = Bukkit.createInventory(null, size, TITLE_MEMBER_ACTIONS);

        // Transfer leadership button
        ItemStack transfer = new ItemStack(Material.GOLDEN_HELMET);
        ItemMeta tm = transfer.getItemMeta();
        if (tm != null) {
            tm.displayName(Text.item("&e&lTransfer leadership"));
            List<String> lore = new ArrayList<>();
            lore.add("&7Give the leader role to this player.");
            tm.lore(Text.lore(lore));
            tm.getPersistentDataContainer().set(KEY_TEAM_ID, PersistentDataType.STRING, team.getId().toString());
            tm.getPersistentDataContainer().set(KEY_TARGET_ID, PersistentDataType.STRING, memberId.toString());
            transfer.setItemMeta(tm);
        }
        inv.setItem(12, transfer);

        // Kick member button
        ItemStack kick = new ItemStack(Material.BARRIER);
        ItemMeta km = kick.getItemMeta();
        if (km != null) {
            km.displayName(Text.item("&c&lKick member"));
            List<String> lore = new ArrayList<>();
            lore.add("&7Remove this player from the team.");
            km.lore(Text.lore(lore));
            km.getPersistentDataContainer().set(KEY_TEAM_ID, PersistentDataType.STRING, team.getId().toString());
            km.getPersistentDataContainer().set(KEY_TARGET_ID, PersistentDataType.STRING, memberId.toString());
            kick.setItemMeta(km);
        }
        inv.setItem(14, kick);

        // Back
        ItemStack back = button(Material.ARROW, Text.item("&7« Back"));
        inv.setItem(size - 9, back);

        // Fill background with team color accent
        fillEmptyWith(inv, pane(paneForColor(team.getColor())));

        p.openInventory(inv);
    }

    // Transfer confirmation menu
    private void openTransferConfirm(Player p, Team team, UUID memberId) {
        int size = 27;
        Inventory inv = Bukkit.createInventory(null, size, TITLE_TRANSFER_CONFIRM);

        // Confirm button
        ItemStack confirm = new ItemStack(Material.RED_CONCRETE);
        ItemMeta cm = confirm.getItemMeta();
        if (cm != null) {
            cm.displayName(Text.item("&c&lTransfer now"));
            List<String> lore = new ArrayList<>();
            lore.add("&7This will make that player the team leader.");
            cm.lore(Text.lore(lore));
            cm.getPersistentDataContainer().set(KEY_TEAM_ID, PersistentDataType.STRING, team.getId().toString());
            cm.getPersistentDataContainer().set(KEY_TARGET_ID, PersistentDataType.STRING, memberId.toString());
            confirm.setItemMeta(cm);
        }
        inv.setItem(13, confirm);

        // Back
        ItemStack back = button(Material.ARROW, Text.item("&7« Back"));
        inv.setItem(size - 9, back);

        // Background
        fillEmptyWith(inv, pane(paneForColor(team.getColor())));

        p.openInventory(inv);
    }

    public static String validateNameMessage(String raw) {
        String s = raw == null ? "" : raw.trim();
        if (s.isEmpty()) return "Name cannot be empty.";
        if (s.length() > 20) return "Name is too long (max 20).";
        // Allow letters, numbers, spaces, - _
        if (!s.matches("[A-Za-z0-9 _-]+")) return "Only letters, numbers, spaces, '-' and '_' allowed.";
        return null;
    }

    private String validateNumberFormatRange(String raw) {
        String s = raw == null ? "" : raw.trim();
        if (!s.matches("\\d+")) return "Please enter digits only.";
        int n = Integer.parseInt(s);
        if (n < 1 || n > 99) return "Number must be between 1 and 99.";
        return null;
    }
}
