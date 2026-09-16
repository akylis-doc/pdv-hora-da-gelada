// Pure, offline migration calculations. No Firebase client or network calls in this module.
const METHODS = ['CASH', 'PIX', 'DEBIT', 'CREDIT'];
const own = (object, key) => Object.prototype.hasOwnProperty.call(object, key);
function assert(condition, message) { if (!condition) throw new Error(message); }
function integer(value, label, minimum = 0) {
  assert(Number.isSafeInteger(value) && value >= minimum, `${label}: inteiro inválido.`);
  return value;
}
function sum(values, label) { return integer(values.reduce((total, value) => total + value, 0), label); }
function optionalInteger(data, field, label) { return own(data, field) ? integer(data[field], label) : 0; }
function same(left, right) {
  if (left && right && typeof left === 'object' && typeof right === 'object') {
    const keys = Object.keys(left).sort();
    return keys.join('|') === Object.keys(right).sort().join('|') && keys.every(key => same(left[key], right[key]));
  }
  return left === right;
}
function difference(data, expected) {
  return Object.fromEntries(Object.entries(expected).filter(([field, value]) => !same(data[field], value)));
}
function mapRecords(records, label) {
  const mapped = new Map();
  for (const record of records) {
    assert(typeof record.id === 'string' && record.id.length > 0 && !mapped.has(record.id), `${label}: identificador ausente ou repetido.`);
    mapped.set(record.id, record);
  }
  return mapped;
}

/** Exact HALF_UP conversion of the decimal representation used by the legacy Double mapper. */
export function decimalCents(value) {
  assert(typeof value === 'number' && Number.isFinite(value), 'Valor monetário antigo inválido.');
  const match = /^(-?)(\d+)(?:\.(\d+))?(?:e([+-]?\d+))?$/i.exec(String(value));
  assert(match !== null, 'Formato monetário antigo inválido.');
  const digits = BigInt(match[2] + (match[3] ?? ''));
  const exponent = Number(match[4] ?? 0) - (match[3]?.length ?? 0) + 2;
  const magnitude = exponent >= 0 ? digits * (10n ** BigInt(exponent))
    : (digits * 2n + 10n ** BigInt(-exponent)) / (2n * 10n ** BigInt(-exponent));
  const result = Number(match[1] ? -magnitude : magnitude);
  assert(Number.isSafeInteger(result), 'Valor monetário antigo fora do intervalo seguro.');
  return result;
}

function cents(data, current, legacy, label, minimum = 0) {
  return integer(own(data, current) ? data[current] : decimalCents(data[legacy]), label, minimum);
}

export function allocatedCost(costCents, paidCents, totalCents) {
  integer(costCents, 'Custo'); integer(paidCents, 'Recebido'); integer(totalCents, 'Total da venda', 1);
  assert(paidCents <= totalCents, 'Recebimento maior que o total da venda.');
  return Number((2n * BigInt(costCents) * BigInt(paidCents) + BigInt(totalCents)) / (2n * BigInt(totalCents)));
}

function chronological(left, right) {
  return left.data.dataHora.seconds - right.data.dataHora.seconds ||
    left.data.dataHora.nanoseconds - right.data.dataHora.nanoseconds ||
    (left.id < right.id ? -1 : left.id > right.id ? 1 : 0);
}

/** Existing paid_before is the authoritative transaction order, even with skewed device clocks. */
function orderedReceipts(receipts, initialPaid) {
  const known = receipts.filter(receipt => own(receipt.data, 'paid_before_cents'))
    .sort((left, right) => left.data.paid_before_cents - right.data.paid_before_cents || chronological(left, right));
  const legacy = receipts.filter(receipt => !own(receipt.data, 'paid_before_cents')).sort(chronological);
  const ordered = [];
  let paid = initialPaid;
  const append = receipt => { ordered.push(receipt); paid = sum([paid, receipt.data.amount_cents], 'Total recebido'); };
  for (const receipt of known) {
    const target = integer(receipt.data.paid_before_cents, `Recebimentos/${receipt.id}: recebido anterior`);
    while (paid < target && legacy.length) {
      const next = legacy.shift();
      assert(paid + next.data.amount_cents <= target, `Recebimentos/${receipt.id}: lacuna incompatível na ordem dos recebimentos.`);
      append(next);
    }
    assert(paid === target, `Recebimentos/${receipt.id}: recebimentos sobrepostos ou faltantes.`);
    append(receipt);
  }
  legacy.forEach(append);
  return ordered;
}

