package com.gmail.val59000mc.players;

import com.gmail.val59000mc.UhcCore;
import com.gmail.val59000mc.configuration.MainConfig;
import com.gmail.val59000mc.customitems.GameItem;
import com.gmail.val59000mc.customitems.KitsManager;
import com.gmail.val59000mc.customitems.UhcItems;
import com.gmail.val59000mc.events.*;
import com.gmail.val59000mc.exceptions.UhcPlayerDoesNotExistException;
import com.gmail.val59000mc.exceptions.UhcPlayerJoinException;
import com.gmail.val59000mc.exceptions.UhcPlayerNotOnlineException;
import com.gmail.val59000mc.exceptions.UhcTeamException;
import com.gmail.val59000mc.game.GameManager;
import com.gmail.val59000mc.game.GameState;
import com.gmail.val59000mc.game.handlers.CustomEventHandler;
import com.gmail.val59000mc.game.handlers.ScoreboardHandler;
import com.gmail.val59000mc.languages.Lang;
import com.gmail.val59000mc.scenarios.Scenario;
import com.gmail.val59000mc.tasks.CheckRemainingPlayerTask;
import com.gmail.val59000mc.tasks.TeleportPlayersTask;
import com.gmail.val59000mc.tasks.TimeBeforeSendBungeeTask;
import com.gmail.val59000mc.utils.*;
import com.gmail.val59000mc.utils.snapshot.ItemStackSnapshot;
import com.google.common.io.ByteArrayDataOutput;
import com.google.common.io.ByteStreams;

import io.papermc.lib.PaperLib;

import org.bukkit.*;
import org.bukkit.entity.*;
import org.bukkit.inventory.EntityEquipment;
import org.bukkit.inventory.ItemStack;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;

import java.util.*;
import java.util.logging.Logger;
import java.util.stream.Collectors;

public class PlayerManager {

	private static final Logger LOGGER = Logger.getLogger(PlayerManager.class.getCanonicalName());

	private final CustomEventHandler customEventHandler;
	private final ScoreboardHandler scoreboardHandler;
	private final List<UhcPlayer> players;
	private long lastDeathTime;

	public PlayerManager(CustomEventHandler customEventHandler, ScoreboardHandler scoreboardHandler) {
		this.customEventHandler = customEventHandler;
		this.scoreboardHandler = scoreboardHandler;
		players = Collections.synchronizedList(new ArrayList<>());
	}

	public void setLastDeathTime() {
		lastDeathTime = System.currentTimeMillis();
	}

	public boolean isPlayerAllowedToJoin(Player player) throws UhcPlayerJoinException {
		GameManager gm = GameManager.getGameManager();
		UhcPlayer uhcPlayer;

		switch(gm.getGameState()){
			case LOADING:
				throw new UhcPlayerJoinException(Lang.KICK_LOADING);

			case WAITING:
				return true;

			case STARTING:
				if (doesPlayerExist(player)){
					uhcPlayer = getUhcPlayer(player);
					if(uhcPlayer.getState().equals(PlayerState.PLAYING)){
						return true;
					}else{
						throw new UhcPlayerJoinException(Lang.KICK_STARTING);
					}
				}else{
					throw new UhcPlayerJoinException(Lang.KICK_STARTING);
				}
			case DEATHMATCH:
			case PLAYING:
				if (doesPlayerExist(player)){
					uhcPlayer = getUhcPlayer(player);

					boolean canSpectate = gm.getConfig().get(MainConfig.CAN_SPECTATE_AFTER_DEATH);
					if(
							uhcPlayer.getState().equals(PlayerState.PLAYING) ||
							((canSpectate || player.hasPermission("uhc-core.spectate.override")) && uhcPlayer.getState().equals(PlayerState.DEAD))
					){
						return true;
					}else{
						throw new UhcPlayerJoinException(Lang.KICK_PLAYING);
					}
				}else{
					if(player.hasPermission("uhc-core.join-override")
							|| player.hasPermission("uhc-core.spectate.override")
							|| gm.getConfig().get(MainConfig.CAN_JOIN_AS_SPECTATOR) && gm.getConfig().get(MainConfig.CAN_SPECTATE_AFTER_DEATH)){
						UhcPlayer spectator = newUhcPlayer(player);
						spectator.setState(PlayerState.DEAD);
						return true;
					}
					throw new UhcPlayerJoinException(Lang.KICK_PLAYING);
				}

			case ENDED:
				if(player.hasPermission("uhc-core.join-override")){
					return true;
				}
				throw new UhcPlayerJoinException(Lang.KICK_ENDED);

		}
		return false;
	}

