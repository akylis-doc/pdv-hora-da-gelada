import { after, before, beforeEach, test } from 'node:test';
import assert from 'node:assert/strict';
import { readFile } from 'node:fs/promises';
import { randomUUID } from 'node:crypto';
import { initializeTestEnvironment, assertFails, assertSucceeds } from '@firebase/rules-unit-testing';
import { doc, getDoc, setDoc, updateDoc, deleteDoc, writeBatch, increment, Timestamp, serverTimestamp, runTransaction } from 'firebase/firestore';

let environment;
let db;
let products;

function product(id, stock = 10) {
  return { id, nome: 'Cerveja lata', codigo_barras: '7890000000000', estoque_atual: stock,
    preco_custo_atual: 3, preco_venda_atual: 5, cost_cents: 300, sale_cents: 500 };
}
function movement(ids = [products[0]], type = 'SAIDA', quantity = 2) {
  const items = ids.map(id => ({ produtoId: id, nome_snapshot: 'Cerveja lata', quantidade: quantity,
    precoUnitarioVendido: type === 'SAIDA' ? 5 : 3, custoUnitarioHistorico: 3,
    sale_unit_cents: type === 'SAIDA' ? 500 : 300, historical_cost_cents: 300 }));
  const total = items.reduce((sum, item) => sum + item.quantidade * (type === 'SAIDA' ? item.sale_unit_cents : item.historical_cost_cents), 0);
  const profit = type === 'SAIDA' ? items.reduce((sum, item) => sum + item.quantidade * (item.sale_unit_cents - item.historical_cost_cents), 0) : 0;
  return { id: randomUUID(), dataHora: Timestamp.now(), tipo: type, valorTotal: total / 100,
    lucroTotal: profit / 100, total_cents: total, profit_cents: profit, produtoIds: ids, Itens: items };
}
function batchFor(sale, { omit = -1, deltaMultiplier = 1 } = {}) {
  const batch = writeBatch(db);
  batch.set(doc(db, 'Vendas', sale.id), sale);
  sale.Itens.forEach((item, index) => {
    if (index !== omit) batch.update(doc(db, 'Produtos', item.produtoId), {
      estoque_atual: increment(item.quantidade * (item.unidades_embalagem ?? 1) * (sale.tipo === 'SAIDA' ? -1 : 1) * deltaMultiplier),
      ultima_movimentacao: sale.id, indice_ultima_movimentacao: index,
    });
  });
  if (sale.receivable_cents > 0) batch.set(doc(db, 'ContasReceber', sale.id), {
    venda_id: sale.id, cliente_id: sale.cliente_id, original_cents: sale.receivable_cents, settled_cents: 0, ultimo_recebimento: '',
  });
  return batch;
}

function modern(sale = movement(), payments = { CASH: sale.total_cents }, customerId = '') {
  const p = { CASH: 0, PIX: 0, DEBIT: 0, CREDIT: 0, FIADO: 0, ...payments };
  const paid = p.CASH + p.PIX + p.DEBIT + p.CREDIT;
  return { ...sale, schema_version: 2, operador_id: 'cashier-a', operador_nome: 'caixa@loja.com',
    cliente_id: customerId, cliente_nome: customerId ? 'Cliente teste' : '', pagamentos: p,
    troco_cents: Math.max(0, paid - sale.total_cents), receivable_cents: p.FIADO,
    Itens: sale.Itens.map(item => ({ ...item, unidades_embalagem: item.unidades_embalagem ?? 1, imagem_snapshot: 'cerveja_lata' })),
  };
}

async function creditSale(amount = 1000) {
  const customerId = randomUUID();
  await setDoc(doc(db, 'Clientes', customerId), { id: customerId, nome: 'Cliente teste', telefone: '' });
  const sale = modern(movement(), { FIADO: amount }, customerId);
  await assertSucceeds(batchFor(sale).commit());
  return sale;
}

async function settle(database, sale, amount, id = randomUUID(), operatorId = 'cashier-a') {
  return runTransaction(database, async tx => {
    const receipt = doc(database, 'Recebimentos', id);
    if ((await tx.get(receipt)).exists()) return id;
    const account = doc(database, 'ContasReceber', sale.id);
    const debt = (await tx.get(account)).data();
    if (amount > debt.original_cents - debt.settled_cents) throw new Error('Saldo insuficiente');
    tx.set(receipt, { id, venda_id: sale.id, cliente_id: sale.cliente_id, dataHora: Timestamp.now(), amount_cents: amount,
      metodo: 'PIX', operador_id: operatorId, operador_nome: `${operatorId}@loja.com` });
    tx.update(account, { settled_cents: debt.settled_cents + amount, ultimo_recebimento: id });
    return id;
  });
}

before(async () => {
  environment = await initializeTestEnvironment({ projectId: 'demo-bebidas-pdv', firestore: {
    rules: await readFile(new URL('../../firestore.rules', import.meta.url), 'utf8'),
  }});
  db = environment.authenticatedContext('cashier-a', { pdv: true }).firestore();
});
beforeEach(async () => {
  await environment.clearFirestore();
  products = Array.from({ length: 9 }, () => randomUUID());
  await environment.withSecurityRulesDisabled(async context => {
    await Promise.all(products.map(id => setDoc(doc(context.firestore(), 'Produtos', id), product(id))));
  });
});
after(async () => { await environment?.cleanup(); });

