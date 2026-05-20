package com.rsbot.stateexporter;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.inject.Provides;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import javax.inject.Inject;

import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Client;
import net.runelite.api.Item;
import net.runelite.api.ItemContainer;
import net.runelite.api.NPC;
import net.runelite.api.NPCComposition;
import net.runelite.api.Player;
import net.runelite.api.Point;
import net.runelite.api.Skill;
import net.runelite.api.WorldView;
import net.runelite.api.coords.LocalPoint;
import net.runelite.api.coords.WorldPoint;
import net.runelite.api.events.GameTick;
import net.runelite.api.gameval.InventoryID;
import net.runelite.api.Perspective;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.plugins.Plugin;
import net.runelite.client.plugins.PluginDescriptor;

/**
 * StateExporterPlugin — RuneLite-side bridge for the rs-bot Python
 * orchestrator. Mirrors the 2009scape rt4-client StateExporterPlugin
 * so the Python bot's `scene.py` doesn't need to change between
 * engines.
 *
 * <p>Bound endpoints (HTTP, GET):
 * <ul>
 *   <li>{@code http://localhost:9998/state} — full snapshot of the
 *       local player + visible NPCs, refreshed every GameTick.
 *       Mirrors the 2009scape client-side /state.</li>
 *   <li>{@code http://localhost:9999/state} — identical content,
 *       served on 9999 so the rs-bot's _STATE_URL (which expects the
 *       server-side endpoint on 9999) works unchanged. In real OSRS
 *       there's no separate server we control, so we serve the same
 *       merged shape from both ports.</li>
 *   <li>{@code /ping} on either port — liveness probe, returns "ok".</li>
 * </ul>
 *
 * <p>JSON shape (R1):
 * <pre>{@code
 * {
 *   "players": [{
 *     "name": "Bot",
 *     "x": 3103, "y": 3425, "plane": 0,
 *     "hp": 10, "max_hp": 10,
 *     "in_combat": false,
 *     "animation": -1,
 *     "camera_yaw": 0,
 *     "camera_pitch": 1024,
 *     "inventory": [{"id": 1351, "amount": 1, "slot": 0}, ...]
 *   }],
 *   "npcs": [{
 *     "id": 316, "name": "Fishing spot",
 *     "wx": 3104, "wy": 3425, "plane": 0,
 *     "sx": 297, "sy": 182,
 *     "size": 1,
 *     "actions": ["Net", "Bait", "Examine"]
 *   }]
 * }
 * }</pre>
 *
 * Subsequent phases (R2 widgets, R3 menu, R4 menu-swap) will extend
 * this plugin with additional endpoints in the same style.
 */
@Slf4j
@PluginDescriptor(name = "rs-bot State Exporter")
public class StateExporterPlugin extends Plugin
{
	@Inject
	private Client client;

	@Inject
	private Gson gson;

	@Inject
	private StateExporterConfig config;

	private HttpServer serverClient;     // 9998 (client-side /state URL)
	private HttpServer serverServer;     // 9999 (server-side /state URL)
	private ExecutorService httpExecutor;

	/** Latest /state snapshot. Written on the client thread by
	 *  onGameTick, read by HTTP handler threads. Volatile is enough —
	 *  the snapshot is a single immutable String. */
	private volatile String stateJson = "{\"players\":[],\"npcs\":[]}";

	@Override
	protected void startUp() throws Exception
	{
		httpExecutor = Executors.newCachedThreadPool(r ->
		{
			Thread t = new Thread(r, "StateExporter-http");
			t.setDaemon(true);
			return t;
		});

		serverClient = bindServer(9998);
		try
		{
			serverServer = bindServer(9999);
		}
		catch (IOException e)
		{
			log.warn("[StateExporter] could not bind port 9999 " +
				"(server-side fallback won't be available): {}", e.toString());
		}

		log.info("[StateExporter] listening on http://localhost:9998 " +
			(serverServer != null ? "and :9999" : "(only)"));
	}

	@Override
	protected void shutDown() throws Exception
	{
		if (serverClient != null)
		{
			serverClient.stop(0);
			serverClient = null;
		}
		if (serverServer != null)
		{
			serverServer.stop(0);
			serverServer = null;
		}
		if (httpExecutor != null)
		{
			httpExecutor.shutdownNow();
			httpExecutor = null;
		}
		log.info("[StateExporter] shut down");
	}

