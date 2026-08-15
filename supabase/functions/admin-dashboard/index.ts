const html = `<!doctype html>
<html lang="en">
<head>
<meta charset="utf-8" />
<meta name="viewport" content="width=device-width,initial-scale=1" />
<title>RefinePilot Admin</title>
<style>
:root{color-scheme:dark;font-family:Inter,system-ui,Segoe UI,Roboto,sans-serif;background:#0d1117;color:#f0f4f8}*{box-sizing:border-box}body{margin:0;background:linear-gradient(180deg,#0d1117,#111827);min-height:100vh}.wrap{max-width:1180px;margin:auto;padding:24px}.top{display:flex;align-items:center;justify-content:space-between;gap:16px;margin-bottom:22px}.brand{font-size:26px;font-weight:800}.sub{color:#94a3b8;font-size:13px}.card{background:#151b24;border:1px solid #273244;border-radius:16px;padding:18px;box-shadow:0 12px 35px rgba(0,0,0,.2)}.grid{display:grid;grid-template-columns:repeat(4,1fr);gap:12px;margin-bottom:18px}.stat b{font-size:28px;display:block}.stat span{color:#94a3b8;font-size:12px}.controls{display:grid;grid-template-columns:1.2fr .7fr .6fr 1.4fr auto;gap:10px;align-items:end}.field label{display:block;color:#94a3b8;font-size:12px;margin:0 0 6px}.field input,.field select,.field textarea{width:100%;background:#0f141c;color:#fff;border:1px solid #334155;border-radius:10px;padding:10px 12px;outline:none}.field textarea{height:42px;resize:vertical}.btn{border:0;border-radius:10px;padding:10px 14px;font-weight:700;cursor:pointer}.btn-primary{background:#22c55e;color:#05120a}.btn-amber{background:#f59e0b;color:#1f1300}.btn-red{background:#ef4444;color:#fff}.btn-dark{background:#263244;color:#fff}.btn:disabled{opacity:.5;cursor:not-allowed}.toolbar{display:flex;gap:10px;align-items:center;margin:18px 0}.toolbar input{flex:1;background:#0f141c;color:#fff;border:1px solid #334155;border-radius:10px;padding:11px 12px}.table-wrap{overflow:auto;border:1px solid #263244;border-radius:14px}table{width:100%;border-collapse:collapse;min-width:1000px;background:#111722}th,td{text-align:left;padding:12px;border-bottom:1px solid #263244;font-size:13px;vertical-align:top}th{color:#94a3b8;font-weight:700;background:#151b24;position:sticky;top:0}.key{font-family:ui-monospace,SFMono-Regular,Menlo,monospace;white-space:nowrap}.badge{display:inline-block;padding:4px 8px;border-radius:999px;font-weight:700;font-size:11px;text-transform:capitalize}.active{background:#123922;color:#86efac}.unused{background:#233047;color:#bfdbfe}.expired{background:#442414;color:#fdba74}.suspended{background:#4a3711;color:#fde68a}.revoked{background:#451a1a;color:#fca5a5}.actions{display:flex;gap:6px;flex-wrap:wrap}.actions button{padding:6px 8px;border-radius:8px;border:0;cursor:pointer;font-size:11px;font-weight:700}.muted{color:#94a3b8}.login{max-width:430px;margin:12vh auto}.login h1{margin:0 0 8px}.hidden{display:none!important}.result{margin-top:12px;padding:12px;border-radius:10px;background:#0f2a18;border:1px solid #1f6d39}.error{background:#351414;border-color:#7f1d1d}.modal{position:fixed;inset:0;background:rgba(0,0,0,.65);display:flex;align-items:center;justify-content:center;padding:20px}.modal .card{max-width:520px;width:100%}.generated{font-size:20px;font-family:ui-monospace,SFMono-Regular,Menlo,monospace;word-break:break-all;margin:14px 0}@media(max-width:800px){.grid{grid-template-columns:repeat(2,1fr)}.controls{grid-template-columns:1fr}.wrap{padding:14px}.top{align-items:flex-start;flex-direction:column}}
</style>
</head>
<body>
<div id="loginView" class="wrap login">
  <div class="card">
    <h1>🔐 RefinePilot Admin</h1>
    <p class="sub">Private seller dashboard</p>
    <div class="field"><label>Admin Key</label><input id="adminKey" type="password" autocomplete="off" placeholder="Enter your private admin key"></div>
    <button id="loginBtn" class="btn btn-primary" style="width:100%;margin-top:12px">Open Dashboard</button>
    <div id="loginMsg" class="sub" style="margin-top:10px"></div>
  </div>
</div>

<div id="appView" class="wrap hidden">
  <div class="top"><div><div class="brand">🛠 RefinePilot Admin</div><div class="sub">Generate and manage customer activation licenses</div></div><button id="logoutBtn" class="btn btn-dark">Lock Dashboard</button></div>

  <div class="grid">
    <div class="card stat"><b id="statTotal">0</b><span>Total Licenses</span></div>
    <div class="card stat"><b id="statActive">0</b><span>Active</span></div>
    <div class="card stat"><b id="statUnused">0</b><span>Unused</span></div>
    <div class="card stat"><b id="statSuspended">0</b><span>Suspended / Revoked</span></div>
  </div>

  <div class="card">
    <h3 style="margin-top:0">Generate Activation Key</h3>
    <div class="controls">
      <div class="field"><label>Customer name</label><input id="customerName" placeholder="Juan Dela Cruz"></div>
      <div class="field"><label>Plan</label><select id="plan"><option value="lifetime">Lifetime</option><option value="monthly">Monthly</option><option value="trial">Trial</option></select></div>
      <div class="field" id="durationField"><label>Days</label><input id="durationDays" type="number" min="1" max="3650" value="30"></div>
      <div class="field"><label>Customer note</label><input id="customerNote" placeholder="Payment reference / Messenger name"></div>
      <button id="generateBtn" class="btn btn-primary">Generate Key</button>
    </div>
    <div id="createMsg" class="sub" style="margin-top:10px"></div>
  </div>

  <div class="toolbar"><input id="search" placeholder="Search customer, key, plan or status..."><button id="refreshBtn" class="btn btn-dark">Refresh</button></div>
  <div class="table-wrap"><table><thead><tr><th>Customer</th><th>Activation Key</th><th>Plan</th><th>Status</th><th>Activation</th><th>Expiration</th><th>Devices</th><th>Actions</th></tr></thead><tbody id="rows"></tbody></table></div>
</div>

<div id="generatedModal" class="modal hidden"><div class="card"><h3>Activation Key Generated ✅</h3><div id="generatedCustomer" class="sub"></div><div id="generatedKey" class="generated"></div><div style="display:flex;gap:8px"><button id="copyGenerated" class="btn btn-primary">Copy Key</button><button id="closeGenerated" class="btn btn-dark">Close</button></div></div></div>

<script>
const API = location.origin + '/functions/v1/admin-api';
let adminKey = sessionStorage.getItem('rp_admin_key') || '';
let licenses = [];
const $ = id => document.getElementById(id);
const esc = s => String(s ?? '').replace(/[&<>"']/g, c => ({'&':'&amp;','<':'&lt;','>':'&gt;','"':'&quot;',"'":'&#39;'}[c]));
const fmt = v => v ? new Date(v).toLocaleString() : '—';
async function api(path, body={}){
  const r = await fetch(API + '/' + path,{method:'POST',headers:{'Content-Type':'application/json','x-admin-key':adminKey},body:JSON.stringify(body)});
  let j={}; try{j=await r.json()}catch{}
  if(r.status===401) throw new Error('Unauthorized admin key');
  if(!r.ok || !j.ok) throw new Error(j.code || 'Request failed');
  return j;
}
async function login(){
  adminKey=$('adminKey').value.trim(); $('loginMsg').textContent='Checking…';
  try{await api('list'); sessionStorage.setItem('rp_admin_key',adminKey); $('loginView').classList.add('hidden'); $('appView').classList.remove('hidden'); $('loginMsg').textContent=''; await load();}
  catch(e){$('loginMsg').textContent='Invalid Admin Key.'; adminKey='';}
}
async function load(){
  try{const j=await api('list',{search:$('search').value.trim()}); licenses=j.licenses||[]; render();}
  catch(e){if(String(e.message).includes('Unauthorized')) logout(); else alert('Unable to load licenses.');}
}
function render(){
  $('statTotal').textContent=licenses.length;
  $('statActive').textContent=licenses.filter(x=>x.status==='active').length;
  $('statUnused').textContent=licenses.filter(x=>x.status==='unused').length;
  $('statSuspended').textContent=licenses.filter(x=>x.status==='suspended'||x.status==='revoked').length;
  $('rows').innerHTML=licenses.map(x=>`<tr>
    <td><b>${esc(x.customer_name||'Unnamed')}</b>${x.customer_note?`<div class="muted">${esc(x.customer_note)}</div>`:''}</td>
    <td><span class="key">${esc(x.license_key||'Legacy key unavailable')}</span><div><button class="btn btn-dark" onclick="copyKey('${esc(x.license_key||'')}')" ${x.license_key?'':'disabled'}>Copy</button></div></td>
    <td style="text-transform:capitalize">${esc(x.license_type)}</td>
    <td><span class="badge ${esc(x.status)}">${esc(x.status)}</span></td>
    <td>${fmt(x.activated_at)}</td>
    <td>${x.license_type==='lifetime'?'Lifetime':fmt(x.expires_at)}</td>
    <td>${Number(x.device_count||0)} / ${Number(x.device_limit||1)}</td>
    <td><div class="actions">
      <button class="btn-dark" onclick="action('reset-device','${x.id}','Reset this device binding?')">Reset Device</button>
      <button class="btn-amber" onclick="action('suspend','${x.id}','Suspend this license?')" ${x.status==='revoked'?'disabled':''}>Suspend</button>
      <button class="btn-red" onclick="action('revoke','${x.id}','Permanently revoke this license?')" ${x.status==='revoked'?'disabled':''}>Revoke</button>
    </div></td>
  </tr>`).join('') || '<tr><td colspan="8" class="muted">No licenses found.</td></tr>';
}
window.copyKey = async key => { if(!key)return; await navigator.clipboard.writeText(key); };
window.action = async (path,id,msg) => { if(!confirm(msg))return; try{await api(path,{license_id:id}); await load();}catch(e){alert('Action failed.');} };
async function generate(){
  const plan=$('plan').value, name=$('customerName').value.trim(), note=$('customerNote').value.trim();
  $('generateBtn').disabled=true; $('createMsg').textContent='Generating…';
  try{
    const j=await api('create',{plan,customer_name:name,customer_note:note,device_limit:1,duration_days:Number($('durationDays').value||1)});
    $('generatedKey').textContent=j.license_key; $('generatedCustomer').textContent=(name||'Unnamed customer')+' • '+plan;
    $('generatedModal').classList.remove('hidden'); $('customerName').value=''; $('customerNote').value=''; $('createMsg').textContent=''; await load();
  }catch(e){$('createMsg').textContent='Unable to generate key.';} finally{$('generateBtn').disabled=false;}
}
function planChanged(){ const p=$('plan').value; $('durationField').style.visibility=p==='lifetime'?'hidden':'visible'; $('durationDays').value=p==='trial'?'7':'30'; }
function logout(){sessionStorage.removeItem('rp_admin_key');adminKey='';$('appView').classList.add('hidden');$('loginView').classList.remove('hidden');$('adminKey').value='';}
$('loginBtn').onclick=login; $('adminKey').addEventListener('keydown',e=>{if(e.key==='Enter')login()}); $('logoutBtn').onclick=logout; $('generateBtn').onclick=generate; $('plan').onchange=planChanged; $('refreshBtn').onclick=load; $('copyGenerated').onclick=()=>navigator.clipboard.writeText($('generatedKey').textContent); $('closeGenerated').onclick=()=>$('generatedModal').classList.add('hidden');
let timer; $('search').addEventListener('input',()=>{clearTimeout(timer);timer=setTimeout(load,250)}); planChanged();
if(adminKey){$('loginView').classList.add('hidden');$('appView').classList.remove('hidden');load();}
</script>
</body></html>`

Deno.serve((req) => {
  if (req.method !== 'GET') return new Response('Method Not Allowed', { status: 405 })
  return new Response(html, {
    headers: {
      'Content-Type': 'text/html; charset=utf-8',
      'Cache-Control': 'no-store',
      'X-Content-Type-Options': 'nosniff',
      'Referrer-Policy': 'no-referrer',
      'X-Frame-Options': 'DENY',
      'Content-Security-Policy': "default-src 'self'; style-src 'unsafe-inline'; script-src 'unsafe-inline'; connect-src 'self'; frame-ancestors 'none'; base-uri 'none'; form-action 'none'",
    },
  })
})