test('requires an authenticated account with the PDV custom claim', async () => {
  const anonymous = environment.unauthenticatedContext().firestore();
  const unapproved = environment.authenticatedContext('unknown').firestore();
  await assertFails(getDoc(doc(anonymous, 'Produtos', products[0])));
  await assertFails(getDoc(doc(unapproved, 'Produtos', products[0])));
  await assertSucceeds(getDoc(doc(db, 'Produtos', products[0])));
});
test('creates products only with zero stock; metadata edits preserve stock', async () => {
  const id = randomUUID();
  await assertFails(setDoc(doc(db, 'Produtos', id), product(id, 50)));
  await assertSucceeds(setDoc(doc(db, 'Produtos', id), product(id, 0)));
  await assertSucceeds(updateDoc(doc(db, 'Produtos', id), { nome: 'Guaraná' }));
});
test('writes sale and exact stock decrement atomically', async () => {
  const sale = movement();
  await assertSucceeds(batchFor(sale).commit());
  assert.equal((await getDoc(doc(db, 'Produtos', products[0]))).data().estoque_atual, 8);
  assert.equal((await getDoc(doc(db, 'Vendas', sale.id))).data().profit_cents, 400);
});
test('rejects sale without product update and product update without sale', async () => {
  const sale = movement();
  await assertFails(setDoc(doc(db, 'Vendas', sale.id), sale));
  await assertFails(updateDoc(doc(db, 'Produtos', products[0]), { estoque_atual: increment(-2) }));
});
test('rejects partial batches with one missing product update and rolls back all writes', async () => {
  const sale = movement(products.slice(0, 2));
  await assertFails(batchFor(sale, { omit: 1 }).commit());
  assert.equal((await getDoc(doc(db, 'Produtos', products[0]))).data().estoque_atual, 10);
  assert.equal((await getDoc(doc(db, 'Vendas', sale.id))).exists(), false);
});
test('rejects a delta that does not equal the movement quantity', async () => {
  await assertFails(batchFor(movement(), { deltaMultiplier: 2 }).commit());
});
test('replaying the same UUID cannot decrement inventory twice', async () => {
  const sale = movement();
  await assertSucceeds(batchFor(sale).commit());
  await assertFails(batchFor(sale).commit());
  assert.equal((await getDoc(doc(db, 'Produtos', products[0]))).data().estoque_atual, 8);
});
test('cannot reuse an older receipt to authorize a later stock mutation', async () => {
  const sale = movement();
  await assertSucceeds(batchFor(sale).commit());
  await assertFails(updateDoc(doc(db, 'Produtos', products[0]), {
    estoque_atual: increment(-2), ultima_movimentacao: sale.id, indice_ultima_movimentacao: 0,
  }));
});
test('permits exactly eight distinct products within the rules access-call budget', async () => {
  await assertSucceeds(batchFor(movement(products.slice(0, 8))).commit());
});
test('rejects nine products and repeated product IDs', async () => {
  await assertFails(batchFor(movement(products)).commit());
  await assertFails(batchFor(movement([products[0], products[0]])).commit());
});
test('rejects malformed item fields, incorrect total, profit, and decimal mirrors', async () => {
  await assertFails(batchFor(movement([products[0]], 'SAIDA', 0)).commit());
  for (const patch of [{ total_cents: 1001, valorTotal: 10.01 }, { profit_cents: 401, lucroTotal: 4.01 }, { valorTotal: 999 }]) {
    await assertFails(batchFor({ ...movement(), ...patch }).commit());
  }
  // These leave totals valid, exercising item validation in the linked product update.
  for (const patch of [{ nome_snapshot: '' }, { custoUnitarioHistorico: 9 }, { precoUnitarioVendido: 9 }, { extra: true }]) {
    const sale = movement();
    sale.Itens[0] = { ...sale.Itens[0], ...patch };
    await assertFails(batchFor(sale).commit());
  }
});
test('stock entries increase stock at cost with zero profit', async () => {
  const entry = movement([products[0]], 'ENTRADA', 12);
  await assertSucceeds(batchFor(entry).commit());
  assert.equal((await getDoc(doc(db, 'Produtos', products[0]))).data().estoque_atual, 22);
  assert.equal((await getDoc(doc(db, 'Vendas', entry.id))).data().valorTotal, 36);
  await assertFails(batchFor({ ...movement([products[0]], 'ENTRADA'), lucroTotal: 1, profit_cents: 100 }).commit());
});
test('historic costs remain immutable after the current product cost changes', async () => {
  const sale = movement();
  await assertSucceeds(batchFor(sale).commit());
  await assertSucceeds(updateDoc(doc(db, 'Produtos', products[0]), { cost_cents: 400, preco_custo_atual: 4 }));
  const stored = (await getDoc(doc(db, 'Vendas', sale.id))).data();
  assert.equal(stored.Itens[0].historical_cost_cents, 300);
  assert.equal(stored.profit_cents, 400);
  await assertFails(updateDoc(doc(db, 'Vendas', sale.id), { profit_cents: 200, lucroTotal: 2 }));
  await assertFails(deleteDoc(doc(db, 'Vendas', sale.id)));
});
test('accepts a delayed offline sale with its historic cached cost', async () => {
  const offlineSale = movement();
  await assertSucceeds(updateDoc(doc(db, 'Produtos', products[0]), { cost_cents: 450, preco_custo_atual: 4.5 }));
  await assertSucceeds(batchFor(offlineSale).commit());
  assert.equal((await getDoc(doc(db, 'Vendas', offlineSale.id))).data().profit_cents, 400);
});
test('two devices use additive increments even when resulting inventory is negative', async () => {
  const other = environment.authenticatedContext('cashier-b', { pdv: true }).firestore();
  const first = movement([products[0]], 'SAIDA', 8);
  const second = movement([products[0]], 'SAIDA', 7);
  const secondBatch = writeBatch(other);
  secondBatch.set(doc(other, 'Vendas', second.id), second);
  secondBatch.update(doc(other, 'Produtos', products[0]), { estoque_atual: increment(-7),
    ultima_movimentacao: second.id, indice_ultima_movimentacao: 0 });
  await Promise.all([assertSucceeds(batchFor(first).commit()), assertSucceeds(secondBatch.commit())]);
  assert.equal((await getDoc(doc(db, 'Produtos', products[0]))).data().estoque_atual, -5);
});

