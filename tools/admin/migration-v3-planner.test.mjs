import test from 'node:test';
import assert from 'node:assert/strict';
import { allocatedCost, decimalCents, planMigration } from './migration-v3-planner.mjs';

function fixture({ total = 101, cost = 67, original = 81, amounts = [27, 27, 27] } = {}) {
  return {
    customers: [{ id: 'customer', data: { id: 'customer', nome: 'Cliente', telefone: '123' } }],
    accounts: [{ id: 'sale', data: { venda_id: 'sale', cliente_id: 'customer', original_cents: original,
      settled_cents: amounts.reduce((sum, value) => sum + value, 0) } }],
    sales: [{ id: 'sale', data: { id: 'sale', tipo: 'SAIDA', cliente_id: 'customer', total_cents: total,
      profit_cents: total - cost, receivable_cents: original, Itens: [{ quantidade: 1, historical_cost_cents: cost }] } }],
    receipts: amounts.map((amount, index) => ({ id: `receipt-${index}`, data: { id: `receipt-${index}`, venda_id: 'sale',
      cliente_id: 'customer', amount_cents: amount, metodo: index === 0 ? 'CASH' : 'PIX', dataHora: { seconds: index + 1, nanoseconds: 0 } } })),
    reversals: [],
  };
}

function applyPlan(data, plan) {
  const copy = structuredClone(data);
  for (const account of plan.accounts) {
    Object.assign(copy.accounts.find(record => record.id === account.id).data, account.patch);
    for (const receipt of account.receipts) Object.assign(copy.receipts.find(record => record.id === receipt.id).data, receipt.patch);
  }
  for (const customer of plan.customers) Object.assign(copy.customers.find(record => record.id === customer.id).data, customer.patch);
  return copy;
}

test('partial receipts recognize only their cumulative historical cost and preview does not mutate input', () => {
  const data = fixture({ amounts: [27, 27] });
  const before = structuredClone(data);
  const plan = planMigration(data);
  assert.deepEqual(data, before);
  assert.equal(plan.customers[0].patch.balance_cents, 27);
  assert.equal(plan.accounts[0].patch.closed, false);
  assert.deepEqual(plan.accounts[0].patch.settled_by_method_cents, { CASH: 27, PIX: 27, DEBIT: 0, CREDIT: 0 });
  assert.deepEqual(plan.accounts[0].receipts.map(receipt => receipt.patch), [
    { paid_before_cents: 20, cost_recognized_cents: 18 },
    { paid_before_cents: 47, cost_recognized_cents: 18 },
  ]);
});

test('full settlement retains every cent over three installments', () => {
  const plan = planMigration(fixture({ total: 3, cost: 1, original: 3, amounts: [1, 1, 1] }));
  assert.deepEqual(plan.accounts[0].receipts.map(receipt => receipt.patch.cost_recognized_cents), [0, 1, 0]);
  assert.equal(plan.accounts[0].patch.closed, true);
  assert.equal(plan.customers[0].patch.balance_cents, 0);
});

test('repeated migration proposes zero writes and preserves original data', () => {
  const source = fixture();
  const migrated = applyPlan(source, planMigration(source));
  const second = planMigration(migrated);
  assert.deepEqual(second.customers[0].patch, {});
  assert.deepEqual(second.accounts[0].patch, {});
  assert.ok(second.accounts[0].receipts.every(receipt => Object.keys(receipt.patch).length === 0));
  assert.deepEqual(migrated.sales, source.sales);
  assert.equal(migrated.customers[0].data.telefone, source.customers[0].data.telefone);
});

test('reversal cancels only remaining debt and retains already received totals', () => {
  const data = fixture({ amounts: [27] });
  data.reversals.push({ id: 'sale', data: { venda_id: 'sale', expected_settled_cents: 27, cancelled_receivable_cents: 54 } });
  const plan = planMigration(data);
  assert.equal(plan.customers[0].patch.balance_cents, 0);
  assert.equal(plan.accounts[0].patch.cancelled_cents, 54);
  assert.equal(plan.accounts[0].patch.estorno_id, 'sale');
  assert.equal(plan.accounts[0].patch.closed, true);
  assert.equal(plan.accounts[0].patch.settled_by_method_cents.CASH, 27);
});

