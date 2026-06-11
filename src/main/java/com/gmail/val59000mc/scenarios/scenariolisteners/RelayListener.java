package com.gmail.val59000mc.scenarios.scenariolisteners;

import com.gmail.val59000mc.UhcCore;
import com.gmail.val59000mc.configuration.MainConfig;
import com.gmail.val59000mc.events.UhcStartedEvent;
import com.gmail.val59000mc.exceptions.UhcPlayerNotOnlineException;
import com.gmail.val59000mc.players.PlayerState;
import com.gmail.val59000mc.players.UhcPlayer;
import com.gmail.val59000mc.scenarios.Option;
import com.gmail.val59000mc.scenarios.ScenarioListener;
import com.gmail.val59000mc.utils.TimeUtils;
import com.gmail.val59000mc.utils.UniversalSound;
import org.bukkit.*;
import org.bukkit.advancement.Advancement;
import org.bukkit.advancement.AdvancementProgress;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.boss.BarColor;
import org.bukkit.boss.BarStyle;
import org.bukkit.boss.BossBar;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.player.PlayerAdvancementDoneEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.potion.PotionEffect;

import java.util.ArrayList;
import java.util.Collections;
import java.util.stream.Collectors;

public class RelayListener extends ScenarioListener {
	@Option
	private int delay = 60;

	@Option(key = "shuffle-players")
	private boolean shufflePlayers;

	@Option(key = "allow-spectate")
	private boolean allowSpectate;

	private int taskId;
	private int broadcastTimer;

	private ArrayList<UhcPlayer> shuffledPlayers;
	private int currentPlayerIndex = -1;
	private UhcPlayer currentPlayer;
	private BossBar countdownBossBar;

	@EventHandler
	public void onGameStart(UhcStartedEvent e) {
		ArrayList<UhcPlayer> players = new ArrayList<>(e.getPlayerManager().getAllPlayingPlayers());

		if (shufflePlayers) {
			Collections.shuffle(players);
		}

		shuffledPlayers = players;
		currentPlayerIndex = 0;
		currentPlayer = players.get(0);
		countdownBossBar = Bukkit.createBossBar("§6§l接力倒计时", BarColor.BLUE, BarStyle.SOLID);
		getGameManager().broadcastInfoMessage("[接力] 玩家顺序: " + shuffledPlayers.stream().map(UhcPlayer::getName).collect(Collectors.joining(", ")));

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

	@EventHandler
	public void onEnable() {
	}

	@EventHandler
	public void onDisable() {
		Bukkit.getScheduler().cancelTask(taskId);
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

		// 1. 优先使用 isOnline() 检查双方在线状态
		if (!currentPlayer.isOnline() || !targetUhc.isOnline()) {
			if (broadcastTimer <= 0) {
				getGameManager().broadcastInfoMessage("§c[接力] 有玩家掉线！接力已暂停，等待重连...");
				broadcastTimer = 10;
			}
			return false;
		}

		// 2. 双方在线，安全获取 Player 对象
		Player sourcePlayer;
		Player targetPlayer;
		try {
			sourcePlayer = currentPlayer.getPlayer();
			targetPlayer = targetUhc.getPlayer();
		} catch (UhcPlayerNotOnlineException ex) {
			return false;
		}

		targetUhc.setState(PlayerState.PLAYING);
		copyPlayerData(sourcePlayer, targetPlayer);
		setSpectateState(currentPlayer);

		currentPlayerIndex = nextIndex;
		currentPlayer = targetUhc;

		getGameManager().broadcastInfoMessage("§a[接力] 控制权已转移给: §b" + targetPlayer.getName());
		targetUhc.sendMessage("§a[接力] 到你了！");
		getPlayerManager().playSoundTo(targetUhc, Sound.BLOCK_NOTE_BLOCK_CHIME);
		return true;
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

	private void copyPlayerData(Player source, Player target) {
		PlayerInventory sourceInv = source.getInventory();
		PlayerInventory targetInv = target.getInventory();
		source.closeInventory();

		AttributeInstance sourceMaxHealth = source.getAttribute(Attribute.GENERIC_MAX_HEALTH);
		AttributeInstance targetMaxHealth = target.getAttribute(Attribute.GENERIC_MAX_HEALTH);

		Entity vehicle = source.getVehicle();

		if (source.isDead() || source.getHealth() <= 0) {
			target.teleport(target.getWorld().getSpawnLocation());

			target.setGameMode(GameMode.SURVIVAL);
			target.setHealth(20);
			target.setFoodLevel(20);
			target.setSaturation(5);
			targetInv.clear();
			target.setLevel(0);
			target.setExp(0);

			for (PotionEffect effect : target.getActivePotionEffects()) {
				target.removePotionEffect(effect.getType());
			}

			source.setGameMode(GameMode.SPECTATOR);
		} else {
			if (vehicle != null) {
				vehicle.removePassenger(source);
			}

			target.teleport(source.getLocation());
			target.setFallDistance(source.getFallDistance());
			target.setVelocity(source.getVelocity());

			source.setGameMode(GameMode.SPECTATOR);
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

			target.setLevel(source.getLevel());
			target.setExp(source.getExp());

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

			if (timeLeft <= 0) {
				if (listener.tryRelay()) {
					timeLeft = listener.delay;
				}
			}
			else {
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
				listener.countdownBossBar.setTitle("§6§l接力倒计时: §e§l" + timeLeft + "秒 §7| §f当前: §b" + listener.currentPlayer.getName());
				listener.countdownBossBar.setColor(BarColor.BLUE);

				if (timeLeft <= 30) {
					listener.countdownBossBar.setColor(BarColor.YELLOW);
					if (timeLeft <= 10) {
						listener.countdownBossBar.setColor(BarColor.RED);
						listener.getPlayerManager().playSoundToAll(UniversalSound.CLICK.getSound());
					}
				}
			}

			listener.taskId = Bukkit.getScheduler().scheduleSyncDelayedTask(UhcCore.getPlugin(),this, TimeUtils.SECOND_TICKS);
		}
	}
}
