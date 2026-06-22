package com.gmail.val59000mc.scenarios.scenariolisteners;

import com.gmail.val59000mc.UhcCore;
import com.gmail.val59000mc.configuration.MainConfig;
import com.gmail.val59000mc.events.UhcStartedEvent;
import com.gmail.val59000mc.exceptions.UhcPlayerDoesNotExistException;
import com.gmail.val59000mc.exceptions.UhcPlayerNotOnlineException;
import com.gmail.val59000mc.game.GameState;
import com.gmail.val59000mc.players.PlayerState;
import com.gmail.val59000mc.players.UhcPlayer;
import com.gmail.val59000mc.scenarios.Option;
import com.gmail.val59000mc.scenarios.ScenarioListener;
import com.gmail.val59000mc.utils.TimeUtils;
import com.gmail.val59000mc.utils.UniversalSound;
import com.gmail.val59000mc.versionadapters.adapters.RespawnAnchorAdapter;
import io.papermc.lib.PaperLib;
import org.bukkit.*;
import org.bukkit.advancement.Advancement;
import org.bukkit.advancement.AdvancementProgress;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.block.Block;
import org.bukkit.boss.BarColor;
import org.bukkit.boss.BarStyle;
import org.bukkit.boss.BossBar;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.block.Action;
import org.bukkit.event.player.*;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.potion.PotionEffect;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.OptionalInt;
import java.util.logging.Logger;
import java.util.stream.Collectors;

public class RelayListener extends ScenarioListener {
	@Option
	private int delay = 60;

	@Option(key = "offline-timeout")
	private int offlineTimeout = 60;

	@Option(key = "shuffle-players")
	private boolean shufflePlayers;

	@Option(key = "allow-spectate")
	private boolean allowSpectate;

	private int taskId;
	private int broadcastTimer;
	private int offlineWaitTimer = 0;

	private ArrayList<UhcPlayer> shuffledPlayers;
	private int currentPlayerIndex = -1;
	private UhcPlayer currentPlayer;
	private BossBar countdownBossBar;
	private YamlConfiguration disconnectedPlayerData = null;
	private Location sharedRespawnLocation = null;
	private Location respawnBlockLocation = null;
	private static final Logger LOGGER = Logger.getLogger(RelayListener.class.getCanonicalName());

	@EventHandler
	public void onGameStart(UhcStartedEvent e) {
		init();
	}

	private void init() {
		ArrayList<UhcPlayer> players = new ArrayList<>(getPlayerManager().getAllPlayingPlayers());

		if (shufflePlayers) {
			Collections.shuffle(players);
		}

		shuffledPlayers = players;
		currentPlayerIndex = 0;
		currentPlayer = players.get(0);
		broadcastPlayerOrder();
		countdownBossBar = Bukkit.createBossBar("§6§l接力倒计时", BarColor.BLUE, BarStyle.SOLID);

		for (int i = 0; i < players.size(); i++) {
			UhcPlayer uhcPlayer = players.get(i);

			if (!uhcPlayer.isOnline()) continue;

			try {
				Player p = uhcPlayer.getPlayer();
				if (i != currentPlayerIndex) {
					p.getInventory().clear();
					p.setGameMode(GameMode.SPECTATOR);
					setSpectateState(uhcPlayer);
				}
			} catch (UhcPlayerNotOnlineException ignored) {}
		}

		taskId = Bukkit.getScheduler().scheduleSyncDelayedTask(UhcCore.getPlugin(), new RelayTask(this), TimeUtils.SECOND_TICKS);
	}

	private void broadcastPlayerOrder() {
		getGameManager().broadcastInfoMessage(getPlayerOrderMessage());
	}

	private String getPlayerOrderMessage() {
		if (shuffledPlayers == null) {
			return "[接力] 游戏还未开始!";
		}

		return "[接力] 玩家顺序: " + shuffledPlayers.stream().map(UhcPlayer::getDisplayName).collect(Collectors.joining(", "));
	}