export function planMigration({ customers, accounts, sales, receipts, reversals }) {
  const customerMap = mapRecords(customers, 'Clientes');
  const accountMap = mapRecords(accounts, 'ContasReceber');
  const saleMap = mapRecords(sales, 'Vendas');
  const reversalMap = mapRecords(reversals, 'Estornos');
  mapRecords(receipts, 'Recebimentos');
  const grouped = new Map();
  for (const receipt of receipts) {
    const data = receipt.data;
    const account = accountMap.get(data.venda_id);
    assert(account !== undefined, `Recebimentos/${receipt.id}: conta a receber ausente.`);
    assert(data.id === receipt.id && data.cliente_id === account.data.cliente_id,
      `Recebimentos/${receipt.id}: identificador ou cliente divergente.`);
    integer(data.amount_cents, `Recebimentos/${receipt.id}: valor`, 1);
    assert(METHODS.includes(data.metodo), `Recebimentos/${receipt.id}: forma de pagamento inválida.`);
    assert(Number.isSafeInteger(data.dataHora?.seconds) && Number.isSafeInteger(data.dataHora?.nanoseconds) &&
      data.dataHora.nanoseconds >= 0 && data.dataHora.nanoseconds < 1_000_000_000,
    `Recebimentos/${receipt.id}: data ausente ou inválida.`);
    if (!grouped.has(data.venda_id)) grouped.set(data.venda_id, []);
    grouped.get(data.venda_id).push(receipt);
  }
  for (const sale of sales) {
    if ((sale.data.receivable_cents ?? 0) > 0) {
      assert(accountMap.has(sale.id), `Vendas/${sale.id}: venda fiada sem conta a receber.`);
    }
  }
  for (const reversal of reversals) {
    assert(reversal.data.venda_id === reversal.id && saleMap.has(reversal.id), `Estornos/${reversal.id}: venda original ausente ou divergente.`);
  }
  const balances = new Map(customers.map(customer => [customer.id, 0]));
  const accountPlans = [];
  for (const account of accounts) {
    const data = account.data;
    const label = `ContasReceber/${account.id}`;
    const sale = saleMap.get(account.id);
    assert(data.venda_id === account.id && sale?.data.tipo === 'SAIDA', `${label}: venda original ausente ou inválida.`);
    assert(customerMap.has(data.cliente_id) && sale.data.cliente_id === data.cliente_id, `${label}: cliente ausente ou divergente.`);
    const original = integer(data.original_cents, `${label}: valor original`, 1);
    const settled = integer(data.settled_cents, `${label}: valor recebido`);
    assert(original === sale.data.receivable_cents && settled <= original, `${label}: saldo incompatível com a venda.`);
    const total = cents(sale.data, 'total_cents', 'valorTotal', `${label}: total da venda`, 1);
    const profit = cents(sale.data, 'profit_cents', 'lucroTotal', `${label}: lucro histórico`, -Number.MAX_SAFE_INTEGER);
    const cost = integer(total - profit, `${label}: CMV histórico`);
    assert(original <= total && Array.isArray(sale.data.Itens) && sale.data.Itens.length > 0, `${label}: itens ou total inválidos.`);
    const itemCost = sum(sale.data.Itens.map(item => integer(cents(item, 'historical_cost_cents', 'custoUnitarioHistorico', `${label}: custo do item`) *
      integer(item.quantidade, `${label}: quantidade`, 1), `${label}: CMV do item`)), `${label}: CMV dos itens`);
    assert(itemCost === cost, `${label}: CMV dos itens difere do custo histórico da venda.`);
    const initialPaid = total - original;
    const payments = grouped.get(account.id) ?? [];
    const byMethod = Object.fromEntries(METHODS.map(method => [method, sum(payments.filter(receipt => receipt.data.metodo === method)
      .map(receipt => receipt.data.amount_cents), `${label}: recebido em ${method}`)]));
    assert(sum(Object.values(byMethod), `${label}: soma dos recebimentos`) === settled,
      `${label}: comprovantes de recebimento não somam o valor baixado. Nenhuma correção financeira automática é permitida.`);
    if (own(data, 'settled_by_method_cents')) {
      assert(same(data.settled_by_method_cents, byMethod), `${label}: resumo existente por forma de pagamento divergente.`);
    }
    const reversal = reversalMap.get(account.id);
    const cancelled = reversal ? integer(reversal.data.cancelled_receivable_cents, `${label}: saldo cancelado`) : 0;
    if (reversal) {
      assert(reversal.data.expected_settled_cents === settled && cancelled === original - settled,
        `${label}: estorno incompatível com os recebimentos.`);
    }
    if (own(data, 'cancelled_cents')) assert(data.cancelled_cents === cancelled, `${label}: saldo cancelado divergente.`);
    if (data.estorno_id) assert(data.estorno_id === reversal?.id, `${label}: referência de estorno inválida.`);
    const remaining = original - settled - cancelled;
    balances.set(data.cliente_id, sum([balances.get(data.cliente_id), remaining], `${label}: saldo do cliente`));
    const receiptPlans = [];
    let paidBefore = initialPaid;
    for (const receipt of orderedReceipts(payments, initialPaid)) {
      const after = sum([paidBefore, receipt.data.amount_cents], `${label}: recebido acumulado`);
      const recognized = allocatedCost(cost, after, total) - allocatedCost(cost, paidBefore, total);
      if (own(receipt.data, 'cost_recognized_cents')) {
        assert(receipt.data.cost_recognized_cents === recognized, `Recebimentos/${receipt.id}: CMV já registrado diverge do histórico.`);
      }
      receiptPlans.push({ id: receipt.id, patch: difference(receipt.data, { paid_before_cents: paidBefore, cost_recognized_cents: recognized }) });
      paidBefore = after;
    }
    const expected = { settled_by_method_cents: byMethod, closed: remaining === 0,
      cancelled_cents: cancelled, due_epoch_day: optionalInteger(data, 'due_epoch_day', `${label}: vencimento`) };
    if (reversal) expected.estorno_id = account.id;
    accountPlans.push({ id: account.id, customerId: data.cliente_id, remainingCents: remaining,
      patch: difference(data, expected), receipts: receiptPlans });
  }
  const customerPlans = customers.map(customer => {
    const balance = balances.get(customer.id);
    if (own(customer.data, 'balance_cents')) {
      assert(customer.data.balance_cents === balance, `Clientes/${customer.id}: saldo agregado existente difere das contas. Confira a conciliação antes de migrar.`);
    }
    const limit = optionalInteger(customer.data, 'credit_limit_cents', `Clientes/${customer.id}: limite de crédito`);
    return { id: customer.id, balanceCents: balance, accountIds: accountPlans.filter(account => account.customerId === customer.id).map(account => account.id).sort(),
      patch: difference(customer.data, { credit_limit_cents: limit, balance_cents: balance }) };
  });
  return { accounts: accountPlans, customers: customerPlans };
}
