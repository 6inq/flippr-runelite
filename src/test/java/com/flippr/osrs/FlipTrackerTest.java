package com.flippr.osrs;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import net.runelite.api.Client;
import net.runelite.api.GrandExchangeOffer;
import net.runelite.api.GrandExchangeOfferState;
import net.runelite.api.ItemComposition;
import net.runelite.client.game.ItemManager;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

// Requirement: FlipTracker turns GrandExchangeOfferChanged events into
// CompletedFlip records - cumulative updates not double counted, partial buy
// cancels and sell price edits stitched correctly, weighted average buy
// price, profit = sell - buy - tax. No live client: collaborators are mocks
// (FlipLogger is mocked, so nothing is written to ~/.runelite/flippr).
public class FlipTrackerTest
{
	private static final int WHIP = 4151;
	private static final int BOND = 13190;
	private static final int BUY_SLOT = 0;
	private static final int SELL_SLOT = 1;

	@Mock
	private ItemManager itemManager;
	@Mock
	private Client client;
	@Mock
	private FlipLogger flipLogger;
	@Mock
	private PriceFeed priceFeed;
	@Mock
	private SessionLogger sessionLogger;
	@Mock
	private FlipprConfig config;

	@InjectMocks
	private FlipTracker tracker;

	private AutoCloseable mocks;

	@Before
	public void setUp()
	{
		mocks = MockitoAnnotations.openMocks(this);
		stubName(WHIP, "Abyssal whip");
		stubName(BOND, "Old school bond");
		// Mockito defaults Long returns to 0L; an empty feed returns null.
		when(priceFeed.getInstantBuyPrice(anyInt())).thenReturn(null);
		when(priceFeed.getInstantSellPrice(anyInt())).thenReturn(null);
	}

	@After
	public void tearDown() throws Exception
	{
		mocks.close();
	}

	private void stubName(int itemId, String name)
	{
		ItemComposition composition = mock(ItemComposition.class);
		when(composition.getName()).thenReturn(name);
		when(itemManager.getItemComposition(itemId)).thenReturn(composition);
	}

	private static GrandExchangeOffer offer(GrandExchangeOfferState state, int itemId, int qtyDone, long spent, long price, int total)
	{
		GrandExchangeOffer offer = mock(GrandExchangeOffer.class);
		when(offer.getState()).thenReturn(state);
		when(offer.getItemId()).thenReturn(itemId);
		when(offer.getQuantitySold()).thenReturn(qtyDone);
		when(offer.getSpent()).thenReturn(spent);
		when(offer.getPrice()).thenReturn(price);
		when(offer.getTotalQuantity()).thenReturn(total);
		return offer;
	}

	private void event(int slot, GrandExchangeOfferState state, int itemId, int qtyDone, long spent, long price, int total)
	{
		tracker.process(slot, offer(state, itemId, qtyDone, spent, price, total));
	}

	private CompletedFlip onlyFlip()
	{
		ArgumentCaptor<CompletedFlip> captor = ArgumentCaptor.forClass(CompletedFlip.class);
		verify(flipLogger, times(1)).log(captor.capture());
		return captor.getValue();
	}

	private void buyTenWhipsAt100()
	{
		event(BUY_SLOT, GrandExchangeOfferState.BUYING, WHIP, 0, 0, 100, 10);
		event(BUY_SLOT, GrandExchangeOfferState.BOUGHT, WHIP, 10, 1_000, 100, 10);
	}