test('existing transaction order wins over device timestamp order', () => {
  const data = fixture({ total: 3, cost: 1, original: 3, amounts: [1, 1, 1] });
  const recognized = [0, 1, 0];
  data.receipts.forEach((receipt, index) => {
    receipt.data.paid_before_cents = index;
    receipt.data.cost_recognized_cents = recognized[index];
    receipt.data.dataHora.seconds = 10 - index;
  });
  const plan = planMigration(data);
  assert.ok(plan.accounts[0].receipts.every(receipt => Object.keys(receipt.patch).length === 0));
});

test('legacy receipts fill a gap before an existing transaction snapshot', () => {
  const data = fixture({ total: 3, cost: 1, original: 3, amounts: [1, 1, 1] });
  data.receipts[2].data.paid_before_cents = 2;
  data.receipts[2].data.cost_recognized_cents = 0;
  assert.deepEqual(planMigration(data).accounts[0].receipts.map(receipt => receipt.id), ['receipt-0', 'receipt-1', 'receipt-2']);
});

test('inconsistent balance, orphan customer, missing payment and wrong cost abort', () => {
  const badBalance = fixture(); badBalance.accounts[0].data.settled_cents--;
  assert.throws(() => planMigration(badBalance), /comprovantes.*não somam/);
  const missingCustomer = fixture(); missingCustomer.customers = [];
  assert.throws(() => planMigration(missingCustomer), /cliente ausente/);
  const missingAccount = fixture(); missingAccount.accounts = [];
  assert.throws(() => planMigration(missingAccount), /conta a receber ausente/);
  const badCost = fixture(); badCost.sales[0].data.Itens[0].historical_cost_cents++;
  assert.throws(() => planMigration(badCost), /CMV dos itens difere/);
});

test('preexisting aggregate, method totals, receipt snapshot and reversal mismatches abort', () => {
  const balance = fixture(); balance.customers[0].data.balance_cents = 1;
  assert.throws(() => planMigration(balance), /saldo agregado existente/);
  const methods = fixture(); methods.accounts[0].data.settled_by_method_cents = { CASH: 81, PIX: 0, DEBIT: 0, CREDIT: 0 };
  assert.throws(() => planMigration(methods), /resumo existente/);
  const snapshot = fixture(); snapshot.receipts[0].data.cost_recognized_cents = 99;
  assert.throws(() => planMigration(snapshot), /CMV já registrado/);
  const reversal = fixture(); reversal.reversals.push({ id: 'sale', data: { venda_id: 'sale', expected_settled_cents: 0, cancelled_receivable_cents: 81 } });
  assert.throws(() => planMigration(reversal), /estorno incompatível/);
});

test('overlapping paid_before intervals are never rewritten', () => {
  const data = fixture();
  data.receipts[0].data.paid_before_cents = 20;
  data.receipts[1].data.paid_before_cents = 20;
  assert.throws(() => planMigration(data), /sobrepostos ou faltantes/);
});

test('legacy decimal and large allocation use exact HALF_UP without binary rounding loss', () => {
  assert.equal(decimalCents(1.005), 101);
  assert.equal(decimalCents(-1.005), -101);
  assert.equal(decimalCents(1e-7), 0);
  assert.equal(decimalCents(12.34), 1234);
  assert.equal(allocatedCost(99_999_999, 99_999_998, 99_999_999), 99_999_998);
  assert.equal(allocatedCost(150, 50, 100), 75);
});

test('an interrupted migration can resume after account transactions and before customer balances', () => {
  const original = fixture({ amounts: [27] });
  const plan = planMigration(original);
  const partial = applyPlan(original, { accounts: plan.accounts, customers: [] });
  const resumed = planMigration(partial);
  assert.deepEqual(resumed.accounts[0].patch, {});
  assert.ok(resumed.accounts[0].receipts.every(receipt => Object.keys(receipt.patch).length === 0));
  assert.equal(resumed.customers[0].patch.balance_cents, 54);
});
