package com.gmail.val59000mc.versionadapters.spigot_1_16.adapters;

import com.gmail.val59000mc.versionadapters.adapters.RespawnAnchorAdapter;
import org.bukkit.block.Block;
import org.bukkit.block.data.BlockData;
import org.bukkit.block.data.type.RespawnAnchor;
import org.bukkit.event.player.PlayerRespawnEvent;

import com.google.auto.service.AutoService;

import io.papermc.lib.PaperLib;

import java.util.OptionalInt;

@AutoService(RespawnAnchorAdapter.class)
public class RespawnAnchorAdapterSpigot_1_16 implements RespawnAnchorAdapter {
	@Override
	public boolean isCompatible() {
		return PaperLib.isVersion(16);
	}

	@Override
	public boolean isAnchorSpawn(PlayerRespawnEvent e) {
		return e.isAnchorSpawn();
	}

	@Override
	public int getRespawnAnchorCharges(Block block) {
		BlockData blockData = block.getBlockData();

		if (!(blockData instanceof RespawnAnchor))
			return -1;

		return ((RespawnAnchor)blockData).getCharges();
	}
}
