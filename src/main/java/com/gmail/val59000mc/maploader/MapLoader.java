package com.gmail.val59000mc.maploader;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.UUID;
import java.util.logging.Level;
import java.util.logging.Logger;

import javax.annotation.Nullable;

import org.apache.commons.lang.Validate;
import org.bukkit.Bukkit;
import org.bukkit.Chunk;
import org.bukkit.Difficulty;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.World.Environment;
import org.bukkit.WorldBorder;
import org.bukkit.WorldCreator;
import org.bukkit.WorldType;
import org.bukkit.configuration.InvalidConfigurationException;

import com.gmail.val59000mc.UhcCore;
import com.gmail.val59000mc.configuration.MainConfig;
import com.gmail.val59000mc.configuration.YamlFile;
import com.gmail.val59000mc.game.GameManager;
import com.gmail.val59000mc.schematics.DeathmatchArena;
import com.gmail.val59000mc.schematics.Lobby;
import com.gmail.val59000mc.schematics.UndergroundNether;
import com.gmail.val59000mc.tasks.ChunkLoaderTask;
import com.gmail.val59000mc.tasks.WorldBorderTask;
import com.gmail.val59000mc.utils.FileUtils;
import com.gmail.val59000mc.utils.VersionUtils;
import com.gmail.val59000mc.versionadapters.adapters.SetBiomeProviderAdapter;
import com.pieterdebot.biomemapping.Biome;
import com.pieterdebot.biomemapping.BiomeMappingAPI;

import io.papermc.lib.PaperLib;

public class MapLoader {

	private static final Logger LOGGER = Logger.getLogger(MapLoader.class.getCanonicalName());

	public final static String DO_DAYLIGHT_CYCLE = "doDaylightCycle";
	public final static String DO_MOB_SPAWNING = "doMobSpawning";
	public final static String NATURAL_REGENERATION = "naturalRegeneration";
	public final static String LOCATOR_BAR = "locatorBar";
	public final static String ANNOUNCE_ADVANCEMENTS = "announceAdvancements";
	public final static String COMMAND_BLOCK_OUTPUT = "commandBlockOutput";
	public final static String LOG_ADMIN_COMMANDS = "logAdminCommands";

	private final MainConfig config;
	private final Map<Environment, String> worldUuids;

	private Lobby lobby;
	private DeathmatchArena arena;

	private long mapSeed;
	private String mapName;

	public MapLoader(MainConfig config){
		this.config = config;
		worldUuids = new HashMap<>();
		mapSeed = -1;
		mapName = null;
	}

	public Lobby getLobby() {
		return lobby;
	}

	public DeathmatchArena getArena() {
		return arena;
	}

	public double getOverworldBorderApothem(){
		World overworld = GameManager.getGameManager().getMapLoader().getUhcWorld(World.Environment.NORMAL);
		return overworld.getWorldBorder().getSize()/2;
	}

	private void removeOceansUsingBiomeMapping() throws Exception {
		final BiomeMappingAPI biomeMapping = new BiomeMappingAPI();
		Biome replacementBiome = Biome.PLAINS;

		for (Biome biome : Biome.values()) {
			if (biome.isOcean() && biomeMapping.biomeSupported(biome)) {
				biomeMapping.replaceBiomes(biome, replacementBiome);

				replacementBiome = replacementBiome == Biome.PLAINS ? Biome.FOREST : Biome.PLAINS;
			}
		}
	}

	private void removeOceans() {
		try {
			if (UhcCore.getNmsAdapter().isPresent()) {
				LOGGER.fine("Removing oceans using NMS adapter");
				UhcCore.getNmsAdapter().get().removeOceans();
			} else if (PaperLib.getMinecraftVersion() < 18) {
				LOGGER.fine("Removing oceans using BiomeMapping");
				removeOceansUsingBiomeMapping();
			} else {
				LOGGER.warning("The 'replace-ocean-biomes' setting is not supported on this Minecraft version");
			}
		} catch (Exception e) {
			LOGGER.log(Level.WARNING, "Unable to remove ocean biomes", e);
		}
	}