test('modern eight-item split payment keeps the rules expression budget', async () => {
  const sale = movement(products.slice(0, 8));
  await assertSucceeds(batchFor(modern(sale, { CASH: 6000, PIX: 2000 })).commit());
});
test('eight-item credit sale creates its account in the same batch', async () => {
  const customerId = randomUUID();
  await setDoc(doc(db, 'Clientes', customerId), { id: customerId, nome: 'Cliente teste', telefone: '' });
  const sale = modern(movement(products.slice(0, 8)), { CASH: 4000, FIADO: 4000 }, customerId);
  await assertSucceeds(batchFor(sale).commit());
});
test('accepts cash change and rejects electronic overpayment, unpaid sale and forged operator', async () => {
  await assertSucceeds(batchFor(modern(movement(), { CASH: 2000, PIX: 200 })).commit());
  await assertFails(batchFor(modern(movement(), { PIX: 2000 })).commit());
  await assertFails(batchFor(modern(movement(), { CASH: 100 })).commit());
  await assertFails(batchFor({ ...modern(), operador_id: 'other-person' }).commit());
  await assertFails(batchFor(modern(movement(), { FIADO: 1000 })).commit());
});
test('selling two boxes uses base units and freezes the cost per box', async () => {
  await updateDoc(doc(db, 'Produtos', products[0]), { unidades_caixa: 6, preco_caixa_cents: 2700 });
  const sale = movement();
  sale.Itens[0] = { ...sale.Itens[0], unidades_embalagem: 6, sale_unit_cents: 2700, historical_cost_cents: 1800,
    precoUnitarioVendido: 27, custoUnitarioHistorico: 18 };
  Object.assign(sale, { total_cents: 5400, profit_cents: 1800, valorTotal: 54, lucroTotal: 18 });
  await assertSucceeds(batchFor(modern(sale)).commit());
  assert.equal((await getDoc(doc(db, 'Produtos', products[0]))).data().estoque_atual, -2);
});
test('catalog deletion preserves history and prevents stale device resurrection or sale', async () => {
  await assertSucceeds(updateDoc(doc(db, 'Produtos', products[0]), { ativo: false }));
  await assertFails(updateDoc(doc(db, 'Produtos', products[0]), { ativo: true }));
  await assertFails(updateDoc(doc(db, 'Produtos', products[0]), { nome: 'Stale edit' }));
  await assertFails(batchFor(movement()).commit());
  await assertFails(deleteDoc(doc(db, 'Produtos', products[0])));
});
test('receivables require atomic receipts and cannot be repaid twice with the same UUID', async () => {
  const sale = await creditSale();
  const id = randomUUID();
  await assertSucceeds(settle(db, sale, 400, id));
  await assertSucceeds(settle(db, sale, 400, id));
  assert.equal((await getDoc(doc(db, 'ContasReceber', sale.id))).data().settled_cents, 400);
  await assertFails(updateDoc(doc(db, 'ContasReceber', sale.id), { settled_cents: 1000 }));
  await assertFails(updateDoc(doc(db, 'Recebimentos', id), { amount_cents: 1 }));
});
test('two simultaneous operators cannot overpay the same debt', async () => {
  const sale = await creditSale();
  const other = environment.authenticatedContext('cashier-b', { pdv: true }).firestore();
  const outcomes = await Promise.allSettled([settle(db, sale, 700), settle(other, sale, 700, randomUUID(), 'cashier-b')]);
  assert.equal(outcomes.filter(it => it.status === 'fulfilled').length, 1);
  assert.equal((await getDoc(doc(db, 'ContasReceber', sale.id))).data().settled_cents, 700);
});
test('server rules reject a forged overpayment even without client validation', async () => {
  const sale = await creditSale();
  const id = randomUUID(); const batch = writeBatch(db);
  batch.set(doc(db, 'Recebimentos', id), { id, venda_id: sale.id, cliente_id: sale.cliente_id, dataHora: Timestamp.now(), amount_cents: 1001,
    metodo: 'CASH', operador_id: 'cashier-a', operador_nome: 'Caixa' });
  batch.update(doc(db, 'ContasReceber', sale.id), { settled_cents: 1001, ultimo_recebimento: id });
  await assertFails(batch.commit());
});
test('expenses are attributed to authenticated operator and immutable', async () => {
  const id = randomUUID(); const expense = { id, dataHora: Timestamp.now(), descricao: 'Aluguel setembro', categoria: 'Aluguel', amount_cents: 90000,
    operador_id: 'cashier-a', operador_nome: 'Caixa' };
  await assertSucceeds(setDoc(doc(db, 'Despesas', id), expense));
  await assertFails(updateDoc(doc(db, 'Despesas', id), { amount_cents: 1 }));
  const another = randomUUID();
  await assertFails(setDoc(doc(db, 'Despesas', another), { ...expense, id: another, operador_id: 'cashier-b' }));
});