	/**
	 * This method is used to get the UhcPlayer object from Bukkit Player.
	 * When using this method in the PlayerJoinEvent please check the doesPlayerExist(Player) to see if the player has a matching UhcPlayer.
	 * @param player The Bukkit player you want the UhcPlayer from.
	 * @return Returns a UhcPlayer.
	 */
	public UhcPlayer getUhcPlayer(Player player){
		try {
			return getUhcPlayer(player.getUniqueId());
		}catch (UhcPlayerDoesNotExistException ex){
			throw new RuntimeException(ex);
		}
	}

	public boolean doesPlayerExist(Player player){
		try {
			getUhcPlayer(player.getUniqueId());
			return true;
		} catch (UhcPlayerDoesNotExistException ingored) {
			return false;
		}
	}

	public UhcPlayer getUhcPlayer(String name) throws UhcPlayerDoesNotExistException {
		for(UhcPlayer uhcPlayer : getPlayersList()){
			if(uhcPlayer.getName().equals(name)) {
				return uhcPlayer;
			}
		}
		throw new UhcPlayerDoesNotExistException(name);
	}

	public UhcPlayer getUhcPlayer(UUID uuid) throws UhcPlayerDoesNotExistException {
		for(UhcPlayer uhcPlayer : getPlayersList()){
			if(uhcPlayer.getUuid().equals(uuid)) {
				return uhcPlayer;
			}
		}
		throw new UhcPlayerDoesNotExistException(uuid.toString());
	}

	public UhcPlayer getOrCreateUhcPlayer(Player player){
		if (doesPlayerExist(player)){
			return getUhcPlayer(player);
		}else{
			return newUhcPlayer(player);
		}
	}

	public synchronized UhcPlayer newUhcPlayer(Player bukkitPlayer){
		return newUhcPlayer(bukkitPlayer.getUniqueId(), bukkitPlayer.getName());
	}

	public synchronized UhcPlayer newUhcPlayer(UUID uuid, String name){
		UhcPlayer newPlayer = new UhcPlayer(uuid, name);
		getPlayersList().add(newPlayer);
		return newPlayer;
	}

	public synchronized List<UhcPlayer> getPlayersList(){
		return players;
	}

	public Set<UhcPlayer> getOnlinePlayingPlayers() {
		return players.stream()
				.filter(UhcPlayer::isPlaying)
				.filter(UhcPlayer::isOnline)
				.collect(Collectors.toSet());
	}

	public Set<UhcPlayer> getAllPlayingPlayers() {
		return players.stream()
				.filter(UhcPlayer::isPlaying)
				.collect(Collectors.toSet());
	}

