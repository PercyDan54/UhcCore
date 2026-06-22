package com.gmail.val59000mc.players;

import com.gmail.val59000mc.configuration.MainConfig;
import com.gmail.val59000mc.customitems.Kit;
import com.gmail.val59000mc.events.UhcPlayerStateChangedEvent;
import com.gmail.val59000mc.exceptions.UhcPlayerNotOnlineException;
import com.gmail.val59000mc.game.GameManager;
import com.gmail.val59000mc.languages.Lang;
import com.gmail.val59000mc.scenarios.Scenario;
import com.gmail.val59000mc.utils.LocationUtils;
import com.gmail.val59000mc.utils.SpigotUtils;
import com.gmail.val59000mc.utils.TimeUtils;
import com.gmail.val59000mc.utils.UniversalMaterial;
import com.gmail.val59000mc.utils.snapshot.Snapshot;

import io.papermc.lib.PaperLib;
import org.apache.commons.lang.Validate;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Location;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.scoreboard.Scoreboard;

import java.util.*;

public class UhcPlayer {
	private final UUID uuid;
	private final String name;

	private UhcTeam team;
	private PlayerState state;
	private boolean globalChat;
	private int kills;
	private Kit kit;
	private boolean hasBeenTeleportedToLocation;
	private final Map<String,Integer> craftedItems;
	private final Set<UhcTeam> teamInvites;
	private final Set<Scenario> scenarioVotes;
	// Note: Using Snapshot here to avoid storing live ItemStack objects,
	// which may become invalid depending on the server version/implementation.
	// Relevant issue: https://github.com/PaperMC/Paper/issues/11520
	private final List<Snapshot<ItemStack>> storedItems;

	private String nickName;
	private Scoreboard scoreboard;
	private Location freezeLocation;
	private UUID offlineZombieUuid;
	private UhcPlayer compassPlayingCurrentPlayer;
	private long compassPlayingLastUpdate;
	private int browsingPage;

	public UhcPlayer(UUID uuid, String name){
		this.uuid = uuid;
		this.name = name;

		setTeam(new UhcTeam());
		setState(PlayerState.WAITING);
		globalChat = false;
		kills = 0;
		kit = null;
		hasBeenTeleportedToLocation = false;
		craftedItems = new HashMap<>();
		teamInvites = new HashSet<>();
		scenarioVotes = new HashSet<>();
		storedItems = new ArrayList<>();
		offlineZombieUuid = null;

		compassPlayingCurrentPlayer = this;
		browsingPage = 0;
	}

	public Player getPlayer() throws UhcPlayerNotOnlineException {
		Player player = Bukkit.getPlayer(uuid);
		if(player != null)
			return player;
		throw new UhcPlayerNotOnlineException(name);
	}

	public Boolean isOnline(){
		Player player = Bukkit.getPlayer(uuid);
		return player != null;
	}

	/**
	 * Used to get the player name.
	 * @return Returns the player name, when they are nicked the nick-name will be returned.
	 */
	public String getName(){
		if (nickName != null){
			return nickName;
		}
		return name;
	}

	/**
	 * Used to get the players real name.
	 * @return Returns the players real name, even when they are nicked.
	 */
	public String getRealName(){
		return name;
	}

	/**
	 * Use ProtocolUtils.setPlayerNickName(); instead!
	 * @param nickName The player nickname. (Make sure its not over 16 characters long!)
	 */
	public void setNickName(String nickName){
		if (nickName != null){
			Validate.isTrue(nickName.length() <= 16, "Nickname is too long! (Max 16 characters)");
		}
		this.nickName = nickName;
	}

	public boolean hasNickName(){
		return nickName != null;
	}

	/**
	 * Used to get the player display-name.
	 * @return Returns the player team color (when enabled) followed by their name.
	 */
	public String getDisplayName(){
		if (GameManager.getGameManager().getConfig().get(MainConfig.TEAM_COLORS)){
			return team.getColor() + getName() + ChatColor.RESET;
		}
		return getName();
	}

	public UUID getUuid() {
		return uuid;
	}

	public Scoreboard getScoreboard() {
		return scoreboard;
	}

	public void setScoreboard(Scoreboard scoreboard) {
		this.scoreboard = scoreboard;
	}

	public synchronized UhcTeam getTeam(){
		return team;
	}

	public synchronized void setTeam(UhcTeam newTeam) {
		final UhcTeam oldTeam = this.team;
		if (oldTeam != null) {
			// Free team number for old team if you are last player to leave
			if (oldTeam.isSolo()) {
				GameManager.getGameManager().getTeamManager().freeTeamNumber(oldTeam);
			}
			oldTeam.getMembers().remove(this);
		}
		newTeam.getMembers().add(this);
		this.team = newTeam;
	}

	public PlayerState getState() {
		return state;
	}

	public boolean isWaiting(){
		return state == PlayerState.WAITING;
	}

	public boolean isPlaying(){
		return state == PlayerState.PLAYING;
	}

	public boolean isDead(){
		return state == PlayerState.DEAD;
	}