const zeroMethods = () => ({ CASH: 0, PIX: 0, DEBIT: 0, CREDIT: 0 });
function profile(uid, role = 'operator', permissions = {}) {
  return { uid, name: 'Operador teste', email: `${uid}@loja.com`, role, active: true,
    permissions: { editPrices: false, deleteProducts: false, viewReports: false, reverseSales: false, exportData: false, ...permissions },
    createdAt: Timestamp.now(), updatedAt: Timestamp.now(), createdBy: 'admin', updatedBy: 'admin' };
}
async function seedProfile(uid = 'cashier-a', role = 'operator', permissions = {}) {
  const data = profile(uid, role, permissions);
  await environment.withSecurityRulesDisabled(context => setDoc(doc(context.firestore(), 'Perfis', uid), data));
  return data;
}
function openingBatch(database = db, deviceId = randomUUID(), amount = 10000, uid = 'cashier-a') {
  const id = randomUUID(); const now = Timestamp.now();
  const cash = { id, aparelho_id: deviceId, operador_id: uid, operador_nome: uid, dataHora: now, status: 'OPEN', opening_cents: amount,
    expected_cents: amount, cash_sales_cents: 0, cash_receipts_cents: 0, supply_cents: 0, withdrawal_cents: 0, refund_cents: 0,
    revision: 0, last_event_id: id, last_event_type: 'ABERTURA' };
  const batch = writeBatch(database);
  batch.set(doc(database, 'CaixaSessoes', id), cash);
  batch.set(doc(database, 'CaixasDispositivos', deviceId), { id: deviceId, active_session_id: id, operador_id: uid });
  batch.set(doc(database, 'MovimentosCaixa', id), { id, caixa_id: id, aparelho_id: deviceId, tipo: 'ABERTURA', dataHora: now,
    amount_cents: amount, motivo: '', operador_id: uid, operador_nome: uid });
  return { batch, cash };
}
async function openCash(database = db, deviceId = randomUUID(), uid = 'cashier-a') {
  const {batch, cash} = openingBatch(database, deviceId, 10000, uid); await assertSucceeds(batch.commit()); return cash;
}
function saleV3(cash, sale = movement(), payments = { CASH: sale.total_cents }, customerId = '') {
  return { ...modern(sale, payments, customerId), operador_id: cash.operador_id, schema_version: 3, caixa_id: cash.id,
    aparelho_id: cash.aparelho_id, due_epoch_day: 21000, credit_override_reason: '' };
}
function v3Batch(sale, database = db, options = {}) {
  const batch = writeBatch(database);
  batch.set(doc(database, 'Vendas', sale.id), sale);
  sale.Itens.forEach((item, index) => {
    if (options.omitProduct !== index) batch.update(doc(database, 'Produtos', item.produtoId), {
      estoque_atual: increment(-item.quantidade * (item.unidades_embalagem ?? 1)), ultima_movimentacao: sale.id, indice_ultima_movimentacao: index });
  });
  if (!options.omitSession) batch.update(doc(database, 'CaixaSessoes', sale.caixa_id), {
    expected_cents: increment(sale.pagamentos.CASH - sale.troco_cents), cash_sales_cents: increment(sale.pagamentos.CASH - sale.troco_cents),
    revision: increment(1), last_event_id: sale.id, last_event_type: 'VENDA' });
  if (sale.receivable_cents > 0) {
    batch.set(doc(database, 'ContasReceber', sale.id), { venda_id: sale.id, cliente_id: sale.cliente_id, original_cents: sale.receivable_cents,
      settled_cents: 0, ultimo_recebimento: '', settled_by_method_cents: zeroMethods(), closed: false, due_epoch_day: sale.due_epoch_day });
    if (!options.omitCustomer) batch.update(doc(database, 'Clientes', sale.cliente_id), { balance_cents: increment(sale.receivable_cents),
      last_credit_event_id: sale.id, last_credit_event_type: 'VENDA' });
  }
  return batch;
}
async function v3Customer(limit = 0, database = db) {
  const id = randomUUID();
  await environment.withSecurityRulesDisabled(context => setDoc(doc(context.firestore(), 'Clientes', id), {
    id, nome: 'Cliente teste', telefone: '11999998888', credit_limit_cents: limit, balance_cents: 0 }));
  return id;
}
async function receiptV3(sale, cash, amount, method = 'PIX', options = {}) {
  const database = options.database ?? db;
  const account = (await getDoc(doc(database, 'ContasReceber', sale.id))).data();
  const id = randomUUID();
  const before = sale.total_cents - sale.receivable_cents + account.settled_cents;
  const cost = sale.total_cents - sale.profit_cents;
  const data = { id, venda_id: sale.id, cliente_id: sale.cliente_id, dataHora: Timestamp.now(), amount_cents: amount, metodo: method,
    operador_id: cash.operador_id, operador_nome: cash.operador_id, caixa_id: cash.id, aparelho_id: cash.aparelho_id,
    paid_before_cents: before, cost_recognized_cents: Math.round(cost * (before + amount) / sale.total_cents) - Math.round(cost * before / sale.total_cents), ...options.patch };
  const batch = writeBatch(database);
  batch.set(doc(database, 'Recebimentos', id), data);
  batch.update(doc(database, 'ContasReceber', sale.id), { settled_cents: account.settled_cents + amount, ultimo_recebimento: id,
    settled_by_method_cents: { ...account.settled_by_method_cents, [method]: account.settled_by_method_cents[method] + amount },
    closed: account.settled_cents + amount === account.original_cents });
  if (!options.omitCustomer) batch.update(doc(database, 'Clientes', sale.cliente_id), { balance_cents: increment(-amount), last_credit_event_id: id, last_credit_event_type: 'RECEBIMENTO' });
  if (!options.omitSession) batch.update(doc(database, 'CaixaSessoes', cash.id), { expected_cents: increment(method === 'CASH' ? amount : 0),
    cash_receipts_cents: increment(method === 'CASH' ? amount : 0), revision: increment(1), last_event_id: id, last_event_type: 'RECEBIMENTO' });
  return { batch, data };
}
async function reversalBatch(sale, cash, options = {}) {
  const database = options.database ?? db;
  const account = sale.receivable_cents > 0 ? (await getDoc(doc(database, 'ContasReceber', sale.id))).data() : null;
  const settled = account?.settled_cents ?? 0;
  const methods = account?.settled_by_method_cents ?? zeroMethods();
  const refunds = { CASH: sale.pagamentos.CASH - sale.troco_cents + methods.CASH, PIX: sale.pagamentos.PIX + methods.PIX,
    DEBIT: sale.pagamentos.DEBIT + methods.DEBIT, CREDIT: sale.pagamentos.CREDIT + methods.CREDIT };
  const id = randomUUID();
  const data = { id, venda_id: sale.id, caixa_id: cash.id, aparelho_id: cash.aparelho_id, dataHora: Timestamp.now(), operador_id: cash.operador_id,
    operador_nome: cash.operador_id, motivo: 'Cliente desistiu da compra', refund_by_method_cents: refunds, expected_settled_cents: settled,
    cancelled_receivable_cents: sale.receivable_cents - settled, original_total_cents: sale.total_cents, original_profit_cents: sale.profit_cents, ...options.patch };
  const batch = writeBatch(database);
  batch.set(doc(database, 'Estornos', sale.id), data);
  sale.Itens.forEach((item, index) => {
    if (index !== options.omitProduct) batch.update(doc(database, 'Produtos', item.produtoId), {
      estoque_atual: increment(item.quantidade * (item.unidades_embalagem ?? 1)), ultimo_estorno: sale.id, indice_ultimo_estorno: index });
  });
  if (account) {
    batch.update(doc(database, 'ContasReceber', sale.id), { cancelled_cents: sale.receivable_cents - settled, estorno_id: sale.id, closed: true });
    // JS -0 serializes as a Firestore double; the Android writer uses Long(0).
    if (!options.omitCustomer) batch.update(doc(database, 'Clientes', sale.cliente_id), { balance_cents: increment(settled - sale.receivable_cents), last_credit_event_id: sale.id, last_credit_event_type: 'ESTORNO' });
  }
  if (!options.omitSession) batch.update(doc(database, 'CaixaSessoes', cash.id), { expected_cents: increment(0 - refunds.CASH), refund_cents: increment(refunds.CASH),
    revision: increment(1), last_event_id: sale.id, last_event_type: 'ESTORNO' });
  batch.set(doc(database, 'MovimentosCaixa', id), { id, caixa_id: cash.id, aparelho_id: cash.aparelho_id, tipo: 'ESTORNO', dataHora: data.dataHora,
    amount_cents: refunds.CASH, motivo: data.motivo, operador_id: cash.operador_id, operador_nome: cash.operador_id, venda_id: sale.id });
  return { batch, data };
}
function closeCashBatch(cash, counted, revision = cash.revision) {
  const id = randomUUID(); const now = Timestamp.now(); const batch = writeBatch(db);
  batch.update(doc(db, 'CaixaSessoes', cash.id), { status: 'CLOSED', closed_at: now, counted_cents: counted,
    difference_cents: counted - cash.expected_cents, revision: increment(1), last_event_id: id, last_event_type: 'FECHAMENTO' });
  batch.set(doc(db, 'CaixasDispositivos', cash.aparelho_id), { id: cash.aparelho_id, active_session_id: '', operador_id: '' });
  batch.set(doc(db, 'MovimentosCaixa', id), { id, caixa_id: cash.id, aparelho_id: cash.aparelho_id, tipo: 'FECHAMENTO', dataHora: now,
    amount_cents: counted, motivo: '', operador_id: cash.operador_id, operador_nome: cash.operador_id, expected_cents: cash.expected_cents, expected_revision: revision });
  return batch;
}