	public void playerJoinsTheGame(Player player){
		UhcPlayer uhcPlayer;

		if (doesPlayerExist(player)){
			uhcPlayer = getUhcPlayer(player);
		}else{
			uhcPlayer = newUhcPlayer(player);
			LOGGER.warning("None existent player joined!");
		}

		GameManager gm = GameManager.getGameManager();
		scoreboardHandler.setUpPlayerScoreboard(uhcPlayer, player);

		switch(uhcPlayer.getState()){
			case WAITING:
				setPlayerWaitsAtLobby(uhcPlayer);

				if(gm.getConfig().get(MainConfig.AUTO_ASSIGN_PLAYER_TO_TEAM)){
					autoAssignPlayerToTeam(uhcPlayer);
				}
				uhcPlayer.sendPrefixedMessage(Lang.PLAYERS_WELCOME_NEW);
				break;
			case PLAYING:
				setPlayerStartPlaying(uhcPlayer);

				if(!uhcPlayer.getHasBeenTeleportedToLocation()){
					List<UhcPlayer> onlinePlayingMembers = uhcPlayer.getTeam().getOnlinePlayingMembers();

					// Only player in team so create random spawn location.
					if(onlinePlayingMembers.size() <= 1){
						World world = gm.getMapLoader().getUhcWorld(World.Environment.NORMAL);
						int maxDistance = (int) gm.getMapLoader().getOverworldBorderApothem();
						uhcPlayer.getTeam().setStartingLocation(LocationUtils.getRandomSpawnLocation(world, maxDistance));
					}
					// Set spawn location at team mate.
					else{
						UhcPlayer teamMate = onlinePlayingMembers.get(0);
						if (teamMate == uhcPlayer){
							teamMate = onlinePlayingMembers.get(1);
						}

						try{
							uhcPlayer.getTeam().setStartingLocation(teamMate.getPlayer().getLocation());
						} catch (UhcPlayerNotOnlineException ingored) {
							// Should not happen
						}
					}

					// Teleport player
					UhcPlayer.teleport(player, LocationUtils.withSameDirection(uhcPlayer.getStartingLocation(), player));
					uhcPlayer.setHasBeenTeleportedToLocation(true);

					// Restore inventory if revived
					if (!uhcPlayer.getStoredItems().isEmpty()) {
						uhcPlayer.getStoredItems().forEach(item -> player.getWorld().dropItem(player.getLocation(), item.reanimate()));
						uhcPlayer.getStoredItems().clear();
					}

					// Call event
					Bukkit.getPluginManager().callEvent(new PlayerStartsPlayingEvent(uhcPlayer));
				}
				if (uhcPlayer.getOfflineZombieUuid() != null){
					Optional<Entity> zombie = Arrays.stream(player.getLocation().getChunk().getEntities())
							.filter(e -> e.getUniqueId().equals(uhcPlayer.getOfflineZombieUuid()))
							.findFirst();

					zombie.ifPresent(Entity::remove);
					uhcPlayer.setOfflineZombieUuid(null);
				}
				uhcPlayer.sendPrefixedMessage(Lang.PLAYERS_WELCOME_BACK_IN_GAME);
				break;
			case DEAD:
				setPlayerSpectateAtLobby(uhcPlayer);
				break;
		}
	}

	private void autoAssignPlayerToTeam(UhcPlayer uhcPlayer) {
		GameManager gm = GameManager.getGameManager();

		if (gm.getScenarioManager().isEnabled(Scenario.LOVE_AT_FIRST_SIGHT)){
			return;
		}

		for(UhcTeam team : listUhcTeams()){
			// Don't assign player to spectating team.
			if (team.isSpectating()){
				continue;
			}

			if(team != uhcPlayer.getTeam() && team.getMembers().size() < gm.getConfig().get(MainConfig.MAX_PLAYERS_PER_TEAM)){
				try {
					team.join(uhcPlayer);

					scoreboardHandler.updateTeamOnTab(team);
				} catch (UhcTeamException ignored) {
					// Should not happen
				}
				break;
			}
		}
	}

	public void setPlayerWaitsAtLobby(UhcPlayer uhcPlayer){
		uhcPlayer.setState(PlayerState.WAITING);
		GameManager gm = GameManager.getGameManager();
		Player player;
		try {
			player = uhcPlayer.getPlayer();
			UhcPlayer.teleport(player, gm.getMapLoader().getLobby().getLocation());
			clearPlayerInventory(player);
			player.setGameMode(GameMode.ADVENTURE);
			player.addPotionEffect(new PotionEffect(PotionEffectType.NIGHT_VISION, 99999999, 0), false);
			player.setHealth(20);
			player.setExhaustion(20);
			player.setFoodLevel(20);
			player.setExp(0);

			UhcItems.giveLobbyItemsTo(player);
		} catch (UhcPlayerNotOnlineException ignored) {
			// Do nothing because WAITING is a safe state
		}

	}

