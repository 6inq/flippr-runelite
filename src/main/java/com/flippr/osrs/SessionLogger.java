package com.flippr.osrs;

import com.google.gson.Gson;
import java.io.IOException;
import javax.inject.Inject;
import lombok.extern.slf4j.Slf4j;

// Append-only JSON-lines file, one line per completed session (see
// FlipTracker.resetSession()) - same "separate stack reads this later"
// reasoning as FlipLogger's own header comment. This is what "reset
// session" actually means: draw a durable boundary and keep the old
// session's totals, not just zero them out and lose them.
@Slf4j
public class SessionLogger
{
	private static final String LOG_FILE = FlipprFiles.SESSIONS;

	@Inject
	private FlipprFiles files;

	// RuneLite's shared Gson (Plugin Hub rule: no private Gson instances).
	@Inject
	Gson gson;

	public void log(SessionSummary summary)
	{
		try
		{
			files.appendLine(LOG_FILE, gson.toJson(summary));
			log.info(
				"Session recorded: {} flips, {} profit, {}s duration",
				summary.flipCount, summary.profit, (summary.endTime - summary.startTime) / 1000
			);
		}
		catch (IOException e)
		{
			log.warn("Failed to write session log to {}", LOG_FILE, e);
		}
	}
}
