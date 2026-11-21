package com.flippr.osrs;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.when;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import java.awt.event.MouseWheelEvent;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import net.runelite.api.Client;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.mockito.Spy;

// Requirement: the Plan tab's Plan setup works like the site / RS3 app -
// capital shorthand with an "= N gp" preview, the form opens with the
// plan's settings (F2P only without Premium), the build body the site's
// POST /api/v1/osrs/plan/build takes (slots never below the ones in use + 1,
// max per slot left out when Off), inline errors; the plan switches PATCH
// /api/v1/osrs/plan/settings with one field (absent = on). Also: the sidebar
// scrolls ~20px per wheel unit, about a screen per block, and a nested
// scroll pane hands the wheel to the outer one at its edge.
public class PlanSetupTest
{
	@Mock
	private WebsiteConnection website;
	@Mock
	private Client client;
	@Mock
	private FlipprConfig config;
	@Mock
	private InventoryTracker inventoryTracker;
	@Mock
	private FlipTracker flipTracker;
	@Mock
	private PriceFeed priceFeed;
	@Spy
	private Gson gson = new Gson();
	@InjectMocks
	private ServerSuggestions suggestions;

	private AutoCloseable mocks;
	private final List<String> sent = new ArrayList<>();
	private WebsiteConnection.ApiResponse answer;

	@Before
	public void setUp()
	{
		mocks = MockitoAnnotations.openMocks(this);
		when(website.isSyncEnabled()).thenReturn(true);
		answer = new WebsiteConnection.ApiResponse(WebsiteConnection.Outcome.OK, 200, "{}");
		doAnswer(inv ->
		{
			sent.add("POST " + inv.getArgument(0) + " " + inv.getArgument(1));
			((WebsiteConnection.ApiCallback) inv.getArgument(2)).onResult(answer);
			return null;
		}).when(website).postJsonAsync(anyString(), anyString(), any());
		doAnswer(inv ->
		{
			sent.add("PATCH " + inv.getArgument(0) + " " + inv.getArgument(1));
			((WebsiteConnection.ApiCallback) inv.getArgument(2)).onResult(answer);
			return null;
		}).when(website).patchJsonAsync(anyString(), anyString(), any());
	}

	@After
	public void tearDown() throws Exception
	{
		mocks.close();
	}

	// Req: capital shorthand like the site.
	@Test
	public void capitalShorthand()
	{
		assertEquals(Long.valueOf(24_000_000L), PlanSetup.parseCapital("24m"));
		assertEquals(Long.valueOf(5_600_000_000L), PlanSetup.parseCapital("5.6b"));
		assertEquals(Long.valueOf(1_000_000L), PlanSetup.parseCapital("1kk"));
		assertEquals(Long.valueOf(1_000_000_000L), PlanSetup.parseCapital("1kkk"));
		assertEquals(Long.valueOf(290_000L), PlanSetup.parseCapital("0.29m"));
		assertEquals(Long.valueOf(15_000_000L), PlanSetup.parseCapital(" 15,000,000 gp "));
		assertEquals(Long.valueOf(5_600_000_000L), PlanSetup.parseCapital("5.6bil"));
		assertEquals(Long.valueOf(2_500_000L), PlanSetup.parseCapital("2.5 mill"));
		assertEquals(Long.valueOf(500L), PlanSetup.parseCapital(".5k"));
		for (String bad : new String[]{"", "abc", "1.2.3m", "-5m", "5x", "20000b", null})
		{
			assertNull(bad, PlanSetup.parseCapital(bad));
		}
		assertEquals("1.5m", PlanSetup.formatCapitalInput(1_500_000));
		assertEquals("250k", PlanSetup.formatCapitalInput(250_000));
		assertEquals("2b", PlanSetup.formatCapitalInput(2_000_000_000L));
		assertEquals("1,234,567", PlanSetup.formatCapitalInput(1_234_567));
		assertEquals("999", PlanSetup.formatCapitalInput(999));
	}

	// Req: the "= N gp" preview.
	@Test
	public void capitalHint()
	{
		assertEquals("= 24,000,000 gp", PlanSetup.capitalHint("24m").text);
		assertEquals(Long.valueOf(24_000_000L), PlanSetup.capitalHint("24m").gp);
		assertTrue(PlanSetup.capitalHint("nope").invalid);
		assertEquals("Not a valid amount.", PlanSetup.capitalHint("nope").text);
		assertFalse(PlanSetup.capitalHint(" ").invalid);
		assertNull(PlanSetup.capitalHint("").gp);
	}