	public void setPlayerStartPlaying(UhcPlayer uhcPlayer){

		Player player;
		MainConfig cfg = GameManager.getGameManager().getConfig();

		if(!uhcPlayer.getHasBeenTeleportedToLocation()){
			uhcPlayer.setState(PlayerState.PLAYING);
			uhcPlayer.selectDefaultGlobalChat();

			try {
				player = uhcPlayer.getPlayer();
				// If the player is being revived, make sure to respawn them
				// (in case they haven't pressed the respawn button yet).
				// Otherwise, they can press the respawn button and will
				// spawn at their vanilla spawnpoint.
				if (player.isDead()) {
					player.spigot().respawn();
				}
				clearPlayerInventory(player);
				player.setFireTicks(0);

				final int extraHalfHearts = cfg.get(MainConfig.ENABLE_EXTRA_HALF_HEARTS) ? cfg.get(MainConfig.EXTRA_HALF_HEARTS) : 0;
				final double maxHealthBase = 20 + extraHalfHearts; // Base value for max health attribute (20 is the vanilla default for a player)
				// Set base value for max health attribute, restore player to full health
				VersionUtils.getVersionUtils().setPlayerMaxHealth(player, maxHealthBase);
				player.setHealth(maxHealthBase);

				for (PotionEffect effect : player.getActivePotionEffects()) {
					player.removePotionEffect(effect.getType());
				}
				for (PotionEffect effect : cfg.get(MainConfig.POTION_EFFECT_ON_START)) {
					player.addPotionEffect(effect);
				}

				player.setGameMode(GameMode.SURVIVAL);
				UhcItems.giveGameItemTo(player, GameItem.COMPASS_ITEM);
				//UhcItems.giveGameItemTo(player, GameItem.CUSTOM_CRAFT_BOOK);
				KitsManager.giveKitTo(player);
			} catch (UhcPlayerNotOnlineException ignored) {
				// Nothing done
			}
		}
	}

	private void clearPlayerInventory(Player player) {
		player.getInventory().clear();

		// PlayerInventory#clear does not clear armor contents on Spigot < 1.9
		if (!PaperLib.isVersion(9)) {
			player.getInventory().setArmorContents(new ItemStack[4]);
		}
	}

	public void setPlayerSpectateAtLobby(UhcPlayer uhcPlayer){
		GameManager gm = GameManager.getGameManager();

		uhcPlayer.setState(PlayerState.DEAD);
		//uhcPlayer.sendPrefixedMessage(Lang.PLAYERS_WELCOME_BACK_SPECTATING);

		if(gm.getConfig().get(MainConfig.SPECTATING_TELEPORT)) {
			uhcPlayer.sendPrefixedMessage(Lang.COMMAND_SPECTATING_HELP);
		}

		Player player;
		try {
			player = uhcPlayer.getPlayer();
			clearPlayerInventory(player);
			player.setGameMode(GameMode.SPECTATOR);

			player.getActivePotionEffects().forEach(effect -> player.removePotionEffect(effect.getType()));
			player.addPotionEffect(new PotionEffect(PotionEffectType.NIGHT_VISION, 999999, 0));

			/*if (gm.getGameState().equals(GameState.DEATHMATCH) && gm.getMapLoader().getArena().isUsed()) {
				player.teleport(gm.getMapLoader().getArena().getLocation());
			}else{
				Location loc = gm.getMapLoader().getUhcWorld(World.Environment.NORMAL).getBlockAt(0, 100, 0).getLocation();
				player.teleport(loc);
			}*/
		} catch (UhcPlayerNotOnlineException ingored) {
			// Do nothing because DEAD is a safe state
		}
	}

