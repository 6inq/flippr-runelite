package com.flippr.osrs;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import net.runelite.api.Client;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.mockito.Spy;

// Requirement: when a GE slot holds a Plan position's offer - matched by
// item and side, and only when that match is unique - the plugin reports it
// like the RS3 app: POST /api/v1/osrs/listings [{positionId, side, ts,
// price}] with the exact listed price, so pending -> buying / bought ->
// selling happens before anything fills and the listed price applies.
// A price change (re-list or update) is reported again; nothing is sent
// that would move a status backwards or that the site already has.
public class ListingReporterTest
{
	private static final int WHIP = 4151;
	private static final int BOND = 13190;

	@Mock
	private Client client;
	@Mock
	private WebsiteConnection website;
	@Mock
	private ServerSuggestions serverSuggestions;
	@Spy
	private Gson gson = new Gson();

	@InjectMocks
	private ListingReporter reporter;

	private AutoCloseable mocks;
	private long now = 1_700_000_000_000L;
	private final List<String> posted = new ArrayList<>();
	private WebsiteConnection.ApiResponse answer;

	@Before
	public void setUp()
	{
		mocks = MockitoAnnotations.openMocks(this);
		reporter.clock = () -> now;
		when(website.isSyncEnabled()).thenReturn(true);
		answer = new WebsiteConnection.ApiResponse(WebsiteConnection.Outcome.OK, 200,
			"{\"received\":1,\"applied\":1,\"unchanged\":0,\"unmatched\":0}");
		doAnswer(inv ->
		{
			posted.add(inv.getArgument(1));
			WebsiteConnection.ApiCallback cb = inv.getArgument(2);
			cb.onResult(answer);
			return null;
		}).when(website).postJsonAsync(eq(ListingReporter.PATH), anyString(), any());
	}

	@After
	public void tearDown() throws Exception
	{
		mocks.close();
	}

	private static ServerSuggestions.PlanPosition pos(String id, int itemId, String status, int target, int bought, int sold, double buy, double sell)
	{
		ServerSuggestions.PlanPosition p = new ServerSuggestions.PlanPosition();
		p.id = id;
		p.itemId = itemId;
		p.name = "Item " + itemId;
		p.status = status;
		p.targetQty = target;
		p.boughtQty = bought;
		p.soldQty = sold;
		p.buyPrice = buy;
		p.sellPrice = sell;
		return p;
	}

	private static ListingReporter.SlotOffer buying(int slot, int itemId, long price)
	{
		return new ListingReporter.SlotOffer(slot, itemId, "buy", price, true);
	}

	private static ListingReporter.SlotOffer selling(int slot, int itemId, long price)
	{
		return new ListingReporter.SlotOffer(slot, itemId, "sell", price, true);
	}

	// Req: a buy placed for a pending position is reported with its exact price.
	@Test
	public void buyPlacedForPendingPositionIsReported()
	{
		List<ListingReporter.Listing> out = ListingReporter.listingsFor(
			Collections.singletonList(buying(2, WHIP, 1_234_567)),
			Collections.singletonList(pos("p1", WHIP, "pending", 10, 0, 0, 1_230_000, 1_300_000)), now);
		assertEquals(1, out.size());
		assertEquals("p1", out.get(0).positionId);
		assertEquals("buy", out.get(0).side);
		assertEquals(1_234_567, out.get(0).price);
		assertEquals(now, out.get(0).ts);
	}

	// Req: a sell placed for a bought position is reported (bought -> selling).
	@Test
	public void sellPlacedForBoughtPositionIsReported()
	{
		List<ListingReporter.Listing> out = ListingReporter.listingsFor(
			Collections.singletonList(selling(5, WHIP, 1_299_999)),
			Collections.singletonList(pos("p1", WHIP, "bought", 10, 10, 0, 1_230_000, 1_300_000)), now);
		assertEquals(1, out.size());
		assertEquals("sell", out.get(0).side);
		assertEquals(1_299_999, out.get(0).price);
	}

	// Req: a listed offer the site already has (status there, same price) is not sent.
	@Test
	public void nothingSentWhenSiteAlreadyHasIt()
	{
		assertTrue(ListingReporter.listingsFor(
			Collections.singletonList(buying(0, WHIP, 1_230_000)),
			Collections.singletonList(pos("p1", WHIP, "buying", 10, 3, 0, 1_230_000, 1_300_000)), now).isEmpty());
		assertTrue(ListingReporter.listingsFor(
			Collections.singletonList(selling(0, WHIP, 1_300_000)),
			Collections.singletonList(pos("p1", WHIP, "selling", 10, 10, 2, 1_230_000, 1_300_000)), now).isEmpty());
	}

	// Req: a price change on an existing offer (re-list / update) re-reports the new price.
	@Test
	public void priceChangeIsReportedForBothSides()
	{
		List<ListingReporter.Listing> buy = ListingReporter.listingsFor(
			Collections.singletonList(buying(0, WHIP, 1_240_000)),
			Collections.singletonList(pos("p1", WHIP, "buying", 10, 3, 0, 1_230_000, 1_300_000)), now);
		assertEquals(1, buy.size());
		assertEquals(1_240_000, buy.get(0).price);

		List<ListingReporter.Listing> sell = ListingReporter.listingsFor(
			Collections.singletonList(selling(0, WHIP, 1_290_000)),
			Collections.singletonList(pos("p1", WHIP, "selling", 10, 10, 2, 1_230_000, 1_300_000)), now);
		assertEquals(1, sell.size());
		assertEquals(1_290_000, sell.get(0).price);
	}