	public void loadWorlds(boolean debug) {
		if (config.get(MainConfig.REPLACE_OCEAN_BIOMES)) {
			removeOceans();
		}

		deleteOldPlayersFiles();

		if(debug){
			loadOldWorld(Environment.NORMAL);
			if (config.get(MainConfig.ENABLE_NETHER)) {
				loadOldWorld(Environment.NETHER);
			}
			if (config.get(MainConfig.ENABLE_THE_END)) {
				loadOldWorld(Environment.THE_END);
			}
		}else{
			deleteLastWorld(Environment.NORMAL);
			deleteLastWorld(Environment.NETHER);
			deleteLastWorld(Environment.THE_END);

			createNewWorld(Environment.NORMAL);
			if (config.get(MainConfig.ENABLE_NETHER)) {
				createNewWorld(Environment.NETHER);
			}
			if (config.get(MainConfig.ENABLE_THE_END)) {
				createNewWorld(Environment.THE_END);
			}
		}
	}

	public void deleteLastWorld(Environment env){
		String uuid = worldUuids.get(env);

		if(uuid == null || uuid.equals("null")){
			LOGGER.info("No world to delete");
		}else{
			final File worldDir = new File(Bukkit.getWorldContainer(), uuid);
			if(worldDir.exists()){
				LOGGER.info("Deleting last world : "+uuid);
				FileUtils.deleteFile(worldDir);
			}else{
				LOGGER.info("World "+uuid+" can't be removed, directory not found");
			}
		}
	}

	public void createNewWorld(Environment env){
		String worldName = UUID.randomUUID().toString();
		if (UhcCore.getPlugin().getConfig().getBoolean("permanent-world-names", false)){
			worldName = "uhc-"+env.name().toLowerCase();
		}

		LOGGER.info("Creating new world : "+worldName);

		GameManager gm = GameManager.getGameManager();

		WorldCreator wc = new WorldCreator(worldName);
		wc.generateStructures(config.get(MainConfig.GENERATE_STRUCTURES));
		if (env == Environment.NORMAL) {
			final String cgOverworld = config.get(MainConfig.CHUNK_GENERATOR_OVERWORLD);
			if (!cgOverworld.isEmpty()) {
				wc.generator(cgOverworld);
			}
			final String bpOverworld = config.get(MainConfig.BIOME_PROVIDER_OVERWORLD);
			if (!bpOverworld.isEmpty()) {
				UhcCore.getVersionAdapterLoader().getVersionAdapter(SetBiomeProviderAdapter.class)
					.setBiomeProvider(wc, bpOverworld);
			}
		} else if (env == Environment.NETHER) {
			final String cgNether = config.get(MainConfig.CHUNK_GENERATOR_NETHER);
			if (!cgNether.isEmpty()) {
				wc.generator(cgNether);
			}
			final String bpNether = config.get(MainConfig.BIOME_PROVIDER_NETHER);
			if (!bpNether.isEmpty()) {
				UhcCore.getVersionAdapterLoader().getVersionAdapter(SetBiomeProviderAdapter.class)
					.setBiomeProvider(wc, bpNether);
			}
		} else if (env == Environment.THE_END) {
			final String cgEnd = config.get(MainConfig.CHUNK_GENERATOR_END);
			if (!cgEnd.isEmpty()) {
				wc.generator(cgEnd);
			}
			final String bpEnd = config.get(MainConfig.BIOME_PROVIDER_END);
			if (!bpEnd.isEmpty()) {
				UhcCore.getVersionAdapterLoader().getVersionAdapter(SetBiomeProviderAdapter.class)
					.setBiomeProvider(wc, bpEnd);
			}
		}
		wc.environment(env);

		List<Long> seeds = gm.getConfig().get(MainConfig.SEEDS);
		List<String> worlds = gm.getConfig().get(MainConfig.WORLDS);
		if(gm.getConfig().get(MainConfig.PICK_RANDOM_SEED_FROM_LIST) && !seeds.isEmpty()){
			if (mapSeed == -1) {
				Random r = new Random();
				mapSeed = seeds.get(r.nextInt(seeds.size()));
				LOGGER.info("Picking random seed from list : "+mapSeed);
			}
			wc.seed(mapSeed);
		}else if(gm.getConfig().get(MainConfig.PICK_RANDOM_WORLD_FROM_LIST) && !worlds.isEmpty()){
			if (mapName == null) {
				Random r = new Random();
				mapName = worlds.get(r.nextInt(worlds.size()));
			}

			String copyWorld = mapName;
			if (env != Environment.NORMAL){
				copyWorld = copyWorld + "_" + env.name().toLowerCase();
			}

			try {
				copyWorld(copyWorld, worldName);
			} catch (IOException e) {
				LOGGER.log(Level.WARNING, "Unable to copy world " + copyWorld, e);
			}
		}

		worldUuids.put(env, worldName);

		YamlFile storage;

		try{
			storage = FileUtils.saveResourceIfNotAvailable(UhcCore.getPlugin(), "storage.yml");
		}catch (IOException | InvalidConfigurationException ex){
			LOGGER.log(Level.WARNING, "Unable to load storage.yml", ex);
			return;
		}

		storage.set("worlds." + env.name().toLowerCase(), worldName);
		try {
			storage.save();
		}catch (IOException ex){
			LOGGER.log(Level.WARNING, "Unable to save storage.yml", ex);
		}

		wc.type(WorldType.NORMAL);
		World createdWorld = Bukkit.getServer().createWorld(wc);
		LOGGER.info("World seed: " + createdWorld.getSeed());
	}

