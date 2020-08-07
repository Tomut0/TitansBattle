package me.roinujnosde.titansbattle.managers;

import me.roinujnosde.titansbattle.Helper;
import me.roinujnosde.titansbattle.TitansBattle;
import me.roinujnosde.titansbattle.events.*;
import me.roinujnosde.titansbattle.types.*;
import me.roinujnosde.titansbattle.types.Game.Mode;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Location;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.text.MessageFormat;
import java.util.*;
import java.util.stream.Collectors;

public class GameManager {

    private TitansBattle plugin;
    private ConfigManager cm;
    private TaskManager tm;
    private DatabaseManager dm;

    private Helper helper;

    private boolean happening;
    private boolean lobby;
    private boolean battle;
    private boolean preparation;

    private Map<Group, Integer> groups;

    private HashMap<Player, Integer> killsCount;
    private List<UUID> participants;
    private List<Player> casualties;
    @Nullable
    private Game currentGame;

    public void load() {
        plugin = TitansBattle.getInstance();
        cm = plugin.getConfigManager();
        tm = plugin.getTaskManager();
        dm = plugin.getDatabaseManager();
        helper = plugin.getHelper();

        groups = new HashMap<>();
        killsCount = new HashMap<>();
        participants = new ArrayList<>();
        casualties = new ArrayList<>();
    }

    /**
     * Gets the current game, or null, if there is no one happening
     *
     * @return the current game
     */
    @Nullable
    public Game getCurrentGame() {
        return currentGame;
    }

    private void processWinner(@NotNull Game game, @NotNull Player winner, @Nullable Player killer) {

        //Chama os eventos de Novo Killer e Novo Vencedor
        Bukkit.getPluginManager().callEvent(new PlayerWinEvent(winner));

        //Define o novo killer
        if (killer != null) {
            setKiller(game, killer, null);
        }
        //Anuncia
        Bukkit.getServer().broadcastMessage(MessageFormat.format(plugin.getLang("who_won", getCurrentGame()), winner.getName()));

        helper.increaseVictories(winner.getUniqueId(), game.getMode());

        setWinner(game.getMode(), winner);

        //Finaliza o jogo atual
        finishGame(null, null, winner);
    }

    private void setWinner(@NotNull Mode mode, @NotNull Player winner) {
        Set<UUID> uuids = new HashSet<>();
        uuids.add(winner.getUniqueId());
        plugin.getDatabaseManager().getTodaysWinners().setWinners(mode, uuids);
    }

    private void processWinners(@NotNull Game game, @NotNull Group group, @Nullable Player killerPlayer) {
        //Chama os eventos
        if (killerPlayer != null) {
            Bukkit.getPluginManager().callEvent(new NewKillerEvent(killerPlayer, null));
            setKiller(game, killerPlayer, null);
        }
        Bukkit.getPluginManager().callEvent(new GroupWinEvent(group));

        List<Player> leaders = new ArrayList<>();
        List<Player> members = new ArrayList<>();

        //Adiciona os participantes restantes à lista de vencedores
        List<UUID> currentWinners = participants.stream().filter(group.getWrapper()::isMember).collect(Collectors.toList());
        //List<UUID> currentWinners = new ArrayList<>(participants);

        //Filtra os membros e líderes da lista de participantes
        participants.stream().map(Bukkit::getPlayer).forEach(player -> {
            if (group.getWrapper().isLeaderOrOfficer(player.getUniqueId())) {
                leaders.add(player);
            } else {
                members.add(player);
            }
        });

        //Filtra os membros e líderes da lista de mortos e os adiciona à lista de vencedores
        casualties.stream().filter(player -> group.getWrapper().isMember(player.getUniqueId())).forEach(player -> {
            currentWinners.add(player.getUniqueId());
            if (group.getWrapper().isLeaderOrOfficer(player.getUniqueId())) {
                leaders.add(player);
            } else {
                members.add(player);
            }
        });

        Mode mode = game.getMode();

        //Salva os vencedores
        DatabaseManager db = plugin.getDatabaseManager();
        Winners today = db.getTodaysWinners();
        today.setWinnerGroup(mode, group);
        today.setWinners(mode, new HashSet<>(currentWinners));

        group.setVictories(mode, (group.getVictories(mode) + 1));

        currentWinners.stream().map(Bukkit::getOfflinePlayer).forEach(player
                -> helper.increaseVictories(player.getUniqueId(), mode));

        //Faz o anuncio
        Bukkit.getServer().broadcastMessage(MessageFormat.format(plugin.getLang("who_won", game),
                group.getWrapper().getName()));

        members.addAll(leaders);
        Bukkit.getPluginManager().callEvent(new PlayerWinEvent(members));
        //Salva as alterações
        cm.save();
        //Finaliza o jogo
        finishGame(leaders, members, null);
        //Dá os prêmios
    }