	@Override
	public void onEnable() {
		UhcCore.getPlugin().getCommand("relay").setExecutor(new RelayCommand(this));

		if (getGameManager().getGameState().ordinal() < GameState.PLAYING.ordinal())
			return;

		init();
	}

	@Override
	public void onDisable() {
		Bukkit.getScheduler().cancelTask(taskId);
		UhcCore.getPlugin().getCommand("relay").setExecutor(null);

		if (shuffledPlayers != null)
			shuffledPlayers.clear();

		shuffledPlayers = null;
	}

	@EventHandler(priority = EventPriority.LOW)
	public void onPlayerJoin(PlayerJoinEvent e) {
		Bukkit.getScheduler().runTaskLater(UhcCore.getPlugin(), () -> onPlayerJoin(e.getPlayer()), 3);
	}

	private void onPlayerJoin(Player player) {
		if (countdownBossBar != null) {
			countdownBossBar.removePlayer(player);
		}

		if (shuffledPlayers != null) {
			UhcPlayer uhcPlayer = getPlayerManager().getOrCreateUhcPlayer(player);

			if (!shuffledPlayers.contains(uhcPlayer)) {
				LOGGER.info("Added player " + player.getName());
				shuffledPlayers.add(uhcPlayer);
			}

			broadcastPlayerOrder();
			setSpectateState(uhcPlayer);
		}
	}

	@EventHandler(priority = EventPriority.LOW)
	public void onPlayerQuit(PlayerQuitEvent e) {
		Player player = e.getPlayer();

		if (countdownBossBar != null) {
			countdownBossBar.removePlayer(player);
		}
		if (currentPlayer != null && player.getUniqueId() == currentPlayer.getUuid()) {
			disconnectedPlayerData = dumpGameState(player);
		}
	}

	@EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
	public void onPlayerInteractBed(PlayerInteractEvent event) {
		if (event.getAction() != Action.RIGHT_CLICK_BLOCK) return;

		Block clicked = event.getClickedBlock();
		if (clicked == null) return;

		String blockName = clicked.getType().name();
		boolean isBedOrAnchor = blockName.endsWith("_BED") || blockName.equals("RESPAWN_ANCHOR");

		if (isBedOrAnchor) {
			Player player = event.getPlayer();

			try {
				UhcPlayer uhcPlayer = getPlayerManager().getUhcPlayer(player.getUniqueId());
				if (uhcPlayer.getState() != PlayerState.PLAYING) return;

				Bukkit.getScheduler().runTaskLater(UhcCore.getPlugin(), () -> {
					Location newLoc = player.getBedSpawnLocation();

					if (newLoc != null && newLoc.getWorld().equals(clicked.getWorld()) && newLoc.distance(clicked.getLocation()) < 5) {
						sharedRespawnLocation = newLoc;
						respawnBlockLocation = clicked.getLocation();
						LOGGER.info("[Relay] Spawn point updated, Loc: " + newLoc + " Block: " + respawnBlockLocation);
					}
				}, 2L);

			} catch (UhcPlayerDoesNotExistException ignored) {}
		}
	}

	@EventHandler(priority = EventPriority.LOW)
	public void onPlayerRespawn(PlayerRespawnEvent event) {
		if (event.isBedSpawn() || UhcCore.getVersionAdapterLoader().getVersionAdapter(RespawnAnchorAdapter.class).isAnchorSpawn(event))
			return;

		Player player = event.getPlayer();

		try {
			UhcPlayer uhcPlayer = getPlayerManager().getUhcPlayer(event.getPlayer().getUniqueId());
			if (uhcPlayer.getState() == PlayerState.PLAYING) {
				if (respawnBlockLocation != null && sharedRespawnLocation != null) {
					Block block = respawnBlockLocation.getBlock();
					String blockName = block.getType().name();
					int anchorCharges = UhcCore.getVersionAdapterLoader().getVersionAdapter(RespawnAnchorAdapter.class).getRespawnAnchorCharges(block);

					if (blockName.endsWith("_BED") || anchorCharges > 0) {
						event.setRespawnLocation(sharedRespawnLocation);
						LOGGER.info("[Relay] Player " + player + " respawned at override " + sharedRespawnLocation);
					} else {
						sharedRespawnLocation = null;
						respawnBlockLocation = null;
						Location loc = getDefaultRespawnLocation();
						if (loc != null)
							event.setRespawnLocation(loc);
					}
				}
				else {
					Location loc = getDefaultRespawnLocation();
					if (loc != null)
						event.setRespawnLocation(loc);
				}
			}
		} catch (UhcPlayerDoesNotExistException e) { }
	}