	public void loadOldWorld(Environment env){
		String uuid = worldUuids.get(env);

		if(uuid == null || uuid.equals("null")){
			LOGGER.info("No world to load, defaulting to default behavior");
			this.createNewWorld(env);
		}else{
			final File worldDir = new File(Bukkit.getWorldContainer(), uuid);
			if(worldDir.exists()){
				// Loading existing world
				LOGGER.info("Loading existing world : " + uuid);
				World loadedWorld = Bukkit.getServer().createWorld(new WorldCreator(uuid).environment(env));
				LOGGER.info("World seed: " + loadedWorld.getSeed());
			}else{
				this.createNewWorld(env);
			}
		}
	}

	public void loadWorldUuids(){
		YamlFile storage;

		try{
			storage = FileUtils.saveResourceIfNotAvailable(UhcCore.getPlugin(), "storage.yml");
		}catch (IOException | InvalidConfigurationException ex){
			LOGGER.log(Level.WARNING, "Unable to load storage.yml", ex);
			return;
		}

		worldUuids.put(Environment.NORMAL, storage.getString("worlds.normal"));
		worldUuids.put(Environment.NETHER, storage.getString("worlds.nether"));
		worldUuids.put(Environment.THE_END, storage.getString("worlds.the_end"));
	}

	/**
	 * Used to obtain the UHC world uuid matching the given environment.
	 * @param environment The environment of the world uuid you want to obtain.
	 * @return Returns the UHC world uuid matching the environment or null if it doesn't exist.
	 */
	@Nullable
	public String getUhcWorldUuid(Environment environment){
		Validate.notNull(environment);
		return worldUuids.get(environment);
	}

	/**
	 * Used to obtain the UHC world matching the given environment.
	 * @param environment The environment of the world you want to obtain.
	 * @return Returns the UHC world matching the environment or null if it doesn't exist.
	 */
	@Nullable
	public World getUhcWorld(Environment environment){
		Validate.notNull(environment);

		String worldUuid = worldUuids.get(environment);
		if (worldUuid == null){
			return null;
		}

		return Bukkit.getWorld(worldUuid);
	}