	// Req: cumulative mid-fill updates and repeated terminal events are not
	// double counted - one buy of 10, one sell of 10, exactly one flip.
	@Test
	public void cumulativeUpdatesAreNotDoubleCounted()
	{
		event(BUY_SLOT, GrandExchangeOfferState.BUYING, WHIP, 0, 0, 100, 10);
		event(BUY_SLOT, GrandExchangeOfferState.BUYING, WHIP, 2, 200, 100, 10);
		event(BUY_SLOT, GrandExchangeOfferState.BUYING, WHIP, 5, 500, 100, 10);
		event(BUY_SLOT, GrandExchangeOfferState.BOUGHT, WHIP, 10, 1_000, 100, 10);
		event(BUY_SLOT, GrandExchangeOfferState.BOUGHT, WHIP, 10, 1_000, 100, 10);

		List<OpenPositionEntry> positions = tracker.getOpenPositions();
		assertEquals(1, positions.size());
		assertEquals(10, positions.get(0).getQuantity());
		assertEquals(100, positions.get(0).getAvgBuyPrice());

		event(SELL_SLOT, GrandExchangeOfferState.SELLING, WHIP, 3, 360, 120, 10);
		event(SELL_SLOT, GrandExchangeOfferState.SOLD, WHIP, 10, 1_200, 120, 10);
		event(SELL_SLOT, GrandExchangeOfferState.SOLD, WHIP, 10, 1_200, 120, 10);

		CompletedFlip flip = onlyFlip();
		assertEquals(10, flip.quantity);
		assertEquals(100, flip.avgBuyPrice);
		assertEquals(120, flip.avgSellPrice);
		assertEquals(1, tracker.getSessionFlipCount());
		assertTrue(tracker.getOpenPositions().isEmpty());
	}

	// Req: profit = sell - buy - tax (2/unit on a 120gp sell -> 20 tax).
	@Test
	public void profitIsSellMinusBuyMinusTax()
	{
		buyTenWhipsAt100();
		event(SELL_SLOT, GrandExchangeOfferState.SOLD, WHIP, 10, 1_200, 120, 10);

		CompletedFlip flip = onlyFlip();
		assertEquals(20, flip.tax);
		assertEquals(1_200 - 1_000 - 20, flip.profit);
		assertEquals(180, tracker.getSessionProfit());
		assertEquals("Abyssal whip", flip.itemName);
		assertEquals(WHIP, flip.itemId);
	}

	// Req: tax-exempt item -> profit has no tax term.
	@Test
	public void exemptItemProfitHasNoTax()
	{
		event(BUY_SLOT, GrandExchangeOfferState.BOUGHT, BOND, 2, 10_000_000, 5_000_000, 2);
		event(SELL_SLOT, GrandExchangeOfferState.SOLD, BOND, 2, 11_000_000, 5_500_000, 2);

		CompletedFlip flip = onlyFlip();
		assertEquals(0, flip.tax);
		assertEquals(1_000_000, flip.profit);
	}

	// Req: the 5M per-unit tax cap applies inside a real flip.
	@Test
	public void taxCapAppliesToFlipProfit()
	{
		event(BUY_SLOT, GrandExchangeOfferState.BOUGHT, WHIP, 1, 300_000_000, 300_000_000, 1);
		event(SELL_SLOT, GrandExchangeOfferState.SOLD, WHIP, 1, 400_000_000, 400_000_000, 1);

		CompletedFlip flip = onlyFlip();
		assertEquals(5_000_000, flip.tax);
		assertEquals(95_000_000, flip.profit);
	}

	// Req: a partially filled buy that's cancelled, then sold -> exactly one
	// flip for the filled quantity only.
	@Test
	public void partialBuyCancelThenSellIsOneFlip()
	{
		event(BUY_SLOT, GrandExchangeOfferState.BUYING, WHIP, 4, 400, 100, 10);
		event(BUY_SLOT, GrandExchangeOfferState.CANCELLED_BUY, WHIP, 4, 400, 100, 10);
		event(BUY_SLOT, GrandExchangeOfferState.CANCELLED_BUY, WHIP, 4, 400, 100, 10);

		event(SELL_SLOT, GrandExchangeOfferState.SOLD, WHIP, 4, 600, 150, 4);

		CompletedFlip flip = onlyFlip();
		assertEquals(4, flip.quantity);
		assertEquals(100, flip.avgBuyPrice);
		assertEquals(150, flip.avgSellPrice);
		assertEquals(4 * 3, flip.tax);
		assertEquals(600 - 400 - 12, flip.profit);
	}

	// Req (edge): a cancelled buy with zero fill records nothing.
	@Test
	public void cancelledBuyWithNoFillRecordsNothing()
	{
		event(BUY_SLOT, GrandExchangeOfferState.CANCELLED_BUY, WHIP, 0, 0, 100, 10);
		assertTrue(tracker.getOpenPositions().isEmpty());
	}

