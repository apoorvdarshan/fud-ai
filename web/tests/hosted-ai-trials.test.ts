import { describe, it, expect } from 'vitest';
import { MemoryLedgerStore } from './hosted-ai-harness';
import { parseRevenueCatSubscriber, quotaFromRow, voiceQuotaFromRow, spendFromLedger, spendVoiceBlock, loadVerifiedLedger, EntitlementLookupError, type LedgerContext } from '../hosted-ai-ledger';

const now = new Date('2026-11-04T12:00:00Z');
const end = '2026-11-07T12:00:00Z';
function subscriber(plan: string, period: string) {
  return { subscriber: { entitlements: { [plan]: { product_identifier: `${plan}.yearly`, expires_date: end } }, subscriptions: { [`${plan}.yearly`]: { period_type: period, expires_date: end } } } };
}

describe('yearly trial metering', () => {
  it('uses the subscription belonging to the active entitlement, with Pro precedence', () => {
    expect(parseRevenueCatSubscriber(subscriber('plus', 'trial'), now)).toEqual({ plan: 'plus', creditsGranted: 0, trialExpiresAt: end });
    expect(parseRevenueCatSubscriber(subscriber('pro', 'normal'), now)).toEqual({ plan: 'pro', creditsGranted: 0 });
    const payload = subscriber('plus', 'trial');
    Object.assign(payload.subscriber.entitlements, { pro: { product_identifier: 'pro.yearly', expires_date: end } });
    Object.assign(payload.subscriber.subscriptions, { 'pro.yearly': { period_type: 'normal', expires_date: end } });
    expect(parseRevenueCatSubscriber(payload, now).trialExpiresAt).toBeUndefined();
    expect(parseRevenueCatSubscriber(subscriber('plus', 'trial'), new Date('2026-11-08')).plan).toBe('none');
  });

  for (const [plan, seconds] of [['plus', 120], ['pro', 300]] as const) {
    it(`${plan}: caps actions and voice, spends credits, resets daily, converts to paid`, async () => {
      const store = new MemoryLedgerStore();
      let clock = now;
      const ctx: LedgerContext = { store, now: () => clock, verify: async () => ({ plan, creditsGranted: 50 }) };
      let row = await store.upsertEntitlement('user', { plan, creditsGranted: 50, trialExpiresAt: end }, now.toISOString());
      expect(quotaFromRow(row, now).dailyLimit).toBe(20);
      expect(voiceQuotaFromRow(row, now).secondsLimit).toBe(seconds);
      expect((await spendFromLedger(ctx, 'user', row, 21)).status).toBe('spent');
      row = (await store.get('user'))!;
      expect(row.credits_spent).toBe(1);
      for (let i = 0; i < seconds / 15 + 1; i++) {
        const spent = await spendVoiceBlock(ctx, 'user', row);
        expect(spent.status).toBe('spent');
        if (spent.status === 'spent') row = spent.row;
      }
      expect(row.credits_spent).toBe(2);
      clock = new Date('2026-11-05T00:00:00Z');
      expect(voiceQuotaFromRow(row, clock).secondsUsed).toBe(0);
      expect(quotaFromRow(row, clock).dailyUsed).toBe(0);
      row = await loadVerifiedLedger(ctx, 'user', 'user', true);
      expect(quotaFromRow(row, clock).dailyLimit).toBe(plan === 'plus' ? 30 : 60);
      expect(voiceQuotaFromRow(row, clock).secondsLimit).toBe(plan === 'plus' ? 1200 : 6000);
      expect(voiceQuotaFromRow(row, clock).secondsUsed).toBe(0);
      expect(row.credits_spent).toBe(2);
    });
  }

  it('reverifies at trial expiry even inside TTL; outage never promotes to paid limits', async () => {
    const store = new MemoryLedgerStore();
    const time = new Date(end);
    await store.upsertEntitlement('user', { plan: 'pro', creditsGranted: 0, trialExpiresAt: end }, new Date(time.getTime() - 1000).toISOString());
    let called = false;
    const ctx: LedgerContext = { store, now: () => time, verify: async () => { called = true; throw new EntitlementLookupError('offline'); } };
    const row = await loadVerifiedLedger(ctx, 'user', 'user');
    expect(called).toBe(true);
    expect(quotaFromRow(row, time).dailyLimit).toBe(20);
    expect(voiceQuotaFromRow(row, time).secondsLimit).toBe(300);
  });

  it('older verification cannot restore a trial after paid conversion', async () => {
    const store = new MemoryLedgerStore();
    await store.upsertEntitlement('user', { plan: 'pro', creditsGranted: 0 }, end);
    const row = await store.upsertEntitlement('user', { plan: 'pro', creditsGranted: 0, trialExpiresAt: end }, now.toISOString());
    expect(row.trial_expires_at).toBeNull();
  });
});