	public void setWorldsStartGame() {
		World overworld = getUhcWorld(Environment.NORMAL);
		VersionUtils.getVersionUtils().setGameRuleValue(overworld, DO_MOB_SPAWNING, true);

		if(config.get(MainConfig.ENABLE_DAY_NIGHT_CYCLE)) {
			VersionUtils.getVersionUtils().setGameRuleValue(overworld, DO_DAYLIGHT_CYCLE, true);
			overworld.setTime(0);
		}

		if (!config.get(MainConfig.LOBBY_IN_DEFAULT_WORLD)) {
			lobby.destroyBoundingBox();
		}

		if(config.get(MainConfig.BORDER_IS_MOVING)){
			int borderEndApothem = config.get(MainConfig.BORDER_END_SIZE);
			int borderEndSideLength = 2 * borderEndApothem;
			int timeToShrink = config.get(MainConfig.BORDER_TIME_TO_SHRINK);
			int timeBeforeShrink = config.get(MainConfig.BORDER_TIME_BEFORE_SHRINK);

			Bukkit.getScheduler().runTask(UhcCore.getPlugin(), new WorldBorderTask(timeBeforeShrink, borderEndSideLength, timeToShrink));
		}
	}

	public void prepareWorlds() {
		Difficulty difficulty = config.get(MainConfig.GAME_DIFFICULTY);
		boolean healthRegen = config.get(MainConfig.ENABLE_HEALTH_REGEN);
		boolean locatorBar = config.get(MainConfig.ENABLE_LOCATOR_BAR);
		boolean announceAdvancements = config.get(MainConfig.ANNOUNCE_ADVANCEMENTS);
		int borderStartApothem = config.get(MainConfig.BORDER_START_SIZE);
		int borderStartSideLength = 2 * borderStartApothem;

		World overworld = getUhcWorld(Environment.NORMAL);
		prepareWorld(overworld, difficulty, healthRegen, locatorBar, announceAdvancements, borderStartSideLength);

		VersionUtils.getVersionUtils().setGameRuleValue(overworld, DO_DAYLIGHT_CYCLE, false);
		VersionUtils.getVersionUtils().setGameRuleValue(overworld, DO_MOB_SPAWNING, false);

		overworld.setTime(6000);
		overworld.setWeatherDuration(999999999);

		if (config.get(MainConfig.ENABLE_NETHER)){
			World nether = getUhcWorld(Environment.NETHER);
			prepareWorld(nether, difficulty, healthRegen, locatorBar, announceAdvancements, borderStartSideLength / config.get(MainConfig.NETHER_SCALE));
		}

		if (config.get(MainConfig.ENABLE_THE_END)){
			World theEnd = getUhcWorld(Environment.THE_END);
			prepareWorld(theEnd, difficulty, healthRegen, locatorBar, announceAdvancements, borderStartSideLength);
		}

		if (config.get(MainConfig.LOBBY_IN_DEFAULT_WORLD)){
			final World defaultWorld = Bukkit.getWorlds().get(0);
			final Location spawnLocation = config.get(MainConfig.USE_DEFAULT_WORLD_SPAWN_FOR_LOBBY)
				? defaultWorld.getSpawnLocation().clone().add(0.5, 0, 0.5)
				: new Location(defaultWorld, 0.5, 100, 0.5);
			lobby = new Lobby(spawnLocation);
			lobby.build();
		}else {
			lobby = new Lobby(new Location(overworld, 0.5, 200, 0.5));
			lobby.build();
		}

		arena = new DeathmatchArena(new Location(overworld, 10000, config.get(MainConfig.ARENA_PASTE_AT_Y), 10000));
		arena.build();

		if (config.get(MainConfig.ENABLE_UNDERGROUND_NETHER)) {
			UndergroundNether undergoundNether = new UndergroundNether();
			undergoundNether.build(config, getUhcWorld(Environment.NORMAL));
		}
	}

