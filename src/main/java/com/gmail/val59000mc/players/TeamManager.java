package com.gmail.val59000mc.players;

import com.gmail.val59000mc.exceptions.UhcTeamException;
import com.gmail.val59000mc.game.handlers.ScoreboardHandler;
import com.gmail.val59000mc.languages.Lang;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.entity.Player;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;
import java.util.PriorityQueue;
import java.util.concurrent.ThreadLocalRandom;
import java.util.regex.Pattern;

public class TeamManager{

	// This limit is imposed by Minecraft 1.8 - 1.17.
	private static final int TEAM_NAME_MAX_LENGTH = 16;

	// Allow space-separated alphanumeric Unicode words (excluding the Connector Punctuation category).
	// https://unicode.org/reports/tr18/#Compatibility_Properties
	// https://unicode.org/reports/tr18/#General_Category_Property
	private static final Pattern TEAM_NAME_PATTERN = Pattern.compile("[\\p{Alnum}\\p{gc=M}\\p{IsJoin_Control}\\p{gc=Zs}]+", Pattern.UNICODE_CHARACTER_CLASS);

	private static ChatColor[] getTeamColors() {
		return new ChatColor[] {
			ChatColor.RED,
			ChatColor.BLUE,
			ChatColor.DARK_GREEN,
			ChatColor.DARK_AQUA,
			ChatColor.DARK_PURPLE,
			ChatColor.YELLOW,
			ChatColor.GOLD,
			ChatColor.GREEN,
			ChatColor.AQUA,
			ChatColor.LIGHT_PURPLE
		};
	}

	private final PlayerManager playerManager;
	private final ScoreboardHandler scoreboardHandler;
	private int lastTeamNumber;
	private final ChatColor[] randomColorPool;
	private int randomColorCounter;
	private final PriorityQueue<Integer> freeTeamNumbers;

	public TeamManager(PlayerManager playerManager, ScoreboardHandler scoreboardHandler){
		this.playerManager = playerManager;
		this.scoreboardHandler = scoreboardHandler;
		this.lastTeamNumber = 0;
		this.randomColorPool = getTeamColors();
		this.randomColorCounter = 0;
		this.freeTeamNumbers = new PriorityQueue<>();
	}

	public List<UhcTeam> getPlayingUhcTeams(){
		List<UhcTeam> teams = new ArrayList<>();
		for(UhcTeam team : getUhcTeams()){
			if (team.getPlayingMemberCount() != 0){
				teams.add(team);
			}
		}
		return teams;
	}

	public List<UhcTeam> getUhcTeams(){
		List<UhcTeam> teams = new ArrayList<>();
		for(UhcPlayer player : playerManager.getPlayersList()){

			UhcTeam team = player.getTeam();
			if(!teams.contains(team)) {
				teams.add(team);
			}
		}
		return teams;
	}

	public String sendInvite(Player inviter, String inviteeName) {
		final UhcPlayer uhcInviter = playerManager.getUhcPlayer(inviter);
		return sendInvite(uhcInviter, inviteeName);
	}

	public String sendInvite(UhcPlayer inviter, String inviteeName) {
		if (!inviter.isTeamLeader()) {
			return Lang.TEAM_MESSAGE_NOT_LEADER;
		}

		if (inviteeName == null || inviteeName.isEmpty()) {
			return Lang.TEAM_MESSAGE_PLAYER_NAME_EMPTY;
		}

		final Player bukkitInvitee = Bukkit.getPlayer(inviteeName);
		if (bukkitInvitee == null) {
			return Lang.TEAM_MESSAGE_PLAYER_NOT_ONLINE.replace("%player%", inviteeName);
		}

		final UhcTeam team = inviter.getTeam();
		final UhcPlayer uhcInvitee = playerManager.getUhcPlayer(bukkitInvitee);

		// Spectators are currently represented by dead players, see SpectateCommandExecutor
		if (uhcInvitee.isDead()) {
			return Lang.TEAM_MESSAGE_SPECTATORS_CANNOT_JOIN;
		}
		if (team.contains(uhcInvitee)) {
			return Lang.TEAM_MESSAGE_ALREADY_IN_TEAM.replace("%player%", uhcInvitee.getRealName());
		}
		if (uhcInvitee.getTeamInvites().contains(team)) {
			return Lang.TEAM_MESSAGE_INVITE_ALREADY_SENT.replace("%player%", uhcInvitee.getRealName());
		}

		// Validation passed, perform the invite
		uhcInvitee.inviteToTeam(team);
		return Lang.TEAM_MESSAGE_INVITE_SUCCESS.replace("%player%", uhcInvitee.getRealName());
	}

	public void replyToTeamInvite(UhcPlayer uhcPlayer, UhcTeam team, boolean accepted){
		uhcPlayer.getTeamInvites().remove(team);

		if (!accepted){
			uhcPlayer.sendMessage(Lang.TEAM_MESSAGE_DENY_REQUEST);
			return;
		}

		// Spectators are currently represented by dead players, see SpectateCommandExecutor
		if (uhcPlayer.isDead()) {
			uhcPlayer.sendMessage(Lang.TEAM_MESSAGE_SPECTATORS_CANNOT_JOIN);
			return;
		}

		try{
			team.join(uhcPlayer);

			scoreboardHandler.updateTeamOnTab(team);
		}catch (UhcTeamException ex){
			uhcPlayer.sendMessage(ex.getMessage());
		}
	}

	public String renameTeam(UhcTeam team, String newName) {
		if (newName == null || newName.isEmpty()) {
			return Lang.TEAM_MESSAGE_NAME_EMPTY;
		} else if (newName.length() > TEAM_NAME_MAX_LENGTH) {
			return Lang.TEAM_MESSAGE_NAME_TOO_LONG;
		} else if (!TEAM_NAME_PATTERN.matcher(newName).matches()) {
			return Lang.TEAM_MESSAGE_NAME_ILLEGAL_CHARACTERS;
		}

		team.setTeamName(newName);
		return Lang.TEAM_MESSAGE_NAME_CHANGED;
	}

	@Nullable
	public UhcTeam getTeamByName(String name){
		for (UhcTeam team : getUhcTeams()){
			if (team.getTeamName().equals(name)){
				return team;
			}
		}

		return null;
	}

	public int getNewTeamNumber() {
		if (!freeTeamNumbers.isEmpty()) {
			return freeTeamNumbers.poll();
		}
		lastTeamNumber++;
		return lastTeamNumber;
	}

	public void freeTeamNumber(UhcTeam team) {
		freeTeamNumbers.add(team.getTeamNumber());
	}

	public ChatColor getRandomTeamColor() {
		// In order to avoid color repetitions, we implement a variant of the
		// algorithm described here: https://www.baeldung.com/cs/non-repeating-random-number-generator#shuffling-during-generation
		// In our variant, randomColorCounter keeps track of the lowest (inclusive)
		// free index, and the used elements are swapped to the front of the array,
		// as opposed to the back. Colors may occasionally repeat when we have
		// gone through an entire cycle of colors.

		// 1. Pick a random color from the "free" section at the back of the array
		final int freeIndex = randomColorCounter;
		final ChatColor randomColor = randomColorPool[freeIndex];

		// 2. Swap it to the "used" section at the front of the array
		randomColorPool[freeIndex] = randomColorPool[randomColorCounter];
		randomColorPool[randomColorCounter] = randomColor;

		// 3. Increment the cyclic counter
		randomColorCounter = (randomColorCounter + 1) % randomColorPool.length;

		return randomColor;
	}

}
