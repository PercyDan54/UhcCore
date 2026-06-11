package com.gmail.val59000mc.game;

import com.gmail.val59000mc.UhcCore;
import com.gmail.val59000mc.commands.*;
import com.gmail.val59000mc.configuration.Dependencies;
import com.gmail.val59000mc.configuration.MainConfig;
import com.gmail.val59000mc.customitems.CraftsManager;
import com.gmail.val59000mc.customitems.KitsManager;
import com.gmail.val59000mc.events.UhcGameStateChangedEvent;
import com.gmail.val59000mc.events.UhcStartingEvent;
import com.gmail.val59000mc.events.UhcStartedEvent;
import com.gmail.val59000mc.game.handlers.*;
import com.gmail.val59000mc.languages.Lang;
import com.gmail.val59000mc.listeners.*;
import com.gmail.val59000mc.maploader.MapLoader;
import com.gmail.val59000mc.players.PlayerManager;
import com.gmail.val59000mc.players.TeamManager;
import com.gmail.val59000mc.players.UhcPlayer;
import com.gmail.val59000mc.scenarios.ScenarioManager;
import com.gmail.val59000mc.scoreboard.ScoreboardLayout;
import com.gmail.val59000mc.scoreboard.ScoreboardManager;
import com.gmail.val59000mc.tasks.ElapsedTimeTask;
import com.gmail.val59000mc.tasks.EnablePVPTask;
import com.gmail.val59000mc.tasks.EnablePermanentDayTask;
import com.gmail.val59000mc.tasks.EndTask;
import com.gmail.val59000mc.tasks.EpisodeMarkersTask;
import com.gmail.val59000mc.tasks.FinalHealTask;
import com.gmail.val59000mc.tasks.PreStartTask;
import com.gmail.val59000mc.tasks.StopRestartTask;
import com.gmail.val59000mc.tasks.TimeBeforeDeathmatchTask;
import com.gmail.val59000mc.utils.*;
import com.gmail.val59000mc.versionadapters.adapters.ChunkyPreGenerator;

import org.apache.commons.lang.Validate;
import org.bukkit.*;
import org.bukkit.World.Environment;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.PluginCommand;
import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.event.Listener;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.logging.Level;
import java.util.logging.Logger;

public class GameManager{

	private static final Logger LOGGER = Logger.getLogger(GameManager.class.getCanonicalName());

	// GameManager Instance
	private static GameManager gameManager;

	// Managers
	private final PlayerManager playerManager;
	private final TeamManager teamManager;
	private final ScoreboardManager scoreboardManager;
	private final ScoreboardLayout scoreboardLayout;
	private final ScenarioManager scenarioManager;
	private final MainConfig config;
	private final MapLoader mapLoader;

	// Handlers
	private final CustomEventHandler customEventHandler;
	private final ScoreboardHandler scoreboardHandler;
	private final DeathmatchHandler deathmatchHandler;
	private final PlayerDeathHandler playerDeathHandler;

	private GameState gameState;
	private boolean pvp;
	private boolean gameIsEnding;
	private int episodeNumber;
	private long remainingTime;
	private long elapsedTime;

	static{
		gameManager = null;
	}

	public GameManager() {
		gameManager = this;
		config = new MainConfig();
		scoreboardLayout = new ScoreboardLayout();
		customEventHandler = new CustomEventHandler(config);
		scoreboardHandler = new ScoreboardHandler(gameManager, config, scoreboardLayout);
		playerManager = new PlayerManager(customEventHandler, scoreboardHandler);
		teamManager = new TeamManager(playerManager, scoreboardHandler);
		scoreboardManager = new ScoreboardManager(scoreboardHandler, scoreboardLayout);
		scenarioManager = new ScenarioManager();
		mapLoader = new MapLoader(config);

		deathmatchHandler = new DeathmatchHandler(this, config, playerManager, mapLoader);
		playerDeathHandler = new PlayerDeathHandler(this, scenarioManager, playerManager, config, customEventHandler);

		episodeNumber = 0;
		elapsedTime = 0;
	}

