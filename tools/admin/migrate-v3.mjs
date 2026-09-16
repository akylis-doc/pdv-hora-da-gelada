// Run only from a trusted administrator workstation after both phones stop and synchronize.
import { applicationDefault, initializeApp } from 'firebase-admin/app';
import { getFirestore } from 'firebase-admin/firestore';
import { planMigration } from './migration-v3-planner.mjs';

const COLLECTIONS = { customers: 'Clientes', accounts: 'ContasReceber', sales: 'Vendas', receipts: 'Recebimentos', reversals: 'Estornos' };
const hasChanges = patch => Object.keys(patch).length > 0;
function assert(condition, message) { if (!condition) throw new Error(message); }
function sameSnapshot(expected, actual) {
  return expected?.exists === actual.exists && (!actual.exists || expected.updateTime.isEqual(actual.updateTime));
}

async function main() {
  const args = process.argv.slice(2);
  const apply = args.includes('--apply');
  const positional = args.filter(value => value !== '--apply');
  assert(positional.length === 1 && !positional[0].startsWith('--'),
    'Uso: node migrate-v3.mjs ID_DO_PROJETO [--apply]');
  const projectId = positional[0];
  initializeApp({ credential: applicationDefault(), projectId });
  const db = getFirestore();
  console.log(`Projeto: ${projectId}\nModo: ${apply ? 'APLICAR' : 'CONFERIR, sem gravações'}\nLendo o histórico para validar a conciliação.`);
  const snapshots = Object.fromEntries(await Promise.all(Object.entries(COLLECTIONS).map(async ([key, name]) => [key, await db.collection(name).get()])));
  const records = Object.fromEntries(Object.entries(snapshots).map(([key, snapshot]) => [key, snapshot.docs.map(document => ({ id: document.id, data: document.data() }))]));
  const originals = Object.fromEntries(Object.entries(snapshots).map(([key, snapshot]) => [key, new Map(snapshot.docs.map(document => [document.id, document]))]));
  const plan = planMigration(records);
  const counts = {
    customers: plan.customers.filter(customer => hasChanges(customer.patch)).length,
    accounts: plan.accounts.filter(account => hasChanges(account.patch)).length,
    receipts: plan.accounts.reduce((total, account) => total + account.receipts.filter(receipt => hasChanges(receipt.patch)).length, 0),
  };
  // One account is committed atomically, including its recognition snapshots. Fail before any writes if too large.
  for (const account of plan.accounts) {
    assert(account.receipts.filter(receipt => hasChanges(receipt.patch)).length + Number(hasChanges(account.patch)) <= 450,
      `ContasReceber/${account.id}: mais de 450 alterações na mesma conta. Requer migração assistida; nenhuma gravação foi iniciada.`);
  }
  console.log(`Histórico validado. Alterações necessárias: ${counts.customers} clientes, ${counts.accounts} contas e ${counts.receipts} recebimentos.`);
  if (!apply) {
    console.log('Nenhum dado alterado. Com os dois celulares sem operar e todas as gravações sincronizadas, repita o comando com --apply. Leia MIGRACAO-V3.md antes.');
    return;
  }
  let changed = 0;
  for (const account of plan.accounts) {
    const changedReceipts = account.receipts.filter(receipt => hasChanges(receipt.patch));
    if (!hasChanges(account.patch) && changedReceipts.length === 0) continue;
    await db.runTransaction(async transaction => {
      const accountRef = db.collection('ContasReceber').doc(account.id);
      const saleRef = db.collection('Vendas').doc(account.id);
      const customerRef = db.collection('Clientes').doc(account.customerId);
      const reversalRef = db.collection('Estornos').doc(account.id);
      const [currentAccount, sale, customer, reversal, receipts] = await Promise.all([
        transaction.get(accountRef), transaction.get(saleRef), transaction.get(customerRef), transaction.get(reversalRef),
        transaction.get(db.collection('Recebimentos').where('venda_id', '==', account.id)),
      ]);
      assert(sameSnapshot(originals.accounts.get(account.id), currentAccount) && sameSnapshot(originals.sales.get(account.id), sale) &&
        sameSnapshot(originals.customers.get(account.customerId), customer),
      `ContasReceber/${account.id}: dados mudaram durante a migração. Pare os celulares e execute a conferência novamente.`);
      const expectedReversal = originals.reversals.get(account.id);
      assert(expectedReversal ? sameSnapshot(expectedReversal, reversal) : !reversal.exists,
        `ContasReceber/${account.id}: o estorno mudou durante a migração.`);
      assert(receipts.size === account.receipts.length && receipts.docs.every(receipt => sameSnapshot(originals.receipts.get(receipt.id), receipt)),
        `ContasReceber/${account.id}: novos recebimentos ou alterações encontrados. Migração interrompida.`);
      if (hasChanges(account.patch)) transaction.update(accountRef, account.patch);
      for (const receipt of changedReceipts) transaction.update(db.collection('Recebimentos').doc(receipt.id), receipt.patch);
    });
    changed += Number(hasChanges(account.patch)) + changedReceipts.length;
  }
  for (const customer of plan.customers) {
    if (!hasChanges(customer.patch)) continue;
    await db.runTransaction(async transaction => {
      const reference = db.collection('Clientes').doc(customer.id);
      const [current, accounts] = await Promise.all([
        transaction.get(reference), transaction.get(db.collection('ContasReceber').where('cliente_id', '==', customer.id)),
      ]);
      assert(sameSnapshot(originals.customers.get(customer.id), current),
        `Clientes/${customer.id}: cadastro mudou durante a migração. Execute novamente a conferência.`);
      const ids = accounts.docs.map(account => account.id).sort();
      assert(JSON.stringify(ids) === JSON.stringify(customer.accountIds),
        `Clientes/${customer.id}: a lista de contas mudou durante a migração.`);
      const balance = accounts.docs.reduce((total, account) => total + account.get('original_cents') - account.get('settled_cents') - (account.get('cancelled_cents') ?? 0), 0);
      assert(Number.isSafeInteger(balance) && balance === customer.balanceCents,
        `Clientes/${customer.id}: o saldo mudou durante a migração. Nenhum valor foi sobrescrito.`);
      transaction.update(reference, customer.patch);
    });
    changed++;
  }
  console.log(`Migração concluída: ${changed} documentos complementados. IDs, valores originais, estoque, perfis e autenticação foram preservados.`);
  console.log('Execute novamente sem --apply para confirmar que não restam alterações. Mantenha os celulares parados até concluir a publicação das regras e a atualização dos dois aparelhos.');
}

main().catch(error => {
  console.error(`Migração interrompida: ${error.message}`);
  console.error('As transações já concluídas permanecem válidas. Confira a causa, mantenha os celulares parados e execute novamente a conferência; o script não duplica recebimentos.');
  process.exitCode = 1;
});