	public void setAllPlayersEndGame() {
		GameManager gm = GameManager.getGameManager();
		MainConfig cfg = gm.getConfig();

		List<UhcPlayer> winners = getWinners();

		if (!winners.isEmpty()) {
			UhcPlayer player1 = winners.get(0);
			if (winners.size() == 1) {
				gm.broadcastInfoMessage(Lang.PLAYERS_WON_SOLO.replace("%player%", player1.getDisplayName()));
			} else {
				gm.broadcastInfoMessage(Lang.PLAYERS_WON_TEAM.replace("%team%", player1.getTeam().getTeamName()));
			}
		}

		// send to bungee
		if(cfg.get(MainConfig.ENABLE_BUNGEE_SUPPORT) && cfg.get(MainConfig.TIME_BEFORE_SEND_BUNGEE_AFTER_END) >= 0){
			for(UhcPlayer player : getPlayersList()){
				Bukkit.getScheduler().runTaskAsynchronously(UhcCore.getPlugin(), new TimeBeforeSendBungeeTask(this, player, cfg.get(MainConfig.TIME_BEFORE_SEND_BUNGEE_AFTER_END)));
			}
		}

		UhcWinEvent event = new UhcWinEvent(new HashSet<>(winners));
		Bukkit.getServer().getPluginManager().callEvent(event);

		customEventHandler.handleWinEvent(new HashSet<>(winners));

		// When the game finished set all player states to DEAD
		getPlayersList().forEach(player -> player.setState(PlayerState.DEAD));
	}

	private List<UhcPlayer> getWinners(){
		// Note: There may be multiple winner teams in case the game ended early, e.g. because of deathmatch.force-end.
		// There may also be no winners if count-offline-teams-as-dead is false and all teams left the server.
		List<UhcPlayer> winners = new ArrayList<>();
		for(UhcPlayer player : getPlayersList()){
			try{
				Player connected = player.getPlayer();
				if(connected.isOnline() && player.getState().equals(PlayerState.PLAYING))
					winners.add(player);
			} catch (UhcPlayerNotOnlineException ingored) {
				// Player is offline, not adding to winner list
			}
		}
		return winners;
	}

	public List<UhcTeam> listUhcTeams(){
		List<UhcTeam> teams = new ArrayList<>();
		for(UhcPlayer player : getPlayersList()){
			UhcTeam team = player.getTeam();
			if(!teams.contains(team))
				teams.add(team);
		}
		return teams;
	}

	public void randomTeleportTeams() {
		GameManager gm = GameManager.getGameManager();
		World world = gm.getMapLoader().getUhcWorld(World.Environment.NORMAL);
		int maxDistance = gm.getConfig().get(MainConfig.BORDER_START_SIZE);

		// Fore solo players to join teams
		if(gm.getConfig().get(MainConfig.FORCE_ASSIGN_SOLO_PLAYER_TO_TEAM_WHEN_STARTING)){
			for(UhcPlayer uhcPlayer : getPlayersList()){
				// If player is spectating don't assign player.
				if (uhcPlayer.getState() == PlayerState.DEAD){
					continue;
				}

				if(uhcPlayer.getTeam().getMembers().size() == 1){
					autoAssignPlayerToTeam(uhcPlayer);
				}
			}
		}

		for(UhcTeam team : listUhcTeams()){
			Location newLoc = LocationUtils.getRandomSpawnLocation(world, maxDistance);
			team.setStartingLocation(newLoc);
		}

		Bukkit.getPluginManager().callEvent(new UhcPreTeleportEvent());

		long delayTeleportByTeam = 0;

		for(UhcTeam team : listUhcTeams()){

			if (team.isSpectating()){
				gm.getPlayerManager().setPlayerSpectateAtLobby(team.getLeader());
				continue;
			}

			for(UhcPlayer uhcPlayer : team.getMembers()){
				uhcPlayer.setState(PlayerState.PLAYING);
				try {
					clearPlayerInventory(uhcPlayer.getPlayer());
				} catch (UhcPlayerNotOnlineException ignored) {}
			}

			Bukkit.getScheduler().runTaskLater(UhcCore.getPlugin(), new TeleportPlayersTask(GameManager.getGameManager(), team), delayTeleportByTeam);
			LOGGER.info("Teleporting a team in "+delayTeleportByTeam+" ticks");
			delayTeleportByTeam += gm.getConfig().get(MainConfig.DELAY_BETWEEN_TEAM_TELEPORTS); // ticks
		}

		Bukkit.getScheduler().runTaskLater(UhcCore.getPlugin(), () -> GameManager.getGameManager().startWatchingEndOfGame(), delayTeleportByTeam + 20);

	}

