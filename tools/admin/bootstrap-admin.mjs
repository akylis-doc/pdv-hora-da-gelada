// Trusted workstation only. Never bundle service-account credentials with the Android app.
import { applicationDefault, initializeApp } from 'firebase-admin/app';
import { getAuth } from 'firebase-admin/auth';
import { FieldValue, getFirestore } from 'firebase-admin/firestore';

const args = process.argv.slice(2);
const apply = args.includes('--apply');
const positional = args.filter(value => value !== '--apply');
if (positional.length !== 3) {
  throw new Error('Uso: node bootstrap-admin.mjs ID_DO_PROJETO UID "NOME" [--apply]');
}
const [projectId, uid, inputName] = positional;
const name = inputName.trim();
if (!projectId.trim() || !uid.trim() || !name || name.length > 120) {
  throw new Error('Informe projeto, UID e nome com até 120 caracteres.');
}
initializeApp({ credential: applicationDefault(), projectId });
const user = await getAuth().getUser(uid);
if (user.disabled || !user.email) throw new Error('A conta precisa estar ativa e ter um e-mail no Firebase Authentication.');
const db = getFirestore();
const reference = db.collection('Perfis').doc(uid);
const admins = db.collection('Perfis').where('role', '==', 'admin').where('active', '==', true).limit(2);
const permissions = { editPrices: true, deleteProducts: true, viewReports: true, reverseSales: true, exportData: true };

const result = await db.runTransaction(async transaction => {
  const activeAdmins = await transaction.get(admins);
  const existing = await transaction.get(reference);
  if (activeAdmins.docs.some(document => document.id !== uid)) {
    throw new Error('Já existe outro administrador ativo. Use Operadores dentro do aplicativo para gerenciar perfis.');
  }
  if (existing.exists && existing.get('role') === 'admin' && existing.get('active') === true) {
    return 'Este UID já possui um perfil de administrador ativo. Nenhuma alteração foi necessária.';
  }
  if (existing.exists && (existing.get('uid') !== uid || existing.get('email') !== user.email)) {
    throw new Error('O UID ou o e-mail do perfil existente não confere com o Authentication. Corrija o cadastro antes de continuar.');
  }
  if (!apply) return 'Conferência concluída. Acrescente --apply ao mesmo comando para registrar este primeiro administrador.';
  if (existing.exists) {
    transaction.update(reference, { name, role: 'admin', active: true, permissions,
      updatedAt: FieldValue.serverTimestamp(), updatedBy: uid });
  } else {
    transaction.create(reference, { uid, name, email: user.email, role: 'admin', active: true, permissions,
      createdAt: FieldValue.serverTimestamp(), updatedAt: FieldValue.serverTimestamp(), createdBy: uid, updatedBy: uid });
  }
  return 'Primeiro administrador registrado. Abra o aplicativo conectado e escolha Conferir acesso novamente, se necessário.';
});
console.log(`Projeto: ${projectId}\nUID: ${uid}\nNome: ${name}\n${result}`);
