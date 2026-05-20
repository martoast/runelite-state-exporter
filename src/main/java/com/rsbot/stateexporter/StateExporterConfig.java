package com.rsbot.stateexporter;

import net.runelite.client.config.Config;
import net.runelite.client.config.ConfigGroup;
import net.runelite.client.config.ConfigItem;

@ConfigGroup("rsbotStateExporter")
public interface StateExporterConfig extends Config
{
	@ConfigItem(
		keyName = "port",
		name = "HTTP port",
		description = "Port to bind the local HTTP server on. Default 9998 " +
			"matches the rs-bot Python client expectations."
	)
	default int port()
	{
		return 9998;
	}
}