	public void strikeLightning(UhcPlayer uhcPlayer) {
		Location loc;
		try{
			loc = uhcPlayer.getPlayer().getLocation();
		} catch (UhcPlayerNotOnlineException ignored) {
			loc = new Location(GameManager.getGameManager().getMapLoader().getUhcWorld(World.Environment.NORMAL),0, 200,0);
		}

		loc.getWorld().strikeLightningEffect(loc);

		// Extinguish fire
		if (loc.getBlock().getType() == Material.FIRE) {
			loc.getBlock().setType(Material.AIR);
		}
	}

	public void playSoundToAll(Sound sound) {
		playSoundToAll(sound, 1, 1);
	}

	public void playSoundToAll(Sound sound, float volume, float pitch) {
		for (UhcPlayer player : getPlayersList()) {
			playSoundTo(player, sound, volume, pitch);
		}
	}

	public void playSoundTo(UhcPlayer player, Sound sound) {
		playSoundTo(player, sound, 1, 1);
	}

	public void playSoundTo(UhcPlayer player, Sound sound, float volume, float pitch) {
		try {
			final Player p = player.getPlayer();
			p.playSound(p.getLocation(), sound, volume, pitch);
		} catch (UhcPlayerNotOnlineException ignored) {
			// No sound played
		}
	}

	public void checkIfRemainingPlayers(){
		final GameManager gm = GameManager.getGameManager();
		final MainConfig cfg = gm.getConfig();

		if (!cfg.get(MainConfig.ENABLE_VICTORY)) {
			return;
		}

		int playingTeams = 0;
		int playingTeamsOnline = 0;

		for(UhcTeam team : listUhcTeams()){

			int teamIsOnline = 0;
			int teamIsPlaying = 0;

			for(UhcPlayer player : team.getMembers()){
				if(player.getState().equals(PlayerState.PLAYING)){
					teamIsPlaying = 1;
					try{
						player.getPlayer();
						teamIsOnline = 1;
					} catch (UhcPlayerNotOnlineException ignored) {
						// Player isn't online
					}
				}
			}

			playingTeamsOnline += teamIsOnline;
			playingTeams += teamIsPlaying;
		}

		if (playingTeams <= 1) {
			gm.endGame();
		} else if (playingTeamsOnline <= 1 && cfg.get(MainConfig.COUNT_OFFLINE_TEAMS_AS_DEAD)) {
			gm.endGame();
		} else if (playingTeamsOnline <= 1 && cfg.get(MainConfig.END_GAME_WHEN_ALL_PLAYERS_HAVE_LEFT)) {
			gm.startEndGameTask();
		} else if (
			// deathmatch.force-end option
			gm.getGameState() == GameState.DEATHMATCH &&
			cfg.get(MainConfig.ENABLE_DEATHMATCH_FORCE_END) &&
			gm.getPvp() &&
			(lastDeathTime+(cfg.get(MainConfig.DEATHMATCH_FORCE_END_DELAY)*TimeUtils.SECOND)) < System.currentTimeMillis()
		) {
			gm.endGame();
		} else if (gm.getGameIsEnding()) {
			// Game went from ending to not ending (e.g. player re-joined)
			gm.stopEndGameTask();
		}
	}