    private void givePrizesToMembersAndLeaders(@NotNull Game game, List<Player> leaders, List<Player> members) {
        Prizes prizes = game.getPrizes();
        if (prizes.isTreatLeadersAsMembers()) {
            members.addAll(leaders);
            leaders.clear();
        }
        if (prizes.isLeaderItemsEnabled()) {
            helper.giveItemsToPlayers(game, leaders, prizes.getLeaderItems().toArray(new ItemStack[0]));
        }
        if (prizes.isMemberItemsEnabled()) {
            helper.giveItemsToPlayers(game, members, prizes.getMemberItems().toArray(new ItemStack[0]));
        }
        if (plugin.getEconomy() != null) {
            if (prizes.isLeaderMoneyEnabled()) {
                helper.giveMoneyToPlayers(leaders, prizes.getLeaderMoneyAmount(), prizes.isLeaderMoneyDivide());
            }
            if (prizes.isMemberMoneyEnabled()) {
                helper.giveMoneyToPlayers(members, prizes.getMemberMoneyAmount(), prizes.isMemberMoneyDivide());
            }
        }
        if (prizes.isLeaderCommandsEnabled()) {
            helper.runCommandsOnPlayers(leaders, prizes.getLeaderCommands(), prizes.getLeaderCommandsSomeNumber(), prizes.isLeaderCommandsSomeNumberDivide());
        }
        if (prizes.isMemberCommandsEnabled()) {
            helper.runCommandsOnPlayers(members, prizes.getMemberCommands(), prizes.getMemberCommandsSomeNumber(), prizes.isMemberCommandsSomeNumberDivide());
        }
    }

    /**
     * Initiates the schedule task or starts the game
     */
    public void startOrSchedule() {
        if (!cm.isScheduler()) {
            plugin.debug("Scheduler disabled", true);
            return;
        }
        plugin.debug("Scheduler enabled", true);
        if (isHappening()) {
            plugin.debug("Already happening or starting", true);
            tm.startSchedulerTask(300);
            return;
        }
        int currentTimeInSeconds = helper.getCurrentTimeInSeconds();
        int oneDay = 86400;

        Scheduler nextScheduler = helper.getNextSchedulerOfDay();
        if (nextScheduler == null) {
            plugin.debug("No next scheduler today", true);
            tm.startSchedulerTask(oneDay - currentTimeInSeconds);
            return;
        }
        int nextHour = nextScheduler.getHour();
        int nextMinute = nextScheduler.getMinute();
        int nextTimeInSeconds = (nextHour * 60 * 60) + (nextMinute * 60);
        plugin.debug(String.format("Scheduler Time: %s:%s", nextHour, nextMinute), true);

        if (currentTimeInSeconds == nextTimeInSeconds) {
            plugin.debug("It's time!", true);
            start(nextScheduler.getMode());
        } else {
            plugin.debug("It's not time yet!", true);
            tm.startSchedulerTask(nextTimeInSeconds - currentTimeInSeconds);
        }
    }