test('profile takes precedence over claim and unapproved users cannot self-bootstrap', async () => {
  const outsider = environment.authenticatedContext('unapproved').firestore();
  await assertSucceeds(getDoc(doc(outsider, 'Perfis', 'unapproved')));
  await assertFails(setDoc(doc(outsider, 'Perfis', 'unapproved'), profile('unapproved', 'admin')));
  await seedProfile();
  await assertSucceeds(getDoc(doc(db, 'Produtos', products[0])));
  await environment.withSecurityRulesDisabled(context => updateDoc(doc(context.firestore(), 'Perfis', 'cashier-a'), { active: false }));
  await assertFails(getDoc(doc(db, 'Produtos', products[0])));
});

test('operator permissions forbid price changes and old payload bypass', async () => {
  await seedProfile();
  await assertFails(updateDoc(doc(db, 'Produtos', products[0]), { sale_cents: 600, preco_venda_atual: 6 }));
  await assertFails(batchFor(modern()).commit());
  await assertFails(batchFor(movement()).commit());
  await assertFails(updateDoc(doc(db, 'Perfis', 'cashier-a'), { role: 'admin' }));
});

test('cash opening is atomic, unique per device and attributed to an operator', async () => {
  await seedProfile(); const cash = await openCash();
  const duplicate = openingBatch(db, cash.aparelho_id);
  await assertFails(duplicate.batch.commit());
  await assertFails(updateDoc(doc(db, 'CaixaSessoes', cash.id), { expected_cents: 999999 }));
  await assertFails(setDoc(doc(db, 'CaixasDispositivos', cash.aparelho_id), { id: cash.aparelho_id, active_session_id: '', operador_id: '' }));
});

