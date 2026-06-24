package com.gmail.val59000mc.versionadapters.spigot_1_8_8.adapters;

import com.gmail.val59000mc.versionadapters.adapters.RespawnAnchorAdapter;
import org.bukkit.block.Block;
import org.bukkit.event.player.PlayerRespawnEvent;

import com.google.auto.service.AutoService;

import io.papermc.lib.PaperLib;

@AutoService(RespawnAnchorAdapter.class)
public class RespawnAnchorAdapterSpigot_1_8_8 implements RespawnAnchorAdapter {
	@Override
	public boolean isCompatible() {
		return PaperLib.isVersion(8) && !PaperLib.isVersion(16);
	}

	@Override
	public boolean isAnchorSpawn(PlayerRespawnEvent e) {
		return false;
	}

	@Override
	public int getRespawnAnchorCharges(Block block) {
		return -1;
	}
}
