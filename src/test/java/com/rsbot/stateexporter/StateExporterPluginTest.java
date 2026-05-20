package com.rsbot.stateexporter;

import net.runelite.client.RuneLite;
import net.runelite.client.externalplugins.ExternalPluginManager;

/**
 * Dev-mode launcher. `./gradlew run` invokes this `main` to start a
 * RuneLite instance with the StateExporter plugin loaded.
 */
public class StateExporterPluginTest
{
	public static void main(String[] args) throws Exception
	{
		ExternalPluginManager.loadBuiltin(StateExporterPlugin.class);
		RuneLite.main(args);
	}
}