	private Location getDefaultRespawnLocation() {
		World world = getGameManager().getMapLoader().getUhcWorld(World.Environment.NORMAL);
		if (world != null)
			return world.getSpawnLocation();

		return null;
	}

	@EventHandler
	public void onPlayerAdvancement(PlayerAdvancementDoneEvent event) {
		Advancement advancement = event.getAdvancement();
		String key = advancement.getKey().getKey();

		if (key.equals("end/kill_dragon")) {
			for (Player target : Bukkit.getOnlinePlayers()) {
				if (target.equals(event.getPlayer())) continue;

				AdvancementProgress progress = target.getAdvancementProgress(advancement);
				if (!progress.isDone()) {
					for (String criteria : progress.getRemainingCriteria()) {
						progress.awardCriteria(criteria);
					}
				}
			}
		}
	}

	protected boolean tryRelay() {
		if (shuffledPlayers.size() <= 1) return false;

		int nextIndex = (currentPlayerIndex + 1) % shuffledPlayers.size();
		UhcPlayer targetUhc = shuffledPlayers.get(nextIndex);

		if (targetUhc.isOnline()) {
			offlineWaitTimer = 0;
			executeRelay(currentPlayer, targetUhc, nextIndex);
			return true;
		}

		offlineWaitTimer++;

		if (offlineWaitTimer < offlineTimeout) {
			if (broadcastTimer <= 0) {
				getGameManager().broadcastInfoMessage("§c[接力] 有玩家掉线！接力已暂停，等待重连...");
				broadcastTimer = 15;
			}
			return false;
		}

		// 超时了！触发强制跳过逻辑
		shuffledPlayers.remove(nextIndex);
		getGameManager().broadcastMessage("§4[接力] 等待超时！强制寻找下一个在线玩家...");
		broadcastPlayerOrder();
		offlineWaitTimer = 0;
		return forceSkipAndRelay();
	}

	private boolean forceSkipAndRelay() {
		int nextIndex = currentPlayerIndex;
		UhcPlayer targetUhc = null;

		for (int i = 0; i < shuffledPlayers.size() - 1; i++) {
			nextIndex = (nextIndex + 1) % shuffledPlayers.size();
			UhcPlayer temp = shuffledPlayers.get(nextIndex);
			if (temp.isOnline()) {
				targetUhc = temp;
				break;
			}
		}

		if (targetUhc == null) {
			getGameManager().broadcastMessage("§c[接力] 场上没有其他在线玩家，接力继续暂停。");
			return false;
		}

		executeRelay(currentPlayer, targetUhc, nextIndex);
		return true;
	}