test('eight item v3 split payment updates cash atomically within rules budgets', async () => {
  await seedProfile(); const cash = await openCash();
  const sale = saleV3(cash, movement(products.slice(0, 8)), { CASH: 6000, PIX: 2000 });
  await assertFails(v3Batch(sale, db, {omitSession: true}).commit());
  await assertSucceeds(v3Batch(sale).commit());
  const stored = (await getDoc(doc(db, 'CaixaSessoes', cash.id))).data();
  assert.equal(stored.expected_cents, 16000); assert.equal(stored.revision, 1);
});

test('eight item fiado v3 links debt, customer balance and zero cash delta', async () => {
  await seedProfile(); const cash = await openCash(); const customer = await v3Customer(9000);
  const sale = saleV3(cash, movement(products.slice(0, 8)), { FIADO: 8000 }, customer);
  await assertFails(v3Batch(sale, db, {omitCustomer: true}).commit());
  await assertSucceeds(v3Batch(sale).commit());
  assert.equal((await getDoc(doc(db, 'Clientes', customer))).data().balance_cents, 8000);
  assert.equal((await getDoc(doc(db, 'CaixaSessoes', cash.id))).data().expected_cents, 10000);
});

test('two devices cannot exceed the shared customer credit limit', async () => {
  await seedProfile(); const a = await openCash(); const b = await openCash(); const customer = await v3Customer(1500);
  const sale1 = saleV3(a, movement(), { FIADO: 1000 }, customer);
  const sale2 = saleV3(b, movement(), { FIADO: 1000 }, customer);
  const results = await Promise.allSettled([v3Batch(sale1).commit(), v3Batch(sale2).commit()]);
  assert.equal(results.filter(r => r.status === 'fulfilled').length, 1);
  assert.equal((await getDoc(doc(db, 'Clientes', customer))).data().balance_cents, 1000);
});

test('credit limit override needs an administrator and a reason', async () => {
  await seedProfile(); const cash = await openCash(); const customer = await v3Customer(500);
  let sale = { ...saleV3(cash, movement(), { FIADO: 1000 }, customer), credit_override_reason: 'Autorizado pelo dono' };
  await assertFails(v3Batch(sale).commit());
  await seedProfile('cashier-a','admin');
  await assertFails(v3Batch({ ...sale, credit_override_reason: '' }).commit());
  await assertSucceeds(v3Batch(sale).commit());
});

test('new receipts atomically recognize proportional cost, update balance and close fully paid debt', async () => {
  await seedProfile(); const cash = await openCash(); const customer = await v3Customer();
  const sale = saleV3(cash, movement(), { CASH: 200, FIADO: 800 }, customer); await v3Batch(sale).commit();
  const invalid = await receiptV3(sale, cash, 300, 'PIX', { patch: { cost_recognized_cents: 1 } });
  await assertFails(invalid.batch.commit());
  const partial = await receiptV3(sale, cash, 300); await assertSucceeds(partial.batch.commit());
  assert.equal(partial.data.cost_recognized_cents, 180);
  const last = await receiptV3(sale, cash, 500, 'CASH'); await assertSucceeds(last.batch.commit());
  assert.equal((await getDoc(doc(db, 'ContasReceber', sale.id))).data().closed, true);
  assert.equal((await getDoc(doc(db, 'Clientes', customer))).data().balance_cents, 0);
  assert.equal((await getDoc(doc(db, 'CaixaSessoes', cash.id))).data().expected_cents, 10700);
  const duplicate = await receiptV3(sale, cash, 1); await assertFails(duplicate.batch.commit());
});

test('receipts require customer and current cash session writes', async () => {
  await seedProfile(); const cash = await openCash(); const customer = await v3Customer();
  const sale = saleV3(cash, movement(), { FIADO: 1000 }, customer); await v3Batch(sale).commit();
  for (const options of [{ omitCustomer: true }, { omitSession: true }]) {
    const attempt = await receiptV3(sale, cash, 200, 'PIX', options); await assertFails(attempt.batch.commit());
  }
});

test('closing requires current revision, prevents later sales and preserves closed history', async () => {
  await seedProfile(); const cash = await openCash(); const sale = saleV3(cash); await v3Batch(sale).commit();
  await assertFails(closeCashBatch(cash, 10000).commit());
  const current = (await getDoc(doc(db, 'CaixaSessoes', cash.id))).data();
  await assertSucceeds(closeCashBatch(current, current.expected_cents - 50).commit());
  await assertFails(v3Batch(saleV3(cash)).commit());
  await assertFails(updateDoc(doc(db, 'CaixaSessoes', cash.id), { status: 'OPEN' }));
  await assertSucceeds(openingBatch(db,cash.aparelho_id).batch.commit());
});

