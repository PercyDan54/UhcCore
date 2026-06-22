package com.gmail.val59000mc.versionadapters.adapters;

import com.gmail.val59000mc.versionadapters.VersionAdapter;
import org.bukkit.block.Block;
import org.bukkit.event.player.PlayerRespawnEvent;

import java.util.OptionalInt;

public interface RespawnAnchorAdapter extends VersionAdapter {
	boolean isAnchorSpawn(PlayerRespawnEvent e);
	int getRespawnAnchorCharges(Block block);
}