	public void startWatchPlayerPlayingTask() {
		// Unfreeze players
		for (UhcPlayer uhcPlayer : getPlayersList()){
			uhcPlayer.releasePlayer();
			Bukkit.getPluginManager().callEvent(new PlayerStartsPlayingEvent(uhcPlayer));
		}

		Bukkit.getScheduler().runTaskLater(UhcCore.getPlugin(), new CheckRemainingPlayerTask(GameManager.getGameManager()) , 40);
	}

	public void sendPlayerToBungeeServer(Player player) {
		ByteArrayDataOutput out = ByteStreams.newDataOutput();
		out.writeUTF("Connect");
		out.writeUTF(GameManager.getGameManager().getConfig().get(MainConfig.SERVER_BUNGEE));
		player.sendMessage(Lang.PLAYERS_SEND_BUNGEE_NOW);
		player.sendPluginMessage(UhcCore.getPlugin(), "BungeeCord", out.toByteArray());
	}

	public void spawnOfflineZombieFor(Player player){
		UhcPlayer uhcPlayer = getUhcPlayer(player);

		Zombie zombie = (Zombie) player.getWorld().spawnEntity(player.getLocation(), EntityType.ZOMBIE);
		zombie.setCustomName(uhcPlayer.getDisplayName());
		zombie.setCustomNameVisible(true);
		// 1.8 doesn't have setAI method so use VersionUtils.
		VersionUtils.getVersionUtils().setEntityAI(zombie, false);
		zombie.setBaby(false);
		zombie.addPotionEffect(new PotionEffect(PotionEffectType.FIRE_RESISTANCE, 999999, 1, true, true));

		EntityEquipment equipment = zombie.getEquipment();
		equipment.setHelmet(VersionUtils.getVersionUtils().createPlayerSkull(player.getName(), player.getUniqueId()));
		equipment.setChestplate(player.getInventory().getChestplate());
		equipment.setLeggings(player.getInventory().getLeggings());
		equipment.setBoots(player.getInventory().getBoots());
		equipment.setItemInHand(player.getItemInHand());

		uhcPlayer.getStoredItems().clear();
		for (ItemStack item : player.getInventory().getContents()){
			if (item != null){
				uhcPlayer.getStoredItems().add(ItemStackSnapshot.of(item));
			}
		}
		// PlayerInventory#getContents does not include armor contents on Spigot < 1.9
		if (!PaperLib.isVersion(9)) {
			for (ItemStack item : player.getInventory().getArmorContents()) {
				// PlayerInventory#getArmorContents maps null items to AIR on Spigot < 1.9
				if (item.getType() != Material.AIR) {
					uhcPlayer.getStoredItems().add(ItemStackSnapshot.of(item));
				}
			}
		}

		uhcPlayer.setOfflineZombieUuid(zombie.getUniqueId());
	}

	public UhcPlayer revivePlayer(UUID uuid, String name, boolean spawnWithItems){
		UhcPlayer uhcPlayer;

		try{
			uhcPlayer = getUhcPlayer(uuid);
		} catch (UhcPlayerDoesNotExistException ignored) {
			uhcPlayer = newUhcPlayer(uuid, name);
		}

		revivePlayer(uhcPlayer, spawnWithItems);
		return uhcPlayer;
	}

	public void revivePlayer(UhcPlayer uhcPlayer, boolean spawnWithItems){
		uhcPlayer.setHasBeenTeleportedToLocation(false);
		uhcPlayer.setState(PlayerState.PLAYING);

		// If not respawn with items, clear stored items.
		if (!spawnWithItems){
			uhcPlayer.getStoredItems().clear();
		}

		try{
			playerJoinsTheGame(uhcPlayer.getPlayer());
		} catch (UhcPlayerNotOnlineException ignored) {
			// Player gets revived next time they attempt to join.
			// We need to support reviving offline players, because they might not be permitted
			// to be online (before they are revived) due to the can-spectate-after-death setting.
		}
	}

}