	private void executeRelay(UhcPlayer sourceUhc, UhcPlayer targetUhc, int newIndex) {
		Player targetPlayer;
		try {
			targetPlayer = targetUhc.getPlayer();
		} catch (UhcPlayerNotOnlineException e) { return; }

		currentPlayerIndex = newIndex;
		currentPlayer = targetUhc;

		if (!sourceUhc.isOnline()) {
			if (disconnectedPlayerData != null) {
				getGameManager().broadcastInfoMessage("[接力] 前任玩家 " + sourceUhc.getDisplayName() + "已离线，正在读取存档...");
				loadGameState(disconnectedPlayerData);
			} else {
				resetPlayerAsNewSpawn(targetPlayer);
				getGameManager().broadcastInfoMessage("§e[接力] 前任玩家 " + sourceUhc.getDisplayName() + "§e 已离线且没有存档，" + targetPlayer.getDisplayName() + " 从出生点重新出发！");
			}
		} else {
			Player sourcePlayer;
			try {
				sourcePlayer = sourceUhc.getPlayer();
				copyPlayerData(sourcePlayer, targetPlayer);
				setSpectateState(sourceUhc);
			} catch (UhcPlayerNotOnlineException ignored) {}
		}

		disconnectedPlayerData = null;
		targetUhc.setState(PlayerState.PLAYING);

		getGameManager().broadcastInfoMessage("§a[接力] 控制权已转移给: " + targetPlayer.getDisplayName());
		//targetUhc.sendMessage("§a[接力] 到你了！");
		getPlayerManager().playSoundTo(targetUhc, Sound.BLOCK_NOTE_BLOCK_PLING);

		int nextIndex = (currentPlayerIndex + 1) % shuffledPlayers.size();
		UhcPlayer nextUhc = shuffledPlayers.get(nextIndex);

		if (nextUhc.isOnline()) {
			getPlayerManager().playSoundTo(nextUhc, Sound.BLOCK_NOTE_BLOCK_HARP);
			nextUhc.sendPrefixedMessage("[接力] 注意，下一个轮到你！");
		}
	}

	private void setSpectateState(UhcPlayer uhcPlayer) {
		getPlayerManager().setPlayerSpectateAtLobby(uhcPlayer);
		if (!allowSpectate) {
			final World defaultWorld = Bukkit.getWorlds().get(0);
			final Location spawnLocation = getConfiguration().get(MainConfig.USE_DEFAULT_WORLD_SPAWN_FOR_LOBBY)
				? defaultWorld.getSpawnLocation().clone().add(0.5, 0, 0.5)
				: new Location(defaultWorld, 0.5, 100, 0.5);
			try {
				Player player = uhcPlayer.getPlayer();
				player.teleport(spawnLocation);
				player.setGameMode(GameMode.ADVENTURE);
			} catch (UhcPlayerNotOnlineException ignored) {}
		}
	}

	private void resetPlayerAsNewSpawn(Player target) {
		if (sharedRespawnLocation != null)
			target.teleport(sharedRespawnLocation);
		else {
			Location loc = getDefaultRespawnLocation();

			if (loc != null)
				target.teleport(loc);
		}

		target.setGameMode(GameMode.SURVIVAL);
		target.setHealth(20.0);
		target.setFoodLevel(20);
		target.setSaturation(5.0f);
		target.setLevel(0);
		target.setExp(0);
		target.getInventory().clear();
		target.getInventory().setHeldItemSlot(0);

		for (PotionEffect effect : target.getActivePotionEffects()) {
			target.removePotionEffect(effect.getType());
		}
	}

	public YamlConfiguration dumpGameState(Player p) {
		YamlConfiguration config = new YamlConfiguration();

		config.set("saved-time", System.currentTimeMillis());
		config.set("last-player-name", p.getName());

		if (sharedRespawnLocation != null) {
			config.set("respawn-location", sharedRespawnLocation);
		} else {
			config.set("respawn-location", null);
		}

		if (respawnBlockLocation != null) {
			config.set("respawn-block", respawnBlockLocation);
		} else {
			config.set("respawn-block", null);
		}

		config.set("stats.health", p.getHealth());
		config.set("stats.max-health", p.getAttribute(Attribute.GENERIC_MAX_HEALTH).getBaseValue());
		config.set("stats.food", p.getFoodLevel());
		config.set("stats.saturation", p.getSaturation());
		config.set("stats.level", p.getLevel());
		config.set("stats.exp", p.getExp());
		config.set("location", p.getLocation());

		config.set("inventory.contents", p.getInventory().getContents());
		config.set("inventory.held-slot", p.getInventory().getHeldItemSlot());
		return config;
	}

	public void saveGameState(Player p) throws IOException {
		try {
			YamlConfiguration config = dumpGameState(p);

			if (config == null) {
				LOGGER.severe("Failed to save game state: dumpGameState returned null");
				return;
			}

			File file = new File(UhcCore.getPlugin().getDataFolder(), "relay_save.yml");
			config.save(file);
		} catch (IOException e) {
			LOGGER.severe("Failed to save game state: IO Exception");
			e.printStackTrace();
			throw e;
		}
	}

