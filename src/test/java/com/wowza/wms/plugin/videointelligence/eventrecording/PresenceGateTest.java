package com.wowza.wms.plugin.videointelligence.eventrecording;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Test;

import com.wowza.wms.plugin.videointelligence.eventrecording.PresenceGate.End;
import com.wowza.wms.plugin.videointelligence.eventrecording.PresenceGate.Seen;
import com.wowza.wms.plugin.videointelligence.eventrecording.PresenceGate.Settings;
import com.wowza.wms.plugin.videointelligence.eventrecording.PresenceGate.Start;
import com.wowza.wms.plugin.videointelligence.eventrecording.PresenceGate.Transition;

public class PresenceGateTest
{
	private static final List<Seen> NOTHING = List.of();
	private static final List<Seen> PERSON = List.of(new Seen("person", 7));
	private static final List<Seen> UNTRACKED_PERSON = List.of(new Seen("person", null));

	private static Settings settings(long lostMs, long mergeGapMs)
	{
		return new Settings(Set.of("person", "car"), 1, false, lostMs, mergeGapMs, null);
	}

	@Test
	public void anEventSpansFromTheFirstMatchUntilLostMsAfterTheLast()
	{
		PresenceGate gate = new PresenceGate(settings(3000, 0));
		assertEquals(List.of(new Start(1000, Set.of("person"), Set.of(7))), gate.observe(1000, PERSON));
		assertEquals(List.of(), gate.observe(2000, PERSON));
		// Exactly lost_ms after the last match is not yet lost; one tick later it is.
		assertEquals(List.of(), gate.observe(5000, NOTHING));
		assertEquals(List.of(new End(1000, 5000, Set.of("person"), Set.of(7))), gate.observe(5001, NOTHING));
		assertFalse(gate.active());
	}

	@Test
	public void aFlickerShorterThanLostMsDoesNotSplitTheEvent()
	{
		PresenceGate gate = new PresenceGate(settings(3000, 0));
		gate.observe(0, PERSON);
		assertEquals(List.of(), gate.observe(1000, NOTHING));
		assertEquals(List.of(), gate.observe(2500, NOTHING));
		assertEquals(List.of(), gate.observe(2900, PERSON));
		assertEquals(List.of(), gate.observe(5000, NOTHING));
		assertEquals(List.of(new End(0, 5900, Set.of("person"), Set.of(7))), gate.observe(6000, NOTHING));
	}

	@Test
	public void aMatchInsideTheMergeGapContinuesTheEventAndTheEndIsHeldBackForIt()
	{
		PresenceGate gate = new PresenceGate(settings(1000, 4000));
		gate.observe(0, PERSON);
		// Lost at 1001, but the gap keeps the end open until 5000.
		assertEquals(List.of(), gate.observe(3000, NOTHING));
		assertEquals(List.of(), gate.observe(4500, PERSON));
		assertEquals(List.of(), gate.observe(9000, NOTHING));
		assertEquals(List.of(new End(0, 5500, Set.of("person"), Set.of(7))), gate.observe(9501, NOTHING));
	}

	@Test
	public void withoutTheMergeGapTwoPresencesAreTwoEvents()
	{
		PresenceGate gate = new PresenceGate(settings(1000, 0));
		gate.observe(0, PERSON);
		assertEquals(List.of(new End(0, 1000, Set.of("person"), Set.of(7))), gate.observe(3000, NOTHING));
		assertEquals(List.of(new Start(4500, Set.of("person"), Set.of(7))), gate.observe(4500, PERSON));
	}

	@Test
	public void anUntrackedDetectionCountsUnlessATrackIsRequired()
	{
		PresenceGate counting = new PresenceGate(new Settings(Set.of("person"), 1, false, 1000, 0, null));
		assertEquals(List.of(new Start(0, Set.of("person"), Set.of())), counting.observe(0, UNTRACKED_PERSON));

		PresenceGate tracked = new PresenceGate(new Settings(Set.of("person"), 1, true, 1000, 0, null));
		assertEquals(List.of(), tracked.observe(0, UNTRACKED_PERSON));
		assertFalse(tracked.active());
		assertEquals(List.of(new Start(100, Set.of("person"), Set.of(7))), tracked.observe(100, PERSON));
	}

	@Test
	public void minCountNeedsThatManyMatchingDetectionsInOneResponse()
	{
		PresenceGate gate = new PresenceGate(new Settings(Set.of("person"), 2, false, 1000, 0, null));
		assertEquals(List.of(), gate.observe(0, List.of(new Seen("person", 1), new Seen("car", 2))));
		assertEquals(List.of(new Start(10, Set.of("person"), Set.of(1, 3))),
				gate.observe(10, List.of(new Seen("person", 1), new Seen("person", 3), new Seen("car", 2))));
	}