	public static GameManager getGameManager(){
		return gameManager;
	}

	public PlayerManager getPlayerManager(){
		return playerManager;
	}

	public TeamManager getTeamManager() {
		return teamManager;
	}

	public ScoreboardManager getScoreboardManager() {
		return scoreboardManager;
	}

	public ScenarioManager getScenarioManager() {
		return scenarioManager;
	}

	public MainConfig getConfig() {
		return config;
	}

	public MapLoader getMapLoader(){
		return mapLoader;
	}

	public synchronized GameState getGameState(){
		return gameState;
	}

	public boolean getGameIsEnding() {
		return gameIsEnding;
	}

	public synchronized long getRemainingTime(){
		return remainingTime;
	}

	public synchronized long getElapsedTime(){
		return elapsedTime;
	}

	public int getEpisodeNumber(){
		return episodeNumber;
	}

	public void setEpisodeNumber(int episodeNumber){
		this.episodeNumber = episodeNumber;
	}

	public long getTimeUntilNextEpisode(){
		return episodeNumber * config.get(MainConfig.EPISODE_MARKERS_DELAY) - getElapsedTime();
	}

	public String getFormattedRemainingTime() {
		return TimeUtils.getFormattedTime(getRemainingTime());
	}

	public synchronized void setRemainingTime(long time){
		remainingTime = time;
	}

	public synchronized void setElapsedTime(long time){
		elapsedTime = time;
	}

	public boolean getPvp() {
		return pvp;
	}

	public void setPvp(boolean state) {
		pvp = state;
	}

	public void setGameState(GameState gameState){
		Validate.notNull(gameState);

		if (this.gameState == gameState){
			return; // Don't change the game state when the same.
		}

		GameState oldGameState = this.gameState;
		this.gameState = gameState;

		// Call UhcGameStateChangedEvent
		Bukkit.getPluginManager().callEvent(new UhcGameStateChangedEvent(oldGameState, gameState));
	}

	public void loadNewGame() {
		setGameState(GameState.LOADING);

		registerListeners();
		registerCommands();

		if(config.get(MainConfig.ENABLE_BUNGEE_SUPPORT)) {
			UhcCore.getPlugin().getServer().getMessenger().registerOutgoingPluginChannel(UhcCore.getPlugin(), "BungeeCord");
		}

		boolean debug = config.get(MainConfig.DEBUG);
		mapLoader.loadWorlds(debug);

		if(config.get(MainConfig.ENABLE_PRE_GENERATE_WORLD) && !debug) {
			final ChunkyPreGenerator chunkyPreGenerator = UhcCore.getVersionAdapterLoader().getVersionAdapter(ChunkyPreGenerator.class);
			if (chunkyPreGenerator != null) {
				final List<CompletableFuture<Void>> preGenerationTasks = new ArrayList<>();

				final World overworld = mapLoader.getUhcWorld(Environment.NORMAL);
				final int overworldSize = config.get(MainConfig.BORDER_START_SIZE);
				preGenerationTasks.add(chunkyPreGenerator.preGenerateChunks(overworld.getName(), overworldSize));

				if (config.get(MainConfig.ENABLE_NETHER)) {
					final World nether = mapLoader.getUhcWorld(Environment.NETHER);
					final double netherScale = config.get(MainConfig.NETHER_SCALE);
					final int netherSize = (int) (overworldSize / netherScale);
					preGenerationTasks.add(chunkyPreGenerator.preGenerateChunks(nether.getName(), netherSize));
				}

				CompletableFuture.allOf(preGenerationTasks.stream().toArray(CompletableFuture[]::new))
					.thenRun(() -> Bukkit.getScheduler().runTask(UhcCore.getPlugin(), () -> {
						startWaitingPlayers();
					}));
			} else {
				// Calls startWaitingPlayers when done
				mapLoader.generateChunks(Environment.NORMAL);
			}
		} else {
			startWaitingPlayers();
		}
	}