    /**
     * Starts a game of the specified mode
     *
     * @param mode the mode
     */
    public void start(Mode mode) {
        if (isHappening()) {
            return;
        }
        if (helper.isGroupBased(helper.getGame(mode))) {
            if (!plugin.isFactions() && !plugin.isSimpleClans()) {
                throw new IllegalStateException("You cannot start a group based game without SimpleClans or Factions!");
            }
        }
        currentGame = helper.getGame(mode);
        LobbyStartEvent event = new LobbyStartEvent(currentGame);
        Bukkit.getPluginManager().callEvent(event);
        if (event.isCancelled()) {
            return;
        }
        setLobby(true);
        setHappening(true);
        int times = currentGame.getAnnouncementStartingTimes();
        long interval = currentGame.getAnnouncementStartingInterval();
        long seconds = times * interval;
        Bukkit.getServer().broadcastMessage(MessageFormat.format(plugin.getLang("starting_game", getCurrentGame()),
                Long.toString(seconds),
                Integer.toString(currentGame.getMinimumGroups()),
                Integer.toString(currentGame.getMinimumPlayers()),
                Integer.toString(getGroupsParticipatingCount()),
                Integer.toString(getPlayersParticipatingCount())));
        tm.startLobbyAnnouncementTask(times, interval);
    }

    /**
     * Initiates the battle
     *
     */
    void startBattle() {
        Game game = getCurrentGame();
        if (isBattle() || game == null) {
            return;
        }
        GameStartEvent event = new GameStartEvent(game);
        Bukkit.getPluginManager().callEvent(event);
        if (event.isCancelled()) {
            cancelGame(Bukkit.getConsoleSender());
            return;
        }
        if (helper.isGroupBased(game)) {
            if (getParticipants().size() < getMinimumPlayers() || groups.size() < getMinimumGroups()) {
                Bukkit.broadcastMessage(plugin.getLang("not_enough_participants", getCurrentGame()));
                finishGame(null, null, null);
                return;
            }
        } else {
            if (getParticipants().size() < getMinimumPlayers()) {
                Bukkit.broadcastMessage(plugin.getLang("not_enough_participants", getCurrentGame()));
                finishGame(null, null, null);
                return;
            }
        }
        setLobby(false);
        setPreparation(true);
        tm.startPreparationTimeTask(game.getPreparationTime());
        Bukkit.getServer().broadcastMessage(MessageFormat.format(plugin.getLang("game_started", getCurrentGame()), Long.toString(game.getPreparationTime())));
        helper.teleportAll(game.getArena());
        tm.startGameExpirationTask(game.getExpirationTime());
        tm.startArenaAnnouncementTask(game.getAnnouncementGameInfoInterval());
        if (game.isDeleteGroups()) {
            int deleted = 0;
            for (Group group : plugin.getDatabaseManager().getGroups()) {
                if (!groups.containsKey(group)) {
                    group.getWrapper().disband();
                    deleted++;
                }
            }
            if (deleted != 0) {
                Bukkit.getServer().broadcastMessage(MessageFormat.format(plugin.getLang("deleted_groups", getCurrentGame()), Integer.toString(deleted)));
            }
        }
    }

    /**
     * Adds a player to the casualty list, broadcasts it and removes from the
     * participants list
     *
     * @param victim the victim
     * @param killer the killer
     */
    public void addCasualty(@NotNull Player victim, @Nullable Player killer) {
        Game game = getCurrentGame();
        if (game == null) {
            return;
        }
        casualties.add(victim);
        victim.sendMessage(plugin.getLang("watch_to_the_end", game));
        if (killer != null) {
            helper.increaseKillsCount(killer);
            Bukkit.broadcastMessage(MessageFormat.format(plugin.getLang("killed_by", game), victim.getName(), killer.getName()));
            Bukkit.broadcastMessage(MessageFormat.format(plugin.getLang("has_killed_times", game), killer.getName(), Integer.toString(getPlayerKillsCount(killer))));
        } else {
            Bukkit.broadcastMessage(MessageFormat.format(plugin.getLang("died_by_himself", game), victim.getName()));
        }
        Mode mode = game.getMode();

        //Victim
        dm.getWarrior(victim.getUniqueId(), vWarrior -> {
            vWarrior.setDeaths(mode, vWarrior.getDeaths(mode) + 1);
            Group vGroup = vWarrior.getGroup();
            if (vGroup != null) {
                vGroup.setDeaths(mode, vGroup.getDeaths(mode) + 1);
            }
        });

        //Killer
        if (killer != null) {
            dm.getWarrior(killer.getUniqueId(), kWarrior -> {
                kWarrior.setKills(mode, kWarrior.getKills(mode) + 1);
                Group kGroup = kWarrior.getGroup();
                if (kGroup != null) {
                    kGroup.setKills(mode, kGroup.getKills(mode) + 1);
                }
            });
        }

        removeParticipant(victim);
    }