	private void prepareWorld(World world, Difficulty difficulty, boolean healthRegen, boolean locatorBar, boolean announceAdvancements, double borderSideLength) {
		world.save();
		if (!healthRegen){
			VersionUtils.getVersionUtils().setGameRuleValue(world, NATURAL_REGENERATION, false);
		}
		if (!locatorBar && PaperLib.isVersion(21, 6)) {
			VersionUtils.getVersionUtils().setGameRuleValue(world, LOCATOR_BAR, false);
		}
		if (!announceAdvancements && PaperLib.getMinecraftVersion() >= 12){
			VersionUtils.getVersionUtils().setGameRuleValue(world, ANNOUNCE_ADVANCEMENTS, false);
		}
		VersionUtils.getVersionUtils().setGameRuleValue(world, COMMAND_BLOCK_OUTPUT, false);
		VersionUtils.getVersionUtils().setGameRuleValue(world, LOG_ADMIN_COMMANDS, false);
		world.setDifficulty(difficulty);

		setWorldBorder(world, 0, 0, borderSideLength);
	}

	public void setWorldBorder(World world, int x, int z, double sideLength) {
		/*WorldBorder worldborder = world.getWorldBorder();
		worldborder.setCenter(x, z);
		worldborder.setSize(sideLength);*/
	}

	private void copyWorld(String sourceName, String destinationName) throws IOException {
		final Path worldsDir = Bukkit.getWorldContainer().toPath().toAbsolutePath().normalize();
		final Path sourceDir = worldsDir.resolve(sourceName);
		final Path destinationDir = worldsDir.resolve(destinationName);

		if (!Files.isDirectory(sourceDir)) {
			LOGGER.warning("Unable to copy world " + sourceName + " as it does not exist");
			return;
		}

		// In case the source directory is a symbolic link
		final Path realSourceDir = sourceDir.toRealPath();

		LOGGER.info("Copying " + sourceName + " to " + destinationName);
		Files.walkFileTree(realSourceDir, new CopyWorldFileVisitor(realSourceDir, destinationDir));
	}

	private void deleteOldPlayersFiles() {
		if (Bukkit.getServer().getWorlds().isEmpty()) {
			return;
		}

		final File mainWorldFolder = Bukkit.getWorlds().get(0).getWorldFolder();

		// Deleting old players files
		final File playerdata = new File(mainWorldFolder, "playerdata");
		if(playerdata.exists() && playerdata.isDirectory()){
			for(File playerFile : playerdata.listFiles()){
				playerFile.delete();
			}
		}

		// Deleting old players stats
		final File stats = new File(mainWorldFolder, "stats");
		if(stats.exists() && stats.isDirectory()){
			for(File statFile : stats.listFiles()){
				statFile.delete();
			}
		}

		// Deleting old players advancements
		final File advancements = new File(mainWorldFolder, "advancements");
		if(advancements.exists() && advancements.isDirectory()){
			for(File advancementFile : advancements.listFiles()){
				advancementFile.delete();
			}
		}
	}

	public void generateChunks(Environment env){
		World world = getUhcWorld(env);
		int size = config.get(MainConfig.BORDER_START_SIZE);
		double netherScale = config.get(MainConfig.NETHER_SCALE);

		if(env == Environment.NETHER){
			size = (int) (size / netherScale);
		}

		int restEveryNumOfChunks = config.get(MainConfig.REST_EVERY_NUM_OF_CHUNKS);
		int restDuration = config.get(MainConfig.REST_DURATION);

		ChunkLoaderTask chunkLoaderTask = new ChunkLoaderTask(world, size, restEveryNumOfChunks, restDuration) {
			@Override
			public void onDoneLoadingWorld() {
				LOGGER.info("Environment "+env.toString()+" 100% loaded");
				if(env.equals(Environment.NORMAL) && config.get(MainConfig.ENABLE_NETHER)) {
					generateChunks(Environment.NETHER);
				}else {
					GameManager.getGameManager().startWaitingPlayers();
				}
			}

			@Override
			public void onDoneLoadingChunk(Chunk chunk) {}
		};

		chunkLoaderTask.printSettings();

		if (PaperLib.isPaper() && PaperLib.getMinecraftVersion() >= 13){
			Bukkit.getScheduler().runTaskAsynchronously(UhcCore.getPlugin(), chunkLoaderTask);
		}else {
			Bukkit.getScheduler().runTask(UhcCore.getPlugin(), chunkLoaderTask);
		}
	}

}