	public void setState(PlayerState state) {
		if (this.state == state){
			return; // Don't change the player state when the same.
		}

		PlayerState oldState = this.state;
		this.state = state;

		// Call UhcPlayerStateChangedEvent
		Bukkit.getPluginManager().callEvent(new UhcPlayerStateChangedEvent(this, oldState, state));
	}

	public boolean isFrozen() {
		return freezeLocation != null;
	}

	public Location getFreezeLocation(){
		return freezeLocation;
	}

	public void freezePlayer(Location location){
		freezeLocation = location;
	}

	public void releasePlayer(){
		freezeLocation = null;
	}

	public synchronized Set<Scenario> getScenarioVotes() {
		return scenarioVotes;
	}

	public synchronized Set<UhcTeam> getTeamInvites() {
		return teamInvites;
	}

	public synchronized List<Snapshot<ItemStack>> getStoredItems() {
		return storedItems;
	}

	public UUID getOfflineZombieUuid() {
		return offlineZombieUuid;
	}

	public void setOfflineZombieUuid(UUID offlineZombieUuid) {
		this.offlineZombieUuid = offlineZombieUuid;
	}

	/**
	 * Counts the times the player has crafted the item.
	 * @param craftName Name of the craft.
	 * @param limit The maximum amount of time the player is allowed to craft the item.
	 * @return Returns true if crafting is allowed.
	 */
	public boolean addCraftedItem(String craftName, int limit){
		int quantity = craftedItems.getOrDefault(craftName, 0);

		if(quantity+1 <= limit){
			craftedItems.put(craftName,	quantity+1);
			return true;
		}

		return false;
	}

	public boolean isInTeamWith(UhcPlayer player){
		return team.contains(player);
	}

	public boolean isTeamLeader(){
		return getTeam().getMembers().get(0).equals(this);
	}

	public boolean isSolo(){
		return (getTeam().getMembers().get(0).equals(this) && getTeam().getMembers().size() == 1);
	}

	public boolean canLeaveTeam(){
		return (getTeam().getMembers().size() > 1);
	}

	public void inviteToTeam(UhcTeam team){
		teamInvites.add(team);

		String message = Lang.TEAM_MESSAGE_INVITE_RECEIVE.replace("%name%", team.getTeamName());

		if (PaperLib.isSpigot()){
			SpigotUtils.sendMessage(this, message, Lang.TEAM_MESSAGE_INVITE_RECEIVE_HOVER, "/team invite-reply " + team.getLeader().getName(), SpigotUtils.Action.COMMAND);
		}else {
			sendMessage(message);
		}
	}

	public void sendPrefixedMessage(String message) {
		if (message.isEmpty()) return;
		sendMessage(Lang.DISPLAY_MESSAGE_PREFIX+message);
	}

	public void sendMessage(String message) {
		if (message.isEmpty()) return;
		try {
			getPlayer().sendMessage(message);
		} catch (UhcPlayerNotOnlineException ignored) {
			// Can't send message
		}
	}

	public boolean isGlobalChat() {
		return globalChat;
	}

	public void setGlobalChat(boolean globalChat) {
		this.globalChat = globalChat;
	}

	public int getKills() {
		return kills;
	}

	public void addKill(){
		kills++;
	}

	public void pointCompassToNextPlayer(int mode, int cooldown) {
		PlayerManager pm = GameManager.getGameManager().getPlayerManager();
		List<UhcPlayer> pointPlayers = new ArrayList<>();

		// Check cooldown
		if (cooldown != -1 && (cooldown*TimeUtils.SECOND) + compassPlayingLastUpdate > System.currentTimeMillis()){
			sendMessage(Lang.ITEMS_COMPASS_PLAYING_COOLDOWN);
			return;
		}

		switch (mode){
			case 1:
				pointPlayers.addAll(team.getOnlinePlayingMembers());
				break;
			case 2:
				pointPlayers.addAll(pm.getOnlinePlayingPlayers());
				for (UhcPlayer teamMember : team.getOnlinePlayingMembers()){
					pointPlayers.remove(teamMember);
				}
				break;
			case 3:
				pointPlayers.addAll(pm.getOnlinePlayingPlayers());
				break;
		}

		if((pointPlayers.size() == 1 && pointPlayers.get(0).equals(this)) || pointPlayers.size() == 0){
			sendMessage(Lang.ITEMS_COMPASS_PLAYING_ERROR);
		}else{
			int currentIndice = -1;
			for(int i = 0 ; i < pointPlayers.size() ; i++){
				if(pointPlayers.get(i).equals(compassPlayingCurrentPlayer))
					currentIndice = i;
			}

			// Switching to next player
			if(currentIndice == pointPlayers.size()-1)
				currentIndice = 0;
			else
				currentIndice++;


			// Skipping player if == this
			if(pointPlayers.get(currentIndice).equals(this))
				currentIndice++;

			// Correct indice if out of bounds
			if(currentIndice == pointPlayers.size())
				currentIndice = 0;


			// Pointing compass
			compassPlayingCurrentPlayer = pointPlayers.get(currentIndice);
			compassPlayingLastUpdate = System.currentTimeMillis();
			try {
				Player bukkitPlayer = getPlayer();
				Player bukkitPlayerPointing = compassPlayingCurrentPlayer.getPlayer();

				bukkitPlayer.setCompassTarget(bukkitPlayerPointing.getLocation());

				String message = Lang.ITEMS_COMPASS_PLAYING_POINTING.replace("%player%", compassPlayingCurrentPlayer.getName());

				if (message.contains("%distance%")){
					int distance = (int) bukkitPlayer.getLocation().distance(bukkitPlayerPointing.getLocation());
					message = message.replace("%distance%", String.valueOf(distance));
				}

				sendMessage(message);
			} catch (UhcPlayerNotOnlineException ignored) {
				sendMessage(Lang.TEAM_MESSAGE_PLAYER_NOT_ONLINE.replace("%player%", compassPlayingCurrentPlayer.getName()));
			}
		}

	}

