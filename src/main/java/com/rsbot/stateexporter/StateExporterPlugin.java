package com.rsbot.stateexporter;

import com.google.inject.Provides;
import javax.inject.Inject;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.ChatMessageType;
import net.runelite.api.Client;
import net.runelite.api.GameState;
import net.runelite.api.events.GameStateChanged;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.plugins.Plugin;
import net.runelite.client.plugins.PluginDescriptor;

/**
 * StateExporterPlugin — bridge between RuneLite's client API and the
 * rs-bot Python orchestrator. Mirrors the 2009scape rt4-client plugin
 * of the same name. Exposes HTTP endpoints on localhost:9998 that the
 * Python bot reads to know player position, visible NPCs, widgets,
 * right-click menu state, etc.
 *
 * R0 milestone: scaffold only. Subsequent phases (R1–R4) add the
 * actual endpoints.
 */
@Slf4j
@PluginDescriptor(
	name = "rs-bot State Exporter"
)
public class StateExporterPlugin extends Plugin
{
	@Inject
	private Client client;

	@Inject
	private StateExporterConfig config;

	@Override
	protected void startUp() throws Exception
	{
		log.info("[StateExporter] starting up");
	}

	@Override
	protected void shutDown() throws Exception
	{
		log.info("[StateExporter] shutting down");
	}

	@Subscribe
	public void onGameStateChanged(GameStateChanged event)
	{
		if (event.getGameState() == GameState.LOGGED_IN)
		{
			// One-time confirmation that the plugin is alive after
			// login. Removed once R1 is in.
			client.addChatMessage(ChatMessageType.GAMEMESSAGE, "",
				"[StateExporter] plugin active", null);
		}
	}

	@Provides
	StateExporterConfig provideConfig(ConfigManager configManager)
	{
		return configManager.getConfig(StateExporterConfig.class);
	}
}