    /**
     * Adds a player to the quitters list and removes from the participants list
     *
     * @param player the quitter
     */
    public void addQuitter(Player player) {
        Game currentGame = getCurrentGame();
        if (currentGame == null) return;
        if (!isLobby()) {
            if (helper.isFun(currentGame)) {
                cm.getClearInventory().add(player.getUniqueId());
            }
            cm.getRespawn().add(player.getUniqueId());
            cm.save();
        }
        removeParticipant(player);
    }

    private boolean tryToAddParticipant(Player player) {
        Game currentGame = getCurrentGame();
        if (currentGame == null) return false;

        if (!isLobby()) {
            player.sendMessage(plugin.getLang("not-starting-or-started"));
        } else {
            if (getParticipants().contains(player.getUniqueId())) {
                player.sendMessage(plugin.getLang("already-joined", currentGame));
                return false;
            }
            if (helper.isGroupBased(currentGame)) {
                if (!helper.isMemberOfAGroup(player)) {
                    player.sendMessage(plugin.getLang("not_in_a_group", currentGame));
                    return false;
                }
            }
            if (isBattle() || isPreparation()) {
                player.sendMessage(plugin.getLang("game_is_happening", currentGame));
                return false;
            }
            if (isLobby()) {
                if (helper.isFun(currentGame)) {
                    if (helper.inventoryHasItems(player)) {
                        player.sendMessage(plugin.getLang("clear-your-inventory", currentGame));
                        return false;
                    }
                }

                PlayerJoinGameEvent event = new PlayerJoinGameEvent(player, currentGame);
                Bukkit.getPluginManager().callEvent(event);
                return !event.isCancelled();
            }
        }
        return false;
    }

    /**
     * Adds a player to the game
     *
     * @param player the player
     */
    public void addParticipant(Player player) {
        if (!tryToAddParticipant(player) || currentGame == null) {
            return;
        }

        Location lobby = currentGame.getLobby();
        if (lobby != null) {
            player.teleport(lobby);
        } else {
            player.sendMessage(ChatColor.RED + "An error has ocurred while trying to teleport you! Contact the admin! :o");
            plugin.debug("You have not setted the Lobby teleport destination!", false);
            return;
        }

        plugin.getDatabaseManager().getWarrior(player.getUniqueId(), warrior -> {
            if (helper.isGroupBased(currentGame)) {
                Group group = warrior.getGroup();
                if (group == null) {
                    plugin.debug("Player not in group", true);
                    return;
                }
                String groupName = group.getWrapper().getName();
                if (groups.containsKey(group)) {
                    //Adiciona mais um jogador na contagem
                    plugin.debug(String.format("The group %s is already in the game. Increasing player count.", groupName),
                            true);
                    groups.replace(group, groups.get(group) + 1);
                } else {
                    plugin.debug(String.format("Adding group %s to the game for the first time", groupName), true);
                    //Adiciona o grupo na lista
                    groups.put(group, 1);
                }
                plugin.debug(String.format("Group %s member count %d", groupName, groups.get(group)), true);
            }

            if (helper.isFun(currentGame)) {
                List<ItemStack> kit = currentGame.getKit();
                if (kit != null && !kit.isEmpty()) {
                    player.getInventory().addItem(kit.toArray(new ItemStack[0]));
                }
            }

            killsCount.put(player, 0);
            participants.add(player.getUniqueId());
            for (UUID uuid : getParticipants()) {
                Player p = Bukkit.getPlayer(uuid);
                p.sendMessage(MessageFormat.format(plugin.getLang("player_joined", getCurrentGame()), player.getName()));
            }
        });
    }