	private HttpServer bindServer(int port) throws IOException
	{
		HttpServer s = HttpServer.create(new InetSocketAddress("localhost", port), 0);
		s.createContext("/state", this::handleState);
		s.createContext("/ping", this::handlePing);
		s.setExecutor(httpExecutor);
		s.start();
		return s;
	}

	private void handleState(HttpExchange ex) throws IOException
	{
		String body = stateJson;
		byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
		ex.getResponseHeaders().add("Content-Type", "application/json");
		ex.sendResponseHeaders(200, bytes.length);
		try (OutputStream os = ex.getResponseBody())
		{
			os.write(bytes);
		}
	}

	private void handlePing(HttpExchange ex) throws IOException
	{
		byte[] bytes = "ok".getBytes(StandardCharsets.UTF_8);
		ex.sendResponseHeaders(200, bytes.length);
		try (OutputStream os = ex.getResponseBody())
		{
			os.write(bytes);
		}
	}

	@Subscribe
	public void onGameTick(GameTick event)
	{
		// Runs on the client thread — safe to read client.* fields.
		stateJson = buildStateJson();
	}

	private String buildStateJson()
	{
		JsonObject root = new JsonObject();
		JsonArray players = new JsonArray();
		JsonArray npcs = new JsonArray();

		Player local = client.getLocalPlayer();
		if (local != null)
		{
			JsonObject p = new JsonObject();
			p.addProperty("name", local.getName());
			WorldPoint wp = local.getWorldLocation();
			if (wp != null)
			{
				p.addProperty("x", wp.getX());
				p.addProperty("y", wp.getY());
				p.addProperty("plane", wp.getPlane());
			}
			p.addProperty("hp", client.getBoostedSkillLevel(Skill.HITPOINTS));
			p.addProperty("max_hp", client.getRealSkillLevel(Skill.HITPOINTS));
			p.addProperty("in_combat", local.getInteracting() != null);
			p.addProperty("animation", local.getAnimation());
			p.addProperty("camera_yaw", client.getCameraYaw());
			p.addProperty("camera_pitch", client.getCameraPitch());

			JsonArray inv = new JsonArray();
			ItemContainer container = client.getItemContainer(InventoryID.INV);
			if (container != null)
			{
				Item[] items = container.getItems();
				for (int i = 0; i < items.length; i++)
				{
					Item it = items[i];
					if (it == null || it.getId() <= 0)
					{
						continue;
					}
					JsonObject item = new JsonObject();
					item.addProperty("id", it.getId());
					item.addProperty("amount", it.getQuantity());
					item.addProperty("slot", i);
					inv.add(item);
				}
			}
			p.add("inventory", inv);

			players.add(p);
		}

		WorldView wv = client.getTopLevelWorldView();
		int currentPlane = wv != null ? wv.getPlane() : 0;
		for (NPC npc : wv != null ? wv.npcs() : java.util.Collections.<NPC>emptyList())
		{
			if (npc == null)
			{
				continue;
			}
			NPCComposition comp = npc.getComposition();
			if (comp == null)
			{
				continue;
			}

			WorldPoint nwp = npc.getWorldLocation();
			if (nwp == null)
			{
				continue;
			}
			LocalPoint nlp = npc.getLocalLocation();
			if (nlp == null)
			{
				continue;
			}
			// Body-midpoint projection: hover ~half the NPC's drawn
			// height. RuneLite's Perspective handles camera yaw +
			// pitch transparently.
			Point screen = Perspective.localToCanvas(
				client, nlp, currentPlane, comp.getSize() * 60);
			if (screen == null)
			{
				continue;   // off-screen NPC, skip
			}

			JsonObject n = new JsonObject();
			n.addProperty("id", npc.getId());
			n.addProperty("name", npc.getName());
			n.addProperty("wx", nwp.getX());
			n.addProperty("wy", nwp.getY());
			n.addProperty("plane", nwp.getPlane());
			n.addProperty("sx", screen.getX());
			n.addProperty("sy", screen.getY());
			n.addProperty("size", comp.getSize());

			String[] actions = comp.getActions();
			if (actions != null)
			{
				JsonArray a = new JsonArray();
				for (String act : actions)
				{
					if (act != null)
					{
						a.add(act);
					}
				}
				n.add("actions", a);
			}

			npcs.add(n);
		}

		root.add("players", players);
		root.add("npcs", npcs);
		return gson.toJson(root);
	}

	@Provides
	StateExporterConfig provideConfig(ConfigManager configManager)
	{
		return configManager.getConfig(StateExporterConfig.class);
	}
}