	public boolean hasKitSelected(){
		return kit != null;
	}

	public Kit getKit() {
		return kit;
	}

	public void setKit(Kit kit) {
		this.kit = kit;
	}

	public void selectDefaultGlobalChat() {
		if (team.getMembers().size() == 1) {
			setGlobalChat(true);
		}
	}

	public Location getStartingLocation(){
		return team.getStartingLocation();
	}

	public boolean getHasBeenTeleportedToLocation() {
		return hasBeenTeleportedToLocation;
	}

	public void setHasBeenTeleportedToLocation(boolean hasBeenTeleportedToLocation) {
		this.hasBeenTeleportedToLocation = hasBeenTeleportedToLocation;
	}

	public int getBrowsingPage() {
		return browsingPage;
	}

	public void setBrowsingPage(int browsingPage) {
		this.browsingPage = browsingPage;
	}

	@SuppressWarnings("deprecation") // Can't use attributes in 1.8
	public void healFully() throws UhcPlayerNotOnlineException {
		final Player player = getPlayer();
		player.setHealth(player.getMaxHealth());
	}

	public static void damageIrreducible(Player player, double amount) {
		// While it's tempting to bypass damage reduction by using Player#setHealth,
		// that method has a number of problems. For example, it plays no sound
		// or visual effect, and also ignores absorption and totems of undying.
		// As a workaround, it's possible to bypass damage reduction by temporarily
		// removing any sources of reduction, like the resistance effect or the
		// protection enchantment.

		// Step 1: Save and remove original resistance and protection
		final Optional<PotionEffect> originalResistance = player.getActivePotionEffects().stream()
			.filter(p -> p.getType().equals(PotionEffectType.DAMAGE_RESISTANCE)).findAny();
		player.removePotionEffect(PotionEffectType.DAMAGE_RESISTANCE);

		final ItemStack[] originalArmor = player.getInventory().getArmorContents();
		final ItemStack[] armorWithoutProtection = Arrays.stream(originalArmor)
			.map(item -> {
				if (item == null) return item;
				final ItemStack newItem = new ItemStack(item);
				newItem.removeEnchantment(Enchantment.PROTECTION_ENVIRONMENTAL);
				return newItem;
			})
			.toArray(ItemStack[]::new);
		player.getInventory().setArmorContents(armorWithoutProtection);

		// Step 2: Deal the damage
		player.damage(amount);

		// Step 3: Restore original resistance and protection
		originalResistance.ifPresent(player::addPotionEffect);
		player.getInventory().setArmorContents(originalArmor);
	}

	public static void damageMiningTool(Player player, int blocksMined) {
		int damagePerBlock = UniversalMaterial.getMiningToolDamage(player.getItemInHand().getType());
		if (damagePerBlock > 0) {
			damageItemInMainHand(player, damagePerBlock * blocksMined);
		}
	}

	private static void damageItemInMainHand(Player player, int amount) {
		final ItemStack item = player.getItemInHand();
		item.setDurability((short) (item.getDurability() + amount));
		if (shouldBreak(item)) {
			player.setItemInHand(null);
		}
	}

	private static boolean shouldBreak(ItemStack item) {
		// Behavior is different on older Minecraft versions, see https://bugs.mojang.com/browse/MC-120664
		if (PaperLib.isVersion(13, 1)) { // 1.13.1+
			return item.getDurability() >= item.getType().getMaxDurability();
		} else { // 1.8.8 - 1.13
			return item.getDurability() > item.getType().getMaxDurability();
		}
	}

	public static void teleport(Player player, Location location) {
		// On Minecraft < 1.9, the player may fall through the ground if teleported to a location
		// where the chunk is not loaded, due to vanilla movement code bugs.
		// See also: https://gitlab.com/uhccore/uhccore/-/issues/81
		if (!PaperLib.isVersion(9)) { // 1.9+
			LocationUtils.fullyPopulateChunk(location.getChunk());
		}
		player.teleport(location);
	}

}