	private static ServerSuggestions.PlanInfo plan()
	{
		ServerSuggestions.PlanInfo p = new ServerSuggestions.PlanInfo();
		p.id = "p1";
		p.capital = 24_000_000;
		p.slots = 6;
		p.style = "highTicket";
		p.fillWindowHours = 12.0;
		p.maxPerSlotPct = 33.0;
		p.account = "members";
		p.reinvest = true;
		return p;
	}

	// Req: the form opens with the plan's settings; defaults without one; F2P without Premium.
	@Test
	public void initialForm()
	{
		PlanSetup.Form f = PlanSetup.initialForm(plan(), true);
		assertEquals("24m", f.capital);
		assertEquals(6, f.slots);
		assertEquals("highTicket", f.style);
		assertEquals(12.0, f.fillWindowHours, 0);
		assertEquals(Integer.valueOf(33), f.maxPerSlotPct);
		assertEquals("members", f.account);
		assertTrue(f.reinvest);
		assertEquals("f2p", PlanSetup.initialForm(plan(), false).account);

		PlanSetup.Form d = PlanSetup.initialForm(null, true);
		assertEquals("", d.capital);
		assertEquals(8, d.slots);
		assertEquals("volume", d.style);
		assertEquals(4.0, d.fillWindowHours, 0);
		assertNull(d.maxPerSlotPct);
		assertEquals("both", d.account);
		assertFalse(d.reinvest);
	}

	// Req: the build body; validation errors inline.
	@Test
	public void buildBody()
	{
		PlanSetup.Form f = PlanSetup.initialForm(plan(), true);
		Map<String, Object> body = PlanSetup.buildRequest(f, true, 0).body;
		JsonObject json = gson.fromJson(gson.toJson(body), JsonObject.class);
		assertEquals(24_000_000L, json.get("capital").getAsLong());
		assertEquals(6, json.get("slots").getAsInt());
		assertEquals("highTicket", json.get("style").getAsString());
		assertEquals(12.0, json.get("fillWindowHours").getAsDouble(), 0);
		assertEquals(33, json.get("maxPerSlotPct").getAsInt());
		assertEquals("members", json.get("account").getAsString());
		assertTrue(json.get("reinvest").getAsBoolean());

		f.maxPerSlotPct = null;
		f.slots = 2;
		Map<String, Object> rebuilt = PlanSetup.buildRequest(f, false, 4).body;
		assertFalse("Off: left out", rebuilt.containsKey("maxPerSlotPct"));
		assertEquals("in use 4 -> at least 5", 5, rebuilt.get("slots"));
		assertEquals("no Premium: F2P", "f2p", rebuilt.get("account"));

		f.capital = "lots";
		assertEquals(PlanSetup.CAPITAL_ERROR, PlanSetup.buildRequest(f, true, 0).error);
		f.capital = "0";
		assertEquals("Capital must be at least 1 gp.", PlanSetup.buildRequest(f, true, 0).error);
		assertEquals(8, PlanSetup.minSlots(9));
		assertEquals(1, PlanSetup.minSlots(0));
	}

	// Req: slots in use counted from positions in progress.
	@Test
	public void slotsInUse()
	{
		List<ServerSuggestions.PlanPosition> list = new ArrayList<>();
		for (String status : new String[]{"pending", "buying", "bought", "selling", "done", null})
		{
			ServerSuggestions.PlanPosition p = new ServerSuggestions.PlanPosition();
			p.status = status;
			list.add(p);
		}
		assertEquals(3, PlanSetup.countSlotsInUse(list));
		assertEquals(0, PlanSetup.countSlotsInUse(null));
	}

	// Req: switches read the plan (absent = on) and PATCH one field.
	@Test
	public void switches()
	{
		ServerSuggestions.PlanInfo p = plan();
		assertTrue(PlanSetup.autoSellPrice(p));
		assertTrue(PlanSetup.autoFill(p));
		p.autoSellPrice = false;
		p.autoFill = false;
		assertFalse(PlanSetup.autoSellPrice(p));
		assertFalse(PlanSetup.autoFill(p));

		List<ServerSuggestions.StopResult> results = new ArrayList<>();
		suggestions.setPlanSetting("autoFill", false, results::add);
		assertEquals("PATCH /api/v1/osrs/plan/settings {\"autoFill\":false}", sent.get(0));
		assertTrue(results.get(0).ok);
	}