    public void setKiller(@NotNull Game game, @NotNull Player killer, Player victim) {
        Bukkit.getPluginManager().callEvent(new NewKillerEvent(killer, victim));
        Bukkit.getServer().broadcastMessage(MessageFormat.format(plugin.getLang("new_killer", game), killer.getName()));
        plugin.getDatabaseManager().getTodaysWinners().setKiller(game.getMode(), killer.getUniqueId());
    }

    public void cancelGame(CommandSender cs) {
        String sender = "Console";
        if (cs instanceof Player) {
            sender = cs.getName();
        }
        Bukkit.broadcastMessage(MessageFormat.format(plugin.getLang("cancelled", getCurrentGame()), sender));
        finishGame(null, null, null);
    }

    public void finishGame(@Nullable List<Player> leaders, @Nullable List<Player> members,
                           @Nullable Player winner) {
        Game game = getCurrentGame();
        if (!isHappening() || game == null) {
            return;
        }
        setLobby(false);
        setBattle(false);
        setPreparation(false);
        setHappening(false);
        helper.teleportAll(game.getExit());
        tm.killAllTasks();
        if (helper.isGroupBased(game)) {
            groups.clear();
            killsCount.clear();
        }
        if (helper.isFun(game)) {
            for (UUID uuid : getParticipants()) {
                Player player = Bukkit.getPlayer(uuid);
                helper.clearInventory(player);
            }
        }
        if (winner != null) {
            givePrizesToMembersAndLeaders(game, null, Collections.singletonList(winner));
        }
        if (leaders != null && members != null) {
            givePrizesToMembersAndLeaders(game, leaders, members);
        }

        participants.clear();
        dm.saveAll();
        currentGame = null;
    }

    public List<UUID> getParticipants() {
        return Collections.unmodifiableList(participants);
    }

    @SuppressWarnings("unused")
    public List<Player> getCasualties() {
        return Collections.unmodifiableList(casualties);
    }

    public Map<Group, Integer> getGroups() {
        return Collections.unmodifiableMap(groups);
    }

    void setPreparation(boolean preparation) {
        this.preparation = preparation;
    }

    private void setLobby(boolean lobby) {
        this.lobby = lobby;
    }

    void setBattle(boolean battle) {
        this.battle = battle;
    }

    private void setHappening(boolean happening) {
        this.happening = happening;
    }

    public HashMap<Player, Integer> getKillsCount() {
        return killsCount;
    }

    public int getPlayerKillsCount(Player player) {
        if (killsCount.containsKey(player)) {
            return killsCount.get(player);
        }
        return 0;
    }

    /**
     * Returns the killer of the specified game
     *
     * @param game the game
     * @return the killer UUID
     */
    @SuppressWarnings("unused")
    public UUID getKiller(@NotNull Game game) {
        return plugin.getDatabaseManager().getLatestWinners().getKiller(game.getMode());
    }

    /**
     * Removes a player from the game and clears his inventory if the mode is
     * FUN
     *
     * @param player the player
     */
    public boolean removeParticipant(@NotNull Player player) {
        Game game = getCurrentGame();
        if (!getParticipants().contains(player.getUniqueId()) || game == null) {
            return false;
        }

        participants.remove(player.getUniqueId());

        if (helper.isGroupBased(game)) {
            processGroupMemberLeaving(game, player);
        } else {
            processRemainingParticipants(game);
        }
        if (!teleportToExit(game, player)) return false;

        if (helper.isFun(game)) {
            clearInventory(player);
        }

        return true;
    }