	public void startWaitingPlayers() {
		mapLoader.prepareWorlds();

		setPvp(false);
		setGameState(GameState.WAITING);

		// Enable default scenarios
		scenarioManager.loadDefaultScenarios(config);

		LOGGER.info("Players are now allowed to join");
		Bukkit.getScheduler().scheduleSyncDelayedTask(UhcCore.getPlugin(), new PreStartTask(this),0);
	}

	public void startGame() {
		setGameState(GameState.STARTING);

		// scenario voting
		if (config.get(MainConfig.ENABLE_SCENARIO_VOTING)) {
			scenarioManager.electScenarios();
		}

		Bukkit.getPluginManager().callEvent(new UhcStartingEvent());

		broadcastInfoMessage(Lang.GAME_STARTING);
		broadcastInfoMessage(Lang.GAME_PLEASE_WAIT_TELEPORTING);
		playerManager.randomTeleportTeams();
		gameIsEnding = false;
	}

	public void startWatchingEndOfGame(){
		setGameState(GameState.PLAYING);

		mapLoader.setWorldsStartGame();

		playerManager.startWatchPlayerPlayingTask();
		Bukkit.getScheduler().runTaskAsynchronously(UhcCore.getPlugin(), new ElapsedTimeTask(this, customEventHandler));
		//Bukkit.getScheduler().runTaskAsynchronously(UhcCore.getPlugin(), new EnablePVPTask(this));

		if (config.get(MainConfig.ENABLE_EPISODE_MARKERS)){
			Bukkit.getScheduler().runTaskAsynchronously(UhcCore.getPlugin(), new EpisodeMarkersTask(this));
		}

		if(config.get(MainConfig.ENABLE_DEATHMATCH)){
			Bukkit.getScheduler().runTaskAsynchronously(UhcCore.getPlugin(), new TimeBeforeDeathmatchTask(this, deathmatchHandler));
		}

		if (config.get(MainConfig.ENABLE_DAY_NIGHT_CYCLE) && config.get(MainConfig.TIME_BEFORE_PERMANENT_DAY) != -1){
			Bukkit.getScheduler().scheduleSyncDelayedTask(UhcCore.getPlugin(), new EnablePermanentDayTask(mapLoader), config.get(MainConfig.TIME_BEFORE_PERMANENT_DAY)*20);
		}

		if (config.get(MainConfig.ENABLE_FINAL_HEAL)){
			Bukkit.getScheduler().scheduleSyncDelayedTask(UhcCore.getPlugin(), new FinalHealTask(this, playerManager), config.get(MainConfig.FINAL_HEAL_DELAY)*20);
		}

		Bukkit.getPluginManager().callEvent(new UhcStartedEvent());
	}

	public void broadcastMessage(String message) {
		if (message.isEmpty()) return;
		for (UhcPlayer player : playerManager.getPlayersList()) {
			player.sendMessage(message);
		}
	}

	public void broadcastInfoMessage(String message) {
		if (message.isEmpty()) return;
		broadcastMessage(Lang.DISPLAY_MESSAGE_PREFIX+message);
	}

	public void loadConfig(){
		new Lang();

		try{
			File configFile = FileUtils.getResourceFile(UhcCore.getPlugin(), "config.yml");
			config.setConfigurationFile(configFile);
			config.load();
		}catch (InvalidConfigurationException | IOException ex){
			LOGGER.log(Level.WARNING, "Unable to load config.yml", ex);
			return;
		}

		// Configure logging level
		try {
			final Level level = Level.parse(config.get(MainConfig.LOGGING_LEVEL));
			UhcCore.getPlugin().getForwardingLogger().setLevel(level);
		} catch (IllegalArgumentException e) {
			LOGGER.log(Level.WARNING, "Failed to parse logging level", e);
		}

		// Dependencies
		Dependencies.loadWorldEdit();
		Dependencies.loadVault();
		Dependencies.loadProtocolLib();

		// Map loader
		mapLoader.loadWorldUuids();

		// Config
		config.preLoad();

		// Set remaining time
		if(config.get(MainConfig.ENABLE_DEATHMATCH)){
			setRemainingTime(config.get(MainConfig.DEATHMATCH_DELAY));
		}

		// Load kits
		KitsManager.loadKits();

		// Load crafts
		CraftsManager.loadBannedCrafts();
		CraftsManager.loadCrafts();
		if (config.get(MainConfig.ENABLE_GOLDEN_HEADS)){
			CraftsManager.registerGoldenHeadCraft();
		}
	}