	// Req: never backwards - a buy offer for a position already bought /
	// selling (nothing left to buy) is not reported.
	@Test
	public void noBackwardsReport()
	{
		assertTrue(ListingReporter.listingsFor(
			Collections.singletonList(buying(0, WHIP, 1_000)),
			Collections.singletonList(pos("p1", WHIP, "bought", 10, 10, 0, 1_230_000, 1_300_000)), now).isEmpty());
		assertTrue(ListingReporter.listingsFor(
			Collections.singletonList(buying(0, WHIP, 1_000)),
			Collections.singletonList(pos("p1", WHIP, "selling", 10, 10, 4, 1_230_000, 1_300_000)), now).isEmpty());
		// Nothing bought yet: a sell offer can't be this position's.
		assertTrue(ListingReporter.listingsFor(
			Collections.singletonList(selling(0, WHIP, 1_300_000)),
			Collections.singletonList(pos("p1", WHIP, "pending", 10, 0, 0, 1_230_000, 1_300_000)), now).isEmpty());
	}

	// Req: ambiguous matches report nothing - two slots with the same item
	// and side, or two positions for that item on the same leg.
	@Test
	public void ambiguousMatchReportsNothing()
	{
		assertTrue(ListingReporter.listingsFor(
			Arrays.asList(buying(0, WHIP, 1_234_000), buying(3, WHIP, 1_235_000)),
			Collections.singletonList(pos("p1", WHIP, "pending", 10, 0, 0, 1_230_000, 1_300_000)), now).isEmpty());
		assertTrue(ListingReporter.listingsFor(
			Collections.singletonList(buying(0, WHIP, 1_234_000)),
			Arrays.asList(pos("p1", WHIP, "pending", 10, 0, 0, 1_230_000, 1_300_000),
				pos("p2", WHIP, "buying", 5, 1, 0, 1_230_000, 1_300_000)), now).isEmpty());
	}

	// Req: a buy and a sell offer of one position (selling part while the
	// buy keeps going) are matched separately; finished offers are not reported.
	@Test
	public void buyAndSellOfOnePositionAndFinishedOffers()
	{
		List<ListingReporter.Listing> out = ListingReporter.listingsFor(
			Arrays.asList(buying(0, BOND, 10_000_000), selling(1, BOND, 10_600_000)),
			Collections.singletonList(pos("b", BOND, "buying", 10, 4, 0, 10_000_000, 10_500_000)), now);
		assertEquals(1, out.size());
		assertEquals("sell", out.get(0).side);

		assertTrue(ListingReporter.listingsFor(
			Collections.singletonList(new ListingReporter.SlotOffer(0, WHIP, "buy", 1_234_567, false)),
			Collections.singletonList(pos("p1", WHIP, "pending", 10, 0, 0, 1_230_000, 1_300_000)), now).isEmpty());
	}

	// Req: each (position, side, price) is posted once while the plan catches
	// up; a new price is posted at once; an applied listing refreshes the plan.
	@Test
	public void postsOnceAndRefreshesPlanWhenApplied()
	{
		List<ListingReporter.Listing> first = Collections.singletonList(new ListingReporter.Listing("p1", "buy", now, 1_234_567));
		reporter.report(first);
		reporter.report(first);
		assertEquals(1, posted.size());
		JsonArray body = gson.fromJson(posted.get(0), JsonArray.class);
		JsonObject l = body.get(0).getAsJsonObject();
		assertEquals("p1", l.get("positionId").getAsString());
		assertEquals("buy", l.get("side").getAsString());
		assertEquals(1_234_567, l.get("price").getAsLong());
		assertEquals(now, l.get("ts").getAsLong());
		verify(serverSuggestions).refreshNow();

		reporter.report(Collections.singletonList(new ListingReporter.Listing("p1", "buy", now, 1_250_000)));
		assertEquals(2, posted.size());

		now += ListingReporter.RESEND_AFTER_MS;
		reporter.report(first);
		assertEquals("resent once the plan still disagrees after a minute", 3, posted.size());
	}

	// Req: a network failure retries after a short wait; nothing is sent while not synced.
	@Test
	public void retriesAfterFailureAndRespectsSync()
	{
		answer = new WebsiteConnection.ApiResponse(WebsiteConnection.Outcome.FAILED, 0, null);
		List<ListingReporter.Listing> l = Collections.singletonList(new ListingReporter.Listing("p1", "sell", now, 99));
		reporter.report(l);
		reporter.report(l);
		assertEquals(1, posted.size());
		verify(serverSuggestions, never()).refreshNow();
		now += ListingReporter.RETRY_AFTER_FAILURE_MS;
		reporter.report(l);
		assertEquals(2, posted.size());

		when(website.isSyncEnabled()).thenReturn(false);
		now += ListingReporter.RESEND_AFTER_MS;
		reporter.report(l);
		assertEquals(2, posted.size());
	}

	// Req: the card's "GE n" is every in-game slot (1-8) holding the item.
	@Test
	public void geSlotsForCards()
	{
		ServerSuggestions.PlanPosition p = pos("p1", WHIP, "buying", 10, 2, 0, 1, 2);
		assertEquals(Arrays.asList(3, 7), ListingReporter.geSlotsByPosition(
			Arrays.asList(buying(2, WHIP, 1), new ListingReporter.SlotOffer(6, WHIP, "buy", 1, false), buying(4, BOND, 1)),
			Collections.singletonList(p)).get("p1"));
	}
}
