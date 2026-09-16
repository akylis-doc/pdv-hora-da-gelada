// Run only from a trusted administrator workstation. Never include admin credentials in Android.
import { applicationDefault, initializeApp } from 'firebase-admin/app';
import { getAuth } from 'firebase-admin/auth';

const [projectId, uid] = process.argv.slice(2);
if (!projectId || !uid) {
  throw new Error('Uso: node authorize-operator.mjs ID_DO_PROJETO UID_DO_OPERADOR');
}
initializeApp({ credential: applicationDefault(), projectId });
const user = await getAuth().getUser(uid);
await getAuth().setCustomUserClaims(uid, { ...user.customClaims, pdv: true });
console.log(`Operador ${uid} autorizado no projeto ${projectId}. Entre novamente no aplicativo.`);