	// Req: Build posts the body to /plan/build; the site's error is shown.
	@Test
	public void buildRequestAndError()
	{
		List<ServerSuggestions.StopResult> results = new ArrayList<>();
		suggestions.buildPlan(PlanSetup.buildRequest(PlanSetup.initialForm(plan(), true), true, 0).body, results::add);
		assertTrue(sent.get(0).startsWith("POST /api/v1/osrs/plan/build {\"capital\":24000000,\"slots\":6,"));
		assertTrue(results.get(0).ok);

		answer = new WebsiteConnection.ApiResponse(WebsiteConnection.Outcome.FAILED, 400, "{\"error\":\"capital must be at least 1 gp.\"}");
		suggestions.buildPlan(PlanSetup.buildRequest(PlanSetup.initialForm(plan(), true), true, 0).body, results::add);
		assertFalse(results.get(1).ok);
		assertEquals("capital must be at least 1 gp.", results.get(1).error);

		answer = new WebsiteConnection.ApiResponse(WebsiteConnection.Outcome.FAILED, 500, "oops");
		suggestions.setPlanSetting("autoSellPrice", true, results::add);
		assertEquals("Couldn't change the setting (HTTP 500).", results.get(2).error);

		when(website.isSyncEnabled()).thenReturn(false);
		sent.clear();
		suggestions.buildPlan(PlanSetup.buildRequest(PlanSetup.initialForm(plan(), true), true, 0).body, results::add);
		assertTrue(sent.isEmpty());
		assertFalse(results.get(3).ok);
	}

	// Req: scroll increments - ~20px per unit, about one screen per block, kept on resize.
	@Test
	public void scrollIncrements()
	{
		JPanel content = new JPanel();
		content.setPreferredSize(new java.awt.Dimension(200, 3000));
		JScrollPane pane = new JScrollPane(content);
		SidebarScroll.apply(pane);
		assertEquals(SidebarScroll.UNIT_INCREMENT, pane.getVerticalScrollBar().getUnitIncrement());
		assertTrue(SidebarScroll.UNIT_INCREMENT >= 16 && SidebarScroll.UNIT_INCREMENT <= 24);
		assertEquals(480, SidebarScroll.blockIncrement(500));
		assertEquals(SidebarScroll.UNIT_INCREMENT, SidebarScroll.blockIncrement(0));
		pane.setSize(220, 600);
		pane.doLayout();
		pane.getViewport().setSize(200, 600);
		pane.getViewport().dispatchEvent(new java.awt.event.ComponentEvent(pane.getViewport(), java.awt.event.ComponentEvent.COMPONENT_RESIZED));
		assertEquals(SidebarScroll.blockIncrement(600), pane.getVerticalScrollBar().getBlockIncrement());
	}

	// Req: wheel at a nested pane's edge goes to the outer pane.
	@Test
	public void nestedWheelForwarding()
	{
		assertTrue(SidebarScroll.canScroll(0, 100, 0, 500, 1));
		assertFalse(SidebarScroll.canScroll(400, 100, 0, 500, 1));
		assertFalse(SidebarScroll.canScroll(0, 100, 0, 500, -1));
		assertTrue(SidebarScroll.canScroll(10, 100, 0, 500, -1));
		assertFalse(SidebarScroll.canScroll(10, 100, 0, 500, 0));

		JPanel innerContent = new JPanel();
		innerContent.setPreferredSize(new java.awt.Dimension(100, 50));
		JScrollPane inner = new JScrollPane(innerContent);
		JPanel outerContent = new JPanel();
		outerContent.add(inner);
		JScrollPane outer = new JScrollPane(outerContent);
		List<MouseWheelEvent> outerGot = new ArrayList<>();
		outer.addMouseWheelListener(outerGot::add);
		SidebarScroll.forwardAtEdge(inner);
		assertFalse(inner.isWheelScrollingEnabled());
		// The inner content fits: nothing to scroll (value 0, extent 50 of 50).
		inner.getVerticalScrollBar().getModel().setRangeProperties(0, 50, 0, 50, false);
		MouseWheelEvent down = new MouseWheelEvent(inner, MouseWheelEvent.MOUSE_WHEEL, 0, 0, 5, 5, 0, false,
			MouseWheelEvent.WHEEL_UNIT_SCROLL, 3, 1);
		for (java.awt.event.MouseWheelListener l : inner.getMouseWheelListeners())
		{
			l.mouseWheelMoved(down);
		}
		assertEquals("inner can't scroll: the outer pane gets it", 1, outerGot.size());
		assertEquals(Arrays.asList(1), Arrays.asList(outerGot.get(0).getWheelRotation()));

		// Room to scroll: the inner pane moves, the outer one gets nothing.
		inner.getVerticalScrollBar().getModel().setRangeProperties(0, 50, 0, 200, false);
		for (java.awt.event.MouseWheelListener l : inner.getMouseWheelListeners())
		{
			l.mouseWheelMoved(down);
		}
		assertTrue("the inner pane moved", inner.getVerticalScrollBar().getValue() > 0);
		assertEquals(1, outerGot.size());
	}
}