test('full eight item reversal restores stock and refunds split payments exactly once', async () => {
  await seedProfile('cashier-a','admin'); const cash = await openCash();
  const sale = saleV3(cash, movement(products.slice(0,8)), { CASH: 3000, PIX: 5000 }); await v3Batch(sale).commit();
  const missing = await reversalBatch(sale,cash,{omitProduct:7}); await assertFails(missing.batch.commit());
  const reversed = await reversalBatch(sale,cash); await assertSucceeds(reversed.batch.commit());
  assert.equal((await getDoc(doc(db,'Produtos',products[0]))).data().estoque_atual,10);
  assert.equal((await getDoc(doc(db,'CaixaSessoes',cash.id))).data().expected_cents,10000);
  const duplicate = await reversalBatch(sale,cash); await assertFails(duplicate.batch.commit());
  await assertFails(deleteDoc(doc(db,'Vendas',sale.id)));
});

test('reversal cancels remaining credit and refunds prior receipts by their original method', async () => {
  await seedProfile('cashier-a','admin'); const cash = await openCash(); const customer = await v3Customer();
  const sale = saleV3(cash,movement(),{ CASH:200,FIADO:800 },customer); await v3Batch(sale).commit();
  const payment = await receiptV3(sale,cash,300,'PIX'); await payment.batch.commit();
  const wrong = await reversalBatch(sale,cash,{patch:{refund_by_method_cents:{ CASH:200, PIX:0, DEBIT:0, CREDIT:0 }}}); await assertFails(wrong.batch.commit());
  const reversed = await reversalBatch(sale,cash); await assertSucceeds(reversed.batch.commit());
  assert.equal(reversed.data.cancelled_receivable_cents,500);
  assert.equal(reversed.data.refund_by_method_cents.PIX,300);
  assert.equal((await getDoc(doc(db,'Clientes',customer))).data().balance_cents,0);
  const late = await receiptV3(sale,cash,100); await assertFails(late.batch.commit());
});

test('concurrent receipt invalidates a prepared reversal; restricted operators cannot reverse', async () => {
  await seedProfile('cashier-a','admin'); const cash=await openCash(); const customer=await v3Customer();
  const sale=saleV3(cash,movement(),{FIADO:1000},customer); await v3Batch(sale).commit();
  const prepared=await reversalBatch(sale,cash);
  const receipt=await receiptV3(sale,cash,200); await receipt.batch.commit();
  await assertFails(prepared.batch.commit());
  await seedProfile(); const unauthorized=await reversalBatch(sale,cash); await assertFails(unauthorized.batch.commit());
});

test('eight item fiado reversal validates refund and account links within access budgets', async () => {
  await seedProfile('cashier-a','admin'); const cash=await openCash(); const customer=await v3Customer();
  const sale=saleV3(cash,movement(products.slice(0,8)),{CASH:1000,FIADO:7000},customer); await v3Batch(sale).commit();
  const receipt=await receiptV3(sale,cash,1300,'PIX'); await receipt.batch.commit();
  const reversal=await reversalBatch(sale,cash); await assertSucceeds(reversal.batch.commit());
  assert.equal((await getDoc(doc(db,'ContasReceber',sale.id))).data().cancelled_cents,5700);
  assert.equal((await getDoc(doc(db,'Clientes',customer))).data().balance_cents,0);
});

test('reversal requires linked session/customer and can restore inactive products', async () => {
  await seedProfile('cashier-a','admin'); const cash=await openCash(); const customer=await v3Customer();
  const sale=saleV3(cash,movement(),{FIADO:1000},customer); await v3Batch(sale).commit();
  await updateDoc(doc(db,'Produtos',products[0]),{ativo:false});
  for(const options of [{omitSession:true},{omitCustomer:true}]) {
    const reversal=await reversalBatch(sale,cash,options); await assertFails(reversal.batch.commit());
  }
  const reversal=await reversalBatch(sale,cash); await assertSucceeds(reversal.batch.commit());
  assert.equal((await getDoc(doc(db,'Produtos',products[0]))).data().estoque_atual,10);
  assert.equal((await getDoc(doc(db,'Produtos',products[0]))).data().ativo,false);
});

test('fully settled fiado reversal refunds payments and preserves settled audit', async () => {
  await seedProfile('cashier-a','admin'); const cash=await openCash(); const customer=await v3Customer();
  const sale=saleV3(cash,movement(),{FIADO:1000},customer); await v3Batch(sale).commit();
  const receipt=await receiptV3(sale,cash,1000); await receipt.batch.commit();
  const reversal=await reversalBatch(sale,cash); await assertSucceeds(reversal.batch.commit());
  const debt=(await getDoc(doc(db,'ContasReceber',sale.id))).data();
  assert.equal(debt.settled_cents,1000); assert.equal(debt.cancelled_cents,0); assert.equal(debt.closed,true);
});

