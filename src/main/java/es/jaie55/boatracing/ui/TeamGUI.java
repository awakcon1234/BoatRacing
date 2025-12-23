/* Team GUI removed
    private static final Component TITLE_COLOR_PICKER = Text.title("Chọn màu đội");
    private static final Component TITLE_BOAT_PICKER = Text.title("Chọn thuyền của bạn");
    private static final Component TITLE_DISBAND_CONFIRM = Text.title("Giải tán đội?");
    private static final Component TITLE_LEAVE_CONFIRM = Text.title("Rời đội?");
    // Resolve allowed boat/raft materials by name to avoid NoSuchFieldError on servers lacking newer enums
    private static final Material[] ALLOWED_BOATS = resolveAllowedBoats();
    
    private static Material[] resolveAllowedBoats() {
        java.util.List<Material> list = new java.util.ArrayList<>();
        addIfPresent(list, "OAK_BOAT");
        addIfPresent(list, "SPRUCE_BOAT");
        addIfPresent(list, "BIRCH_BOAT");
        addIfPresent(list, "JUNGLE_BOAT");
        addIfPresent(list, "ACACIA_BOAT");
        addIfPresent(list, "DARK_OAK_BOAT");
        addIfPresent(list, "MANGROVE_BOAT");
        addIfPresent(list, "CHERRY_BOAT");
        addIfPresent(list, "PALE_OAK_BOAT");
        addIfPresent(list, "BAMBOO_RAFT");
        addIfPresent(list, "OAK_CHEST_BOAT");
        addIfPresent(list, "SPRUCE_CHEST_BOAT");
        addIfPresent(list, "BIRCH_CHEST_BOAT");
        addIfPresent(list, "JUNGLE_CHEST_BOAT");
        addIfPresent(list, "ACACIA_CHEST_BOAT");
        addIfPresent(list, "DARK_OAK_CHEST_BOAT");
        addIfPresent(list, "MANGROVE_CHEST_BOAT");
        addIfPresent(list, "CHERRY_CHEST_BOAT");
        addIfPresent(list, "PALE_OAK_CHEST_BOAT");
        addIfPresent(list, "BAMBOO_CHEST_RAFT");
        return list.toArray(new Material[0]);
    }
    
    private static void addIfPresent(java.util.List<Material> out, String name) {
        // Resolve strictly by modern enum name; avoids triggering legacy material support
        Material m = org.bukkit.Material.matchMaterial(name);
        if (m != null) out.add(m);
    }
    private final BoatRacingPlugin plugin;
    private final NamespacedKey KEY_TEAM_ID;
    private final NamespacedKey KEY_TARGET_ID;
    

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
                lore.add("&7Thành viên: &f" + t.getMembers().size() + "/" + plugin.getTeamManager().getMaxMembers());
                // Members list (dropdown-like on hover)
                if (!t.getMembers().isEmpty()) {
                    lore.add(" ");
                    lore.add("&8Thành viên:");
                    for (UUID uid : t.getMembers()) {
                        String name = Bukkit.getOfflinePlayer(uid).getName();
                        lore.add("&7- &f" + (name != null ? name : uid.toString().substring(0, 8)));
                    }
                }
                lore.add(" ");
                lore.add("&eBấm: &fMở");
                meta.lore(Text.lore(lore));
                PersistentDataContainer pdc = meta.getPersistentDataContainer();
                pdc.set(KEY_TEAM_ID, PersistentDataType.STRING, t.getId().toString());
                item.setItemMeta(meta);
            }
            inv.setItem(slot++, item);
            if (slot >= size - 9) break;
        }
        
        int base = size - 9;
        fillRow(inv, base, pane(Material.GRAY_STAINED_GLASS_PANE));
        boolean allowCreate = plugin.getConfig().getBoolean("player-actions.allow-team-create", true);
        if (allowCreate) inv.setItem(base + 4, button(Material.ANVIL, Text.item("&a&lTạo đội")));
        // Admin quick access
        if (p.hasPermission("boatracing.admin")) {
            java.util.List<String> lore = java.util.Arrays.asList("&7Mở bảng quản trị.");
            inv.setItem(base, buttonWithLore(Material.NETHER_STAR, Text.item("&d&lBảng quản trị"), lore));
        }
        // Refresh list
        inv.setItem(base + 7, buttonWithLore(Material.CLOCK, Text.item("&e&lLàm mới"), java.util.Arrays.asList("&7Tải lại trang và cập nhật danh sách.")));
        inv.setItem(base + 8, button(Material.BARRIER, Text.item("&c&lĐóng")));

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

    private static ItemStack buttonWithLore(Material mat, Component name, java.util.List<String> loreLines) {
        ItemStack it = new ItemStack(mat);
        ItemMeta im = it.getItemMeta();
        if (im != null) {
            im.displayName(name);
            if (loreLines != null && !loreLines.isEmpty()) {
                im.lore(Text.lore(loreLines));
            }
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
    boolean inMemberActions = false;
    boolean inTransferConfirm = false;
    boolean inKickConfirm = false;
    if (!inMain && !inTeam && !inMember && !inColorPicker && !inBoatPicker && !inDisbandConfirm && !inLeaveConfirm) return;
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
        // Admin panel shortcut
        if (inMain && slot == base && p.hasPermission("boatracing.admin")) {
            es.jaie55.boatracing.BoatRacingPlugin.getInstance().getAdminGUI().openMain(p);
            p.playSound(p.getLocation(), org.bukkit.Sound.UI_BUTTON_CLICK, 0.9f, 1.2f);
            return;
        }
        // Refresh list
        if (inMain && slot == base + 7) {
            openMain(p);
            p.playSound(p.getLocation(), org.bukkit.Sound.UI_BUTTON_CLICK, 0.9f, 1.2f);
            return;
        }
    if (inMain && slot == base + 4) {
            boolean allowCreate2 = plugin.getConfig().getBoolean("player-actions.allow-team-create", true);
            if (!allowCreate2) {
                es.jaie55.boatracing.util.Text.msg(p, "&cMáy chủ này đã hạn chế việc tạo đội. Chỉ quản trị viên mới có thể tạo đội.");
                p.playSound(p.getLocation(), org.bukkit.Sound.BLOCK_NOTE_BLOCK_BASS, 0.8f, 0.6f);
                return;
            }
            TeamManager tm = plugin.getTeamManager();
            if (tm.getTeamByMember(p.getUniqueId()).isPresent()) {
                es.jaie55.boatracing.util.Text.msg(p, "&cBạn đã ở trong một đội.");
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
                // Rename team (allowed for admins, or members when enabled in config)
                boolean allowRename = plugin.getConfig().getBoolean("player-actions.allow-team-rename", false);
                boolean isAdmin = p.hasPermission("boatracing.admin");
                if (!allowRename && !isAdmin) {
                    es.jaie55.boatracing.util.Text.msg(p, "&cMáy chủ này đã hạn chế việc đổi tên đội. Chỉ quản trị viên mới có thể đổi tên đội.");
                    p.playSound(p.getLocation(), org.bukkit.Sound.BLOCK_NOTE_BLOCK_BASS, 0.8f, 0.6f);
                    return;
                }
                if (!isAdmin && !team.isMember(p.getUniqueId())) {
                    es.jaie55.boatracing.util.Text.msg(p, "&cChỉ thành viên của đội mới có thể đổi tên đội của mình.");
                    p.playSound(p.getLocation(), org.bukkit.Sound.BLOCK_NOTE_BLOCK_BASS, 0.8f, 0.6f);
                    return;
                }
                openAnvilForName(p, team);
                p.playSound(p.getLocation(), org.bukkit.Sound.UI_BUTTON_CLICK, 0.9f, 1.25f);
            } else if (isDye(it.getType())) {
                // Open color picker (allowed for admins, or members when enabled in config)
                boolean allowColor = plugin.getConfig().getBoolean("player-actions.allow-team-color", false);
                boolean isAdmin = p.hasPermission("boatracing.admin");
                if (!allowColor && !isAdmin) {
                    es.jaie55.boatracing.util.Text.msg(p, "&cMáy chủ này đã hạn chế việc đổi màu đội. Chỉ quản trị viên mới có thể đổi màu đội.");
                    p.playSound(p.getLocation(), org.bukkit.Sound.BLOCK_NOTE_BLOCK_BASS, 0.8f, 0.6f);
                    return;
                }
                if (!isAdmin && !team.isMember(p.getUniqueId())) {
                    es.jaie55.boatracing.util.Text.msg(p, "&cChỉ thành viên của đội mới có thể đổi màu đội.");
                    p.playSound(p.getLocation(), org.bukkit.Sound.BLOCK_NOTE_BLOCK_BASS, 0.8f, 0.6f);
                    return;
                }
                openColorPicker(p, team);
                p.playSound(p.getLocation(), org.bukkit.Sound.UI_BUTTON_CLICK, 0.9f, 1.2f);
            } else if (it.getType() == Material.GREEN_CONCRETE) {
                // Join team
                if (team.getMembers().size() >= plugin.getTeamManager().getMaxMembers()) {
                    es.jaie55.boatracing.util.Text.msg(p, "&cĐội này đã đầy.");
                    p.playSound(p.getLocation(), org.bukkit.Sound.BLOCK_NOTE_BLOCK_BASS, 0.8f, 0.6f);
                    return;
                }
                if (plugin.getTeamManager().getTeamByMember(p.getUniqueId()).isPresent()) {
                    es.jaie55.boatracing.util.Text.msg(p, "&cBạn đã ở trong một đội. Hãy rời đội trước.");
                    p.playSound(p.getLocation(), org.bukkit.Sound.BLOCK_NOTE_BLOCK_BASS, 0.8f, 0.6f);
                    return;
                }
                team.addMember(p.getUniqueId());
                plugin.getTeamManager().save();
                es.jaie55.boatracing.util.Text.msg(p, "&aBạn đã tham gia đội " + team.getName());
                // Notify other team members
                for (java.util.UUID m : team.getMembers()) {
                    if (m.equals(p.getUniqueId())) continue;
                    org.bukkit.OfflinePlayer op = Bukkit.getOfflinePlayer(m);
                    if (op.isOnline() && op.getPlayer() != null) {
                        es.jaie55.boatracing.util.Text.msg(op.getPlayer(), "&e" + p.getName() + " đã tham gia đội.");
                    }
                }
                p.playSound(p.getLocation(), org.bukkit.Sound.ENTITY_EXPERIENCE_ORB_PICKUP, 0.8f, 1.3f);
                openTeamView(p, team);
            } else if (it.getType() == Material.RED_CONCRETE) {
                // Leave team (member): always open confirmation; if last member, warn about deletion
                if (!team.isMember(p.getUniqueId())) return;
                openLeaveConfirm(p, team);
                p.playSound(p.getLocation(), org.bukkit.Sound.UI_BUTTON_CLICK, 0.9f, 1.1f);
            } else if (it.getType() == Material.PLAYER_HEAD) {
                // Player head: own profile; other members require admin via commands/GUI
                if (im instanceof SkullMeta) {
                    SkullMeta sm = (SkullMeta) im;
                    if (sm.getOwningPlayer() != null) {
                        java.util.UUID memberId = sm.getOwningPlayer().getUniqueId();
                        if (memberId.equals(p.getUniqueId())) {
                            openMemberProfile(p, team);
                            p.playSound(p.getLocation(), org.bukkit.Sound.UI_BUTTON_CLICK, 0.9f, 1.2f);
                        } else {
                            es.jaie55.boatracing.util.Text.msg(p, "&cChỉ quản trị viên mới có thể quản lý thành viên khác.");
                            p.playSound(p.getLocation(), org.bukkit.Sound.BLOCK_NOTE_BLOCK_BASS, 0.8f, 0.6f);
                        }
                    }
                }
            } else if (it.getType() == Material.TNT) {
                // Open disband confirmation (admin, or member if enabled in config)
                boolean cfgDisband = plugin.getConfig().getBoolean("player-actions.allow-team-disband", false);
                boolean isAdmin = p.hasPermission("boatracing.admin");
                if (!(isAdmin || (cfgDisband && team.isMember(p.getUniqueId())))) {
                    es.jaie55.boatracing.util.Text.msg(p, "&cMáy chủ này đã hạn chế việc giải tán đội. Chỉ quản trị viên mới có thể giải tán đội.");
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
                // Prefer the team id embedded in the back arrow; fallback to player's team
                ItemMeta backMeta = it.getItemMeta();
                Team backTeam = null;
                if (backMeta != null) {
                    String backTid = backMeta.getPersistentDataContainer().get(KEY_TEAM_ID, PersistentDataType.STRING);
                    if (backTid != null) {
                        backTeam = plugin.getTeamManager().getTeams().stream().filter(t -> t.getId().toString().equals(backTid)).findFirst().orElse(null);
                    }
                }
                if (backTeam == null) {
                    backTeam = plugin.getTeamManager().getTeamByMember(p.getUniqueId()).orElse(null);
                }
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
            boolean allowColor = plugin.getConfig().getBoolean("player-actions.allow-team-color", false);
            if (!allowColor && !p.hasPermission("boatracing.admin")) {
                es.jaie55.boatracing.util.Text.msg(p, "&cMáy chủ này đã hạn chế việc đổi màu đội. Chỉ quản trị viên mới có thể đổi màu đội.");
                p.playSound(p.getLocation(), org.bukkit.Sound.BLOCK_NOTE_BLOCK_BASS, 0.8f, 0.6f);
                return;
            }
            // Derive color from item type name prefix
            DyeColor chosen = dyeFromBanner(it.getType());
            if (chosen == null) return;
            team.setColor(chosen);
            plugin.getTeamManager().save();
            es.jaie55.boatracing.util.Text.msg(p, "&aĐã đặt màu đội thành " + chosen.name() + ".");
            // Notify other team members
            for (java.util.UUID m : team.getMembers()) {
                if (m.equals(p.getUniqueId())) continue;
                org.bukkit.OfflinePlayer op = Bukkit.getOfflinePlayer(m);
                if (op.isOnline() && op.getPlayer() != null) {
                    es.jaie55.boatracing.util.Text.msg(op.getPlayer(), "&e" + p.getName() + " đã đổi màu đội thành " + chosen.name() + ".");
                }
            }
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
            boolean allowBoat = plugin.getConfig().getBoolean("player-actions.allow-set-boat", true);
            if (!allowBoat) {
                es.jaie55.boatracing.util.Text.msg(p, "&cMáy chủ này đã hạn chế việc đổi thuyền. Chỉ quản trị viên mới có thể đặt thuyền của bạn.");
                p.playSound(p.getLocation(), org.bukkit.Sound.BLOCK_NOTE_BLOCK_BASS, 0.8f, 0.6f);
                return;
            }
            if (!isBoatItem(chosen)) return;
            team.setBoatType(p.getUniqueId(), chosen.name());
            plugin.getTeamManager().save();
            es.jaie55.boatracing.util.Text.msg(p, "&aĐã đặt loại thuyền thành " + chosen.name() + ".");
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
                // Disband (admin, or member if enabled in config)
                boolean cfgDisband = plugin.getConfig().getBoolean("player-actions.allow-team-disband", false);
                boolean allowed = p.hasPermission("boatracing.admin") || (cfgDisband && team.isMember(p.getUniqueId()));
                if (!allowed) { 
                    es.jaie55.boatracing.util.Text.msg(p, "&cMáy chủ này đã hạn chế việc giải tán đội. Chỉ quản trị viên mới có thể giải tán đội."); 
                    p.playSound(p.getLocation(), org.bukkit.Sound.BLOCK_NOTE_BLOCK_BASS, 0.8f, 0.6f); 
                    return; 
                }
                // Notify all members
                java.util.Set<java.util.UUID> members = new java.util.HashSet<>(team.getMembers());
                for (java.util.UUID m : members) {
                    org.bukkit.OfflinePlayer op = Bukkit.getOfflinePlayer(m);
                    if (op.isOnline()) {
                        Player mp = op.getPlayer();
                        if (mp != null) {
                            es.jaie55.boatracing.util.Text.msg(mp, "&eĐội của bạn đã bị giải tán.");
                            mp.playSound(mp.getLocation(), org.bukkit.Sound.ENTITY_VILLAGER_NO, 0.8f, 0.8f);
                        }
                    }
                }
                plugin.getTeamManager().deleteTeam(team);
                es.jaie55.boatracing.util.Text.msg(p, "&aĐã giải tán đội.");
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
                team.removeMember(p.getUniqueId());
                if (team.getMembers().isEmpty()) {
                    plugin.getTeamManager().deleteTeam(team);
                    es.jaie55.boatracing.util.Text.msg(p, "&aBạn đã rời và đội của bạn đã bị xóa (không còn thành viên).");
                } else {
                    plugin.getTeamManager().save();
                    es.jaie55.boatracing.util.Text.msg(p, "&aBạn đã rời đội.");
                    // Notify remaining members
                    for (java.util.UUID m : team.getMembers()) {
                        org.bukkit.OfflinePlayer op = Bukkit.getOfflinePlayer(m);
                        if (op.isOnline() && op.getPlayer() != null) {
                            es.jaie55.boatracing.util.Text.msg(op.getPlayer(), "&e" + p.getName() + " đã rời đội.");
                        }
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
            try { java.util.UUID.fromString(mid); } catch (Exception ex) { return; }
            es.jaie55.boatracing.util.Text.msg(p, "&cChỉ quản trị viên mới có thể quản lý thành viên. Dùng /boatracing admin team remove <team> <player>.");
            p.playSound(p.getLocation(), org.bukkit.Sound.BLOCK_NOTE_BLOCK_BASS, 0.8f, 0.6f);
            return;
            // Disabled buttons in no-leader mode
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
            es.jaie55.boatracing.util.Text.msg(p, "&cHệ thống trưởng nhóm đã bị loại bỏ.");
            p.playSound(p.getLocation(), org.bukkit.Sound.BLOCK_NOTE_BLOCK_BASS, 0.8f, 0.6f);
            Team teamBack = plugin.getTeamManager().getTeamByMember(p.getUniqueId()).orElse(null);
            if (teamBack != null) openTeamView(p, teamBack);
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
            es.jaie55.boatracing.util.Text.msg(p, "&cThao tác này chỉ dành cho quản trị viên. Dùng /boatracing admin team remove <team> <player>.");
            p.playSound(p.getLocation(), org.bukkit.Sound.BLOCK_NOTE_BLOCK_BASS, 0.8f, 0.6f);
            Team tb = plugin.getTeamManager().getTeamByMember(p.getUniqueId()).orElse(null);
            if (tb != null) openTeamView(p, tb);
        }
    }


    @EventHandler(priority = EventPriority.HIGHEST)
    public void onClose(InventoryCloseEvent e) {
        // No-op
    }

    private void openTeamView(Player p, Team team) {
    int size = 36; // more space to arrange buttons clearly
    Inventory inv = Bukkit.createInventory(null, size, Text.title("Đội • " + team.getName()));

        // Header banner with team info
        ItemStack header = new ItemStack(bannerForColor(team.getColor()));
        BannerMeta bm = (BannerMeta) header.getItemMeta();
        if (bm != null) {
            // Team name in bold with team color
            bm.displayName(Text.item("&l" + team.getName()));
            List<String> lore = new ArrayList<>();
            // Leader info removed in no-leader mode
            lore.add(Text.colorize("&7Thành viên: &f" + team.getMembers().size() + "/" + plugin.getTeamManager().getMaxMembers()));
            // Members list similar to main menu
            lore.add(" ");
            lore.add("&8Thành viên:");
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
    int[] memberSlots = {10, 12, 14, 16};
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
                lore.add(Text.colorize("&7Số tay đua: &f" + (team.getRacerNumber(m) == 0 ? "(chưa đặt)" : team.getRacerNumber(m))));
                lore.add(Text.colorize("&7Thuyền: &f" + team.getBoatType(m)));
                if (m.equals(p.getUniqueId())) lore.add(Text.colorize("&eBấm: &fChỉnh sửa hồ sơ"));
                sm.lore(Text.lore(lore));
                // Link back to team id for handling
                sm.getPersistentDataContainer().set(KEY_TEAM_ID, PersistentDataType.STRING, team.getId().toString());
                skull.setItemMeta(sm);
            }
            inv.setItem(memberSlots[idx++], skull);
        }

    // Action buttons (bottom row): Back | (conditional) Rename | (conditional) Change Color
            int base = size - 9;
            ItemStack back = button(Material.ARROW, Text.item("&7« Quay lại"));
            ItemMeta backMeta = back.getItemMeta();
            if (backMeta != null) {
                backMeta.getPersistentDataContainer().set(KEY_TEAM_ID, PersistentDataType.STRING, team.getId().toString());
                back.setItemMeta(backMeta);
            }
            inv.setItem(base, back);

    boolean isMember = team.isMember(p.getUniqueId());
    boolean isAdmin = p.hasPermission("boatracing.admin");
    boolean cfgRename = plugin.getConfig().getBoolean("player-actions.allow-team-rename", false);
    boolean cfgColor = plugin.getConfig().getBoolean("player-actions.allow-team-color", false);
    boolean cfgDisband = plugin.getConfig().getBoolean("player-actions.allow-team-disband", false);
    if (isAdmin || (cfgRename || cfgColor || cfgDisband)) {
            ItemStack rename = new ItemStack(Material.PAPER);
            ItemMeta rim = rename.getItemMeta();
            if (rim != null) {
                rim.displayName(Text.item("&e&lĐổi tên đội"));
                List<String> rl = new ArrayList<>();
                if (isAdmin) rl.add(Text.colorize("&8Chỉ quản trị viên"));
                else if (cfgRename && isMember) rl.add(Text.colorize("&7Thành viên có thể đổi (cấu hình)"));
                else rl.add(Text.colorize("&8Đã khóa"));
                rim.lore(Text.lore(rl));
                rim.getPersistentDataContainer().set(KEY_TEAM_ID, PersistentDataType.STRING, team.getId().toString());
                rename.setItemMeta(rim);
            }
            inv.setItem((size - 9) + 2, rename); // base+2

            ItemStack color = new ItemStack(dyeFor(team.getColor()));
            ItemMeta cim = color.getItemMeta();
            if (cim != null) {
                cim.displayName(Text.item("&b&lĐổi màu"));
                List<String> cl = new ArrayList<>();
                if (isAdmin) cl.add(Text.colorize("&8Chỉ quản trị viên"));
                else if (cfgColor && isMember) cl.add(Text.colorize("&7Thành viên có thể đổi (cấu hình)"));
                else cl.add(Text.colorize("&8Đã khóa"));
                cim.lore(Text.lore(cl));
                cim.getPersistentDataContainer().set(KEY_TEAM_ID, PersistentDataType.STRING, team.getId().toString());
                color.setItemMeta(cim);
            }
            inv.setItem((size - 9) + 4, color); // base+4

            // Disband button (only if allowed)
            if (isAdmin || (cfgDisband && isMember)) {
                ItemStack disband = new ItemStack(Material.TNT);
                ItemMeta dim = disband.getItemMeta();
                if (dim != null) {
                    dim.displayName(Text.item("&c&lGiải tán đội"));
                    List<String> dl = new ArrayList<>();
                    dl.add(Text.colorize("&cHành động này không thể hoàn tác"));
                    dim.lore(Text.lore(dl));
                    dim.getPersistentDataContainer().set(KEY_TEAM_ID, PersistentDataType.STRING, team.getId().toString());
                    disband.setItemMeta(dim);
                }
                inv.setItem((size - 9) + 8, disband); // base+8
            }
        }

        // Join button (if not member and not full)
    if (!isMember && team.getMembers().size() < plugin.getTeamManager().getMaxMembers()) {
            ItemStack join = new ItemStack(Material.GREEN_CONCRETE);
            ItemMeta jim = join.getItemMeta();
            if (jim != null) {
                jim.displayName(Text.item("&a&lTham gia đội"));
                jim.getPersistentDataContainer().set(KEY_TEAM_ID, PersistentDataType.STRING, team.getId().toString());
                join.setItemMeta(jim);
            }
            inv.setItem((size - 9) + 6, join); // base+6
        }

    // Leave button (if member)
    if (isMember) {
            ItemStack leave = new ItemStack(Material.RED_CONCRETE);
            ItemMeta lim = leave.getItemMeta();
            if (lim != null) {
                lim.displayName(Text.item("&c&lRời đội"));
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
            pim.displayName(Text.item("&6&lHồ sơ của bạn"));
            List<String> lore = new ArrayList<>();
            lore.add(Text.colorize("&7Người chơi: &f" + playerName));
            lore.add(Text.colorize("&7Đội: &f" + teamName));
            lore.add(Text.colorize("&7Số tay đua: &f" + (team.getRacerNumber(p.getUniqueId()) == 0 ? "(chưa đặt)" : team.getRacerNumber(p.getUniqueId()))));
            lore.add(Text.colorize("&7Thuyền: &f" + team.getBoatType(p.getUniqueId())));
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
            rim.displayName(Text.item("&eĐặt số tay đua"));
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
            bim.displayName(Text.item("&bĐổi loại thuyền &7(Bấm)"));
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
    ItemStack backBtn = button(Material.ARROW, Text.item("&7« Quay lại"));
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
                lore.add(Text.colorize("&eBấm: &fChọn màu này"));
                im.lore(Text.lore(lore));
                im.getPersistentDataContainer().set(KEY_TEAM_ID, PersistentDataType.STRING, team.getId().toString());
                banner.setItemMeta(im);
            }
            inv.setItem(slot++, banner);
        }
    // Footer decoration neutral
    fillRow(inv, size - 9, pane(Material.GRAY_STAINED_GLASS_PANE));
    ItemStack back = button(Material.ARROW, Text.item("&7« Quay lại"));
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
                lore.add(Text.colorize("&eBấm: &fChọn"));
                im.lore(Text.lore(lore));
                im.getPersistentDataContainer().set(KEY_TEAM_ID, PersistentDataType.STRING, team.getId().toString());
                it.setItemMeta(im);
            }
            inv.setItem(slot++, it);
        }
    // Footer decoration neutral
    fillRow(inv, size - 9, pane(Material.GRAY_STAINED_GLASS_PANE));
    ItemStack back = button(Material.ARROW, Text.item("&7« Quay lại"));
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
            wim.displayName(Text.item("&c&lXác nhận giải tán"));
            java.util.List<String> lore = new java.util.ArrayList<>();
            lore.add(Text.colorize("&7Thao tác này sẽ xóa đội."));
            lore.add(Text.colorize("&7Các thành viên khác sẽ bị loại khỏi đội."));
            wim.lore(Text.lore(lore));
            wim.getPersistentDataContainer().set(KEY_TEAM_ID, PersistentDataType.STRING, team.getId().toString());
            warn.setItemMeta(wim);
        }
        inv.setItem(4, warn);

        // Confirm button
        ItemStack yes = new ItemStack(Material.RED_CONCRETE);
        ItemMeta yim = yes.getItemMeta();
        if (yim != null) {
            yim.displayName(Text.item("&c&lGiải tán ngay"));
            yim.getPersistentDataContainer().set(KEY_TEAM_ID, PersistentDataType.STRING, team.getId().toString());
            yes.setItemMeta(yim);
        }
        inv.setItem(13, yes);

        // Back
        ItemStack back = button(Material.ARROW, Text.item("&7« Quay lại"));
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
            wim.displayName(Text.item("&c&lXác nhận rời đội"));
            java.util.List<String> lore = new java.util.ArrayList<>();
            lore.add(Text.colorize("&7Bạn sẽ rời đội của mình."));
            if (team.getMembers().size() <= 1) {
                lore.add(Text.colorize("&cNếu bạn là thành viên cuối cùng, đội sẽ bị xóa."));
            } else {
                lore.add(Text.colorize("&7Bạn có thể tham gia lại nếu còn chỗ."));
            }
            wim.lore(Text.lore(lore));
            warn.setItemMeta(wim);
        }
        inv.setItem(4, warn);

        // Confirm button
        ItemStack yes = new ItemStack(Material.RED_CONCRETE);
        ItemMeta yim = yes.getItemMeta();
        if (yim != null) {
            yim.displayName(Text.item("&c&lRời ngay"));
            yes.setItemMeta(yim);
        }
        inv.setItem(13, yes);

        // Back
        ItemStack back = button(Material.ARROW, Text.item("&7« Quay lại"));
        inv.setItem(size - 9, back);
        fillEmptyWith(inv, pane(paneForColor(team.getColor())));
        p.openInventory(inv);
    }

    // openDisbandConfirm removed: disband happens immediately from the team view

    // Text input via AnvilGUI
    private void openAnvilForName(Player p, Team team) {
        openAnvil(p, "name", team.getId(), "", "Tên đội");
    }
    private void openAnvilForRacer(Player p, Team team) {
        openAnvil(p, "racer", team.getId(), "", "Số tay đua");
    }
    private void openAnvilForCreate(Player p) {
        openAnvil(p, "create", null, "", "Tên đội");
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
            if (team == null) { es.jaie55.boatracing.util.Text.msg(p, "&cKhông tìm thấy đội."); return java.util.Collections.emptyList(); }
            boolean allowRename = plugin.getConfig().getBoolean("player-actions.allow-team-rename", false);
            boolean isAdmin = p.hasPermission("boatracing.admin");
            if (!allowRename && !isAdmin) {
                es.jaie55.boatracing.util.Text.msg(p, "&cMáy chủ này đã hạn chế việc đổi tên đội. Chỉ quản trị viên mới có thể đổi tên đội.");
                return java.util.Collections.emptyList();
            }
            if (!isAdmin && !team.isMember(p.getUniqueId())) {
                es.jaie55.boatracing.util.Text.msg(p, "&cChỉ thành viên của đội mới có thể đổi tên đội của mình.");
                return java.util.Collections.emptyList();
            }
            String err = validateNameMessage(input);
            if (err != null) {
                es.jaie55.boatracing.util.Text.msg(p, "&c" + err);
                String retry = input.isEmpty() ? BLANK : input;
                p.playSound(p.getLocation(), org.bukkit.Sound.BLOCK_NOTE_BLOCK_BASS, 0.8f, 0.6f);
                return java.util.Arrays.asList(AnvilGUI.ResponseAction.replaceInputText(retry));
            }
            boolean exists = plugin.getTeamManager().getTeams().stream().anyMatch(t -> t != team && t.getName().equalsIgnoreCase(input));
            if (exists) { 
                es.jaie55.boatracing.util.Text.msg(p, "&cĐã tồn tại đội với tên đó."); 
                String retry = input.isEmpty() ? BLANK : input;
                p.playSound(p.getLocation(), org.bukkit.Sound.BLOCK_NOTE_BLOCK_BASS, 0.8f, 0.6f);
                return java.util.Arrays.asList(AnvilGUI.ResponseAction.replaceInputText(retry));
            }
            team.setName(sanitizeName(input)); plugin.getTeamManager().save();
            es.jaie55.boatracing.util.Text.msg(p, "&aĐã đổi tên đội thành &e" + input + "&a.");
            // Notify other team members
            for (java.util.UUID m : team.getMembers()) {
                if (m.equals(p.getUniqueId())) continue;
                org.bukkit.OfflinePlayer op = Bukkit.getOfflinePlayer(m);
                if (op.isOnline() && op.getPlayer() != null) {
                    es.jaie55.boatracing.util.Text.msg(op.getPlayer(), "&e" + p.getName() + " đã đổi tên đội thành &e" + input + "&e.");
                }
            }
            p.playSound(p.getLocation(), org.bukkit.Sound.ENTITY_PLAYER_LEVELUP, 0.8f, 1.4f);
            return java.util.Arrays.asList(
                AnvilGUI.ResponseAction.close(),
                AnvilGUI.ResponseAction.run(() -> openTeamView(p, team))
            );
        } else if ("racer".equals(action)) {
            Team team = getTeam(teamId);
            if (team == null) { es.jaie55.boatracing.util.Text.msg(p, "&cKhông tìm thấy đội."); return java.util.Collections.emptyList(); }
            boolean allowNumber = plugin.getConfig().getBoolean("player-actions.allow-set-number", true);
            if (!allowNumber) {
                es.jaie55.boatracing.util.Text.msg(p, "&cMáy chủ này đã hạn chế việc đặt số tay đua. Chỉ quản trị viên mới có thể đặt số của bạn.");
                return java.util.Collections.emptyList();
            }
            String err = validateNumberFormatRange(input);
            if (err != null) { 
                es.jaie55.boatracing.util.Text.msg(p, "&c" + err);
                String retry = input.isEmpty() ? BLANK : input;
                p.playSound(p.getLocation(), org.bukkit.Sound.BLOCK_NOTE_BLOCK_BASS, 0.8f, 0.6f);
                return java.util.Arrays.asList(AnvilGUI.ResponseAction.replaceInputText(retry));
            }
            int num = Integer.parseInt(input);
            team.setRacerNumber(p.getUniqueId(), num); plugin.getTeamManager().save();
            es.jaie55.boatracing.util.Text.msg(p, "&aĐã đặt số tay đua của bạn thành " + num + ".");
            p.playSound(p.getLocation(), org.bukkit.Sound.ENTITY_EXPERIENCE_ORB_PICKUP, 0.9f, 1.5f);
            return java.util.Arrays.asList(
                AnvilGUI.ResponseAction.close(),
                AnvilGUI.ResponseAction.run(() -> openMemberProfile(p, team))
            );
        } else if ("create".equals(action)) {
            if (plugin.getTeamManager().getTeamByMember(p.getUniqueId()).isPresent()) { 
                es.jaie55.boatracing.util.Text.msg(p, "&cBạn đã ở trong một đội. Hãy rời đội trước."); 
                return null; 
            }
            boolean allowCreate = plugin.getConfig().getBoolean("player-actions.allow-team-create", true);
            if (!allowCreate) {
                es.jaie55.boatracing.util.Text.msg(p, "&cMáy chủ này đã hạn chế việc tạo đội. Chỉ quản trị viên mới có thể tạo đội.");
                return java.util.Collections.emptyList();
            }
            String err = validateNameMessage(input);
            if (err != null) { 
                es.jaie55.boatracing.util.Text.msg(p, "&c" + err);
                String retry = input.isEmpty() ? BLANK : input;
                p.playSound(p.getLocation(), org.bukkit.Sound.BLOCK_NOTE_BLOCK_BASS, 0.8f, 0.6f);
                return java.util.Arrays.asList(AnvilGUI.ResponseAction.replaceInputText(retry));
            }
            boolean exists = plugin.getTeamManager().getTeams().stream().anyMatch(t -> t.getName().equalsIgnoreCase(input));
            if (exists) { 
                es.jaie55.boatracing.util.Text.msg(p, "&cĐã tồn tại đội với tên đó."); 
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
            || plain.equals(Text.plain(TITLE_LEAVE_CONFIRM));
        if (block) {
            e.setCancelled(true);
        }
    }

    private static String sanitizeName(String raw) {
        String s = raw.replace("§", "").replace("&", "").trim();
        if (s.length() > 20) s = s.substring(0, 20);
        return s;
    }

    // Leader-based subviews removed in leaderless model

    public static String validateNameMessage(String raw) {
        String s = raw == null ? "" : raw.trim();
        if (s.isEmpty()) return "Tên không được để trống.";
        if (s.length() > 20) return "Tên quá dài (tối đa 20).";
        // Allow letters, numbers, spaces, - _
        if (!s.matches("[A-Za-z0-9 _-]+")) return "Chỉ cho phép chữ, số, khoảng trắng, '-' và '_'.";
        return null;
    }

    private String validateNumberFormatRange(String raw) {
        String s = raw == null ? "" : raw.trim();
        if (!s.matches("\\d+")) return "Vui lòng chỉ nhập chữ số.";
        int n = Integer.parseInt(s);
        if (n < 1 || n > 99) return "Số phải từ 1 đến 99.";
        return null;
    }
*/