	// Req: weighted average buy price across a partial cancel + re-buy.
	@Test
	public void weightedAverageBuyPriceAcrossFragments()
	{
		event(BUY_SLOT, GrandExchangeOfferState.CANCELLED_BUY, WHIP, 4, 400, 100, 10);
		event(BUY_SLOT, GrandExchangeOfferState.EMPTY, WHIP, 0, 0, 0, 0);
		event(BUY_SLOT, GrandExchangeOfferState.BOUGHT, WHIP, 6, 660, 110, 6);

		List<OpenPositionEntry> positions = tracker.getOpenPositions();
		assertEquals(1, positions.size());
		assertEquals(10, positions.get(0).getQuantity());
		assertEquals((400 + 660) / 10, positions.get(0).getAvgBuyPrice());

		event(SELL_SLOT, GrandExchangeOfferState.SOLD, WHIP, 10, 1_200, 120, 10);
		CompletedFlip flip = onlyFlip();
		assertEquals(106, flip.avgBuyPrice);
		assertEquals(1_200 - 20 - 1_060, flip.profit);
	}

	// Req: sell price edit (cancel remainder + new offer) is stitched into
	// one flip via SellEpisode - no flip emitted for the first fragment.
	@Test
	public void sellPriceEditIsStitchedIntoOneFlip()
	{
		buyTenWhipsAt100();

		event(SELL_SLOT, GrandExchangeOfferState.SELLING, WHIP, 4, 480, 120, 10);
		event(SELL_SLOT, GrandExchangeOfferState.CANCELLED_SELL, WHIP, 4, 480, 120, 10);
		verify(flipLogger, never()).log(any());
		assertEquals(6, tracker.getOpenPositions().get(0).getQuantity());

		event(SELL_SLOT, GrandExchangeOfferState.EMPTY, WHIP, 0, 0, 0, 0);
		event(SELL_SLOT, GrandExchangeOfferState.SELLING, WHIP, 0, 0, 110, 6);
		event(SELL_SLOT, GrandExchangeOfferState.SOLD, WHIP, 6, 660, 110, 6);

		CompletedFlip flip = onlyFlip();
		assertEquals(10, flip.quantity);
		assertEquals((480 + 660) / 10, flip.avgSellPrice);
		assertEquals(4 * 2 + 6 * 2, flip.tax);
		assertEquals(480 + 660 - 20 - 1_000, flip.profit);
		assertEquals(1, tracker.getSessionFlipCount());
	}

	// Req: cancelling a sell that exhausts the position closes the episode.
	@Test
	public void cancelledSellThatExhaustsPositionIsFinal()
	{
		event(BUY_SLOT, GrandExchangeOfferState.BOUGHT, WHIP, 4, 400, 100, 4);
		event(SELL_SLOT, GrandExchangeOfferState.CANCELLED_SELL, WHIP, 4, 500, 125, 10);

		CompletedFlip flip = onlyFlip();
		assertEquals(4, flip.quantity);
		assertEquals(500 - 4 * 2 - 400, flip.profit);
	}

	// Req (error case): a sell with no tracked buy (pre-owned stock) is not a flip.
	@Test
	public void sellWithoutTrackedBuyIsIgnored()
	{
		event(SELL_SLOT, GrandExchangeOfferState.SOLD, WHIP, 5, 600, 120, 5);
		verify(flipLogger, never()).log(any());
		assertEquals(0, tracker.getSessionFlipCount());
	}

	// Req (edge): selling more than was tracked only matches the tracked qty.
	@Test
	public void sellLargerThanPositionMatchesTrackedQtyOnly()
	{
		event(BUY_SLOT, GrandExchangeOfferState.BOUGHT, WHIP, 3, 300, 100, 3);
		event(SELL_SLOT, GrandExchangeOfferState.SOLD, WHIP, 5, 600, 120, 5);

		CompletedFlip flip = onlyFlip();
		assertEquals(3, flip.quantity);
		assertEquals(3 * 120 - 3 * 2 - 300, flip.profit);
	}