	@Test
	public void classesMatchCaseInsensitivelyAndAllMatchesEverything()
	{
		PresenceGate named = new PresenceGate(new Settings(Set.of("Person"), 1, false, 1000, 0, null));
		assertTrue(named.observe(0, List.of(new Seen("PERSON", null))).get(0) instanceof Start);

		PresenceGate all = new PresenceGate(new Settings(Set.of("all"), 1, false, 1000, 0, null));
		assertTrue(all.observe(0, List.of(new Seen("giraffe", null))).get(0) instanceof Start);

		PresenceGate empty = new PresenceGate(new Settings(Set.of(), 1, false, 1000, 0, null));
		assertTrue(empty.observe(0, List.of(new Seen("giraffe", null))).get(0) instanceof Start);
	}

	@Test
	public void aClockThatJumpsBackEndsTheEventWhereItWasLastSeen()
	{
		PresenceGate gate = new PresenceGate(settings(3000, 0));
		gate.observe(10_000, PERSON);
		gate.observe(12_000, PERSON);
		List<Transition> transitions = gate.observe(500, PERSON);
		assertEquals(List.of(new End(10_000, 12_000, Set.of("person"), Set.of(7)),
				new Start(500, Set.of("person"), Set.of(7))), transitions);
	}

	@Test
	public void maxClipClosesALongEventAndOpensTheNextOneAtOnce()
	{
		PresenceGate gate = new PresenceGate(new Settings(Set.of("person"), 1, false, 3000, 0, 10_000L));
		gate.observe(0, PERSON);
		for (long atMs : new long[] {3000, 6000, 9000, 9_999})
			assertEquals(List.of(), gate.observe(atMs, PERSON));
		assertEquals(List.of(new End(0, 10_000, Set.of("person"), Set.of(7), true),
				new Start(10_500, Set.of("person"), Set.of(7))), gate.observe(10_500, PERSON));
		// A cap reached while nothing matches closes the event and opens nothing.
		for (long atMs : new long[] {13_000, 15_500, 18_000, 20_400})
			assertEquals(List.of(), gate.observe(atMs, PERSON));
		assertEquals(List.of(new End(10_500, 20_500, Set.of("person"), Set.of(7), true)),
				gate.observe(20_600, NOTHING));
		assertFalse(gate.active());
	}

	@Test
	public void aLossOlderThanTheCapEndsTheEventWhereItWasLost()
	{
		PresenceGate gate = new PresenceGate(new Settings(Set.of("person"), 1, false, 3000, 0, 10_000L));
		gate.observe(0, PERSON);
		assertEquals(List.of(new End(0, 3000, Set.of("person"), Set.of(7))), gate.observe(21_000, NOTHING));
		assertFalse(gate.active());
	}

	@Test
	public void twoPresencesWithNoResponseBetweenThemAreTwoEvents()
	{
		PresenceGate gate = new PresenceGate(settings(3000, 0));
		gate.observe(0, PERSON);
		gate.observe(2000, PERSON);
		// A host that withholds empty responses says nothing for the 28 s in between.
		assertEquals(List.of(new End(0, 5000, Set.of("person"), Set.of(7)),
				new Start(30_000, Set.of("person"), Set.of(7))), gate.observe(30_000, PERSON));
		assertTrue(gate.active());
	}

	@Test
	public void theOpenEventAndWhetherTheLastResponseMatchedCanBeAsked()
	{
		PresenceGate gate = new PresenceGate(settings(3000, 0));
		assertEquals(null, gate.open());
		assertFalse(gate.matchedLast());
		gate.observe(1000, PERSON);
		assertTrue(gate.matchedLast());
		gate.observe(2000, NOTHING);
		assertFalse(gate.matchedLast());
		assertEquals(new Start(1000, Set.of("person"), Set.of(7)), gate.open());
	}

	@Test
	public void closingAnOpenEventEndsItLostMsAfterTheLastMatch()
	{
		PresenceGate gate = new PresenceGate(settings(3000, 0));
		gate.observe(0, PERSON);
		gate.observe(2000, PERSON);
		assertEquals(List.of(new End(0, 5000, Set.of("person"), Set.of(7))), gate.close());
		assertEquals(List.of(), gate.close());
	}

	@Test
	public void theEventRemembersEveryClassAndTrackItSaw()
	{
		PresenceGate gate = new PresenceGate(settings(3000, 0));
		gate.observe(0, List.of(new Seen("person", 1)));
		gate.observe(1000, List.of(new Seen("car", 2), new Seen("dog", 3)));
		End end = (End)gate.close().get(0);
		assertEquals(Set.of("person", "car"), end.classes());
		assertEquals(Set.of(1, 2), end.trackIds());
	}

	@Test
	public void theSettingsRefuseWhatCannotWork()
	{
		assertThrows(IllegalArgumentException.class, () -> new Settings(Set.of(), 0, false, 1000, 0, null));
		assertThrows(IllegalArgumentException.class, () -> new Settings(Set.of(), 1, false, -1, 0, null));
		assertThrows(IllegalArgumentException.class, () -> new Settings(Set.of(), 1, false, 1000, 0, 0L));
	}
}