	public void loadGameState(YamlConfiguration config) {
		if (currentPlayer == null) return;

		try {
			Player p = currentPlayer.getPlayer();

			if (config.contains("stats.max-health")) {
				p.getAttribute(Attribute.GENERIC_MAX_HEALTH).setBaseValue(config.getDouble("stats.max-health"));
			}

			if (config.contains("respawn-location")) {
				sharedRespawnLocation = config.getLocation("respawn-location");
			}

			if (config.contains("respawn-block")) {
				respawnBlockLocation = config.getLocation("respawn-block");
			}

			p.setHealth(config.getDouble("stats.health", 20.0));
			p.setFoodLevel(config.getInt("stats.food", 20));
			p.setSaturation((float) config.getDouble("stats.saturation", 5.0));
			p.setLevel(config.getInt("stats.level", 0));
			p.setExp((float) config.getDouble("stats.exp", 0.0));

			Location loc = config.getLocation("location");

			if (loc != null) {
				p.teleport(loc);
			}

			if (config.contains("inventory.contents")) {
				List<?> list = config.getList("inventory.contents");
				if (list != null) {
					ItemStack[] contents = list.toArray(new ItemStack[0]);
					p.getInventory().setContents(contents);
				}
			}
			p.getInventory().setHeldItemSlot(config.getInt("inventory.held-slot", 0));
			p.updateInventory();

			getGameManager().broadcastInfoMessage("§a[接力] 游戏进度读取成功！已赋予给: " + p.getDisplayName());

		} catch (UhcPlayerNotOnlineException e) {
			LOGGER.warning("Failed to load state: Player not online!");
		}
	}

	private void copyPlayerData(Player source, Player target) {
		PlayerInventory sourceInv = source.getInventory();
		PlayerInventory targetInv = target.getInventory();
		source.closeInventory();

		AttributeInstance sourceMaxHealth = source.getAttribute(Attribute.GENERIC_MAX_HEALTH);
		AttributeInstance targetMaxHealth = target.getAttribute(Attribute.GENERIC_MAX_HEALTH);

		Entity vehicle = source.getVehicle();

		if (source.isDead() || source.getHealth() <= 0) {
			resetPlayerAsNewSpawn(target);

			source.setGameMode(GameMode.SPECTATOR);
		} else {
			if (vehicle != null) {
				vehicle.removePassenger(source);
			}

			target.teleport(source.getLocation());
			target.setFallDistance(source.getFallDistance());
			target.setVelocity(source.getVelocity());

			target.setGameMode(GameMode.SURVIVAL);

			if (vehicle != null && !vehicle.isDead()) {
				vehicle.addPassenger(target);
			}

			if (sourceMaxHealth != null && targetMaxHealth != null) {
				targetMaxHealth.setBaseValue(sourceMaxHealth.getBaseValue());
			}

			target.setHealth(source.getHealth());
			target.setFoodLevel(source.getFoodLevel());
			target.setSaturation(source.getSaturation());
			target.setFireTicks(source.getFireTicks());
			target.setBedSpawnLocation(sharedRespawnLocation);
			source.setGameMode(GameMode.SPECTATOR);
			source.setBedSpawnLocation(null);

			target.setLevel(source.getLevel());
			target.setExp(source.getExp());

			source.setLevel(0);
			source.setExp(0);
			source.setHealth(20);
			source.setFoodLevel(20);
			source.setSaturation(5);

			for (PotionEffect effect : target.getActivePotionEffects()) {
				target.removePotionEffect(effect.getType());
			}
			for (PotionEffect effect : source.getActivePotionEffects()) {
				target.addPotionEffect(effect);
			}

			ItemStack[] clonedContents = cloneItemArray(sourceInv.getContents());
			targetInv.setContents(clonedContents);
			targetInv.setHeldItemSlot(sourceInv.getHeldItemSlot());

			sourceInv.clear();
			target.updateInventory();
		}
	}