	// Req: flip metadata - no local player -> null rsn; reference prices come
	// from PriceFeed at offer start (null when the feed has no data).
	@Test
	public void flipMetadataWithoutLiveClient()
	{
		when(priceFeed.getInstantSellPrice(anyInt())).thenReturn(95L);
		buyTenWhipsAt100();
		event(SELL_SLOT, GrandExchangeOfferState.SOLD, WHIP, 10, 1_200, 120, 10);

		CompletedFlip flip = onlyFlip();
		assertNull(flip.rsn);
		assertEquals(Long.valueOf(95L), flip.referenceBuyPrice);
		assertNull(flip.referenceSellPrice);
		assertEquals(flip.lastSellTime - flip.firstBuyTime, flip.totalDurationMs);
	}

	// --- Known gap fix: a cancelled partial sell that's never re-listed ---

	private long now = 1_700_000_000_000L;

	// The GE events of a sell price edit's first half: 4 of 10 sold at 120,
	// then the rest cancelled (the slot still holds the cancelled offer).
	private void sellFourThenCancel()
	{
		tracker.clock = () -> now;
		when(config.staleSellFlushMinutes()).thenReturn(10);
		buyTenWhipsAt100();
		event(SELL_SLOT, GrandExchangeOfferState.SELLING, WHIP, 4, 480, 120, 10);
		event(SELL_SLOT, GrandExchangeOfferState.CANCELLED_SELL, WHIP, 4, 480, 120, 10);
	}

	// Req (a): collected and not re-listed within staleSellFlushMinutes ->
	// one flip for the 4 sold; the 6 unsold stay an open position.
	@Test
	public void cancelledPartialSellClosesAfterTimeout()
	{
		sellFourThenCancel();
		tracker.onSlotEmptied(SELL_SLOT);

		now += 9 * 60_000L;
		tracker.onGameTick();
		verify(flipLogger, never()).log(any());

		now += 60_000L;
		tracker.onGameTick();
		CompletedFlip flip = onlyFlip();
		assertEquals(4, flip.quantity);
		assertEquals(100, flip.avgBuyPrice);
		assertEquals(120, flip.avgSellPrice);
		assertEquals(4 * 2, flip.tax);
		assertEquals(480 - 8 - 400, flip.profit);
		assertEquals(now, flip.lastSellTime);
		assertEquals(6, tracker.getOpenPositions().get(0).getQuantity());

		tracker.onGameTick();
		verify(flipLogger, times(1)).log(any());
	}

	// Req: re-listed in time -> still exactly one stitched flip, even when
	// the new offer sits unfilled past the timeout before selling.
	@Test
	public void relistedInTimeIsStillOneStitchedFlip()
	{
		sellFourThenCancel();
		tracker.onSlotEmptied(SELL_SLOT);

		now += 5 * 60_000L;
		event(SELL_SLOT, GrandExchangeOfferState.SELLING, WHIP, 0, 0, 110, 6);
		now += 20 * 60_000L;
		tracker.onGameTick();
		verify(flipLogger, never()).log(any());

		event(SELL_SLOT, GrandExchangeOfferState.SOLD, WHIP, 6, 660, 110, 6);
		CompletedFlip flip = onlyFlip();
		assertEquals(10, flip.quantity);
		assertEquals((480 + 660) / 10, flip.avgSellPrice);
		assertTrue(tracker.getOpenPositions().isEmpty());
	}

	// Req (b): logout/shutdown closes an open episode with the quantity
	// sold so far, whether or not the slot was collected.
	@Test
	public void logoutClosesOpenSellEpisode()
	{
		sellFourThenCancel();

		tracker.closeAllSellEpisodes();

		CompletedFlip flip = onlyFlip();
		assertEquals(4, flip.quantity);
		assertEquals(480 - 8 - 400, flip.profit);
		assertEquals(6, tracker.getOpenPositions().get(0).getQuantity());
		tracker.closeAllSellEpisodes();
		verify(flipLogger, times(1)).log(any());
	}

	// Req: finished buys are counted in the rolling 4h buy-limit window.
	@Test
	public void buysCountTowardsTheFourHourWindow()
	{
		tracker.clock = () -> now;
		buyTenWhipsAt100();
		long window = 4 * 60 * 60_000L;
		assertEquals(10, tracker.getBoughtInWindow(WHIP, now, window));
		assertEquals(0, tracker.getBoughtInWindow(BOND, now, window));
		assertEquals(0, tracker.getBoughtInWindow(WHIP, now + window, window));
	}
}