test('product category and package purchase fields support food and tobacco', async () => {
  await seedProfile('cashier-a','operator',{editPrices:true});
  await assertSucceeds(updateDoc(doc(db,'Produtos',products[0]),{categoria:'CIGARRO',unidade_venda:'MACO',custo_por_caixa:true,custo_caixa_cents:3000,unidades_caixa:10}));
  await assertFails(updateDoc(doc(db,'Produtos',products[0]),{categoria:'INVALIDO'}));
  await assertFails(updateDoc(doc(db,'Produtos',products[0]),{ativo:false}));
  await seedProfile('cashier-a','operator',{deleteProducts:true});
  await assertSucceeds(updateDoc(doc(db,'Produtos',products[0]),{ativo:false}));
  await assertFails(deleteDoc(doc(db,'Produtos',products[0])));
});

function manualBatch(cash, tipo, amount, reason='Movimento conferido') {
  const id=randomUUID(); const batch=writeBatch(db);
  batch.set(doc(db,'MovimentosCaixa',id),{id,caixa_id:cash.id,aparelho_id:cash.aparelho_id,tipo,dataHora:Timestamp.now(),amount_cents:amount,
    motivo:reason,operador_id:cash.operador_id,operador_nome:cash.operador_id});
  batch.update(doc(db,'CaixaSessoes',cash.id),{expected_cents:increment(tipo==='SANGRIA'?-amount:amount),
    [tipo==='SANGRIA'?'withdrawal_cents':'supply_cents']:increment(amount),revision:increment(1),last_event_id:id,last_event_type:tipo});
  return batch;
}
test('cash supplies and withdrawals require reasons, precise totals and available cash', async () => {
  await seedProfile(); const cash=await openCash();
  await assertFails(manualBatch(cash,'SANGRIA',10001).commit());
  await assertFails(manualBatch(cash,'SUPRIMENTO',100,'').commit());
  await assertSucceeds(manualBatch(cash,'SANGRIA',2000).commit());
  await assertSucceeds(manualBatch(cash,'SUPRIMENTO',500).commit());
  assert.equal((await getDoc(doc(db,'CaixaSessoes',cash.id))).data().expected_cents,8500);
});

test('operators cannot use another operators cash session or rewrite immutable events', async () => {
  await seedProfile(); const cash=await openCash();
  await seedProfile('cashier-b'); const other=environment.authenticatedContext('cashier-b',{pdv:true}).firestore();
  const sale={...saleV3(cash),operador_id:'cashier-b'}; await assertFails(v3Batch(sale,other).commit());
  await assertFails(updateDoc(doc(db,'MovimentosCaixa',cash.id),{amount_cents:20000}));
  await assertFails(deleteDoc(doc(db,'MovimentosCaixa',cash.id)));
});

test('v3 prevents malformed payment totals, invented profit and actor mismatch', async () => {
  await seedProfile(); const cash=await openCash();
  const patches=[{profit_cents:401,lucroTotal:4.01},{valorTotal:1000},{operador_id:'someone-else'},
    {pagamentos:{CASH:0,PIX:0,DEBIT:0,CREDIT:0,FIADO:0}}];
  for(const patch of patches) await assertFails(v3Batch({...saleV3(cash),...patch}).commit());
});

test('box purchase cost must match rounded unit cost and contain more than one unit', async () => {
  await seedProfile('cashier-a','admin');
  const ref = doc(db,'Produtos',products[0]);
  await assertFails(updateDoc(ref,{custo_por_caixa:true,custo_caixa_cents:300,unidades_caixa:1}));
  await assertFails(updateDoc(ref,{custo_por_caixa:true,custo_caixa_cents:1001,unidades_caixa:3}));
  await assertSucceeds(updateDoc(ref,{custo_por_caixa:true,custo_caixa_cents:1001,unidades_caixa:3,cost_cents:334,preco_custo_atual:3.34}));
  await assertSucceeds(updateDoc(ref,{custo_caixa_cents:1001,unidades_caixa:2,cost_cents:501,preco_custo_atual:5.01}));
});

test('administrator can create and deactivate profiles but cannot remove the own administrator', async () => {
  await seedProfile('cashier-a','admin');
  const ref = doc(db,'Perfis','new-operator');
  await assertSucceeds(setDoc(ref,{...profile('new-operator'), createdBy:'cashier-a',updatedBy:'cashier-a',
    createdAt:serverTimestamp(),updatedAt:serverTimestamp()}));
  await assertSucceeds(updateDoc(ref,{active:false,updatedAt:serverTimestamp(),updatedBy:'cashier-a'}));
  const own = doc(db,'Perfis','cashier-a');
  await assertFails(updateDoc(own,{active:false,updatedAt:serverTimestamp(),updatedBy:'cashier-a'}));
  await assertFails(updateDoc(own,{role:'operator',updatedAt:serverTimestamp(),updatedBy:'cashier-a'}));
  await assertFails(updateDoc(ref,{email:'different@example.com',updatedAt:serverTimestamp(),updatedBy:'cashier-a'}));
  await assertFails(deleteDoc(ref));
});

test('deactivated profile with a legacy claim cannot mutate cash or debt', async () => {
  await seedProfile('cashier-a','admin'); const cash=await openCash(); const customer=await v3Customer();
  const sale=saleV3(cash,movement(),{FIADO:1000},customer); await v3Batch(sale).commit();
  const receipt=await receiptV3(sale,cash,200); const reversal=await reversalBatch(sale,cash);
  await environment.withSecurityRulesDisabled(context => updateDoc(doc(context.firestore(),'Perfis','cashier-a'),{active:false}));
  await assertFails(receipt.batch.commit());
  await assertFails(reversal.batch.commit());
  await assertFails(manualBatch(cash,'SUPRIMENTO',500).commit());
});