	private static ItemStack[] cloneItemArray(ItemStack[] original) {
		ItemStack[] clone = new ItemStack[original.length];
		for (int i = 0; i < original.length; i++) {
			if (original[i] != null && !original[i].getType().isAir()) {
				clone[i] = original[i].clone();
			} else {
				clone[i] = null;
			}
		}
		return clone;
	}

	public static class RelayTask implements Runnable {
		private int timeLeft;
		private final RelayListener listener;

		public RelayTask(RelayListener listener) {
			this.listener = listener;
			timeLeft = listener.delay;
		}

		@Override
		public void run() {
			timeLeft--;

			if (listener.broadcastTimer > 0) {
				listener.broadcastTimer--;
			}

			if (timeLeft >= 0) {
				for (UhcPlayer player : listener.shuffledPlayers) {
					if (player.isOnline()) {
						try {
							Player p = player.getPlayer();
							if (!listener.countdownBossBar.getPlayers().contains(p)) {
								listener.countdownBossBar.addPlayer(p);
							}
						} catch (UhcPlayerNotOnlineException e) {
						}
					}
				}

				listener.countdownBossBar.setProgress((double) timeLeft / listener.delay);
				listener.countdownBossBar.setTitle("§6§l接力倒计时: §e§l" + timeLeft + "秒 §7| §f当前: §b" + listener.currentPlayer.getDisplayName());
				listener.countdownBossBar.setColor(BarColor.BLUE);

				if (timeLeft <= 30) {
					listener.countdownBossBar.setColor(BarColor.YELLOW);
					if (timeLeft <= 15) {
						listener.countdownBossBar.setColor(BarColor.RED);
						if (timeLeft <= 10) {
							listener.getPlayerManager().playSoundToAll(UniversalSound.CLICK.getSound());
						}
					}
				}
			} else {
				if (listener.tryRelay()) {
					timeLeft = listener.delay;
				}
			}

			listener.taskId = Bukkit.getScheduler().scheduleSyncDelayedTask(UhcCore.getPlugin(),this, TimeUtils.SECOND_TICKS);
		}
	}

	public class RelayCommand implements CommandExecutor {

		private final RelayListener relayListener;

		public RelayCommand(RelayListener relayListener) {
			this.relayListener = relayListener;
		}

		@Override
		public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
			if (relayListener == null) {
				return true;
			}

			if (args.length == 0) {
				sender.sendMessage("§e用法: /relay <order|save|load>");
				return true;
			}

			if (args[0].equalsIgnoreCase("order")) {
				if (!(sender instanceof Player)){
					sender.sendMessage("Only players can use this command!");
					return true;
				}

				Player player = (Player)sender;
				UhcPlayer uhcPlayer = getPlayerManager().getUhcPlayer(player);

				uhcPlayer.sendPrefixedMessage(getPlayerOrderMessage());
				return true;
			}

			if (!sender.hasPermission("uhc.relay.admin")) {
				return true;
			}

			if (args[0].equalsIgnoreCase("save")) {
				sender.sendMessage("§7正在保存当前进度...");
				try {
					Player p = currentPlayer.getPlayer();
					saveGameState(p);
					sender.sendMessage("§a保存完成");
				} catch (UhcPlayerNotOnlineException e) {
					sender.sendMessage(ChatColor.RED + "保存失败：玩家" + currentPlayer.getDisplayName() + ChatColor.RED + "不在线！");
				} catch (IOException e) {
					sender.sendMessage(ChatColor.RED + "保存失败：IO 异常 " + e);
				}
				return true;
			}

			if (args[0].equalsIgnoreCase("load")) {
				sender.sendMessage("§7正在读取进度...");
				File file = new File(UhcCore.getPlugin().getDataFolder(), "relay_save.yml");

				if (!file.exists()) {
					sender.sendMessage("§c[接力] 找不到存档文件！");
					return true;
				}

				relayListener.loadGameState(YamlConfiguration.loadConfiguration(file));
				return true;
			}

			sender.sendMessage("§c未知的参数。用法: /relay <order|save|load>");
			return true;
		}
	}
}