	private void registerListeners() {
		// Registers Listeners
		List<Listener> listeners = new ArrayList<>();
		listeners.add(new PlayerConnectionListener(this, playerManager, playerDeathHandler, scoreboardHandler));
		listeners.add(new PlayerChatListener(playerManager, config));
		listeners.add(new PlayerDamageListener(this));
		listeners.add(new ItemsListener(gameManager, config, playerManager, teamManager, scenarioManager, scoreboardHandler));
		listeners.add(new TeleportListener());
		listeners.add(new PlayerDeathListener(playerDeathHandler));
		//listeners.add(new EntityDeathListener(playerManager, config, playerDeathHandler));
		//listeners.add(new CraftListener());
		listeners.add(new PingListener());
		listeners.add(new BlockListener(this));
		//listeners.add(new WorldListener());
		listeners.add(new PlayerMovementListener(playerManager));
		listeners.add(new PlayerHungerGainListener(playerManager));
		for(Listener listener : listeners){
			Bukkit.getServer().getPluginManager().registerEvents(listener, UhcCore.getPlugin());
		}
	}

	private void registerCommands(){
		// Registers CommandExecutor
		registerCommand("uhccore", new UhcCommandExecutor(this));
		registerCommand("chat", new ChatCommandExecutor(playerManager));
		registerCommand("teleport", new TeleportCommandExecutor(this));
		registerCommand("start", new StartCommandExecutor());
		registerCommand("scenarios", new ScenarioCommandExecutor(scenarioManager));
		registerCommand("teaminventory", new TeamInventoryCommandExecutor(playerManager, scenarioManager));
		registerCommand("hub", new HubCommandExecutor(this));
		registerCommand("iteminfo", new ItemInfoCommandExecutor());
		registerCommand("revive", new ReviveCommandExecutor(this));
		registerCommand("seed", new SeedCommandExecutor(mapLoader));
		registerCommand("crafts", new CustomCraftsCommandExecutor());
		registerCommand("top", new TopCommandExecutor(playerManager));
		registerCommand("spectate", new SpectateCommandExecutor(this, scoreboardHandler));
		registerCommand("upload", new UploadCommandExecutor());
		registerCommand("deathmatch", new DeathmatchCommandExecutor(this, deathmatchHandler));
		registerCommand("team", new TeamCommandExecutor(this));
		registerCommand("heal", new HealCommandExecutor(playerManager));
	}

	private void registerCommand(String commandName, CommandExecutor executor){
		PluginCommand command = UhcCore.getPlugin().getCommand(commandName);
		if (command == null){
			LOGGER.warning("Failed to register " + commandName + " command!");
			return;
		}

		command.setExecutor(executor);
	}

	public void endGame() {
		if(gameState.equals(GameState.PLAYING) || gameState.equals(GameState.DEATHMATCH)){
			setGameState(GameState.ENDED);
			pvp = false;
			gameIsEnding = true;
			broadcastInfoMessage(Lang.GAME_FINISHED);
			playerManager.playSoundToAll(UniversalSound.ENDERDRAGON_GROWL.getSound(), 1, 2);
			playerManager.setAllPlayersEndGame();
			Bukkit.getScheduler().scheduleSyncDelayedTask(UhcCore.getPlugin(), new StopRestartTask(),20);
		}
	}

	public void startEndGameTask() {
		if(!gameIsEnding && (gameState.equals(GameState.DEATHMATCH) || gameState.equals(GameState.PLAYING))){
			gameIsEnding = true;
			EndTask.start();
		}
	}

	public void stopEndGameTask(){
		if(gameIsEnding && (gameState.equals(GameState.DEATHMATCH) || gameState.equals(GameState.PLAYING))){
			gameIsEnding = false;
			EndTask.stop();
		}
	}

}