    private void processGroupMemberLeaving(@NotNull Game game, @NotNull Player player) {
        plugin.getDatabaseManager().getWarrior(player.getUniqueId(), warrior -> {
            Group group = warrior.getGroup();
            if (group == null) {
                plugin.debug(String.format("Player %s is not in a group", player.getName()), false);
                return;
            }
            int members = groups.getOrDefault(group, 0);
            groups.replace(group, --members);

            if (members < 1) {
                groups.remove(group);

                if (!isBattle()) {
                    return;
                }
                Bukkit.broadcastMessage(MessageFormat.format(plugin.getLang("group_defeated",
                        getCurrentGame()), group.getWrapper().getName()));
                Bukkit.getPluginManager().callEvent(new GroupDefeatedEvent(group, player));
                increaseDefeats(game.getMode(), group);

                processRemainingGroups(game);
            }
        });
    }

    private void processRemainingParticipants(@NotNull Game game) {
        if (getParticipants().size() == 1) {
            Player killerPlayer = null;
            int mostKills = 0;
            for (Player p : killsCount.keySet()) {
                if (killsCount.get(p) > mostKills) {
                    mostKills = killsCount.get(p);
                    killerPlayer = p;
                }
            }
            processWinner(game, Bukkit.getPlayer(getParticipants().get(0)), killerPlayer);
        }
    }

    private void processRemainingGroups(@NotNull Game game) {
        List<Group> remainingClans = new ArrayList<>(groups.keySet());
        Player killer = null;
        if (remainingClans.size() == 1) {
            int mostKills = 0;
            for (Player p : killsCount.keySet()) {
                if (killsCount.get(p) >= mostKills) {
                    mostKills = killsCount.get(p);
                    killer = p;
                }
            }
            processWinners(game, remainingClans.get(0), killer);
        }
    }

    private void increaseDefeats(@NotNull Mode mode, @NotNull Group group) {
        group.setDefeats(mode, group.getDefeats(mode) + 1);
    }

    private boolean teleportToExit(@NotNull Game game, @NotNull Player player) {
        Location exit = game.getExit();
        if (exit != null) {
            player.teleport(exit);
        } else {
            player.sendMessage(ChatColor.RED + "An error has ocurred while trying to teleport you! Contact the admin! :o");
            plugin.debug("You have not setted the Exit teleport destination!", false);
            return false;
        }
        return true;
    }

    private void clearInventory(@NotNull Player player) {
        player.getInventory().clear();
        player.getInventory().setHelmet(null);
        player.getInventory().setChestplate(null);
        player.getInventory().setLeggings(null);
        player.getInventory().setBoots(null);
    }

    /**
     * Checks if the game is happening
     *
     * @return if it the game is happening (lobby, preparation or battle)
     */
    public boolean isHappening() {
        return happening;
    }

    /**
     * Checks if the game is starting
     *
     * @return if it is starting
     */
    public boolean isLobby() {
        return lobby;
    }

    /**
     * Checks if the battle has started
     *
     * @return if the battle has started
     */
    public boolean isBattle() {
        return battle;
    }

    /**
     * Checks if the game is in preparation mode
     *
     * @return if it is in preparation
     */
    public boolean isPreparation() {
        return preparation;
    }

    /**
     * Gets the minimum players to start the game
     *
     * @return the minimum
     */
    public int getMinimumPlayers() {
        if (currentGame == null) {
            return 2;
        }
        int a = currentGame.getMinimumPlayers();
        if (a <= 1) {
            return 2;
        }
        return a;
    }

    /**
     * Gets the minimum groups to start the game
     *
     * @return the minimum
     */
    public int getMinimumGroups() {
        if (currentGame == null) {
            return 2;
        }
        int a = currentGame.getMinimumGroups();
        if (a <= 1) {
            return 2;
        }
        return a;
    }

    /**
     * Returns the amount of players that are in the game
     *
     * @return the amount of participants
     */
    public int getPlayersParticipatingCount() {
        return participants.size();
    }

    /**
     * Returns the amount of groups that are in the game
     */
    int getGroupsParticipatingCount() {
        if (!isHappening()) {
            return 0;
        }
        return groups.size();
    }
}
